import SwiftUI

struct SportsView: View {
    @EnvironmentObject var store: AppStore
    @State private var league: League = League.all[0]
    @State private var dayOffset = 0
    @State private var games: [Game] = []
    @State private var loading = false

    private var leagues: [League] { (store.homeLeagues ?? League.all.map(\.key)).compactMap(League.byKey) }
    private var date: Date { Calendar.current.date(byAdding: .day, value: dayOffset, to: Date()) ?? Date() }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(leagues) { l in
                            Button { league = l } label: {
                                Text(l.short).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                                    .background(league == l ? Color.primary : DS.card, in: Capsule())
                                    .foregroundStyle(league == l ? Color(uiColor: .systemBackground) : .primary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal).padding(.vertical, 8)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(-1...6, id: \.self) { o in
                            let d = Calendar.current.date(byAdding: .day, value: o, to: Date()) ?? Date()
                            Button { dayOffset = o } label: {
                                Text(o == -1 ? "Yesterday" : d.dayText).font(.caption.weight(dayOffset == o ? .bold : .regular))
                                    .padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(dayOffset == o ? DS.accent.opacity(0.25) : .clear, in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
                List {
                    if loading { ProgressView().frame(maxWidth: .infinity) }
                    else if games.isEmpty { Text("No \(league.short) games \(dayOffset == 0 ? "today" : date.dayText)").foregroundStyle(DS.dim) }
                    ForEach(games) { g in
                        NavigationLink { GameDetailView(game: g) } label: { GameRow(game: g) }
                    }
                }
                .listStyle(.plain)
                .refreshable { await load() }
            }
            .navigationTitle("Sports")
            .task(id: "\(league.key)-\(dayOffset)") { await load() }
            .onAppear { if !leagues.contains(league), let f = leagues.first { league = f } }
        }
    }

    private func load() async {
        loading = games.isEmpty
        games = await Sports.scoreboard(league, dates: Sports.day(date))
        loading = false
    }
}

struct GameRow: View {
    let game: Game
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                team(game.away); team(game.home)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(game.state == "pre" ? game.start.timeText : game.detail).font(.caption.weight(.semibold))
                    .foregroundStyle(game.isLive ? DS.live : DS.dim)
                if let b = game.broadcasts.first { NetworkLabel(name: b) }
            }
        }
        .padding(.vertical, 4)
    }
    private func team(_ t: Team) -> some View {
        HStack(spacing: 8) {
            Img(url: t.logo, mode: .fit).frame(width: 24, height: 24)
            Text(t.short).font(.subheadline.weight(.semibold))
            if let r = t.record { Text(r).font(.caption2).foregroundStyle(DS.dim) }
            if game.state != "pre" { Text(t.score ?? "").font(.subheadline.weight(.bold)).monospacedDigit() }
        }
    }
}

struct GameDetailView: View {
    let game: Game
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router
    var body: some View {
        let chans = Sports.channels(for: game, in: catalog.channels, catalog: catalog)
        List {
            Section {
                SpotlightCard(game: game).listRowInsets(EdgeInsets()).listRowBackground(Color.clear).allowsHitTesting(false)
            }
            if !game.broadcasts.isEmpty {
                Section("On TV") { Text(game.broadcasts.joined(separator: " · ")).font(.subheadline) }
            }
            Section(chans.isEmpty ? "No matching channels found" : "Watch on") {
                ForEach(chans) { c in
                    Button { router.play(catalog.liveRequest(c)) } label: { ChannelRow(channel: c) }
                }
            }
        }
        .navigationTitle("\(game.away.abbr) @ \(game.home.abbr)")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Which leagues Home's scores card and Sports show.
struct LeaguePicker: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<String> = []
    var body: some View {
        List {
            Section {
                Button("All leagues") { picked = Set(League.all.map(\.key)) }
                Button("None") { picked = [] }
            }
            Section("Leagues") {
                ForEach(League.all) { l in
                    Button {
                        if picked.contains(l.key) { picked.remove(l.key) } else { picked.insert(l.key) }
                    } label: {
                        HStack { Text(l.name).foregroundStyle(.primary); Spacer(); if picked.contains(l.key) { Image(systemName: "checkmark").foregroundStyle(DS.accent) } }
                    }
                }
            }
        }
        .navigationTitle("Leagues")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") {
                    store.homeLeagues = picked.count == League.all.count || picked.isEmpty ? nil : League.all.map(\.key).filter { picked.contains($0) }
                    dismiss()
                }
            }
        }
        .onAppear { picked = Set(store.homeLeagues ?? League.all.map(\.key)) }
    }
}
