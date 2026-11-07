#if DEBUG || DD_BENCH || OTP_STANDALONE
import Foundation

/// `DD_SELFTEST_OTP=1 mac/.build/debug/DroidDock` checks `OTPDetector` against realistic SMS templates, prints, exits.
/// (SwiftPM has no test target here, so this follows `CommandBarSelfTest`; Step B wires it into AppDelegate.)
enum OTPDetectorSelfTest {
    static func runIfRequested() -> Bool {
        guard ProcessInfo.processInfo.environment["DD_SELFTEST_OTP"] != nil else { return false }
        let fails = run()
        print(fails.isEmpty ? "OTP SELFTEST PASS" : "OTP SELFTEST FAIL"); fflush(stdout)
        exit(fails.isEmpty ? 0 : 1)
    }

    static let positives: [(String, String)] = [
        ("Your verification code is 482913. Do not share it.", "482913"),
        ("482913 is your login code for Acme", "482913"),
        ("Use OTP 7391 to sign in. Valid for 5 minutes.", "7391"),
        ("G-123456 is your Google verification code.", "123456"),
        ("Your verification code is AB12CD, expires soon", "AB12CD"),
        ("Your OTP is 123456. Use promo code SAVE20 at checkout", "123456"),
        ("Your security code is 123-456", "123456"),
        ("Your passcode: 123 456", "123456"),
        ("Your PIN is 4821", "4821"),
        ("Your one-time password is 93817265", "93817265"),
        ("Your 2FA code is 556677", "556677"),
        ("Your order code is 483920", "483920"),
        ("Tu código de verificación es 482913", "482913"),
        ("Votre code à usage unique est 482913", "482913"),
        ("Dein Bestätigungscode lautet 482913", "482913"),
        ("Il tuo codice di verifica è 482913", "482913"),
        ("Seu código de verificação é 482913", "482913"),
        ("Doğrulama kodunuz: 482913", "482913"),
        ("Kode verifikasi Anda adalah 482913", "482913"),
        ("Mã xác minh của bạn là 482913", "482913"),
        ("Ваш код подтверждения: 482913", "482913"),
        ("رمز التحقق الخاص بك هو ٤٨٢٩١٣", "482913"),
        ("आपका ओटीपी ४८२९१३ है", "482913"),
        ("আপনার ওটিপি কোড ৪৮২৯১৩", "482913"),
        ("認証コードは482913です", "482913"),
        ("[Acme] 인증번호는 482913 입니다", "482913"),
        ("您的验证码是 482913，5分钟内有效", "482913"),
        ("您的驗證碼是 482913", "482913"),
        ("您的動態密碼為 482913", "482913"),
        ("Your code is １２３４５６", "123456"),
    ]

    static let negatives: [(String, String)] = [
        ("Your card was charged $123456 charged", "currency symbol"),
        ("Call 9876543210", "phone number, no keyword"),
        ("Your code is ready at 12:30", "time"),
        ("Order 483920 shipped", "order, no keyword"),
        ("Your balance 1,234.50", "decimal, no keyword"),
        ("Verification code for 9876543210 sent", "9+ digits"),
        ("Your code will be sent. Balance: 1,234.50", "thousands/decimal"),
        ("Your code costs Rs 123456", "currency word"),
        ("Verification code discount 123456%", "percent"),
        ("Verification code: 2024-11-07", "date"),
        ("Verification code: 07/11/2024", "date slashes"),
        ("Verify this ref ID 483920 with code", "ref id nearer than keyword"),
        ("Hello, your number is 482913", "no keyword"),
        ("Your verification code is below. Please keep this message and do not share it with anybody at all 482913", "keyword too far"),
        ("Your code: 555-123-4567", "phone chain"),
        ("Use code SAVE20 at checkout", "promo code, weak keyword only"),
        ("Your code is AB12CD, expires soon", "letters+digits need a strong keyword"),
        ("Zip code 90210", "zip"),
        ("PIN code 560001", "postal PIN"),
        ("Promo code 482913 gives 10% off", "promo"),
        ("Use coupon code 123456 today", "coupon"),
        ("Votre code postal 75001", "fr postal"),
    ]

    static func run() -> [String] {
        var fails = [String]()
        for (text, want) in positives {
            let got = OTPDetector.extract(title: nil, text: text)
            if got != want { fails.append("expected \(want) got \(got ?? "nil"): \(text)") }
        }
        for (text, why) in negatives {
            let got = OTPDetector.extract(title: nil, text: text)
            if got != nil { fails.append("expected nil (\(why)) got \(got!): \(text)") }
        }
        if OTPDetector.extract(title: "Acme", text: "482913 is your verification code") != "482913" { fails.append("title+text") }
        for f in fails { print("FAIL: \(f)") }
        return fails
    }
}
#endif
