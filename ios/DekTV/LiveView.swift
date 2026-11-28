import SwiftUI

struct LiveView: View {
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var router: Router
    @State private var category: String = "__fav"
    @State private var query = ""
    @State private var guideMode = false

    private var list: [Channel] {
        let base: [Channel]
        switch category {
        case "__fav": base = store.favorites.compactMap(catalog.channel)
        case "__recent": base = store.recents.compactMap(catalog.channel)
        case "__all": base = catalog.channels
        default: base = catalog.channels(in: category)
        }
        guard let q = query.nilIfBlank?.lowercased() else { return base }
        return catalog.channels.filter { $0.name.lowercased().contains(q) || (catalog.now($0)?.title.lowercased().contains(q) ?? false) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip("Favorites", "__fav"); chip("Recent", "__recent"); chip("All", "__all")
                        ForEach(catalog.liveCats) { c in chip(c.name, c.id) }
                    }
                    .padding(.horizontal).padding(.vertical, 8)
                }
                if list.isEmpty {
                    if catalog.loading { ProgressView().padding(40) }
                    else if category == "__fav" && query.isEmpty {
                        EmptyState(icon: "heart", title: "No favorites yet", message: "Swipe left on a channel (or long-press it) to add it to favorites.")
                    } else { EmptyState(icon: "tv", title: "Nothing here", message: "Try another category.") }
                    Spacer()
                } else if guideMode {
                    GuideList(channels: list)
                } else {
                    List(list) { c in
                        Button { router.play(catalog.liveRequest(c)) } label: { ChannelRow(channel: c) }
                            .swipeActions { favButton(c) }
                            .contextMenu { favButton(c) }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Live TV")
            .searchable(text: $query, prompt: "Channels and shows")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { guideMode.toggle() } label: { Image(systemName: guideMode ? "list.bullet" : "calendar") }
                }
            }
            .onAppear { if store.favorites.isEmpty && category == "__fav" { category = "__all" } }
        }
    }

    private func favButton(_ c: Channel) -> some View {
        Button { store.toggleFavorite(c) } label: {
            Label(store.isFavorite(c) ? "Unfavorite" : "Favorite", systemImage: store.isFavorite(c) ? "heart.slash" : "heart")
        }
        .tint(DS.accent)
    }

    private func chip(_ title: String, _ id: String) -> some View {
        Button { category = id } label: {
            Text(title).font(.footnote.weight(.semibold)).lineLimit(1).padding(.horizontal, 12).padding(.vertical, 7)
                .background(category == id ? Color.primary : DS.card, in: Capsule())
                .foregroundStyle(category == id ? Color(uiColor: .systemBackground) : .primary)
        }
        .buttonStyle(.plain)
    }
}

/// Guide: each channel with its programmes on a shared timeline (scroll sideways for later).
struct GuideList: View {
    let channels: [Channel]
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router
    private let perHour: CGFloat = 220
    @State private var start = Calendar.current.dateInterval(of: .hour, for: Date())?.start ?? Date()

    var body: some View {
        ScrollView([.vertical]) {
            HStack(alignment: .top, spacing: 0) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 28)
                    ForEach(channels) { c in
                        Button { router.play(catalog.liveRequest(c)) } label: {
                            HStack(spacing: 8) {
                                ChannelLogo(url: c.icon, size: 34)
                                Text(c.name).font(.caption2.weight(.semibold)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .padding(.horizontal, 8).frame(width: 130, height: 58)
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 0) {
                            ForEach(0..<12, id: \.self) { h in
                                Text(start.addingTimeInterval(Double(h) * 3600).timeText).font(.caption2).foregroundStyle(DS.dim)
                                    .frame(width: perHour, height: 28, alignment: .leading)
                            }
                        }
                        ForEach(channels) { c in
                            row(c).frame(height: 58)
                            Divider()
                        }
                    }
                }
            }
        }
    }

    private func row(_ c: Channel) -> some View {
        let end = start.addingTimeInterval(12 * 3600)
        let progs = catalog.programs(c).filter { $0.end > start && $0.start < end }
        return ZStack(alignment: .leading) {
            Color.clear.frame(width: perHour * 12)
            if progs.isEmpty {
                Text("No guide info").font(.caption2).foregroundStyle(DS.dim).padding(.leading, 8)
            }
            ForEach(progs) { p in
                let x = max(0, p.start.timeIntervalSince(start)) / 3600 * perHour
                let w = max(24, (min(p.end, end).timeIntervalSince(max(p.start, start))) / 3600 * perHour - 3)
                Button { if p.isNow { router.play(catalog.liveRequest(c)) } } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.title).font(.caption.weight(.semibold)).lineLimit(1)
                        Text(p.start.timeText).font(.caption2).foregroundStyle(DS.dim)
                    }
                    .padding(.horizontal, 8).frame(width: w, height: 50, alignment: .leading)
                    .background(p.isNow ? DS.accent.opacity(0.22) : DS.card, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .offset(x: x)
            }
        }
    }
}
