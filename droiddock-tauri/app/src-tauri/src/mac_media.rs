//! What's playing on the Mac, pushed to the phone (Phase 3).
//!
//! ```json
//! {"type":"mac-media","playing":true,"title":"…","artist":"…","app":"Google Chrome"}
//! ```
//!
//! # Why this doesn't use the API that would actually work
//!
//! The complete answer is the private `MediaRemote` framework —
//! `MRMediaRemoteGetNowPlayingInfo` returns title, artist and artwork for
//! *whatever* owns the system now-playing session, browsers included. macOS
//! 15.4 put it behind an entitlement Apple does not issue to third parties, and
//! it broke every app that relied on it. So this reads the same information the
//! long way, from two sources that are public.
//!
//! # The two sources, and why neither is enough alone
//!
//! **AppleScript** names the thing: Music, Spotify, TV and VLC report a track
//! and a real player state, and browsers report each window's tab title and URL.
//! What it cannot do is tell you whether a *browser tab* is playing — there is
//! no such property — which is why pausing a YouTube video on the Mac used to
//! leave the phone's button stuck on the pause glyph forever. It also costs a
//! process launch every time it is asked.
//!
//! **CoreAudio** ([`crate::mac_audio`]) answers only "is sound coming out of
//! this Mac", for every app at once, from an in-process property read. That is
//! exactly the bit AppleScript is missing, and it is free.
//!
//! So: AppleScript supplies the label and, where it can, the authoritative
//! state; CoreAudio supplies the state for everything else and decides *when*
//! the expensive read is worth doing at all.
//!
//! # Why every app-specific line goes through `run script`
//!
//! An app's terminology — `player state`, `current track`, `active tab` — is
//! resolved when the script is *compiled*, from the app's own dictionary. So a
//! single script naming four players fails to compile in its entirety if any
//! one of them is missing, and `if application "Spotify" is running` does not
//! save it: that check runs long after compilation has already failed. One
//! script covering Music, Spotify, TV and VLC therefore only ever worked on a
//! Mac with all four installed, and on every other Mac reported nothing at all.
//!
//! `run script` moves compilation of the app-specific part to *runtime*, inside
//! the `try` and behind the `is running` check, so an app that isn't installed
//! costs nothing and takes nothing else down with it. The same indirection is
//! what lets one script cover six browsers.
//!
//! # Why this is cheaper than the fixed 3s poll it replaces
//!
//! It used to fork `osascript` — twice, when browser titles were on — every
//! three seconds for as long as a phone was linked, whether or not anything was
//! playing or could have changed. Now the 1s tick is a CoreAudio read, and the
//! fork happens only when that bit *changes* (a play or pause, reflected within
//! about a second) or when a heartbeat comes due: [`PROBE_PLAYING`] while sound
//! is coming out, [`PROBE_IDLE`] while it isn't. A linked Mac with nothing
//! playing went from ~40 process launches a minute to four.
//!
//! # Control is universal; only metadata is not
//!
//! Worth being precise, because the asymmetry is easy to misread as a bug:
//! play/pause/next/prev already work for *everything*, including YouTube in
//! Chrome, because [`crate::mac_remote`] posts real `NX_KEYTYPE_*` HID keys and
//! macOS routes those to whichever app holds the now-playing session. What this
//! module adds is only the *label* above those buttons. An app we can't read
//! still gets working controls, a correct play/pause glyph (CoreAudio knows it
//! is making noise even when nothing can name it) and the subtitle "Playing on
//! your Mac".
//!
//! # Why browser tabs are matched against a domain allow-list
//!
//! Reading tab titles and shipping them to a phone every few seconds is a
//! browsing-history feed, which is not what anyone asked for. Only tabs whose
//! host is on [`MEDIA_HOSTS`] are ever reported, so a YouTube video is picked up
//! and your bank tab is not — and that also happens to be the more accurate
//! rule, since an arbitrary tab title isn't "now playing" anyway. The script
//! reads more than it reports (every window's active tab, so a video playing
//! behind the window you're typing in is still found), but the filter runs
//! here, before anything leaves the Mac.

use crate::config::Config;
use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};

/// Advertised in `welcome.caps` while the feature is on. A phone that never
/// sees it renders no player card rather than an empty one.
pub const CAP: &str = "macmedia";

/// How often the cheap signal is sampled. This is a CoreAudio property read,
/// not a process launch, so it can be far tighter than the AppleScript cadence
/// and still cost less than the 3s poll it replaces. It is what makes a pause
/// on the Mac show up on the phone in about a second.
const TICK: Duration = Duration::from_secs(1);

/// Metadata re-read interval while sound is coming out. A track change doesn't
/// move the CoreAudio bit, so something has to go and look.
const PROBE_PLAYING: Duration = Duration::from_secs(3);

/// …and while the Mac is silent. Nothing can change from here without the audio
/// bit flipping first — which probes immediately — so this is a backstop for
/// the paused-player case, not a poll.
const PROBE_IDLE: Duration = Duration::from_secs(15);

/// Hosts whose tab titles are treated as now-playing metadata. Anything else in
/// the browser is ignored entirely — see the module note.
const MEDIA_HOSTS: &[&str] = &[
    "youtube.com",
    "youtu.be",
    "music.youtube.com",
    "open.spotify.com",
    "soundcloud.com",
    "music.apple.com",
    "vimeo.com",
    "twitch.tv",
    "netflix.com",
    "primevideo.com",
    "hotstar.com",
    "jiocinema.com",
    "bandcamp.com",
    "deezer.com",
    "tidal.com",
];

pub fn enabled(app: &AppHandle) -> bool {
    let cfg: Config = app.state::<crate::AppState>().config.lock().unwrap().clone();
    cfg.mac_media_sync && !cfg.is_paused()
}

fn browser_titles_enabled(app: &AppHandle) -> bool {
    let cfg: Config = app.state::<crate::AppState>().config.lock().unwrap().clone();
    cfg.mac_media_browser
}

/// The CoreAudio bit, with the one process we know about subtracted.
///
/// While the phone is streaming its own audio to this Mac, the speakers are
/// busy *because of the phone*. Reporting that back as "your Mac is playing
/// something" would be a feedback loop dressed up as a fact, so the signal is
/// dropped to "unknown" and callers fall back to what AppleScript says.
fn audio_active(app: &AppHandle) -> Option<bool> {
    if crate::audio::streaming(app) {
        return None;
    }
    crate::mac_audio::output_active()
}

#[derive(Debug, Default, PartialEq, Clone)]
pub struct NowPlaying {
    pub playing: bool,
    pub title: String,
    pub artist: String,
    /// Which app it came from, for the "from your Mac" subtitle.
    pub app: String,
}

/// The per-player probe, with `@APP@` filled in. Music, Spotify and TV all
/// speak the same iTunes-derived dialect.
///
/// Every property read is wrapped: a player can be running with nothing loaded,
/// and TV in particular has tracks that carry no artist. An error anywhere here
/// would abandon the whole probe and fall through to the next app, so the
/// tolerant version reports a title where the strict one reported nothing.
///
/// `sep` is hoisted out of the `tell` deliberately — see [`BROWSER_BODY`].
const PLAYER_BODY: &str = r#"set sep to tab
tell application "@APP@"
  set ps to ""
  try
    set ps to (player state as text)
  end try
  if ps is not "playing" and ps is not "paused" then return ""
  set nm to ""
  try
    set nm to (name of current track) as text
  end try
  if nm is "" then return ""
  set ar to ""
  try
    set ar to (artist of current track) as text
  end try
  return "@APP@" & sep & nm & sep & ar & sep & ps
end tell"#;

/// VLC answers a boolean `playing` rather than a player state, and has items
/// rather than tracks, so it gets its own body. No artist — a file is a file.
const VLC_BODY: &str = r#"set sep to tab
tell application "VLC"
  set nm to ""
  try
    set nm to (name of current item) as text
  end try
  if nm is "" then return ""
  if playing then
    return "VLC" & sep & nm & sep & "" & sep & "playing"
  end if
  return "VLC" & sep & nm & sep & "" & sep & "paused"
end tell"#;

/// The per-browser probe: one line per window, `app<TAB>url<TAB>title`.
///
/// # Why `sep` is set before the `tell` and not inside it
///
/// Every browser's dictionary defines `tab` as a *class* — a browser tab. Inside
/// `tell application "Google Chrome"`, the identifier `tab` therefore resolves
/// to that class and not to AppleScript's tab character, and concatenating it
/// coerces the class name into the string: the probe returned the literal text
/// `Google Chrometababout:blanktab`, which no amount of splitting on `\t` was
/// ever going to parse. Reading it into `sep` outside the `tell` binds the
/// character while the browser's terminology is not in scope.
const BROWSER_BODY: &str = r#"set sep to tab
set eol to linefeed
tell application "@APP@"
  set r to ""
  repeat with w in windows
    try
      set r to r & "@APP@" & sep & (URL of @TAB@ of w) & sep & (@TITLE@ of @TAB@ of w) & eol
    end try
  end repeat
  return r
end tell"#;

/// Players in priority order: a real player beats that same service's web tab,
/// because it carries an artist and an authoritative play state.
const PLAYERS: &[&str] = &["Music", "Spotify", "TV"];

/// Probed in order; the first with a tab on [`MEDIA_HOSTS`] wins. Safari is last
/// because it is on every Mac and so is the most likely to be open with
/// something unrelated in it.
const BROWSERS: &[&str] = &[
    "Google Chrome",
    "Brave Browser",
    "Microsoft Edge",
    "Arc",
    "Vivaldi",
    "Safari",
];

/// Wrap a script as an AppleScript string literal, ready for `run script`.
/// AppleScript's literals take the same `\"`, `\\`, `\n`, `\r` and `\t` escapes
/// C does, so a multi-line probe collapses to one line safely.
fn as_literal(src: &str) -> String {
    let mut out = String::with_capacity(src.len() + 16);
    out.push('"');
    for ch in src.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `if <app> is running then <assign> (run script <body>)`, guarded so a missing
/// app, a denied Automation prompt or a player with nothing loaded all come back
/// as "no answer" rather than killing the run.
fn guarded(app: &str, body: &str, accumulate: bool) -> String {
    let assign = if accumulate { "out & " } else { "" };
    format!(
        "try\n  if application \"{app}\" is running then set out to {assign}(run script {})\nend try\n",
        as_literal(body)
    )
}

/// One script for every dedicated player, stopping at the first that answers.
fn players_script() -> &'static str {
    static SCRIPT: OnceLock<String> = OnceLock::new();
    SCRIPT.get_or_init(|| {
        let mut bodies: Vec<(&str, String)> = PLAYERS
            .iter()
            .map(|app| (*app, PLAYER_BODY.replace("@APP@", app)))
            .collect();
        bodies.push(("VLC", VLC_BODY.to_string()));

        let mut script = String::from("set out to \"\"\n");
        for (app, body) in &bodies {
            // `if out is ""` is what makes this first-match rather than
            // last-match: an already-answered probe must not be overwritten by
            // a player further down the list.
            script.push_str("if out is \"\" then\n");
            script.push_str(&guarded(app, body, false));
            script.push_str("end if\n");
        }
        script.push_str("return out\n");
        script
    })
}

/// One script for every browser, concatenating every window's active tab.
///
/// Everything is collected and the allow-list filter runs in Rust, because the
/// script has no way to know the list. The earlier version stopped at the first
/// *running* browser's *front* window, which meant a Chrome window on a docs
/// page hid the YouTube tab playing in Safari behind it — and, more often, that
/// a video playing in another window of the same browser was never seen.
fn browsers_script() -> &'static str {
    static SCRIPT: OnceLock<String> = OnceLock::new();
    SCRIPT.get_or_init(|| {
        let mut script = String::from("set out to \"\"\n");
        for app in BROWSERS {
            // Safari names both properties differently from the Chromium family.
            let (tab, title) = if *app == "Safari" {
                ("current tab", "name")
            } else {
                ("active tab", "title")
            };
            let body = BROWSER_BODY
                .replace("@APP@", app)
                .replace("@TAB@", tab)
                .replace("@TITLE@", title);
            script.push_str(&guarded(app, &body, true));
        }
        script.push_str("return out\n");
        script
    })
}

/// Strip the platform suffix a browser tacks onto the tab title, so the phone
/// shows the track rather than the site.
fn clean_title(raw: &str) -> String {
    let mut t = raw.trim();

    // Order matters, and both must apply: a real YouTube tab with unread
    // activity is "(3) Song - YouTube", so stripping only whichever matched
    // first would leave the other half on screen.
    //
    // Prefix first — a notification count Chrome prepends on some sites. Only
    // digits count, so a title that genuinely opens with a parenthetical
    // ("(Reprise) Song") is left alone.
    if let Some(rest) = t.strip_prefix('(') {
        if let Some((count, tail)) = rest.split_once(") ") {
            if !count.is_empty() && count.chars().all(|c| c.is_ascii_digit()) {
                t = tail;
            }
        }
    }

    for suffix in [
        " - YouTube Music",
        " - YouTube",
        " | Spotify",
        " - SoundCloud",
        " on Vimeo",
        " - Twitch",
        " - Netflix",
        " | Prime Video",
    ] {
        if let Some(stripped) = t.strip_suffix(suffix) {
            t = stripped;
            break;
        }
    }
    t.trim().to_string()
}

/// True when `url`'s host — or any parent of it — is on the allow-list.
fn is_media_url(url: &str) -> bool {
    let host = url
        .split_once("://")
        .map(|(_, rest)| rest)
        .unwrap_or(url)
        .split('/')
        .next()
        .unwrap_or("")
        .split('@')
        .next_back()
        .unwrap_or("")
        .split(':')
        .next()
        .unwrap_or("")
        .trim_start_matches("www.")
        .to_ascii_lowercase();
    MEDIA_HOSTS
        .iter()
        .any(|allowed| host == *allowed || host.ends_with(&format!(".{allowed}")))
}

/// Parse the tab-separated player result. Split into its own function so the
/// cases that matter are testable without an AppleScript runtime.
pub fn parse_player(out: &str) -> Option<NowPlaying> {
    let mut parts = out.trim().splitn(4, '\t');
    let app = parts.next()?.trim();
    if app.is_empty() {
        return None;
    }
    let title = parts.next().unwrap_or("").trim();
    let artist = parts.next().unwrap_or("").trim();
    // Absent on a Mac still running the pre-state script — treat that as
    // playing, which is what it used to mean by only reporting at all.
    let state = parts.next().unwrap_or("playing").trim();
    if title.is_empty() {
        return None;
    }
    Some(NowPlaying {
        playing: !state.eq_ignore_ascii_case("paused"),
        title: title.to_string(),
        artist: artist.to_string(),
        app: app.to_string(),
    })
}

/// Pick the first tab on the allow-list out of the script's one-line-per-window
/// output. Everything else is dropped here, before it can reach the phone.
///
/// `playing` is left at `true` — a tab has no state to read. The caller
/// overwrites it with the CoreAudio answer, which does.
pub fn parse_browser(out: &str) -> Option<NowPlaying> {
    out.lines().find_map(parse_browser_line)
}

fn parse_browser_line(line: &str) -> Option<NowPlaying> {
    let mut parts = line.trim().splitn(3, '\t');
    let app = parts.next()?.trim();
    let url = parts.next()?.trim();
    let title = parts.next()?.trim();
    if app.is_empty() || title.is_empty() || !is_media_url(url) {
        return None;
    }
    Some(NowPlaying {
        playing: true,
        title: clean_title(title),
        artist: String::new(),
        app: app.to_string(),
    })
}

#[cfg(target_os = "macos")]
async fn osascript(script: &str) -> String {
    let out = tokio::process::Command::new("/usr/bin/osascript")
        .args(["-e", script])
        .output()
        .await;
    out.map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default()
}

#[cfg(not(target_os = "macos"))]
async fn osascript(_script: &str) -> String {
    String::new()
}

/// Fold the two sources into one answer.
///
/// `audio` is [`audio_active`]'s reading: `Some(true)`/`Some(false)` when
/// CoreAudio could be asked, `None` when it couldn't or when the phone's own
/// stream is what's making the noise.
///
/// Dedicated players first — they carry a real artist and a real state, which a
/// browser tab never does, so a Spotify session should win over a Spotify *web*
/// tab.
pub async fn read_now_playing(app: &AppHandle, audio: Option<bool>) -> NowPlaying {
    if let Some(np) = parse_player(&osascript(players_script()).await) {
        return np;
    }
    if browser_titles_enabled(app) {
        if let Some(np) = parse_browser(&osascript(browsers_script()).await) {
            return with_audio_state(np, audio);
        }
    }
    // Nothing that can be named, but the speakers disagree — a video in a tab
    // we don't report, a game, IINA, QuickTime. Say so rather than "Nothing
    // Playing": the transport buttons work for all of it, and a play glyph that
    // matches reality is the whole point of the card.
    if audio == Some(true) {
        return NowPlaying {
            playing: true,
            ..NowPlaying::default()
        };
    }
    NowPlaying::default()
}

/// Overlay CoreAudio's answer onto a source that has no state of its own.
/// Unknown leaves the guess alone, which is the old behaviour — better to say
/// "playing" for a tab we found than to invent a pause.
fn with_audio_state(mut np: NowPlaying, audio: Option<bool>) -> NowPlaying {
    if let Some(playing) = audio {
        np.playing = playing;
    }
    np
}

fn to_json(np: &NowPlaying) -> Value {
    json!({
        "type": "mac-media",
        "playing": np.playing,
        "title": np.title,
        "artist": np.artist,
        "app": np.app,
    })
}

pub async fn snapshot(app: &AppHandle) -> Value {
    to_json(&read_now_playing(app, audio_active(app)).await)
}

pub async fn push_now(app: &AppHandle, state: &SharedState) {
    if !enabled(app) {
        return;
    }
    if !ws_server::phone_has_cap(state, CAP).await {
        return;
    }
    let _ = ws_server::push(state, snapshot(app).await).await;
}

/// One ticker for the app, alongside the `mac_info` one.
///
/// Only does anything while a phone is actually listening, and even then the
/// tick itself is a CoreAudio read — the AppleScript fork is rationed by
/// [`PROBE_PLAYING`]/[`PROBE_IDLE`] and by whether that bit moved. An unpaired
/// Mac sitting on a desk does no polling at all.
pub async fn run(app: AppHandle, state: SharedState) {
    let mut last_sent: Option<Value> = None;
    let mut cached = NowPlaying::default();
    let mut last_audio: Option<bool> = None;
    let mut last_probe: Option<Instant> = None;

    loop {
        tokio::time::sleep(TICK).await;
        if !enabled(&app) || !ws_server::phone_has_cap(&state, CAP).await {
            // Drop the dedup memory with the session. A phone that links later
            // must get a full report, not silence because the last thing we
            // sent to a *different* phone happened to match.
            last_sent = None;
            last_probe = None;
            // With no phone at all there is nothing to wake up for — park on
            // the link instead of sampling CoreAudio once a second into a
            // `continue`. (A phone that *is* linked but hasn't got the cap, or
            // a toggle switched off, still falls through to the 1s tick: that
            // one has to keep re-checking a condition nothing signals.)
            if !ws_server::is_connected(&state).await {
                ws_server::await_connected(&state).await;
            }
            continue;
        }

        let audio = audio_active(&app);
        // A flip is a play or a pause and is worth a fork immediately; the
        // heartbeat catches what the bit can't see, like a track change. When
        // CoreAudio can't be read at all we keep the old 3s cadence rather than
        // silently slowing down to the idle one.
        let heartbeat = if audio == Some(false) {
            PROBE_IDLE
        } else {
            PROBE_PLAYING
        };
        let due = last_probe.is_none_or(|at| at.elapsed() >= heartbeat);
        if due || audio != last_audio {
            last_audio = audio;
            last_probe = Some(Instant::now());
            cached = read_now_playing(&app, audio).await;
        }

        // Only speak when something changed. At this tick rate an unchanged
        // track would otherwise be sixty identical messages a minute for the
        // phone to wake up and re-render.
        let snap = to_json(&cached);
        if last_sent.as_ref() != Some(&snap) {
            let _ = ws_server::push(&state, snap.clone()).await;
            last_sent = Some(snap);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_script_body_survives_becoming_a_string_literal() {
        assert_eq!(as_literal("plain"), "\"plain\"");
        // The three that actually occur in these bodies.
        assert_eq!(as_literal("say \"hi\""), "\"say \\\"hi\\\"\"");
        assert_eq!(as_literal("one\ntwo"), "\"one\\ntwo\"");
        assert_eq!(as_literal("a\tb"), "\"a\\tb\"");
        // A literal must never contain a raw newline — `run script` takes one
        // expression, and a broken literal is a script that silently never runs.
        assert!(!as_literal(PLAYER_BODY).contains('\n'));
        assert!(!as_literal(BROWSER_BODY).contains('\n'));
        assert!(!as_literal(VLC_BODY).contains('\n'));
    }

    #[test]
    fn every_app_is_probed_and_guarded() {
        let players = players_script();
        for app in PLAYERS.iter().chain(["VLC"].iter()) {
            assert!(
                players.contains(&format!("if application \"{app}\" is running")),
                "{app} is not guarded by a running check"
            );
        }
        // First match wins: a player further down the list must not clobber an
        // answer already in hand.
        assert_eq!(players.matches("if out is \"\"").count(), PLAYERS.len() + 1);

        let browsers = browsers_script();
        for app in BROWSERS {
            assert!(browsers.contains(&format!("if application \"{app}\" is running")));
        }
        // Browsers accumulate instead — every window of every browser is a
        // candidate, and Rust picks the one on the allow-list.
        assert_eq!(browsers.matches("set out to out &").count(), BROWSERS.len());
    }

    #[test]
    fn the_tab_character_is_bound_outside_every_tell() {
        // A browser's dictionary defines `tab` as a class, so inside its `tell`
        // the identifier stops meaning the tab *character* and the probe emits
        // the literal text "tab" between fields. Binding it first is the fix,
        // and this is the property that keeps it fixed.
        for body in [PLAYER_BODY, VLC_BODY, BROWSER_BODY] {
            let (preamble, tell) = body.split_once("tell application").unwrap();
            assert!(preamble.contains("set sep to tab"));
            assert!(!tell.contains(" tab "), "a bare `tab` survives inside the tell");
        }
    }

    /// The regression that motivated all of the above: the shipped script named
    /// four players and six browsers directly, so it only compiled on a Mac
    /// with every one of them installed — and reported nothing at all on any
    /// other. Compiling is the whole assertion; a failure here is a feature
    /// that is silently dead on most machines.
    #[test]
    #[cfg(target_os = "macos")]
    fn the_generated_scripts_compile_on_this_mac() {
        for (name, script) in [
            ("players", players_script()),
            ("browsers", browsers_script()),
        ] {
            let src = std::env::temp_dir().join(format!("droiddock-{name}-probe.applescript"));
            std::fs::write(&src, script).unwrap();
            let out = std::process::Command::new("/usr/bin/osacompile")
                .arg("-o")
                .arg("/dev/null")
                .arg(&src)
                .output()
                .expect("osacompile is part of the base macOS install");
            let _ = std::fs::remove_file(&src);
            assert!(
                out.status.success(),
                "the {name} probe does not compile:\n{}",
                String::from_utf8_lossy(&out.stderr)
            );
        }
    }

    #[test]
    fn player_output_parses() {
        let np = parse_player("Spotify\tParanoid Android\tRadiohead\tplaying").unwrap();
        assert_eq!(np.title, "Paranoid Android");
        assert_eq!(np.artist, "Radiohead");
        assert_eq!(np.app, "Spotify");
        assert!(np.playing);
    }

    #[test]
    fn a_paused_track_is_still_reported_as_a_track() {
        // The regression this guards: reporting only while playing meant
        // hitting pause blanked the card to "Nothing Playing" and left the
        // button stuck on the pause glyph.
        let np = parse_player("Music\tKid A\tRadiohead\tpaused").unwrap();
        assert_eq!(np.title, "Kid A");
        assert!(!np.playing);
    }

    #[test]
    fn vlc_reports_a_title_with_no_artist() {
        let np = parse_player("VLC\tsome-movie.mkv\t\tpaused").unwrap();
        assert_eq!(np.app, "VLC");
        assert_eq!(np.title, "some-movie.mkv");
        assert!(np.artist.is_empty());
        assert!(!np.playing);
    }

    #[test]
    fn a_mac_on_the_older_script_still_reads_as_playing() {
        // Three fields, no state — the shape the previous build emitted.
        let np = parse_player("Spotify\tSong\tArtist").unwrap();
        assert!(np.playing);
    }

    #[test]
    fn nothing_playing_is_none_not_a_blank_track() {
        assert!(parse_player("").is_none());
        assert!(parse_player("Music\t\t\tplaying").is_none());
    }

    #[test]
    fn youtube_tab_is_reported_without_its_suffix() {
        let np = parse_browser(
            "Google Chrome\thttps://www.youtube.com/watch?v=x\tNever Gonna Give You Up - YouTube",
        )
        .unwrap();
        assert_eq!(np.title, "Never Gonna Give You Up");
        assert_eq!(np.app, "Google Chrome");
    }

    #[test]
    fn a_non_media_browser_no_longer_hides_the_one_playing() {
        // The bug: the script stopped at the first running browser and its
        // front window, so this docs tab in Chrome meant the YouTube tab in
        // Safari was never even looked at — the card said "Nothing Playing"
        // while a video was on screen.
        let out = "Google Chrome\thttps://docs.rs/tokio\ttokio - Rust\n\
                   Google Chrome\thttps://mybank.example.com/\tBalance\n\
                   Safari\thttps://www.youtube.com/watch?v=x\tSome Song - YouTube\n";
        let np = parse_browser(out).unwrap();
        assert_eq!(np.app, "Safari");
        assert_eq!(np.title, "Some Song");
    }

    #[test]
    fn non_media_tabs_are_never_reported() {
        // The whole privacy argument for this module rests on this test.
        assert!(parse_browser("Safari\thttps://mybank.example.com/accounts\tBalance").is_none());
        assert!(parse_browser("Arc\thttps://mail.google.com/\tInbox (42)").is_none());
        // …including when they're the only thing open across every window.
        assert!(parse_browser(
            "Google Chrome\thttps://mybank.example.com/\tBalance\n\
             Safari\thttps://mail.google.com/\tInbox\n"
        )
        .is_none());
    }

    #[test]
    fn lookalike_hosts_do_not_match() {
        assert!(!is_media_url("https://youtube.com.evil.test/watch"));
        assert!(!is_media_url("https://notyoutube.com/watch"));
        assert!(is_media_url("https://www.youtube.com/watch?v=x"));
        assert!(is_media_url("https://music.youtube.com/"));
    }

    #[test]
    fn subdomains_of_allowed_hosts_match() {
        assert!(is_media_url("https://open.spotify.com/track/1"));
        assert!(is_media_url("https://m.twitch.tv/x"));
    }

    #[test]
    fn a_browser_tab_takes_its_play_state_from_the_speakers() {
        // The reported bug: a tab exposes no play state, so pausing on the Mac
        // left the phone showing "playing" for as long as the tab stayed open.
        let tab = parse_browser("Safari\thttps://www.youtube.com/watch?v=x\tSong - YouTube")
            .unwrap();
        assert!(!with_audio_state(tab.clone(), Some(false)).playing);
        assert!(with_audio_state(tab.clone(), Some(true)).playing);
        // Unknown must not invent a pause — that would be worse than the guess.
        assert!(with_audio_state(tab, None).playing);
    }
}

