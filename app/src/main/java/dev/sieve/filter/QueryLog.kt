package dev.sieve.filter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory record of recent lookups and running counters.
 *
 * Nothing here is written to disk. Browsing history is the most sensitive data this app touches,
 * so the log lives only as long as the process and is capped at [CAPACITY] entries.
 */
object QueryLog {

    private const val CAPACITY = 250

    data class Entry(
        val domain: String,
        val verdict: FilterEngine.Verdict,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _sessionQueries = MutableStateFlow(0L)
    val sessionQueries: StateFlow<Long> = _sessionQueries.asStateFlow()

    private val _sessionBlocked = MutableStateFlow(0L)
    val sessionBlocked: StateFlow<Long> = _sessionBlocked.asStateFlow()

    /** Counters not yet folded into the persisted lifetime totals. */
    private val pendingQueries = AtomicLong(0)
    private val pendingBlocked = AtomicLong(0)

    fun record(domain: String, verdict: FilterEngine.Verdict, log: Boolean) {
        _sessionQueries.value = _sessionQueries.value + 1
        pendingQueries.incrementAndGet()
        if (verdict.isBlocked) {
            _sessionBlocked.value = _sessionBlocked.value + 1
            pendingBlocked.incrementAndGet()
        }
        if (!log) return

        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeLast()
            buffer.addFirst(Entry(domain, verdict))
            _entries.value = buffer.toList()
        }
    }

    /** Atomically takes the counters accumulated since the last call, for persisting. */
    fun drainCounters(): Pair<Long, Long> =
        pendingQueries.getAndSet(0) to pendingBlocked.getAndSet(0)

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun resetSession() {
        clear()
        _sessionQueries.value = 0
        _sessionBlocked.value = 0
    }
}
