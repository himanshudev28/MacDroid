package com.droiddock.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the network is doing, as something the connect loop can *wait on*
 * instead of rediscover by probing.
 *
 * [ConnectionManager.connectLoop] used to run its full sweep — up to four TCP
 * dials, a UDP broadcast and a live mDNS browse — on a fixed backoff no matter
 * what, so a phone in a pocket with the Mac shut down (or in airplane mode, or
 * on cellular where a LAN probe cannot possibly succeed) kept the Wi-Fi radio
 * busy around the clock. Two facts remove almost all of that:
 *
 *  - **Is there a network at all?** [awaitOnline] parks the loop with no timer
 *    until one appears, so no-network costs literally nothing.
 *  - **Did the network just change?** [sleepOrWake] cuts a backoff short the
 *    instant a new network arrives, which is also the moment the Mac is most
 *    likely to be reachable again. That turns "up to ~35s to notice the Mac is
 *    back" into "as soon as Android tells us Wi-Fi is up".
 *
 * One process-wide default-network callback backs all of it. Registering it is
 * cheap and it replaces polling, so it is never unregistered.
 */
object NetworkWatch {
    /** Optimistic until the first callback lands: a missed probe is a much
     *  cheaper mistake than refusing to connect on a phone whose
     *  ConnectivityManager we failed to read. */
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /** True unless we positively know the only way out is cellular. A LAN probe
     *  cannot work there, but a VPN (Tailscale) or an unknown transport might,
     *  so anything we are not sure about counts as capable. */
    @Volatile var lanCapable: Boolean = true
        private set

    /** Coalescing "something changed, stop waiting" nudge. Capacity 1 with
     *  DROP_OLDEST: a burst of callbacks during a Wi-Fi handover should wake the
     *  loop once, not queue five wake-ups it will act on one at a time. */
    private val wake = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                // The single most valuable wake-up there is: Wi-Fi just came
                // back, which usually means the Mac is reachable again.
                poke()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifiish = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val cellularOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !wifiish
                val next = !cellularOnly
                if (next != lanCapable) {
                    lanCapable = next
                    poke()
                }
                _online.value = true
            }

            override fun onLost(network: Network) {
                // Only the *default* network is watched, so losing it means
                // there is nothing to reach the Mac over. A replacement arrives
                // as its own onAvailable.
                _online.value = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure {
                // Without the callback we cannot know; stay optimistic so the
                // loop behaves exactly as it did before this existed.
                _online.value = true
                lanCapable = true
            }
    }

    /** Wake anything sleeping in [sleepOrWake] right now. Safe from any thread. */
    fun poke() {
        wake.tryEmit(Unit)
    }

    /** Park until there is a default network. Returns at once when there is. */
    suspend fun awaitOnline() {
        online.first { it }
    }

    /** Sleep up to [ms], returning early if the network changed underneath us. */
    suspend fun sleepOrWake(ms: Long) {
        if (ms <= 0) return
        withTimeoutOrNull(ms) { wake.first() }
    }
}
