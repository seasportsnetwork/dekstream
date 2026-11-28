import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router
    @State private var tab = 0
    private let cols = [GridItem(.adaptive(minimum: 108), spacing: 12)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) { Text("My Library").tag(0); Text("Channels").tag(1); Text("Continue").tag(2) }
                    .pickerStyle(.segmented).padding()
                switch tab {
                case 0: library
                case 1: channels
                default: continueList
                }
            }
            .navigationTitle("Library")
        }
    }

    private var library: some View {
        ScrollView {
            if store.library.isEmpty {
                EmptyState(icon: "bookmark", title: "Nothing saved yet", message: "Long-press any movie or series and choose Add to My Library, or tap + on its page.")
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        Text("Sort").font(.footnote).foregroundStyle(DS.dim)
                        ForEach(AppStore.sortNames.indices, id: \.self) { i in
                            Button { store.librarySort = i } label: {
                                Text(AppStore.sortNames[i]).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                                    .background(store.librarySort == i ? Color.primary : DS.card, in: Capsule())
                                    .foregroundStyle(store.librarySort == i ? Color(uiColor: .systemBackground) : .primary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
                LazyVGrid(columns: cols, spacing: 18) {
                    ForEach(store.sortedLibrary) { t in
                        NavigationLink { titleDestination(t) } label: {
                            PosterCard(title: cleanTitle(t.name), image: t.poster, width: 108, progress: t.kind == "movie" ? store.resume["vod:\(t.itemId)"]?.fraction : nil)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { Button(role: .destructive) { store.toggleLibrary(t) } label: { Label("Remove from My Library", systemImage: "minus.circle") } }
                    }
                }
                .padding()
            }
        }
    }

    private var channels: some View {
        let favs = store.favorites.compactMap(catalog.channel)
        return Group {
            if favs.isEmpty {
                EmptyState(icon: "heart", title: "No favorite channels", message: "In Live TV, swipe left on a channel to add it.")
                Spacer()
            } else {
                List {
                    ForEach(favs) { c in
                        Button { router.play(catalog.liveRequest(c)) } label: { ChannelRow(channel: c) }
                    }
                    .onMove { store.favorites.move(fromOffsets: $0, toOffset: $1) }
                    .onDelete { idx in let ids = idx.map { favs[$0].streamId }; store.favorites.removeAll { ids.contains($0) } }
                }
                .listStyle(.plain)
                .toolbar { EditButton() }
            }
        }
    }

    private var continueList: some View {
        let list = store.resume.values.sorted { $0.updatedAt > $1.updatedAt }
        return Group {
            if list.isEmpty {
                EmptyState(icon: "play.circle", title: "Nothing in progress", message: "Movies and episodes you stop partway show up here.")
                Spacer()
            } else {
                List {
                    ForEach(list) { p in
                        Button {
                            router.play(PlayRequest(url: p.url, title: p.title, subtitle: p.subtitle, isLive: false, resumeKey: p.key, poster: p.poster, startAt: p.position))
                        } label: {
                            HStack(spacing: 12) {
                                Img(url: p.poster).frame(width: 54, height: 80).clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(cleanTitle(p.title)).font(.subheadline.weight(.semibold))
                                    if let s = p.subtitle { Text(s).font(.caption).foregroundStyle(DS.dim) }
                                    ProgressView(value: p.fraction).tint(DS.accent)
                                    if p.duration > 0 { Text("\(Int((p.duration - p.position) / 60)) min left").font(.caption2).foregroundStyle(DS.dim) }
                                }
                            }
                        }
                    }
                    .onDelete { idx in idx.map { list[$0].key }.forEach(store.removeResume) }
                }
                .listStyle(.plain)
            }
        }
    }
}

struct SearchView: View {
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router
    @State private var q = ""
    @State private var movies: [Vod] = []
    @State private var shows: [Series] = []

    private var chans: [Channel] {
        guard let t = q.nilIfBlank?.lowercased(), t.count >= 2 else { return [] }
        return catalog.channels.filter { $0.name.lowercased().contains(t) }.prefix(40).map { $0 }
    }
    private var onNow: [(Channel, Program)] {
        guard let t = q.nilIfBlank?.lowercased(), t.count >= 3 else { return [] }
        return catalog.channels.compactMap { c in catalog.now(c).flatMap { $0.title.lowercased().contains(t) ? (c, $0) : nil } }.prefix(30).map { $0 }
    }

    var body: some View {
        List {
            if !chans.isEmpty {
                Section("Channels") {
                    ForEach(chans) { c in Button { router.play(catalog.liveRequest(c)) } label: { ChannelRow(channel: c) } }
                }
            }
            if !onNow.isEmpty {
                Section("On now") {
                    ForEach(onNow, id: \.0.streamId) { c, p in
                        Button { router.play(catalog.liveRequest(c)) } label: {
                            VStack(alignment: .leading) { Text(p.title).font(.subheadline.weight(.semibold)); Text(c.name).font(.caption).foregroundStyle(DS.dim) }
                        }
                    }
                }
            }
            if !movies.isEmpty {
                Section("Movies") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(movies) { v in NavigationLink { MovieDetailView(vod: v) } label: { PosterCard(title: cleanTitle(v.name), image: v.poster, width: 100) }.buttonStyle(.plain) }
                        }
                    }
                }
            }
            if !shows.isEmpty {
                Section("Series") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(shows) { s in NavigationLink { SeriesDetailView(series: s) } label: { PosterCard(title: cleanTitle(s.name), image: s.cover, width: 100) }.buttonStyle(.plain) }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Search")
        .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Channels, shows, movies")
        .task(id: q) {
            guard let t = q.nilIfBlank?.lowercased(), t.count >= 2 else { movies = []; shows = []; return }
            try? await Task.sleep(nanoseconds: 350_000_000)
            let vods = await catalog.allVods(), series = await catalog.allSeries()
            movies = vods.filter { $0.name.lowercased().contains(t) }.prefix(40).map { $0 }
            shows = series.filter { $0.name.lowercased().contains(t) }.prefix(40).map { $0 }
        }
    }
}
