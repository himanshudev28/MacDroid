import SwiftUI

/// Composite hosts for the phoneTabs layout: a native segmented sub-picker above one self-contained screen.
struct SegmentedHost: View {
    let items: [Destination]
    @Binding var selection: Destination
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker(tr("Section"), selection: Binding(get: { selection },
                                                         set: { v in withDDPointerAnimation(Motion.screen) { selection = v } })) {
                    ForEach(items) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented).labelsHidden().controlSize(.small)
                .frame(maxWidth: 340)
            }
            .frame(maxWidth: .infinity)
            .overlay(alignment: .trailing) {
                if !model.isConnected {
                    StatusPill(text: tr("Offline"), kind: .bad, systemImage: "wifi.slash")
                        .padding(.trailing, DD.Space.content)
                        .transition(.opacity)
                }
            }
            .padding(.vertical, DD.Space.sm)
            .ddAnimation(Motion.content, value: model.isConnected)
            Divider()
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
        SegmentedHost(items: [.mirror, .camera], selection: $m.selection)
    }
}
