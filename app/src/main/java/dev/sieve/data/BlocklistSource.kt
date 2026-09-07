package dev.sieve.data

/**
 * A remote list of domains to block.
 *
 * All of the bundled sources come from StevenBlack/hosts, which publishes single-category
 * "-only" variants. Keeping the categories separate means enabling adult-content filtering does
 * not silently also block social media.
 */
data class BlocklistSource(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val enabledByDefault: Boolean,
) {
    companion object {
        val BUNDLED: List<BlocklistSource> = listOf(
            BlocklistSource(
                id = "adult",
                title = "Adult content",
                description = "Pornography and adult sites. The main list — roughly 60,000 domains.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
                enabledByDefault = true,
            ),
            BlocklistSource(
                id = "gambling",
                title = "Gambling",
                description = "Casinos, sportsbooks and betting sites.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
                enabledByDefault = false,
            ),
            BlocklistSource(
                id = "social",
                title = "Social media",
                description = "Major social networks. Blunt — it takes out the sites entirely.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts",
                enabledByDefault = false,
            ),
            BlocklistSource(
                id = "ads",
                title = "Ads and trackers",
                description = "The StevenBlack base list. Not content filtering, but it pairs well.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                enabledByDefault = false,
            ),
        )

        fun byId(id: String): BlocklistSource? = BUNDLED.firstOrNull { it.id == id }

        val DEFAULT_ENABLED_IDS: Set<String> =
            BUNDLED.filter { it.enabledByDefault }.map { it.id }.toSet()
    }
}
