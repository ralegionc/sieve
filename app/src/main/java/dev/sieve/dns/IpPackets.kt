package dev.sieve.dns

/**
 * IPv4/IPv6 header parsing and framing, plus the checksum arithmetic every transport shares.
 *
 * In DNS-only mode Sieve routes a handful of synthetic addresses and sees nothing but UDP. In full
 * mode it routes everything, so this has to hand back TCP as readily as UDP. Neither IPv4
 * fragments nor IPv6 extension headers are reassembled — both are dropped, and neither appears in
 * traffic originated by the local stack.
 */
object IpPackets {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17
    const val PROTOCOL_ICMP = 1
    const val PROTOCOL_ICMPV6 = 58

    const val IPV4_MIN_HEADER = 20
    const val IPV6_HEADER = 40
    const val UDP_HEADER = 8

    private const val DEFAULT_HOP_LIMIT = 64

    /** A parsed IP header, with the transport payload located but not interpreted. */
    class Header(
        val isIpv6: Boolean,
        val protocol: Int,
        val source: ByteArray,
        val destination: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        val addressSize: Int get() = if (isIpv6) 16 else 4
    }

    fun parseHeader(raw: ByteArray, length: Int): Header? {
        if (length < 1) return null
        return when ((raw[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4Header(raw, length)
            6 -> parseIpv6Header(raw, length)
            else -> null
        }
    }

    private fun parseIpv4Header(raw: ByteArray, length: Int): Header? {
        if (length < IPV4_MIN_HEADER) return null
        val headerLength = (raw[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_MIN_HEADER || length < headerLength) return null

        // Fragments are dropped rather than reassembled; the local stack does not emit them.
        val fragmentOffset = ((raw[6].toInt() and 0x1F) shl 8) or (raw[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null

        val totalLength = readUShort(raw, 2).coerceIn(headerLength, length)
        return Header(
            isIpv6 = false,
            protocol = raw[9].toInt() and 0xFF,
            source = raw.copyOfRange(12, 16),
            destination = raw.copyOfRange(16, 20),
            payloadOffset = headerLength,
            payloadLength = totalLength - headerLength,
        )
    }

    private fun parseIpv6Header(raw: ByteArray, length: Int): Header? {
        if (length < IPV6_HEADER) return null
        val declared = readUShort(raw, 4)
        val payloadLength = declared.coerceAtMost(length - IPV6_HEADER)
        return Header(
            isIpv6 = true,
            // An extension-header chain would need a walk; nothing local sends one.
            protocol = raw[6].toInt() and 0xFF,
            source = raw.copyOfRange(8, 24),
            destination = raw.copyOfRange(24, 40),
            payloadOffset = IPV6_HEADER,
            payloadLength = payloadLength,
        )
    }

    /** Wraps an already-checksummed transport segment in an IP header. */
    fun buildPacket(
        isIpv6: Boolean,
        protocol: Int,
        source: ByteArray,
        destination: ByteArray,
        transport: ByteArray,
    ): ByteArray = if (isIpv6) {
        ByteArray(IPV6_HEADER + transport.size).also { packet ->
            packet[0] = 0x60
            writeUShort(packet, 4, transport.size)
            packet[6] = protocol.toByte()
            packet[7] = DEFAULT_HOP_LIMIT.toByte()
            source.copyInto(packet, 8)
            destination.copyInto(packet, 24)
            transport.copyInto(packet, IPV6_HEADER)
        }
    } else {
        ByteArray(IPV4_MIN_HEADER + transport.size).also { packet ->
            packet[0] = 0x45
            writeUShort(packet, 2, packet.size)
            writeUShort(packet, 6, 0x4000) // do not fragment
            packet[8] = DEFAULT_HOP_LIMIT.toByte()
            packet[9] = protocol.toByte()
            source.copyInto(packet, 12)
            destination.copyInto(packet, 16)
            transport.copyInto(packet, IPV4_MIN_HEADER)
            writeUShort(packet, 10, checksum(sum(packet, 0, IPV4_MIN_HEADER)))
        }
    }

    /**
     * Computes the transport checksum over the pseudo-header and [transport], and writes it at
     * [checksumOffset] (6 for UDP, 16 for TCP). The field must be zero on entry.
     */
    fun writeTransportChecksum(
        transport: ByteArray,
        checksumOffset: Int,
        protocol: Int,
        source: ByteArray,
        destination: ByteArray,
    ) {
        writeUShort(transport, checksumOffset, 0)
        var total = sum(source, 0, source.size, 0L)
        total = sum(destination, 0, destination.size, total)
        total += protocol.toLong()
        total += transport.size.toLong()
        total = sum(transport, 0, transport.size, total)
        val result = checksum(total)
        // Zero means "not computed" on the wire for UDP, so it goes out as all ones.
        writeUShort(transport, checksumOffset, if (result == 0 && protocol == PROTOCOL_UDP) 0xFFFF else result)
    }

    private fun sum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Long {
        var total = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) total += (data[i].toInt() and 0xFF) shl 8
        return total
    }

    private fun checksum(sum: Long): Int {
        var folded = sum
        while ((folded shr 16) != 0L) folded = (folded and 0xFFFF) + (folded shr 16)
        return folded.toInt().inv() and 0xFFFF
    }

    fun readUShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeUShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun readUInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    fun writeUInt(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    // ------------------------------------------------------------ UDP

    fun parseUdp(raw: ByteArray, length: Int): UdpDatagram? {
        val header = parseHeader(raw, length) ?: return null
        if (header.protocol != PROTOCOL_UDP) return null
        return parseUdp(raw, header)
    }

    fun parseUdp(raw: ByteArray, header: Header): UdpDatagram? {
        val start = header.payloadOffset
        if (header.payloadLength < UDP_HEADER) return null

        val declared = readUShort(raw, start + 4) - UDP_HEADER
        val available = header.payloadLength - UDP_HEADER
        val payloadLength = declared.coerceIn(0, available)
        val dataStart = start + UDP_HEADER
        if (dataStart + payloadLength > raw.size) return null

        return UdpDatagram(
            isIpv6 = header.isIpv6,
            sourceAddress = header.source,
            destinationAddress = header.destination,
            sourcePort = readUShort(raw, start),
            destinationPort = readUShort(raw, start + 2),
            payload = raw.copyOfRange(dataStart, dataStart + payloadLength),
        )
    }

    /** Frames [payload] as a UDP reply to [request], with addresses and ports swapped. */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray = buildUdp(
        isIpv6 = request.isIpv6,
        source = request.destinationAddress,
        destination = request.sourceAddress,
        sourcePort = request.destinationPort,
        destinationPort = request.sourcePort,
        payload = payload,
    )

    fun buildUdp(
        isIpv6: Boolean,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val transport = ByteArray(UDP_HEADER + payload.size)
        writeUShort(transport, 0, sourcePort)
        writeUShort(transport, 2, destinationPort)
        writeUShort(transport, 4, transport.size)
        payload.copyInto(transport, UDP_HEADER)
        writeTransportChecksum(transport, 6, PROTOCOL_UDP, source, destination)
        return buildPacket(isIpv6, PROTOCOL_UDP, source, destination, transport)
    }
}
