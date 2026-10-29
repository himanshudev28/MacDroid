//! The payload layer: reassembling chunked transfers into bytes buffers and files.
//!
//! Nearby multiplexes several payloads over one connection, each identified by
//! an id and delivered as `PayloadTransferFrame` chunks carrying an explicit
//! offset. Negotiation messages ride BYTES payloads (small, buffered in memory);
//! the actual files ride FILE payloads and are streamed to disk.
//!
//! Everything here is driven by remote input, so the rules are:
//!   * a payload id we were never told to expect is ignored, not created;
//!   * the file name comes from the *introduction* the user accepted, never
//!     from the payload header that arrives later;
//!   * chunks must arrive in order at the offset we expect, and may not push
//!     the total past the declared size.

use std::collections::HashMap;
use std::fs::File;
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Refuse a single transfer larger than this. Quick Share has no legitimate
/// need to hand a Mac 100 GB unattended, and the declared size is used for
/// progress arithmetic and disk writes.
pub const MAX_PAYLOAD_BYTES: i64 = 64 * 1024 * 1024 * 1024;

/// Cap on a buffered BYTES payload. These are protobuf control messages —
/// kilobytes — so anything approaching this is an attempt to exhaust memory.
pub const MAX_BYTES_PAYLOAD: usize = 8 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum PayloadError {
    Unexpected(i64),
    OutOfOrder { id: i64, expected: i64, got: i64 },
    TooLarge(i64),
    Io(String),
}

impl std::fmt::Display for PayloadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unexpected(id) => write!(f, "payload {id} was never introduced"),
            Self::OutOfOrder { id, expected, got } => {
                write!(f, "payload {id}: chunk at {got}, expected {expected}")
            }
            Self::TooLarge(id) => write!(f, "payload {id} exceeds its declared size"),
            Self::Io(e) => write!(f, "{e}"),
        }
    }
}

/// What a completed chunk produced, if anything.
#[derive(Debug)]
pub enum Completed {
    /// A control message finished assembling; the caller parses it as a frame.
    Bytes { id: i64, data: Vec<u8> },
    /// A file finished writing.
    File { id: i64, path: PathBuf },
    /// Chunk accepted, transfer still in progress.
    Progress { id: i64, received: i64, total: i64 },
}

enum Sink {
    Bytes(Vec<u8>),
    File { handle: File, path: PathBuf },
}

struct Incoming {
    sink: Sink,
    received: i64,
    total: i64,
}

/// Tracks in-flight payloads for one connection.
pub struct Reassembler {
    incoming: HashMap<i64, Incoming>,
    /// Files the sender declared in its introduction: payload id → the name the
    /// user was shown and accepted.
    expected_files: HashMap<i64, String>,
    dest_dir: PathBuf,
}

impl Reassembler {
    pub fn new(dest_dir: impl Into<PathBuf>) -> Self {
        Self {
            incoming: HashMap::new(),
            expected_files: HashMap::new(),
            dest_dir: dest_dir.into(),
        }
    }

    /// Register a file the introduction declared. Only ids registered here will
    /// ever be written to disk.
    pub fn expect_file(&mut self, payload_id: i64, name: &str) {
        self.expected_files.insert(payload_id, sanitize_file_name(name));
    }

    pub fn is_expected_file(&self, payload_id: i64) -> bool {
        self.expected_files.contains_key(&payload_id)
    }

    /// Feed one chunk.
    ///
    /// `is_file` distinguishes a declared FILE payload from the BYTES payloads
    /// that carry control frames; a FILE payload whose id was never introduced
    /// is refused rather than written somewhere hopeful.
    pub fn on_chunk(
        &mut self,
        id: i64,
        is_file: bool,
        total_size: i64,
        offset: i64,
        body: &[u8],
        last: bool,
    ) -> Result<Option<Completed>, PayloadError> {
        if total_size < 0 || total_size > MAX_PAYLOAD_BYTES {
            return Err(PayloadError::TooLarge(id));
        }

        if !self.incoming.contains_key(&id) {
            let sink = if is_file {
                let name = self
                    .expected_files
                    .get(&id)
                    .cloned()
                    .ok_or(PayloadError::Unexpected(id))?;
                let path = unique_path(&self.dest_dir, &name);
                if let Some(parent) = path.parent() {
                    std::fs::create_dir_all(parent)
                        .map_err(|e| PayloadError::Io(e.to_string()))?;
                }
                let handle = File::create(&path).map_err(|e| PayloadError::Io(e.to_string()))?;
                Sink::File { handle, path }
            } else {
                if total_size as usize > MAX_BYTES_PAYLOAD {
                    return Err(PayloadError::TooLarge(id));
                }
                Sink::Bytes(Vec::new())
            };
            self.incoming.insert(id, Incoming { sink, received: 0, total: total_size });
        }

        let entry = self.incoming.get_mut(&id).expect("just inserted");

        // Strictly sequential. Nearby sends chunks in order over TCP, and
        // accepting arbitrary offsets would mean honouring a seek to an
        // attacker-chosen position in a file we are writing.
        if offset != entry.received {
            let expected = entry.received;
            self.incoming.remove(&id);
            return Err(PayloadError::OutOfOrder { id, expected, got: offset });
        }
        if entry.received + body.len() as i64 > entry.total {
            self.incoming.remove(&id);
            return Err(PayloadError::TooLarge(id));
        }

        match &mut entry.sink {
            Sink::Bytes(buf) => buf.extend_from_slice(body),
            Sink::File { handle, .. } => {
                handle.write_all(body).map_err(|e| PayloadError::Io(e.to_string()))?;
            }
        }
        entry.received += body.len() as i64;

        if !last {
            return Ok(Some(Completed::Progress {
                id,
                received: entry.received,
                total: entry.total,
            }));
        }

        let done = self.incoming.remove(&id).expect("present");
        Ok(Some(match done.sink {
            Sink::Bytes(data) => Completed::Bytes { id, data },
            Sink::File { mut handle, path } => {
                handle.flush().map_err(|e| PayloadError::Io(e.to_string()))?;
                let _ = handle.seek(SeekFrom::Start(0));
                Completed::File { id, path }
            }
        }))
    }

    /// Abandon everything in flight, deleting partial files. Called when the
    /// connection drops mid-transfer: a truncated file in the user's Downloads
    /// that looks complete is worse than no file.
    pub fn abort(&mut self) {
        for (_, inc) in self.incoming.drain() {
            if let Sink::File { path, .. } = inc.sink {
                let _ = std::fs::remove_file(path);
            }
        }
    }
}

/// Reduce a peer-supplied name to something safe to create inside a directory.
///
/// The name arrives from another device over the network. Path separators,
/// `..`, absolute paths, NUL and control characters are all removed rather than
/// escaped — there is no legitimate transfer whose file name needs them, and
/// the cost of being wrong is a write outside the destination directory.
pub fn sanitize_file_name(name: &str) -> String {
    // Take the last path-ish component under either separator, so both
    // "../../x" and "..\\..\\x" reduce to "x".
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .filter(|c| !c.is_control() && *c != '\0')
        .collect();
    let trimmed = cleaned.trim().trim_start_matches('.').trim();
    if trimmed.is_empty() {
        return "received-file".to_string();
    }
    // Leave room for the " (2)" a collision adds, and for the filesystem's own
    // 255-byte component limit.
    let mut out: String = trimmed.chars().take(200).collect();
    if out.trim().is_empty() {
        out = "received-file".to_string();
    }
    out
}

/// A path in `dir` that does not exist yet, appending " (2)", " (3)"… before the
/// extension the way the rest of the app does.
fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let path = Path::new(name);
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or(name);
    let ext = path.extension().and_then(|s| s.to_str());
    for n in 2..10_000 {
        let attempt = match ext {
            Some(e) => dir.join(format!("{stem} ({n}).{e}")),
            None => dir.join(format!("{stem} ({n})")),
        };
        if !attempt.exists() {
            return attempt;
        }
    }
    dir.join(format!("{stem}-{}", std::process::id()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("qs-test-{}-{:?}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn sanitize_defeats_path_traversal() {
        assert_eq!(sanitize_file_name("../../../etc/passwd"), "passwd");
        assert_eq!(sanitize_file_name("..\\..\\windows\\system32"), "system32");
        assert_eq!(sanitize_file_name("/absolute/path/x.png"), "x.png");
        assert_eq!(sanitize_file_name("..").len() > 0, true);
        assert!(!sanitize_file_name("..").contains(".."));
        // Nothing usable left must still yield a creatable name.
        assert_eq!(sanitize_file_name(""), "received-file");
        assert_eq!(sanitize_file_name("   "), "received-file");
        assert_eq!(sanitize_file_name("///"), "received-file");
    }

    #[test]
    fn sanitize_strips_control_characters() {
        // A newline or NUL in a name is either an attempt at log injection or a
        // name the filesystem will reject outright.
        assert_eq!(sanitize_file_name("evil\u{0}.png"), "evil.png");
        assert_eq!(sanitize_file_name("two\nlines.txt"), "twolines.txt");
    }

    #[test]
    fn sanitize_keeps_ordinary_names_intact() {
        assert_eq!(sanitize_file_name("Photo 2026-09-05.jpg"), "Photo 2026-09-05.jpg");
        assert_eq!(sanitize_file_name("résumé.pdf"), "résumé.pdf");
    }

    #[test]
    fn bytes_payload_assembles_in_order() {
        let mut r = Reassembler::new(tmp());
        assert!(matches!(
            r.on_chunk(1, false, 6, 0, b"abc", false).unwrap(),
            Some(Completed::Progress { received: 3, .. })
        ));
        match r.on_chunk(1, false, 6, 3, b"def", true).unwrap() {
            Some(Completed::Bytes { data, .. }) => assert_eq!(data, b"abcdef"),
            other => panic!("expected Bytes, got {other:?}"),
        }
    }

    #[test]
    fn file_payload_must_be_introduced_first() {
        let mut r = Reassembler::new(tmp());
        // Not introduced: refused, and nothing is created.
        assert_eq!(
            r.on_chunk(9, true, 4, 0, b"data", true).unwrap_err(),
            PayloadError::Unexpected(9)
        );
    }

    #[test]
    fn file_payload_writes_the_introduced_name_not_the_header_name() {
        let dir = tmp();
        let mut r = Reassembler::new(&dir);
        // The user accepted "safe.txt"; a later header claiming otherwise is
        // irrelevant because the name is never taken from the chunk.
        r.expect_file(5, "../../safe.txt");
        match r.on_chunk(5, true, 5, 0, b"hello", true).unwrap() {
            Some(Completed::File { path, .. }) => {
                assert_eq!(path.file_name().unwrap(), "safe.txt");
                assert_eq!(path.parent().unwrap(), dir.as_path());
                assert_eq!(std::fs::read(&path).unwrap(), b"hello");
            }
            other => panic!("expected File, got {other:?}"),
        }
    }

    #[test]
    fn out_of_order_chunk_is_refused_and_drops_the_transfer() {
        let mut r = Reassembler::new(tmp());
        r.on_chunk(1, false, 10, 0, b"ab", false).unwrap();
        assert_eq!(
            r.on_chunk(1, false, 10, 7, b"zz", false).unwrap_err(),
            PayloadError::OutOfOrder { id: 1, expected: 2, got: 7 }
        );
    }

    #[test]
    fn a_chunk_cannot_exceed_the_declared_size() {
        let mut r = Reassembler::new(tmp());
        assert_eq!(
            r.on_chunk(1, false, 4, 0, b"more than four", true).unwrap_err(),
            PayloadError::TooLarge(1)
        );
    }

    #[test]
    fn absurd_declared_sizes_are_refused() {
        let mut r = Reassembler::new(tmp());
        assert_eq!(r.on_chunk(1, false, i64::MAX, 0, b"x", false).unwrap_err(), PayloadError::TooLarge(1));
        assert_eq!(r.on_chunk(2, false, -1, 0, b"x", false).unwrap_err(), PayloadError::TooLarge(2));
        // A BYTES payload is a control message; megabytes of it is an attack.
        assert_eq!(
            r.on_chunk(3, false, (MAX_BYTES_PAYLOAD + 1) as i64, 0, b"x", false).unwrap_err(),
            PayloadError::TooLarge(3)
        );
    }

    #[test]
    fn colliding_names_do_not_overwrite() {
        let dir = tmp();
        let mut r = Reassembler::new(&dir);
        r.expect_file(1, "dup.txt");
        r.on_chunk(1, true, 3, 0, b"aaa", true).unwrap();
        r.expect_file(2, "dup.txt");
        match r.on_chunk(2, true, 3, 0, b"bbb", true).unwrap() {
            Some(Completed::File { path, .. }) => {
                assert_eq!(path.file_name().unwrap(), "dup (2).txt");
                assert_eq!(std::fs::read(dir.join("dup.txt")).unwrap(), b"aaa");
            }
            other => panic!("expected File, got {other:?}"),
        }
    }

    #[test]
    fn abort_removes_partial_files() {
        let dir = tmp();
        let mut r = Reassembler::new(&dir);
        r.expect_file(1, "partial.bin");
        r.on_chunk(1, true, 100, 0, b"half", false).unwrap();
        let path = dir.join("partial.bin");
        assert!(path.exists());
        r.abort();
        assert!(!path.exists(), "a truncated file must not be left behind");
    }

    #[test]
    fn parallel_payloads_stay_separate() {
        let mut r = Reassembler::new(tmp());
        r.on_chunk(1, false, 4, 0, b"aa", false).unwrap();
        r.on_chunk(2, false, 4, 0, b"bb", false).unwrap();
        r.on_chunk(2, false, 4, 2, b"BB", true).unwrap();
        match r.on_chunk(1, false, 4, 2, b"AA", true).unwrap() {
            Some(Completed::Bytes { data, .. }) => assert_eq!(data, b"aaAA"),
            other => panic!("{other:?}"),
        }
    }
}
