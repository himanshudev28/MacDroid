import AppKit
import SwiftUI

/// Calls: dialer, quick-dial from contacts, and live call controls. The old UI had no call log and the phone
/// link exposes none, so this does not pretend to (see CallsView.tsx). Shell-agnostic (ARCHITECTURE.md §K).
struct CallsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var contacts = ContactsStore.shared
    @State private var controls = CallControls.shared
    @State private var reloadGate = ReloadGate()
    @State private var number = ""
    @State private var search = ""
    @FocusState private var numberFocused: Bool

    private var inCall: Bool { ["ringing", "active", "dialing"].contains(model.activeCall?.state ?? "") }
    private var query: String { number.isEmpty ? search : number }
    private var results: [Contact] { contacts.matching(query) }

    var body: some View {
        Group {
            if !model.isConnected {
                EmptyState.disconnected(feature: tr("calls")) { model.selection = .dashboard }
            } else {
                HSplitView {
                    dialer.frame(minWidth: 300, idealWidth: 320, maxWidth: 380)
                    quickDial.frame(minWidth: 280)
                }
            }
        }
        .searchable(text: $search, prompt: tr("Search contacts"))
        .task(id: model.isConnected) { if model.isConnected, await reloadGate.ready() { await contacts.load() } }
    }

    // MARK: Dialer

    private var dialer: some View {
        ScrollView {
            VStack(spacing: DD.Space.lg) {
                if inCall, let call = model.activeCall { LiveCallCard(call: call).transition(.opacity.combined(with: .offset(y: -6))) }
                VStack(spacing: DD.Space.md) {
                    HStack {
                        TextField(tr("Enter a number"), text: $number)
                            .textFieldStyle(.plain).multilineTextAlignment(.center)
                            .font(.system(size: 28, weight: .regular, design: .rounded)).monospacedDigit()
                            .focused($numberFocused)
                            .onSubmit(dial)
                            .onChange(of: number) { _, v in
                                let f = v.filter { $0.isNumber || "+*#-() ".contains($0) }
                                if f != v { number = f }
                            }
                        if !number.isEmpty {
                            IconButton(systemImage: "delete.left", label: tr("Delete")) { number.removeLast() }
                                .transition(.opacity)
                        }
                    }
                    .frame(height: 44)
                    if let name = contacts.name(forNumber: number), !number.isEmpty {
                        Text(name).font(DD.Font.callout).foregroundStyle(DD.dim).transition(.opacity)
                    }
                    Keypad { digit in number.append(digit) }
                    CallCircle(symbol: "phone.fill", tint: DD.ok, label: tr("Call  ⏎"), size: 56, action: dial)
                        .disabled(number.filter(\.isNumber).count < 3)
                        .keyboardShortcut(.defaultAction)
                }
                Text(model.wifi.supports("callctl")
                     ? tr("Incoming calls show a Mac alert. Answer, mute and hang up from here.")
                     : tr("Incoming calls show a Mac alert with the caller's name. Grant DroidDock's Phone permission on the phone to answer from here."))
                    .font(DD.Font.caption).foregroundStyle(DD.dim).multilineTextAlignment(.center)
            }
            .padding(DD.Space.content)
        }
        .ddAnimation(Motion.expand, value: inCall)
        .ddAnimation(Motion.content, value: number.isEmpty)
        .onAppear { numberFocused = true }
    }

    // MARK: Quick dial

    @ViewBuilder
    private var quickDial: some View {
        switch contacts.phase {
        case .idle, .loading:
            if contacts.contacts.isEmpty { LoadingState(rows: 6) } else { quickList }
        case .failed(let msg):
            EmptyState.failed(msg) { Task { await contacts.load(force: true) } }
        case .loaded:
            if contacts.contacts.isEmpty {
                EmptyState(systemImage: "person.crop.circle", title: tr("No contacts"),
                           message: tr("Contacts saved on your phone appear here for quick dialling."))
            } else if results.isEmpty {
                NoResults(query: query)
            } else { quickList }
        }
    }

    private var quickList: some View {
        List {
            SwiftUI.Section {
                ForEach(results.prefix(200)) { c in
                    ListRow(title: c.name, subtitle: c.number) {
                        AvatarView(name: c.name)
                    } trailing: {
                        IconButton(systemImage: "phone.fill", label: tr("Call")) { call(c.number, c.name) }
                    }
                    .onTapGesture(count: 2) { call(c.number, c.name) }
                    .contextMenu {
                        Button(tr("Call")) { call(c.number, c.name) }
                        Button(tr("Message")) { InboxRouter.shared.openMessages(to: MessageTarget(number: c.number, name: c.name)) }
                        Button(tr("Use Number")) { number = c.number }
                    }
                }
            } header: {
                Text(query.isEmpty ? tr("Contacts") : tr("Matches"))
                    .font(DD.Font.headline).foregroundStyle(DD.fg).textCase(nil)
            }
        }
        .listStyle(.inset)
        .scrollContentBackground(.hidden)
    }

    // MARK: Actions

    private func dial() {
        let n = number.trimmingCharacters(in: .whitespaces)
        guard n.filter(\.isNumber).count >= 3 else { return }
        call(n, contacts.name(forNumber: n) ?? n)
    }

    private func call(_ n: String, _ name: String) {
        Task {
            do { try await Core.shared.actionCall(number: n); model.toast(.info, tr("Calling {name}…", ["name": name])) }
            catch { model.toast(.bad, error.localizedDescription) }
        }
    }
}

// MARK: - Keypad

private struct Keypad: View {
    var onKey: (String) -> Void
    private let keys: [(String, String)] = [
        ("1", ""), ("2", "ABC"), ("3", "DEF"), ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
        ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"), ("*", ""), ("0", "+"), ("#", ""),
    ]
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(64), spacing: DD.Space.md), count: 3), spacing: DD.Space.md) {
            ForEach(keys, id: \.0) { k in
                Button { onKey(k.0) } label: {
                    VStack(spacing: 0) {
                        Text(k.0).font(.system(size: 22, weight: .regular, design: .rounded))
                        Text(k.1.isEmpty ? " " : k.1).font(.system(size: 10, weight: .semibold)).tracking(1)
                            .foregroundStyle(DD.dim)
                    }
                    .foregroundStyle(DD.fg)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(DD.panel2))
                    .contentShape(Circle())
                }
                .buttonStyle(CallPressStyle())
                .accessibilityLabel(k.0)
            }
        }
    }
}

// MARK: - Live call card

struct LiveCallCard: View {
    var call: IncomingCall
    @State private var controls = CallControls.shared

    var body: some View {
        let ringing = call.state == "ringing"
        VStack(spacing: DD.Space.md) {
            HStack(spacing: DD.Space.md) {
                AvatarView(name: call.name ?? call.number ?? "?", size: 40,
                           systemImage: (call.name == nil) ? "person.fill" : nil)
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.name ?? call.number ?? tr("Unknown caller")).font(DD.Font.headline).foregroundStyle(DD.fg).lineLimit(1)
                    HStack(spacing: DD.Space.xs) {
                        if ringing { Text(tr("Incoming call")) }
                        else if call.state == "dialing" { Text(tr("Calling…")) }
                        else if let since = controls.activeSince { CallTimer(since: since) }
                        else { Text(tr("In call")) }
                    }
                    .font(DD.Font.callout).foregroundStyle(DD.dim)
                }
                Spacer()
            }
            HStack(spacing: DD.Space.md) {
                if ringing {
                    CallCircle(symbol: "phone.down.fill", tint: DD.bad, label: tr("Decline")) { controls.end() }
                    CallCircle(symbol: "phone.fill", tint: DD.ok, label: tr("Answer")) { controls.answer() }
                        .disabled(call.canAnswer != true)
                } else {
                    CallCircle(symbol: (controls.muted ?? call.muted ?? false) ? "mic.slash.fill" : "mic.fill",
                               tint: DD.dim, label: tr("Mute"), filled: controls.muted ?? call.muted ?? false) { controls.toggleMute() }
                    CallCircle(symbol: "speaker.wave.2.fill", tint: DD.dim, label: tr("Speaker"),
                               filled: controls.speaker ?? call.speaker ?? false) { controls.toggleSpeaker() }
                    CallCircle(symbol: "phone.down.fill", tint: DD.bad, label: tr("Hang Up")) { controls.end() }
                }
            }
        }
        .padding(DD.Space.lg)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(DD.panel))
        .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous), DD.containerEdge)
    }
}

struct CallTimer: View {
    var since: Date
    var body: some View {
        TimelineView(.periodic(from: since, by: UIActivity.shared.period(1))) { ctx in
            Text(InboxTime.clock(max(0, Int(ctx.date.timeIntervalSince(since)))))
                .monospacedDigit()
        }
    }
}

/// Pressed feedback for round call/keypad buttons: a quick dim, like the system's own circular controls.
struct CallPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Round call control. Red/green carry a glyph and a tooltip as well as colour.
struct CallCircle: View {
    var symbol: String
    var tint: Color
    var label: String
    var filled: Bool = false
    var size: CGFloat = 38
    var action: () -> Void
    @Environment(\.isEnabled) private var enabled

    private var solid: Bool { tint == DD.ok || tint == DD.bad }

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.4, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .contentTransition(.symbolEffect(.replace))
                .foregroundStyle(solid ? DD.accentInk : (filled ? DD.ink : DD.fg))
                .frame(width: size, height: size)
                .background(Circle().fill(solid ? tint : (filled ? DD.fg : DD.panel2)))
        }
        .buttonStyle(CallPressStyle())
        .opacity(enabled ? 1 : 0.4)
        .help(label)
        .accessibilityLabel(label)
    }
}
