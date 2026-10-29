//! Replacement for `tauri::ipc::{Channel, InvokeResponseBody}`. A `Channel`
//! sends binary frames to the host's `on_frame` callback on a fixed stream id.

use super::{AppHandle, Result};

pub enum InvokeResponseBody {
    Raw(Vec<u8>),
    Json(String),
}

#[derive(Clone)]
pub struct Channel {
    app: AppHandle,
    stream: u32,
}

impl Channel {
    pub fn new(app: &AppHandle, stream: u32) -> Self {
        Channel { app: app.clone(), stream }
    }

    /// Borrowed delivery: the host callback is synchronous and copies what it
    /// keeps, so the hot frame path needs no owned `Vec` (no per-frame
    /// `to_vec()`).
    pub fn send_slice(&self, bytes: &[u8]) {
        self.app.emit_frame(self.stream, bytes);
    }

    pub fn send(&self, body: InvokeResponseBody) -> Result<()> {
        match body {
            InvokeResponseBody::Raw(bytes) => {
                self.app.emit_frame(self.stream, &bytes);
                Ok(())
            }
            // The native host has no JSON channel: JSON goes through events.
            InvokeResponseBody::Json(_) => Err(super::Error("Channel carries raw frames only".into())),
        }
    }
}
