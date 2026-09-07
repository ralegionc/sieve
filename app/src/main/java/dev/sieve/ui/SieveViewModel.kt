package dev.sieve.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sieve.Graph
import dev.sieve.data.BlocklistSource
import dev.sieve.data.Lock
import dev.sieve.data.LockMode
import dev.sieve.data.SievePreferences
import dev.sieve.data.TunnelMode
import dev.sieve.data.UnlockState
import dev.sieve.data.UpstreamMode
import dev.sieve.filter.BlocklistRepository
import dev.sieve.filter.QueryLog
import dev.sieve.vpn.SieveVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SieveViewModel(application: Application) : AndroidViewModel(application) {

    init {
        Graph.ensure(application)
    }

    val preferences: StateFlow<SievePreferences?> = Graph.settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val vpnState: StateFlow<SieveVpnService.State> = SieveVpnService.state
    val updateState: StateFlow<BlocklistRepository.UpdateState> = Graph.blocklists.updateState
    val logEntries: StateFlow<List<QueryLog.Entry>> = QueryLog.entries
    val sessionQueries: StateFlow<Long> = QueryLog.sessionQueries
    val sessionBlocked: StateFlow<Long> = QueryLog.sessionBlocked

    val sources: List<BlocklistSource> = BlocklistSource.BUNDLED

    /** Re-evaluated on a ticker so a running waiting period counts down in the UI. */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val unlockState: StateFlow<UnlockState> =
        combine(Graph.settings.preferences, ticker) { preferences, now ->
            Lock.stateOf(preferences, now)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnlockState.Open)

    private val _passphraseError = MutableStateFlow(false)
    val passphraseError: StateFlow<Boolean> = _passphraseError.asStateFlow()

    val privateDnsHostname: String?
        get() = SieveVpnService.privateDnsHostname(getApplication())

    // ------------------------------------------------------------ the lock

    /**
     * Arms, changes or removes the lock.
     *
     * The gate matters most here: without it the lock is decorative, because switching it to
     * [LockMode.NONE] is a single tap. Arming a lock when none is set is free; every other
     * transition — including swapping a passphrase for a short waiting period — has to pass
     * through the lock already in force.
     */
    fun armLock(mode: LockMode, passphrase: String = "", cooldownMinutes: Int = 30) =
        viewModelScope.launch {
            val current = Graph.settings.preferences.first()
            val isFirstArming = current.lockMode == LockMode.NONE
            if (isFirstArming || Lock.stateOf(current).allowsWeakening) {
                Graph.settings.arm(mode, passphrase, cooldownMinutes)
            }
        }

    fun requestUnlock() = viewModelScope.launch { Graph.settings.requestUnlock() }

    fun cancelUnlock() = viewModelScope.launch { Graph.settings.cancelUnlock() }

    fun submitPassphrase(candidate: String) = viewModelScope.launch {
        val stored = Graph.settings.preferences.first().passphraseHash
        if (Lock.matches(candidate, stored)) {
            _passphraseError.value = false
            Graph.settings.grantUnlock()
        } else {
            _passphraseError.value = true
        }
    }

    fun dismissPassphraseError() { _passphraseError.value = false }

    /**
     * Runs [action] only if the lock currently permits weakening the filter. Every caller that
     * loosens the filter goes through here — locking the off switch alone would be pointless when
     * allowlisting a domain achieves the same result.
     */
    private fun ifUnlocked(action: suspend () -> Unit) = viewModelScope.launch {
        if (Lock.stateOf(Graph.settings.preferences.first()).allowsWeakening) action()
    }

    /** Enabling a list is always permitted; disabling one weakens the filter. */
    fun setSourceEnabled(id: String, enabled: Boolean) {
        val apply: suspend () -> Unit = {
            Graph.settings.setSourceEnabled(id, enabled)
            // Toggling a list is meaningless until it has been fetched, so fetch straight away.
            Graph.blocklists.update()
        }
        if (enabled) viewModelScope.launch { apply() } else ifUnlocked(apply)
    }

    fun updateBlocklists() = viewModelScope.launch { Graph.blocklists.update() }

    fun dismissUpdateError() = Graph.blocklists.clearError()

    fun addBlocked(domain: String) = withNormalised(domain) {
        viewModelScope.launch { Graph.settings.addCustomBlocked(it) }
    }

    fun removeBlocked(domain: String) = ifUnlocked {
        Graph.settings.removeCustomBlocked(domain)
    }

    fun addAllowed(domain: String) = withNormalised(domain) { host ->
        ifUnlocked { Graph.settings.addAllowed(host) }
    }

    fun removeAllowed(domain: String) = viewModelScope.launch {
        Graph.settings.removeAllowed(domain)
    }

    fun setUpstream(mode: UpstreamMode, custom: String = "") = viewModelScope.launch {
        Graph.settings.setUpstream(mode, custom)
        // The resolver set is baked into the tunnel at establish() time.
        if (vpnState.value == SieveVpnService.State.RUNNING) {
            val context = getApplication<Application>()
            SieveVpnService.stop(context)
            SieveVpnService.start(context)
        }
    }

    fun setStartOnBoot(value: Boolean) = viewModelScope.launch {
        Graph.settings.setStartOnBoot(value)
    }

    fun setLogQueries(value: Boolean) = viewModelScope.launch {
        Graph.settings.setLogQueries(value)
        if (!value) QueryLog.clear()
    }

    fun setAutoUpdate(value: Boolean) = viewModelScope.launch {
        Graph.settings.setAutoUpdate(value)
    }

    fun resetStatistics() = viewModelScope.launch {
        Graph.settings.resetTraffic()
        QueryLog.resetSession()
    }

    fun clearActivity() = QueryLog.clear()

    /**
     * The routes are fixed when the interface is built, so a mode change means re-establishing the
     * tunnel. Narrowing it back to DNS-only weakens the filter and goes through the lock.
     */
    fun setTunnelMode(mode: TunnelMode) {
        val apply: suspend () -> Unit = {
            Graph.settings.setTunnelMode(mode)
            if (vpnState.value == SieveVpnService.State.RUNNING) {
                val context = getApplication<Application>()
                SieveVpnService.stop(context)
                SieveVpnService.start(context)
            }
        }
        if (mode == TunnelMode.FULL) viewModelScope.launch { apply() } else ifUnlocked(apply)
    }

    /** Strict mode closes bypasses, so turning it off is itself a weakening change. */
    fun setStrictMode(value: Boolean) {
        if (value) {
            viewModelScope.launch { Graph.settings.setStrictMode(true) }
        } else {
            ifUnlocked { Graph.settings.setStrictMode(false) }
        }
    }

    fun stopFiltering() = ifUnlocked { SieveVpnService.stop(getApplication()) }

    /**
     * Accepts what a user actually types — a bare domain, a full URL, a leading `www.` — and
     * reduces it to the host that the rule engine matches on.
     */
    private inline fun withNormalised(raw: String, action: (String) -> Unit) {
        val host = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .removeSuffix(".")
            .lowercase()
        if (host.isNotEmpty() && host.contains('.')) action(host)
    }
}
