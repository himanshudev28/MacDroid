package com.droiddock.app.link.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/** BLE-DESIGN §3 UUIDs. Never change. */
object BleIds {
    val SERVICE: UUID = UUID.fromString("B5800E35-6F66-41DF-A777-60B974C4B5F9")
    val M2P: UUID = UUID.fromString("B5800E36-6F66-41DF-A777-60B974C4B5F9")
    val P2M: UUID = UUID.fromString("B5800E37-6F66-41DF-A777-60B974C4B5F9")
    val INFO: UUID = UUID.fromString("B5800E38-6F66-41DF-A777-60B974C4B5F9")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** [protoVer=1][flags=0][maxFrame u24 BE = 65536] */
    val INFO_VALUE = byteArrayOf(1, 0, 0x01, 0x00, 0x00)
}

/**
 * The phone's GATT server (L1): one service, three characteristics, one Mac at a
 * time. Created only when the user turned Bluetooth fallback on and the grant
 * exists. Tolerates the Samsung quirks in BLE-DESIGN §10: notifications strictly
 * one at a time (next only after onNotificationSent), MTU assumed 23 until told,
 * no traffic before onServiceAdded.
 */
@SuppressLint("MissingPermission")
internal class BleGattServer(private val ctx: Context, private val host: Host) {

    interface Host {
        fun onServerReady(ok: Boolean)
        fun onAuthenticated(conn: Conn)
        fun onMessage(conn: Conn, msg: JSONObject)
        fun onAuthFailed()
        fun onClosed(conn: Conn)
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null
    @Volatile private var ready = false
    @Volatile private var current: Conn? = null

    val isReady get() = ready
    val hasConnection get() = current != null

    /** One connected Mac: its L2 session plus the L1 pump state. */
    inner class Conn(val device: BluetoothDevice, token: String) : BleSession.Listener {
        val session = BleSession(token, this)
        @Volatile var mtu = 23
        private val frag = BleFraming.Fragmenter()
        private val reasm = BleFraming.Reassembler()
        private val pending = ArrayDeque<ByteArray>()
        private var inFlight = false
        private var closeWhenDrained = false
        @Volatile var closed = false
        @Volatile private var welcomed = false
        private val deadline = Runnable { if (!welcomed) disconnect("handshake timeout") }

        fun armHandshakeTimer() { main.postDelayed(deadline, HANDSHAKE_MS) }

        fun onWrite(value: ByteArray) {
            try {
                val frame = reasm.push(value) ?: return
                session.onFrame(frame)
            } catch (e: BleFraming.ProtocolError) {
                disconnect(e.message ?: "framing")
            }
        }

        override fun onWake() = pump()
        override fun onAuthenticated() = host.onAuthenticated(this)
        override fun onMessage(msg: JSONObject) {
            if (msg.optString("type") == "welcome") { welcomed = true; main.removeCallbacks(deadline) }
            host.onMessage(this, msg)
        }
        override fun onBye(reason: Int) = disconnect("bye $reason")
        override fun onAuthFailed() {
            host.onAuthFailed()
            synchronized(this) { closeWhenDrained = true }
            pump()
        }
        override fun onFatal(why: String) = disconnect(why)

        fun notificationSent(ok: Boolean) {
            synchronized(this) { inFlight = false }
            if (!ok) disconnect("notify failed") else pump()
        }

        fun pump() {
            val next: ByteArray?
            var finish = false
            synchronized(this) {
                if (closed || inFlight) return
                if (pending.isEmpty()) {
                    session.pollFrame()?.let { pending.addAll(frag.fragment(it, maxOf(mtu - 3, 20))) }
                }
                next = pending.removeFirstOrNull()
                if (next != null) inFlight = true
                else if (closeWhenDrained) finish = true
            }
            if (finish) { disconnect("auth failed"); return }
            if (next != null && !notify(this, next)) {
                synchronized(this) { inFlight = false }
                disconnect("notify rejected")
            }
        }

        fun disconnect(why: String) {
            if (closed) return
            closed = true
            main.removeCallbacks(deadline)
            session.kill()
            runCatching { server?.cancelConnection(device) }
            if (current === this) current = null
            host.onClosed(this)
        }
    }

    fun open(manager: BluetoothManager, token: () -> String?) {
        close()
        this.tokenProvider = token
        val s = manager.openGattServer(ctx, callback) ?: return host.onServerReady(false)
        server = s
        val svc = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.M2P,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        val p2m = BluetoothGattCharacteristic(
            BleIds.P2M, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        )
        p2m.addDescriptor(
            BluetoothGattDescriptor(
                BleIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        svc.addCharacteristic(p2m)
        val info = BluetoothGattCharacteristic(
            BleIds.INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        info.value = BleIds.INFO_VALUE
        svc.addCharacteristic(info)
        service = svc
        if (!s.addService(svc)) host.onServerReady(false)
    }

    private var tokenProvider: () -> String? = { null }

    fun close() {
        ready = false
        current?.let { c -> c.closed = true; c.session.kill() ; current = null; host.onClosed(c) }
        runCatching { server?.close() }
        server = null
        service = null
    }

    private fun notify(c: Conn, value: ByteArray): Boolean {
        val s = server ?: return false
        val ch = service?.getCharacteristic(BleIds.P2M) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(c.device, ch, false, value) == 0 // BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { ch.value = value.copyOf(); s.notifyCharacteristicChanged(c.device, ch, false) }
            }
        }.getOrDefault(false)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, svc: BluetoothGattService) {
            ready = status == BluetoothGatt.GATT_SUCCESS && svc.uuid == BleIds.SERVICE
            host.onServerReady(ready)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val token = tokenProvider()
                if (!ready || token == null || current != null) {
                    runCatching { server?.cancelConnection(device) }
                    return
                }
                val c = Conn(device, token)
                current = c
                c.armHandshakeTimer()
            } else {
                current?.takeIf { it.device.address == device.address }?.disconnect("gatt disconnected")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            current?.takeIf { it.device.address == device.address }?.mtu = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic,
        ) {
            val v = if (ch.uuid == BleIds.INFO) BleIds.INFO_VALUE else ByteArray(0)
            val ok = offset <= v.size
            runCatching {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                    offset, if (ok) v.copyOfRange(offset, v.size) else null,
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val c = current ?: return
            if (ch.uuid == BleIds.M2P && value != null && c.device.address == device.address) c.onWrite(value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor,
        ) {
            val on = current?.let { subscribed[it] } == true
            runCatching {
                server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                    if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val c = current ?: return
            if (d.uuid == BleIds.CCCD && value != null && c.device.address == device.address &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ) {
                subscribed[c] = true
                c.session.onSubscribed() // sends P_HELLO
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            current?.notificationSent(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private val subscribed = java.util.WeakHashMap<Conn, Boolean>()

    companion object { const val HANDSHAKE_MS = 5_000L }
}
