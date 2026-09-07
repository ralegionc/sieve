package dev.sieve

import dev.sieve.data.Lock
import dev.sieve.data.LockMode
import dev.sieve.data.SievePreferences
import dev.sieve.data.TunnelMode
import dev.sieve.data.UnlockState
import dev.sieve.data.UpstreamMode
import dev.sieve.filter.BypassGuard
import dev.sieve.filter.DomainSet
import dev.sieve.filter.FilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

private const val MINUTE = 60_000L

private fun preferences(
    lockMode: LockMode = LockMode.NONE,
    passphraseHash: String = "",
    cooldownMinutes: Int = 30,
    unlockRequestedAt: Long = 0L,
    unlockedUntil: Long = 0L,
) = SievePreferences(
    enabledSourceIds = emptySet(),
    customBlocked = emptySet(),
    allowed = emptySet(),
    upstreamMode = UpstreamMode.SYSTEM,
    customUpstream = "",
    startOnBoot = false,
    logQueries = true,
    autoUpdate = true,
    lastUpdateMillis = 0L,
    blocklistSize = 0,
    lifetimeQueries = 0L,
    lifetimeBlocked = 0L,
    strictMode = true,
    lockMode = lockMode,
    passphraseHash = passphraseHash,
    cooldownMinutes = cooldownMinutes,
    unlockRequestedAt = unlockRequestedAt,
    unlockedUntil = unlockedUntil,
    tunnelMode = TunnelMode.DNS_ONLY,
)

class LockStateTest {

    private val now = 1_000_000_000L

    @Test
    fun `no lock leaves everything open`() {
        assertEquals(UnlockState.Open, Lock.stateOf(preferences(), now))
        assertTrue(Lock.stateOf(preferences(), now).allowsWeakening)
    }

    @Test
    fun `a cooldown lock with no request is closed`() {
        val state = Lock.stateOf(preferences(lockMode = LockMode.COOLDOWN), now)
        assertEquals(UnlockState.Locked, state)
        assertFalse(state.allowsWeakening)
    }

    @Test
    fun `a request starts a countdown that does not yet unlock`() {
        val state = Lock.stateOf(
            preferences(
                lockMode = LockMode.COOLDOWN,
                cooldownMinutes = 30,
                unlockRequestedAt = now - 10 * MINUTE,
            ),
            now,
        )
        assertTrue(state is UnlockState.Waiting)
        assertEquals(20 * MINUTE, (state as UnlockState.Waiting).remainingMillis)
        assertFalse(state.allowsWeakening)
    }

    @Test
    fun `the countdown unlocks once it elapses`() {
        val state = Lock.stateOf(
            preferences(
                lockMode = LockMode.COOLDOWN,
                cooldownMinutes = 30,
                unlockRequestedAt = now - 31 * MINUTE,
            ),
            now,
        )
        assertEquals(UnlockState.Granted, state)
        assertTrue(state.allowsWeakening)
    }

    @Test
    fun `a passphrase lock is never satisfied by waiting`() {
        val waited = preferences(
            lockMode = LockMode.PASSPHRASE,
            passphraseHash = Lock.hash("correct horse"),
            unlockRequestedAt = now - 10_000 * MINUTE,
        )
        assertEquals(UnlockState.Locked, Lock.stateOf(waited, now))
    }

    @Test
    fun `a granted unlock expires with its grace window`() {
        val granted = preferences(
            lockMode = LockMode.PASSPHRASE,
            unlockedUntil = now + 1,
        )
        assertEquals(UnlockState.Granted, Lock.stateOf(granted, now))
        // One millisecond past the window and the gate closes again.
        assertEquals(UnlockState.Locked, Lock.stateOf(granted, now + 1))
    }

    @Test
    fun `winding the clock back does not hand out an unlock`() {
        val pending = preferences(
            lockMode = LockMode.COOLDOWN,
            cooldownMinutes = 30,
            unlockRequestedAt = now,
        )
        // A device clock moved backwards leaves a longer wait, never a shorter one.
        val state = Lock.stateOf(pending, now - 60 * MINUTE)
        assertTrue(state is UnlockState.Waiting)
        assertTrue((state as UnlockState.Waiting).remainingMillis > 30 * MINUTE)
    }
}

class PassphraseTest {

    @Test
    fun `hashing is stable and case sensitive`() {
        assertEquals(Lock.hash("hello there"), Lock.hash("  hello there  "))
        assertNotEquals(Lock.hash("hello there"), Lock.hash("Hello There"))
    }

    @Test
    fun `the raw passphrase is not recoverable from what is stored`() {
        val stored = Lock.hash("open sesame")
        assertFalse(stored.contains("open"))
        assertEquals(64, stored.length) // SHA-256, hex
    }

    @Test
    fun `matching accepts only the right passphrase`() {
        val stored = Lock.hash("open sesame")
        assertTrue(Lock.matches("open sesame", stored))
        assertFalse(Lock.matches("open sesam", stored))
        assertFalse(Lock.matches("", stored))
        assertFalse("an unset passphrase must never match", Lock.matches("anything", ""))
    }

    @Test
    fun `generated passphrases are long and unambiguous`() {
        val passphrase = Lock.generatePassphrase(Random(1))
        assertEquals(29, passphrase.length) // five groups of five, four separators
        assertTrue(passphrase.none { it in "ilo01" }) // no glyphs that get misread
        assertNotEquals(Lock.generatePassphrase(), Lock.generatePassphrase())
    }
}

class BypassGuardTest {

    private val engine = FilterEngine(
        blocklist = DomainSet.EMPTY,
        customBlocked = emptySet(),
        // The user has explicitly allowed the DoH endpoint. Strict mode must not honour it.
        allowed = setOf("dns.google", "cloudflare-dns.com"),
        strict = true,
    )

    @Test
    fun `encrypted DNS endpoints are blocked even when explicitly allowed`() {
        assertEquals(FilterEngine.Verdict.BLOCKED_BY_GUARD, engine.evaluate("dns.google"))
        assertEquals(FilterEngine.Verdict.BLOCKED_BY_GUARD, engine.evaluate("cloudflare-dns.com"))
        assertEquals(
            FilterEngine.Verdict.BLOCKED_BY_GUARD,
            engine.evaluate("chrome.cloudflare-dns.com"),
        )
    }

    @Test
    fun `the Firefox canary domain is refused, which turns its DoH off`() {
        assertEquals(
            FilterEngine.Verdict.BLOCKED_BY_GUARD,
            engine.evaluate("use-application-dns.net"),
        )
    }

    @Test
    fun `ordinary domains are untouched by the guard`() {
        assertEquals(FilterEngine.Verdict.ALLOWED, engine.evaluate("wikipedia.org"))
        assertEquals(FilterEngine.Verdict.ALLOWED, engine.evaluate("google.com"))
    }

    @Test
    fun `the guard stands down outside strict mode`() {
        val relaxed = FilterEngine(DomainSet.EMPTY, emptySet(), emptySet(), strict = false)
        assertEquals(FilterEngine.Verdict.ALLOWED, relaxed.evaluate("dns.google"))
    }

    @Test
    fun `every captured resolver address is a parseable literal`() {
        val all = BypassGuard.RESOLVER_IPV4 + BypassGuard.RESOLVER_IPV6
        all.forEach { literal ->
            val address = java.net.InetAddress.getByName(literal)
            assertEquals(literal, address.hostAddress?.lowercase() ?: literal, address.hostAddress?.lowercase())
        }
        assertEquals("no duplicate resolver entries", all.size, all.toSet().size)
    }

    @Test
    fun `guard hostnames are plausible domains with no duplicates`() {
        assertEquals(BypassGuard.DOH_HOSTS.size, BypassGuard.DOH_HOSTS.toSet().size)
        BypassGuard.DOH_HOSTS.forEach { host ->
            assertTrue("$host should look like a domain", host.contains('.'))
            assertEquals("$host should be lowercase", host.lowercase(), host)
        }
    }
}
