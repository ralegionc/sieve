package dev.sieve.net

import android.util.Log
import dev.sieve.dns.IpPackets
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Drives full-tunnel forwarding: everything the phone sends arrives here, is proxied through
 * protected sockets, and the replies are framed back.
 *
 * Three threads, deliberately:
 *
 *  - **reader** blocks on the tun file descriptor and parses packets. It never does network I/O.
 *  - **selector** owns every upstream socket. All forwarder state lives on this thread alone, so
 *    the connection tables need no locking; work arriving from the reader is handed over through
 *    [inbound] and a [Selector.wakeup].
 *  - **writer** drains [outbound] to the tun fd, so no other thread can block on a full pipe.
 */
class TunnelEngine(
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    /** Evaluates one looked-up name, records it, and says whether to refuse it. */
    private val isBlocked: (String) -> Boolean,
) {

    private val selector: Selector = Selector.open()
    private val outbound = LinkedBlockingQueue<ByteArray>(OUTBOUND_CAPACITY)
    private val inbound = ConcurrentLinkedQueue<Packet>()

    private val sink = PacketSink { packet ->
        if (!outbound.offer(packet)) Log.w(TAG, "Outbound queue full, dropping a packet")
    }

    private val udp = UdpForwarder(
        selector = selector,
        sink = sink,
        protect = protectDatagram,
        filter = { payload -> dnsRefusal(payload, isBlocked) },
    )

    private val tcp = TcpForwarder(
        selector = selector,
        sink = sink,
        protect = protectSocket,
        onBlockedDns = isBlocked,
    )

    private class Packet(val raw: ByteArray, val length: Int)

    @Volatile
    private var running = false

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var selectorThread: Thread? = null

    fun start(input: FileInputStream, output: FileOutputStream) {
        running = true

        writerThread = thread("sieve-writer") { writeLoop(output) }
        selectorThread = thread("sieve-selector") { selectLoop() }
        readerThread = thread("sieve-reader") { readLoop(input) }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true; start() }

    // ---------------------------------------------------------------- reader

    private fun readLoop(input: FileInputStream) {
        val buffer = ByteArray(MTU)
        try {
            while (running) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length == 0) continue
                // Waking the selector costs a syscall, so only do it when the queue was drained.
                // drainInbound() loops until empty, and wakeup() is sticky, so a packet added
                // while it is already awake is still picked up.
                val wasIdle = inbound.isEmpty()
                inbound.add(Packet(buffer.copyOf(length), length))
                if (wasIdle) selector.wakeup()
            }
        } catch (_: IOException) {
            // Expected when the tunnel is torn down underneath us.
        } finally {
            runCatching { input.close() }
            running = false
            selector.wakeup()
        }
    }

    // ---------------------------------------------------------------- writer

    private fun writeLoop(output: FileOutputStream) {
        try {
            while (true) {
                val packet = outbound.take()
                if (packet.isEmpty()) break // poison pill
                output.write(packet)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
            // Tunnel closed.
        } finally {
            runCatching { output.close() }
        }
    }

    // ---------------------------------------------------------------- selector

    private fun selectLoop() {
        var lastSweep = System.currentTimeMillis()
        try {
            while (running) {
                selector.select(SELECT_TIMEOUT_MS)
                drainInbound()
                dispatchReady()

                val now = System.currentTimeMillis()
                if (now - lastSweep > SWEEP_INTERVAL_MS) {
                    udp.sweep(now)
                    tcp.sweep(now)
                    lastSweep = now
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Selector loop stopped", error)
        } finally {
            udp.closeAll()
            tcp.closeAll()
            runCatching { selector.close() }
        }
    }

    private fun drainInbound() {
        while (true) {
            val packet = inbound.poll() ?: return
            val header = IpPackets.parseHeader(packet.raw, packet.length) ?: continue
            when (header.protocol) {
                IpPackets.PROTOCOL_UDP -> udp.handle(packet.raw, header)
                IpPackets.PROTOCOL_TCP -> tcp.handle(packet.raw, header)
                // ICMP would need a raw socket, which needs root. Ping does not work in full mode.
                else -> Unit
            }
        }
    }

    private fun dispatchReady() {
        val keys = selector.selectedKeys()
        val iterator = keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            iterator.remove()
            val attachment = key.attachment() as? Attachment ?: continue
            if (!key.isValid) {
                notifyError(attachment)
                continue
            }
            runCatching {
                when {
                    key.isConnectable -> (attachment.owner as? TcpForwarder)?.onConnectable(attachment.flow)
                    key.isReadable -> when (val owner = attachment.owner) {
                        is TcpForwarder -> owner.onReadable(attachment.flow)
                        is UdpForwarder -> owner.onReadable(attachment.flow)
                        else -> Unit
                    }
                    else -> Unit
                }
                // Writability is checked separately: a key can be both readable and writable.
                if (key.isValid && key.isWritable) {
                    (attachment.owner as? TcpForwarder)?.onWritable(attachment.flow)
                }
            }.onFailure {
                Log.v(TAG, "Dispatch failed", it)
                notifyError(attachment)
            }
        }
    }

    private fun notifyError(attachment: Attachment) {
        when (val owner = attachment.owner) {
            is TcpForwarder -> owner.onError(attachment.flow)
            is UdpForwarder -> owner.onError(attachment.flow)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun stop() {
        running = false
        readerThread?.interrupt()
        outbound.offer(ByteArray(0))
        selector.wakeup()
        readerThread = null
        writerThread = null
        selectorThread = null
    }

    val connectionCount: Int get() = tcp.size + udp.size

    private companion object {
        const val TAG = "Sieve/Engine"
        const val MTU = 1500
        const val OUTBOUND_CAPACITY = 1024
        const val SELECT_TIMEOUT_MS = 1_000L
        const val SWEEP_INTERVAL_MS = 10_000L
    }
}
