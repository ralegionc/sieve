package dev.sieve.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.sieve.Graph
import dev.sieve.MainActivity
import dev.sieve.R
import dev.sieve.SieveApp
import dev.sieve.data.Lock
import dev.sieve.data.TunnelMode
import dev.sieve.data.UpstreamMode
import dev.sieve.filter.BypassGuard
import dev.sieve.filter.FilterEngine
import dev.sieve.filter.QueryLog
import dev.sieve.net.TunnelEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The tunnel.
 *
 * Both modes run the same forwarding engine and differ only in what they route:
 *
 *  - [TunnelMode.DNS_ONLY] routes the resolvers themselves — the ones the network advertised, plus
 *    the public ones apps hardcode when strict mode is on. Everything else takes its normal path,
 *    so there is no throughput cost.
 *  - [TunnelMode.FULL] routes `0.0.0.0/0` and `::/0`. Every packet is proxied through a protected
 *    socket in userspace. That costs throughput and battery, and it is the only way Android's
 *    "Block connections without VPN" leaves the phone able to reach anything.
 *
 * Whatever is routed in, DNS is filtered on the way through and blocked names are answered locally
 * with NXDOMAIN. Nothing else is inspected.
 */
class SieveVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunnel: ParcelFileDescriptor? = null
    private var engine: TunnelEngine? = null

    @Volatile
    private var filter: FilterEngine? = null

    @Volatile
    private var logQueries: Boolean = true

    /**
     * Mirrors whether weakening the filter is currently permitted, so a stale "Stop" intent —
     * from an old notification, or a shortcut — cannot slip past the lock.
     */
    @Volatile
    private var weakeningAllowed: Boolean = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (!weakeningAllowed) {
                    Log.i(TAG, "Stop refused: the filter is locked")
                    updateNotification()
                    return START_STICKY
                }
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        if (_state.value == State.RUNNING) return
        Graph.ensure(this)
        startInForeground()
        _state.value = State.STARTING

        scope.launch {
            val preferences = Graph.settings.preferences.first()
            logQueries = preferences.logQueries
            weakeningAllowed = Lock.stateOf(preferences).allowsWeakening
            filter = FilterEngine(
                blocklist = Graph.blocklists.domains(),
                customBlocked = preferences.customBlocked,
                allowed = preferences.allowed,
                strict = preferences.strictMode,
            )

            val resolvers = resolveUpstreams(preferences.upstreamMode, preferences.customUpstream)
            if (resolvers.isEmpty()) {
                Log.e(TAG, "No usable upstream resolvers; refusing to start")
                _state.value = State.STOPPED
                stopSelf()
                return@launch
            }

            val descriptor = establish(resolvers, preferences.tunnelMode, preferences.strictMode)
            if (descriptor == null) {
                _state.value = State.STOPPED
                stopSelf()
                return@launch
            }

            QueryLog.resetSession()
            startEngine(descriptor)
            _activeMode.value = preferences.tunnelMode
            _state.value = State.RUNNING

            persistCountersPeriodically()
            watchForRuleChanges()
            watchLockState()
        }
    }

    // ---------------------------------------------------------------- tunnel

    private fun establish(
        resolvers: List<InetAddress>,
        mode: TunnelMode,
        strict: Boolean,
    ): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setBlocking(true)
            .setMtu(MTU)
            .addAddress(TUNNEL_IPV4_ADDRESS, TUNNEL_IPV4_PREFIX)

        val wantsIpv6 = hasGlobalIpv6()
        if (wantsIpv6) {
            runCatching { builder.addAddress(TUNNEL_IPV6_ADDRESS, TUNNEL_IPV6_PREFIX) }
                .onFailure { Log.w(TAG, "Could not add the IPv6 tunnel address", it) }
        }

        // The system is told to use the real resolvers; the routes below decide what we see.
        resolvers.forEach { resolver ->
            runCatching { builder.addDnsServer(resolver) }
                .onFailure { Log.w(TAG, "Rejected DNS server $resolver", it) }
        }

        when (mode) {
            TunnelMode.FULL -> {
                builder.addRoute("0.0.0.0", 0)
                if (wantsIpv6) builder.addRoute("::", 0)
                // Excluding ourselves would leave the app with no network at all under lockdown,
                // because a disallowed app is blocked outright. Our upstream sockets are
                // protected, so there is no loop; the app's own downloads ride through the engine.
            }

            TunnelMode.DNS_ONLY -> {
                val routed = resolvers + if (strict) capturableResolvers(wantsIpv6) else emptyList()
                var added = 0
                for (address in routed) {
                    // A link-local resolver cannot be routed into the tunnel meaningfully.
                    if (address.isLinkLocalAddress || address.isAnyLocalAddress) continue
                    if (address is Inet6Address && !wantsIpv6) continue
                    val prefix = if (address is Inet6Address) 128 else 32
                    runCatching { builder.addRoute(address, prefix) }
                        .onSuccess { added++ }
                        .onFailure { Log.v(TAG, "Could not route $address", it) }
                }
                if (added == 0) {
                    Log.e(TAG, "No resolver could be routed into the tunnel")
                    return null
                }
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "Could not exclude self from the tunnel", it) }
            }
        }

        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        val descriptor = runCatching { builder.establish() }
            .onFailure { Log.e(TAG, "establish() failed", it) }
            .getOrNull()

        if (descriptor == null) Log.e(TAG, "VPN permission revoked, or the tunnel was refused")
        tunnel = descriptor
        return descriptor
    }

    private fun startEngine(descriptor: ParcelFileDescriptor) {
        val created = TunnelEngine(
            protectSocket = { socket -> protect(socket) },
            protectDatagram = { socket -> protect(socket) },
            isBlocked = ::evaluate,
        )
        engine = created
        created.start(
            FileInputStream(descriptor.fileDescriptor),
            FileOutputStream(descriptor.fileDescriptor),
        )
    }

    /** Called from the engine for every looked-up name. Records it and returns the verdict. */
    private fun evaluate(domain: String): Boolean {
        val active = filter ?: return false
        val verdict = active.evaluate(domain)
        QueryLog.record(domain, verdict, logQueries)
        return verdict.isBlocked
    }

    /** Public resolvers worth routing in so an app cannot sidestep the advertised DNS server. */
    private fun capturableResolvers(includeIpv6: Boolean): List<InetAddress> {
        val literals = BypassGuard.RESOLVER_IPV4 +
            if (includeIpv6) BypassGuard.RESOLVER_IPV6 else emptyList()
        return literals.mapNotNull(::parseAddress)
    }

    /**
     * Picks the resolvers that non-blocked queries are forwarded to. In [UpstreamMode.SYSTEM] we
     * read the ones the active network handed out, so the user keeps their ISP or router DNS.
     */
    private fun resolveUpstreams(mode: UpstreamMode, custom: String): List<InetAddress> {
        val configured = when (mode) {
            UpstreamMode.SYSTEM -> systemResolvers()
            UpstreamMode.CUSTOM -> custom.split(',').mapNotNull { parseAddress(it.trim()) }
            else -> mode.servers.mapNotNull(::parseAddress)
        }
        val usable = configured.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
        return usable.ifEmpty { FALLBACK_RESOLVERS.mapNotNull(::parseAddress) }
            .take(MAX_UPSTREAMS)
    }

    private fun systemResolvers(): List<InetAddress> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        return manager.getLinkProperties(network)?.dnsServers.orEmpty()
    }

    /**
     * True when the active network has a routable IPv6 address. Advertising an IPv6 route on a
     * v4-only network makes apps try IPv6 first and wait for Happy Eyeballs to give up on it.
     */
    private fun hasGlobalIpv6(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val properties = manager.getLinkProperties(network) ?: return false
        return properties.linkAddresses.any { link ->
            val address = link.address
            address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress
        }
    }

    private fun parseAddress(value: String): InetAddress? =
        if (value.isEmpty()) null else runCatching { InetAddress.getByName(value) }.getOrNull()

    // ---------------------------------------------------------------- settings watching

    /**
     * Rebuilds the filter when the rules or the downloaded lists change, so edits take effect
     * without cycling the tunnel. Traffic counters are written to the same store every 20 seconds,
     * hence the explicit narrowing — otherwise this would rebuild on every flush.
     *
     * The tunnel mode is deliberately absent: changing it means re-establishing the interface, so
     * the view model restarts the service instead.
     */
    private fun watchForRuleChanges() {
        scope.launch {
            Graph.settings.preferences
                .map {
                    RuleSnapshot(
                        it.customBlocked,
                        it.allowed,
                        it.logQueries,
                        it.lastUpdateMillis,
                        it.strictMode,
                    )
                }
                .distinctUntilChanged()
                .drop(1) // the initial snapshot is already applied
                .collect { snapshot ->
                    logQueries = snapshot.logQueries
                    filter = FilterEngine(
                        blocklist = Graph.blocklists.cachedDomains(),
                        customBlocked = snapshot.customBlocked,
                        allowed = snapshot.allowed,
                        strict = snapshot.strictMode,
                    )
                }
        }
    }

    /**
     * Keeps [weakeningAllowed] current. A waiting period elapses on a wall clock rather than an
     * event, so this re-evaluates on a timer as well as on every preference change.
     */
    private fun watchLockState() {
        scope.launch {
            Graph.settings.preferences.collect { preferences ->
                weakeningAllowed = Lock.stateOf(preferences).allowsWeakening
            }
        }
        scope.launch {
            while (true) {
                delay(LOCK_POLL_INTERVAL_MS)
                val allowed = Lock.stateOf(Graph.settings.preferences.first()).allowsWeakening
                if (allowed != weakeningAllowed) {
                    weakeningAllowed = allowed
                    updateNotification()
                }
            }
        }
    }

    private data class RuleSnapshot(
        val customBlocked: Set<String>,
        val allowed: Set<String>,
        val logQueries: Boolean,
        val lastUpdateMillis: Long,
        val strictMode: Boolean,
    )

    // ---------------------------------------------------------------- lifecycle

    private fun persistCountersPeriodically() {
        scope.launch {
            while (_state.value == State.RUNNING) {
                delay(COUNTER_FLUSH_INTERVAL_MS)
                flushCounters()
                updateNotification()
            }
        }
    }

    private suspend fun flushCounters() {
        val (queries, blocked) = QueryLog.drainCounters()
        if (queries > 0 || blocked > 0) Graph.settings.recordTraffic(queries, blocked)
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val blocked = QueryLog.sessionBlocked.value
        val total = QueryLog.sessionQueries.value

        return NotificationCompat.Builder(this, SieveApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(
                if (weakeningAllowed) {
                    getString(R.string.notification_title)
                } else {
                    getString(R.string.notification_title_locked)
                },
            )
            .setContentText(getString(R.string.notification_text, blocked, total))
            .setContentIntent(open)
            // Offering a button that would be refused is worse than not offering one.
            .apply { if (weakeningAllowed) addAction(0, getString(R.string.action_stop), stop) }
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun shutdown() {
        if (_state.value == State.STOPPED) return
        _state.value = State.STOPPED

        engine?.stop()
        engine = null
        runCatching { tunnel?.close() }
        tunnel = null
        filter = null

        // Flush on a scope that outlives this one, then cancel.
        val (queries, blocked) = QueryLog.drainCounters()
        if (queries > 0 || blocked > 0) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                Graph.settings.recordTraffic(queries, blocked)
            }
        }
        scope.cancel()
    }

    /**
     * Called when the tunnel is torn down from outside — the VPN toggle in Android settings, or
     * another VPN app taking the slot. While locked, that is exactly the bypass the lock exists to
     * prevent, so filtering is re-established as soon as the platform lets us.
     */
    override fun onRevoke() {
        Log.i(TAG, "VPN consent revoked")
        val shouldReArm = !weakeningAllowed
        shutdown()
        if (shouldReArm) scheduleReArm() else stopSelf()
    }

    private fun scheduleReArm() {
        val context = applicationContext
        // A scope of its own: this service is on its way out.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            repeat(RE_ARM_ATTEMPTS) { attempt ->
                delay(RE_ARM_DELAY_MS)
                // Null means consent still stands. Non-null means another VPN holds the slot and
                // only the user can hand it back, so nagging further would be pointless.
                if (VpnService.prepare(context) != null) {
                    Log.w(TAG, "Cannot re-arm: another VPN holds the slot")
                    return@launch
                }
                runCatching { start(context) }
                    .onSuccess {
                        Log.i(TAG, "Re-armed after revoke on attempt ${attempt + 1}")
                        return@launch
                    }
                    .onFailure { Log.w(TAG, "Re-arm attempt ${attempt + 1} failed", it) }
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    enum class State { STOPPED, STARTING, RUNNING }

    companion object {
        private const val TAG = "Sieve/Vpn"
        private const val NOTIFICATION_ID = 1

        private const val MTU = 1500
        private const val LOCK_POLL_INTERVAL_MS = 30_000L
        private const val COUNTER_FLUSH_INTERVAL_MS = 20_000L
        private const val RE_ARM_DELAY_MS = 2_000L
        private const val RE_ARM_ATTEMPTS = 5
        private const val MAX_UPSTREAMS = 4

        // A private range unlikely to collide with a real network.
        private const val TUNNEL_IPV4_ADDRESS = "10.111.222.1"
        private const val TUNNEL_IPV4_PREFIX = 24
        private const val TUNNEL_IPV6_ADDRESS = "fd00:5165:7665::1"
        private const val TUNNEL_IPV6_PREFIX = 64

        private val FALLBACK_RESOLVERS = listOf("9.9.9.9", "149.112.112.112")

        const val ACTION_STOP = "dev.sieve.action.STOP"

        private val _state = MutableStateFlow(State.STOPPED)
        val state: StateFlow<State> = _state.asStateFlow()

        private val _activeMode = MutableStateFlow(TunnelMode.DNS_ONLY)

        /** The mode the running tunnel was built with; a settings change alone does not alter it. */
        val activeMode: StateFlow<TunnelMode> = _activeMode.asStateFlow()

        /**
         * Returns the Private DNS hostname if one is configured.
         *
         * Android resolves through it over TLS on port 853. In DNS-only mode that traffic never
         * enters the tunnel. In full mode it does, but it is encrypted end to end, so it still
         * cannot be filtered — only refused wholesale, which would break name resolution outright.
         */
        fun privateDnsHostname(context: Context): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = manager.activeNetwork ?: return null
            val properties = manager.getLinkProperties(network) ?: return null
            return properties.privateDnsServerName?.takeIf { properties.isPrivateDnsActive }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SieveVpnService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, SieveVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
