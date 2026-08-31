import SwiftUI

@main
struct LabelerApp: App {
    @StateObject private var store = WorkerStore()
    @StateObject private var cvat = CvatStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(cvat)
                .preferredColorScheme(.dark)
                // The worker's CVAT session is handed to the store so submit can
                // read back its own annotations; it is never sent anywhere else.
                .task { store.cvat = cvat }
        }
    }
}
