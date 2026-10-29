import AppKit
import QuartzCore
import SwiftUI

/// Borderless, non-activating panel that can still take keyboard focus (the reply field). Esc closes it.
final class IslandPanel: NSPanel {
    var onCancel: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func cancelOperation(_ sender: Any?) { onCancel?() }
}

/// SwiftUI host that reports content-size changes (a notification arriving while the panel is open).
final class FitHostingView<Content: View>: NSHostingView<Content> {
    var onFitChange: (() -> Void)?
    override func invalidateIntrinsicContentSize() {
        super.invalidateIntrinsicContentSize()
        onFitChange?()
    }
}

/// The panel's contents and its Dynamic-Island morph.
///
/// The window is always laid out at its final size, so SwiftUI never re-lays-out during the animation. What morphs is a
/// layer mask (plus a matching hairline layer) on the content: a capsule under the status item springs open to the
/// panel's size while its corner radius eases to `Motion.Island.panelRadius`. The content fades in a beat later.
/// Every animation runs in the render server, so it stays smooth even if the main thread is busy building the view.
final class IslandContainerView: NSView {
    private let effect = NSVisualEffectView()
    private let content = NSView()
    private let mask = CALayer()
    private let border = CALayer()
    private var host: NSView?
    private(set) var shapeSize = CGSize.zero
    private var pillCenterX: CGFloat = 0

    /// QA: `DD_ISLAND_SLOW=8` plays every animation 8x slower so frames can be screenshotted mid-flight.
    private static let slow: Float = {
        #if DEBUG
        if let v = ProcessInfo.processInfo.environment["DD_ISLAND_SLOW"], let f = Float(v), f > 0 { return 1 / f }
        #endif
        return 1
    }()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.masksToBounds = false
        content.wantsLayer = true
        content.frame = bounds
        content.autoresizingMask = [.width, .height]
        addSubview(content)

        effect.material = .popover
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.frame = content.bounds
        effect.autoresizingMask = [.width, .height]
        content.addSubview(effect)

        mask.backgroundColor = NSColor.black.cgColor
        mask.cornerCurve = .continuous
        mask.anchorPoint = CGPoint(x: 0.5, y: 1)
        content.layer?.mask = mask

        border.cornerCurve = .continuous
        border.anchorPoint = CGPoint(x: 0.5, y: 1)
        border.borderWidth = 0.5
        border.backgroundColor = nil
        layer?.addSublayer(border)
    }
    required init?(coder: NSCoder) { fatalError() }

    func install(_ view: NSView, solid: Bool) {
        host?.removeFromSuperview()
        host = view
        content.addSubview(view)
        solidFill = solid
        effect.isHidden = solid
        updateBorderColor()
        needsLayout = true
    }

    func removeHost() { host?.removeFromSuperview(); host = nil }

    override func viewDidChangeEffectiveAppearance() { updateBorderColor() }

    private var solidFill = false

    private func updateBorderColor() {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            // Reduce Transparency: a solid window colour, resolved for the panel's own appearance.
            content.layer?.backgroundColor = solidFill ? NSColor.windowBackgroundColor.cgColor : nil
            let hc = NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast
            border.borderColor = NSColor.separatorColor.withAlphaComponent(hc ? 1 : 0.9).cgColor
            border.borderWidth = hc ? 1 : 0.5
        }
    }

    /// Host sits at the top; its height is the content's ideal height (the window may be taller while shrinking).
    func layoutHost(height: CGFloat) {
        host?.frame = CGRect(x: 0, y: bounds.height - height, width: bounds.width, height: height)
    }

    // MARK: Shape

    private var layers: [CALayer] { [mask, border] }

    private func setShape(size: CGSize, centerX: CGFloat, radius: CGFloat) {
        CATransaction.begin(); CATransaction.setDisableActions(true)
        for l in layers {
            l.bounds = CGRect(origin: .zero, size: size)
            l.position = CGPoint(x: centerX, y: bounds.height)
            l.cornerRadius = radius
        }
        CATransaction.commit()
        shapeSize = size
    }

    /// Final state, no animation.
    func settle() {
        for l in layers { l.removeAllAnimations() }
        layer?.removeAnimation(forKey: "fade")
        content.layer?.removeAllAnimations()
        host?.layer?.removeAllAnimations()
        setShape(size: CGSize(width: bounds.width, height: shapeHeight), centerX: bounds.width / 2, radius: Motion.Island.panelRadius)
        content.alphaValue = 1; host?.alphaValue = 1
    }
    var shapeHeight: CGFloat = 0

    func setFinalHeight(_ h: CGFloat) { shapeHeight = h }

    /// Height change while open: the shape follows with a short ease; the window shrinks afterwards (caller).
    func animateHeight(to h: CGFloat, duration: CFTimeInterval, completion: @escaping () -> Void) {
        shapeHeight = h
        CATransaction.begin()
        CATransaction.setAnimationDuration(duration)
        CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeOut))
        CATransaction.setCompletionBlock(completion)
        for l in layers { l.bounds = CGRect(x: 0, y: 0, width: bounds.width, height: h) }
        CATransaction.commit()
        shapeSize = CGSize(width: bounds.width, height: h)
    }

    // MARK: Open / close

    private func spring(_ key: String, from: Any, to: Any, stiffness: CGFloat, damping: CGFloat) -> CASpringAnimation {
        let a = CASpringAnimation(keyPath: key)
        a.fromValue = from; a.toValue = to
        a.mass = Motion.Island.mass; a.stiffness = stiffness; a.damping = damping
        a.duration = a.settlingDuration
        a.fillMode = .backwards
        a.speed = Self.slow
        return a
    }

    func open(pillWidth: CGFloat, pillCenterX cx: CGFloat, reduceMotion: Bool, didSettle: @escaping () -> Void) {
        pillCenterX = cx
        let I = Motion.Island.self
        let full = CGSize(width: bounds.width, height: shapeHeight)
        setShape(size: full, centerX: bounds.width / 2, radius: I.panelRadius)
        if reduceMotion {
            let f = CABasicAnimation(keyPath: "opacity"); f.fromValue = 0; f.toValue = 1
            f.duration = I.reducedFade; f.speed = Self.slow
            layer?.add(f, forKey: "fade")
            DispatchQueue.main.asyncAfter(deadline: .now() + I.reducedFade / Double(Self.slow)) { didSettle() }
            return
        }
        let pill = CGSize(width: min(pillWidth, full.width), height: min(I.pillHeight, full.height))
        let pillRadius = pill.height / 2
        for l in layers {
            l.add(spring("bounds.size.width", from: pill.width, to: full.width, stiffness: I.widthStiffness, damping: I.widthDamping), forKey: "w")
            l.add(spring("bounds.size.height", from: pill.height, to: full.height, stiffness: I.heightStiffness, damping: I.heightDamping), forKey: "h")
            l.add(spring("position.x", from: cx, to: bounds.width / 2, stiffness: I.widthStiffness, damping: I.widthDamping), forKey: "x")
            l.add(spring("cornerRadius", from: pillRadius, to: I.panelRadius, stiffness: I.heightStiffness, damping: I.heightDamping), forKey: "r")
        }
        // Content: fade up and settle down a few points, after the shape has started to open.
        if let hl = host?.layer {
            let fade = CABasicAnimation(keyPath: "opacity"); fade.fromValue = 0; fade.toValue = 1
            let rise = CABasicAnimation(keyPath: "transform.translation.y"); rise.fromValue = 10; rise.toValue = 0
            for a in [fade, rise] {
                a.duration = I.contentDuration; a.beginTime = CACurrentMediaTime() + I.contentDelay / Double(Self.slow)
                a.fillMode = .backwards; a.speed = Self.slow
                a.timingFunction = CAMediaTimingFunction(name: .easeOut)
            }
            hl.add(fade, forKey: "fade"); hl.add(rise, forKey: "rise")
        }
        let settle = (spring("bounds.size.height", from: 0, to: 1, stiffness: I.heightStiffness, damping: I.heightDamping).duration + 0.02) / Double(Self.slow)
        DispatchQueue.main.asyncAfter(deadline: .now() + settle) { didSettle() }
    }

    func close(reduceMotion: Bool, completion: @escaping () -> Void) {
        let I = Motion.Island.self
        if reduceMotion {
            CATransaction.begin()
            CATransaction.setCompletionBlock(completion)
            let f = CABasicAnimation(keyPath: "opacity"); f.fromValue = 1; f.toValue = 0
            f.duration = I.reducedFade; f.speed = Self.slow; f.fillMode = .forwards; f.isRemovedOnCompletion = false
            layer?.add(f, forKey: "fade")
            CATransaction.commit()
            return
        }
        let full = CGSize(width: bounds.width, height: shapeHeight)
        let pill = CGSize(width: min(pillWidthForClose, full.width), height: min(I.pillHeight, full.height))
        CATransaction.begin()
        CATransaction.setCompletionBlock(completion)
        let ease = CAMediaTimingFunction(controlPoints: 0.4, 0, 0.9, 0.5)
        func tween(_ key: String, _ from: Any, _ to: Any) -> CABasicAnimation {
            let a = CABasicAnimation(keyPath: key); a.fromValue = from; a.toValue = to
            a.duration = I.closeDuration; a.timingFunction = ease; a.speed = Self.slow
            a.fillMode = .forwards; a.isRemovedOnCompletion = false
            return a
        }
        for l in layers {
            l.add(tween("bounds.size.width", full.width, pill.width), forKey: "w")
            l.add(tween("bounds.size.height", full.height, pill.height), forKey: "h")
            l.add(tween("position.x", bounds.width / 2, pillCenterX), forKey: "x")
            l.add(tween("cornerRadius", I.panelRadius, pill.height / 2), forKey: "r")
        }
        if let hl = host?.layer {
            let f = tween("opacity", 1, 0); f.duration = I.closeDuration * 0.6
            hl.add(f, forKey: "fade")
        }
        CATransaction.commit()
    }
    var pillWidthForClose: CGFloat = 80
}
