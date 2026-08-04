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
object MdnsDiscovery {
    private const val SERVICE_TYPE = "_droiddock._tcp."

    /** First reachable IPv4 advertised on this LAN, or null within [timeoutMs]. */
    suspend fun find(ctx: Context, timeoutMs: Long = 3_000): String? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val found = CompletableDeferred<String?>()

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
                resolve(nsd, service) { ip -> if (ip != null) found.complete(ip) }
            }
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (_: Exception) {
            null
        } finally {
            // Always tear the browse down: NsdManager leaks a live multicast
            // listener (and drains battery) if the discovery is left running.
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun resolve(nsd: NsdManager, service: NsdServiceInfo, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 34) {
            // resolveService is deprecated from API 34; the callback flavour is
            // the supported replacement and doesn't serialise resolves.
            runCatching {
                nsd.registerServiceInfoCallback(
                    service,
                    { it.run() },
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = done(null)
                        override fun onServiceUpdated(info: NsdServiceInfo) = done(firstIpv4(info))
                        override fun onServiceLost() = done(null)
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }.onFailure { done(null) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) = done(null)
                    override fun onServiceResolved(s: NsdServiceInfo) = done(firstIpv4(s))
                })
            }.onFailure { done(null) }
        }
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
