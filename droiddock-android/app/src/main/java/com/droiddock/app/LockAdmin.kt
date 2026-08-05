package com.droiddock.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device-admin receiver whose only reason to exist is `lockNow()`.
 *
 * It declares exactly one policy — `force-lock` (see `res/xml/device_admin.xml`)
 * — and overrides nothing. There is no wipe, no password policy, no camera
 * restriction: the policy list in that XML is the complete set of things this
 * app is technically able to do as an admin, and it is one item long.
 */
class LockAdminReceiver : DeviceAdminReceiver()

/**
 * The non-accessibility route to locking the screen.
 *
 * # Why this exists
 *
 * Locking was originally done through the accessibility service
 * (`GLOBAL_ACTION_LOCK_SCREEN`). That works, but it makes the Lock button a
 * hostage to a permission many people have to keep switched off: banking apps
 * commonly refuse to run while *any* accessibility service is enabled — they
 * call `AccessibilityManager.getEnabledAccessibilityServiceList()` and don't
 * care which service it is or what it can do. Nothing DroidDock's own service
 * declares changes that.
 *
 * `DevicePolicyManager.lockNow()` needs an active device admin instead, which
 * is a different permission those apps generally don't inspect. So with this
 * granted, Lock keeps working with accessibility off — which is the whole
 * point.
 *
 * # The honest cost
 *
 * Device admin shows a serious system prompt, and Android won't let the app be
 * uninstalled until the admin is deactivated. That's why this is **opt-in and
 * separate**: the accessibility path still works, and someone who doesn't want
 * a device admin simply never grants it and loses nothing they had before.
 */
object LockAdmin {
    private fun component(ctx: Context) = ComponentName(ctx, LockAdminReceiver::class.java)

    private fun dpm(ctx: Context): DevicePolicyManager? = runCatching {
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }.getOrNull()

    fun isActive(ctx: Context): Boolean =
        runCatching { dpm(ctx)?.isAdminActive(component(ctx)) == true }.getOrDefault(false)

    /**
     * The system's grant screen. Caller starts it; there is no silent path.
     *
     * **Must be launched from an Activity, without `FLAG_ACTIVITY_NEW_TASK`.**
     * `DeviceAdminAdd` hands a result back to whoever asked, so it refuses to
     * run in a task of its own:
     *
     *     W/SecDeviceAdminAdd: Cannot start ADD_DEVICE_ADMIN as a new task
     *
     * and finishes itself in `onCreate`. Nothing throws and nothing appears —
     * the button simply looks broken. The Settings row launches this through an
     * `ActivityResultLauncher`, which can't carry the flag by construction.
     */
    fun enableIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets your Mac's Lock button lock this screen without needing " +
                    "accessibility access. DroidDock asks for only one admin " +
                    "policy — lock the screen — and nothing else.",
            )

    /** Give it back. Deactivating also makes the app uninstallable again. */
    fun disable(ctx: Context) {
        runCatching { dpm(ctx)?.removeActiveAdmin(component(ctx)) }
    }

    /**
     * Lock now. Returns false when the admin isn't active, so the caller can
     * fall back to the accessibility path rather than silently doing nothing —
     * a Lock button that no-ops is the failure this whole file exists to avoid.
     */
    fun lock(ctx: Context): Boolean {
        if (!isActive(ctx)) return false
        return runCatching { dpm(ctx)?.lockNow(); true }.getOrDefault(false)
    }
}
