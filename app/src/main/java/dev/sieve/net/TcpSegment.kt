package dev.sieve.net

import dev.sieve.dns.IpPackets

/**
 * A parsed TCP segment. Sequence numbers are 32-bit unsigned, held in a [Long] so the arithmetic
 * in [Seq] can wrap explicitly rather than by accident.
 */
class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Long,
    val acknowledgement: Long,
    val flags: Int,
    val window: Int,
    val maxSegmentSize: Int?,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    val isSyn: Boolean get() = flags and TcpFlags.SYN != 0
    val isAck: Boolean get() = flags and TcpFlags.ACK != 0
    val isFin: Boolean get() = flags and TcpFlags.FIN != 0
    val isRst: Boolean get() = flags and TcpFlags.RST != 0
}

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/**
 * 32-bit sequence arithmetic. Comparisons have to be modular: a connection that has been running
 * long enough wraps past 2^32, and a naive `<` would decide the whole stream arrived out of order.
 */
object Seq {
    const val MASK = 0xFFFFFFFFL

    fun add(value: Long, delta: Long): Long = (value + delta) and MASK

    /**
     * Signed distance from [b] to [a]. Masking to 32 bits and reinterpreting as a signed Int is
     * what makes this wrap correctly: a difference of -5 masks to 0xFFFFFFFB, which is -5 again
     * once truncated. Valid for gaps under 2^31, which no real connection exceeds in flight.
     */
    fun diff(a: Long, b: Long): Int = ((a - b) and MASK).toInt()

    /** True when [a] is at or after [b], across the wrap point. */
    fun geq(a: Long, b: Long): Boolean = diff(a, b) >= 0

    fun gt(a: Long, b: Long): Boolean = diff(a, b) > 0
}

object TcpPackets {

    private const val MIN_HEADER = 20
    private const val OPTION_END = 0
    private const val OPTION_NOP = 1
    private const val OPTION_MSS = 2

    fun parse(raw: ByteArray, header: IpPackets.Header): TcpSegment? {
        val start = header.payloadOffset
        if (header.payloadLength < MIN_HEADER) return null

        val dataOffset = ((raw[start + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < MIN_HEADER || dataOffset > header.payloadLength) return null

        val payloadOffset = start + dataOffset
        val payloadLength = header.payloadLength - dataOffset
        if (payloadOffset + payloadLength > raw.size) return null

        return TcpSegment(
            sourcePort = IpPackets.readUShort(raw, start),
            destinationPort = IpPackets.readUShort(raw, start + 2),
            sequence = IpPackets.readUInt(raw, start + 4),
            acknowledgement = IpPackets.readUInt(raw, start + 8),
            flags = raw[start + 13].toInt() and 0x3F,
            window = IpPackets.readUShort(raw, start + 14),
            maxSegmentSize = readMss(raw, start + MIN_HEADER, start + dataOffset),
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
        )
    }

    /** Reads the MSS option so we never hand the peer a segment it would have to fragment. */
    private fun readMss(raw: ByteArray, from: Int, to: Int): Int? {
        var i = from
        while (i < to) {
            when (raw[i].toInt() and 0xFF) {
                OPTION_END -> return null
                OPTION_NOP -> i++
                else -> {
                    if (i + 1 >= to) return null
                    val kind = raw[i].toInt() and 0xFF
                    val length = raw[i + 1].toInt() and 0xFF
                    if (length < 2 || i + length > to) return null
                    if (kind == OPTION_MSS && length == 4) {
                        return IpPackets.readUShort(raw, i + 2)
                    }
                    i += length
                }
            }
        }
        return null
    }

    /**
     * Builds a complete IP packet carrying one TCP segment. [maxSegmentSize] adds the MSS option,
     * which is only meaningful on a SYN.
     */
    fun build(
        isIpv6: Boolean,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        window: Int,
        payload: ByteArray = ByteArray(0),
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        maxSegmentSize: Int? = null,
    ): ByteArray {
        val optionBytes = if (maxSegmentSize != null) 4 else 0
        val headerLength = MIN_HEADER + optionBytes
        val transport = ByteArray(headerLength + payloadLength)

        IpPackets.writeUShort(transport, 0, sourcePort)
        IpPackets.writeUShort(transport, 2, destinationPort)
        IpPackets.writeUInt(transport, 4, sequence and Seq.MASK)
        IpPackets.writeUInt(transport, 8, acknowledgement and Seq.MASK)
        transport[12] = ((headerLength / 4) shl 4).toByte()
        transport[13] = flags.toByte()
        IpPackets.writeUShort(transport, 14, window)
        // Bytes 16-17 are the checksum, filled in below; 18-19 are the urgent pointer, unused.

        if (maxSegmentSize != null) {
            transport[MIN_HEADER] = OPTION_MSS.toByte()
            transport[MIN_HEADER + 1] = 4
            IpPackets.writeUShort(transport, MIN_HEADER + 2, maxSegmentSize)
        }
        if (payloadLength > 0) {
            payload.copyInto(transport, headerLength, payloadOffset, payloadOffset + payloadLength)
        }

        IpPackets.writeTransportChecksum(transport, 16, IpPackets.PROTOCOL_TCP, source, destination)
        return IpPackets.buildPacket(isIpv6, IpPackets.PROTOCOL_TCP, source, destination, transport)
    }
}
