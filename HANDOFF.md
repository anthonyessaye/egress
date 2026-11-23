# Handoff: Egress (Android network traffic mapper)

Status as of 2026-11-23. Written by the Claude session that built the first version. Read this before changing anything.

## 1. The most important fact

**v1.0 builds, passes its tests, and runs on the user's own phone and an API 36 emulator.** Release 1.0 (versionCode 100) ships as `com.anthonyessaye.egress`. The Kotlin package and namespace stay `app.egress`. The release build is R8-minified (APK ~4.6 MB, down from 43 MB), and the minified build was re-verified on the emulator: capture, geo, map, DNS sinking and route blackholing all work. What was verified:

| Part | Status | How |
|---|---|---|
| `vpn/Packets.kt`, `vpn/Dns.kt` | Verified | Compiled with kotlinc 2.0.21 and run on the JVM: IP/UDP/TCP checksums verify, DNS query/answer parsing and in-place TTL capping are correct (checked against `dnslib`), and 200k random inputs didn't crash it |
| `GeoResolver.isPublic` | Verified | JVM test: private, CGNAT, ULA, link-local, 198.18/15 and garbage inputs all rejected |
| All SQL in `data/Repository.kt` | Verified | Every query run against the real schema in SQLite with synthetic data |
| `assets/countries.bin` | Verified | Decoded by a Python reader and rendered to SVG; shapes correct |
| Everything else (Android and Compose) | **Builds and runs** | `assembleRelease` green (2026-11-23). Exercised on an API 36 emulator: capture, DNS still resolving, geo, map, Pulse sheets and Log filters all work |
| Per-app attribution | **Works on emulator** | API 36 emulator showed lookups correctly credited to Google, Play services and Shell. User reports it works on their own phone |

**Status:** the user tested v1.0 on their own phone and reported it works. The detailed on-device checks in §5 are still useful after any change to capture or attribution.

## 2. What the user wants

A personal Android app, sideloaded rather than published on Play, that:
- monitors the phone's network traffic,
- maps where it goes,
- filters by **app**, **time** and **geo location**,
- has **very modern visuals**.

Personal working notes for whoever maintains this live in `NOTES.local.md` (gitignored), if present.

## 3. Architecture decisions, and why

**DNS-tap VPN, not a full packet tunnel.** `EgressVpnService` routes only `10.111.222.53/32` into the TUN interface and sets it as every app's DNS server. All real traffic bypasses the tunnel.
- Why: forwarding *all* traffic needs a user-space TCP/IP stack (NetGuard/PCAPdroid-style native code). That's fragile, can break connectivity, and couldn't be tested here.
- Cost: **no byte counts**. The app sees app → domain → IPs → geo, not volume. The user was told this.
- How it works: each UDP/53 packet is parsed, the owning app's UID comes from `ConnectivityManager.getConnectionOwnerUid()` (API 29+, VPN apps only), and the query is forwarded upstream over a `protect()`ed socket. Then the TTLs in the response are capped, the IPv4/UDP response is rebuilt and written back to the TUN, and `(uid, domain, ips)` is recorded.
- TCP SYNs to the resolver get an immediate RST, so Android's opportunistic Private DNS (DoT on port 853) probe and DNS-over-TCP fail fast instead of hanging.
- The app itself is excluded with `addDisallowedApplication`, so its geo lookups and default-network callback use the real network.
- `setMetered(false)` is set; without it Android marks the whole device metered while the VPN is up.
- MTU is 8192, so large EDNS responses don't need fragmenting.

**Country blocking (added 2026-11-23; the user accepted breakage).** It has two layers and is enforced only while capture runs:
- *DNS:* A/AAAA answers whose address is in a blocked country are rewritten **in place** to a sinkhole (`10.111.222.66` / `fd65:6772:6573:7300::66`), with the TTL cut to 5 s (`Dns.sinkAddresses`). In-place rewriting keeps DNS name-compression pointers valid. The lookup row gets `blocked=1` (DB v2).
- *Routes:* the blocked countries' largest prefixes go into the tunnel (`IpCountryDb.plan`, weighted by each country's address share, about 25% of the budget for IPv6). Anything arriving in the tunnel that isn't DNS gets a TCP reset (`Packets.buildTcpReset`, IPv4 and IPv6, RFC 793) or, for UDP, is dropped. The tunnel is re-established live when the block list changes.
- IP→country comes from the offline DB-IP Lite (`assets/ipcountry.bin`, built by `tools/build_ipdb.py`, CC BY 4.0, attributed in Settings). It's a different source from ip-api.com, which the map uses, so they occasionally disagree (e.g. qq.com: the map says HK, blocking says CN).
- **Route limit, measured on an API 36 emulator:** ≤1,500 routes is clean. 2,000–3,000 still blocks, but Android's NetworkMonitor can't receive the config, so the VPN is shown as not validated. At 5,000 Android silently installs *no* routes at all. Default 1.5k; 2.5k is offered as "aggressive". At 1.5k, coverage is about 98% of China's IPv4 space and about 60% of Russia's. Smaller prefixes rely on DNS.
- Bypasses: apps with hard-coded IPs outside the routed prefixes, or with their own DoH, when the destination is a small prefix. Our own app is excluded from the VPN.
- With nothing blocked, the VPN calls `allowFamily(AF_INET6)`. Without it, a VPN that configures no IPv6 blocks IPv6 for every app (from the Android docs; not separately verified).

**TTL cap (default 10 s, Settings).** The system DNS cache in netd is shared across apps: app B's lookup of a name app A just resolved never reaches the tap. Shorter TTLs mean more lookups reach the tap and get attributed.

**Lookup merging.** A, AAAA and HTTPS queries from the same uid and domain within 3 s become one `lookup` row (`Repository.insertLookup`). This is done on the single writer thread.

**Geo via ip-api.com batch.** Free tier, HTTP only (a cleartext exception is in `network_security_config.xml`), up to 100 IPs per request and 15 requests per minute. The code honours the `X-Rl`/`X-Ttl` headers. Results are cached forever in the `geo` table. "Home" (where the arcs start) is the location of the phone's own public IP, refreshed every 3 h. Don't hard-code it, because the phone travels. The pluggable alternative is an offline DB-IP/MaxMind `.mmdb` reader; the user may want this for privacy.

**No Room, Retrofit or map SDK.** Plain `SQLiteOpenHelper` (WAL), `HttpURLConnection` and `org.json`. The map is a custom Compose `Canvas` that draws Natural Earth 1:50m country outlines (public domain) from `assets/countries.bin` (~130 KB, built by `tools/build_countries.py`) on an equirectangular projection (`MapSpace`), with no tiles, API keys or network. Countries with destinations are tinted by lookup count; names are placed greedily as you zoom. The countries are drawn on a static layer, and the arcs and pulses on a separate animated canvas, so the coastline isn't re-rendered every frame. The old `LandMask` dot grid was removed because the user couldn't tell the countries apart. This was deliberate, to minimise build risk and dependencies. Keep it that way unless there's a strong reason.

## 4. Code map

```
EgressApp.kt              Application singleton: Prefs, AppResolver, Repository, GeoResolver (launched in appScope)
vpn/EgressVpnService.kt   The DNS tap (see §3). Foreground service, type specialUse
vpn/Packets.kt, Dns.kt    Pure Kotlin wire parsing. Keep them Android-free so they stay JVM-testable
vpn/CaptureState.kt       StateFlows the UI observes: running, privateDnsStrict, lastError
data/Db.kt                Schema: app(uid,pkg,label), lookup(id,ts,uid,domain,ok), answer(lookup_id,ip), geo(ip,ok,cc,country,city,lat,lon,org,asn,ts)
                          geo.ok: 1 located, 0 failed (retried after 6 h), -1 private/unroutable
data/Repository.kt        Single-thread writer, plus every read query. Reads run on Dispatchers.IO via rememberQuery()
                          `changes` StateFlow is bumped per write; MainViewModel.tick samples it every 1.5 s
data/GeoResolver.kt       Batch geolocation loop + home location
data/AppResolver.kt       UID → label/pkg/icon. Special UIDs (-1, 0, 1000, 1051 …) mapped by hand
data/Prefs.kt             ttlCap, retentionDays, home
ui/MainActivity.kt        Edge-to-edge, VPN consent flow (rememberCaptureToggle), floating pill nav bar
ui/MainViewModel.kt       Shared Filter (time range, uids, countries), current tab, app detail, selected map cluster
ui/theme/Theme.kt         Palette (dark, cyan #3CF0FF → violet #8A5CFF gradient accent), fonts, Type scale
ui/components/            GlassCard, Pill, StatTile, MeterBar, AppIcon, FilterBar + SelectSheet, ActivityChart, WorldMap, CountryShapes (+ MapSpace)
vpn/IpCountryDb.kt        Offline IP→country lookup + route planning (pure Kotlin)
ui/screens/               Overview ("Pulse"), Map, Apps (+ detail), Activity ("Log", with Log-only city/provider/status filters), BreakdownSheet (Pulse tile drill-downs), SettingsSheet
.github/workflows/build.yml  Builds the release APK on push (debug-signed) and uploads it as an artifact
```

Versions: AGP 8.7.3, Gradle 8.11.1 (wrapper), Kotlin 2.0.21 with the compose compiler plugin, Compose BOM 2024.12.01, compileSdk/targetSdk 35, minSdk 29, JDK 17. If the installed Android Studio requires newer AGP, upgrading is fine. Keep Kotlin and the compose plugin versions identical to each other.

## 5. Do these first, in order

1. **Make it compile.** These are the likeliest problem spots (all guesses, none confirmed):
   - Compose API signatures I wrote from memory: `ModalBottomSheet(scrimColor=…)`, `AnimatedContent(contentKey=…)`, `TextMeasurer.measure(String, TextStyle)`, `DrawScope.drawText(layout, topLeft=…)`, `animate(0f, 1f, animationSpec=…) { v, _ -> }`.
   - `Path.quadraticBezierTo` is deprecated in Compose 1.7 (warning only; `quadraticTo` replaces it).
   - Local functions that capture state inside `WorldMap` (`project`, `clamp`, `centeredOn`) and are used from `pointerInput` lambdas keyed on `viewSize`.
   - `rememberQuery` in `Components.kt` (`produceState` with vararg keys + `rememberUpdatedState`).
   Fix what the compiler reports. Don't restructure.
2. **Install on a real phone (Android 10+) and check per-app attribution. This is the single biggest risk.** Start capture, open Chrome and then Instagram (or any two apps), and look at the Log tab.
   - Expected: rows labelled with the real app names. The assumption is that Android's DnsResolver `fchown`s its query sockets to the requesting app's UID, so `getConnectionOwnerUid` returns that app. (Likely, but unconfirmed.)
   - If most rows say **"DNS resolver"** (UID 1051), **"Android System"** or **"Unattributed"** (-1), attribution is broken. Report that to the user before building anything else. The fallback is a real decision for the user to make: a full tunnel that observes TCP SYN/UDP 5-tuples per app (much more work), or accepting system-level attribution.
3. **Check that DNS still works for every app while capture runs**, including after switching Wi-Fi ↔ mobile data and with Private DNS on Automatic. If name resolution stalls for ~5 s on first use, the opportunistic-DoT RST path isn't working.
4. **Check that geo fills in** within ~10 s and the Map shows arcs from the user's current city.
5. **Watch battery and smoothness.** The map runs an infinite animation. It should stop when the tab isn't visible (`AnimatedContent` disposes it), but confirm.

## 6. Known gaps and backlog (ask the user before starting any of these)

- **Per-app data volume** via `NetworkStatsManager` (needs the Usage Access special permission). This is the agreed next feature; it was offered to the user.
- Offline geolocation (`.mmdb`) instead of ip-api.com.
- CI signing: each GitHub Actions run generates a new debug key, so updates need an uninstall first. Fix with a committed keystore or GitHub secrets.
- CSV export of the log (FileProvider + share sheet).
- Notification shows static text. It could show a live lookup count.
- Map arcs don't wrap across the antimeridian (Pacific destinations draw the long way round).
- Apps using their own DoH/DoQ, or hard-coded IPs, are invisible by design. Strict Private DNS bypasses the tap; the UI warns about it.

## 7. Conventions

- Keep `Packets.kt` and `Dns.kt` free of Android imports. If you change them, rerun the JVM test approach: compile with kotlinc together with a small `main()` harness.
- All DB writes go through `Repository`'s single writer executor. Reads are only ever called inside `rememberQuery { }` (IO dispatcher).
- The filter is global (`MainViewModel.filter`) and every screen honours it. The Apps list ignores the app filter on purpose.
- Visual language: near-black background `#06080D`, glass cards (vertical gradient + 1 dp gradient border, 24 dp corners), a violet → cyan accent gradient, and Magenta marking the selection. Fonts: Space Grotesk for headings and numbers, Inter for body text, JetBrains Mono for IPs, domains and times. Keep new UI consistent with `GlassCard`, `Pill`, `Type.*` and `Palette.*`, and don't introduce default Material look-and-feel.
- Be honest in the UI copy: these are *lookups*, not connections or bytes.
