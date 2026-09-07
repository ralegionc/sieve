package dev.sieve.net

import android.util.Log
import dev.sieve.dns.IpPackets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.ArrayDeque

/**
 * Terminates TCP in userspace and relays each connection to a protected socket.
 *
 * The client's stack thinks it is talking to the real server; in fact it completes a handshake
 * with us, and we run a separate ordinary socket to the destination. This is what makes full
 * routing possible, and therefore what makes Android's "Block connections without VPN" usable.
 *
 * **Why this is not a whole TCP stack.** The link between the app and this code is the tun
 * interface: a local, lossless, in-order queue. Nothing we write there can be dropped in transit,
 * and nothing the app writes can reach us out of order. That removes the two hardest jobs a real
 * TCP has — retransmission and congestion control — and leaves sequence accounting, flow control
 * and the connection state machine, which are all tractable. Loss on the *real* network is handled
 * by the kernel socket on the other side, which is a fully-fledged TCP.
 *
 * Duplicates are still possible: if we are slow to acknowledge, the client's own timer fires and
 * it retransmits. Segments at or below [Connection.receiveNext] are therefore dropped and simply
 * re-acknowledged.
 */
class TcpForwarder(
    private val selector: Selector,
    private val sink: PacketSink,
    private val protect: (java.net.Socket) -> Boolean,
    private val onBlockedDns: (String) -> Boolean,
) {

    private enum class State { CONNECTING, HANDSHAKING, ESTABLISHED, CLIENT_CLOSED, CLOSING }

    private class Connection(
        val key: FlowKey,
        val channel: SocketChannel,
        val isIpv6: Boolean,
        val clientAddress: ByteArray,
        val clientPort: Int,
        val serverAddress: ByteArray,
        val serverPort: Int,
    ) {
        var state = State.CONNECTING

        /** Next sequence number we will put on the wire towards the client. */
        var sendNext: Long = 0

        /** Oldest byte the client has not acknowledged. */
        var sendUnacked: Long = 0

        /** Next sequence number we expect from the client, i.e. what we acknowledge. */
        var receiveNext: Long = 0

        var clientWindow: Int = DEFAULT_WINDOW
        var mss: Int = DEFAULT_MSS
        var lastActivity: Long = System.currentTimeMillis()

        /** Client bytes not yet accepted by the upstream socket. */
        val pendingUpstream: ArrayDeque<ByteBuffer> = ArrayDeque()
        var pendingBytes: Int = 0

        var clientFinSeen = false
        var upstreamClosed = false
        var readsPaused = false

        /** Set on the first data segment of a port-53 connection, once the name is known. */
        var dnsInspected = false

        val inFlight: Int get() = Seq.diff(sendNext, sendUnacked)
    }

    private val connections = HashMap<FlowKey, Connection>()
    private val random = SecureRandom()

    val size: Int get() = connections.size

    // ---------------------------------------------------------------- from the tun

    fun handle(raw: ByteArray, header: IpPackets.Header) {
        val segment = TcpPackets.parse(raw, header) ?: return
        val key = FlowKey(
            protocol = IpPackets.PROTOCOL_TCP,
            sourceAddress = header.source.toList(),
            sourcePort = segment.sourcePort,
            destinationAddress = header.destination.toList(),
            destinationPort = segment.destinationPort,
        )

        val existing = connections[key]
        when {
            existing == null && segment.isSyn && !segment.isAck -> open(key, header, segment)
            existing == null -> sendReset(header, segment)
            else -> process(existing, raw, segment)
        }
    }

    private fun open(key: FlowKey, header: IpPackets.Header, segment: TcpSegment) {
        if (connections.size >= MAX_CONNECTIONS) {
            sendReset(header, segment)
            return
        }

        var connectedImmediately = false
        val channel = runCatching {
            SocketChannel.open().apply {
                configureBlocking(false)
                if (!protect(socket())) {
                    close()
                    error("socket could not be protected")
                }
                connectedImmediately = connect(
                    InetSocketAddress(
                        InetAddress.getByAddress(header.destination),
                        segment.destinationPort,
                    ),
                )
            }
        }.getOrElse {
            Log.v(TAG, "Connect failed for ${segment.destinationPort}", it)
            sendReset(header, segment)
            return
        }

        val connection = Connection(
            key = key,
            channel = channel,
            isIpv6 = header.isIpv6,
            clientAddress = header.source,
            clientPort = segment.sourcePort,
            serverAddress = header.destination,
            serverPort = segment.destinationPort,
        ).apply {
            receiveNext = Seq.add(segment.sequence, 1) // the SYN itself consumes one
            sendNext = random.nextInt().toLong() and Seq.MASK
            sendUnacked = sendNext
            clientWindow = segment.window
            // Never offer the peer more than our own framing can carry.
            mss = minOf(segment.maxSegmentSize ?: DEFAULT_MSS, tunnelMss(header.isIpv6))
        }

        runCatching {
            channel.register(selector, SelectionKey.OP_CONNECT, Attachment(key, this))
        }.onFailure {
            runCatching { channel.close() }
            sendReset(header, segment)
            return
        }

        connections[key] = connection

        // A loopback or already-resolved destination can connect within the call itself, in which
        // case OP_CONNECT never fires and the handshake would stall waiting for it.
        if (connectedImmediately) onConnectable(key)
    }

    private fun process(connection: Connection, raw: ByteArray, segment: TcpSegment) {
        connection.lastActivity = System.currentTimeMillis()

        if (segment.isRst) {
            close(connection)
            return
        }

        if (segment.isAck) {
            // Only move the window forward; a delayed duplicate must not rewind it.
            if (Seq.gt(segment.acknowledgement, connection.sendUnacked) &&
                Seq.geq(connection.sendNext, segment.acknowledgement)
            ) {
                connection.sendUnacked = segment.acknowledgement
            }
            connection.clientWindow = segment.window
            if (connection.state == State.HANDSHAKING) connection.state = State.ESTABLISHED
            resumeReadsIfWindowOpened(connection)
        }

        if (segment.isSyn) return // a retransmitted SYN; the handshake is already in flight

        if (segment.payloadLength > 0) {
            acceptClientData(connection, raw, segment)
        }

        if (segment.isFin && segment.sequence == connection.receiveNext) {
            connection.receiveNext = Seq.add(connection.receiveNext, 1)
            connection.clientFinSeen = true
            sendAck(connection)
            flushUpstream(connection)
            if (connection.pendingUpstream.isEmpty()) shutdownUpstreamOutput(connection)
            if (connection.upstreamClosed) close(connection)
        }

        if (connection.state == State.CLOSING && connection.pendingUpstream.isEmpty()) {
            close(connection)
        }
    }

    private fun acceptClientData(connection: Connection, raw: ByteArray, segment: TcpSegment) {
        // A retransmission of data we already took. Re-acknowledge so the client moves on.
        if (!Seq.geq(segment.sequence, connection.receiveNext)) {
            sendAck(connection)
            return
        }
        // A gap: we do not buffer out-of-order data. Re-acknowledging what we have triggers
        // the client's fast retransmit, which over a lossless tun arrives immediately.
        if (segment.sequence != connection.receiveNext) {
            sendAck(connection)
            return
        }

        val payload = raw.copyOfRange(
            segment.payloadOffset,
            segment.payloadOffset + segment.payloadLength,
        )

        if (connection.serverPort == DNS_PORT && !connection.dnsInspected) {
            connection.dnsInspected = true
            if (refuseDnsOverTcp(payload)) {
                sendResetTo(connection)
                close(connection)
                return
            }
        }

        connection.receiveNext = Seq.add(connection.receiveNext, payload.size.toLong())
        connection.pendingUpstream.addLast(ByteBuffer.wrap(payload))
        connection.pendingBytes += payload.size

        flushUpstream(connection)
        sendAck(connection)
    }

    private fun refuseDnsOverTcp(payload: ByteArray): Boolean =
        DnsOverTcp.queriedName(payload)?.let(onBlockedDns) == true

    // ---------------------------------------------------------------- from the selector

    fun onConnectable(key: FlowKey) {
        val connection = connections[key] ?: return
        val connected = runCatching { connection.channel.finishConnect() }.getOrElse {
            Log.v(TAG, "finishConnect failed", it)
            sendResetTo(connection)
            close(connection)
            return
        }
        if (!connected) return

        connection.state = State.HANDSHAKING
        send(
            connection,
            flags = TcpFlags.SYN or TcpFlags.ACK,
            maxSegmentSize = connection.mss,
        )
        connection.sendNext = Seq.add(connection.sendNext, 1) // our SYN consumes one
        interest(connection, SelectionKey.OP_READ)
    }

    fun onReadable(key: FlowKey) {
        val connection = connections[key] ?: return
        val window = availableWindow(connection)
        if (window <= 0) {
            pauseReads(connection)
            return
        }

        val buffer = ByteBuffer.allocate(minOf(window, READ_CHUNK))
        val read = runCatching { connection.channel.read(buffer) }.getOrElse {
            Log.v(TAG, "Upstream read failed", it)
            sendResetTo(connection)
            close(connection)
            return
        }

        if (read < 0) {
            connection.upstreamClosed = true
            send(connection, flags = TcpFlags.FIN or TcpFlags.ACK)
            connection.sendNext = Seq.add(connection.sendNext, 1) // our FIN consumes one
            connection.state = if (connection.clientFinSeen) State.CLOSING else State.CLIENT_CLOSED
            interest(connection, 0)
            return
        }
        if (read == 0) return

        buffer.flip()
        val data = ByteArray(read).also { buffer.get(it) }
        connection.lastActivity = System.currentTimeMillis()

        // Segment to the MSS the client told us it can accept.
        var offset = 0
        while (offset < data.size) {
            val length = minOf(connection.mss, data.size - offset)
            send(
                connection,
                flags = TcpFlags.PSH or TcpFlags.ACK,
                payload = data,
                payloadOffset = offset,
                payloadLength = length,
            )
            connection.sendNext = Seq.add(connection.sendNext, length.toLong())
            offset += length
        }

        if (availableWindow(connection) <= 0) pauseReads(connection)
    }

    fun onWritable(key: FlowKey) {
        val connection = connections[key] ?: return
        flushUpstream(connection)
    }

    fun onError(key: FlowKey) {
        connections[key]?.let {
            sendResetTo(it)
            close(it)
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun flushUpstream(connection: Connection) {
        while (connection.pendingUpstream.isNotEmpty()) {
            val buffer = connection.pendingUpstream.first()
            val written = runCatching { connection.channel.write(buffer) }.getOrElse {
                sendResetTo(connection)
                close(connection)
                return
            }
            connection.pendingBytes -= written
            if (buffer.hasRemaining()) {
                // Upstream is full; finish when the selector says it can take more.
                interest(connection, currentInterest(connection) or SelectionKey.OP_WRITE)
                return
            }
            connection.pendingUpstream.removeFirst()
        }

        interest(connection, currentInterest(connection) and SelectionKey.OP_WRITE.inv())
        if (connection.clientFinSeen) shutdownUpstreamOutput(connection)
    }

    private fun shutdownUpstreamOutput(connection: Connection) {
        runCatching { connection.channel.shutdownOutput() }
            .onFailure { Log.v(TAG, "shutdownOutput failed", it) }
    }

    /** How many more bytes the client has said it can receive. */
    private fun availableWindow(connection: Connection): Int =
        (connection.clientWindow - connection.inFlight).coerceIn(0, MAX_SEND_BURST)

    private fun pauseReads(connection: Connection) {
        if (connection.readsPaused) return
        connection.readsPaused = true
        interest(connection, currentInterest(connection) and SelectionKey.OP_READ.inv())
    }

    private fun resumeReadsIfWindowOpened(connection: Connection) {
        if (!connection.readsPaused || availableWindow(connection) <= 0) return
        if (connection.upstreamClosed) return
        connection.readsPaused = false
        interest(connection, currentInterest(connection) or SelectionKey.OP_READ)
    }

    private fun currentInterest(connection: Connection): Int =
        connection.channel.keyFor(selector)?.takeIf { it.isValid }?.interestOps() ?: 0

    private fun interest(connection: Connection, operations: Int) {
        val key = connection.channel.keyFor(selector) ?: return
        if (!key.isValid) return
        runCatching { key.interestOps(operations) }
    }

    private fun send(
        connection: Connection,
        flags: Int,
        payload: ByteArray = EMPTY,
        payloadOffset: Int = 0,
        payloadLength: Int = 0,
        maxSegmentSize: Int? = null,
    ) {
        sink.send(
            TcpPackets.build(
                isIpv6 = connection.isIpv6,
                source = connection.serverAddress,
                destination = connection.clientAddress,
                sourcePort = connection.serverPort,
                destinationPort = connection.clientPort,
                sequence = connection.sendNext,
                acknowledgement = connection.receiveNext,
                flags = flags,
                window = advertisedWindow(connection),
                payload = payload,
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
                maxSegmentSize = maxSegmentSize,
            ),
        )
    }

    private fun sendAck(connection: Connection) = send(connection, TcpFlags.ACK)

    /** Shrinks as unwritten client data piles up, so a slow upstream throttles the app. */
    private fun advertisedWindow(connection: Connection): Int =
        (MAX_PENDING_UPSTREAM - connection.pendingBytes).coerceIn(0, DEFAULT_WINDOW)

    /** Refuses a connection we have no state for, or cannot service. */
    private fun sendReset(header: IpPackets.Header, segment: TcpSegment) {
        val sequence = if (segment.isAck) segment.acknowledgement else 0L
        val acknowledgement = Seq.add(segment.sequence, segment.payloadLength.toLong() + 1)
        sink.send(
            TcpPackets.build(
                isIpv6 = header.isIpv6,
                source = header.destination,
                destination = header.source,
                sourcePort = segment.destinationPort,
                destinationPort = segment.sourcePort,
                sequence = sequence,
                acknowledgement = acknowledgement,
                flags = TcpFlags.RST or TcpFlags.ACK,
                window = 0,
            ),
        )
    }

    private fun sendResetTo(connection: Connection) =
        send(connection, TcpFlags.RST or TcpFlags.ACK)

    private fun close(connection: Connection) {
        connections.remove(connection.key)
        runCatching { connection.channel.keyFor(selector)?.cancel() }
        runCatching { connection.channel.close() }
    }

    fun sweep(now: Long) {
        connections.values
            .filter { now - it.lastActivity > IDLE_TIMEOUT_MS }
            .forEach { close(it) }
    }

    fun closeAll() {
        connections.values.forEach {
            runCatching { it.channel.keyFor(selector)?.cancel() }
            runCatching { it.channel.close() }
        }
        connections.clear()
    }

    private fun tunnelMss(isIpv6: Boolean): Int =
        TUN_MTU - (if (isIpv6) IpPackets.IPV6_HEADER else IpPackets.IPV4_MIN_HEADER) - TCP_HEADER

    private companion object {
        const val TAG = "Sieve/Tcp"
        const val DNS_PORT = 53
        const val TUN_MTU = 1500
        const val TCP_HEADER = 20
        const val DEFAULT_MSS = 1460
        const val DEFAULT_WINDOW = 65_535
        const val MAX_SEND_BURST = 65_535
        const val MAX_PENDING_UPSTREAM = 256 * 1024
        const val READ_CHUNK = 32 * 1024
        const val MAX_CONNECTIONS = 512
        const val IDLE_TIMEOUT_MS = 300_000L
        val EMPTY = ByteArray(0)
    }
}
