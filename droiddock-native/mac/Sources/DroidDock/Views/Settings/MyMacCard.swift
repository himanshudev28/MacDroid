import SwiftUI

/// "My Mac" header card at the top of the Settings sidebar (System Settings' Apple ID card): model, macOS version.
struct MyMacCard: View {
    var compact = false
    private let model = MacModel.marketingName
    private let os = ProcessInfo.processInfo.operatingSystemVersionString

    var body: some View {
        HStack(spacing: DD.Space.md) {
            Image(systemName: MacModel.symbol)
                .font(.system(size: 17, weight: .regular))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(nsColor: .systemGray).gradient))
                .accessibilityHidden(true)
            if !compact {
                VStack(alignment: .leading, spacing: 1) {
                    Text(tr("My Mac")).font(DD.Font.headline).lineLimit(1)
                    Text(model).font(DD.Font.caption).foregroundStyle(DD.dim).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(DD.Space.md)
        .background(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous).fill(DD.panel))
        .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous), DD.containerEdge)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(tr("My Mac")), \(model)")
    }
}
