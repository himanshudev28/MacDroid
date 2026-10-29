import Foundation

/// Loose JSON payload (config extras, automation events, anything without a fixed shape).
enum JSONValue: Codable, Sendable, Equatable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "unsupported JSON value") }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    var stringValue: String? { if case .string(let s) = self { s } else { nil } }
    var boolValue: Bool? { if case .bool(let b) = self { b } else { nil } }
    var doubleValue: Double? { if case .number(let n) = self { n } else { nil } }
    var intValue: Int? { doubleValue.map { Int($0) } }
    var arrayValue: [JSONValue]? { if case .array(let a) = self { a } else { nil } }
    var objectValue: [String: JSONValue]? { if case .object(let o) = self { o } else { nil } }
    subscript(key: String) -> JSONValue? { objectValue?[key] }

    /// Foundation representation, for JSONSerialization (command args).
    var any: Any {
        switch self {
        case .null: NSNull()
        case .bool(let v): v
        case .number(let v): v
        case .string(let v): v
        case .array(let v): v.map(\.any)
        case .object(let v): v.mapValues(\.any)
        }
    }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByFloatLiteral, ExpressibleByIntegerLiteral, ExpressibleByNilLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral {
    init(stringLiteral v: String) { self = .string(v) }
    init(booleanLiteral v: Bool) { self = .bool(v) }
    init(floatLiteral v: Double) { self = .number(v) }
    init(integerLiteral v: Int) { self = .number(Double(v)) }
    init(nilLiteral: ()) { self = .null }
    init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(uniqueKeysWithValues: elements))
    }
}

extension KeyedDecodingContainer {
    /// Tolerant read: missing, null or wrong-typed -> `fallback`.
    func get<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }
    /// Tolerant optional read.
    func opt<T: Decodable>(_ key: Key) -> T? {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil)
    }
}
