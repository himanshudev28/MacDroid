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
//! long way, from the apps that publish it over AppleScript.
//!
//! # Control is universal; only metadata is not
//!
//! Worth being precise, because the asymmetry is easy to misread as a bug:
//! play/pause/next/prev already work for *everything*, including YouTube in
//! Chrome, because [`crate::mac_remote`] posts real `NX_KEYTYPE_*` HID keys and
//! macOS routes those to whichever app holds the now-playing session. What this
//! module adds is only the *label* above those buttons. An app we can't read
//! still gets working controls — it just says "Playing on your Mac".
//!
//! # Why browser tabs are matched against a domain allow-list
//!
//! Reading "the title of the frontmost tab" and shipping it to a phone every
//! few seconds is a browsing-history feed, which is not what anyone asked for.
//! Only tabs whose host is on [`MEDIA_HOSTS`] are ever reported, so a YouTube
//! video is picked up and your bank tab is not — and that also happens to be
//! the more accurate rule, since an arbitrary tab title isn't "now playing"
//! anyway.

use crate::config::Config;
use crate::ws_server::{self, SharedState};
use serde_json::{json, Value};
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// Advertised in `welcome.caps` while the feature is on. A phone that never
/// sees it renders no player card rather than an empty one.
pub const CAP: &str = "macmedia";

/// Fast enough that pressing play on the Mac lights up the phone before you
/// look at it; slow enough that the osascript fork is noise. Only ticks while a
/// phone is actually linked and listening.
const TICK: Duration = Duration::from_secs(3);

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

#[derive(Debug, Default, PartialEq)]
pub struct NowPlaying {
    pub playing: bool,
    pub title: String,
    pub artist: String,
    /// Which app it came from, for the "from your Mac" subtitle.
    pub app: String,
}

/// One AppleScript for the dedicated players, so a normal music session costs a
/// single fork rather than one per app.
///
/// `application "X" is running` is checked first throughout: a bare
/// `tell application "Music"` *launches* Music, which would mean opening the
/// phone app silently starting iTunes.
/// Reports a **paused** track as well as a playing one, with the state as a
/// fourth field.
///
/// It used to fire only `if player state is playing`, which had two
/// consequences: pausing made the phone say "Nothing Playing" and forget the
/// track outright, and the play/pause button could never show a paused state
/// because nothing ever reported one.
const PLAYERS_SCRIPT: &str = r#"
set out to ""
try
  if application "Music" is running then
    tell application "Music"
      set st to (player state as text)
      if st is "playing" or st is "paused" then
        set out to "Music" & tab & (name of current track) & tab & (artist of current track) & tab & st
      end if
    end tell
  end if
end try
if out is "" then
  try
    if application "Spotify" is running then
      tell application "Spotify"
        set st to (player state as text)
        if st is "playing" or st is "paused" then
          set out to "Spotify" & tab & (name of current track) & tab & (artist of current track) & tab & st
        end if
      end tell
    end if
  end try
end if
return out
"#;

/// Chromium-family and Safari expose the active tab's URL and title. The URL is
/// what gates reporting; the title is what gets reported.
const BROWSER_SCRIPT: &str = r#"
on probe(appName)
  set res to ""
  try
    if application appName is running then
      tell application appName
        if (count of windows) > 0 then
          if appName is "Safari" then
            set u to URL of current tab of front window
            set t to name of current tab of front window
          else
            set u to URL of active tab of front window
            set t to title of active tab of front window
          end if
          set res to appName & tab & u & tab & t
        end if
      end tell
    end if
  end try
  return res
end probe

set out to ""
repeat with a in {"Google Chrome", "Brave Browser", "Microsoft Edge", "Arc", "Safari"}
  if out is "" then set out to probe(a as string)
end repeat
return out
"#;

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

/// Parse the browser result, dropping anything not on the allow-list.
pub fn parse_browser(out: &str) -> Option<NowPlaying> {
    let mut parts = out.trim().splitn(3, '\t');
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

/// Dedicated players first — they carry a real artist, a browser tab never
/// does, so a Spotify session should win over a Spotify *web* tab.
pub async fn read_now_playing(app: &AppHandle) -> NowPlaying {
    if let Some(np) = parse_player(&osascript(PLAYERS_SCRIPT).await) {
        return np;
    }
    if browser_titles_enabled(app) {
        if let Some(np) = parse_browser(&osascript(BROWSER_SCRIPT).await) {
            return np;
        }
    }
    NowPlaying::default()
}

pub async fn snapshot(app: &AppHandle) -> Value {
    let np = read_now_playing(app).await;
    json!({
        "type": "mac-media",
        "playing": np.playing,
        "title": np.title,
        "artist": np.artist,
        "app": np.app,
    })
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
/// Only forks osascript when a phone is actually listening — `push_now` bails
/// before the read if the cap isn't advertised, so an unpaired Mac sitting on a
/// desk does no polling at all.
pub async fn run(app: AppHandle, state: SharedState) {
    let mut last: Option<Value> = None;
    loop {
        tokio::time::sleep(TICK).await;
        if !enabled(&app) || !ws_server::phone_has_cap(&state, CAP).await {
            continue;
        }
        let snap = snapshot(&app).await;
        // Only speak when something changed. At a 3s tick an unchanged track
        // would otherwise be 20 identical messages a minute for the phone to
        // wake up and re-render.
        if last.as_ref() != Some(&snap) {
            let _ = ws_server::push(&state, snap.clone()).await;
            last = Some(snap);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn unread_count_prefix_is_stripped() {
        assert_eq!(clean_title("(3) Some Song - YouTube"), "Some Song");
        // Not a count — must survive untouched.
        assert_eq!(clean_title("(Reprise) Song - YouTube"), "(Reprise) Song");
    }

    #[test]
    fn non_media_tabs_are_never_reported() {
        // The whole privacy argument for this module rests on this test.
        assert!(parse_browser("Safari\thttps://mybank.example.com/accounts\tBalance").is_none());
        assert!(parse_browser("Arc\thttps://mail.google.com/\tInbox (42)").is_none());
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
}
