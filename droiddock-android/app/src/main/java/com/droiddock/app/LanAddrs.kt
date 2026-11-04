package com.droiddock.app

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Which addresses are on this phone's own subnets.
 *
 * Needed because the phone can be the hotspot: the Mac then sits on an
 * interface that is not a ConnectivityManager Network, and may also advertise
 * a stale address from another Wi-Fi. Pure ordering is split from the
 * interface reads so it can be tested on the JVM.
 */

/** [candidates] de-duplicated, addresses inside any [local] (ip, prefix) subnet first.
 *  Order is otherwise preserved. */
fun orderBySubnet(candidates: List<String>, local: List<Pair<String, Int>>): List<String> {
    val uniq = candidates.distinct()
    val (near, far) = uniq.partition { c -> local.any { (ip, p) -> sameSubnet(c, ip, p) } }
    return near + far
}

/** Candidates not yet dialed this round, de-duplicated, in order. */
fun notTriedYet(candidates: List<String>, tried: Set<String>): List<String> =
    candidates.distinct().filter { it !in tried }

internal fun ipv4ToInt(s: String): Int? {
    val p = s.split('.')
    if (p.size != 4) return null
    var v = 0
    for (x in p) {
        val n = x.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        v = (v shl 8) or n
    }
    return v
}

internal fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
    if (prefix !in 1..32) return false
    val x = ipv4ToInt(a) ?: return false
    val y = ipv4ToInt(b) ?: return false
    val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
    return (x and mask) == (y and mask)
}

/** RFC1918 ranges (also what phone hotspots hand out). */
internal fun isPrivateLan(ip: String): Boolean {
    val v = ipv4ToInt(ip) ?: return false
    val a = v ushr 24
    val b = (v ushr 16) and 0xFF
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

object LanAddrs {
    /** Every up, non-loopback interface's IPv4 as (ip, prefix). Includes the
     *  hotspot/tether interface. */
    fun localSubnets(): List<Pair<String, Int>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { ia -> ia.address.hostAddress?.let { it to ia.networkPrefixLength.toInt() } }
    }.getOrDefault(emptyList())

    /** Directed broadcast address of each up IPv4 interface. */
    fun broadcastAddresses(): List<java.net.InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
            .distinctBy { it.hostAddress }
    }.getOrDefault(emptyList())

    /** True if some up interface holds a private-range IPv4 (Wi-Fi, hotspot, USB tether). */
    fun hasPrivateLan(): Boolean = localSubnets().any { isPrivateLan(it.first) }
}

/** Which known device an advertised Mac is: an address match first, then a
 *  name match only if exactly one device has that name; otherwise none. */
fun matchKnown(known: List<KnownDevice>, macIps: List<String>, port: Int, name: String): KnownDevice? {
    known.firstOrNull { d -> d.port == port && d.ips.any { it in macIps } }?.let { return it }
    return known.filter { it.macName.equals(name, ignoreCase = true) }.singleOrNull()
}

/** Candidate addresses from discovery: services named [preferName] only if any
 *  exist (never offer the token to other Macs needlessly), else all. */
fun candidateAddresses(macs: List<DiscoveredMac>, preferName: String?, max: Int = 6): List<String> {
    val named = preferName?.let { n -> macs.filter { it.name.equals(n, ignoreCase = true) } }.orEmpty()
    return (named.ifEmpty { macs }).flatMap { it.ips }.distinct().take(max)
}
