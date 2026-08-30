import Foundation
import UIKit

/// `--demo` fixture: one CVAT-style animals job driven entirely in memory,
/// so the UI (and the CI screenshot test) exercises the real claim →
/// label → submit → paid flow without relays. Clearly a demo — the
/// network pill says so.
@MainActor
enum DemoData {
    static let escrowId = String(repeating: "cd", count: 32)

    static func install(into store: WorkerStore) {
        store.pubkey = String(repeating: "d3", count: 32)
        store.networkLabel = "demo"
        store.payoutAddress = store.payoutAddress.isEmpty
            ? "tb1qdemoexampleaddressxxxxxxxxxxxxxxxxxxx" : store.payoutAddress
        store.jobs = [WorkerStore.JobModel(
            escrowId: escrowId,
            jobType: "image_label",
            rewardPerTaskSats: 1_000,
            status: "open",
            tasks: [
                task("frame-0", "What animal is in frame 0?", ears: .pointy),
                task("frame-1", "What animal is in frame 1?", ears: .floppy),
                task("frame-2", "What animal is in frame 2?", ears: .pointy),
            ]
        )]
    }

    static func claim(store: WorkerStore, escrowId: String) {
        setStatus(store, escrowId, "claimed")
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 600_000_000)
            setStatus(store, escrowId, "active")
        }
    }

    static func submit(store: WorkerStore, escrowId: String) {
        setStatus(store, escrowId, "submitted")
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            setStatus(store, escrowId, "validated")
            store.earnings = [WorkerStore.EarningModel(
                escrowId: escrowId,
                txid: "b24890f1374cb6c501e851877bd49cccf627accba83d79f7bf9a7034b2aedc42",
                sats: 3_000
            )]
        }
    }

    private static func setStatus(_ store: WorkerStore, _ escrowId: String, _ status: String) {
        store.jobs = store.jobs.map { job in
            guard job.escrowId == escrowId else { return job }
            var updated = job
            updated.status = status
            return updated
        }
    }

    private enum Ears { case pointy, floppy }

    private static func task(_ key: String, _ text: String, ears: Ears) -> WorkerStore.TaskModel {
        WorkerStore.TaskModel(
            key: key, text: text, imageData: pictogram(ears: ears), choices: ["cat", "dog"]
        )
    }

    /// The same cat/dog pictograms the harness's mock CVAT draws: a head
    /// with pointy or floppy ears.
    private static func pictogram(ears: Ears) -> Data? {
        let size = CGSize(width: 200, height: 150)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let ink = UIColor(red: 0.15, green: 0.17, blue: 0.20, alpha: 1)
            ink.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 60, y: 45, width: 80, height: 80))
            switch ears {
            case .pointy:
                triangle(ctx.cgContext, CGPoint(x: 65, y: 55), CGPoint(x: 85, y: 50), CGPoint(x: 68, y: 22))
                triangle(ctx.cgContext, CGPoint(x: 115, y: 50), CGPoint(x: 135, y: 55), CGPoint(x: 132, y: 22))
            case .floppy:
                ctx.cgContext.fillEllipse(in: CGRect(x: 45, y: 50, width: 26, height: 55))
                ctx.cgContext.fillEllipse(in: CGRect(x: 129, y: 50, width: 26, height: 55))
            }
            UIColor.white.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 78, y: 70, width: 12, height: 12))
            ctx.cgContext.fillEllipse(in: CGRect(x: 110, y: 70, width: 12, height: 12))
        }
        return image.pngData()
    }

    private static func triangle(_ ctx: CGContext, _ a: CGPoint, _ b: CGPoint, _ c: CGPoint) {
        ctx.beginPath()
        ctx.move(to: a)
        ctx.addLine(to: b)
        ctx.addLine(to: c)
        ctx.closePath()
        ctx.fillPath()
    }
}
