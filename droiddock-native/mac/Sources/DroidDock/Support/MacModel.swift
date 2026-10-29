import Foundation
import SystemConfiguration
import AppKit

/// "MacBook Air (M1, 2020)" for this Mac. `hw.model` is mapped to a marketing name when known, otherwise the
/// family + chip from `machdep.cpu.brand_string`, otherwise the raw identifier.
enum MacModel {
    static var identifier: String { sysctlString("hw.model") ?? "Mac" }

    static var isPortable: Bool { identifier.hasPrefix("MacBook") }

    static var marketingName: String {
        let id = identifier
        if let n = table[id] { return n }
        let chip = sysctlString("machdep.cpu.brand_string").map { $0.replacingOccurrences(of: "Apple ", with: "") }
        let family: String? = [("MacBookAir", "MacBook Air"), ("MacBookPro", "MacBook Pro"), ("MacBook", "MacBook"),
                               ("Macmini", "Mac mini"), ("iMac", "iMac"), ("MacPro", "Mac Pro"), ("MacStudio", "Mac Studio")]
            .first { id.hasPrefix($0.0) }?.1
        switch (family, chip) {
        case let (f?, c?): return "\(f) (\(c))"
        case let (f?, nil): return f
        case let (nil, c?): return "Mac (\(c))"
        default: return id
        }
    }

    static var symbol: String { isPortable ? "laptopcomputer" : "desktopcomputer" }

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buf, &size, nil, 0) == 0 else { return nil }
        return String(cString: buf)
    }

    private static let table: [String: String] = [
        "MacBookAir10,1": "MacBook Air (M1, 2020)",
        "MacBookPro17,1": "MacBook Pro (13-inch, M1, 2020)",
        "MacBookPro18,3": "MacBook Pro (14-inch, 2021)", "MacBookPro18,4": "MacBook Pro (14-inch, 2021)",
        "MacBookPro18,1": "MacBook Pro (16-inch, 2021)", "MacBookPro18,2": "MacBook Pro (16-inch, 2021)",
        "Macmini9,1": "Mac mini (M1, 2020)", "iMac21,1": "iMac (24-inch, M1, 2021)", "iMac21,2": "iMac (24-inch, M1, 2021)",
        "Mac13,1": "Mac Studio (M1 Max, 2022)", "Mac13,2": "Mac Studio (M1 Ultra, 2022)",
        "Mac14,2": "MacBook Air (M2, 2022)", "Mac14,7": "MacBook Pro (13-inch, M2, 2022)",
        "Mac14,15": "MacBook Air (15-inch, M2, 2023)", "Mac14,3": "Mac mini (M2, 2023)", "Mac14,12": "Mac mini (M2 Pro, 2023)",
        "Mac14,5": "MacBook Pro (14-inch, 2023)", "Mac14,9": "MacBook Pro (14-inch, 2023)",
        "Mac14,6": "MacBook Pro (16-inch, 2023)", "Mac14,10": "MacBook Pro (16-inch, 2023)",
        "Mac15,12": "MacBook Air (13-inch, M3, 2024)", "Mac15,13": "MacBook Air (15-inch, M3, 2024)",
        "Mac15,3": "MacBook Pro (14-inch, M3, 2023)", "Mac15,6": "MacBook Pro (14-inch, M3 Pro, 2023)",
        "Mac15,8": "MacBook Pro (14-inch, M3 Max, 2023)", "Mac15,10": "MacBook Pro (14-inch, M3 Max, 2023)",
        "Mac15,7": "MacBook Pro (16-inch, M3 Pro, 2023)", "Mac15,9": "MacBook Pro (16-inch, M3 Max, 2023)",
        "Mac15,11": "MacBook Pro (16-inch, M3 Max, 2023)", "Mac15,4": "iMac (24-inch, M3, 2023)", "Mac15,5": "iMac (24-inch, M3, 2023)",
        "Mac16,1": "MacBook Pro (14-inch, M4, 2024)", "Mac16,6": "MacBook Pro (14-inch, M4 Pro, 2024)",
        "Mac16,8": "MacBook Pro (14-inch, M4 Pro, 2024)", "Mac16,7": "MacBook Pro (16-inch, M4 Pro, 2024)",
        "Mac16,5": "MacBook Pro (16-inch, M4 Max, 2024)", "Mac16,10": "Mac mini (M4, 2024)", "Mac16,11": "Mac mini (M4 Pro, 2024)",
        "Mac16,2": "iMac (24-inch, M4, 2024)", "Mac16,3": "iMac (24-inch, M4, 2024)",
    ]
}

/// Active IPv4 network adapters (for the server's "Network adapter" picker), e.g. "Wi-Fi (en0) – 10.160.249.65".
struct NetAdapter: Identifiable, Equatable, Sendable {
    var name: String      // BSD name, e.g. en0
    var label: String     // localized service name, e.g. Wi-Fi
    var ip: String
    var id: String { name }
    var title: String { "\(label) (\(name)) – \(ip)" }
}

enum NetAdapters {
    static func active() -> [NetAdapter] {
        var labels: [String: String] = [:]
        for i in (SCNetworkInterfaceCopyAll() as? [SCNetworkInterface]) ?? [] {
            if let bsd = SCNetworkInterfaceGetBSDName(i) as String?, let n = SCNetworkInterfaceGetLocalizedDisplayName(i) as String? { labels[bsd] = n }
        }
        var out: [NetAdapter] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            let f = Int32(cur.pointee.ifa_flags)
            guard f & IFF_UP != 0, f & IFF_LOOPBACK == 0, let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let name = String(cString: cur.pointee.ifa_name)
            let ip = String(cString: host)
            guard !out.contains(where: { $0.name == name && $0.ip == ip }) else { continue }
            out.append(NetAdapter(name: name, label: labels[name] ?? tr("Network"), ip: ip))
        }
        return out.sorted { $0.name < $1.name }
    }
}
