package dev.sieve.net

import dev.sieve.dns.DnsMessage
import dev.sieve.dns.IpPackets

/**
 * DNS carried over TCP, which matters because a resolver that fails over to TCP — after a
 * truncated UDP answer, or because the network drops UDP 53 — would otherwise route around the
 * filter entirely.
 *
 * RFC 1035 §4.2.2: the message is prefixed with its length as a two-byte big-endian integer.
 */
object DnsOverTcp {

    private const val LENGTH_PREFIX = 2
    private const val MIN_MESSAGE = 12 // a DNS header

    /**
     * Reads the queried name from the first segment of a TCP DNS stream, or null if this does not
     * look like a complete, well-formed query. Returning null lets the connection proceed: it is
     * better to miss a lookup than to sever a connection we failed to understand.
     */
    fun queriedName(payload: ByteArray): String? {
        if (payload.size < LENGTH_PREFIX + MIN_MESSAGE) return null
        val declared = IpPackets.readUShort(payload, 0)
        if (declared < MIN_MESSAGE) return null
        // A query split across segments is not reassembled; we only inspect what arrived first.
        if (declared > payload.size - LENGTH_PREFIX) return null

        val message = payload.copyOfRange(LENGTH_PREFIX, LENGTH_PREFIX + declared)
        return DnsMessage.readQuestion(message)?.name
    }
}
