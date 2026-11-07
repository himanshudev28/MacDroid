import Foundation

/// Finds a one-time / verification code in a notification. Pure (no UI, core or defaults) so it is unit-testable.
/// Strict by design: a keyword within ~40 characters is REQUIRED, so a bare 6-digit number is never returned.
enum OTPDetector {
    static let keywordWindow = 40
    static let refWindow = 12

    static func extract(title: String?, text: String?) -> String? {
        var raw = [title, text].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
        if raw.isEmpty { return nil }
        if raw.count > 1000 { raw = String(raw.prefix(1000)) }
        let (n, l) = normalize(raw)
        let kws = spans(of: keywords, in: l)
        if kws.isEmpty { return nil }
        let cands = candidates(n)
        if cands.isEmpty { return nil }
        let refs = spans(of: refWords, in: l)
        let excl = spans(of: exclusionWords, in: l)
        let strongKws = kws.filter { $0.strong }

        var best: (score: Int, value: String)?
        for c in cands {
            var score = Int.max, raw = Int.max
            // A bare "code" is not enough for a letters-and-digits token ("Use code SAVE20"): it needs a real OTP word.
            if c.mixed, !strongKws.contains(where: { distance($0, c) <= keywordWindow }) { continue }
            for k in kws {
                let d: Int, after: Bool
                if k.e <= c.s { d = c.s - k.e; after = true }
                else if k.s >= c.e { d = k.s - c.e; after = false }
                else { continue }
                guard d <= keywordWindow else { continue }
                score = min(score, d + (after ? 0 : 5))
                raw = min(raw, d)
            }
            if score == Int.max { continue }
            // "zip code 90210", "PIN code 560001", "promo code …": a code, but not a one-time one.
            if let ex = excl.filter({ $0.e <= c.s && c.s - $0.e <= refWindow }).max(by: { $0.e < $1.e }) {
                let strongNear = strongKws.map { distance($0, c) }.min() ?? Int.max
                if !(strongNear < c.s - ex.e) { continue }
            }
            // "order / ref / id …" just before it, with no keyword nearer: not a code.
            if let ref = refs.filter({ $0.e <= c.s && c.s - $0.e <= refWindow }).max(by: { $0.e < $1.e }),
               !(raw < c.s - ref.e) { continue }
            if best == nil || score < best!.score { best = (score, c.value) }
        }
        return best?.value
    }

    private static func distance(_ k: Span, _ c: Cand) -> Int {
        k.e <= c.s ? c.s - k.e : (k.s >= c.e ? k.s - c.e : 0)
    }

    // MARK: Normalisation

    /// `n` keeps case (the alphanumeric rule needs capitals); `l` is the same characters lower-cased, index-aligned.
    static func normalize(_ s: String) -> (n: [Character], l: [Character]) {
        var u = String.UnicodeScalarView()
        for sc in s.precomposedStringWithCanonicalMapping.unicodeScalars { u.append(mapDigit(sc)) }
        var str = String(u)
        // Vietnamese "mã" would fold to the Spanish/Italian word "ma": keep a private marker.
        str = str.replacingOccurrences(of: "mã", with: "maqvi", options: .caseInsensitive)
        let n = Array(str.folding(options: [.diacriticInsensitive], locale: nil))
        return (n, n.map { Character(String($0).lowercased().first.map(String.init) ?? String($0)) })
    }

    private static func mapDigit(_ u: Unicode.Scalar) -> Unicode.Scalar {
        let v = u.value
        for base in [0x0660, 0x06F0, 0x0966, 0x09E6] where v >= base && v <= base + 9 {
            return Unicode.Scalar(0x30 + (v - UInt32(base)))!
        }
        // Full-width digits and letters only: full-width punctuation (，。) must NOT become a decimal point.
        if (0xFF10...0xFF19).contains(v) || (0xFF21...0xFF3A).contains(v) || (0xFF41...0xFF5A).contains(v) {
            return Unicode.Scalar(v - 0xFF00 + 0x20)!
        }
        return u
    }

    // MARK: Keywords

    private struct Word { let chars: [Character]; let kind: Kind; let strictRight: Bool; var strong = false }
    private enum Kind { case latin, cyrillic, substring }
    private struct Span { let s: Int; let e: Int; var strong = false }

    private static func word(_ w: String, strictRight: Bool? = nil, strong: Bool = false) -> Word {
        let chars = Array(normalize(w).l)
        var kind = Kind.latin
        for sc in w.unicodeScalars {
            switch sc.value {
            case 0x0400...0x04FF: kind = .cyrillic
            case 0x0600...0x06FF, 0x0900...0x09FF, 0x3040...0x30FF, 0x4E00...0x9FFF, 0xAC00...0xD7AF: kind = .substring
            default: break
            }
            if kind == .substring { break }
        }
        // Agglutinative / compounding languages (kodunuz, codes): allow a suffix on longer Latin keywords.
        let strict = strictRight ?? (chars.count < 3)
        return Word(chars: chars, kind: kind, strictRight: strict, strong: strong)
    }

    /// Strong = specific to one-time codes. Weak words ("code", "pin", "kod"…) also appear in promo codes and postcodes.
    private static let keywords: [Word] = {
        let strongLatin = ["otp", "passcode", "one-time", "one time", "verifica", "security code", "login code", "sign-in",
                           "2fa", "bestatigungscode", "sicherheitscode", "einmalig", "a usage unique", "dogrulama",
                           "verifikasi", "xac minh"]
        let weakLatin = ["code", "codigo", "clave", "codice", "kod", "sifre", "kode", "kata sandi"]
        let strongOther = ["подтверждения", "تحقق", "ओटीपी", "सत्यापन", "ওটিপি", "認証コード", "確認コード", "ワンタイム",
                           "인증번호", "인증 코드", "验证码", "校验码", "动态码", "驗證碼", "驗證代碼", "動態密碼"]
        let weakOther = ["код", "пароль", "رمز", "كود", "कोड", "কোড"]
        return strongLatin.map { word($0, strong: true) } + weakLatin.map { word($0) }
            + [word("pin", strictRight: true), word("maqvi", strictRight: true)]
            + strongOther.map { word($0, strong: true) } + weakOther.map { word($0) }
    }()

    /// Contexts where a nearby "code" is a postcode, coupon or similar.
    private static let exclusionWords: [Word] = [
        "zip", "postal", "postcode", "pin code", "pincode", "pin-code", "area code", "country code", "dial code", "promo", "coupon",
        "discount", "voucher", "referral", "gift card", "giftcard", "sale code",
        "codigo postal", "cupon", "descuento", "code postal", "bon de reduction", "postleitzahl", "plz", "gutschein", "rabatt",
        "codice postale", "sconto", "cep", "cupom", "desconto", "posta kodu", "indirim", "kupon", "kode pos", "diskon",
        "khuyen mai", "giam gia", "индекс", "промокод", "купон", "скидк", "كوبون", "خصم", "بريدي", "पिन कोड", "कूपन", "छूट",
        "পিন কোড", "郵便番号", "クーポン", "割引", "우편번호", "쿠폰", "할인", "邮政编码", "邮编", "优惠码", "优惠券", "折扣",
        "郵遞區號", "優惠碼", "優惠券",
    ].map { word($0) }

    private static let refWords: [Word] = ["order", "orders", "ref", "reference", "id", "invoice", "tracking", "account", "acct"]
        .map { word($0, strictRight: true) }

    private static func isWordChar(_ c: Character) -> Bool { c.isLetter || c.isNumber }

    private static func spans(of words: [Word], in l: [Character]) -> [Span] {
        var out = [Span]()
        for w in words {
            let m = w.chars.count
            guard m > 0, l.count >= m else { continue }
            var i = 0
            while i <= l.count - m {
                if l[i] == w.chars[0], Array(l[i..<(i + m)]) == w.chars {
                    var ok = true
                    if w.kind != .substring, i > 0, isWordChar(l[i - 1]) { ok = false }
                    if ok, w.kind == .latin, w.strictRight, i + m < l.count, isWordChar(l[i + m]) { ok = false }
                    if ok { out.append(Span(s: i, e: i + m, strong: w.strong)) }
                }
                i += 1
            }
        }
        return out
    }

    // MARK: Candidates

    private struct Cand { let s: Int; let e: Int; let value: String; var mixed = false }
    private struct Run { let s: Int; let e: Int; let digits: Bool }

    private static func isAsciiDigit(_ c: Character) -> Bool { c.isASCII && c.isNumber }
    private static func isAsciiAlnum(_ c: Character) -> Bool { c.isASCII && (c.isLetter || c.isNumber) }

    private static func candidates(_ n: [Character]) -> [Cand] {
        var runs = [Run]()
        var i = 0
        while i < n.count {
            if isAsciiAlnum(n[i]) {
                var j = i
                while j < n.count, isAsciiAlnum(n[j]) { j += 1 }
                runs.append(Run(s: i, e: j, digits: n[i..<j].allSatisfy(isAsciiDigit)))
                i = j
            } else { i += 1 }
        }
        // Separator joining two digit runs: exactly one '-' or ' '.
        func sep(_ k: Int) -> Character? {
            guard k + 1 < runs.count, runs[k].digits, runs[k + 1].digits, runs[k + 1].s == runs[k].e + 1 else { return nil }
            let c = n[runs[k].e]
            return (c == "-" || c == " ") ? c : nil
        }
        var out = [Cand]()
        var k = 0
        while k < runs.count {
            let r = runs[k]
            if !r.digits {
                if let c = mixed(r, n) { out.append(c) }
                k += 1; continue
            }
            var last = k
            if let sc = sep(k) { while sep(last) == sc { last += 1 } }
            let parts = Array(runs[k...last])
            if parts.count >= 2 {
                let sc = n[runs[k].e]
                if sc == "-" {
                    let ok = parts.count == 2 && parts.allSatisfy { (3...4).contains($0.e - $0.s) }
                    if ok, edgeOK(parts[0].s, parts[1].e, n) { out.append(Cand(s: parts[0].s, e: parts[1].e, value: parts.map { String(n[$0.s..<$0.e]) }.joined())) }
                    k = last + 1; continue
                }
                if parts.count == 2, parts.allSatisfy({ (3...4).contains($0.e - $0.s) }) {
                    if edgeOK(parts[0].s, parts[1].e, n) { out.append(Cand(s: parts[0].s, e: parts[1].e, value: parts.map { String(n[$0.s..<$0.e]) }.joined())) }
                    k = last + 1; continue
                }
                if parts.count >= 3, parts.allSatisfy({ (3...4).contains($0.e - $0.s) }) { k = last + 1; continue }
                // A space between unrelated numbers: judge this run alone.
            }
            if (4...8).contains(r.e - r.s), edgeOK(r.s, r.e, n) { out.append(Cand(s: r.s, e: r.e, value: String(n[r.s..<r.e]))) }
            k += 1
        }
        return out
    }

    private static func mixed(_ r: Run, _ n: [Character]) -> Cand? {
        guard (5...8).contains(r.e - r.s) else { return nil }
        let t = n[r.s..<r.e]
        guard t.allSatisfy({ isAsciiDigit($0) || ($0.isUppercase && $0.isASCII) }),
              t.contains(where: isAsciiDigit), t.contains(where: { !isAsciiDigit($0) }) else { return nil }
        if r.s > 0, "/@=.".contains(n[r.s - 1]) { return nil }
        if r.e < n.count, "/@".contains(n[r.e]) { return nil }
        if r.e + 1 < n.count, n[r.e] == ".", n[r.e + 1].isLetter { return nil }
        return Cand(s: r.s, e: r.e, value: String(t), mixed: true)
    }

    private static let currencySymbols: Set<Character> = ["$", "₹", "€", "£", "¥"]
    private static let currencyWords: Set<String> = ["rs", "inr", "usd", "eur", "gbp", "aud", "cad", "dollars", "dollar", "rupees", "euros"]

    /// Neighbour checks for a digit span [s, e): decimals, times, dates, phone numbers, currency, percentages.
    private static func edgeOK(_ s: Int, _ e: Int, _ n: [Character]) -> Bool {
        let cnt = n.count
        if s > 0 {
            let p = n[s - 1]
            if ".,:/".contains(p), s >= 2, isAsciiDigit(n[s - 2]) { return false }
            if p == "-", s >= 2, isAsciiDigit(n[s - 2]) { return false }
            if p == "+" { return false }
        }
        if e < cnt {
            let q = n[e]
            if ".,:/".contains(q), e + 1 < cnt, isAsciiDigit(n[e + 1]) { return false }
            if q == "-", e + 1 < cnt, isAsciiDigit(n[e + 1]) { return false }
        }
        // Currency before.
        var b = s - 1
        if b >= 0, n[b] == " " { b -= 1 }
        if b >= 0 {
            if currencySymbols.contains(n[b]) { return false }
            var w = b
            if n[w] == "." { w -= 1 }
            var word = ""
            while w >= 0, n[w].isLetter { word = String(n[w]).lowercased() + word; w -= 1 }
            if !word.isEmpty, currencyWords.contains(word) { return false }
        }
        // Currency / percent after.
        var a = e
        while a < cnt, n[a] == " " { a += 1 }
        if a < cnt {
            if n[a] == "%" || currencySymbols.contains(n[a]) { return false }
            var word = ""
            var w = a
            while w < cnt, n[w].isLetter { word += String(n[w]).lowercased(); w += 1 }
            if !word.isEmpty, currencyWords.contains(word) { return false }
        }
        return true
    }
}

#if !OTP_STANDALONE
extension PhoneNotification {
    /// The verification code in this notification, if it looks like one.
    var otp: String? {
        if type == "call" { return nil }
        return OTPDetector.extract(title: title, text: text)
    }
}
#endif
