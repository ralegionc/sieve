package dev.sieve.dns

/**
 * A UDP datagram lifted off the tun interface, with the IP layer already stripped.
 *
 * Addresses are kept as raw bytes (4 for IPv4, 16 for IPv6) because we only ever need them to
 * build the matching reply and to look up which upstream resolver a query was aimed at.
 */
class UdpDatagram(
    val isIpv6: Boolean,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)
