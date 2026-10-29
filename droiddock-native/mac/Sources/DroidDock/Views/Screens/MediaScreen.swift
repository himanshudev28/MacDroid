import SwiftUI

/// Media screen. Shell-agnostic: never assume which layout hosts it (ARCHITECTURE.md §K).
struct MediaScreen: View {
    @Environment(AppModel.self) private var model
    @State private var art: NSImage?
    @State private var tint: Color?

    var body: some View {
        Group {
            if !model.isConnected {
                EmptyState.disconnected(feature: tr("media"))
            } else if let m = model.media, m.active {
                NowPlayingView(media: m, art: art, tint: tint)
            } else {
                VStack(spacing: 0) {
                    EmptyState(systemImage: "music.note",
                               title: tr("Nothing playing"),
                               message: tr("Play music or a video on your phone and control it from here."))
                    MacMediaShareCard().padding(.horizontal, DD.Space.content).padding(.bottom, DD.Space.content)
                }
            }
        }
        .ddAnimation(Motion.content, value: model.media?.active ?? false)
        // Decode + tint once per artwork change, never in `body`.
        .task(id: model.mediaArt) {
            guard let b64 = model.mediaArt,
                  let img = await ImageCache.shared.image(fromDataURL: b64, maxPixel: 600) else { art = nil; tint = nil; return }
            art = img
            tint = img.ddAverageColor()
        }
    }
}
