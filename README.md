# Tunguska

Tunguska is an Android VPN client for VLESS + REALITY profiles with a security-first runtime, staged profile import, per-app split routing, and fail-closed behavior.

Current release: `v0.2.1`

## Product Scope

Tunguska currently focuses on one path:

- import a profile from a share link or QR code
- validate and preview the normalized profile
- save it into encrypted local storage
- obtain Android VPN permission
- connect, monitor runtime health, and stop cleanly
- export encrypted backup or redacted diagnostics when needed

The app contains subscription and notification code from earlier iterations, but that surface is frozen and secondary. It is not the primary product path.

## First Screen

The first screen is intentionally product-first:

- primary status and `Connect` / `Stop` actions are visible before secondary controls
- staged import, QR scan, and diagnostics stay on one screen
- advanced and frozen secondary surfaces are still available, but collapsed out of the default path
- compact layout rules keep the first screen usable down to `320dp` width without broken wrapping or clipped primary actions

## Supported Platform

- Android `8.0+` (`minSdk 26`)
- Device ABI: `arm64-v8a`
- Emulator ABI: `x86_64`

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
- the runtime uses a loopback-only authenticated SOCKS bridge as an internal implementation detail
- the bridge binds only to `127.0.0.1`
- the bridge uses a random high port and random credentials per session
- management APIs, debug endpoints, and pprof-style listeners are not enabled in the active lane

`libbox` is still bundled as a secondary comparison lane, but it is not the active runtime used for the current release.

## Functional Validation

Tunguska does not treat `RUNNING` as success on its own.

Current proof for `v0.2.1`:

- headed emulator smoke imports a real VLESS + REALITY share link
- the Android VPN permission dialog is completed through UI Automator
- Chrome shows one public IP without VPN and a different public IP with VPN enabled
- stopping the VPN returns Chrome to the direct IP
- a separate helper app proves full-tunnel, allowlist, and denylist behavior
- real-device testing has already confirmed live tunnel traffic instead of a zero-byte fake connect

## Split Routing

Tunguska supports three routing modes:

- full tunnel
- allowlist
- denylist

Split routing is enforced through `VpnService.Builder` package policy, not only through UI state. The app package itself is excluded from the tunnel when required by the runtime lane. Loopback traffic is kept local by design.

The UI also exposes a deterministic route preview so the user can inspect how a destination would be handled before connecting.

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

## Security Properties

Current security properties in the shipped code:

- no telemetry by default
- no cleartext traffic allowed by app network policy
- no unauthenticated localhost proxy in the active runtime lane
- no enabled Xray or sing-box management API in the active runtime lane
- no deep-link import path enabled by default
- no release-path claim of VPN invisibility

## Current Limitations

Tunguska `v0.2.1` is a real sideload release, but it still has clear limits.

- The current release is not Play-signed or store-distributed.
- The active runtime uses an authenticated internal loopback bridge rather than a pure no-loopback embedded transport.
- The full physical-device detector matrix is still pending. Functional traffic has been confirmed on a real phone, and headed emulator smoke now proves direct-vs-VPN IP change plus split-routing parity, but the detector suite is not fully closed yet.
- Generic VPN visibility such as `TRANSPORT_VPN`, foreground notification presence, and `tun0` visibility is not treated as a defect by itself.

## Releases

Installable APKs are available from GitHub Releases and from GitHub Actions artifacts.

- GitHub release assets are published for version tags such as `v0.2.1`
- the main sideload artifact is `tunguska-vX.Y.Z-internal.apk`
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

- [tools/release/build-internal.ps1](/C:/src/tunguska/tools/release/build-internal.ps1)

## Repository Guide

- [SPEC.md](./SPEC.md): current product specification
- [THREAT_MODEL.md](./THREAT_MODEL.md): threat model and residual risks
- [PRIVACY.md](./PRIVACY.md): privacy and local data handling
- [SECURITY.md](./SECURITY.md): vulnerability reporting policy
- [docs/mvp-device-validation.md](./docs/mvp-device-validation.md): detector and real-device validation matrix
- [docs/release-process.md](./docs/release-process.md): versioning and GitHub Release process
