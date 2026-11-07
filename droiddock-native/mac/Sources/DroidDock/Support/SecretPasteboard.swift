import AppKit

/// Copies a secret (an OTP) so clipboard managers and DroidDock's own clipboard sync skip it
/// (nspasteboard.org Concealed/Transient markers), and optionally clears it again.
@MainActor
enum SecretPasteboard {
    static let concealedType = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    static let transientType = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")

    private static var lastChangeCount = -1
    private static var clearTask: Task<Void, Never>?

    /// `clearAfter` seconds; nil uses the user's setting (`OTPPrefs.clearSeconds`), 0 = never.
    static func copySecret(_ value: String, clearAfter: Int? = nil) {
        clearTask?.cancel(); clearTask = nil
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.declareTypes([.string, concealedType, transientType], owner: nil)
        pb.setString(value, forType: .string)
        pb.setData(Data(), forType: concealedType)
        pb.setData(Data(), forType: transientType)
        let mine = pb.changeCount
        lastChangeCount = mine
        let secs = clearAfter ?? OTPPrefs.clearSeconds
        guard secs > 0 else { return }
        clearTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(secs) * 1_000_000_000)
            if Task.isCancelled { return }
            // Only if nothing else has been copied since.
            if NSPasteboard.general.changeCount == mine { NSPasteboard.general.clearContents() }
            clearTask = nil
        }
    }
}
