package com.droiddock.app.link.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * Advertising (BLE-DESIGN §3): primary AD = flags + service UUID, scan response =
 * 8-byte rotating id as service data; no name, no TX power. BALANCED (~250 ms)
 * for the first 120 s, then LOW_POWER (~1 s). The rid is re-set at each 15-minute
 * epoch. Only created when the controller asks for it.
 */
@SuppressLint("MissingPermission")
internal class BleAdvertiser(
    private val adapter: () -> BluetoothAdapter?,
    private val token: () -> String?,
    private val onStatus: (busy: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false
        private set
    private var set: AdvertisingSet? = null
    private var legacyCb: AdvertiseCallback? = null
    private var useLegacy = false
    private var lowPower = false
    private var backoff = 5_000L
    private var gen = 0

    private val stepDown = Runnable { if (running) { lowPower = true; restart() } }
    private val rotate = Runnable { if (running) rotateRid() }
    private val retry = Runnable { if (running) begin() }

    fun start() {
        if (running) return
        running = true
        lowPower = false
        backoff = 5_000L
        begin()
        main.postDelayed(stepDown, 120_000L)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(stepDown); main.removeCallbacks(rotate); main.removeCallbacks(retry)
        halt()
    }

    private fun halt() {
        gen++
        val adv = adapter()?.bluetoothLeAdvertiser
        runCatching { set?.let { adv?.stopAdvertisingSet(setCb) } }
        runCatching { legacyCb?.let { adv?.stopAdvertising(it) } }
        set = null; legacyCb = null
    }

    private fun restart() { halt(); begin() }

    private fun scanResponse(): AdvertiseData? {
        val t = token() ?: return null
        val rid = BleCrypto.rid(t, BleCrypto.epoch(clock()))
        return AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(BleIds.SERVICE), rid).build()
    }

    private fun primary() = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(BleIds.SERVICE)).build()

    private fun begin() {
        val adv = adapter()?.bluetoothLeAdvertiser ?: return onStatus(true)
        val sr = scanResponse() ?: return
        scheduleRotation()
        val ok = runCatching {
            if (!useLegacy) {
                val p = AdvertisingSetParameters.Builder()
                    .setLegacyMode(true).setConnectable(true).setScannable(true)
                    .setInterval(if (lowPower) AdvertisingSetParameters.INTERVAL_HIGH else AdvertisingSetParameters.INTERVAL_MEDIUM)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM).build()
                adv.startAdvertisingSet(p, primary(), sr, null, null, setCb)
            } else {
                val cb = object : AdvertiseCallback() {
                    override fun onStartSuccess(s: AdvertiseSettings?) { onStatus(false) }
                    override fun onStartFailure(errorCode: Int) { failed(errorCode) }
                }
                legacyCb = cb
                adv.startAdvertising(
                    AdvertiseSettings.Builder().setConnectable(true)
                        .setAdvertiseMode(if (lowPower) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                    primary(), sr, cb,
                )
            }
        }.isSuccess
        if (!ok) failed(-1)
    }

    private fun failed(code: Int) {
        // ADVERTISE_FAILED_FEATURE_UNSUPPORTED (5 for sets, 5 for the legacy call): use the classic API.
        if (code == 5 && !useLegacy) { useLegacy = true; main.post { if (running) restart() }; return }
        onStatus(true) // "Bluetooth busy" in Permission Health
        main.removeCallbacks(retry)
        main.postDelayed(retry, backoff)
        backoff = minOf(backoff * 2, 300_000L)
    }

    private fun rotateRid() {
        val sr = scanResponse() ?: return
        val ok = runCatching { set?.setScanResponseData(sr) }.isSuccess && set != null
        if (!ok) restart() else scheduleRotation()
    }

    private fun scheduleRotation() {
        main.removeCallbacks(rotate)
        val now = clock()
        val untilNext = (BleCrypto.epoch(now) + 1) * BleCrypto.EPOCH_MS - now
        main.postDelayed(rotate, untilNext + 500)
    }

    private val setCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(s: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) { set = s; backoff = 5_000L; onStatus(false) } else failed(status)
        }
        override fun onScanResponseDataSet(s: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) main.post { if (running) restart() }
        }
    }
}
