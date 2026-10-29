# Sieve

An Android content filter that works at the DNS layer. No root, no accessibility service, no
traffic proxying, no account, and nothing leaves the phone.

Sieve runs a local `VpnService` that carries **only DNS**. When an app looks up a domain, the
lookup is checked against your blocklists: a hit is answered locally with `NXDOMAIN` and never
reaches the network, and a miss is forwarded to your normal resolver and the reply copied back
verbatim. Because everything except DNS takes its ordinary path, there is no throughput cost and
no measurable battery drain.

Two modes of use. Out of the box it is friction you can switch off in two taps. With **strict
mode** and a **lock** enabled it closes the encrypted-DNS bypasses and puts a waiting period or a
passphrase between you and every change that weakens it. See
[Making it hard to bypass](#making-it-hard-to-bypass).

## What it does

- **Category blocklists** from [StevenBlack/hosts](https://github.com/StevenBlack/hosts), split so
  categories are independent: adult content (on by default), gambling, social media, ads/trackers.
- **Your own rules.** Always-block and never-block lists. An allow rule beats every downloaded
  list, which is how you undo a false positive. Subdomains are covered automatically — blocking
  `example.com` also blocks `cdn.example.com`.
- **Activity view** of the last 250 lookups, held in memory only, never written to disk.
- **Choice of upstream resolver** — network default, Quad9, Cloudflare, Google, or your own.
- **Quick Settings tile**, optional start-on-boot, and a daily list refresh on unmetered Wi-Fi.
- **Strict mode** blocks encrypted-DNS providers and pulls hardcoded resolvers into the filter.
- **Locks** — a waiting period, or a passphrase you hand to somebody else — covering everything
  that weakens the filter, not just the off switch.
- **Uninstall protection** via device admin, and full device-owner lockdown if you want it.
- **Full-tunnel mode** that routes and proxies everything, so Android's "Block connections without
  VPN" leaves the phone working instead of offline.

## Building

Needs JDK 17+ and the Android SDK (compileSdk 36). The Gradle wrapper handles the rest.

```bash
./gradlew :app:assembleDebug
```

Install onto a connected phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the app, tap **Update now** on the Lists tab to fetch the blocklists, and **Turn on**.
Android shows its own VPN consent dialog the first time; only you can accept it.

`./gradlew test` runs the unit suite — 58 tests covering DNS wire format, IPv4/IPv6 framing and
checksums, TCP segment framing and 32-bit sequence arithmetic across the wraparound, domain
matching, hosts-file parsing, the on-disk index, lock-state transitions, passphrase handling, and
the bypass guard.

`./gradlew :app:assembleRelease` produces an unsigned ~1.5 MB APK; add a `signingConfig` before
distributing it anywhere.

## How it is put together

```
dns/       IpPackets      IPv4/IPv6 + UDP parsing and framing, with checksums
           DnsMessage     question parsing and NXDOMAIN synthesis
net/       TunnelEngine   reader, selector and writer threads
           TcpForwarder   userspace TCP termination and relay
           UdpForwarder   per-flow UDP relay with DNS filtering
           TcpSegment     TCP framing and sequence arithmetic
           DnsOverTcp     length-prefixed DNS on port 53
filter/    DomainSet      sorted hash index — 8 bytes per domain, binary-search lookup
           FilterEngine   guard > allow rules > block rules > downloaded lists
           BypassGuard    DoH hostnames and public resolvers to capture
           BlocklistRepository  download, parse, cache
           QueryLog       in-memory ring buffer and counters
vpn/       SieveVpnService  the tunnel and the packet pumps
           DeviceAdmin      uninstall protection and device-owner policy
data/      SieveSettings  DataStore-backed preferences
           Lock           lock modes and unlock-state arithmetic
ui/        Compose screens
```

Two design notes worth knowing:

**Two modes, one engine.** DNS-only mode advertises the real resolvers and routes just those
`/32`s into the tunnel, so nothing else is touched. Full mode routes `0.0.0.0/0` and `::/0` and
proxies every connection. Both run the same forwarder; only the route set differs. Upstream
sockets are `protect()`ed so they bypass the tunnel and cannot loop.

**Userspace TCP, but not a whole TCP stack.** Full mode has to terminate TCP: the app completes a
handshake with Sieve, and Sieve runs a separate ordinary socket to the real destination. The link
between the app and the forwarder is the tun interface — local, lossless, in-order — which removes
the two hardest parts of TCP, retransmission and congestion control, and leaves sequence
accounting, flow control and the state machine. Loss on the real network is handled by the kernel
socket on the far side, which is a complete TCP. Duplicates are still possible when our ACK is
slow, so segments at or below the expected sequence number are dropped and re-acknowledged.

**Domains are stored as hashes, not strings.** A `HashSet` of 150k domain strings costs tens of
megabytes held for the whole life of the service. A sorted `LongArray` of 64-bit FNV-1a hashes
costs 8 bytes each — about 1.2 MB — and a lookup is an allocation-free binary search. The queried
name is already known to the caller, so nothing is lost by not keeping the strings. A hash
collision would cause a false block; at 64 bits over ~10^6 entries the odds are roughly 1 in 10^7,
and the allowlist is checked first, so it is recoverable.

## Making it hard to bypass

Nothing on a phone you own is literally foolproof — you can always factory reset it. The goal is
to put more steps between the impulse and the bypass than the impulse will survive. These are in
order of how much they buy you.

### 1. Strict mode (in the app, on by default)

Without it, the filter has three holes wide enough to walk through:

| Hole | What strict mode does |
| --- | --- |
| Chrome and Firefox resolving over HTTPS, skipping the system resolver entirely | Blocks the DoH providers' bootstrap hostnames, so the browser falls back to system DNS. Answering `use-application-dns.net` with NXDOMAIN makes Firefox disable DoH by itself. |
| Apps querying `8.8.8.8` directly instead of the advertised DNS server | Routes ~35 public resolver addresses into the tunnel, filters them, then forwards to the address originally asked for. Nothing breaks; it is only inspected. |
| An allow rule for `dns.google` re-opening the first hole | The guard is checked *before* allow rules and cannot be overridden. |

The guard list is in [`BypassGuard.kt`](app/src/main/java/dev/sieve/filter/BypassGuard.kt) — add to
it if you find a provider it misses.

### 2. A lock (in the app)

**Waiting period** — turning the filter off, disabling a list, adding an allow rule, shortening the
wait or removing the lock all require requesting an unlock and then waiting. The countdown is
written to disk, so closing the app, force-stopping it and rebooting do not skip it. Fifteen
minutes is enough to outlast most impulses; several hours is enough to outlast nearly all of them.

**Passphrase** — the app generates one and makes you type it back, then stores only its SHA-256
hash. Send it to someone you trust and you genuinely cannot undo the filter alone. This is the
single most effective measure here, and it is social rather than technical.

Either way the gate covers every change that *weakens* the filter. Tightening — turning it on,
enabling a list, adding a block rule, lengthening the wait — is always free. Locking only the off
switch would be theatre when allowlisting `.com` achieves the same thing.

While locked, Sieve also re-establishes the tunnel if it is revoked from Android's VPN settings.

### 3. Full tunnel + always-on VPN with lockdown

This is the setup that actually holds.

Switch **Coverage** to **Full tunnel** in Settings, then turn on
**Settings › Network & internet › VPN › ⚙ Sieve › Always-on VPN**, and
**Block connections without VPN** underneath it.

With lockdown on, Android drops any packet that is not going through the VPN. Since full mode
routes `0.0.0.0/0` and `::/0`, that is everything — so the phone works normally while Sieve is
running, and has no network at all when it is not. Force-stopping the app, rebooting, or killing
it from the task switcher all leave you offline rather than unfiltered.

Lockdown does **not** work in DNS-only mode: that tunnel routes only the resolvers, so every other
packet has no route through the VPN and gets dropped. The app will not let you believe otherwise —
switching to full mode is what unlocks the instruction.

What full mode costs: every packet is proxied through userspace, so expect a throughput ceiling in
the tens of Mbps rather than whatever your link can do, and more battery use under sustained
transfer. ICMP is not proxied, so `ping` stops working; nothing else depends on it.

### 4. Uninstall protection (in the app)

Granting device admin means Android refuses to uninstall Sieve until you deactivate it in Settings
first, and the deactivation dialog says what it costs. Removing device admin is itself gated behind
the lock.

### 5. Device owner (adb, requires a factory reset)

The strongest thing Android offers without root, and the only level at which deleting the app is
genuinely prevented rather than merely slowed down.

It can only be granted on a device with no accounts configured, so in practice: factory reset, skip
the Google account at setup, enable USB debugging, install the APK, then

```bash
adb shell dpm set-device-owner dev.sieve/.vpn.SieveDeviceAdminReceiver
```

Open Sieve, go to Settings, and press **Enforce** on the Uninstall protection card. That applies:

- `setUninstallBlocked` — the uninstall option is refused everywhere, including Play Store and
  a long-press on the icon.
- `DISALLOW_CONFIG_VPN` — VPN settings cannot be changed, so always-on and lockdown cannot be
  switched off.
- `setAlwaysOnVpnPackage` — Sieve is pinned as the always-on VPN, with lockdown enabled
  automatically when you are in full-tunnel mode.

The optional switch underneath adds `DISALLOW_APPS_CONTROL` and `DISALLOW_SAFE_BOOT`, which close
the last two holes — clearing the app's storage to reset the lock, force-stopping it, and booting
into safe mode where third-party apps do not run. The catch is that `DISALLOW_APPS_CONTROL` is
device-wide: you lose force-stop and clear-data for *every* app, not just this one. That is why it
is a separate choice.

Two restrictions are deliberately **not** applied: `DISALLOW_FACTORY_RESET` and
`DISALLOW_DEBUGGING_FEATURES`. Together they would remove every way back out, and a phone you
cannot recover is a worse outcome than a filter you can eventually defeat. So adb stays as the
escape hatch:

```bash
adb shell dpm remove-active-admin dev.sieve/.vpn.SieveDeviceAdminReceiver
```

### What still gets through

Be honest with yourself about these rather than assuming the app has them covered:

- **Private DNS** (Settings › Network & internet › Private DNS) resolves over TLS on port 853. In
  DNS-only mode that traffic never enters the tunnel. In full mode it does, but it is encrypted end
  to end, so it can only be refused wholesale — which would break name resolution rather than
  filter it. Sieve detects it and warns on the Status tab; set it to Off or Automatic. Device owner
  can forbid changing it.
- **DoH to a hardcoded IP.** Strict mode blocks the bootstrap hostnames, which is enough for every
  browser that resolves its provider by name. An app that ships the provider's IP address and
  speaks HTTPS to it directly is indistinguishable from ordinary web traffic, in either mode.
- **Clearing app storage** resets the lock and the waiting period, and **force-stopping** the app
  stops filtering until it is opened again. Both are closed by the "Also block clearing data and
  force-stop" switch, which is device-owner only and applies device-wide rather than to Sieve
  alone. Without device owner, neither can be prevented.
- **A second device**, a desktop browser, or a factory reset defeats anything configured here.
  Router-level DNS filtering covers the home network; nothing covers mobile data on another phone.
- **Domain granularity.** A site that mixes content cannot be partly filtered.
- **One VPN at a time.** Installing another VPN app takes the slot. While locked Sieve tries to
  reclaim it, but cannot if the other app holds consent.

## Privacy

There is no server. Blocklists are fetched directly from GitHub over HTTPS; nothing else leaves
the device. Blocked lookups never reach the network at all. The activity log is in memory and is
cleared when the process stops. Passphrases are stored as a SHA-256 hash. Traffic counters, your
rules and the lock state are the only things persisted.
