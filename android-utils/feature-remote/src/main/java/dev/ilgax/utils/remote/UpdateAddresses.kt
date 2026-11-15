package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.OfferFrame
import java.net.Inet4Address
import java.net.NetworkInterface

/** The tablet's IPv4 addresses worth offering to the watch, best first. */
internal object UpdateAddresses {
    class Candidate(val iface: String, val ip: String)

    private val HOTSPOT = Regex("^(ap|swlan|softap|wlan1)\\d*$")
    private val WIFI = Regex("^wlan0$")

    /** Hotspot gateway first (the watch is usually on it), then the Wi-Fi LAN, then anything else; unique, at most 4. */
    fun pick(candidates: List<Candidate>): List<String> {
        fun rank(c: Candidate) = when {
            HOTSPOT.matches(c.iface) -> 0
            WIFI.matches(c.iface) -> 1
            else -> 2
        }
        return candidates
            .filter { usable(it.ip) }
            .sortedBy(::rank) // stable: keeps enumeration order inside a rank
            .map { it.ip }
            .distinct()
            .take(OfferFrame.MAX_ADDRESSES)
    }

    /** Non-loopback, non-link-local, non-wildcard dotted IPv4. */
    fun usable(ip: String): Boolean {
        val b = OfferFrame.parseIpv4(ip) ?: return false
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first != 127 && first != 0 && !(first == 169 && second == 254) && first < 224
    }

    fun collect(): List<String> = try {
        val all = ArrayList<Candidate>()
        for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address) addr.hostAddress?.let { all += Candidate(nif.name, it) }
            }
        }
        pick(all)
    } catch (e: Exception) {
        emptyList()
    }
}
