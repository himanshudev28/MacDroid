import SwiftUI

/// Onboarding-only motion. First-run tier (seen once per install), so the welcome hero and the Done burst run longer
/// than UI-GUIDE §10's 0.35 s cap on purpose: both are one-shot and explanatory, and hold still afterwards (no perpetual
/// motion). Everything else reuses `Motion` tokens. Reduce Motion: the final frame, faded in.
enum OnboardingMotion {
    /// Devices settle before the first chip leaves the phone.
    static let heroLead: Double = 0.28
    /// One chip's trip from the phone to the Mac.
    static let chipTravel: Double = 0.7
    /// Gap between chips leaving.
    static let chipStagger: Double = 0.14
    /// Hold before the first chip leaves. Never 0: a zero-length keyframe yields a NaN transform and AppKit aborts.
    static let chipLeadIn: Double = 0.05
    /// Done: the checkmark stroke draws after the tile lands.
    static let checkDraw = Animation.easeOut(duration: 0.4).delay(0.12)
    /// Done: the dot burst starts as the stroke is halfway.
    static let burstDelay: Double = 0.18
    static let burstDuration: Double = 0.6

    /// True when motion should collapse to a fade (system setting or the DEBUG `DD_REDUCE_MOTION=1` override).
    @MainActor static func reduced(_ env: Bool) -> Bool { env || Motion.systemReduceMotion }
}

// MARK: - Welcome hero: phone → Mac

/// A phone and a Mac. Three chips (a notification, a file, a clipboard item) arc from one to the other and land on the
/// Mac's screen, which keeps them. Plays once on appear (about 1.6 s), then holds the final frame.
struct OnboardingHero: View {
    @Environment(\.accessibilityReduceMotion) private var envReduceMotion

    struct Item: Identifiable {
        let id: Int
        let symbol: String
        let tint: Color
    }
    static let items: [Item] = [
        Item(id: 0, symbol: "bell.badge.fill", tint: Color(nsColor: .systemRed)),
        Item(id: 1, symbol: "doc.fill", tint: Color(nsColor: .systemBlue)),
        Item(id: 2, symbol: "doc.on.clipboard.fill", tint: Color(nsColor: .systemOrange)),
    ]

    private static let size = CGSize(width: 300, height: 120)
    // Arc from the phone's screen to the Mac's screen, bowing upward.
    private static let from = CGPoint(x: 46, y: 62)
    private static let control = CGPoint(x: 150, y: -6)
    private static let to = CGPoint(x: 232, y: 52)

    @State private var devicesIn = false
    @State private var arcDrawn = false
    @State private var play = false
    @State private var arrived = 0

    var body: some View {
        ZStack {
            arc
            phone.position(x: 46, y: 62)
            mac.position(x: 232, y: 60)
            if !reduced {
                ForEach(Self.items) { item in chip(item) }
            }
        }
        .frame(width: Self.size.width, height: Self.size.height)
        .opacity(devicesIn ? 1 : 0)
        .offset(y: devicesIn || reduced ? 0 : 8)
        .task { await run() }
        .accessibilityHidden(true)
    }

    private var reduced: Bool { OnboardingMotion.reduced(envReduceMotion) }

    // MARK: Sequence

    private func run() async {
        guard !devicesIn else { return }
        if reduced {
            withAnimation(Motion.reducedFade) { devicesIn = true; arcDrawn = true; arrived = Self.items.count }
            return
        }
        withAnimation(Motion.state) { devicesIn = true }
        withAnimation(.easeOut(duration: 0.35).delay(0.08)) { arcDrawn = true }
        try? await Task.sleep(for: .seconds(OnboardingMotion.heroLead))
        play = true
        for i in Self.items.indices {
            // Chip i lands `chipTravel` after it leaves, and they leave `chipStagger` apart. The screen tile appears as
            // the chip starts fading into the screen, so the hand-off reads as one object.
            let wait = i == 0 ? OnboardingMotion.chipLeadIn + OnboardingMotion.chipTravel - 0.1 : OnboardingMotion.chipStagger
            try? await Task.sleep(for: .seconds(wait))
            if Task.isCancelled { return }
            withAnimation(Motion.pop) { arrived = i + 1 }
        }
    }

    // MARK: Pieces

    private var arc: some View {
        Path { p in
            p.move(to: Self.from)
            p.addQuadCurve(to: Self.to, control: Self.control)
        }
        .trim(from: 0, to: arcDrawn ? 1 : 0)
        .stroke(DD.lineStrong.opacity(0.7), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [1.5, 5]))
    }

    private var phone: some View {
        RoundedRectangle(cornerRadius: 11, style: .continuous)
            .fill(DD.panel3)
            .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous).strokeBorder(DD.lineStrong.opacity(0.6), lineWidth: 1))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(LinearGradient(colors: [Color.accentColor.opacity(0.95), Color.accentColor.opacity(0.6)],
                                         startPoint: .top, endPoint: .bottom))
                    .padding(3)
            )
            .overlay(alignment: .top) { Circle().fill(Color.black.opacity(0.35)).frame(width: 4, height: 4).padding(.top, 6) }
            .frame(width: 44, height: 80)
    }

    private var mac: some View {
        VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(DD.panel3)
                .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(DD.lineStrong.opacity(0.6), lineWidth: 1))
                .overlay(
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(DD.panel)
                        .overlay(landed)
                        .padding(4)
                )
                .frame(width: 112, height: 72)
            UnevenRoundedRectangle(bottomLeadingRadius: 3, bottomTrailingRadius: 3, style: .continuous)
                .fill(DD.panel3)
                .overlay(UnevenRoundedRectangle(bottomLeadingRadius: 3, bottomTrailingRadius: 3, style: .continuous)
                    .strokeBorder(DD.lineStrong.opacity(0.6), lineWidth: 1))
                .frame(width: 134, height: 6)
        }
    }

    /// What the Mac's screen holds once chips have landed: one small tile per arrival.
    private var landed: some View {
        HStack(spacing: 6) {
            ForEach(Self.items.prefix(arrived)) { item in
                tile(item, size: 22)
                    .transition(reduced ? .opacity
                                : .asymmetric(insertion: .scale(scale: 0.7).combined(with: .opacity), removal: .opacity))
            }
        }
    }

    private func tile(_ item: Item, size: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            .fill(item.tint)
            .overlay(Image(systemName: item.symbol).font(.system(size: size * 0.48, weight: .semibold)).foregroundStyle(.white))
            .frame(width: size, height: size)
    }

    private struct Flight { var t: Double = 0; var opacity: Double = 0; var scale: Double = 0.9 }

    private func chip(_ item: Item) -> some View {
        let wait = OnboardingMotion.chipLeadIn + Double(item.id) * OnboardingMotion.chipStagger
        let travel = OnboardingMotion.chipTravel
        return KeyframeAnimator(initialValue: Flight(), trigger: play) { f in
            tile(item, size: 26)
                .shadow(color: .black.opacity(0.18), radius: 3, y: 1)
                .scaleEffect(f.scale)
                .opacity(f.opacity)
                .position(Self.point(f.t))
        } keyframes: { _ in
            KeyframeTrack(\.t) {
                LinearKeyframe(0, duration: wait)
                CubicKeyframe(1, duration: travel, startVelocity: 0, endVelocity: 0)
            }
            KeyframeTrack(\.opacity) {
                LinearKeyframe(0, duration: wait)
                LinearKeyframe(1, duration: 0.14)
                LinearKeyframe(1, duration: travel - 0.28)
                LinearKeyframe(0, duration: 0.14)
            }
            KeyframeTrack(\.scale) {
                LinearKeyframe(0.9, duration: wait)
                CubicKeyframe(1.0, duration: travel * 0.4)
                CubicKeyframe(0.85, duration: travel * 0.6)
            }
        }
    }

    /// Point on the quadratic arc at `t` (0 = phone, 1 = Mac).
    private static func point(_ t: Double) -> CGPoint {
        let u = 1 - t
        return CGPoint(x: u * u * from.x + 2 * u * t * control.x + t * t * to.x,
                       y: u * u * from.y + 2 * u * t * control.y + t * t * to.y)
    }
}

// MARK: - Done mark

/// The Done step's mark: a green tile settles, its checkmark draws itself, and one ring of dots bursts out once.
struct OnboardingDoneMark: View {
    @Environment(\.accessibilityReduceMotion) private var envReduceMotion
    @State private var landed = false
    @State private var drawn = false
    @State private var burst = false

    private static let burstColors: [Color] = [
        DD.ok, Color.accentColor, Color(nsColor: .systemOrange), Color(nsColor: .systemPink), Color(nsColor: .systemTeal),
    ]

    var body: some View {
        ZStack {
            if !reduced { dots }
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(LinearGradient(colors: [DD.ok.opacity(0.95), DD.ok.opacity(0.62)], startPoint: .top, endPoint: .bottom))
                .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(DD.OnCard.stroke, lineWidth: 1))
                .overlay(
                    CheckShape()
                        .trim(from: 0, to: drawn ? 1 : 0)
                        .stroke(.white, style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
                )
                .frame(width: 88, height: 88)
                .scaleEffect(landed || reduced ? 1 : 0.92)
                .opacity(landed ? 1 : 0)
        }
        .frame(width: 160, height: 160)
        .padding(-36)   // the burst may spill into surrounding space; layout stays the old 88-pt glyph plus a little
        .task {
            guard !landed else { return }
            if reduced {
                withAnimation(Motion.reducedFade) { landed = true; drawn = true }
                return
            }
            withAnimation(Motion.pop) { landed = true }
            withAnimation(OnboardingMotion.checkDraw) { drawn = true }
            burst = true
        }
        .accessibilityHidden(true)
    }

    private var reduced: Bool { OnboardingMotion.reduced(envReduceMotion) }

    private struct Ring { var radius: Double = 46; var opacity: Double = 0 }

    private var dots: some View {
        KeyframeAnimator(initialValue: Ring(), trigger: burst) { r in
            ZStack {
                ForEach(0..<10, id: \.self) { i in
                    let angle = Double(i) / 10 * 2 * .pi - .pi / 2
                    Circle()
                        .fill(Self.burstColors[i % Self.burstColors.count])
                        .frame(width: i.isMultiple(of: 2) ? 7 : 5, height: i.isMultiple(of: 2) ? 7 : 5)
                        .offset(x: cos(angle) * r.radius, y: sin(angle) * r.radius)
                }
            }
            .opacity(r.opacity)
        } keyframes: { _ in
            KeyframeTrack(\.radius) {
                LinearKeyframe(46, duration: OnboardingMotion.burstDelay)
                SpringKeyframe(80, duration: OnboardingMotion.burstDuration, spring: .smooth)
            }
            KeyframeTrack(\.opacity) {
                LinearKeyframe(0, duration: OnboardingMotion.burstDelay)
                LinearKeyframe(1, duration: 0.06)
                LinearKeyframe(1, duration: 0.14)
                LinearKeyframe(0, duration: OnboardingMotion.burstDuration - 0.2)
            }
        }
    }

    private struct CheckShape: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: rect.width * 0.30, y: rect.height * 0.52))
            p.addLine(to: CGPoint(x: rect.width * 0.45, y: rect.height * 0.66))
            p.addLine(to: CGPoint(x: rect.width * 0.71, y: rect.height * 0.36))
            return p
        }
    }
}
