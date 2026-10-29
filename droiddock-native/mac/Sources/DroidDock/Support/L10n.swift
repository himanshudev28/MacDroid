import Foundation
import Observation

/// Localization with the English text as the key (mirrors droiddock-tauri's i18n.ts).
///
/// Usage in views: call `tr("Setup check")` / `tn("1 file", "{n} files", count)` inside `body`.
/// `tr` reads an observable revision, so any view whose body calls it re-renders
/// automatically when `L10n.shared.choice` changes. No `@Environment` plumbing needed.
/// Settings picker: `Picker(selection: Binding(get: { L10n.shared.choice }, set: { L10n.shared.choice = $0 }))`
/// over `L10n.languages`. Root view: `.environment(\.layoutDirection, L10n.shared.isRTL ? .rightToLeft : .leftToRight)`.
public struct Language: Sendable, Identifiable, Hashable {
    public let code: String      // matches the TS tag: "de", "zh", "zh-Hant"
    public let name: String      // native display name
    public let lproj: String     // Apple .lproj directory name
    public let rtl: Bool
    public var id: String { code }
}

public final class L10n: Observable, @unchecked Sendable {
    public static let shared = L10n()
    public static let defaultsKey = "droiddock.language"
    public static let system = "system"

    /// Same order as the TS LOCALES list.
    public static let languages: [Language] = [
        Language(code: "en", name: "English", lproj: "en", rtl: false),
        Language(code: "ar", name: "العربية", lproj: "ar", rtl: true),
        Language(code: "bn", name: "বাংলা", lproj: "bn", rtl: false),
        Language(code: "de", name: "Deutsch", lproj: "de", rtl: false),
        Language(code: "es", name: "Español", lproj: "es", rtl: false),
        Language(code: "fr", name: "Français", lproj: "fr", rtl: false),
        Language(code: "hi", name: "हिन्दी", lproj: "hi", rtl: false),
        Language(code: "id", name: "Bahasa Indonesia", lproj: "id", rtl: false),
        Language(code: "it", name: "Italiano", lproj: "it", rtl: false),
        Language(code: "ja", name: "日本語", lproj: "ja", rtl: false),
        Language(code: "ko", name: "한국어", lproj: "ko", rtl: false),
        Language(code: "pt", name: "Português", lproj: "pt", rtl: false),
        Language(code: "ru", name: "Русский", lproj: "ru", rtl: false),
        Language(code: "tr", name: "Türkçe", lproj: "tr", rtl: false),
        Language(code: "vi", name: "Tiếng Việt", lproj: "vi", rtl: false),
        Language(code: "zh", name: "简体中文", lproj: "zh-Hans", rtl: false),
        Language(code: "zh-Hant", name: "繁體中文", lproj: "zh-Hant", rtl: false),
    ]

    private struct State {
        var choice: String
        var bundle: Bundle?     // nil => English (keys are the text)
        var language: Language
    }

    private let registrar = ObservationRegistrar()
    private let lock = NSLock()
    private var state: State

    private init() {
        let stored = UserDefaults.standard.string(forKey: L10n.defaultsKey) ?? L10n.system
        state = L10n.makeState(choice: stored)
    }

    /// "system" or a language code. Setting persists to UserDefaults and re-renders observers.
    public var choice: String {
        get {
            registrar.access(self, keyPath: \.choice)
            return withState { $0.choice }
        }
        set {
            registrar.withMutation(of: self, keyPath: \.choice) {
                let new = L10n.makeState(choice: newValue)
                lock.lock(); state = new; lock.unlock()
                if newValue == L10n.system {
                    UserDefaults.standard.removeObject(forKey: L10n.defaultsKey)
                } else {
                    UserDefaults.standard.set(newValue, forKey: L10n.defaultsKey)
                }
            }
        }
    }

    /// The language actually in use after resolving "system".
    public var current: Language {
        registrar.access(self, keyPath: \.choice)
        return withState { $0.language }
    }

    public var isRTL: Bool { current.rtl }

    private func withState<T>(_ f: (State) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return f(state)
    }

    /// Mirrors i18n.ts `activeTag`: primary subtag of the first preferred language,
    /// else English. Deviation: Traditional-Chinese variants map to zh-Hant
    /// (the TS code would send them to zh).
    static func resolve(_ choice: String, preferred: [String] = Locale.preferredLanguages) -> Language {
        let wanted = choice == system ? (preferred.first ?? "en") : choice
        let parts = wanted.replacingOccurrences(of: "_", with: "-").split(separator: "-").map(String.init)
        let primary = (parts.first ?? "en").lowercased()
        if primary == "zh" {
            let rest = parts.dropFirst().map { $0.lowercased() }
            if wanted == "zh-Hant" || rest.contains("hant") || rest.contains("tw") || rest.contains("hk") || rest.contains("mo") {
                return languages.first { $0.code == "zh-Hant" }!
            }
        }
        return languages.first { $0.code == primary } ?? languages[0]
    }

    private static func makeState(choice: String) -> State {
        let lang = resolve(choice)
        var bundle: Bundle?
        if lang.code != "en",
           let path = Bundle.main.path(forResource: lang.lproj, ofType: "lproj") {
            bundle = Bundle(path: path)
        }
        return State(choice: choice, bundle: bundle, language: lang)
    }

    // MARK: Lookup

    public func translate(_ english: String, _ args: [String: any CustomStringConvertible] = [:]) -> String {
        registrar.access(self, keyPath: \.choice)
        let bundle = withState { $0.bundle }
        let raw = bundle?.localizedString(forKey: english, value: english, table: nil) ?? english
        return L10n.substitute(raw, args)
    }

    /// `{name}` -> args[name]; unknown placeholders are left intact (same as the TS).
    static func substitute(_ text: String, _ args: [String: any CustomStringConvertible]) -> String {
        guard !args.isEmpty, text.contains("{") else { return text }
        var out = ""
        var i = text.startIndex
        while i < text.endIndex {
            if text[i] == "{", let close = text[i...].firstIndex(of: "}") {
                let name = String(text[text.index(after: i)..<close])
                if !name.isEmpty, name.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }),
                   let v = args[name] {
                    out += v.description
                    i = text.index(after: close)
                    continue
                }
            }
            out.append(text[i])
            i = text.index(after: i)
        }
        return out
    }
}

/// Translate `english`, substituting `{name}` placeholders from `args`.
public func tr(_ english: String, _ args: [String: any CustomStringConvertible] = [:]) -> String {
    L10n.shared.translate(english, args)
}

/// Singular/plural: picks `one` when count == 1, else `other`; `{n}` and `{count}` are the count.
public func tn(_ one: String, _ other: String, _ count: Int,
               _ args: [String: any CustomStringConvertible] = [:]) -> String {
    var merged: [String: any CustomStringConvertible] = ["n": count, "count": count]
    for (k, v) in args { merged[k] = v }
    return L10n.shared.translate(count == 1 ? one : other, merged)
}
