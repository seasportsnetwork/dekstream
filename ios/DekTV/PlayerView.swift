import SwiftUI
import AVKit

/// Presents the player full screen from anywhere.
@MainActor
final class Router: ObservableObject {
    static let shared = Router()
    @Published var playing: PlayRequest?
    func play(_ r: PlayRequest) {
        if let c = r.channel { AppStore.shared.watched(c) }
        playing = r
    }
}

/// Holds the AVPlayer, saves resume points, and switches channels for live TV.
@MainActor
final class PlayerModel: ObservableObject {
    let player = AVPlayer()
    @Published var request: PlayRequest
    @Published var error: String?
    @Published var buffering = true
    private var timeObserver: Any?
    private var statusObs: NSKeyValueObservation?
    private var bufferObs: NSKeyValueObservation?
    private var failObs: NSObjectProtocol?

    init(_ r: PlayRequest) {
        request = r
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        load(r)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.saveResume() }
        }
    }

    func load(_ r: PlayRequest) {
        request = r
        error = nil
        buffering = true
        guard let url = URL(string: r.url) else { error = "This stream has no valid link."; return }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": "DekTV iOS"]]))
        item.preferredForwardBufferDuration = r.isLive ? 6 : 0
        statusObs = item.observe(\.status) { [weak self] it, _ in
            Task { @MainActor in
                guard let self else { return }
                if it.status == .failed { self.error = Self.explain(it.error) }
                if it.status == .readyToPlay, !r.isLive, r.startAt > 5 {
                    await self.player.seek(to: CMTime(seconds: r.startAt, preferredTimescale: 1))
                }
            }
        }
        bufferObs = player.observe(\.timeControlStatus) { [weak self] p, _ in
            Task { @MainActor in self?.buffering = p.timeControlStatus == .waitingToPlayAtSpecifiedRate }
        }
        if let f = failObs { NotificationCenter.default.removeObserver(f) }
        failObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] n in
            let e = n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor in self?.error = Self.explain(e) }
        }
        player.replaceCurrentItem(with: item)
        player.play()
    }

    static func explain(_ e: Error?) -> String {
        let ns = e as NSError?
        let text = (ns?.localizedDescription ?? "").lowercased()
        if text.contains("403") || text.contains("forbidden") { return "Your IPTV connection is busy. Another device may be using it." }
        if text.contains("401") { return "Your provider rejected the login. Check the playlist in Settings." }
        if text.contains("404") { return "This isn't available from your provider right now." }
        if ns?.domain == NSURLErrorDomain { return "Can't reach your provider. Check the internet connection." }
        if text.contains("cannot open") || text.contains("unsupported") || ns?.code == -11828 || ns?.code == -11829 {
            return "iPhone can't play this stream's format."
        }
        return ns?.localizedDescription ?? "Playback problem."
    }

    func saveResume() {
        guard let key = request.resumeKey, let item = player.currentItem else { return }
        let pos = player.currentTime().seconds, dur = item.duration.seconds
        guard pos.isFinite, pos > 0 else { return }
        AppStore.shared.saveResume(ResumePoint(key: key, title: request.title, subtitle: request.subtitle, poster: request.poster, url: request.url,
                                               position: pos, duration: dur.isFinite ? dur : 0, updatedAt: Date().timeIntervalSince1970))
    }

    func zap(_ step: Int, catalog: Catalog) {
        guard let c = request.channel else { return }
        let list = catalog.channels.filter { $0.categoryId == c.categoryId }
        guard let i = list.firstIndex(of: c), !list.isEmpty else { return }
        let next = list[(i + step + list.count) % list.count]
        AppStore.shared.watched(next)
        load(catalog.liveRequest(next))
    }

    func stop() {
        saveResume()
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let t = timeObserver { player.removeTimeObserver(t); timeObserver = nil }
    }
}

struct AVPlayerControllerView: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = false
        vc.videoGravity = .resizeAspect
        return vc
    }
    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}
}

struct PlayerScreen: View {
    @StateObject private var model: PlayerModel
    @EnvironmentObject var catalog: Catalog
    @Environment(\.dismiss) private var dismiss
    @State private var showChannels = false

    init(request: PlayRequest) { _model = StateObject(wrappedValue: PlayerModel(request)) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            AVPlayerControllerView(player: model.player).ignoresSafeArea()
            if model.buffering && model.error == nil { ProgressView().tint(.white).scaleEffect(1.3) }
            if let e = model.error {
                VStack(spacing: 14) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
                    Text(e).multilineTextAlignment(.center).foregroundStyle(.white)
                    Button("Try again") { model.load(model.request) }.buttonStyle(.borderedProminent).tint(DS.accent)
                }
                .padding(24).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18)).padding(40)
            }
            VStack {
                HStack(alignment: .top) {
                    Button { model.stop(); dismiss() } label: {
                        Image(systemName: "xmark").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.request.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                        if let s = model.request.subtitle { Text(s).font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                    }
                    .padding(.top, 2)
                    Spacer()
                    if model.request.isLive {
                        HStack(spacing: 8) {
                            ctrl("chevron.up") { model.zap(-1, catalog: catalog) }
                            ctrl("chevron.down") { model.zap(1, catalog: catalog) }
                            ctrl("list.bullet") { showChannels = true }
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.top, 8)
                Spacer()
            }
        }
        .statusBarHidden()
        .onDisappear { model.stop() }
        .sheet(isPresented: $showChannels) {
            if let c = model.request.channel {
                NavigationStack {
                    List(catalog.channels(in: c.categoryId)) { ch in
                        Button { model.load(catalog.liveRequest(ch)); AppStore.shared.watched(ch); showChannels = false } label: { ChannelRow(channel: ch) }
                            .listRowBackground(ch == c ? DS.accent.opacity(0.18) : Color.clear)
                    }
                    .navigationTitle(catalog.liveCats.first { $0.id == c.categoryId }?.name ?? "Channels")
                    .navigationBarTitleDisplayMode(.inline)
                }
                .presentationDetents([.medium, .large])
            }
        }
    }

    private func ctrl(_ icon: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Image(systemName: icon).font(.headline).foregroundStyle(.white).frame(width: 40, height: 40).background(.ultraThinMaterial, in: Circle())
        }
    }
}
