//! Tray menu completeness + Mac-initiated pause (Phase 14).
//!
//! The Electron reference has NO tray icon and NO launch-at-login at all
//! (confirmed: no `Tray`/`Menu`/`app.setLoginItemSettings` anywhere in
//! `index.js`) — this is net-new Mac polish, not a port. Phase 1 shipped a
//! Quit-only tray as a placeholder; this phase fills it out per the PRD:
//! status + Pause (1h/8h/indefinite) + Resume + Quit.
//!
//! Pause here is a Mac-local "quiet hours" concept: the Android app has no
//! receive-side handling for an inbound "pause"/"resume" message at all
//! (`ConnectionManager.kt` only ever SENDS `pause`, never handles receiving
//! one), so a Mac→phone pause command would just be silently ignored by the
//! frozen Android app. Pausing here instead mutes local reactions —
//! notification banners, clipboard sync, and the ADB reconnect scanner (see
//! `notifications.rs`/`clipboard.rs`/`adb.rs`'s `is_paused()` gates) — using
//! the same duration-based `pausedUntil` shape the Android app already uses
//! for its own (phone-initiated) pause, just applied Mac-side.

use crate::config;
use tauri::menu::{MenuBuilder, MenuItemBuilder, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::ManagerExt;

const TRAY_ID: &str = "main-tray";

/// The menu-bar panel — a small always-on-top window hung under the tray icon,
/// loading this same bundle at `#menubar` (exactly the routing trick the mirror
/// pop-out already uses). Left-click the tray icon opens it; **right-click
/// still opens the Pause/Resume/Quit menu, unchanged** — the menu didn't move,
/// only the button that reaches it.
const PANEL_LABEL: &str = "menubar";
const PANEL_W: f64 = 340.0;
const PANEL_H: f64 = 500.0;
const HOUR_MS: i64 = 3_600_000;
/// Any `paused_until` further out than this reads as "indefinite" for
/// display. Deliberately NOT an exact-`i64::MAX` sentinel match — a
/// JS-originated indefinite pause (Settings' "∞" button) round-trips through
/// an f64-safe `Number.MAX_SAFE_INTEGER`, not `i64::MAX` itself, so both need
/// to land in the same "basically forever" bucket.
const TEN_YEARS_MS: i64 = 10 * 365 * 24 * 3600 * 1000;

pub fn build(app: &AppHandle) -> tauri::Result<()> {
    let menu = build_menu(app)?;
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(app.default_window_icon().unwrap().clone())
        .tooltip("DroidDock")
        .menu(&menu)
        // The menu moves to right-click so left-click can open the panel —
        // the standard macOS menu-bar-app split. Every menu item is still
        // exactly where it was, reached with the other button.
        .show_menu_on_left_click(false)
        .on_menu_event(on_menu_event)
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                rect,
                ..
            } = event
            {
                toggle_panel(tray.app_handle(), Some(rect));
            }
        })
        .build(app)?;
    Ok(())
}

/// Set (or clear) the text shown beside the tray icon.
///
/// `"device"` is resolved here rather than in `statusbar::render_title` because
/// the Mac's display name lives in config, not in the phone snapshot.
pub fn set_title(app: &AppHandle, title: Option<&str>) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else { return };
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let resolved = match (cfg.menubar_text.as_str(), title) {
        ("device", _) => crate::ws_server::last_phone_name(app),
        (_, t) => t.map(str::to_string),
    };
    let _ = tray.set_title(resolved.as_deref());
}

// ── Floating status widget ───────────────────────────────────────────────

/// The widget window.
///
/// Real macOS Widgets (Notification Centre / desktop) are WidgetKit, which
/// means a Swift app extension embedded in the bundle — a Tauri app cannot
/// produce one, so this is deliberately *not* claiming to be that. It's the
/// thing the widget was for: a small always-on-top, borderless panel you can
/// park anywhere and glance at.
const WIDGET_LABEL: &str = "widget";
const WIDGET_W: f64 = 240.0;
const WIDGET_H: f64 = 132.0;

#[tauri::command]
pub fn widget_set(app: AppHandle, show: bool) -> Result<(), String> {
    {
        let state = app.state::<crate::AppState>();
        let mut cfg = state.config.lock().unwrap();
        cfg.widget_enabled = show;
        config::save(&app, &cfg);
    }
    apply_widget(&app);
    Ok(())
}

/// Open or close the widget to match the persisted setting. Called on startup
/// too, so it reappears where it was after a restart.
pub fn apply_widget(app: &AppHandle) {
    let show = app.state::<crate::AppState>().config.lock().unwrap().widget_enabled;

    if let Some(win) = app.get_webview_window(WIDGET_LABEL) {
        let _ = if show { win.show() } else { win.hide() };
        return;
    }
    if !show {
        return;
    }

    let built = WebviewWindowBuilder::new(
        app,
        WIDGET_LABEL,
        WebviewUrl::App("index.html#widget".into()),
    )
    .title("DroidDock")
    .inner_size(WIDGET_W, WIDGET_H)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .always_on_top(true)
    // Stays put when you switch Spaces — a status readout you have to go
    // looking for defeats the point.
    .visible_on_all_workspaces(true)
    .skip_taskbar(true)
    // A glanceable readout must never take key focus. It sits on every Space,
    // so letting it become the focused window is how a Space switch turns into
    // an app activation — and an app activation is what drags the main window
    // across desktops.
    .focused(false)
    .build();

    match built {
        Ok(win) => {
            let _ = win.show();
        }
        Err(e) => eprintln!("[widget] failed to open: {e}"),
    }
}

// ── Menu-bar panel ───────────────────────────────────────────────────────

/// Show the panel under the tray icon, or hide it if it's already up.
/// Created lazily on first use and reused thereafter — same lifecycle as the
/// mirror pop-out.
fn toggle_panel(app: &AppHandle, rect: Option<tauri::Rect>) {
    if let Some(win) = app.get_webview_window(PANEL_LABEL) {
        if win.is_visible().unwrap_or(false) {
            let _ = win.hide();
        } else {
            position_panel(&win, rect);
            let _ = win.show();
            // NOT `set_focus()`: that activates the app with "bring every
            // window forward", which drags the main window off whichever
            // desktop it was left on and onto this one. `show()` already made
            // this panel key; this only makes the app frontmost.
            crate::appearance::activate_without_raising_all();
            // Opening the panel is seeing the notifications.
            crate::statusbar::clear_unread(app);
        }
        return;
    }

    let built = WebviewWindowBuilder::new(
        app,
        PANEL_LABEL,
        WebviewUrl::App("index.html#menubar".into()),
    )
    .title("DroidDock")
    .inner_size(PANEL_W, PANEL_H)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .always_on_top(true)
    // An always-on-top window already floats above every Space. Saying so
    // explicitly is what keeps macOS from yanking it back to the Space it was
    // created on: without it, switching desktops drags the panel along, shows
    // it on the new Space, and then the ensuing focus change hides it — which
    // reads as "the app appeared for a second and vanished".
    .visible_on_all_workspaces(true)
    .visible(false)
    .build();

    match built {
        Ok(win) => {
            // Click-away dismissal, the way every menu-bar panel behaves.
            let handle = win.clone();
            win.on_window_event(move |e| {
                if let WindowEvent::Focused(false) = e {
                    // Not an unconditional hide. A Space switch, a display
                    // reconfiguration, or the system briefly taking focus all
                    // deliver `Focused(false)` without the user having clicked
                    // away, and hiding on those is the flicker described above.
                    // Re-checking a moment later distinguishes the two: a real
                    // click-away leaves the panel unfocused, while an
                    // incidental one has already handed focus back.
                    let h = handle.clone();
                    std::thread::spawn(move || {
                        std::thread::sleep(std::time::Duration::from_millis(180));
                        if !h.is_focused().unwrap_or(false) {
                            let _ = h.hide();
                        }
                    });
                }
            });
            position_panel(&win, rect);
            let _ = win.show();
            // Same reasoning as the reuse path above.
            crate::appearance::activate_without_raising_all();
            crate::statusbar::clear_unread(app);
        }
        Err(e) => eprintln!("[tray] menu-bar panel failed to open: {e}"),
    }
}

/// Hang the panel just below the tray icon, horizontally centred on it, and
/// nudged back on screen if the icon sits near the right edge.
fn position_panel(win: &tauri::WebviewWindow, rect: Option<tauri::Rect>) {
    let Some(rect) = rect else { return };
    let scale = win.scale_factor().unwrap_or(1.0);

    let (icon_x, icon_y) = match rect.position {
        tauri::Position::Physical(p) => (f64::from(p.x) / scale, f64::from(p.y) / scale),
        tauri::Position::Logical(p) => (p.x, p.y),
    };
    let (icon_w, icon_h) = match rect.size {
        tauri::Size::Physical(s) => (f64::from(s.width) / scale, f64::from(s.height) / scale),
        tauri::Size::Logical(s) => (s.width, s.height),
    };

    let mut x = icon_x + icon_w / 2.0 - PANEL_W / 2.0;
    if let Ok(Some(monitor)) = win.current_monitor() {
        let screen_w = f64::from(monitor.size().width) / monitor.scale_factor();
        x = x.min(screen_w - PANEL_W - 8.0);
    }
    x = x.max(8.0);

    let _ = win.set_position(tauri::LogicalPosition::new(x, icon_y + icon_h + 6.0));
}

/// Let the panel dismiss itself (e.g. after "Open DroidDock").
#[tauri::command]
pub fn menubar_hide(app: AppHandle) {
    if let Some(win) = app.get_webview_window(PANEL_LABEL) {
        let _ = win.hide();
    }
}

/// Bring the main window forward, from the Dock icon, the tray menu, or the
/// menu-bar panel's "Open DroidDock".
///
/// # The distinction this function exists to draw
///
/// "Open DroidDock" means two different things depending on where the window
/// already is, and conflating them is what made the window appear to live on
/// every desktop:
///
/// * **Closed, hidden or minimised** — there is no window anywhere, so putting
///   one on the desktop the user is looking at is exactly right.
/// * **Open, on another desktop** — the window already exists somewhere. The
///   Mac-native answer is to take the user *to it*, which is what activating
///   the app does on its own. Calling `show()` here instead would order the
///   window front on the current desktop, and AppKit implements that by
///   dragging it across. Do that a few times from a few desktops and the app
///   has, from the user's point of view, appeared on all of them.
///
/// So: only ever `show()` a window that isn't already up somewhere.
pub fn raise_main(app: &AppHandle) {
    let Some(win) = app.get_webview_window("main") else {
        return;
    };

    let visible = win.is_visible().unwrap_or(false);
    let minimized = win.is_minimized().unwrap_or(false);
    let here = crate::appearance::is_on_active_space(&win);
    let elsewhere = visible && !minimized && !here;
    eprintln!(
        "[window] raise_main: visible={visible} minimized={minimized} \
         on_active_space={here} → {}",
        if elsewhere { "activate only" } else { "show + activate" }
    );

    if !elsewhere {
        let _ = win.unminimize();
        let _ = win.show();
    }
    // Narrow activation in both branches: `ActivateAllWindows` would drag the
    // mirror pop-out along with whatever else is parked elsewhere.
    crate::appearance::activate_without_raising_all();
}

#[tauri::command]
pub fn open_main_window(app: AppHandle) {
    raise_main(&app);
    if let Some(panel) = app.get_webview_window(PANEL_LABEL) {
        let _ = panel.hide();
    }
}

fn build_menu(app: &AppHandle) -> tauri::Result<tauri::menu::Menu<tauri::Wry>> {
    let cfg = app.state::<crate::AppState>().config.lock().unwrap().clone();
    let paused = cfg.is_paused();
    let status_text = match cfg.paused_until {
        Some(u) if paused && u - config::now_ms() > TEN_YEARS_MS => "Paused indefinitely".to_string(),
        Some(u) if paused => format!("Paused until {}", fmt_clock(u)),
        _ => "Active".to_string(),
    };

    let status = MenuItemBuilder::with_id("status", format!("DroidDock — {status_text}"))
        .enabled(false)
        .build(app)?;
    let pause_1h = MenuItemBuilder::with_id("pause_1h", "Pause for 1 hour").enabled(!paused).build(app)?;
    let pause_8h = MenuItemBuilder::with_id("pause_8h", "Pause for 8 hours").enabled(!paused).build(app)?;
    let pause_indef = MenuItemBuilder::with_id("pause_indef", "Pause indefinitely").enabled(!paused).build(app)?;
    let resume = MenuItemBuilder::with_id("resume", "Resume").enabled(paused).build(app)?;
    let widget_on = cfg.widget_enabled;
    let widget = MenuItemBuilder::with_id(
        "widget",
        if widget_on { "Hide status widget" } else { "Show status widget" },
    )
    .build(app)?;
    // Relabels itself once a background check has found something, so the menu
    // bar carries the news even when the main window has never been opened.
    let pending_version = app
        .try_state::<crate::updater::UpdaterState>()
        .and_then(|s| s.pending_version());
    let update = MenuItemBuilder::with_id(
        "update",
        match &pending_version {
            Some(v) => format!("Update to {v}…"),
            None => "Check for Updates…".to_string(),
        },
    )
    .build(app)?;
    let quit = PredefinedMenuItem::quit(app, Some("Quit DroidDock"))?;

    MenuBuilder::new(app)
        .item(&status)
        .separator()
        .item(&pause_1h)
        .item(&pause_8h)
        .item(&pause_indef)
        .item(&resume)
        .separator()
        .item(&widget)
        .separator()
        .item(&update)
        .item(&quit)
        .build()
}

/// `HH:MM` in **local** time, no `chrono` dependency — just enough for a tray
/// label, not a general-purpose formatter.
///
/// This used to divide the epoch directly, which is UTC: "Paused until 09:30"
/// read hours off for anyone not on UTC, which is the only thing this label is
/// for. `localtime_r` applies the zone *and* DST for the instant in question,
/// which a fixed offset would get wrong across a transition.
fn fmt_clock(epoch_ms: i64) -> String {
    #[cfg(target_os = "macos")]
    {
        let secs = epoch_ms.div_euclid(1000) as libc::time_t;
        // SAFETY: `localtime_r` writes into `tm` and reads `secs`; both are
        // live for the call, and the _r form needs no global lock.
        let tm = unsafe {
            let mut tm: libc::tm = std::mem::zeroed();
            if libc::localtime_r(&secs, &mut tm).is_null() {
                return "??:??".into();
            }
            tm
        };
        format!("{:02}:{:02}", tm.tm_hour, tm.tm_min)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let secs_since_midnight = (epoch_ms / 1000).rem_euclid(86400);
        format!("{:02}:{:02}", secs_since_midnight / 3600, (secs_since_midnight % 3600) / 60)
    }
}

fn on_menu_event(app: &AppHandle, event: tauri::menu::MenuEvent) {
    match event.id().as_ref() {
        "pause_1h" => apply_pause(app, Some(config::now_ms() + HOUR_MS)),
        "pause_8h" => apply_pause(app, Some(config::now_ms() + 8 * HOUR_MS)),
        "pause_indef" => apply_pause(app, Some(i64::MAX)),
        "resume" => apply_pause(app, None),
        "widget" => {
            let show = !app.state::<crate::AppState>().config.lock().unwrap().widget_enabled;
            let _ = widget_set(app.clone(), show);
            refresh(app);
        }
        // Deliberately opens Settings → About rather than downloading straight
        // from the menu: an update ends with the app relaunching, and that is
        // not something to trigger from a menu item with no confirmation.
        "update" => {
            open_main_window(app.clone());
            let _ = app.emit("open-updates", ());
        }
        _ => {}
    }
}

/// Update `paused_until`, persist, notify the frontend (so Settings reflects
/// it live), and rebuild the tray menu's status/enabled-state.
pub fn apply_pause(app: &AppHandle, until: Option<i64>) {
    let updated = {
        let app_state = app.state::<crate::AppState>();
        let mut cfg = app_state.config.lock().unwrap();
        cfg.paused_until = until;
        config::save(app, &cfg);
        cfg.clone()
    };
    let _ = app.emit("config", &updated);
    refresh(app);
}

/// Rebuild the menu in place. Public because the updater relabels its item
/// when a background check finds something.
pub fn refresh(app: &AppHandle) {
    if let Ok(menu) = build_menu(app) {
        if let Some(tray) = app.tray_by_id(TRAY_ID) {
            let _ = tray.set_menu(Some(menu));
        }
    }
}

/// Auto-resume once a timed pause's deadline passes — checked every 30s
/// rather than a one-shot timer, so it survives an app restart mid-pause
/// (the deadline is just a persisted timestamp, not a live timer).
pub async fn expire_loop(app: AppHandle) {
    let mut ticker = tokio::time::interval(std::time::Duration::from_secs(30));
    loop {
        ticker.tick().await;
        let expired = {
            let app_state = app.state::<crate::AppState>();
            let cfg = app_state.config.lock().unwrap();
            cfg.paused_until.is_some() && !cfg.is_paused()
        };
        if expired {
            apply_pause(&app, None);
        }
    }
}

// ── Tauri commands ───────────────────────────────────────────────────────

#[tauri::command]
pub fn pause_set(app: AppHandle, until: Option<i64>) -> config::Config {
    apply_pause(&app, until);
    app.state::<crate::AppState>().config.lock().unwrap().clone()
}

#[tauri::command]
pub fn autostart_get(app: AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

#[tauri::command]
pub fn autostart_set(app: AppHandle, enabled: bool) -> Result<(), String> {
    let mgr = app.autolaunch();
    let result = if enabled { mgr.enable() } else { mgr.disable() };
    result.map_err(|e| e.to_string())
}
