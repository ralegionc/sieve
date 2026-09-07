package dev.sieve.filter

import android.content.Context
import android.util.Log
import dev.sieve.data.BlocklistSource
import dev.sieve.data.SieveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads the enabled hosts lists, folds them into a single [DomainSet], and caches the result
 * on disk so the VPN can start instantly and work offline.
 */
class BlocklistRepository(
    private val context: Context,
    private val settings: SieveSettings,
) {

    sealed interface UpdateState {
        data object Idle : UpdateState
        data class Running(val source: String, val index: Int, val total: Int) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val cacheFile: File get() = File(context.filesDir, "blocklist.idx")

    @Volatile
    private var cached: DomainSet? = null

    /** The active set, read from disk on first use. Empty until the first successful update. */
    suspend fun domains(): DomainSet = cached ?: withContext(Dispatchers.IO) {
        val loaded = DomainSet.readFrom(cacheFile) ?: DomainSet.EMPTY
        cached = loaded
        loaded
    }

    fun cachedDomains(): DomainSet = cached ?: DomainSet.EMPTY

    /**
     * Re-downloads every enabled source. Returns the number of unique domains, or null if every
     * source failed — in which case the previous cache is left untouched.
     */
    suspend fun update(): Int? = withContext(Dispatchers.IO) {
        val enabledIds = settings.preferences.first().enabledSourceIds
        val sources = BlocklistSource.BUNDLED.filter { it.id in enabledIds }

        if (sources.isEmpty()) {
            val empty = DomainSet.EMPTY
            empty.writeTo(cacheFile)
            cached = empty
            settings.recordUpdate(0)
            _updateState.value = UpdateState.Idle
            return@withContext 0
        }

        val builder = DomainSet.Builder()
        var failures = 0
        var lastError = ""

        sources.forEachIndexed { index, source ->
            _updateState.value = UpdateState.Running(source.title, index + 1, sources.size)
            runCatching { download(source.url, builder) }
                .onFailure {
                    failures++
                    lastError = it.message ?: it::class.java.simpleName
                    Log.w(TAG, "Failed to fetch ${source.id}", it)
                }
        }

        if (failures == sources.size) {
            _updateState.value = UpdateState.Failed(lastError.ifEmpty { "Download failed" })
            return@withContext null
        }

        val result = builder.build()
        result.writeTo(cacheFile)
        cached = result
        settings.recordUpdate(result.size)
        _updateState.value = UpdateState.Idle
        result.size
    }

    fun clearError() {
        if (_updateState.value is UpdateState.Failed) _updateState.value = UpdateState.Idle
    }

    private fun download(url: String, builder: DomainSet.Builder) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")

            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            BufferedReader(InputStreamReader(stream), READ_BUFFER).use { reader ->
                reader.lineSequence().forEach { line ->
                    parseHostsLine(line)?.let(builder::add)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "Sieve/Blocklist"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val READ_BUFFER = 1 shl 16
        private const val USER_AGENT = "Sieve/1.0 (Android DNS filter)"

        /** Hosts-file entries that map to the local machine rather than to a site to block. */
        private val LOOPBACK_NAMES = setOf(
            "localhost",
            "localhost.localdomain",
            "local",
            "broadcasthost",
            "ip6-localhost",
            "ip6-loopback",
            "ip6-localnet",
            "ip6-mcastprefix",
            "ip6-allnodes",
            "ip6-allrouters",
            "ip6-allhosts",
            "0.0.0.0",
        )

        /**
         * Accepts both hosts syntax (`0.0.0.0 example.com`) and bare domain lists. Returns the
         * lowercased domain, or null for comments, blank lines and anything implausible.
         */
        fun parseHostsLine(line: String): String? {
            val withoutComment = line.substringBefore('#').trim()
            if (withoutComment.isEmpty()) return null

            val fields = withoutComment.split(' ', '\t').filter { it.isNotEmpty() }
            val candidate = when (fields.size) {
                0 -> return null
                1 -> fields[0]
                else -> fields[1] // "<ip> <domain>"
            }.lowercase().removeSuffix(".")

            if (candidate in LOOPBACK_NAMES) return null
            return candidate.takeIf(::isPlausibleDomain)
        }

        private fun isPlausibleDomain(value: String): Boolean {
            if (value.length !in 4..253) return false
            if (!value.contains('.')) return false
            if (value.startsWith('.') || value.endsWith('.') || value.contains("..")) return false
            // Reject bare IP literals, which appear in some lists as the redirect target.
            if (value[0].isDigit() && value.all { it.isDigit() || it == '.' }) return false
            return value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        }
    }
}
