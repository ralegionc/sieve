package dev.sieve

import dev.sieve.dns.IpPackets
import dev.sieve.net.DnsOverTcp
import dev.sieve.net.Seq
import dev.sieve.net.TcpFlags
import dev.sieve.net.TcpPackets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The userspace TCP path is the one place a mistake corrupts a stream silently rather than
 * failing loudly, so the wire format and the sequence arithmetic are pinned down here.
 */
class SeqTest {

    @Test
    fun `distance is signed and small differences behave normally`() {
        assertEquals(5, Seq.diff(105, 100))
        assertEquals(-5, Seq.diff(100, 105))
        assertTrue(Seq.geq(100, 100))
        assertTrue(Seq.gt(101, 100))
        assertFalse(Seq.gt(100, 100))
    }

    @Test
    fun `comparison survives the wrap at 2^32`() {
        val beforeWrap = 0xFFFFFFF0L
        val afterWrap = Seq.add(beforeWrap, 32) // wraps to 16

        assertEquals(16L, afterWrap)
        assertEquals(32, Seq.diff(afterWrap, beforeWrap))
        assertTrue("a sequence past the wrap is still later", Seq.gt(afterWrap, beforeWrap))
        assertFalse(Seq.gt(beforeWrap, afterWrap))
    }

    @Test
    fun `addition stays inside 32 bits`() {
        assertEquals(0L, Seq.add(0xFFFFFFFFL, 1))
        assertEquals(0xFFFFFFFFL, Seq.add(0xFFFFFFFEL, 1))
    }
}

class TcpPacketTest {

    private val client = ip(10, 111, 222, 1)
    private val server = ip(93, 184, 216, 34)

    @Test
    fun `round-trips a data segment`() {
        val payload = "GET / HTTP/1.1".toByteArray()
        val packet = TcpPackets.build(
            isIpv6 = false,
            source = client,
            destination = server,
            sourcePort = 45123,
            destinationPort = 443,
            sequence = 0x11223344L,
            acknowledgement = 0x55667788L,
            flags = TcpFlags.PSH or TcpFlags.ACK,
            window = 64240,
            payload = payload,
        )

        val header = IpPackets.parseHeader(packet, packet.size)!!
        assertEquals(IpPackets.PROTOCOL_TCP, header.protocol)
        assertArrayEquals(client, header.source)
        assertArrayEquals(server, header.destination)

        val segment = TcpPackets.parse(packet, header)!!
        assertEquals(45123, segment.sourcePort)
        assertEquals(443, segment.destinationPort)
        assertEquals(0x11223344L, segment.sequence)
        assertEquals(0x55667788L, segment.acknowledgement)
        assertEquals(64240, segment.window)
        assertTrue(segment.isAck)
        assertFalse(segment.isSyn)
        assertEquals(payload.size, segment.payloadLength)
        assertArrayEquals(
            payload,
            packet.copyOfRange(segment.payloadOffset, segment.payloadOffset + segment.payloadLength),
        )
    }

    @Test
    fun `carries the MSS option on a SYN and reads it back`() {
        val packet = TcpPackets.build(
            isIpv6 = false,
            source = server,
            destination = client,
            sourcePort = 443,
            destinationPort = 45123,
            sequence = 1000,
            acknowledgement = 2000,
            flags = TcpFlags.SYN or TcpFlags.ACK,
            window = 65535,
            maxSegmentSize = 1400,
        )

        val header = IpPackets.parseHeader(packet, packet.size)!!
        val segment = TcpPackets.parse(packet, header)!!

        assertTrue(segment.isSyn)
        assertTrue(segment.isAck)
        assertEquals(1400, segment.maxSegmentSize)
        assertEquals(0, segment.payloadLength)
    }

    @Test
    fun `checksums verify for IPv4 and IPv6`() {
        // An odd payload length exercises the trailing-byte pad in the checksum.
        val payload = ByteArray(37) { it.toByte() }

        val v4 = TcpPackets.build(
            isIpv6 = false,
            source = client,
            destination = server,
            sourcePort = 1234,
            destinationPort = 80,
            sequence = 7,
            acknowledgement = 9,
            flags = TcpFlags.ACK,
            window = 100,
            payload = payload,
        )
        assertEquals("IPv4 header checksum", 0xFFFF, onesComplement(v4, 0, 20))
        assertEquals("TCP checksum over IPv4", 0xFFFF, tcpChecksum(v4))

        val v6Client = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
        val v6Server = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
        val v6 = TcpPackets.build(
            isIpv6 = true,
            source = v6Client,
            destination = v6Server,
            sourcePort = 1234,
            destinationPort = 80,
            sequence = 7,
            acknowledgement = 9,
            flags = TcpFlags.ACK,
            window = 100,
            payload = payload,
        )
        assertEquals("TCP checksum over IPv6", 0xFFFF, tcpChecksum(v6))

        val header = IpPackets.parseHeader(v6, v6.size)!!
        assertTrue(header.isIpv6)
        assertEquals(payload.size, TcpPackets.parse(v6, header)!!.payloadLength)
    }

    @Test
    fun `sends only part of a payload when asked`() {
        val payload = ByteArray(100) { it.toByte() }
        val packet = TcpPackets.build(
            isIpv6 = false,
            source = client,
            destination = server,
            sourcePort = 1,
            destinationPort = 2,
            sequence = 0,
            acknowledgement = 0,
            flags = TcpFlags.ACK,
            window = 1,
            payload = payload,
            payloadOffset = 40,
            payloadLength = 25,
        )
        val header = IpPackets.parseHeader(packet, packet.size)!!
        val segment = TcpPackets.parse(packet, header)!!

        assertEquals(25, segment.payloadLength)
        assertArrayEquals(
            payload.copyOfRange(40, 65),
            packet.copyOfRange(segment.payloadOffset, segment.payloadOffset + 25),
        )
    }

    @Test
    fun `rejects a segment whose data offset is impossible`() {
        val packet = TcpPackets.build(
            isIpv6 = false,
            source = client,
            destination = server,
            sourcePort = 1,
            destinationPort = 2,
            sequence = 0,
            acknowledgement = 0,
            flags = TcpFlags.ACK,
            window = 1,
        )
        val header = IpPackets.parseHeader(packet, packet.size)!!
        // Claim a 60-byte TCP header in a packet that only has 20 bytes of it.
        packet[header.payloadOffset + 12] = (15 shl 4).toByte()
        assertNull(TcpPackets.parse(packet, header))

        // A data offset below the 20-byte minimum is equally bogus.
        packet[header.payloadOffset + 12] = (3 shl 4).toByte()
        assertNull(TcpPackets.parse(packet, header))
    }

    @Test
    fun `ignores option padding and unknown options when looking for MSS`() {
        val packet = TcpPackets.build(
            isIpv6 = false,
            source = client,
            destination = server,
            sourcePort = 1,
            destinationPort = 2,
            sequence = 0,
            acknowledgement = 0,
            flags = TcpFlags.SYN,
            window = 1,
            maxSegmentSize = 1460,
        )
        val header = IpPackets.parseHeader(packet, packet.size)!!
        val options = header.payloadOffset + 20

        // Replace the MSS option with two NOPs followed by a window-scale option: no MSS present.
        packet[options] = 1      // NOP
        packet[options + 1] = 1  // NOP
        packet[options + 2] = 3  // window scale
        packet[options + 3] = 2  // length 2, so it ends here
        assertNull(TcpPackets.parse(packet, header)!!.maxSegmentSize)
    }

    // ------------------------------------------------------------ helpers

    private fun ip(vararg parts: Int) = ByteArray(parts.size) { parts[it].toByte() }

    private fun onesComplement(data: ByteArray, offset: Int, length: Int, seed: Long = 0): Int {
        var total = seed
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) total += (data[i].toInt() and 0xFF) shl 8
        while ((total shr 16) != 0L) total = (total and 0xFFFF) + (total shr 16)
        return total.toInt()
    }

    /** Recomputes the TCP checksum including the pseudo-header; a correct one sums to 0xFFFF. */
    private fun tcpChecksum(packet: ByteArray): Int {
        val header = IpPackets.parseHeader(packet, packet.size)!!
        var seed = 0L
        val addresses = header.source + header.destination
        var i = 0
        while (i + 1 < addresses.size) {
            seed += ((addresses[i].toInt() and 0xFF) shl 8) or (addresses[i + 1].toInt() and 0xFF)
            i += 2
        }
        seed += IpPackets.PROTOCOL_TCP.toLong()
        seed += header.payloadLength.toLong()
        return onesComplement(packet, header.payloadOffset, header.payloadLength, seed)
    }
}

class DnsOverTcpTest {

    /** A length-prefixed query, as RFC 1035 §4.2.2 requires on a TCP connection. */
    private fun query(name: String, declaredLength: Int? = null): ByteArray {
        val labels = name.split('.')
        val message = ByteArray(12 + labels.sumOf { it.length + 1 } + 1 + 4)
        IpPackets.writeUShort(message, 0, 0x4242)
        IpPackets.writeUShort(message, 2, 0x0100)
        IpPackets.writeUShort(message, 4, 1)
        var offset = 12
        for (label in labels) {
            message[offset++] = label.length.toByte()
            for (character in label) message[offset++] = character.code.toByte()
        }
        message[offset++] = 0
        IpPackets.writeUShort(message, offset, 1)
        IpPackets.writeUShort(message, offset + 2, 1)

        val framed = ByteArray(2 + message.size)
        IpPackets.writeUShort(framed, 0, declaredLength ?: message.size)
        message.copyInto(framed, 2)
        return framed
    }

    @Test
    fun `reads the name from a length-prefixed query`() {
        assertEquals("tracker.example.com", DnsOverTcp.queriedName(query("tracker.example.com")))
    }

    @Test
    fun `lets anything it cannot parse through`() {
        assertNull("too short", DnsOverTcp.queriedName(ByteArray(4)))
        assertNull("length prefix exceeds what arrived", DnsOverTcp.queriedName(query("a.com", 900)))
        assertNull("length prefix below a DNS header", DnsOverTcp.queriedName(query("a.com", 4)))
        assertNull("not DNS at all", DnsOverTcp.queriedName("GET / HTTP/1.1\r\n\r\n".toByteArray()))
    }
}
