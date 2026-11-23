# Egress

An Android app that shows which apps on your phone contact which servers, and where in the world those servers are. Works without root.

- **Pulse:** start/stop capture, totals, an activity chart, top destination countries and a live feed.
- **Map:** a vector world map with country outlines and names, tinted by how much you talk to each country, with animated arcs from where you are to every destination city. Pinch to zoom, tap a city to see its apps, domains and network owner, double-tap to reset.
- **Apps:** every app that went online. Open one to see its countries, domains and timeline, or show only that app on the map.
- **Log:** every lookup, newest first, with search. Tap a row to see the IPs and the network owner.
- **Filters:** time (1H / 6H / 24H / 7D / All), app and destination country. The filters are shared across all tabs.

## How it works

Android only lets a normal app see other apps' network activity through the `VpnService` API. Egress uses that API as a narrow **DNS tap** rather than a full tunnel:

1. The VPN routes a single address (`10.111.222.53`) and tells every app to use it as their DNS server. All other traffic goes over your normal network as usual, so Egress can't break or slow your connection.
2. For each DNS query, `ConnectivityManager.getConnectionOwnerUid()` tells us which app asked. This is what ties each lookup to an app.
3. The query is forwarded to your network's real resolver over a protected socket. The answer IPs are recorded, and the answer is passed back to the app.
4. The IPs are geolocated in batches through ip-api.com and drawn on the map. Each IP is looked up only once and then cached.

TTLs are capped (10 s by default, adjustable in Settings). Without the cap, Android's shared DNS cache would hide the second app that resolves the same host.

## Limits

- **You see lookups, not bytes.** You get which app, which domain, which IP, and where. You don't get how much data was sent; that would need a full user-space TCP/IP tunnel. Per-app byte counts could come from `NetworkStatsManager` (needs Usage Access) and can be added later.
- **Location means where the server is, not who owns it.** Big services serve you from CDN edges near you. Expect a lot of activity in or near your own city. The network owner (Cloudflare, Akamai, Meta…) is shown for each destination and is often the more telling detail.
- **Some traffic bypasses the tap:** apps with their own built-in encrypted DNS (DoH/DoQ) and apps that connect to hard-coded IPs. If **Settings → Network → Private DNS** is set to a *specific hostname*, almost everything bypasses it, and the app warns you. *Automatic* or *Off* both work.
- **Only one VPN can run at a time** on Android. Egress can't run alongside a real VPN.
- **Geolocation uses ip-api.com's free tier.** It's for non-commercial use, works over HTTP only and allows 15 batch requests a minute. Only bare IPs are sent. To keep everything on the device, replace `GeoResolver` with an offline MaxMind/DB-IP `.mmdb` reader.

## Install

Requires Android 10 or newer (API 29+). Download `egress-1.0.apk` from the [latest release](../../releases/latest) and sideload it. Application ID: `com.anthonyessaye.egress`.

## Build

**From your phone (GitHub Actions):** push this folder to a GitHub repo. The included workflow builds `app-release.apk`. Open the run's page in the Actions tab, download the `egress-apk` artifact, unzip it and install it. Each CI run signs with a new debug key, so uninstall the old version before installing a new build (or commit a fixed keystore).

**Android Studio:** open the folder, let Gradle sync, then Run. On the command line: `./gradlew assembleRelease` (R8-minified, debug-signed). Tests: `./gradlew testReleaseUnitTest`.

Stack: Kotlin 2.0, Jetpack Compose (Material 3), AGP 8.7, plain SQLite (WAL) and no other third-party libraries. Fonts are Space Grotesk, Inter and JetBrains Mono (SIL OFL, licences included). Map and IP data: see Data sources below.

## Layout

```
vpn/EgressVpnService.kt   DNS-tap VPN, upstream forwarding, per-app attribution
vpn/Packets.kt, Dns.kt    IPv4/UDP/TCP + DNS wire parsing (pure Kotlin, JVM-tested)
data/Repository.kt        SQLite schema use, lookup merging, all filtered queries
data/GeoResolver.kt       ip-api batch geolocation + "you are here"
data/AppResolver.kt       UID → app name / icon
ui/components/WorldMap.kt country map, arcs, gestures (shapes from assets/countries.bin)
ui/screens/*              Pulse, Map, Apps (+ detail), Log, Settings
```

## Data sources

- Country outlines: [Natural Earth](https://www.naturalearthdata.com) 1:50m (public domain), `tools/build_countries.py`.
- IP→country for blocking: [IP to Country Lite by DB-IP](https://db-ip.com) (CC BY 4.0), `tools/build_ipdb.py`. Refresh monthly by re-running the script.
