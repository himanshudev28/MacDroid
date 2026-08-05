package com.droiddock.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Finds the Mac over Bonjour/mDNS (Tier C).
 *
 * The UDP broadcast probe in [ConnectionManager.discoverViaBroadcast] stays the
 * primary path and is untouched — this is the fallback for networks that drop
 * directed broadcast or isolate clients, where the phone otherwise has no way
 * back after the Mac's IP changes.
 *
 * Returns only an address. Authentication is unchanged: the caller still opens a
 * normal WebSocket and still has to present the stored pairing token, so a
 * hostile service answering this browse gains nothing beyond wasting one
 * connection attempt.
 */
/** A Mac seen advertising itself on the network. Carries no credentials — see
 *  [MdnsDiscovery.browse] for why that matters. */
data class DiscoveredMac(
    val name: String,
    val ip: String,
    val port: Int,
    /** How it was found, shown as a tag in the UI. */
    val via: String = "mDNS",
)

object MdnsDiscovery {
    private const val SERVICE_TYPE = "_droiddock._tcp."

    /** First reachable IPv4 advertised on this LAN, or null within [timeoutMs].
     *  Returns as soon as one resolves — this is on the reconnect path, so it
     *  must not sit out the full timeout when the answer arrived immediately. */
    suspend fun find(ctx: Context, timeoutMs: Long = 3_000): String? =
        sweep(ctx, timeoutMs, stopOnFirst = true).firstOrNull()?.ip

    /**
     * Every Mac advertising on this LAN, for the "Available Devices" list.
     *
     * Waits out the full [timeoutMs] rather than stopping at the first hit,
     * because the point here is the whole list. Note that mDNS yields an
     * *address*, never a token — so a discovered Mac can only be connected to
     * if this phone already paired with it before. An unknown one still has to
     * go through QR or manual pairing, which is the only place a token comes
     * from.
     */
    suspend fun browse(ctx: Context, timeoutMs: Long = 2_500): List<DiscoveredMac> =
        sweep(ctx, timeoutMs, stopOnFirst = false)

    private suspend fun sweep(
        ctx: Context,
        timeoutMs: Long,
        stopOnFirst: Boolean,
    ): List<DiscoveredMac> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = CompletableDeferred<String?>()
        val seen = java.util.concurrent.ConcurrentHashMap<String, DiscoveredMac>()
        val firstResolved = java.util.concurrent.atomic.AtomicReference<String?>(null)

        // Every ServiceInfoCallback registered during this browse. Each one holds a
        // live multicast listener until it is explicitly unregistered, and find()
        // runs on every failed reconnect round — so without this they piled up by
        // the hundred over an evening of the Mac being off. Cleared in `finally`,
        // which covers the timeout path as well as success. Thread-safe because
        // callbacks are delivered on NSD's own thread, not ours.
        val infoCallbacks = java.util.concurrent.CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                found.complete(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                // A browse result carries no address — it has to be resolved.
                resolve(nsd, service, infoCallbacks) { resolved ->
                    if (resolved != null) {
                        seen[resolved.ip] = resolved
                        firstResolved.compareAndSet(null, resolved.ip)
                        if (stopOnFirst) found.complete(resolved.ip)
                    }
                }
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            // In list mode nothing ever completes `found`, so this always runs
            // the clock out and returns null — which is the intent.
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (_: Exception) {
            // fall through to whatever was collected before the failure
        } finally {
            // Always tear the browse down: NsdManager leaks a live multicast
            // listener (and drains battery) if the discovery is left running.
            runCatching { nsd.stopServiceDiscovery(listener) }
            infoCallbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
        }
        val byName = seen.values.sortedBy { it.name.lowercase() }
        // find() takes the head of this list, and it must get the address that
        // actually answered first — not whichever Mac happens to sort earliest,
        // which on a two-Mac network would hand the connect loop the one that
        // never replied.
        val winner = firstResolved.get()
        return if (winner == null) byName
        else byName.sortedByDescending { it.ip == winner }
    }

    private fun resolve(
        nsd: NsdManager,
        service: NsdServiceInfo,
        infoCallbacks: MutableList<NsdManager.ServiceInfoCallback>,
        done: (DiscoveredMac?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            // resolveService is deprecated from API 34; the callback flavour is
            // the supported replacement and doesn't serialise resolves.
            runCatching {
                // Deliberately keeps listening after an update that carried no
                // address: onServiceUpdated fires repeatedly and the first one
                // frequently arrives before the host resolves, so unregistering on
                // the first null would lose the Mac on exactly the WiFi-switch this
                // fallback exists for. The registration is tracked instead, and
                // find()'s `finally` unregisters it once the browse is over.
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = done(null)
                    override fun onServiceUpdated(info: NsdServiceInfo) = done(toMac(info))
                    override fun onServiceLost() = done(null)
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                infoCallbacks.add(cb)
                nsd.registerServiceInfoCallback(service, { it.run() }, cb)
            }.onFailure { done(null) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) = done(null)
                    override fun onServiceResolved(s: NsdServiceInfo) = done(toMac(s))
                })
            }.onFailure { done(null) }
        }
    }

    /** A resolved service → a displayable entry, or null if it carried no IPv4
     *  yet (which happens on the first update or two). */
    private fun toMac(info: NsdServiceInfo): DiscoveredMac? {
        val ip = firstIpv4(info) ?: return null
        val port = info.port.takeIf { it > 0 } ?: 48484
        // NSD hands back the Bonjour instance name, which is what the Mac chose
        // to call itself. Blank on some OEM stacks, hence the fallback.
        val name = info.serviceName?.takeIf { it.isNotBlank() } ?: "Mac"
        return DiscoveredMac(name = name, ip = ip, port = port)
    }

    /** The Mac advertises v4 only, but a resolve can still surface link-local v6. */
    private fun firstIpv4(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            (info.host as? Inet4Address)?.hostAddress
        }
}
