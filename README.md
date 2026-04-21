# Tunguska

Tunguska is an Android VPN client for VLESS + REALITY profiles with a security-first runtime, staged profile import, per-app split routing, and fail-closed behavior.


## Product Scope

Tunguska currently focuses on one path:

- import a profile from a share link or QR code
- validate and preview the normalized profile
- save it into encrypted local storage
- keep routing intent explicit, including regional bypass defaults
- obtain Android VPN permission
- connect, monitor runtime health, and stop cleanly
- export encrypted backup or redacted diagnostics when needed
- optionally expose a token-gated automation bridge for external orchestrators such as Anubis

The app contains subscription and notification code from earlier iterations, but that surface is frozen and secondary. It is not the primary product path.

## First Screen

The first screen is intentionally product-first:

- primary status and `Connect` / `Stop` actions are visible before secondary controls
- staged import, QR scan, and diagnostics stay on one screen
- advanced and frozen secondary surfaces are still available, but collapsed out of the default path
- compact layout rules keep the first screen usable down to `320dp` width without broken wrapping or clipped primary actions

## Supported Platform

- Android `8.0+` (`minSdk 26`)
- Published device ABI: `arm64-v8a`
- Published emulator ABI: `x86_64`

## Supported Connection Formats

Tunguska currently accepts:

- `vless://` REALITY share links
- `ess://` share links when they map cleanly to the same VLESS + REALITY model
- canonical JSON `ProfileIr` imports

The accepted runtime subset is intentionally narrow:

- transport: `tcp`
- security: `reality`
- encryption: omitted or `none`
- `sni` or `serverName` is required
- `pbk` or `publicKey` is required
- `sid` or `shortId` is required
- `flow=xtls-rprx-vision` is accepted when present

Unsupported extra URI parameters are treated as warnings and ignored. Imports are staged first, then explicitly confirmed by the user before being written to storage.

## Import And QR Flow

The first screen is centered on import and connection control.

- Manual paste: user pastes a share link or JSON profile, validates it, reviews the normalized preview, then confirms import.
- Camera QR: live scan uses CameraX plus on-device ML Kit barcode decoding.
- Image QR: the app uses the Android photo picker and decodes locally, without broad storage permissions.

The app does not auto-connect after import. Import and connection remain separate actions.

## Runtime Architecture

Tunguska uses a split app/runtime design.

- `:app` process: UI, encrypted storage, profile import, QR handling, route preview, redacted exports
- `:vpnservice` isolated process: Binder control service, `VpnService`, runtime session, health monitoring, listener audit

The active MVP runtime lane is `xray+tun2socks`.

- Android `VpnService` owns the TUN interface
- the packaged Xray executable comes from the Linux release lane because it carries real traffic reliably in the Android app sandbox for the current MVP path
- the runtime uses a loopback-only authenticated TCP-only SOCKS bridge as an internal implementation detail
- the bridge binds only to `127.0.0.1`
- the bridge uses a random high port and random credentials per session
- UDP association is disabled on the bridge
- management APIs, debug endpoints, and pprof-style listeners are not enabled in the active lane
- `geoip.dat` and `geosite.dat` are staged into the runtime workspace from the pinned Xray asset set

## Functional Validation

Tunguska does not treat `RUNNING` as success on its own.

Current proof for `v0.2.4`:

- a headed emulator harness covers import, UI flow, stop, automation control-path, screenshot capture, UI hierarchy capture, and filtered diagnostics
- the Android VPN permission dialog is completed through UI Automator in the local harness
- a separate helper app proves full-tunnel, allowlist, and denylist policy behavior in the local test rig
- the neutral-host `jointtesthost` harness proves the combined Tunguska + Anubis control path `freeze -> start Tunguska -> stop -> refreeze`
- real-device testing has confirmed both live tunnel traffic and a different public IP with VPN enabled than without VPN

The emulator remains useful for UI, orchestration, and dataplane debugging through the dedicated `x86_64` emulator build, while the primary sideload artifact for real devices remains `arm64-v8a`.

## Split Routing

Tunguska supports three routing modes:

- full tunnel
- allowlist
- denylist

Split routing is enforced through `VpnService.Builder` package policy, not only through UI state. The app package itself is excluded from the tunnel when required by the runtime lane. Loopback traffic is kept local by design.

The UI also exposes a deterministic route preview so the user can inspect how a destination would be handled before connecting.

## Regional Bypass

Tunguska also exposes a higher-level regional bypass layer above the ordinary route-rule list.

- shipping scope in the current release is `RU First`
- new and newly imported profiles default to `Russia direct`
- existing encrypted profiles are not silently changed; the app asks once whether to enable the preset

The default `RU+` preset expands to:

- `.ru`
- `.su`
- `.рф` / `xn--p1ai`
- `geoip:ru`

Rule precedence is fixed:

- split-tunnel package policy
- explicit `BLOCK`
- generated regional `DIRECT`
- explicit `DIRECT` / `PROXY`
- default action

This keeps the behavior predictable:

- Russian destinations stay direct by default
- explicit block rules can still override them
- explicit direct/proxy rules still work after the regional layer

The first screen keeps the UX simple:

- one `Russia direct` switch
- one `Configure` action for advanced settings

The advanced area adds:

- `Always direct domains`
- a route preview for host or IP input
- a short explanation of precedence

When a result depends on runtime geodata rather than a plain hostname suffix, the preview shows an explicit runtime hint instead of pretending it has already resolved destination IP classification ahead of time.

## DNS Behavior

Profiles imported from `vless://` or `ess://` share links default to `SystemDns` unless the user imports an explicit JSON profile with a different DNS policy.

- this avoids broken DoH-over-IP defaults such as `https://1.1.1.1/dns-query`
- legacy stored profiles using the old built-in DoH-over-IP defaults are migrated on load to `SystemDns`
- custom JSON profiles can still carry explicit encrypted DNS settings

## Self-Audit And Fail-Closed Behavior

The runtime continuously checks that its own security assumptions still hold.

### Listener self-audit

The listener auditor reads `/proc/net/tcp` and `/proc/net/tcp6`, filters sockets to the current app UID, and rejects forbidden listeners.

Allowed case:

- the single authenticated loopback bridge expected for the active runtime session

Forbidden cases:

- unexpected loopback listeners
- wildcard listeners
- any management or debug exposure reachable from outside the intended private runtime path

If a forbidden listener is detected, Tunguska fails closed and tears down the VPN session.

### Session watchdog

The runtime also probes engine health while connected.

- healthy session: runtime remains active
- failed health probe: runtime is stopped and the VPN is torn down

This keeps the product aligned with the core rule: connection failures should not silently degrade into a leaky or ambiguous state.

## Storage And Diagnostics

Profiles and artifacts are stored in app-private encrypted form.

- profile storage: encrypted `ProfileIr`
- backup export: encrypted full-profile envelope
- diagnostic export: redacted encrypted bundle with hashes, runtime status, storage state, and route-preview context

Diagnostics are designed to be useful without dumping raw secrets into user-visible files.

Regional bypass state is included in redacted form:

- enabled preset ids
- custom direct-domain count
- generated regional-rule count
- route-preview runtime dataset hints

Automation state is also included in redacted form:

- whether external automation is enabled
- whether `VpnService` permission is already satisfied
- last automation status and error
- last caller hint
- encrypted app-private status storage

The automation token itself is not exported.

## Automation Integration

Tunguska also exposes an opt-in automation path intended for orchestrators such as Anubis.

- the API is disabled by default
- the entrypoint is an explicit exported activity, not an open broadcast receiver
- every request requires the current automation token
- the token is rotatable and stored in encrypted app-private storage
- the token is not written into diagnostic exports
- the app must already have Android VPN permission before external automation can start the runtime

Operationally, the automation bridge always uses the current sealed Tunguska profile. It does not introduce a second profile slot or a separate runtime configuration path.

The current shell contract is:

- `io.acionyx.tunguska.action.AUTOMATION_START`
- `io.acionyx.tunguska.action.AUTOMATION_STOP`
- required extra: `automation_token`

Detailed setup notes are in [docs/anubis-integration.md](./docs/anubis-integration.md).

## Security Properties

Current security properties in the shipped code:

- no telemetry by default
- no cleartext traffic allowed by app network policy
- no unauthenticated localhost proxy in the active runtime lane
- no enabled Xray or sing-box management API in the active runtime lane
- no open broadcast control surface for external automation
- exported automation control is opt-in and token-gated
- automation status metadata is sealed into encrypted app-private storage
- no deep-link import path enabled by default
- no release-path claim of VPN invisibility
- no silent migration of existing stored profiles into `RU direct`

## Current Limitations

- The current release is not Play-signed or store-distributed.
- The active runtime uses an authenticated internal loopback bridge rather than a pure no-loopback embedded transport.
- Server-side Xray blocking is only complementary. If the client intentionally routes a destination direct, the server will never see that traffic.
- The full physical-device detector matrix is still pending. Functional traffic has been confirmed on a real phone, and local headed-emulator dataplane proof now uses the dedicated `x86_64` emulator APK rather than ARM translation.
- Generic VPN visibility such as `TRANSPORT_VPN`, foreground notification presence, and `tun0` visibility is not treated as a defect by itself.

## Releases

Installable APKs are available from GitHub Releases and from GitHub Actions artifacts.

- GitHub release assets are published for version tags such as `v0.2.4`
- the main device sideload artifact is `tunguska-vX.Y.Z-arm64-v8a-internal.apk`
- the emulator test artifact is `tunguska-vX.Y.Z-x86_64-emulator-internal.apk`
- a matching `.sha256` file is published for each APK

Release page:

- `https://github.com/Acionyx/tunguska/releases`

The release process is documented in [docs/release-process.md](./docs/release-process.md).

## Build From Source

Typical local build:

```powershell
.\gradlew.bat :vpnservice:testDebugUnitTest :app:testDebugUnitTest :app:assembleInternal --no-configuration-cache
```

Local sideload package helper:

- [tools/release/build-internal.ps1](./tools/release/build-internal.ps1)

## Repository Guide

- [SPEC.md](./SPEC.md): current product specification
- [THREAT_MODEL.md](./THREAT_MODEL.md): threat model and residual risks
- [PRIVACY.md](./PRIVACY.md): privacy and local data handling
- [SECURITY.md](./SECURITY.md): vulnerability reporting policy
- [docs/e2e-testing.md](./docs/e2e-testing.md): local emulator, diagnostics, and joint Tunguska + Anubis validation guide
- [docs/mvp-device-validation.md](./docs/mvp-device-validation.md): detector and real-device validation matrix
- [docs/anubis-integration.md](./docs/anubis-integration.md): token-gated automation setup for Anubis-style orchestration
- [docs/release-process.md](./docs/release-process.md): versioning and GitHub Release process
