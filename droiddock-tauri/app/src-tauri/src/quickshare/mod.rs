//! Quick Share (Google Nearby Share) — receive side.
//!
//! Lets any Android, ChromeOS or Windows device send files to this Mac through
//! its own system share sheet, with no DroidDock installed on the sender. That
//! is the whole point: it is not another route for the paired phone, which
//! already has a faster one over the app link.
//!
//! Roles are fixed by direction: **the receiver is the server**. It advertises
//! an mDNS service and accepts TCP connections; the sender discovers and
//! connects. See `PROTOCOL.md` in grishka/NearDrop for the wire details this
//! implements.
//!
//! Sending (Mac → phone) is deliberately not here. Android only starts
//! advertising after it hears a BLE broadcast with a specific service-data
//! prefix, and macOS exposes no API to set that field — so a Mac can never make
//! a phone discoverable on its own. Receiving has no such gap.

// The receive stack is being built in stages: the crypto layer below is
// complete and tested, and is consumed by the connection state machine that
// lands next. Until then its constants read as dead to the compiler.
#![allow(dead_code)]

pub mod connection;
pub mod crypto;
pub mod payload;
pub mod proto;
pub mod secure;
pub mod server;
pub mod wire;
