package dev.sieve.dns

import dev.sieve.dns.IpPackets.readUShort
import dev.sieve.dns.IpPackets.writeUShort

/**
 * Just enough of RFC 1035 to read the question off a query and to synthesise a refusal.
 *
 * We never need to parse answers: allowed queries are forwarded verbatim to the upstream resolver
 * and its reply is copied straight back, so the only message Sieve ever constructs is NXDOMAIN.
 */
object DnsMessage {

    private const val HEADER_SIZE = 12
    private const val MAX_NAME_LENGTH = 253
    private const val FLAG_RESPONSE = 0x8000
    private const val RCODE_NAME_ERROR = 3

    /** The single question in a query, plus where it ends so a reply can be truncated there. */
    class Question(val name: String, val endOffset: Int)

    /**
     * Reads the first question of a DNS query. Returns null for anything that is not a
     * well-formed single-question query, which the caller forwards untouched rather than guesses at.
     */
    fun readQuestion(payload: ByteArray): Question? {
        if (payload.size < HEADER_SIZE) return null
        if ((readUShort(payload, 2) and FLAG_RESPONSE) != 0) return null // already a response
        if (readUShort(payload, 4) < 1) return null                     // no question section

        val name = StringBuilder(64)
        var offset = HEADER_SIZE
        while (true) {
            if (offset >= payload.size) return null
            val labelLength = payload[offset].toInt() and 0xFF
            if (labelLength == 0) {
                offset++
                break
            }
            // A compression pointer cannot legally appear in the question of a query.
            if ((labelLength and 0xC0) != 0) return null
            offset++
            if (offset + labelLength > payload.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until labelLength) {
                // Labels are octets, not text. Widening each byte keeps punycode and any
                // non-ASCII bytes byte-for-byte comparable against the blocklist.
                name.append(((payload[offset + i].toInt() and 0xFF).toChar()))
            }
            offset += labelLength
            if (name.length > MAX_NAME_LENGTH) return null
        }

        if (name.isEmpty()) return null              // the root, nothing to match on
        if (offset + 4 > payload.size) return null   // QTYPE + QCLASS must follow
        return Question(name.toString().lowercase(), offset + 4)
    }

    /**
     * Builds an NXDOMAIN response to [query], echoing the question and dropping every other
     * section. NXDOMAIN — rather than an A record pointing at 0.0.0.0 — makes clients fail fast
     * instead of opening a connection that will time out.
     */
    fun buildNameError(query: ByteArray, question: Question): ByteArray {
        val response = query.copyOfRange(0, question.endOffset)

        // Byte 2: QR | Opcode | AA | TC | RD. Set QR, keep the opcode and RD, clear AA and TC.
        response[2] = ((query[2].toInt() or 0x80) and 0xF9).toByte()
        // Byte 3: RA | Z | RCODE. Recursion available, no error class other than name error.
        response[3] = (0x80 or RCODE_NAME_ERROR).toByte()

        writeUShort(response, 4, 1) // QDCOUNT
        writeUShort(response, 6, 0) // ANCOUNT
        writeUShort(response, 8, 0) // NSCOUNT
        writeUShort(response, 10, 0) // ARCOUNT — drops any EDNS OPT record along with it
        return response
    }
}
