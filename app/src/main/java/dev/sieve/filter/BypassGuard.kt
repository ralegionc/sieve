package dev.sieve.filter

/**
 * The holes a determined user walks through, and what Sieve does about each.
 *
 * A DNS filter is only as good as its monopoly on DNS. Three things break that monopoly:
 *
 *  1. **Encrypted DNS in the browser.** Chrome and Firefox can resolve over HTTPS, skipping the
 *     system resolver entirely. Both bootstrap the provider by hostname, so blocking those
 *     hostnames is enough to make the browser fall back to system DNS. Firefox also honours a
 *     canary domain: answering `use-application-dns.net` with NXDOMAIN turns DoH off by itself.
 *  2. **Hardcoded resolvers.** Some apps query 8.8.8.8 directly rather than the DNS server the
 *     network advertised. Sieve normally routes only its own synthetic addresses into the tunnel,
 *     so those queries sail past. [RESOLVER_IPV4] and [RESOLVER_IPV6] are pulled into the tunnel
 *     in strict mode so they are filtered like everything else.
 *  3. **Private DNS (DoT) in Android settings.** Not fixable from inside the app — the system
 *     resolver talks to it directly on port 853. Sieve detects it and says so.
 */
object BypassGuard {

    /**
     * Hostnames used to reach encrypted-DNS providers. Blocking the bootstrap lookup is what
     * makes a browser give up and fall back to the system resolver.
     */
    val DOH_HOSTS: List<String> = listOf(
        // Firefox canary: NXDOMAIN here disables DoH without any user interaction.
        "use-application-dns.net",

        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "dns.quad9.net",
        "dns9.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.nextdns.io",
        "doh.opendns.com",
        "doh.familyshield.opendns.com",
        "dns.adguard.com",
        "dns.adguard-dns.com",
        "dns-family.adguard.com",
        "doh.cleanbrowsing.org",
        "doh.dns.sb",
        "dns.alidns.com",
        "doh.pub",
        "dot.pub",
        "dns.twnic.tw",
        "odvr.nic.cz",
        "resolver.dnscrypt.info",
        "doh.libredns.gr",
        "dns.digitale-gesellschaft.ch",
        "doh.mullvad.net",
        "dns.mullvad.net",
        "doh.controld.com",
        "freedns.controld.com",
        "dns.oszx.co",
        "doh.tiar.app",
        "jp.tiar.app",
        "dns.decloudus.com",
        "dnsforge.de",
        "doh.ffmuc.net",
    )

    /**
     * Public resolvers worth pulling into the tunnel so an app cannot reach them directly. Not
     * exhaustive — nothing short of routing all traffic could be — but it covers the resolvers
     * apps actually hardcode.
     */
    val RESOLVER_IPV4: List<String> = listOf(
        "8.8.8.8", "8.8.4.4",                     // Google
        "1.1.1.1", "1.0.0.1",                     // Cloudflare
        "1.1.1.2", "1.0.0.2",                     // Cloudflare, malware filtering
        "1.1.1.3", "1.0.0.3",                     // Cloudflare, family filtering
        "9.9.9.9", "149.112.112.112",             // Quad9
        "208.67.222.222", "208.67.220.220",       // OpenDNS
        "208.67.222.123", "208.67.220.123",       // OpenDNS FamilyShield
        "94.140.14.14", "94.140.15.15",           // AdGuard
        "76.76.2.0", "76.76.10.0",                // ControlD
        "185.228.168.9", "185.228.169.9",         // CleanBrowsing
        "76.76.19.19", "76.223.122.150",          // Alternate DNS
        "8.26.56.26", "8.20.247.20",              // Comodo
        "77.88.8.8", "77.88.8.1",                 // Yandex
        "4.2.2.1", "4.2.2.2",                     // Level3
        "64.6.64.6", "64.6.65.6",                 // Verisign
        "45.90.28.0", "45.90.30.0",               // NextDNS
    )

    val RESOLVER_IPV6: List<String> = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844", // Google
        "2606:4700:4700::1111", "2606:4700:4700::1001", // Cloudflare
        "2620:fe::fe", "2620:fe::9",                    // Quad9
        "2620:119:35::35", "2620:119:53::53",           // OpenDNS
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",       // AdGuard
    )

    /** Built once; the guard list is fixed at compile time. */
    val domains: DomainSet by lazy(LazyThreadSafetyMode.PUBLICATION) { DomainSet.of(DOH_HOSTS) }
}
