import SwiftUI

@main
struct DekTVApp: App {
    @StateObject private var store = AppStore.shared
    @StateObject private var catalog = Catalog.shared
    @StateObject private var router = Router.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(catalog)
                .environmentObject(router)
                .preferredColorScheme(store.lightMode ? .light : .dark)
                .tint(DS.accent)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var router: Router

    var body: some View {
        Group {
            if store.sources.isEmpty {
                WelcomeView()
            } else {
                TabView {
                    HomeView().tabItem { Label("Home", systemImage: "house.fill") }
                    LiveView().tabItem { Label("Live TV", systemImage: "tv") }
                    SportsView().tabItem { Label("Sports", systemImage: "sportscourt") }
                    MoviesView().tabItem { Label("Movies", systemImage: "film") }
                    LibraryView().tabItem { Label("Library", systemImage: "bookmark") }
                }
            }
        }
        .task(id: store.active?.id) { await catalog.ensure(store.active) }
        .fullScreenCover(item: $router.playing) { r in
            PlayerScreen(request: r).environmentObject(catalog).environmentObject(store)
        }
    }
}

/// First run: add a playlist, or copy the setup from another DekTV device.
struct WelcomeView: View {
    @State private var adding = false
    @State private var copying = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 22) {
                Spacer()
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(LinearGradient(colors: [DS.accent, Color(red: 0.7, green: 0.14, blue: 0.37)], startPoint: .top, endPoint: .bottom))
                    Text("D").font(.system(size: 54, weight: .black)).foregroundStyle(.white)
                }
                .frame(width: 96, height: 96)
                Text("Welcome to DekTV").font(.largeTitle.weight(.bold))
                Text("Add your IPTV playlist to get started, or copy everything from your Shield or tablet.")
                    .font(.subheadline).foregroundStyle(DS.dim).multilineTextAlignment(.center).padding(.horizontal, 32)
                Spacer()
                VStack(spacing: 12) {
                    Button { adding = true } label: { Text("Add a playlist").frame(maxWidth: .infinity).padding(.vertical, 6) }
                        .buttonStyle(.borderedProminent)
                    Button { copying = true } label: { Label("Copy from another device", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity).padding(.vertical, 6) }
                        .buttonStyle(.bordered)
                }
                .padding(.horizontal, 24).padding(.bottom, 30)
            }
            .sheet(isPresented: $adding) { NavigationStack { SourceEditView(source: nil) } }
            .sheet(isPresented: $copying) { NavigationStack { CopySetupView() } }
        }
    }
}
