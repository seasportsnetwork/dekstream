import SwiftUI
import WebKit

struct MoviesView: View {
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    @State private var series = false
    @State private var cats: [Category] = []
    @State private var cat: Category?
    @State private var vods: [Vod] = []
    @State private var shows: [Series] = []
    @State private var query = ""
    @State private var loading = false
    private let cols = [GridItem(.adaptive(minimum: 108), spacing: 12)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $series) { Text("Movies").tag(false); Text("Series").tag(true) }
                    .pickerStyle(.segmented).padding(.horizontal).padding(.top, 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(cats) { c in
                            Button { cat = c } label: {
                                Text(cleanTitle(c.name)).font(.footnote.weight(.semibold)).lineLimit(1).padding(.horizontal, 12).padding(.vertical, 7)
                                    .background(cat == c ? Color.primary : DS.card, in: Capsule())
                                    .foregroundStyle(cat == c ? Color(uiColor: .systemBackground) : .primary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal).padding(.vertical, 8)
                }
                ScrollView {
                    if loading { ProgressView().padding(40) }
                    LazyVGrid(columns: cols, spacing: 18) {
                        if series {
                            ForEach(filteredShows) { s in
                                NavigationLink { SeriesDetailView(series: s) } label: { PosterCard(title: cleanTitle(s.name), image: s.cover, width: 108) }
                                    .buttonStyle(.plain).contextMenu { libraryButton(SavedTitle.of(s)) }
                            }
                        } else {
                            ForEach(filteredVods) { v in
                                NavigationLink { MovieDetailView(vod: v) } label: {
                                    PosterCard(title: cleanTitle(v.name), image: v.poster, width: 108, progress: store.resume["vod:\(v.streamId)"]?.fraction)
                                }
                                .buttonStyle(.plain).contextMenu { libraryButton(SavedTitle.of(v)) }
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .navigationTitle(series ? "Series" : "Movies")
            .searchable(text: $query, prompt: series ? "Search series" : "Search movies")
            .task(id: "\(series)-\(store.active?.id ?? "")") { await loadCats() }
            .task(id: "\(series)-\(cat?.id ?? "")") { await loadItems() }
        }
    }

    private var filteredVods: [Vod] { guard let q = query.nilIfBlank?.lowercased() else { return vods }; return vods.filter { $0.name.lowercased().contains(q) } }
    private var filteredShows: [Series] { guard let q = query.nilIfBlank?.lowercased() else { return shows }; return shows.filter { $0.name.lowercased().contains(q) } }

    private func libraryButton(_ t: SavedTitle) -> some View {
        Button { store.toggleLibrary(t) } label: {
            Label(store.inLibrary(t.kind, t.itemId) ? "Remove from My Library" : "Add to My Library",
                  systemImage: store.inLibrary(t.kind, t.itemId) ? "minus.circle" : "plus.circle")
        }
    }

    private func loadCats() async {
        cats = series ? await catalog.seriesCategories() : await catalog.vodCategories()
        cat = cats.first
    }
    private func loadItems() async {
        guard let c = cat else { vods = []; shows = []; return }
        loading = true
        if series { shows = await catalog.series(c.id) } else { vods = await catalog.vods(c.id) }
        loading = false
    }
}

// MARK: - Movie page

struct MovieDetailView: View {
    let vod: Vod
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var router: Router
    @State private var info: VodInfo?
    @State private var extras: TitleExtras?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Header(image: extras?.backdrop ?? info?.backdrop ?? vod.poster, title: cleanTitle(vod.name))
                VStack(alignment: .leading, spacing: 14) {
                    meta
                    HStack(spacing: 10) {
                        let resume = store.resume["vod:\(vod.streamId)"]
                        Button { router.play(catalog.vodRequest(vod)) } label: {
                            Label(resume != nil ? "Resume" : "Play", systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        if resume != nil {
                            Button { router.play(catalog.vodRequest(vod, from: 0)) } label: { Image(systemName: "gobackward").padding(.vertical, 6) }.buttonStyle(.bordered)
                        }
                        let saved = store.inLibrary("movie", vod.streamId)
                        Button { store.toggleLibrary(SavedTitle.of(vod)) } label: { Image(systemName: saved ? "checkmark" : "plus").padding(.vertical, 6) }
                            .buttonStyle(.bordered)
                    }
                    if let p = extras?.overview ?? info?.plot { ExpandableText(text: p) }
                    DetailExtras(extras: extras, fallbackCast: info?.cast, fallbackDirector: info?.director)
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 30)
        }
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            info = try? await catalog.provider?.vodInfo(vod)
            extras = await TMDB.extras(tv: false, tmdbId: info?.tmdbId, title: titleKey(vod.name).0.isEmpty ? vod.name : cleanTitle(vod.name),
                                       year: info?.releaseDate.map { String($0.prefix(4)) } ?? titleKey(vod.name).1.map(String.init))
        }
    }

    private var meta: some View {
        let year = extras?.year ?? info?.releaseDate.map { String($0.prefix(4)) }
        let runtime = extras?.runtime.map { "\($0 / 60)h \($0 % 60)m" } ?? info?.duration
        let rating = extras?.rating.map { String(format: "%.1f", $0) } ?? info?.rating
        let genres = extras?.genres.prefix(3).joined(separator: " · ") ?? info?.genre
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                if let y = year { Text(y).font(.subheadline.weight(.bold)) }
                if let r = runtime { Text(r).font(.subheadline.weight(.bold)) }
                if let r = rating, r != "0", r != "0.0" { Text("★ \(r)").font(.subheadline.weight(.bold)).foregroundStyle(.yellow) }
            }
            if let g = genres, !g.isEmpty { Text(g).font(.footnote).foregroundStyle(DS.dim) }
        }
    }
}

/// Backdrop that fades into the page, with the title over it.
struct Header: View {
    let image: String?
    let title: String
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Img(url: image).frame(height: 300).frame(maxWidth: .infinity).clipped()
            LinearGradient(colors: [.clear, Color(uiColor: .systemBackground)], startPoint: .center, endPoint: .bottom)
            Text(title).font(.largeTitle.weight(.heavy)).lineLimit(3).padding(.horizontal).padding(.bottom, 4)
        }
        .frame(height: 300)
    }
}

// MARK: - Series page

struct SeriesDetailView: View {
    let series: Series
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var router: Router
    @State private var info: SeriesInfo?
    @State private var extras: TitleExtras?
    @State private var season = 0

    var body: some View {
        let seasons = (info?.seasons.keys.sorted() ?? [])
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Header(image: extras?.backdrop ?? info?.backdrop ?? series.cover, title: cleanTitle(series.name))
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        if let y = extras?.year ?? series.releaseDate.map({ String($0.prefix(4)) }) { Text(y).font(.subheadline.weight(.bold)) }
                        if let r = extras?.rating { Text("★ " + String(format: "%.1f", r)).font(.subheadline.weight(.bold)).foregroundStyle(.yellow) }
                        if !seasons.isEmpty { Text("\(seasons.count) season\(seasons.count == 1 ? "" : "s")").font(.subheadline).foregroundStyle(DS.dim) }
                    }
                    let saved = store.inLibrary("series", series.seriesId)
                    Button { store.toggleLibrary(SavedTitle.of(series)) } label: {
                        Label(saved ? "In My Library" : "My Library", systemImage: saved ? "checkmark" : "plus")
                    }
                    .buttonStyle(.bordered)
                    if let p = extras?.overview ?? info?.plot ?? series.plot { ExpandableText(text: p) }
                    if seasons.count > 1 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(seasons, id: \.self) { s in
                                    Button { season = s } label: {
                                        Text("Season \(s)").font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                                            .background(season == s ? Color.primary : DS.card, in: Capsule())
                                            .foregroundStyle(season == s ? Color(uiColor: .systemBackground) : .primary)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    if info == nil { ProgressView() }
                    ForEach(info?.seasons[season] ?? []) { e in
                        Button { router.play(catalog.episodeRequest(e, show: series)) } label: { EpisodeRow(episode: e, progress: store.resume["ep:\(e.id)"]?.fraction) }
                            .buttonStyle(.plain)
                    }
                    DetailExtras(extras: extras, fallbackCast: info?.cast, fallbackDirector: info?.director, tv: true)
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 30)
        }
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            info = try? await catalog.provider?.seriesInfo(series)
            if let first = info?.seasons.keys.sorted().first, info?.seasons[season] == nil { season = first }
            extras = await TMDB.extras(tv: true, tmdbId: nil, title: cleanTitle(series.name), year: series.releaseDate.map { String($0.prefix(4)) })
        }
    }
}

struct EpisodeRow: View {
    let episode: Episode
    let progress: Double?
    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottom) {
                Img(url: episode.image).frame(width: 140, height: 79).clipped()
                if let p = progress, p > 0 {
                    GeometryReader { g in Capsule().fill(DS.accent).frame(width: g.size.width * p, height: 3).frame(maxHeight: .infinity, alignment: .bottom) }
                }
                Image(systemName: "play.fill").foregroundStyle(.white).padding(8).background(.black.opacity(0.4), in: Circle())
                    .frame(maxHeight: .infinity)
            }
            .frame(width: 140, height: 79).clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 3) {
                Text("\(episode.number). \(episode.title)").font(.subheadline.weight(.semibold)).lineLimit(2)
                if let d = episode.duration { Text(d).font(.caption).foregroundStyle(DS.dim) }
                if let p = episode.plot { Text(p).font(.caption).foregroundStyle(DS.dim).lineLimit(2) }
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Cast, trailers, reviews

struct DetailExtras: View {
    let extras: TitleExtras?
    let fallbackCast: String?
    let fallbackDirector: String?
    var tv = false
    @State private var trailer: Trailer?
    @State private var review: Review?

    var body: some View {
        let cast = extras?.cast.isEmpty == false ? extras!.cast : (fallbackCast ?? "").split(separator: ",").map { CastMember(name: $0.trimmingCharacters(in: .whitespaces), character: nil, photo: nil) }.filter { !$0.name.isEmpty }
        let directors = extras?.directors.isEmpty == false ? extras!.directors : [fallbackDirector].compactMap { $0 }
        VStack(alignment: .leading, spacing: 10) {
            if !directors.isEmpty { credit(tv ? "Created by" : "Director", directors.joined(separator: ", ")) }
            if let w = extras?.writers, !w.isEmpty { credit(w.count == 1 ? "Writer" : "Writers", w.joined(separator: ", ")) }
            if let t = extras?.trailers, !t.isEmpty {
                Text(t.count == 1 ? "Trailer" : "Trailers & teasers").font(.title3.weight(.bold)).padding(.top, 10)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(t) { tr in
                            Button { trailer = tr } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    ZStack {
                                        Img(url: "https://img.youtube.com/vi/\(tr.youtubeId)/hqdefault.jpg").frame(width: 220, height: 124).clipped()
                                        Image(systemName: "play.fill").foregroundStyle(.white).padding(10).background(.black.opacity(0.55), in: Circle())
                                    }
                                    .frame(width: 220, height: 124).clipShape(RoundedRectangle(cornerRadius: 10))
                                    Text(tr.name).font(.caption).foregroundStyle(DS.dim).lineLimit(1).frame(width: 220, alignment: .leading)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            if !cast.isEmpty {
                Text("Cast").font(.title3.weight(.bold)).padding(.top, 10)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(cast.prefix(20)) { c in
                            VStack(spacing: 6) {
                                ZStack {
                                    Circle().fill(DS.card)
                                    if let p = c.photo { Img(url: p).clipShape(Circle()) }
                                    else { Text(c.name.split(separator: " ").prefix(2).compactMap { $0.first }.map(String.init).joined()).font(.title2.weight(.bold)).foregroundStyle(DS.dim) }
                                }
                                .frame(width: 78, height: 78)
                                Text(c.name).font(.caption.weight(.semibold)).lineLimit(1)
                                if let ch = c.character { Text(ch).font(.caption2).foregroundStyle(DS.dim).lineLimit(1) }
                            }
                            .frame(width: 88)
                        }
                    }
                }
            }
            if let rv = extras?.reviews, !rv.isEmpty {
                Text("Reviews").font(.title3.weight(.bold)).padding(.top, 10)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(rv) { r in
                            Button { review = r } label: {
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack {
                                        Text(r.author).font(.subheadline.weight(.bold)).lineLimit(1)
                                        if let s = r.rating { Text(String(format: "%.0f/10", s)).font(.caption2.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 3).background(DS.card, in: Capsule()) }
                                    }
                                    Text(r.content).font(.footnote).foregroundStyle(DS.dim).lineLimit(4).multilineTextAlignment(.leading)
                                }
                                .padding(14).frame(width: 290, alignment: .topLeading)
                                .background(DS.card, in: RoundedRectangle(cornerRadius: 14))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            if !TMDB.hasKey {
                Text("Add a free TMDB key in Settings for cast photos, trailers and reviews.").font(.footnote).foregroundStyle(DS.dim).padding(.top, 8)
            }
        }
        .fullScreenCover(item: $trailer) { t in TrailerView(trailer: t) }
        .sheet(item: $review) { r in
            NavigationStack {
                ScrollView { Text(r.content).padding() }
                    .navigationTitle(r.author).navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func credit(_ label: String, _ value: String) -> some View {
        (Text(label + ":  ").foregroundColor(DS.dim).fontWeight(.semibold) + Text(value)).font(.subheadline)
    }
}

/// Trailer in DekTV's own full-screen player (YouTube's embedded player; the YouTube app is never opened).
struct TrailerView: View {
    let trailer: Trailer
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            YouTubeEmbed(id: trailer.youtubeId).ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40).background(.ultraThinMaterial, in: Circle())
            }
            .padding()
        }
        .statusBarHidden()
    }
}

struct YouTubeEmbed: UIViewRepresentable {
    let id: String
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        let w = WKWebView(frame: .zero, configuration: cfg)
        w.isOpaque = false
        w.backgroundColor = .black
        w.scrollView.isScrollEnabled = false
        let vid = id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        let html = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
        <body><iframe src="https://www.youtube-nocookie.com/embed/\(vid)?autoplay=1&playsinline=1&rel=0&modestbranding=1&vq=hd1080"
        allow="autoplay; encrypted-media; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>
        """
        w.loadHTMLString(html, baseURL: URL(string: "https://seasportsnetwork.github.io/"))
        return w
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

// MARK: - Genre page

struct GenreView: View {
    let genre: Genre
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    @State private var movies: [Vod] = []
    @State private var shows: [Series] = []
    @State private var loading = true
    @State private var fromTmdb = false
    private let cols = [GridItem(.adaptive(minimum: 108), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(loading ? "Finding \(genre.name) in your playlist…" : fromTmdb ? "Most popular first · from your playlist" :
                        "From your playlist's \(genre.name.lowercased()) categories" + (TMDB.hasKey ? "" : " · add a TMDB key for a fuller list"))
                    .font(.footnote).foregroundStyle(DS.dim).padding(.horizontal)
                if loading { ProgressView().frame(maxWidth: .infinity).padding(30) }
                if !loading && movies.isEmpty && shows.isEmpty { EmptyState(icon: "film", title: "Nothing yet", message: "Nothing in your playlist for \(genre.name).") }
                if !movies.isEmpty {
                    Text("Movies").font(.title3.weight(.bold)).padding(.horizontal)
                    LazyVGrid(columns: cols, spacing: 18) {
                        ForEach(movies) { v in
                            NavigationLink { MovieDetailView(vod: v) } label: { PosterCard(title: cleanTitle(v.name), image: v.poster, width: 108) }.buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
                if !shows.isEmpty {
                    Text("Series").font(.title3.weight(.bold)).padding(.horizontal).padding(.top, 8)
                    LazyVGrid(columns: cols, spacing: 18) {
                        ForEach(shows) { s in
                            NavigationLink { SeriesDetailView(series: s) } label: { PosterCard(title: cleanTitle(s.name), image: s.cover, width: 108) }.buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical)
        }
        .navigationTitle(genre.name)
        .task { await load() }
    }

    private func load() async {
        if TMDB.hasKey {
            async let pm = TMDB.genreTitles(tv: false, genreId: genre.movieId)
            async let ps: [(String, Int?)] = genre.tvId != nil ? TMDB.genreTitles(tv: true, genreId: genre.tvId!) : []
            let (m, s) = await (pm, ps)
            let vods = await catalog.allVods(), series = await catalog.allSeries()
            let mm = await Task.detached { matchPopular(m, vods, limit: 60, name: { $0.name }, hasArt: { $0.poster != nil }) }.value
            let ss = await Task.detached { matchPopular(s, series, limit: 40, name: { $0.name }, hasArt: { $0.cover != nil }) }.value
            if !mm.isEmpty || !ss.isEmpty { movies = mm; shows = ss; fromTmdb = true; loading = false; return }
        }
        func hit(_ n: String) -> Bool { let l = n.lowercased(); return genre.words.contains { l.contains($0) } }
        var mv: [Vod] = [], sv: [Series] = []
        for c in await catalog.vodCategories() where hit(c.name) { mv += await catalog.vods(c.id); if mv.count > 120 { break } }
        for c in await catalog.seriesCategories() where hit(c.name) { sv += await catalog.series(c.id); if sv.count > 80 { break } }
        var seenM = Set<Int>(), seenS = Set<Int>()
        movies = mv.filter { seenM.insert($0.streamId).inserted }
        shows = sv.filter { seenS.insert($0.seriesId).inserted }
        loading = false
    }
}
