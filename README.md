# Tunguska

Tunguska is an Android VPN client for VLESS + REALITY profiles. It focuses on safe profile import, explicit routing policy, encrypted local state, a selectable embedded runtime strategy, and fail-closed behavior when runtime assumptions are violated.

The checked-in Android app version is `0.4.0`.

## Product Shape

The app is organized around four top-level sections:

- Home: protection state, active profile, routing summary, connect/disconnect, and current or exit IP.
- Profiles: one sealed active profile plus import/replace flow.
- Routing: traffic policy, Russia direct regional bypass, custom direct domains, and offline route test.
- Security: security posture, backup export, audit export, automation, and advanced diagnostics.

Technical controls remain available, but they are no longer the primary screen. Runtime strategy selection, restage, refresh runtime, storage details, build surface, and tunnel plan live under Security -> Advanced diagnostics.

The finished Advanced diagnostics slice is organized around four cards in order: Runtime controls, Strategy capability summary, Runtime lane summary, and Runtime internals. Capability rows with limits expand their technical note inline from the yellow chip, and the runtime lane summary info action scrolls back to the capability matrix instead of opening a duplicate popup.

## Supported Platform

- Android `8.0+` (`minSdk 26`)
- Build target SDK `36`
- Primary sideload ABI: `arm64-v8a`
- Local emulator ABI: `x86_64`

## Supported Profile Inputs

Tunguska accepts:

- `vless://` REALITY share links
- `ess://` links when they normalize to the same VLESS + REALITY model
- canonical JSON `ProfileIr`

Supported protocol subset:

- VLESS outbound
- TCP transport
- REALITY security
- `encryption=none` or omitted
- required `sni` / `serverName`
- required `pbk` / `publicKey`
- required `sid` / `shortId`
- optional `flow=xtls-rprx-vision`
- optional `spx` / `spiderX`, preserved as shared outbound semantics

Unsupported URI parameters are warnings unless they would weaken the security model. Unsafe import flags, debug endpoints, and compatibility localhost proxy settings are rejected.

## Import Flow

Import is always staged:

1. Paste, scan camera QR, or scan an image QR.
2. Validate locally.
3. Review the normalized profile.
4. Confirm import before storage changes.

The app does not auto-connect after import. Importing and connecting are separate user decisions.

## Runtime Architecture

Tunguska uses a split app/runtime design:

- `:app`: Compose UI, import, encrypted storage, route test, exports, automation relay.
- `:vpnservice`: Android `VpnService`, Binder control, runtime session, health monitoring, exposure check, and egress IP probe.

The user-facing runtime selector supports:

- `XRAY_TUN2SOCKS`: xray plus tun2socks runtime.
- `SINGBOX_EMBEDDED`: sing-box/libbox embedded runtime.

Both lanes consume the same stored profile and routing model. The selector changes the runtime implementation; it does not create a second profile store or a separate automation path.

## sing-box Dependency

`libbox-android` is consumed as a Maven dependency:

- group: `io.acionyx.thirdparty`
- artifact: `libbox-android`
- version: see `gradle/libs.versions.toml`
- default remote: GitHub Packages for this repository
- local override: `.tmp/maven`

The refresh script can rebuild the pinned AAR from upstream sing-box and publish to local Maven, GitHub Packages, or both:

```powershell
.\tools\runtime\fetch-singbox-embedded.ps1 -PublishTarget LocalMaven
.\tools\runtime\fetch-singbox-embedded.ps1 -PublishTarget GitHubPackages
.\tools\runtime\fetch-singbox-embedded.ps1 -PublishTarget Both
```

The sing-box lane also stages `vpnservice/src/main/assets/singbox/rule-set/geoip-ru.srs` for GeoIP-based Russia direct behavior.

## Routing

Tunguska supports:

- full tunnel
- app allowlist
- app denylist
- generated regional direct rules
- explicit direct/proxy/block rules

Russia direct is the built-in regional preset. It covers Russian domain zones and Russian GeoIP rules, including `.ru`, `.su`, Cyrillic RF TLD via `xn--p1ai`, and `geoip:ru`.

Rule precedence:

1. Android per-app VPN policy.
2. Explicit `BLOCK`.
3. Generated regional `DIRECT`.
4. Explicit `DIRECT` / `PROXY`.
5. Default route.

Custom direct domains are suffix rules. Adding `example.com` applies to `example.com` and subdomains such as `api.example.com`.

Route test is an offline policy simulation. It does not send traffic and does not require the VPN to be connected. Its job is to answer whether a draft destination would use the VPN, go direct, or be blocked by the current policy.

## Security Posture

Tunguska treats these as security-critical:

- no unauthenticated localhost proxy surface
- no enabled xray or sing-box management API
- no debug listener in release paths
- no raw profile secrets in diagnostics
- fail-closed behavior on real runtime violations
- explicit token gating for automation

The runtime exposure check has two confidence levels:

- Full listener audit when Android allows process socket inventory reads.
- Limited topology check when Android SELinux restricts `/proc/net` socket inventory for app-sandboxed VPN processes.

Limited does not mean the app is broken. It means Android blocked low-level socket enumeration, so Tunguska reports declared runtime topology instead of pretending the audit fully ran.

## Egress IP Display

Home shows:

- current external IP while idle
- exit IP while connected
- detecting state while probes are running
- a clear fallback state when all probes fail

Connected exit-IP checks are routed through the active engine path where supported so the UI reports the tunnel egress rather than the app process's direct network path.

## Exports

Security exports are local and explicit:

- encrypted full-profile backup
- encrypted redacted diagnostic/audit bundle

After a successful export, Tunguska launches Android's share sheet with a `FileProvider` content URI. Backup and audit export states are independent; clicking one export cannot mutate the other card's status.

## Automation

Automation is opt-in and intended for orchestrators such as Anubis:

- disabled by default
- token-gated
- rotatable token
- explicit exported activity, not an open broadcast receiver
- uses the same stored profile and Binder-backed runtime path as the UI

See [docs/anubis-integration.md](./docs/anubis-integration.md).

## Build From Source

Configure GitHub Packages access for `libbox-android`, unless `.tmp/maven` already contains a local override:

```properties
# ~/.gradle/gradle.properties
githubPackagesUser=YOUR_GITHUB_USERNAME
githubPackagesToken=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Environment variables also work:

```powershell
$env:GITHUB_PACKAGES_USER = "YOUR_GITHUB_USERNAME"
$env:GITHUB_PACKAGES_TOKEN = "YOUR_CLASSIC_PAT_WITH_READ_PACKAGES"
```

Typical local validation:

```powershell
.\gradlew.bat :vpnservice:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
```

Build local installable artifacts:

```powershell
.\tools\release\build-internal.ps1
```

## Test Strategy

Inner-loop checks:

- unit tests for domain, compiler, runtime, and export behavior
- Compose/instrumentation tests for UI routing, export, security, and runtime controls
- focused headed emulator proofs for Chrome/IP and selected UI flows

Pre-commit or pre-release gates:

- both runtime lanes for Chrome/IP smoke
- joint Tunguska + Anubis E2E for `XRAY_TUN2SOCKS` and `SINGBOX_EMBEDDED`
- physical-device validation when changing runtime, routing, or security claims

Do not run the full Anubis proof as a routine inner-loop check. It is intentionally heavier than focused proofs.

## Repository Guide

- [SPEC.md](./SPEC.md): current implementation specification.
- [THREAT_MODEL.md](./THREAT_MODEL.md): threat model and residual risks.
- [PRIVACY.md](./PRIVACY.md): privacy and local data handling.
- [SECURITY.md](./SECURITY.md): vulnerability reporting policy.
- [docs/e2e-testing.md](./docs/e2e-testing.md): emulator and Anubis validation guide.
- [docs/device-validation.md](./docs/device-validation.md): physical-device validation matrix.
- [docs/anubis-integration.md](./docs/anubis-integration.md): automation contract.
- [docs/release-process.md](./docs/release-process.md): versioning and GitHub Release process.
