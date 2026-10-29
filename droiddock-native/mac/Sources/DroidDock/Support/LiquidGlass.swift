import AppKit
import SwiftUI

/// Window-wide "liquid" background. Opacity 1 is the ordinary solid window; lower values turn the window background into
/// a behind-window blur (the desktop shows through) with a window-colour tint whose opacity follows the slider.
/// Apply with `.liquidWindowBackground()` at the root of a window. Text stays on adaptive vibrancy/label colours, so
/// contrast holds at every value; Reduce Transparency (app toggle or macOS) forces solid.
struct LiquidWindowBackground: View {
    private let look = AppearanceSettings.shared
    var body: some View {
        let o = look.effectiveGlassOpacity
        ZStack {
            if o < 0.999 {
                DDVisualEffect(material: .underWindowBackground, blending: .behindWindow)
                Color(nsColor: .windowBackgroundColor).opacity(o)
            } else {
                Color(nsColor: .windowBackgroundColor)
            }
            LiquidWindowConfigurator(translucent: o < 0.999)
                .frame(width: 0, height: 0)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// Makes the hosting NSWindow non-opaque while the glass is on (and restores it when solid).
private struct LiquidWindowConfigurator: NSViewRepresentable {
    let translucent: Bool
    func makeNSView(context: Context) -> NSView { NSView() }
    func updateNSView(_ v: NSView, context: Context) {
        DispatchQueue.main.async {
            guard let w = v.window else { return }
            w.isOpaque = !translucent
            w.backgroundColor = translucent ? .clear : .windowBackgroundColor
        }
    }
}

extension View {
    func liquidWindowBackground() -> some View { background(LiquidWindowBackground()) }
}
