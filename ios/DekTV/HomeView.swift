import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router
    @State private var games: [Game] = []
    @State private var upcoming: [String: [Game]] = [:]
    @State private var topMovies: (Bool, [Vod]) = (false, [])
    @State private var topSeries: (Bool, [Series]) = (false, [])
    @State private var showSettings = false
    @State private var showSearch = false
    @State private var pickLeagues = false
    @State private var league: String? = nil

    private var leagues: [League] { (store.homeLeagues ?? League.all.map(\.key)).compactMap(League.byKey) }
    private var favChannels: [Channel] { store.favorites.compactMap(catalog.channel) }
    private var resumeList: [ResumePoint] { store.resume.values.sorted { $0.updatedAt > $1.updatedAt } }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 26) {
                    greeting
                    if let g = spotlight { SpotlightCard(game: g).padding(.horizontal) }
                    scoresCard
                    if !favChannels.isEmpty || !store.recents.isEmpty { channelsRow }
                    if store.showMoviesOnHome {
                        if !resumeList.isEmpty { continueRow }
                        if !store.library.isEmpty { libraryRow }
                        genresRow
                        if topMovies.1.count >= 3 { top10Movies }
                        if topSeries.1.count >= 3 { top10Series }
                    }
                    if catalog.loading { ProgressView().frame(maxWidth: .infinity).padding() }
                    if let e = catalog.error { Text(e).foregroundStyle(DS.live).padding(.horizontal) }
                }
                .padding(.vertical, 8)
            }
            .refreshable { await catalog.ensure(store.active, force: true); await loadScores() }
            .navigationTitle("DekTV")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button { showSearch = true } label: { Image(systemName: "magnifyingglass") } }
                ToolbarItem(placement: .topBarTrailing) { Button { showSettings = true } label: { Image(systemName: "gearshape") } }
            }
            .navigationDestination(isPresented: $showSearch) { SearchView() }
            .sheet(isPresented: $showSettings) { NavigationStack { SettingsView() } }
            .sheet(isPresented: $pickLeagues) { NavigationStack { LeaguePicker() } }
            .task(id: store.homeLeagues) { await loadScores() }
            .task(id: store.active?.id) { await loadTop10() }
        }
    }

    private var greeting: some View {
        let h = Calendar.current.component(.hour, from: Date())
        let part = h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"
        let live = games.filter(\.isLive).count
        return VStack(alignment: .leading, spacing: 2) {
            Text(part).font(.title2.weight(.bold))
            Text(Date().formatted(date: .complete, time: .omitted) + (live > 0 ? " · \(live) live game\(live == 1 ? "" : "s")" : ""))
                .font(.footnote).foregroundStyle(DS.dim)
        }
        .padding(.horizontal)
    }

    private var spotlight: Game? { games.first(where: \.isLive) ?? games.first { $0.state == "pre" } }

    // MARK: scores

    private var scoresCard: some View {
        let today = Dictionary(grouping: games, by: { $0.league.key })
        let shownLeagues = leagues.filter { league == nil || $0.key == league }
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Scores").font(.title3.weight(.bold))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        tab("All", league == nil) { league = nil }
                        ForEach(leagues) { l in tab(l.short, league == l.key) { league = l.key } }
                    }
                }
                Button { pickLeagues = true } label: { Image(systemName: "slider.horizontal.3") }.tint(DS.dim)
            }
            ForEach(shownLeagues) { l in
                let t = today[l.key] ?? []
                let next = upcoming[l.key] ?? []
                if !t.isEmpty || !next.isEmpty || league != nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(label(l, today: t, next: next)).font(.caption.weight(.bold)).foregroundStyle(DS.dim)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(t.isEmpty ? Array(next.prefix(league == nil ? 2 : 20)) : t) { g in
                                    NavigationLink { GameDetailView(game: g) } label: { MiniScore(game: g) }.buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
            if games.isEmpty && upcoming.values.allSatisfy(\.isEmpty) {
                Text("No games today").font(.footnote).foregroundStyle(DS.dim)
            }
        }
        .padding(14)
        .background(DS.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal)
    }

    private func label(_ l: League, today t: [Game], next: [Game]) -> String {
        let p = league == nil ? l.short + " · " : ""
        if !t.isEmpty {
            if t.contains(where: \.isLive) { return p + "Live now" }
            if t.allSatisfy({ $0.state == "post" }) { return p + "Final · " + (t.first?.start.dayText ?? "") }
            return p + (t.first { $0.state != "post" }?.start.dayText ?? "Today")
        }
        if let n = next.first { return p + (league == nil ? "" : "No games today · Next ") + n.start.dayText }
        return p + "No games this week"
    }

    private func tab(_ title: String, _ on: Bool, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 3) {
                Text(title).font(.caption.weight(on ? .bold : .medium)).foregroundStyle(on ? .primary : DS.dim)
                Capsule().fill(on ? DS.accent : .clear).frame(width: 14, height: 2)
            }
            .padding(.horizontal, 6)
        }
        .buttonStyle(.plain)
    }

    private func loadScores() async {
        let ls = leagues
        games = await Sports.today(ls)
        let have = Set(games.map(\.league.key))
        var up: [String: [Game]] = [:]
        await withTaskGroup(of: (String, [Game]).self) { g in
            for l in ls where !have.contains(l.key) { g.addTask { (l.key, await Sports.next(l)) } }
            for await (k, v) in g { up[k] = v }
        }
        upcoming = up
    }

    private func loadTop10() async {
        guard store.active != nil else { return }
        await catalog.ensure(store.active)
        async let pm = TMDB.popular(tv: false)
        async let ps = TMDB.popular(tv: true)
        let (m, s) = await (pm, ps)
        let vods = await catalog.allVods(), series = await catalog.allSeries()
        let tm = await Task.detached { matchPopular(m.titles, vods, limit: 10, name: { $0.name }, hasArt: { $0.poster != nil }) }.value
        let ts = await Task.detached { matchPopular(s.titles, series, limit: 10, name: { $0.name }, hasArt: { $0.cover != nil }) }.value
        topMovies = (m.thisWeek, tm)
        topSeries = (s.thisWeek, ts)
    }

    // MARK: rows

    private var channelsRow: some View {
        let list = favChannels.isEmpty ? store.recents.compactMap(catalog.channel) : favChannels
        return VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: favChannels.isEmpty ? "Recently watched" : "Your channels", subtitle: "What's on right now")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(list) { c in
                        Button { router.play(catalog.liveRequest(c)) } label: { NowCard(channel: c) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var continueRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Continue watching", subtitle: "Movies and shows")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(resumeList) { p in
                        Button {
                            router.play(PlayRequest(url: p.url, title: p.title, subtitle: p.subtitle, isLive: false, resumeKey: p.key, poster: p.poster, startAt: p.position))
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                ZStack(alignment: .bottom) {
                                    Img(url: p.poster).frame(width: 220, height: 124).clipped()
                                    GeometryReader { g in
                                        Capsule().fill(DS.accent).frame(width: g.size.width * p.fraction, height: 3).frame(maxHeight: .infinity, alignment: .bottom)
                                    }
                                }
                                .frame(width: 220, height: 124).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                Text(cleanTitle(p.title)).font(.caption.weight(.semibold)).lineLimit(1).frame(width: 220, alignment: .leading)
                            }
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button { router.play(PlayRequest(url: p.url, title: p.title, subtitle: p.subtitle, isLive: false, resumeKey: p.key, poster: p.poster, startAt: p.position)) } label: { Label("Resume", systemImage: "play.fill") }
                            Button { router.play(PlayRequest(url: p.url, title: p.title, subtitle: p.subtitle, isLive: false, resumeKey: p.key, poster: p.poster, startAt: 0)) } label: { Label("Start over", systemImage: "gobackward") }
                            Button(role: .destructive) { store.removeResume(p.key) } label: { Label("Remove from row", systemImage: "trash") }
                        }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var libraryRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "My Library", subtitle: "Saved to watch later")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(store.sortedLibrary) { t in
                        NavigationLink { titleDestination(t) } label: {
                            PosterCard(title: cleanTitle(t.name), image: t.poster, progress: t.kind == "movie" ? store.resume["vod:\(t.itemId)"]?.fraction : nil)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { Button(role: .destructive) { store.toggleLibrary(t) } label: { Label("Remove from My Library", systemImage: "minus.circle") } }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var genresRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Genres", subtitle: "Browse your playlist by genre")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(Array(Genre.all.enumerated()), id: \.element.id) { i, g in
                        NavigationLink { GenreView(genre: g) } label: { GenreTile(name: g.name, index: i) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var top10Movies: some View {
        VStack(alignment: .leading, spacing: 10) {
            Top10Header(kind: "Movies", thisWeek: topMovies.0)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(topMovies.1.enumerated()), id: \.element.id) { i, v in
                        NavigationLink { MovieDetailView(vod: v) } label: { RankedTile(rank: i + 1, title: cleanTitle(v.name), image: v.poster) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var top10Series: some View {
        VStack(alignment: .leading, spacing: 10) {
            Top10Header(kind: "Series", thisWeek: topSeries.0)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(topSeries.1.enumerated()), id: \.element.id) { i, s in
                        NavigationLink { SeriesDetailView(series: s) } label: { RankedTile(rank: i + 1, title: cleanTitle(s.name), image: s.cover) }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

@ViewBuilder func titleDestination(_ t: SavedTitle) -> some View {
    if t.kind == "movie" { MovieDetailView(vod: t.vod) } else { SeriesDetailView(series: t.series) }
}

struct SpotlightCard: View {
    let game: Game
    var body: some View {
        NavigationLink { GameDetailView(game: game) } label: {
            VStack(spacing: 12) {
                HStack {
                    Text(game.isLive ? "LIVE" : game.start.dayText + " · " + game.start.timeText)
                        .font(.caption2.weight(.bold)).padding(.horizontal, 8).padding(.vertical, 4)
                        .background(game.isLive ? Color.yellow : Color.black.opacity(0.35), in: Capsule())
                        .foregroundStyle(game.isLive ? .black : .white)
                    Text(game.league.short).font(.caption2.weight(.bold)).padding(.horizontal, 8).padding(.vertical, 4).background(Color.black.opacity(0.35), in: Capsule())
                    Spacer()
                }
                HStack {
                    teamView(game.away)
                    Spacer()
                    VStack(spacing: 2) {
                        Text(game.state == "pre" ? game.start.timeText : "\(game.away.score ?? "0") – \(game.home.score ?? "0")")
                            .font(.system(size: 30, weight: .black)).monospacedDigit()
                        Text(game.detail).font(.caption).opacity(0.85)
                    }
                    Spacer()
                    teamView(game.home)
                }
                if let b = game.broadcasts.first { Text(b).font(.caption.weight(.semibold)).opacity(0.85) }
            }
            .foregroundStyle(.white)
            .padding(18)
            .background(LinearGradient(colors: [Color(red: 0.94, green: 0.25, blue: 0.3), Color(red: 0.55, green: 0.12, blue: 0.35)], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
    }
    private func teamView(_ t: Team) -> some View {
        VStack(spacing: 6) {
            Img(url: t.logo, mode: .fit).frame(width: 54, height: 54)
            Text(t.short).font(.subheadline.weight(.bold)).lineLimit(1)
            if let r = t.record { Text(r).font(.caption2).opacity(0.8) }
        }
        .frame(width: 100)
    }
}

struct MiniScore: View {
    let game: Game
    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                row(game.away); row(game.home)
            }
            VStack(alignment: .trailing, spacing: 3) {
                Text(game.state == "pre" ? game.start.timeText : game.detail).font(.caption2.weight(.semibold))
                    .foregroundStyle(game.isLive ? DS.live : DS.dim).lineLimit(1)
                if let b = game.broadcasts.first { NetworkLabel(name: b) }
            }
            .frame(width: 78, alignment: .trailing)
        }
        .padding(10)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
    private func row(_ t: Team) -> some View {
        HStack(spacing: 6) {
            Img(url: t.logo, mode: .fit).frame(width: 18, height: 18)
            Text(t.abbr).font(.caption.weight(.bold)).frame(width: 40, alignment: .leading)
            if game.state != "pre" { Text(t.score ?? "").font(.caption.weight(.bold)).monospacedDigit() }
        }
    }
}

struct NowCard: View {
    let channel: Channel
    @EnvironmentObject var catalog: Catalog
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                ChannelLogo(url: channel.icon, size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.name).font(.caption.weight(.bold)).lineLimit(1)
                    Text("● LIVE").font(.system(size: 9, weight: .bold)).foregroundStyle(DS.live)
                }
            }
            Text(catalog.now(channel)?.title ?? " ").font(.caption).lineLimit(1).foregroundStyle(DS.dim)
            ProgressView(value: catalog.now(channel)?.progress ?? 0).tint(DS.accent)
        }
        .padding(12)
        .frame(width: 220)
        .background(DS.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct GenreTile: View {
    let name: String
    let index: Int
    private static let colors: [Color] = [.red, .orange, .cyan, .green, .purple, .pink]
    var body: some View {
        let c = Self.colors[index % Self.colors.count]
        VStack(alignment: .leading, spacing: 2) {
            Text("GENRE").font(.system(size: 9, weight: .bold)).foregroundStyle(.white.opacity(0.75))
            Text(name).font(.headline.weight(.bold)).foregroundStyle(.white)
        }
        .padding(12)
        .frame(width: 150, height: 70, alignment: .bottomLeading)
        .background(LinearGradient(colors: [c.opacity(0.9), c.opacity(0.45)], startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct Top10Header: View {
    let kind: String
    let thisWeek: Bool
    var body: some View {
        HStack(spacing: 10) {
            VStack(spacing: 0) {
                Text("TOP").font(.system(size: 8, weight: .black))
                Text("10").font(.system(size: 15, weight: .black))
            }
            .foregroundStyle(.white).padding(.horizontal, 6).padding(.vertical, 3)
            .background(Color(red: 0.9, green: 0.04, blue: 0.08), in: RoundedRectangle(cornerRadius: 5))
            VStack(alignment: .leading, spacing: 1) {
                Text("\(kind) \(thisWeek ? "this week" : "right now")").font(.title3.weight(.bold))
                Text("Most popular in your playlist").font(.footnote).foregroundStyle(DS.dim)
            }
        }
        .padding(.horizontal)
    }
}

struct RankedTile: View {
    let rank: Int
    let title: String
    let image: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                Text("\(rank)")
                    .font(.system(size: 110, weight: .black)).kerning(-8)
                    .foregroundStyle(LinearGradient(colors: [Color.white.opacity(0.42), Color.white.opacity(0.08)], startPoint: .top, endPoint: .bottom))
                    .offset(y: 18)
                Img(url: image).frame(width: 106, height: 159).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .padding(.leading, rank >= 10 ? 74 : 44)
            }
            .frame(height: 159, alignment: .bottom)
            Text(title).font(.caption.weight(.medium)).lineLimit(1).frame(width: 106, alignment: .leading).padding(.leading, rank >= 10 ? 74 : 44)
        }
    }
}
