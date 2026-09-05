//! The Google Nearby protobuf schemas, generated at build time by `protox` +
//! `prost-build` from the vendored `proto/` directory (Apache-2.0).
//!
//! Namespaced to match the wire packages so the message names read the same
//! here as they do in the spec and in Android's logcat, which is the only
//! practical way to debug this protocol.
#![allow(clippy::all)]

// prost generates the full `pub mod` nesting (`securegcm`, `securemessage`,
// `location::nearby::connections`, `sharing::nearby`,
// `location::nearby::proto::sharing`) into this one file, so the cross-package
// `super::` references between the schemas resolve as generated.
include!(concat!(env!("OUT_DIR"), "/_protos.rs"));
