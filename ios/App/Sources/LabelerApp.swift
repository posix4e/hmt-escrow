import SwiftUI

@main
struct LabelerApp: App {
    @StateObject private var store = WorkerStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
