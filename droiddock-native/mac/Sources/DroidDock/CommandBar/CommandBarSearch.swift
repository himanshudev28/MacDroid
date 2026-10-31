import Foundation

// The quick command bar's brain: pure, synchronous, main-thread-free matching and ranking. Nothing here touches AppKit,
// the core or UserDefaults, so it is unit-testable (`CommandBarSelfTest`) and cheap to run on every keystroke.
//
// Candidates are normalised ONCE per data change (lowercased, diacritic- and width-folded, stored as UTF-8 bytes);
// a keystroke then only folds the short query and scans bytes.
//
// SAFETY RULE: who receives a message or a call is chosen with STRICT matching only (exact, prefix, in-order word
// prefix). Loose hits (substring, unordered words, fuzzy) may be listed, but never act immediately. Anything that is
// not unambiguous must be confirmed with a second ↩ (`CBItem.confirm`).

// MARK: - Items

/// What a row does. A value (not a closure) so results compare, test and diff cheaply.
enum CBAction: Equatable {
    case none
    case ring, mirror, screenshot, playPause, sendClipboard
    case sms(number: String, name: String, body: String)
    case call(number: String, name: String)
    case openConversation(number: String, name: String)
    case showContact(name: String)
    case openApp(pkg: String, label: String, onMac: Bool)
    /// Replace the query (the "Text a contact…" hint rows).
    case prefill(String)

    /// Needs the Wi-Fi/BLE link to the phone. Showing a contact or opening an app in a Mac window (ADB) does not.
    var needsLink: Bool {
        switch self {
        case .none, .prefill, .showContact, .openConversation: false
        case .openApp(_, _, let onMac): !onMac
        default: true
        }
    }
}

struct CBItem: Identifiable, Equatable {
    var id: String
    var symbol: String
    var title: String
    var subtitle: String
    var primary: CBAction
    var secondary: CBAction = .none
    /// Shown next to the ↩ hint when this row is selected ("Show contact").
    var secondaryTitle: String = ""
    /// The PRIMARY action needs the phone link (dimmed + "Connect your phone first" when it is down).
    /// The secondary's need is `secondary.needsLink`. Kept per row for items whose need depends on state (mirror stop).
    var needsLink = false
    /// The first ↩ only arms the row (subtitle becomes `armSubtitle`); a second ↩ runs it.
    var confirm = false
    var armSubtitle = ""
    var score: Double = 0
}

/// A remembered action: its kind plus a number or package, nothing else. Names and labels are resolved when shown.
struct CBRecent: Codable, Equatable {
    var kind: String
    var a: String = ""      // number or package
    var key: String { kind + "|" + a }
}

/// Everything the planner needs to know about the app right now (a snapshot, so the planner stays pure).
struct CBContext {
    var connected = false
    var openAppsOnMac = false
    var macWindowPossible = false     // an ADB device is attached
    var battery: Int?
    var charging = false
    var mediaActive = false
    var mediaPlaying = false
    var mirroring = false
    var ringing = false
    var appsLoading = false
}

// MARK: - Normalised index

enum CBText {
    /// Lowercase + strip diacritics + fold width, so "José", "JOSE" and "ｊｏｓｅ" are one thing.
    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }
    static func bytes(_ s: String) -> [UInt8] { Array(fold(s).utf8) }
    static func digits(_ s: String) -> String { s.filter(\.isNumber) }
}

struct CBIndex {
    struct ContactEntry { let contact: Contact; let key: [UInt8]; let digits: String }
    struct AppEntry { let app: PhoneApp; let key: [UInt8] }
    var contacts: [ContactEntry] = []
    var apps: [AppEntry] = []
    /// For resolving recents at render time (last 10 digits -> name, package -> label).
    private(set) var nameByNumber: [String: String] = [:]
    private(set) var labelByPkg: [String: String] = [:]

    init() {}
    init(contacts: [Contact], apps: [PhoneApp]) {
        self.contacts = contacts.map { ContactEntry(contact: $0, key: CBText.bytes($0.name), digits: CBText.digits($0.number)) }
        self.apps = apps.map { AppEntry(app: $0, key: CBText.bytes($0.label)) }
        for c in contacts { nameByNumber[normalizedNumber(c.number)] = nameByNumber[normalizedNumber(c.number)] ?? c.name }
        for a in apps { labelByPkg[a.pkg] = a.label }
    }
}

// MARK: - Scoring

enum CBScore {
    /// Packs (score, index) into one Int so sorting 3,000 hits is a plain integer sort; ties keep index order.
    static func pack(_ score: Int, _ idx: Int) -> Int { score << 20 | (0xFFFFF - idx) }
    static func unpack(_ v: Int) -> (score: Int, idx: Int) { (v >> 20, 0xFFFFF - (v & 0xFFFFF)) }
    static let exact = 1000, prefix = 900, wordPrefix = 700, contains = 500, tokens = 650

    /// Score of `needle` inside `hay` (both folded UTF-8), or nil. Prefix > word-prefix > substring > fuzzy.
    /// `fuzzy` allows an in-order subsequence match (ASCII needles only, so a multi-byte character is never split).
    static func score(_ needle: [UInt8], in hay: [UInt8], fuzzy: Bool) -> Int? {
        needle.withUnsafeBufferPointer { nb in
            hay.withUnsafeBufferPointer { hb in scoreRaw(nb, hb, fuzzy: fuzzy, strict: false) }
        }
    }

    /// STRICT: exact, prefix, or in-order word prefix only. Used to choose who receives a message or a call.
    static func strict(_ needle: [UInt8], in hay: [UInt8]) -> Int? {
        needle.withUnsafeBufferPointer { nb in
            hay.withUnsafeBufferPointer { hb in scoreRaw(nb, hb, fuzzy: false, strict: true) }
        }
    }

    private static func scoreRaw(_ needle: UnsafeBufferPointer<UInt8>, _ hay: UnsafeBufferPointer<UInt8>,
                                 fuzzy: Bool, strict: Bool) -> Int? {
        let n = needle.count, h = hay.count
        guard n > 0, h > 0 else { return nil }
        let first = needle[0]
        if n <= h {
            var containsAt = -1
            var i = 0
            let last = h - n
            while i <= last {
                if hay[i] == first {
                    var j = 1
                    while j < n, hay[i + j] == needle[j] { j += 1 }
                    if j == n {
                        if n == h { return exact }
                        let whole = (i + n == h || hay[i + n] == 0x20) ? 25 : 0
                        if i == 0 { return prefix + whole - min(h - n, 40) }
                        if hay[i - 1] == 0x20 { return wordPrefix + whole - min(i + h - n, 40) }
                        if containsAt < 0 { containsAt = i }
                    }
                }
                i += 1
            }
            if strict { return nil }
            if containsAt >= 0 { return contains - min(containsAt + h - n, 80) }
        }
        if strict { return nil }
        // Several words typed in a different order ("lovelace ada"): every word must start a word of the candidate.
        var hasSpace = false, ascii = true
        for k in 0..<n { let b = needle[k]; if b == 0x20 { hasSpace = true }; if b >= 0x80 { ascii = false } }
        if hasSpace {
            var start = 0
            while start < n {
                var end = start
                while end < n, needle[end] != 0x20 { end += 1 }
                if end > start {
                    let part = UnsafeBufferPointer(rebasing: needle[start..<end])
                    guard let sc = scoreRaw(part, hay, fuzzy: false, strict: false), sc >= wordPrefix - 80 else { return nil }
                }
                start = end + 1
            }
            return tokens - min(h, 80)
        }
        guard fuzzy, n >= 3, ascii else { return nil }
        var ni = 0, firstAt = -1, lastAt = -1, idx = 0
        while idx < h, ni < n {
            if hay[idx] == needle[ni] {
                if firstAt < 0 { firstAt = idx }
                lastAt = idx
                ni += 1
            }
            idx += 1
        }
        guard ni == n else { return nil }
        return max(60, 300 - (lastAt - firstAt + 1 - n) * 8 - firstAt)
    }
}

// MARK: - Planner

enum CBSearch {
    static let maxRows = 8

    private static let textVerbs: Set<String> = ["text", "sms", "message", "msg"]
    private static let callVerbs: Set<String> = ["call", "dial"]
    private static let openVerbs: Set<String> = ["open", "launch"]

    /// Verb hint rows ("Text a contact…"): shown while the user is still typing the verb.
    private enum Verb: CaseIterable {
        case text, call, open
        var word: String { switch self { case .text: "text"; case .call: "call"; case .open: "open" } }
    }

    private enum Cmd: CaseIterable {
        case ring, mirror, screenshot, playPause, clipboard, battery
        var aliases: [String] {
            switch self {
            case .ring: ["ring", "ring phone", "find phone", "find my phone", "locate phone"]
            case .mirror: ["mirror", "screen mirror", "mirror screen", "cast"]
            case .screenshot: ["screenshot", "screen shot", "capture screen"]
            case .playPause: ["play", "pause", "play pause", "music", "media"]
            case .clipboard: ["send clipboard", "clipboard", "paste to phone"]
            case .battery: ["battery", "charge", "power"]
            }
        }
        var key: String { "cmd-\(self)" }
    }
    private static let cmdKeys: [(Cmd, [[UInt8]])] = Cmd.allCases.map { c in (c, c.aliases.map(CBText.bytes)) }

    // MARK: Entry point

    static func results(query raw: String, index: CBIndex, ctx: CBContext, recents: [CBRecent]) -> [CBItem] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var out: [CBItem]
        if trimmed.isEmpty {
            out = emptyQuery(ctx: ctx, recents: recents, index: index)
        } else {
            let words = wordRanges(trimmed)
            let verb = CBText.fold(String(trimmed[words[0]]))
            let hasMore = words.count > 1 || raw.last?.isWhitespace == true
            let rest = Array(words.dropFirst())
            if hasMore, textVerbs.contains(verb) {
                out = textRows(text: trimmed, rest: rest, index: index)
            } else if hasMore, callVerbs.contains(verb) {
                out = callRows(text: trimmed, rest: rest, index: index)
            } else if hasMore, openVerbs.contains(verb) {
                let q = rest.isEmpty ? "" : String(trimmed[rest[0].lowerBound...])
                out = openRows(query: q, index: index, ctx: ctx)
            } else {
                out = bareRows(query: trimmed, index: index, ctx: ctx)
            }
        }
        out = finish(out)
        return Array(out.prefix(maxRows))
    }

    /// Ranges of the whitespace-separated words, so the original text (spacing, newlines) can be sliced exactly.
    private static func wordRanges(_ s: String) -> [Range<String.Index>] {
        var out: [Range<String.Index>] = []
        var i = s.startIndex
        while i < s.endIndex {
            if s[i].isWhitespace { i = s.index(after: i); continue }
            var j = i
            while j < s.endIndex, !s[j].isWhitespace { j = s.index(after: j) }
            out.append(i..<j)
            i = j
        }
        return out
    }

    private static func finish(_ items: [CBItem]) -> [CBItem] {
        var seen = Set<String>()
        // Stable by score, then by original order.
        let ordered = items.enumerated().sorted { a, b in
            a.element.score != b.element.score ? a.element.score > b.element.score : a.offset < b.offset
        }.map(\.element)
        return ordered.filter { seen.insert($0.id).inserted }
    }

    // MARK: Empty query

    static func emptyQuery(ctx: CBContext, recents: [CBRecent], index: CBIndex) -> [CBItem] {
        var items: [CBItem] = []
        var rank = 1000.0
        func add(_ i: CBItem?) { if var i { rank -= 1; i.score = rank; items.append(i) } }
        for r in recents.prefix(5) { add(item(for: r, index: index, ctx: ctx)) }
        for c in [Cmd.ring, .screenshot, .clipboard, .mirror, .playPause, .battery] { add(commandItem(c, ctx: ctx)) }
        return items
    }

    /// Rebuilds a row from a remembered action. Names and labels are looked up now, never stored.
    static func item(for r: CBRecent, index: CBIndex, ctx: CBContext) -> CBItem? {
        switch r.kind {
        case "ring": return commandItem(.ring, ctx: ctx)
        case "mirror": return commandItem(.mirror, ctx: ctx)
        case "screenshot": return commandItem(.screenshot, ctx: ctx)
        case "playPause": return commandItem(.playPause, ctx: ctx)
        case "sendClipboard": return commandItem(.clipboard, ctx: ctx)
        case "call": return callItem(number: r.a, name: index.nameByNumber[normalizedNumber(r.a)] ?? r.a, confirm: false)
        case "text": return messageItem(number: r.a, name: index.nameByNumber[normalizedNumber(r.a)] ?? r.a)
        case "openApp":
            guard let label = index.labelByPkg[r.a] else { return nil }
            return openAppItem(pkg: r.a, label: label, ctx: ctx)
        default: return nil
        }
    }

    // MARK: Bare query (commands, contacts, apps, verb hints)

    private static func bareRows(query: String, index: CBIndex, ctx: CBContext) -> [CBItem] {
        let q = CBText.bytes(query)
        let fuzzy = q.count >= 3
        var items: [CBItem] = []

        for (cmd, aliases) in cmdKeys {
            let best = aliases.compactMap { CBScore.score(q, in: $0, fuzzy: fuzzy) }.max()
            if let best, var it = commandItem(cmd, ctx: ctx) { it.score = Double(best) + 40; items.append(it) }
        }
        for v in Verb.allCases where q.count >= 2 {
            if let s = CBScore.score(q, in: Array(v.word.utf8), fuzzy: false), s >= CBScore.prefix - 80 {
                items.append(verbHint(v, score: Double(s) + 20))
            }
        }

        // Contacts: the best match expands to Message / Call / Show contact; the next few are one-line "Message" rows.
        var packed: [Int] = []
        let qDigits = CBText.digits(query)
        let numeric = qDigits.count >= 4 && isNumberOnly(query)
        for (i, e) in index.contacts.enumerated() {
            var s = CBScore.score(q, in: e.key, fuzzy: fuzzy)
            if s == nil, numeric, e.digits.contains(qDigits) { s = CBScore.contains }
            if let s { packed.append(CBScore.pack(s, i)) }
        }
        packed.sort(by: >)   // best first; ties keep index order (the index is already name-sorted)
        if let topPack = packed.first {
            let (ts, ti) = CBScore.unpack(topPack)
            let c = index.contacts[ti].contact, s = Double(ts)
            // A call goes out immediately only when this is the one strict match; anything else asks first.
            var strictCount = 0
            var topStrict = false
            for pk in packed {
                let e = index.contacts[CBScore.unpack(pk).idx]
                if CBScore.strict(q, in: e.key) != nil { strictCount += 1; if pk == topPack { topStrict = true } }
                if strictCount > 1 { break }
            }
            let sure = topStrict && strictCount == 1
            var m = messageItem(number: c.number, name: c.name); m.score = s
            var call = callItem(number: c.number, name: c.name, confirm: !sure); call.score = s - 0.1
            var show = showContactItem(c); show.score = s - 0.2
            items += [m, call, show]
            for (i, pk) in packed.dropFirst().prefix(3).enumerated() {
                let (hs, hi) = CBScore.unpack(pk)
                let oc = index.contacts[hi].contact
                var o = messageItem(number: oc.number, name: oc.name)
                o.score = Double(hs) - 1 - Double(i) * 0.01
                items.append(o)
            }
        }

        // Apps: only strong matches, so a bare "go" doesn't bury everything under apps.
        var appHits: [(CBIndex.AppEntry, Int, Int)] = []
        for e in index.apps { if let s = CBScore.score(q, in: e.key, fuzzy: false), s >= CBScore.wordPrefix - 80 { appHits.append((e, s, appHits.count)) } }
        appHits.sort { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }
        for h in appHits.prefix(2) {
            var it = openAppItem(pkg: h.0.app.pkg, label: h.0.app.label, ctx: ctx)
            it.score = Double(h.1) - 30
            items.append(it)
        }
        return items
    }

    // MARK: Recipient resolution (text and call)

    /// One strict interpretation: this contact, named by the first `k` words.
    private struct Cand { var idx: Int; var score: Int; var k: Int }

    /// Strict matches for the first 1...maxK words, one entry per contact (the interpretation using the most words wins).
    private static func strictCandidates(words: [String], minK: Int = 1, maxK: Int, index: CBIndex) -> [Cand] {
        var best: [Int: Cand] = [:]
        for k in minK...max(minK, min(maxK, words.count)) {
            let q = CBText.bytes(words[0..<k].joined(separator: " "))
            guard !q.isEmpty else { continue }
            for (i, e) in index.contacts.enumerated() {
                guard let s = CBScore.strict(q, in: e.key) else { continue }
                if let old = best[i], old.k > k || (old.k == k && old.score >= s) { continue }
                best[i] = Cand(idx: i, score: s, k: k)
            }
        }
        return best.values.sorted { $0.score != $1.score ? $0.score > $1.score : $0.idx < $1.idx }
    }

    /// Unambiguous = exactly one strict recipient, or the interpretation that uses the most typed words is a single
    /// exact full-name match ("ada lovelace hi" with an "Adam" around). "raj at 5pm" with {Raj, Raj Atwal} is NOT.
    private static func isUnambiguous(_ cands: [Cand]) -> Bool {
        if cands.count == 1 { return true }
        guard let maxK = cands.map(\.k).max() else { return false }
        let top = cands.filter { $0.k == maxK }
        return top.count == 1 && top[0].score >= CBScore.exact
    }

    // MARK: text <name> <message>

    private static func textRows(text: String, rest: [Range<String.Index>], index: CBIndex) -> [CBItem] {
        guard !rest.isEmpty else { return [verbHint(.text, score: 1000, hasArg: true)] }
        let words = rest.map { String(text[$0]) }
        /// The message is the ORIGINAL text after the name words: spacing and newlines untouched.
        func body(after k: Int) -> String { k < rest.count ? String(text[rest[k].lowerBound...]) : "" }

        let cands = strictCandidates(words: words, maxK: 3, index: index)
        if !cands.isEmpty {
            let sure = isUnambiguous(cands)
            return cands.prefix(4).map { c in
                var it = smsItem(index.contacts[c.idx].contact, body: body(after: c.k), confirm: !sure)
                it.score = Double(c.score) + Double(c.k) * 0.5
                return it
            }
        }
        // An explicit number is an explicit recipient.
        if isDialable(words[0]) {
            return [smsItem(Contact(name: words[0], number: words[0], starred: false), body: body(after: 1), confirm: false)]
        }
        // Loose matches (substring, any-order words, fuzzy) are only listed: ↩ opens the conversation, never sends.
        let loose = contactHits(words[0], index: index, fuzzy: true)
        if !loose.isEmpty {
            return loose.prefix(4).enumerated().map { i, c in
                var it = looseItem(c)
                it.score = 800 - Double(i)
                return it
            }
        }
        return [noMatch(words.joined(separator: " "))]
    }

    private static func smsItem(_ c: Contact, body: String, confirm: Bool) -> CBItem {
        guard !body.isEmpty else { return messageItem(number: c.number, name: c.name) }
        return CBItem(id: "sms|\(c.number)", symbol: "paperplane.fill",
                      title: tr("Send “{body}” to {name}", ["body": body, "name": c.name]), subtitle: c.number,
                      primary: .sms(number: c.number, name: c.name, body: body),
                      secondary: .showContact(name: c.name), secondaryTitle: tr("Show contact"), needsLink: true,
                      confirm: confirm,
                      armSubtitle: tr("Press ↩ again to send to {name} ({number})", ["name": c.name, "number": c.number]))
    }

    /// A loose (non-strict) contact hit: opens the conversation, so nothing goes to the wrong person.
    private static func looseItem(_ c: Contact) -> CBItem {
        CBItem(id: "loose|\(c.number)", symbol: "message.fill", title: tr("Message {name}", ["name": c.name]),
               subtitle: tr("Closest match · {number}", ["number": c.number]),
               primary: .openConversation(number: c.number, name: c.name),
               secondary: .showContact(name: c.name), secondaryTitle: tr("Show contact"))
    }

    // MARK: call <name> / dial <number>

    private static func callRows(text: String, rest: [Range<String.Index>], index: CBIndex) -> [CBItem] {
        guard !rest.isEmpty else { return [verbHint(.call, score: 1000, hasArg: true)] }
        let joined = String(text[rest[0].lowerBound...])
        if isDialable(joined) {
            var items: [CBItem] = []
            var it = callItem(number: joined, name: joined, confirm: false); it.score = 1000
            items.append(it)
            // Also a saved contact with this number.
            let d = CBText.digits(joined)
            for e in index.contacts where d.count >= 4 && e.digits.hasSuffix(d) {
                var c = callItem(number: e.contact.number, name: e.contact.name, confirm: false); c.score = 990
                items.append(c)
                if items.count >= 4 { break }
            }
            return items
        }
        let words = rest.map { String(text[$0]) }
        let cands = strictCandidates(words: words, minK: words.count, maxK: words.count, index: index)
        if !cands.isEmpty {
            let sure = cands.count == 1
            return cands.prefix(5).map { c in
                var it = callItem(number: index.contacts[c.idx].contact.number, name: index.contacts[c.idx].contact.name, confirm: !sure)
                it.score = Double(c.score)
                return it
            }
        }
        let loose = contactHits(joined, index: index, fuzzy: true)
        if loose.isEmpty { return [noMatch(joined)] }
        return loose.prefix(5).enumerated().map { i, c in
            var it = callItem(number: c.number, name: c.name, confirm: true)   // loose: always asks first
            it.score = 800 - Double(i)
            return it
        }
    }

    // MARK: open <app>

    private static func openRows(query: String, index: CBIndex, ctx: CBContext) -> [CBItem] {
        if query.isEmpty { return [verbHint(.open, score: 1000, hasArg: true)] }
        if index.apps.isEmpty {
            return [CBItem(id: "apps-loading", symbol: "square.grid.2x2", title: ctx.appsLoading ? tr("Loading apps…") : tr("No apps yet"),
                           subtitle: ctx.connected ? "" : tr("Connect your phone first"), primary: .none, needsLink: !ctx.connected, score: 1000)]
        }
        let q = CBText.bytes(query)
        var hits: [(CBIndex.AppEntry, Int, Int)] = []
        for e in index.apps { if let s = CBScore.score(q, in: e.key, fuzzy: q.count >= 3) { hits.append((e, s, hits.count)) } }
        hits.sort { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }
        if hits.isEmpty { return [noMatch(query)] }
        return hits.prefix(6).map { h in
            var it = openAppItem(pkg: h.0.app.pkg, label: h.0.app.label, ctx: ctx)
            it.score = Double(h.1)
            return it
        }
    }

    // MARK: Row builders

    /// Loose contact hits (any match kind), best first.
    private static func contactHits(_ name: String, index: CBIndex, fuzzy: Bool) -> [Contact] {
        let q = CBText.bytes(name)
        guard !q.isEmpty else { return [] }
        var packed: [Int] = []
        for (i, e) in index.contacts.enumerated() { if let s = CBScore.score(q, in: e.key, fuzzy: fuzzy) { packed.append(CBScore.pack(s, i)) } }
        packed.sort(by: >)
        return packed.prefix(8).map { index.contacts[CBScore.unpack($0).idx].contact }
    }

    static func isDialable(_ s: String) -> Bool { isNumberOnly(s) && CBText.digits(s).count >= 3 }

    private static func messageItem(number: String, name: String) -> CBItem {
        CBItem(id: "msg|\(number)", symbol: "message.fill", title: tr("Message {name}", ["name": name]),
               subtitle: number, primary: .openConversation(number: number, name: name),
               secondary: .showContact(name: name), secondaryTitle: tr("Show contact"))
    }
    private static func callItem(number: String, name: String, confirm: Bool) -> CBItem {
        CBItem(id: "call|\(number)", symbol: "phone.fill", title: tr("Call {name}", ["name": name]),
               subtitle: name == number ? "" : number, primary: .call(number: number, name: name),
               secondary: .showContact(name: name), secondaryTitle: tr("Show contact"), needsLink: true,
               confirm: confirm,
               armSubtitle: tr("Press ↩ again to call {name} ({number})", ["name": name, "number": number]))
    }
    private static func showContactItem(_ c: Contact) -> CBItem {
        CBItem(id: "show|\(c.number)", symbol: "person.crop.circle", title: tr("Show {name}", ["name": c.name]),
               subtitle: tr("Open in Contacts"), primary: .showContact(name: c.name))
    }
    private static func openAppItem(pkg: String, label: String, ctx: CBContext) -> CBItem {
        let mac = ctx.openAppsOnMac && ctx.macWindowPossible
        var it = CBItem(id: "app|\(pkg)", symbol: "app.fill", title: tr("Open {name}", ["name": label]),
                        subtitle: mac ? tr("In a Mac window") : tr("On your phone"),
                        primary: .openApp(pkg: pkg, label: label, onMac: mac), needsLink: !mac)
        if mac {
            it.secondary = .openApp(pkg: pkg, label: label, onMac: false); it.secondaryTitle = tr("On your phone")
        } else if ctx.macWindowPossible {
            it.secondary = .openApp(pkg: pkg, label: label, onMac: true); it.secondaryTitle = tr("In a Mac window")
        }
        return it
    }
    private static func verbHint(_ v: Verb, score: Double, hasArg: Bool = false) -> CBItem {
        switch v {
        case .text:
            CBItem(id: "hint-text", symbol: "message", title: tr("Text a contact…"), subtitle: tr("Type a name, then your message"),
                   primary: hasArg ? .none : .prefill("text "), score: score)
        case .call:
            CBItem(id: "hint-call", symbol: "phone", title: tr("Call a contact…"), subtitle: tr("Type a name or a number"),
                   primary: hasArg ? .none : .prefill("call "), score: score)
        case .open:
            CBItem(id: "hint-open", symbol: "square.grid.2x2", title: tr("Open a phone app…"), subtitle: tr("Type an app name"),
                   primary: hasArg ? .none : .prefill("open "), score: score)
        }
    }
    private static func noMatch(_ q: String) -> CBItem {
        CBItem(id: "none", symbol: "magnifyingglass", title: tr("No match for “{q}”", ["q": q]), subtitle: "", primary: .none, score: 1000)
    }

    private static func commandItem(_ c: Cmd, ctx: CBContext) -> CBItem? {
        switch c {
        case .ring:
            return CBItem(id: c.key, symbol: "bell.and.waves.left.and.right",
                          title: ctx.ringing ? tr("Stop ringing") : tr("Ring phone"),
                          subtitle: tr("Find your phone by sound"), primary: .ring, needsLink: true)
        case .mirror:
            return CBItem(id: c.key, symbol: "rectangle.on.rectangle",
                          title: ctx.mirroring ? tr("Stop screen mirror") : tr("Start screen mirror"),
                          subtitle: tr("Show the phone screen on this Mac"), primary: .mirror, needsLink: !ctx.mirroring)
        case .screenshot:
            return CBItem(id: c.key, symbol: "camera.viewfinder", title: tr("Take phone screenshot"),
                          subtitle: tr("Saved to Downloads"), primary: .screenshot, needsLink: true)
        case .playPause:
            return CBItem(id: c.key, symbol: ctx.mediaPlaying ? "pause.fill" : "play.fill",
                          title: ctx.mediaPlaying ? tr("Pause phone media") : tr("Play phone media"),
                          subtitle: ctx.mediaActive ? "" : tr("Nothing is playing on the phone"), primary: .playPause, needsLink: true)
        case .clipboard:
            return CBItem(id: c.key, symbol: "doc.on.clipboard", title: tr("Send Mac clipboard to phone"),
                          subtitle: tr("Copies what you copied here"), primary: .sendClipboard, needsLink: true)
        case .battery:
            if ctx.connected, let b = ctx.battery {
                return CBItem(id: c.key, symbol: ctx.charging ? "battery.100.bolt" : batterySymbol(b),
                              title: tr("Phone battery {percent}%", ["percent": b]),
                              subtitle: ctx.charging ? tr("Charging") : tr("Not charging"), primary: .none)
            }
            return CBItem(id: c.key, symbol: "battery.50", title: tr("Phone battery"),
                          subtitle: ctx.connected ? tr("Level not reported yet") : tr("Connect your phone first"),
                          primary: .none, needsLink: !ctx.connected)
        }
    }

    private static func batterySymbol(_ p: Int) -> String {
        switch p { case ..<13: "battery.0"; case ..<38: "battery.25"; case ..<63: "battery.50"; case ..<88: "battery.75"; default: "battery.100" }
    }
}
