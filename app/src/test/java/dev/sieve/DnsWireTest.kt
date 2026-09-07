package dev.sieve

import dev.sieve.dns.DnsMessage
import dev.sieve.dns.IpPackets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the wire-format code against bytes it will actually see. Everything here runs on the
 * JVM — none of it touches Android — which is the whole reason the parsing lives in plain Kotlin.
 */
class DnsWireTest {

    // ------------------------------------------------------------ DNS messages

    @Test
    fun `reads the question name from a query`() {
        val query = dnsQuery("ads.example.com")
        val question = DnsMessage.readQuestion(query)

        assertNotNull(question)
        assertEquals("ads.example.com", question!!.name)
        assertEquals(query.size, question.endOffset)
    }

    @Test
    fun `lowercases the question name`() {
        val question = DnsMessage.readQuestion(dnsQuery("ADS.Example.COM"))
        assertEquals("ads.example.com", question!!.name)
    }

    @Test
    fun `rejects a response, a truncated name and an empty question`() {
        val response = dnsQuery("example.com").also { it[2] = 0x81.toByte() }
        assertNull(DnsMessage.readQuestion(response))

        val truncated = dnsQuery("example.com").copyOfRange(0, 16)
        assertNull(DnsMessage.readQuestion(truncated))

        assertNull(DnsMessage.readQuestion(ByteArray(12)))
    }

    @Test
    fun `rejects a compression pointer in the question`() {
        val poisoned = dnsQuery("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.readQuestion(poisoned))
    }

    @Test
    fun `name error echoes the question and clears every other section`() {
        val query = dnsQuery("tracker.example.com")
        val question = DnsMessage.readQuestion(query)!!
        val response = DnsMessage.buildNameError(query, question)

        // Same transaction id, so the client matches it to its outstanding query.
        assertEquals(IpPackets.readUShort(query, 0), IpPackets.readUShort(response, 0))

        val flags = IpPackets.readUShort(response, 2)
        assertEquals("QR must be set", 0x8000, flags and 0x8000)
        assertEquals("RCODE must be NXDOMAIN", 3, flags and 0x000F)
        assertEquals("RA should be set", 0x0080, flags and 0x0080)

        assertEquals(1, IpPackets.readUShort(response, 4))  // QDCOUNT
        assertEquals(0, IpPackets.readUShort(response, 6))  // ANCOUNT
        assertEquals(0, IpPackets.readUShort(response, 8))  // NSCOUNT
        assertEquals(0, IpPackets.readUShort(response, 10)) // ARCOUNT

        // The question section survives byte for byte.
        assertArrayEquals(
            query.copyOfRange(12, question.endOffset),
            response.copyOfRange(12, response.size),
        )
    }

    @Test
    fun `name error drops a trailing EDNS OPT record`() {
        val query = dnsQuery("example.com", additionalCount = 1) + ednsOptRecord()
        val question = DnsMessage.readQuestion(query)!!
        val response = DnsMessage.buildNameError(query, question)

        assertEquals(question.endOffset, response.size)
        assertEquals(0, IpPackets.readUShort(response, 10))
    }

    // ------------------------------------------------------------ IP framing

    @Test
    fun `parses an IPv4 UDP datagram`() {
        val payload = dnsQuery("example.com")
        val packet = ipv4UdpPacket(
            source = ip(10, 111, 222, 1),
            destination = ip(10, 111, 222, 2),
            sourcePort = 54321,
            destinationPort = 53,
            payload = payload,
        )

        val parsed = IpPackets.parseUdp(packet, packet.size)!!
        assertEquals(false, parsed.isIpv6)
        assertEquals(54321, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(ip(10, 111, 222, 2), parsed.destinationAddress)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `ignores non-UDP and fragmented IPv4 packets`() {
        val tcp = ipv4UdpPacket(
            ip(10, 0, 0, 1), ip(10, 0, 0, 2), 1, 53, ByteArray(4),
        ).also { it[9] = 6 } // TCP
        assertNull(IpPackets.parseUdp(tcp, tcp.size))

        val fragment = ipv4UdpPacket(
            ip(10, 0, 0, 1), ip(10, 0, 0, 2), 1, 53, ByteArray(4),
        ).also { IpPackets.writeUShort(it, 6, 0x0001) } // non-zero fragment offset
        assertNull(IpPackets.parseUdp(fragment, fragment.size))
    }

    @Test
    fun `reply is addressed back to the sender with valid checksums`() {
        val requestPacket = ipv4UdpPacket(
            source = ip(10, 111, 222, 1),
            destination = ip(10, 111, 222, 2),
            sourcePort = 40000,
            destinationPort = 53,
            payload = dnsQuery("example.com"),
        )
        val request = IpPackets.parseUdp(requestPacket, requestPacket.size)!!
        val responsePayload = ByteArray(31) { it.toByte() } // odd length exercises the pad path
        val reply = IpPackets.buildUdpReply(request, responsePayload)

        val parsedReply = IpPackets.parseUdp(reply, reply.size)!!
        assertArrayEquals(ip(10, 111, 222, 2), parsedReply.sourceAddress)
        assertArrayEquals(ip(10, 111, 222, 1), parsedReply.destinationAddress)
        assertEquals(53, parsedReply.sourcePort)
        assertEquals(40000, parsedReply.destinationPort)
        assertArrayEquals(responsePayload, parsedReply.payload)

        // A correct header sums to 0xFFFF in ones-complement arithmetic.
        assertEquals(0xFFFF, onesComplementSum(reply, 0, 20))
        assertEquals(0xFFFF, udpSumWithPseudoHeader(reply))
    }

    @Test
    fun `builds an IPv6 reply that parses back`() {
        val source = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
        val destination = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
        val payload = dnsQuery("example.com")
        val packet = ipv6UdpPacket(source, destination, 40000, 53, payload)

        val request = IpPackets.parseUdp(packet, packet.size)!!
        assertTrue(request.isIpv6)
        assertArrayEquals(payload, request.payload)

        val reply = IpPackets.buildUdpReply(request, byteArrayOf(1, 2, 3))
        val parsedReply = IpPackets.parseUdp(reply, reply.size)!!
        assertArrayEquals(destination, parsedReply.sourceAddress)
        assertArrayEquals(source, parsedReply.destinationAddress)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsedReply.payload)
    }

    // ------------------------------------------------------------ helpers

    /** Builds an address from ints, since values above 127 do not fit a Kotlin Byte literal. */
    private fun ip(vararg parts: Int) = ByteArray(parts.size) { parts[it].toByte() }

    /** A minimal single-question A/IN query, the shape the system resolver emits. */
    private fun dnsQuery(name: String, additionalCount: Int = 0): ByteArray {
        val labels = name.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val query = ByteArray(size)

        IpPackets.writeUShort(query, 0, 0x1234) // transaction id
        IpPackets.writeUShort(query, 2, 0x0100) // standard query, recursion desired
        IpPackets.writeUShort(query, 4, 1)      // QDCOUNT
        IpPackets.writeUShort(query, 10, additionalCount)

        var offset = 12
        for (label in labels) {
            query[offset++] = label.length.toByte()
            for (character in label) query[offset++] = character.code.toByte()
        }
        query[offset++] = 0
        IpPackets.writeUShort(query, offset, 1)     // QTYPE = A
        IpPackets.writeUShort(query, offset + 2, 1) // QCLASS = IN
        return query
    }

    /** Root-name OPT record, as a resolver advertising EDNS0 would append. */
    private fun ednsOptRecord(): ByteArray = byteArrayOf(
        0,                    // root name
        0, 41,                // TYPE = OPT
        0x10, 0x00,           // UDP payload size 4096
        0, 0, 0, 0,           // extended rcode and flags
        0, 0,                 // RDLENGTH
    )

    private fun ipv4UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(28 + payload.size)
        packet[0] = 0x45
        IpPackets.writeUShort(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        IpPackets.writeUShort(packet, 20, sourcePort)
        IpPackets.writeUShort(packet, 22, destinationPort)
        IpPackets.writeUShort(packet, 24, 8 + payload.size)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun ipv6UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(48 + payload.size)
        packet[0] = 0x60
        IpPackets.writeUShort(packet, 4, 8 + payload.size)
        packet[6] = 17
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        IpPackets.writeUShort(packet, 40, sourcePort)
        IpPackets.writeUShort(packet, 42, destinationPort)
        IpPackets.writeUShort(packet, 44, 8 + payload.size)
        payload.copyInto(packet, 48)
        return packet
    }

    private fun onesComplementSum(data: ByteArray, offset: Int, length: Int, seed: Long = 0): Int {
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

    /** Recomputes the UDP checksum of an IPv4 packet, pseudo-header included. */
    private fun udpSumWithPseudoHeader(packet: ByteArray): Int {
        val udpLength = IpPackets.readUShort(packet, 24)
        var seed = 0L
        for (i in 12 until 20 step 2) {
            seed += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        seed += 17L
        seed += udpLength.toLong()
        return onesComplementSum(packet, 20, udpLength, seed)
    }
}
