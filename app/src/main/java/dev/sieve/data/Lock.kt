package dev.sieve.data

import java.security.MessageDigest

/**
 * How hard it is to weaken the filter.
 *
 * Turning filtering *on* is always free. Everything that loosens it — stopping the tunnel,
 * disabling a list, adding an allow rule, deleting a block rule — goes through the same gate.
 * Locking only the off switch would be theatre: adding `.com` to the allowlist is the same thing.
 */
enum class LockMode(val label: String, val summary: String) {
    NONE(
        "Off",
        "Anything can be changed straight away.",
    ),
    COOLDOWN(
        "Waiting period",
        "Weakening the filter requires a wait. The request survives rebooting and force-stopping.",
    ),
    PASSPHRASE(
        "Passphrase",
        "A passphrase is required. Generate one, hand it to someone else, and you cannot undo it alone.",
    ),
}

sealed interface UnlockState {

    /** No lock is configured. */
    data object Open : UnlockState

    /** Locked, with nothing pending. */
    data object Locked : UnlockState

    /** A waiting period is running. */
    data class Waiting(val remainingMillis: Long) : UnlockState

    /** The waiting period has elapsed, or a passphrase was accepted. */
    data object Granted : UnlockState

    /** True when a change that weakens the filter is currently permitted. */
    val allowsWeakening: Boolean
        get() = this is Open || this is Granted
}

object Lock {

    /** How long a granted unlock stays usable before the gate closes again. */
    const val GRACE_MILLIS = 10 * 60 * 1000L

    val COOLDOWN_CHOICES = listOf(15, 30, 60, 180, 720)

    fun stateOf(preferences: SievePreferences, now: Long = System.currentTimeMillis()): UnlockState {
        if (preferences.lockMode == LockMode.NONE) return UnlockState.Open
        if (now < preferences.unlockedUntil) return UnlockState.Granted

        return when (preferences.lockMode) {
            LockMode.NONE -> UnlockState.Open
            LockMode.PASSPHRASE -> UnlockState.Locked
            LockMode.COOLDOWN -> {
                val requestedAt = preferences.unlockRequestedAt
                if (requestedAt <= 0L) return UnlockState.Locked
                val readyAt = requestedAt + preferences.cooldownMinutes * 60_000L
                // A clock rolled backwards would otherwise hand out an unlock for free.
                if (now >= readyAt) UnlockState.Granted else UnlockState.Waiting(readyAt - now)
            }
        }
    }

    /**
     * Passphrases are stored hashed. Not because an attacker is expected — it is your own phone —
     * but so that handing the passphrase to someone else actually means you no longer hold it,
     * even with a file browser.
     */
    fun hash(passphrase: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(passphrase.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun matches(passphrase: String, storedHash: String): Boolean =
        storedHash.isNotEmpty() && hash(passphrase) == storedHash

    /**
     * A passphrase worth handing to somebody else: long enough that retyping it from memory is
     * not realistic, and drawn from an alphabet with no ambiguous glyphs.
     */
    fun generatePassphrase(random: java.util.Random = java.security.SecureRandom()): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (0 until 5).joinToString("-") {
            (0 until 5).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
