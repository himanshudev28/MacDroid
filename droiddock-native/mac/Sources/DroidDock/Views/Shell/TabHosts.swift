import SwiftUI

/// Composite hosts for the phoneTabs layout: a native segmented sub-picker above one self-contained screen.
struct SegmentedHost: View {
    let items: [Destination]
    @Binding var selection: Destination
    /// The trailing "Offline" pill. Off for screens that show their own disconnected empty state.
    var showsOfflinePill = true
    @Environment(AppModel.self) private var model
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker(tr("Section"), selection: Binding(get: { selection },
                                                         set: { v in withDDPointerAnimation(Motion.screen) { selection = v } })) {
                    ForEach(items) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented).labelsHidden().controlSize(.small)
                .fixedSize()
            }
            .frame(maxWidth: .infinity)
            .overlay(alignment: .trailing) {
                if !model.isConnected && showsOfflinePill {
                    StatusPill(text: tr("Offline"), kind: .bad, systemImage: "wifi.slash")
                        .padding(.trailing, DD.Space.content)
                        .transition(.opacity)
                }
            }
            .frame(height: 36)
            .ddAnimation(Motion.content, value: model.isConnected)
            Rectangle().fill(DD.line).frame(height: 1 / max(displayScale, 1))
            ScreenView(destination: selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct InboxHost: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var m = model
        SegmentedHost(items: [.notifications, .messages, .calls], selection: $m.selection)
    }
}

struct FilesHost: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var m = model
        SegmentedHost(items: [.files, .photos, .transfers], selection: $m.selection)
    }
}

struct ScreenHost: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var m = model
        SegmentedHost(items: [.mirror, .camera], selection: $m.selection, showsOfflinePill: false)
    }
}
