package dev.sieve.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sieve")

/** Which resolver forwarded (i.e. non-blocked) queries are sent to. */
enum class UpstreamMode(val label: String) {
    /** Whatever the current Wi-Fi or mobile network hands out. */
    SYSTEM("Network default"),
    QUAD9("Quad9"),
    CLOUDFLARE("Cloudflare"),
    GOOGLE("Google"),
    CUSTOM("Custom"),
    ;

    /** Hard-coded resolvers for the fixed choices; SYSTEM and CUSTOM resolve elsewhere. */
    val servers: List<String>
        get() = when (this) {
            QUAD9 -> listOf("9.9.9.9", "149.112.112.112")
            CLOUDFLARE -> listOf("1.1.1.1", "1.0.0.1")
            GOOGLE -> listOf("8.8.8.8", "8.8.4.4")
            SYSTEM, CUSTOM -> emptyList()
        }
}

data class SievePreferences(
    val enabledSourceIds: Set<String>,
    val customBlocked: Set<String>,
    val allowed: Set<String>,
    val upstreamMode: UpstreamMode,
    val customUpstream: String,
    val startOnBoot: Boolean,
    val logQueries: Boolean,
    val autoUpdate: Boolean,
    val lastUpdateMillis: Long,
    val blocklistSize: Int,
    val lifetimeQueries: Long,
    val lifetimeBlocked: Long,
    val strictMode: Boolean,
    val lockMode: LockMode,
    val passphraseHash: String,
    val cooldownMinutes: Int,
    val unlockRequestedAt: Long,
    val unlockedUntil: Long,
    val tunnelMode: TunnelMode,
)

class SieveSettings(private val context: Context) {

    val preferences: Flow<SievePreferences> = context.dataStore.data.map { it.toSievePreferences() }

    private fun Preferences.toSievePreferences() = SievePreferences(
        enabledSourceIds = this[Keys.ENABLED_SOURCES] ?: BlocklistSource.DEFAULT_ENABLED_IDS,
        customBlocked = this[Keys.CUSTOM_BLOCKED].orEmpty(),
        allowed = this[Keys.ALLOWED].orEmpty(),
        upstreamMode = this[Keys.UPSTREAM_MODE]
            ?.let { name -> runCatching { UpstreamMode.valueOf(name) }.getOrNull() }
            ?: UpstreamMode.SYSTEM,
        customUpstream = this[Keys.CUSTOM_UPSTREAM].orEmpty(),
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        logQueries = this[Keys.LOG_QUERIES] ?: true,
        autoUpdate = this[Keys.AUTO_UPDATE] ?: true,
        lastUpdateMillis = this[Keys.LAST_UPDATE] ?: 0L,
        blocklistSize = this[Keys.BLOCKLIST_SIZE]?.toInt() ?: 0,
        lifetimeQueries = this[Keys.LIFETIME_QUERIES] ?: 0L,
        lifetimeBlocked = this[Keys.LIFETIME_BLOCKED] ?: 0L,
        strictMode = this[Keys.STRICT_MODE] ?: true,
        lockMode = this[Keys.LOCK_MODE]
            ?.let { name -> runCatching { LockMode.valueOf(name) }.getOrNull() }
            ?: LockMode.NONE,
        passphraseHash = this[Keys.PASSPHRASE_HASH].orEmpty(),
        cooldownMinutes = this[Keys.COOLDOWN_MINUTES] ?: 30,
        unlockRequestedAt = this[Keys.UNLOCK_REQUESTED_AT] ?: 0L,
        unlockedUntil = this[Keys.UNLOCKED_UNTIL] ?: 0L,
        tunnelMode = this[Keys.TUNNEL_MODE]
            ?.let { name -> runCatching { TunnelMode.valueOf(name) }.getOrNull() }
            ?: TunnelMode.DNS_ONLY,
    )

    suspend fun setSourceEnabled(id: String, enabled: Boolean) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.ENABLED_SOURCES] ?: BlocklistSource.DEFAULT_ENABLED_IDS
        prefs[Keys.ENABLED_SOURCES] = if (enabled) current + id else current - id
    }

    suspend fun addCustomBlocked(domain: String) = editDomainSet(Keys.CUSTOM_BLOCKED) { it + domain }

    suspend fun removeCustomBlocked(domain: String) = editDomainSet(Keys.CUSTOM_BLOCKED) { it - domain }

    suspend fun addAllowed(domain: String) = editDomainSet(Keys.ALLOWED) { it + domain }

    suspend fun removeAllowed(domain: String) = editDomainSet(Keys.ALLOWED) { it - domain }

    private suspend fun editDomainSet(
        key: Preferences.Key<Set<String>>,
        transform: (Set<String>) -> Set<String>,
    ) = context.dataStore.edit { prefs ->
        prefs[key] = transform(prefs[key].orEmpty())
    }

    suspend fun setUpstream(mode: UpstreamMode, custom: String = "") = context.dataStore.edit {
        it[Keys.UPSTREAM_MODE] = mode.name
        if (mode == UpstreamMode.CUSTOM) it[Keys.CUSTOM_UPSTREAM] = custom.trim()
    }

    suspend fun setStartOnBoot(value: Boolean) = putBoolean(Keys.START_ON_BOOT, value)

    suspend fun setLogQueries(value: Boolean) = putBoolean(Keys.LOG_QUERIES, value)

    suspend fun setAutoUpdate(value: Boolean) = putBoolean(Keys.AUTO_UPDATE, value)

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) =
        context.dataStore.edit { it[key] = value }

    suspend fun recordUpdate(entryCount: Int, timestamp: Long = System.currentTimeMillis()) =
        context.dataStore.edit {
            it[Keys.BLOCKLIST_SIZE] = entryCount.toLong()
            it[Keys.LAST_UPDATE] = timestamp
        }

    suspend fun recordTraffic(queries: Long, blocked: Long) = context.dataStore.edit {
        it[Keys.LIFETIME_QUERIES] = (it[Keys.LIFETIME_QUERIES] ?: 0L) + queries
        it[Keys.LIFETIME_BLOCKED] = (it[Keys.LIFETIME_BLOCKED] ?: 0L) + blocked
    }

    suspend fun setTunnelMode(mode: TunnelMode) = context.dataStore.edit {
        it[Keys.TUNNEL_MODE] = mode.name
    }

    suspend fun setStrictMode(value: Boolean) = putBoolean(Keys.STRICT_MODE, value)

    /**
     * Arms a lock. Any pending unlock is discarded, so re-arming while a waiting period is running
     * does not leave a grant sitting there ready to use.
     */
    suspend fun arm(mode: LockMode, passphrase: String = "", cooldownMinutes: Int = 30) =
        context.dataStore.edit {
            it[Keys.LOCK_MODE] = mode.name
            it[Keys.COOLDOWN_MINUTES] = cooldownMinutes
            it[Keys.UNLOCK_REQUESTED_AT] = 0L
            it[Keys.UNLOCKED_UNTIL] = 0L
            if (mode == LockMode.PASSPHRASE) it[Keys.PASSPHRASE_HASH] = Lock.hash(passphrase)
            if (mode == LockMode.NONE) it[Keys.PASSPHRASE_HASH] = ""
        }

    /** Starts the waiting period. Recorded on disk so a reboot or force-stop does not skip it. */
    suspend fun requestUnlock(now: Long = System.currentTimeMillis()) = context.dataStore.edit {
        if ((it[Keys.UNLOCK_REQUESTED_AT] ?: 0L) <= 0L) it[Keys.UNLOCK_REQUESTED_AT] = now
    }

    suspend fun cancelUnlock() = context.dataStore.edit {
        it[Keys.UNLOCK_REQUESTED_AT] = 0L
        it[Keys.UNLOCKED_UNTIL] = 0L
    }

    /** Opens the grace window after a correct passphrase or an elapsed waiting period. */
    suspend fun grantUnlock(now: Long = System.currentTimeMillis()) = context.dataStore.edit {
        it[Keys.UNLOCKED_UNTIL] = now + Lock.GRACE_MILLIS
        it[Keys.UNLOCK_REQUESTED_AT] = 0L
    }

    suspend fun resetTraffic() = context.dataStore.edit {
        it[Keys.LIFETIME_QUERIES] = 0L
        it[Keys.LIFETIME_BLOCKED] = 0L
    }

    private object Keys {
        val ENABLED_SOURCES = stringSetPreferencesKey("enabled_sources")
        val CUSTOM_BLOCKED = stringSetPreferencesKey("custom_blocked")
        val ALLOWED = stringSetPreferencesKey("allowed")
        val UPSTREAM_MODE = stringPreferencesKey("upstream_mode")
        val CUSTOM_UPSTREAM = stringPreferencesKey("custom_upstream")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val LOG_QUERIES = booleanPreferencesKey("log_queries")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val LAST_UPDATE = longPreferencesKey("last_update")
        val BLOCKLIST_SIZE = longPreferencesKey("blocklist_size")
        val LIFETIME_QUERIES = longPreferencesKey("lifetime_queries")
        val LIFETIME_BLOCKED = longPreferencesKey("lifetime_blocked")
        val STRICT_MODE = booleanPreferencesKey("strict_mode")
        val LOCK_MODE = stringPreferencesKey("lock_mode")
        val PASSPHRASE_HASH = stringPreferencesKey("passphrase_hash")
        val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
        val UNLOCK_REQUESTED_AT = longPreferencesKey("unlock_requested_at")
        val UNLOCKED_UNTIL = longPreferencesKey("unlocked_until")
        val TUNNEL_MODE = stringPreferencesKey("tunnel_mode")
    }
}
