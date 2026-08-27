package com.phoebe.app.player

import com.phoebe.app.platform.PhoebeLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

internal const val ChromecastServiceType = "_googlecast._tcp.local."
internal const val DefaultChromecastPort = 8009

private val DesktopVirtualInterfaceNamePrefixes = listOf(
    "docker", "br-", "veth", "vmnet", "vbox", "virbr", "tun", "tap", "utun",
    "awdl", "llw", "bridge", "ap", "ipsec", "ppp", "gif", "stf", "zt", "wg",
)

internal fun ensureDesktopChromecastNetworkingConfigured() {
    if (System.getProperty("java.net.preferIPv4Stack") == null) {
        System.setProperty("java.net.preferIPv4Stack", "true")
    }
}

fun configureDesktopChromecastNetworking() {
    ensureDesktopChromecastNetworkingConfigured()
}

internal fun desktopChromecastDiscoveryAddresses(): List<InetAddress> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { iface ->
                runCatching {
                    iface.isUp &&
                        !iface.isLoopback &&
                        !iface.isVirtual &&
                        !iface.isPointToPoint &&
                        !isDesktopVirtualInterfaceName(iface.name)
                }.getOrDefault(false)
            }
            .sortedBy { iface -> desktopChromecastInterfacePreference(iface.name) }
            .flatMap { iface ->
                iface.inetAddresses.toList().asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address ->
                        val host = address.hostAddress?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (address.isLinkLocalAddress) return@mapNotNull null
                        address to host
                    }
            }
            .map { it.first }
            .distinctBy { it.hostAddress }
            .toList()
    }.onFailure { error ->
        PhoebeLog.d("DesktopCastDiscovery") { "Chromecast interface lookup failed: ${error.message}" }
    }.getOrDefault(emptyList())

internal fun desktopChromecastDiscoveryAddress(): InetAddress? =
    desktopChromecastDiscoveryAddresses().firstOrNull()

private fun isDesktopVirtualInterfaceName(name: String): Boolean {
    val lower = name.lowercase()
    return DesktopVirtualInterfaceNamePrefixes.any { prefix ->
        lower == prefix || lower.startsWith(prefix)
    }
}

private fun desktopChromecastInterfacePreference(name: String): Int =
    when {
        name.equals("en0", ignoreCase = true) -> 0
        name.startsWith("en", ignoreCase = true) -> 1
        name.startsWith("wlan", ignoreCase = true) -> 2
        name.startsWith("eth", ignoreCase = true) -> 3
        else -> 10
    }

internal class DesktopChromecastDiscoverySession : AutoCloseable {
    private val lock = Any()
    private val devicesById = linkedMapOf<String, DesktopCastDevice>()
    private val jmdnsInstances = mutableListOf<JmDNS>()

    init {
        ensureDesktopChromecastNetworkingConfigured()
        val bindAddresses = desktopChromecastDiscoveryAddresses()
        PhoebeLog.d("DesktopCastDiscovery") {
            val hosts = bindAddresses.mapNotNull { it.hostAddress }
            "starting Chromecast mDNS on ${hosts.joinToString().ifBlank { "default interface" }}"
        }
        if (bindAddresses.isEmpty()) {
            val jmdns = JmDNS.create()
            jmdns.addServiceListener(ChromecastServiceType, ChromecastServiceListener())
            jmdnsInstances += jmdns
        } else {
            bindAddresses.forEach { bindAddress ->
                val jmdns = JmDNS.create(bindAddress)
                jmdns.addServiceListener(ChromecastServiceType, ChromecastServiceListener())
                jmdnsInstances += jmdns
            }
        }
    }

    fun devices(): List<DesktopCastDevice> =
        synchronized(lock) {
            devicesById.values.toList()
        }

    override fun close() {
        jmdnsInstances.forEach { jmdns ->
            runCatching { jmdns.close() }
        }
        jmdnsInstances.clear()
        synchronized(lock) {
            devicesById.clear()
        }
    }

    private inner class ChromecastServiceListener : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(lock) {
                devicesById.remove(event.name)
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val device = event.info.toDesktopCastDevice() ?: return
            synchronized(lock) {
                devicesById[device.id] = device
            }
        }
    }
}

private fun ServiceInfo.toDesktopCastDevice(): DesktopCastDevice? {
    val host = inet4Addresses.firstOrNull()?.hostAddress
        ?: hostAddresses.firstOrNull()
        ?: return null
    val serviceName = name?.takeIf { it.isNotBlank() } ?: return null
    val displayName = getPropertyString("fn")?.takeIf { it.isNotBlank() }
        ?: serviceName
    val model = getPropertyString("md")?.takeIf { it.isNotBlank() }
    return DesktopCastDevice(
        id = serviceName,
        displayName = displayName,
        model = model,
        host = host,
        port = port,
    )
}
