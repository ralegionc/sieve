package dev.sieve.filter

/**
 * Decides whether a queried name is allowed through.
 *
 * The rule order is deliberate. The bypass guard comes first and is not overridable — an allow
 * rule for `dns.google` would hand back the encrypted-DNS escape hatch the guard exists to close.
 * After that an explicit allow always wins, so a false positive in a downloaded list can be
 * corrected without editing the list.
 */
class FilterEngine(
    private val blocklist: DomainSet,
    customBlocked: Set<String>,
    allowed: Set<String>,
    private val strict: Boolean = true,
) {

    private val customBlockedSet = DomainSet.of(customBlocked)
    private val allowedSet = DomainSet.of(allowed)

    enum class Verdict(val isBlocked: Boolean) {
        ALLOWED(false),
        ALLOWED_BY_RULE(false),
        BLOCKED_BY_LIST(true),
        BLOCKED_BY_RULE(true),
        BLOCKED_BY_GUARD(true),
    }

    fun evaluate(domain: String): Verdict = when {
        strict && BypassGuard.domains.matches(domain) -> Verdict.BLOCKED_BY_GUARD
        allowedSet.matches(domain) -> Verdict.ALLOWED_BY_RULE
        customBlockedSet.matches(domain) -> Verdict.BLOCKED_BY_RULE
        blocklist.matches(domain) -> Verdict.BLOCKED_BY_LIST
        else -> Verdict.ALLOWED
    }

    val blocklistSize: Int get() = blocklist.size
}
