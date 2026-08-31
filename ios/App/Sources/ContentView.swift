import HpbCore
import SwiftUI

/// The labeler, native: claim, label, submit, get paid — the same one-page
/// flow as the web labeler, over the same protocol.
struct Palette {
    static let bg = Color(red: 0x10 / 255.0, green: 0x14 / 255.0, blue: 0x18 / 255.0)
    static let panel = Color(red: 0x1a / 255.0, green: 0x21 / 255.0, blue: 0x29 / 255.0)
    static let panel2 = Color(red: 0x21 / 255.0, green: 0x2a / 255.0, blue: 0x34 / 255.0)
    static let line = Color(red: 0x2c / 255.0, green: 0x37 / 255.0, blue: 0x42 / 255.0)
    static let text = Color(red: 0xe8 / 255.0, green: 0xed / 255.0, blue: 0xf2 / 255.0)
    static let dim = Color(red: 0x93 / 255.0, green: 0xa1 / 255.0, blue: 0xaf / 255.0)
    static let accent = Color(red: 0xf7 / 255.0, green: 0x93 / 255.0, blue: 0x1a / 255.0)
    static let ok = Color(red: 0x2e / 255.0, green: 0xcc / 255.0, blue: 0x71 / 255.0)
}

struct ContentView: View {
    @EnvironmentObject var store: WorkerStore
    @EnvironmentObject var cvat: CvatStore
    @State private var cvatPassword = ""

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    addressField
                    relaysField
                    cvatSection
                    sectionTitle("Jobs")
                    if store.jobs.isEmpty {
                        Text(store.lastError ?? "looking for work on the relays…")
                            .font(.system(size: 13)).foregroundColor(Palette.dim)
                    } else {
                        ForEach(store.jobs) { JobCard(job: $0) }
                    }
                    earningsSection
                }
                .padding(16)
            }
        }
        .foregroundColor(Palette.text)
    }

    private var header: some View {
        HStack(spacing: 10) {
            Text("₿").foregroundColor(Palette.accent).font(.system(size: 18, weight: .bold))
            Text("hpb labeler").font(.system(size: 17, weight: .semibold))
            Text(store.networkLabel)
                .font(.system(size: 11)).foregroundColor(Palette.dim)
                .padding(.horizontal, 9).padding(.vertical, 3)
                .overlay(Capsule().stroke(Palette.line))
            Spacer()
            if !store.pubkey.isEmpty {
                Text("worker \(store.pubkey.prefix(12))…")
                    .font(.system(size: 11)).foregroundColor(Palette.dim)
            }
        }
    }

    private var addressField: some View {
        TextField("your payout address (tb1...)", text: $store.payoutAddress)
            .font(.system(size: 13, design: .monospaced))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
            .accessibilityIdentifier("payoutAddress")
    }

    /// The worker's whole world is a key and relays — the relays are
    /// configured right here, comma-separated; submit reconnects.
    private var relaysField: some View {
        TextField("relays (wss://relay.one,wss://relay.two)", text: $store.relayList)
            .font(.system(size: 13, design: .monospaced))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
            .onSubmit { store.relaysChanged() }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
            .accessibilityIdentifier("relayList")
    }

    /// Signing into CVAT happens on the device. The token stays in the
    /// keychain — the launcher never sees it, which is what stops it from
    /// annotating as you and then calling the result yours.
    private var cvatSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("CVAT account")
            TextField("CVAT address (http://host:8080)", text: $cvat.baseUrl)
                .font(.system(size: 13, design: .monospaced))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
                .accessibilityIdentifier("cvatBaseUrl")
            TextField("CVAT username", text: $cvat.username)
                .font(.system(size: 13, design: .monospaced))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
                .accessibilityIdentifier("cvatUsername")
            if cvat.signedIn {
                HStack(spacing: 10) {
                    Text("signed in as \(cvat.username)")
                        .font(.system(size: 12)).foregroundColor(Palette.ok)
                    Button("Sign out") { cvat.signOut() }
                        .font(.system(size: 12)).foregroundColor(Palette.dim)
                }
            } else {
                SecureField("CVAT password", text: $cvatPassword)
                    .font(.system(size: 13, design: .monospaced))
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
                    .accessibilityIdentifier("cvatPassword")
                Button("Sign in to CVAT") {
                    let password = cvatPassword
                    cvatPassword = ""
                    Task { await cvat.signIn(password: password) }
                }
                .buttonStyle(AccentButton())
                .accessibilityIdentifier("cvatSignIn")
            }
            if !cvat.status.isEmpty {
                Text(cvat.status).font(.system(size: 12)).foregroundColor(Palette.dim)
            }
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 12, weight: .medium))
            .kerning(1.0)
            .foregroundColor(Palette.dim)
    }

    private var earningsSection: some View {
        Group {
            HStack(spacing: 8) {
                sectionTitle("Earnings")
                if !store.earnings.isEmpty {
                    // String(_:) keeps sats ungrouped ("3000", not "3,000"),
                    // matching the web labeler
                    Text("· \(String(store.earnings.reduce(0) { $0 + $1.sats })) sats")
                        .font(.system(size: 12, weight: .semibold)).foregroundColor(Palette.ok)
                        .accessibilityIdentifier("earningsTotal")
                }
            }
            if store.earnings.isEmpty {
                Text("nothing yet").font(.system(size: 13)).foregroundColor(Palette.dim)
            } else {
                ForEach(store.earnings) { earning in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("+\(String(earning.sats)) sats")
                            .font(.system(size: 14, weight: .semibold)).foregroundColor(Palette.ok)
                            .accessibilityIdentifier("earningSats")
                        Text("tx \(earning.txid)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Palette.dim)
                            .lineLimit(2)
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }
}

struct JobCard: View {
    @EnvironmentObject var store: WorkerStore
    @EnvironmentObject var cvat: CvatStore
    let job: WorkerStore.JobModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(job.jobType).font(.system(size: 15, weight: .semibold))
                Text("\(String(job.rewardPerTaskSats)) sats/task")
                    .font(.system(size: 13, weight: .semibold)).foregroundColor(Palette.accent)
                Text("\(job.tasks.count) tasks")
                    .font(.system(size: 12)).foregroundColor(Palette.dim)
                Spacer()
                statusPill
            }
            content
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.panel))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.line))
    }

    private var statusPill: some View {
        let color: Color = switch job.status {
        case "active": Palette.accent
        case "validated", "paid": Palette.ok
        default: Palette.dim
        }
        return Text(job.status.uppercased())
            .font(.system(size: 10, weight: .medium)).kerning(0.6)
            .foregroundColor(color)
            .padding(.horizontal, 9).padding(.vertical, 3)
            .overlay(Capsule().stroke(color.opacity(0.7)))
            .accessibilityIdentifier("status-\(job.status)")
    }

    @ViewBuilder private var content: some View {
        switch job.status {
        case "open":
            // Claiming CVAT work before signing in strands the job: the
            // launcher invites by address, and the app has none to send.
            let needsCvat = job.tasks.contains { $0.work != nil } && !cvat.signedIn
            HStack(spacing: 12) {
                Button("Claim job") { store.claim(job) }
                    .buttonStyle(AccentButton())
                    .disabled(needsCvat)
                    .accessibilityIdentifier("claimButton")
                Text("escrow \(job.escrowId.prefix(12))…")
                    .font(.system(size: 12)).foregroundColor(Palette.dim)
            }
            if needsCvat {
                Text("sign in to CVAT above before claiming this job")
                    .font(.system(size: 12)).foregroundColor(Palette.dim)
                    .accessibilityIdentifier("needsCvatSignIn")
            }
        case "active":
            ForEach(job.tasks) { TaskCard(job: job, task: $0) }
            // Work done in CVAT has no pick to make here: the app reads it back
            // from CVAT at submit time and commits to what it finds.
            let external = job.tasks.contains { $0.work != nil }
            let done = job.tasks.filter { store.picks[job.escrowId]?[$0.key] != nil }.count
            Button(external ? "I've finished in CVAT" : "Submit \(done)/\(job.tasks.count) labels") {
                store.submit(job)
            }
            .buttonStyle(AccentButton())
            .disabled(!external && done < job.tasks.count)
            .accessibilityIdentifier("submitButton")
        case "claimed":
            Text("waiting for the launcher to grant your claim…")
                .font(.system(size: 13)).foregroundColor(Palette.dim)
        case "submitted":
            Text("labels submitted — awaiting validation and payout")
                .font(.system(size: 13)).foregroundColor(Palette.dim)
        case "validated":
            Text("labels accepted ✓").font(.system(size: 13)).foregroundColor(Palette.ok)
        default:
            EmptyView()
        }
    }
}

struct TaskCard: View {
    @EnvironmentObject var store: WorkerStore
    let job: WorkerStore.JobModel
    let task: WorkerStore.TaskModel

    var body: some View {
        if let work = task.work {
            externalCard(work)
        } else {
            inlineCard
        }
    }

    /// The labeler is a worker client here, not the labeling tool: the actual
    /// annotation happens in CVAT's own editor, which is where boxes, polygons
    /// and interpolation live.
    private func externalCard(_ work: CvatWorkSource) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(task.text).font(.system(size: 14)).foregroundColor(Palette.text)
            Text("labels: \(work.labels.joined(separator: ", "))")
                .font(.system(size: 12)).foregroundColor(Palette.dim)
            if store.cvatReady.contains(job.escrowId), let url = URL(string: work.url) {
                Link("Open in CVAT", destination: url)
                    .buttonStyle(AccentButton())
                    .accessibilityIdentifier("openInCvat")
                Text("Annotate there, save, then tap “I've finished in CVAT”.")
                    .font(.system(size: 12)).foregroundColor(Palette.dim)
            } else {
                // Opening before the launcher has admitted you gets a bare
                // permission error from CVAT, which explains nothing.
                Text("waiting for CVAT access from the launcher…")
                    .font(.system(size: 12)).foregroundColor(Palette.dim)
                    .accessibilityIdentifier("awaitingCvatAccess")
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.panel))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
    }

    private var inlineCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let data = task.imageData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable().scaledToFit()
                    .frame(maxWidth: 220)
                    .background(Color.white)
                    .cornerRadius(8)
            }
            if !task.text.isEmpty {
                Text(task.text).font(.system(size: 14))
            }
            if task.choices.isEmpty {
                TextField("your answer", text: freeTextBinding)
                    .font(.system(size: 13))
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Palette.bg))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
            } else {
                HStack(spacing: 8) {
                    ForEach(task.choices, id: \.self) { choice in
                        choiceButton(choice)
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.panel2))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
    }

    private var picked: String? {
        store.picks[job.escrowId]?[task.key]
    }

    private var freeTextBinding: Binding<String> {
        Binding(
            get: { picked ?? "" },
            set: { store.pick(job, task, $0) }
        )
    }

    private func choiceButton(_ choice: String) -> some View {
        let selected = picked == choice
        return Button(choice) { store.pick(job, task, choice) }
            .buttonStyle(.plain)
            .font(.system(size: 14, weight: selected ? .semibold : .regular))
            .foregroundColor(selected ? Palette.accent : Palette.text)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(selected ? Palette.accent.opacity(0.12) : Palette.panel)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Palette.accent : Palette.line)
            )
            .accessibilityIdentifier("choice-\(task.key)-\(choice)")
    }
}

struct AccentButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(red: 0.08, green: 0.06, blue: 0.04))
            .padding(.horizontal, 16).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 8).fill(Palette.accent))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
