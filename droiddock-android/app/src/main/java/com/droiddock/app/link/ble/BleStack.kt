package com.droiddock.app.link.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.droiddock.app.ConnectionManager
import com.droiddock.app.Prefs
import com.droiddock.app.link.FallbackController
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Everything Bluetooth, behind one door. **Nothing here touches a Bluetooth API
 * until [sync] finds fallback mode on, the permission granted and a pairing
 * present**; in the default "network" mode this object is never even asked for
 * a BluetoothManager, so there is no prompt, no object, no advertising.
 */
@SuppressLint("MissingPermission")
object BleStack {
    enum class Status { OFF, NO_PERMISSION, RADIO_OFF, STANDBY, ADVERTISING, CONNECTED, BUSY, LOCKED }

    val status = MutableStateFlow(Status.OFF)

    private val controller = FallbackController()
    private var appCtx: Context? = null
    private var server: BleGattServer? = null
    private var advertiser: BleAdvertiser? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var started = false
    @Volatile var radioOn = false
        private set
    @Volatile private var busy = false
    @Volatile var lockedUntil = 0L
        private set
    private val failures = ArrayDeque<Long>()
    @Volatile private var link: BleLink? = null

    fun hasBle(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** ADVERTISE + CONNECT on Android 12+; the old install-time grants below that. */
    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    /** Runtime permissions to request (empty below Android 12: nothing to ask). */
    fun permissionsToRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    fun wanted(ctx: Context) =
        Prefs.connectionMode(ctx) == "fallback" && hasBle(ctx) && hasPermission(ctx) && Prefs.load(ctx) != null

    fun lockedOut() = lockedUntil > System.currentTimeMillis()

    /** Idempotent: bring the stack in line with mode, permission and pairing. */
    @Synchronized
    fun sync(ctx: Context) {
        appCtx = ctx.applicationContext
        val want = wanted(ctx)
        if (want && !started) start(ctx.applicationContext)
        else if (!want && started) stop()
        if (!want) {
            status.value = when {
                Prefs.connectionMode(ctx) != "fallback" -> Status.OFF
                !hasPermission(ctx) -> Status.NO_PERMISSION
                else -> Status.OFF
            }
        }
        ConnectionManager.reevaluateLinks()
    }

    private fun token() = appCtx?.let { Prefs.load(it)?.token }

    private fun start(ctx: Context) {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        started = true
        advertiser = BleAdvertiser({ bm.adapter }, ::token, { b -> busy = b; refreshStatus() })
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> { radioOn = true; openServer(bm) }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        radioOn = false; teardownRadio(); refreshStatus()
                    }
                }
                ConnectionManager.reevaluateLinks()
            }
        }
        receiver = rcv
        ctx.registerReceiver(rcv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        radioOn = bm.adapter?.isEnabled == true
        if (radioOn) openServer(bm)
        refreshStatus()
    }

    private fun openServer(bm: BluetoothManager) {
        val ctx = appCtx ?: return
        server?.close()
        server = BleGattServer(ctx, host).also { it.open(bm, ::token) }
    }

    private fun teardownRadio() {
        advertiser?.stop()
        server?.close()
        server = null
    }

    @Synchronized
    private fun stop() {
        started = false
        runCatching { receiver?.let { appCtx?.unregisterReceiver(it) } }
        receiver = null
        teardownRadio()
        advertiser = null
        radioOn = false
        busy = false
        status.value = Status.OFF
    }

    /** Pairing changed: drop the session and re-key the advertisement. */
    fun onPairingChanged() {
        link?.dropNow()
        advertiser?.let { if (it.running) { it.stop() } }
        ConnectionManager.reevaluateLinks()
    }

    /** Apply the controller's verdict; returns it for ConnectionManager's own use. */
    fun evaluate(i: FallbackController.Inputs): FallbackController.Decision {
        val d = controller.evaluate(i)
        if (started) {
            val adv = advertiser
            if (d.advertise && server?.isReady == true) adv?.start() else adv?.stop()
            if (d.dropBle) link?.close()
            refreshStatus()
        }
        return d
    }

    private fun refreshStatus() {
        if (!started) return
        status.value = when {
            lockedOut() -> Status.LOCKED
            !radioOn -> Status.RADIO_OFF
            link != null -> Status.CONNECTED
            busy -> Status.BUSY
            advertiser?.running == true -> Status.ADVERTISING
            else -> Status.STANDBY
        }
    }

    private val host = object : BleGattServer.Host {
        override fun onServerReady(ok: Boolean) { ConnectionManager.reevaluateLinks() }

        override fun onAuthenticated(conn: BleGattServer.Conn) {
            val ctx = appCtx ?: return
            val pairing = Prefs.load(ctx) ?: return
            // Step 6: the normal hello, now inside the sealed channel.
            conn.session.send(ConnectionManager.buildHello(pairing))
        }

        override fun onMessage(conn: BleGattServer.Conn, msg: JSONObject) {
            val l = link?.takeIf { it.conn === conn } ?: BleLink(conn).also { if (msg.optString("type") == "welcome") link = it }
            ConnectionManager.onBleMessage(l, msg)
            refreshStatus()
        }

        override fun onAuthFailed() {
            val now = System.currentTimeMillis()
            failures.addLast(now)
            while (failures.isNotEmpty() && now - failures.first() > 300_000) failures.removeFirst()
            if (failures.size >= 3) {
                lockedUntil = now + 300_000
                failures.clear()
                advertiser?.stop()
                refreshStatus()
                ConnectionManager.reevaluateLinks()
            }
        }

        override fun onClosed(conn: BleGattServer.Conn) {
            val l = link
            if (l != null && l.conn === conn) {
                link = null
                ConnectionManager.onBleClosed(l)
            }
            refreshStatus()
            ConnectionManager.reevaluateLinks()
        }
    }

}
