//! Release signer for the in-app updater.
//!
//!   DD_UPDATE_SIGNING_KEY=<base64 32-byte seed> cargo run --example sign_update -- sign DroidDock-3.0.1-mac.zip
//!     writes DroidDock-3.0.1-mac.zip.sig (base64 Ed25519 signature of the file's exact bytes)
//!   cargo run --example sign_update -- keygen ~/.droiddock-release/update-signing-key
//!     writes a new seed (mode 600) and prints only the PUBLIC key, base64
//!
//! The public half is embedded in `src/updater.rs` (`UPDATE_PUBLIC_KEY`).

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use ed25519_dalek::{Signer, SigningKey};
use std::os::unix::fs::OpenOptionsExt;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["keygen", path] => {
            let mut seed = [0u8; 32];
            getrandom::fill(&mut seed).expect("random");
            let key = SigningKey::from_bytes(&seed);
            let mut f = std::fs::OpenOptions::new()
                .write(true).create_new(true).mode(0o600).open(path)
                .unwrap_or_else(|e| die(&format!("{path}: {e} (refusing to overwrite)")));
            std::io::Write::write_all(&mut f, B64.encode(seed).as_bytes()).unwrap();
            println!("{}", B64.encode(key.verifying_key().to_bytes()));
        }
        ["sign", file] => {
            let seed_b64 = std::env::var("DD_UPDATE_SIGNING_KEY")
                .unwrap_or_else(|_| die("DD_UPDATE_SIGNING_KEY is not set"));
            let seed: [u8; 32] = B64.decode(seed_b64.trim()).ok()
                .and_then(|v| v.try_into().ok())
                .unwrap_or_else(|| die("DD_UPDATE_SIGNING_KEY must be a base64 32-byte seed"));
            let bytes = std::fs::read(file).unwrap_or_else(|e| die(&format!("{file}: {e}")));
            let sig = SigningKey::from_bytes(&seed).sign(&bytes);
            std::fs::write(format!("{file}.sig"), B64.encode(sig.to_bytes())).unwrap();
            println!("signed {file}");
        }
        _ => die("usage: sign_update sign <file> | keygen <path>"),
    }
}

fn die(m: &str) -> ! {
    eprintln!("sign_update: {m}");
    std::process::exit(1)
}
