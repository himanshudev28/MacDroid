//! Build script.
//!
//! Besides Tauri's own codegen, this compiles the Google Nearby protobuf
//! schemas that Quick Share speaks (see `src/quickshare/`).
//!
//! Compiled with `protox` — a pure-Rust protobuf compiler — rather than
//! `prost-build`'s default of shelling out to `protoc`. A clean checkout of
//! this repo must build with nothing but a Rust toolchain; requiring a system
//! protobuf install would be a new setup step for every contributor and every
//! CI runner, to produce byte-identical output.

use std::path::PathBuf;

/// Google's Nearby schemas, vendored under `proto/` (Apache-2.0). They import
/// each other by bare filename, so `proto/` is also the include path.
const PROTOS: &[&str] = &[
    "device_to_device_messages.proto",
    "offline_wire_formats.proto",
    "securegcm.proto",
    "securemessage.proto",
    "sharing_enums.proto",
    "ukey.proto",
    "wire_format.proto",
];

fn main() {
    let dir = PathBuf::from("proto");
    let files: Vec<PathBuf> = PROTOS.iter().map(|f| dir.join(f)).collect();

    let fds = protox::compile(&files, [&dir]).expect("protox: failed to compile Nearby protos");

    let mut cfg = prost_build::Config::new();
    // One nested include file rather than five flat ones. These schemas
    // reference each other across packages (`sharing.nearby` uses
    // `location.nearby.proto.sharing`), and prost emits those as `super::`
    // paths whose depth assumes the generated modules are nested to match the
    // package names. Including the per-package files by hand flattens that and
    // the paths no longer resolve.
    cfg.include_file("_protos.rs");
    cfg.compile_fds(fds).expect("prost-build: codegen failed");

    for f in &files {
        println!("cargo:rerun-if-changed={}", f.display());
    }

    tauri_build::build()
}
