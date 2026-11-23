# Egress — notes for Claude Code

Personal, sideloaded Android app (Kotlin, Jetpack Compose, minSdk 29). It maps which app contacts which server and where. **Read `HANDOFF.md` first.** It covers current status, what is unverified, and the on-device test plan.

## Commands
- Build: `./gradlew assembleRelease` (debug-signed release; output in `app/build/outputs/apk/release/`)
- Install: `adb install -r app/build/outputs/apk/release/app-release.apk`
- Logs: `adb logcat -s EgressVpn EgressRepo`

## Rules
- The capture engine is a **DNS-only tap** (`vpn/EgressVpnService.kt`). Country blocking adds sinkholed DNS answers and blackhole routes, but nothing except DNS is ever *forwarded*: TCP gets a reset, UDP is dropped. Don't turn it into a full tunnel without the user's explicit go-ahead.
- Route budget ceiling is `MAX_ROUTE_BUDGET` in `data/Prefs.kt`, measured on device. Read its comment before raising it.
- Tests: `./gradlew testReleaseUnitTest` (JVM tests for `Packets`, `Dns`, `IpCountryDb`).
- `vpn/Packets.kt`, `vpn/Dns.kt` and `vpn/IpCountryDb.kt` stay pure Kotlin (no `android.*`) so they remain JVM-testable.
- DB writes go only through `Repository`'s writer thread. UI reads go through `rememberQuery {}`.
- No new heavy dependencies (Room, Retrofit, map SDKs) unless asked. Reuse `GlassCard`, `Pill`, `Palette`, `Type` for UI.
- UI copy says "lookups", never "connections" or "traffic volume".
