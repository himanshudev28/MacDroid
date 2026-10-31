#if DEBUG || DD_BENCH
import Foundation

/// `DD_SELFTEST_CMDBAR=1 mac/.build/debug/DroidDock` checks the command bar's matching/ranking and times it with
/// 3,000 contacts + 300 apps. Runs before the core starts (no port bound, no data dir touched), prints, exits.
enum CommandBarSelfTest {
    static func runIfRequested() -> Bool {
        guard ProcessInfo.processInfo.environment["DD_SELFTEST_CMDBAR"] != nil else { return false }
        DispatchQueue.main.async {
            let ok = MainActor.assumeIsolated { run() }
            print(ok ? "CMDBAR SELFTEST PASS" : "CMDBAR SELFTEST FAIL"); fflush(stdout)
            exit(ok ? 0 : 1)
        }
        return true
    }

    #if DEBUG
    /// `DD_CMDBAR_DEMO=<query>`: open the bar a second after launch with fake data; prints the window number.
    @MainActor static func demoIfRequested() {
        guard let q = ProcessInfo.processInfo.environment["DD_CMDBAR_DEMO"] else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            CommandBarController.shared.debugDemo(query: q == "-" ? "" : q)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                print("CMDBAR WINDOW \(CommandBarController.shared.debugWindowNumber)"); fflush(stdout)
                // Warm reopen timing (panel already built): logged by `show()` as "commandbar: open took".
                if ProcessInfo.processInfo.environment["DD_CMDBAR_REOPEN"] != nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { CommandBarController.shared.hide() }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 4) { CommandBarController.shared.show() }
                }
            }
        }
    }

    #endif

    @MainActor private static func run() -> Bool {
        var failures = 0
        func check(_ cond: Bool, _ what: String) {
            if !cond { failures += 1; print("FAIL: \(what)") }
        }
        let people = [("Ada Lovelace", "+14155550134"), ("José Álvarez", "+34600111222"), ("Mom", "+14155550100"),
                      ("Adam Smith", "+14155550111"), ("Zoë Ångström", "+46701234567")]
        let contacts = people.map { Contact(name: $0.0, number: $0.1, starred: false) }
        let apps = [PhoneApp(pkg: "com.spotify.music", label: "Spotify"), PhoneApp(pkg: "com.whatsapp", label: "WhatsApp"),
                    PhoneApp(pkg: "com.google.android.gm", label: "Gmail"), PhoneApp(pkg: "com.android.chrome", label: "Chrome")]
        let idx = CBIndex(contacts: contacts, apps: apps)
        var ctx = CBContext(); ctx.connected = true; ctx.battery = 82; ctx.charging = true
        func run(_ q: String, _ c: CBContext? = nil) -> [CBItem] { CBSearch.results(query: q, index: idx, ctx: c ?? ctx, recents: []) }

        // Normalisation.
        check(run("jose").first?.title == "Message José Álvarez", "diacritic-insensitive: jose -> José")
        check(run("ANGSTROM").first?.title.contains("Zoë") == true, "case + diacritic: ANGSTROM -> Zoë Ångström")
        // Prefix beats substring: "ad" -> Ada/Adam before anything fuzzy.
        let ad = run("ad").first?.title ?? ""
        check(ad.contains("Ada") || ad.contains("Adam"), "prefix ranking for 'ad' (got \(ad))")
        // A bare name expands to Message / Call / Show.
        let ada = run("ada lov")
        check(ada.prefix(3).map(\.primary) == [.openConversation(number: "+14155550134", name: "Ada Lovelace"),
                                               .call(number: "+14155550134", name: "Ada Lovelace"),
                                               .showContact(name: "Ada Lovelace")], "bare name -> 3 rows")
        // text <name> <message>: longest leading name, rest is the body (original case kept).
        let t = run("text ada Running Late, sorry")
        check(t.first?.primary == .sms(number: "+14155550134", name: "Ada Lovelace", body: "Running Late, sorry"), "text ada <body>")
        check(t.first?.confirm == true, "'ada' also matches Adam: ambiguous, first ↩ only arms")
        check(t.first?.secondary == .showContact(name: "Ada Lovelace"), "⌘↩ on a text row shows the contact")
        check(run("sms ada lovelace hi").first?.primary == .sms(number: "+14155550134", name: "Ada Lovelace", body: "hi"), "two-word name")
        check(run("sms ada lovelace hi").first?.confirm == false, "full exact name is unambiguous: one ↩ sends")
        check(run("text ada lovelace  two  spaces\nand a line").first?.primary == .sms(number: "+14155550134", name: "Ada Lovelace", body: "two  spaces\nand a line"),
              "body keeps double spaces and newlines exactly")
        check(run("text ada one").first?.id == run("text ada two").first?.id, "row id does not include the body")
        check(run("text ada hi").first?.subtitle == "+14155550134", "number shown on every text row")
        check(run("message mom").first?.primary == .openConversation(number: "+14155550100", name: "Mom"), "no body opens the conversation")
        check(run("text +34 600 111 222 hola").first != nil, "number target does not crash")
        // call / dial.
        check(run("call mom").first?.primary == .call(number: "+14155550100", name: "Mom"), "call <name>")
        check(run("dial 555 0199").first?.primary == .call(number: "555 0199", name: "555 0199"), "dial <number>")
        // Commands, aliases and fuzzy.
        check(run("scr").first?.primary == .screenshot, "scr -> screenshot")
        check(run("sshot").contains { $0.primary == .screenshot }, "fuzzy sshot -> screenshot")
        check(run("send clip").first?.primary == .sendClipboard, "send clip -> clipboard")
        check(run("pause").first?.primary == .playPause, "pause")
        check(run("ring").first?.primary == .ring, "ring")
        check(run("mirror").first?.primary == .mirror, "mirror")
        // battery is inline and not actionable.
        let b = run("battery").first
        check(b?.primary == CBAction.none && b?.title == tr("Phone battery {percent}%", ["percent": 82]), "battery row")
        // open <app>, honouring the setting; ⌘↩ is the other one.
        var mac = ctx; mac.openAppsOnMac = true; mac.macWindowPossible = true
        check(run("open spot").first?.primary == .openApp(pkg: "com.spotify.music", label: "Spotify", onMac: false), "open on phone by default")
        check(run("open spot", mac).first?.primary == .openApp(pkg: "com.spotify.music", label: "Spotify", onMac: true), "open on Mac when set")
        check(run("open spot", mac).first?.secondary == .openApp(pkg: "com.spotify.music", label: "Spotify", onMac: false), "⌘↩ does the other")
        check(run("open spot").first?.secondary == CBAction.none, "no ADB -> no Mac-window secondary")
        // Verb hint rows.
        check(run("tex").contains { $0.primary == .prefill("text ") }, "tex -> Text a contact hint")
        // Empty query: suggestions, capped at 8; recents first and deduped.
        let empty = CBSearch.results(query: "", index: idx, ctx: ctx, recents: [CBRecent(kind: "call", a: "+14155550100"), CBRecent(kind: "ring")])
        check(empty.first?.primary == .call(number: "+14155550100", name: "Mom"), "recents first")
        check(empty.count <= CBSearch.maxRows && empty.filter { $0.primary == .ring }.count == 1, "empty query capped and deduped")
        // Disconnected rows are flagged (the view dims them).
        var off = ctx; off.connected = false
        check(run("ring", off).first?.needsLink == true, "ring needs the link")
        check(run("battery", off).first?.subtitle == tr("Connect your phone first"), "battery offline")
        // Nonsense does not produce rows that act.
        check(run("zzzzqqq").allSatisfy { $0.primary == .none }, "gibberish -> nothing actionable")

        // Recipient safety.
        func idxOf(_ list: [(String, String)]) -> CBIndex { CBIndex(contacts: list.map { Contact(name: $0.0, number: $0.1, starred: false) }, apps: []) }
        func plan(_ q: String, _ i: CBIndex) -> [CBItem] { CBSearch.results(query: q, index: i, ctx: ctx, recents: []) }
        // "text raj at 5pm" with {Raj, Raj Atwal}: Raj with "at 5pm" ranks first, nothing sends on one ↩.
        let rajs = idxOf([("Raj", "+1111"), ("Raj Atwal", "+2222")])
        let r1 = plan("text raj at 5pm", rajs)
        check(r1.first?.primary == .sms(number: "+1111", name: "Raj", body: "at 5pm"), "raj at 5pm: Raj + body first (got \(String(describing: r1.first?.primary)))")
        check(r1.allSatisfy { $0.confirm }, "raj at 5pm: every row must be armed first")
        check(r1.contains { $0.primary == .sms(number: "+2222", name: "Raj Atwal", body: "5pm") }, "raj at 5pm: Raj Atwal offered with body 5pm")
        // "Raj" vs "Rajesh".
        let rr = idxOf([("Raj", "+1111"), ("Rajesh", "+3333")])
        check(plan("text raj hi", rr).first?.primary == .sms(number: "+1111", name: "Raj", body: "hi"), "exact Raj ranks above Rajesh")
        check(plan("text raj hi", rr).allSatisfy { $0.confirm }, "Raj/Rajesh: armed")
        check(plan("text rajesh hi", rr).first?.confirm == false, "rajesh only matches Rajesh: one ↩ sends")
        // A contact that is merely close ("tom" ~ "Tim Owen Martin") is listed but never sends.
        let tim = idxOf([("Tim Owen Martin", "+4444")])
        let t1 = plan("text tom running late", tim)
        check(t1.first?.primary == .openConversation(number: "+4444", name: "Tim Owen Martin"), "tom -> Tim Owen Martin only opens the conversation")
        check(!t1.contains { if case .sms = $0.primary { true } else { false } }, "no sms row for a fuzzy recipient")
        // Ties.
        let kp = idxOf([("Raj Kumar", "+5555"), ("Raj Patel", "+6666")])
        let k1 = plan("text raj hi", kp)
        check(k1.count == 2 && k1.allSatisfy { $0.confirm }, "Raj Kumar / Raj Patel tie: both armed")
        // Name vs message word order.
        let hr = idxOf([("Raj", "+1111"), ("Hello Raj", "+7777")])
        let h1 = plan("text Raj hello", hr)
        check(h1.first?.primary == .sms(number: "+1111", name: "Raj", body: "hello"), "text Raj hello: Raj first")
        check(h1.allSatisfy { $0.confirm }, "text Raj hello with a Hello Raj contact: armed")
        // Calls follow the same rule.
        check(plan("call tom", tim).first?.confirm == true, "fuzzy call recipient asks first")
        check(plan("call raj", kp).allSatisfy { $0.confirm }, "ambiguous call asks first")
        check(plan("call raj kumar", kp).first?.confirm == false, "exact full name calls on one ↩")
        // Per-action link need.
        check(CBAction.showContact(name: "x").needsLink == false && CBAction.openApp(pkg: "p", label: "l", onMac: true).needsLink == false, "Show contact / Mac window work offline")
        check(CBAction.sms(number: "1", name: "n", body: "b").needsLink && CBAction.openApp(pkg: "p", label: "l", onMac: false).needsLink, "send / phone launch need the link")

        // Timing: 3,000 contacts + 300 apps.
        var big: [Contact] = []
        let first = ["Ada", "Adam", "José", "Mohammed", "Priya", "Zoë", "Chen", "Olga", "Liam", "Noor"]
        let last = ["Lovelace", "Álvarez", "Smith", "Khan", "Ångström", "Müller", "Nguyen", "Ivanov", "Okafor", "Rossi"]
        for i in 0..<3000 { big.append(Contact(name: "\(first[i % 10]) \(last[(i / 10) % 10]) \(i)", number: "+1415\(String(format: "%07d", i))", starred: false)) }
        let bigApps = (0..<300).map { PhoneApp(pkg: "com.example.app\($0)", label: "App \(last[$0 % 10]) \($0)") }
        let t0 = CFAbsoluteTimeGetCurrent()
        let bigIdx = CBIndex(contacts: big, apps: bigApps)
        let buildMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        let queries = ["a", "ad", "jose", "mohammed kh", "text priya running late", "call zoe", "open app ang", "scr", "xyzzy", "lovelace ada 1"]
        var worst = 0.0, total = 0.0, n = 0
        for _ in 0..<20 {
            for q in queries {
                let s = CFAbsoluteTimeGetCurrent()
                _ = CBSearch.results(query: q, index: bigIdx, ctx: ctx, recents: [])
                let ms = (CFAbsoluteTimeGetCurrent() - s) * 1000
                worst = max(worst, ms); total += ms; n += 1
                if ProcessInfo.processInfo.environment["DD_CMDBAR_VERBOSE"] != nil { print(String(format: "  %.2f ms  %@", ms, q)) }
            }
        }
        print(String(format: "TIMING index build (3000 contacts + 300 apps): %.1f ms", buildMs))
        print(String(format: "TIMING per keystroke: mean %.2f ms, worst %.2f ms over %d searches", total / Double(n), worst, n))
        #if DEBUG
        check(worst < 60, "worst keystroke (unoptimised debug build) under 60 ms")
        #else
        check(worst < 8, "worst keystroke (release) under half a frame")
        #endif
        return failures == 0
    }
}
#endif
