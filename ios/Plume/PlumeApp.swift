import SwiftUI

@main
struct PlumeApp: App {
    @StateObject private var store = NoteStore.shared
    @StateObject private var sync = SyncManager.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .environmentObject(store)
                .environmentObject(sync)
                .onAppear { sync.startAuto() }
                .onChange(of: phase) { p in
                    switch p {
                    case .active: sync.startAuto()
                    case .background: sync.stopAuto()
                    default: break
                    }
                }
        }
    }
}
