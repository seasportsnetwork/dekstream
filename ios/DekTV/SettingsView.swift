import SwiftUI
import AVFoundation

struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var catalog: Catalog
    @Environment(\.dismiss) private var dismiss
    @State private var editing: Source?
    @State private var adding = false
    @State private var copying = false
    @State private var keyStatus: String?
    @State private var pickLeagues = false

    var body: some View {
        Form {
            Section("Playlists") {
                ForEach(store.sources) { s in
                    HStack {
                        Button { store.activeId = s.id } label: {
                            HStack {
                                Image(systemName: store.active?.id == s.id ? "checkmark.circle.fill" : "circle").foregroundStyle(DS.accent)
                                VStack(alignment: .leading) {
                                    Text(s.name).foregroundStyle(.primary)
                                    Text(s.type.label).font(.caption).foregroundStyle(DS.dim)
                                }
                            }
                        }
                        Spacer()
                        Button { editing = s } label: { Image(systemName: "pencil") }.buttonStyle(.borderless)
                    }
                }
                .onDelete { idx in idx.map { store.sources[$0] }.forEach(store.remove) }
                Button { adding = true } label: { Label("Add playlist", systemImage: "plus") }
                Button { copying = true } label: { Label("Copy from another device", systemImage: "qrcode.viewfinder") }
                Button { Task { await catalog.ensure(store.active, force: true) } } label: { Label("Reload playlist", systemImage: "arrow.clockwise") }
            }

            Section {
                SecureField("TMDB API key or Read Access Token", text: $store.tmdbKey)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Test key") {
                    keyStatus = "Checking…"
                    Task { keyStatus = await TMDB.test(store.tmdbKey) ? "✓ Key works" : "✗ TMDB didn't accept that key" }
                }
                if let s = keyStatus { Text(s).font(.footnote) }
            } header: { Text("Movie & show details") } footer: {
                Text("A free TMDB key adds cast photos, trailers, reviews, genre pages and Top 10 this week. Get one at themoviedb.org › Settings › API.")
            }

            Section("Home & sports") {
                Toggle("Movies & series on Home", isOn: $store.showMoviesOnHome)
                Button("Leagues on Home and Sports") { pickLeagues = true }
            }

            Section("Appearance & content") {
                Toggle("Light mode", isOn: $store.lightMode)
                Toggle("Show adult content", isOn: $store.showAdult)
            }

            Section {
                LabeledContent("Version", value: (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0")
            } footer: { Text("DekTV for iPhone. New versions are published on GitHub as DekTV.ipa.") }
        }
        .navigationTitle("Settings")
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        .sheet(item: $editing) { s in NavigationStack { SourceEditView(source: s) } }
        .sheet(isPresented: $adding) { NavigationStack { SourceEditView(source: nil) } }
        .sheet(isPresented: $copying) { NavigationStack { CopySetupView() } }
        .sheet(isPresented: $pickLeagues) { NavigationStack { LeaguePicker() } }
    }
}

struct SourceEditView: View {
    let source: Source?
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var catalog: Catalog
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var type: SourceType = .xtream
    @State private var url = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var epg = ""
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        Form {
            Picker("Type", selection: $type) { ForEach(SourceType.allCases) { Text($0.label).tag($0) } }
            TextField("Name (optional)", text: $name)
            TextField(type == .xtream ? "Server URL (http://host:port)" : "Playlist link (.m3u)", text: $url)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            if type == .xtream {
                TextField("Username", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Password", text: $pass)
            }
            TextField("Guide (XMLTV) link, optional", text: $epg).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            if let e = error { Text(e).foregroundStyle(DS.live).font(.footnote) }
        }
        .navigationTitle(source == nil ? "Add playlist" : "Edit playlist")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                if checking { ProgressView() } else { Button("Save") { Task { await save() } }.disabled(url.count < 8) }
            }
        }
        .onAppear {
            guard let s = source else { return }
            name = s.name; type = s.type; url = s.url; user = s.username; pass = s.password; epg = s.epgUrl
        }
    }

    private func save() async {
        var s = source ?? Source(name: "", type: type, url: "")
        s.name = name.nilIfBlank ?? (type == .xtream ? (URL(string: url)?.host ?? "My IPTV") : "My playlist")
        s.type = type; s.url = url.trimmingCharacters(in: .whitespaces); s.username = user.trimmingCharacters(in: .whitespaces); s.password = pass; s.epgUrl = epg
        checking = true; error = nil
        if type == .xtream {
            do { try await XtreamProvider(s).login() } catch { self.error = error.localizedDescription; checking = false; return }
        }
        checking = false
        store.addOrUpdate(s)
        store.activeId = s.id
        await catalog.ensure(s, force: true)
        dismiss()
    }
}

/// Copy the setup from another DekTV device: scan its Sync QR code, or type its 10-digit code.
struct CopySetupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = true
    @State private var address = ""
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var result: SetupSync.Imported?

    var body: some View {
        Form {
            if let r = result {
                Section("From \(r.from)") {
                    Text("\(r.sources.count) playlist\(r.sources.count == 1 ? "" : "s") · \(r.favorites.values.reduce(0) { $0 + $1.count }) favorite channels")
                    ForEach(r.sources) { s in Text(s.name).foregroundStyle(DS.dim) }
                    Button("Add to this iPhone") { SetupSync.apply(r); dismiss() }.bold()
                }
            } else {
                Section {
                    Text("On your Shield or tablet, open Settings › Sync › Send from this device. Then scan its QR code here.").font(.subheadline)
                }
                if scanning {
                    Section {
                        QRScanner { text in
                            guard !busy else { return }
                            guard let qr = SetupSync.parseQR(text) else { error = "That isn't a DekTV sync code."; return }
                            run { try await SetupSync.fetch(qr: qr) }
                        }
                        .frame(height: 300).listRowInsets(EdgeInsets())
                    }
                }
                Section("Or type the code") {
                    TextField("Device address (shown under the QR code)", text: $address).keyboardType(.numbersAndPunctuation).autocorrectionDisabled()
                    TextField("10-digit code", text: $code).keyboardType(.numberPad)
                    Button("Get setup") { run { try await SetupSync.fetch(address: address, code: code) } }
                        .disabled(address.isEmpty || code.filter(\.isNumber).count != 10 || busy)
                }
                if busy { Section { HStack { ProgressView(); Text("Waiting for the other device to allow it…") } } }
                if let e = error { Section { Text(e).foregroundStyle(DS.live) } }
            }
        }
        .navigationTitle("Copy setup")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
    }

    private func run(_ work: @escaping () async throws -> SetupSync.Imported) {
        busy = true; error = nil
        Task {
            do { result = try await work() } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

/// Camera QR scanner.
struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerVC { let v = ScannerVC(); v.onCode = onCode; return v }
    func updateUIViewController(_ vc: ScannerVC, context: Context) {}

    final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var last: String?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? self.setup() : self.denied() }
            }
        }
        private func denied() {
            let l = UILabel(); l.text = "Allow camera access in Settings to scan, or type the code below."
            l.textColor = .white; l.numberOfLines = 0; l.textAlignment = .center
            l.frame = view.bounds.insetBy(dx: 20, dy: 20); l.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(l)
        }
        private func setup() {
            guard let cam = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) else { denied(); return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }
        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.bounds }
        }
        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            if session.isRunning { session.stopRunning() }
        }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard let s = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue, s != last else { return }
            last = s
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onCode?(s)
        }
    }
}
