// swift-tools-version: 6.0
import PackageDescription
import Foundation

// Absolute path to the Rust static library (built by scripts/build.sh).
let coreLibDir = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()          // mac/
    .deletingLastPathComponent()          // droiddock-native/
    .appendingPathComponent("core/target/aarch64-apple-darwin/release").path

let package = Package(
    name: "DroidDock",
    platforms: [.macOS(.v14)],
    targets: [
        .systemLibrary(name: "CDroidDockCore", path: "Sources/CDroidDockCore"),
        .executableTarget(
            name: "DroidDock",
            dependencies: ["CDroidDockCore"],
            exclude: ["Resources", "DesignSystem/DESIGN.md"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .unsafeFlags(["-L", coreLibDir]),
                .linkedLibrary("droiddock_core"),
                .linkedLibrary("iconv"),
                .linkedLibrary("objc"),
                .linkedFramework("AppKit"),
                .linkedFramework("Foundation"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreServices"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("ApplicationServices"),
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("UserNotifications"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("CoreBluetooth"),
            ]
        ),
    ]
)
