package dev.sieve.net

import android.util.Log
import dev.sieve.dns.DnsMessage
import dev.sieve.dns.IpPackets
import dev.sieve.dns.UdpDatagram
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * Proxies UDP flows through protected sockets, filtering anything aimed at port 53 on the way.
 *
 * One channel per flow, keyed by the four-tuple, evicted when idle. DNS flows are short-lived and
 * numerous, so they get a much shorter timeout than everything else — QUIC, in particular, wants
 * its socket to stay open for minutes.
 */
class UdpForwarder(
    private val selector: Selector,
    private val sink: PacketSink,
    private val protect: (java.net.DatagramSocket) -> Boolean,
    private val filter: DnsFilter,
) {

    /** Decides DNS verdicts; supplied by the service so the engine stays testable. */
    fun interface DnsFilter {
        /** Returns the NXDOMAIN response to send back, or null to let the query through. */
        fun refuse(payload: ByteArray): ByteArray?
    }

    private class Flow(
        val channel: DatagramChannel,
        val template: UdpDatagram,
        val isDns: Boolean,
    ) {
        var lastActivity: Long = System.currentTimeMillis()
    }

    private val flows = HashMap<FlowKey, Flow>()

    val size: Int get() = flows.size

    fun handle(raw: ByteArray, header: IpPackets.Header) {
        val datagram = IpPackets.parseUdp(raw, header) ?: return

        if (datagram.destinationPort == DNS_PORT) {
            val refusal = filter.refuse(datagram.payload)
            if (refusal != null) {
                sink.send(IpPackets.buildUdpReply(datagram, refusal))
                return
            }
        }

        val key = FlowKey(
            protocol = IpPackets.PROTOCOL_UDP,
            sourceAddress = datagram.sourceAddress.toList(),
            sourcePort = datagram.sourcePort,
            destinationAddress = datagram.destinationAddress.toList(),
            destinationPort = datagram.destinationPort,
        )

        val flow = flows[key] ?: openFlow(key, datagram) ?: return
        flow.lastActivity = System.currentTimeMillis()

        runCatching {
            flow.channel.write(ByteBuffer.wrap(datagram.payload))
        }.onFailure {
            Log.v(TAG, "UDP write failed", it)
            close(key, flow)
        }
    }

    private fun openFlow(key: FlowKey, datagram: UdpDatagram): Flow? {
        if (flows.size >= MAX_FLOWS) evictOldest()

        return runCatching {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            if (!protect(channel.socket())) {
                channel.close()
                error("socket could not be protected")
            }
            val destination = InetAddress.getByAddress(datagram.destinationAddress)
            channel.connect(InetSocketAddress(destination, datagram.destinationPort))

            val flow = Flow(channel, datagram, datagram.destinationPort == DNS_PORT)
            channel.register(selector, SelectionKey.OP_READ, Attachment(key, this))
            flows[key] = flow
            flow
        }.onFailure { Log.v(TAG, "Could not open UDP flow", it) }.getOrNull()
    }

    /** Called by the engine when a channel this forwarder owns becomes readable. */
    fun onReadable(key: FlowKey) {
        val flow = flows[key] ?: return
        val buffer = ByteBuffer.allocate(MAX_DATAGRAM)

        while (true) {
            buffer.clear()
            val read = runCatching { flow.channel.read(buffer) }.getOrElse {
                close(key, flow)
                return
            }
            if (read <= 0) break

            buffer.flip()
            val payload = ByteArray(read).also { buffer.get(it) }
            flow.lastActivity = System.currentTimeMillis()
            sink.send(IpPackets.buildUdpReply(flow.template, payload))

            // A DNS exchange is one datagram each way; holding the socket open wastes an fd.
            if (flow.isDns) {
                close(key, flow)
                return
            }
        }
    }

    fun onError(key: FlowKey) {
        flows[key]?.let { close(key, it) }
    }

    fun sweep(now: Long) {
        val expired = flows.filter { (_, flow) ->
            val timeout = if (flow.isDns) DNS_TIMEOUT_MS else IDLE_TIMEOUT_MS
            now - flow.lastActivity > timeout
        }
        expired.forEach { (key, flow) -> close(key, flow) }
    }

    private fun evictOldest() {
        flows.minByOrNull { it.value.lastActivity }?.let { (key, flow) -> close(key, flow) }
    }

    private fun close(key: FlowKey, flow: Flow) {
        flows.remove(key)
        runCatching { flow.channel.keyFor(selector)?.cancel() }
        runCatching { flow.channel.close() }
    }

    fun closeAll() {
        flows.values.forEach { flow ->
            runCatching { flow.channel.keyFor(selector)?.cancel() }
            runCatching { flow.channel.close() }
        }
        flows.clear()
    }

    private companion object {
        const val TAG = "Sieve/Udp"
        const val DNS_PORT = 53
        const val MAX_DATAGRAM = 65_535
        const val MAX_FLOWS = 512
        const val DNS_TIMEOUT_MS = 10_000L
        const val IDLE_TIMEOUT_MS = 120_000L
    }
}

/** Identifies one transport flow. Addresses are boxed as lists so they hash by value. */
data class FlowKey(
    val protocol: Int,
    val sourceAddress: List<Byte>,
    val sourcePort: Int,
    val destinationAddress: List<Byte>,
    val destinationPort: Int,
)

/** What a [SelectionKey] carries so the engine can route readiness back to the right owner. */
class Attachment(val flow: FlowKey, val owner: Any)

/** Where finished packets go: the queue draining to the tun file descriptor. */
fun interface PacketSink {
    fun send(packet: ByteArray)
}

/** Builds the NXDOMAIN refusal for a blocked query, or null when the query may proceed. */
fun dnsRefusal(payload: ByteArray, isBlocked: (String) -> Boolean): ByteArray? {
    val question = DnsMessage.readQuestion(payload) ?: return null
    return if (isBlocked(question.name)) DnsMessage.buildNameError(payload, question) else null
}
