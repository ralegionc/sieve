package dev.sieve.data

/**
 * How much of the phone's traffic goes through the tunnel.
 *
 * This is the one setting with a real cost attached, so it is a choice rather than a default.
 */
enum class TunnelMode(val label: String, val summary: String) {

    /**
     * Only the resolvers are routed in. Everything else takes its ordinary path, so throughput and
     * battery are untouched — but Android's lockdown option would have nothing to route and would
     * cut the phone off entirely.
     */
    DNS_ONLY(
        "DNS only",
        "Routes just the resolvers. No effect on speed or battery. Not compatible with " +
            "Block connections without VPN.",
    ),

    /**
     * Everything is routed in and proxied through userspace. Slower and more power-hungry, but it
     * is the only mode where "Block connections without VPN" leaves you with a working phone —
     * and the only one where an app cannot reach a resolver Sieve has not seen.
     */
    FULL(
        "Full tunnel",
        "Routes everything through Sieve. Required for Block connections without VPN. Costs " +
            "some throughput and battery; ping stops working.",
    ),
}
