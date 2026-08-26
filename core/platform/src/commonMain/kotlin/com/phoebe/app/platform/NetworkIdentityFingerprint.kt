package com.phoebe.app.platform

/** Stable, non-PII fingerprint from transport + optional subnet/gateway material. */
internal fun networkFingerprint(
    transport: NetworkTransport,
    material: String,
): String {
    val normalized = material.trim().lowercase()
    if (normalized.isBlank()) return transport.name.lowercase()
    return "${transport.name.lowercase()}-${normalized.stableHashHex()}"
}

private fun String.stableHashHex(): String {
    var hash = 0x811c9dc5.toInt()
    for (ch in this) {
        hash = hash xor ch.code
        hash = (hash * 0x01000193)
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}
