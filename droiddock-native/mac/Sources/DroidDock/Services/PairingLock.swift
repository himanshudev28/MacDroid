import AppKit
import LocalAuthentication
import SwiftUI

/// Touch ID (or the login password) before the pairing QR is shown. Setting: `droiddock.lockPairingQR`.
@MainActor
enum PairingLock {
    static let defaultsKey = "droiddock.lockPairingQR"
    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: defaultsKey) }

    /// Presents the system prompt. Returns true when the user authenticates, or when no authentication
    /// is available at all (no password set) so a locked-out Mac can still pair.
    static func unlock(reason: String) async -> Bool {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            dlog("pairing lock: no authentication available (\(err?.localizedDescription ?? "?")) — allowing")
            return true
        }
        do { return try await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) }
        catch { dlog("pairing lock: \(error.localizedDescription)"); return false }
    }
}

/// Wrap the QR: `LockedQRView { QRCodeView(...) }`. Passes content through when the setting is off.
/// Re-locks when the app goes to the background.
struct LockedQRView<Content: View>: View {
    @ViewBuilder var content: Content
    @AppStorage(PairingLock.defaultsKey) private var enabled = false
    @State private var unlocked = false
    @State private var busy = false

    var body: some View {
        Group {
            if !enabled || unlocked {
                content
            } else {
                content
                    .blur(radius: 18)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                    .overlay {
                        VStack(spacing: DD.Space.sm) {
                            Image(systemName: "touchid").font(.system(size: 28, weight: .light)).foregroundStyle(DD.dim)
                            Button(tr("Unlock to show")) { unlock() }
                                .buttonStyle(PrimaryButtonStyle())
                                .disabled(busy)
                        }
                    }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in unlocked = false }
        .onDisappear { unlocked = false }
    }

    private func unlock() {
        busy = true
        Task {
            let ok = await PairingLock.unlock(reason: tr("Show the pairing code for your phone"))
            if ok { unlocked = true }
            busy = false
        }
    }
}
