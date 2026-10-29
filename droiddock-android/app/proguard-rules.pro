# DroidDock release shrinker rules (R8, full mode default on AGP 8).
#
# Manifest-declared Activities / Services / Receivers / Providers (including
# AccessibilityService, NotificationListenerService, TileService, AppWidget and
# DeviceAdmin receivers) are kept automatically by AGP's generated aapt rules,
# NAMES INCLUDED. That matters: PermissionHealth and the setup screen compare
# `ClipAccessibilityService::class.java.name` against Settings.Secure's enabled
# accessibility list, so renaming that class would silently break the check.
# The explicit keeps below make that dependency visible and survive a manifest
# refactor.
-keepnames class com.droiddock.app.ClipAccessibilityService
-keepnames class com.droiddock.app.NotifListener

# org.json is the platform's own (android.jar); nothing to keep. No protobuf,
# no reflection, no JNI in this app.

# OkHttp ships consumer rules; only its optional TLS providers are absent.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable crash traces (CrashNotifier prints stack frames).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- R8 safety -------------------------------------------------------------
# Enums persisted in SharedPreferences by Enum.name (Prefs ThemeMode,
# AppearanceStore UiStyle/NavLayout/MotionPersonality/GlassIntensity). Keep
# constant names so a saved value always resolves, whatever R8 decides later.
-keepclassmembers enum com.droiddock.app.ThemeMode { <fields>; }
-keepclassmembers enum com.droiddock.app.ui.theme.** { <fields>; }

# Component classes referenced by ComponentName(ctx, X::class.java) or the
# manifest / res/xml (tiles, widget, device admin). Kept with names.
-keepnames class com.droiddock.app.ClipAccessibilityService
-keepnames class com.droiddock.app.NotifListener
-keepnames class com.droiddock.app.LockAdminReceiver
-keepnames class com.droiddock.app.DockWidget
-keepnames class com.droiddock.app.ClipTileService
-keepnames class com.droiddock.app.A11yTileService
-keepnames class com.droiddock.app.ConnectionTileService

# Names stay readable: shrinking + optimisation remain, renaming is off. This
# removes the whole renaming class of crashes (name-based lookups, persisted
# enum names) and makes CrashNotifier traces readable without mapping.txt.
# Cost: ~0.1 MB (see docs/ANDROID-PERF.md).
-dontobfuscate
