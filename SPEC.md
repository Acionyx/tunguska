# Product Specification

## Status

- Project: Tunguska
- Scope: current implementation specification
- App version in this checkout: `0.4.0`
- Runtime lanes: `XRAY_TUN2SOCKS` and `SINGBOX_EMBEDDED`

This document describes the product behavior the code should preserve. Historical research notes and UI plans are intentionally not source-of-truth documents.

## Product Goal

Tunguska is an Android VPN client for VLESS + REALITY profiles that prioritizes:

- safe local import
- one clear active profile
- predictable routing decisions
- user-visible runtime strategy selection
- encrypted local state
- fail-closed runtime behavior
- no unauthenticated local proxy or management surface
- optional token-gated automation for external orchestration

## User-Facing Information Architecture

The app has four top-level sections.

### Home

Home is the daily-use screen. It shows:

- protection hero
- active profile row
- routing row
- connect/disconnect action
- current external IP while idle
- exit IP while connected
- detecting/fallback copy when IP probing is unresolved

Home must not show server endpoint, raw runtime internals, manual refresh controls, or duplicate state labels.

### Profiles

Profiles manages the single active sealed profile. It shows:

- profile name and type
- sealed/verified state
- replace/import action
- lower-emphasis technical details such as endpoint, SNI, and hashes

It must not imply a multi-profile library until that feature exists.

### Routing

Routing explains and edits traffic policy. It shows:

- current split-routing mode
- Russia direct regional bypass
- custom direct domains
- dynamic direct-domain list derived from effective policy
- route test

Route test is an offline policy simulation. It computes the current policy result for draft inputs only after the user presses `Test route`. It must explicitly report one of:

- would use VPN
- would go direct
- would be blocked

It is valid while the VPN is disconnected because it does not perform network traffic.

### Security

Security contains trust posture and operational surfaces:

- runtime/security posture
- backup export
- redacted audit export
- automation
- advanced diagnostics

Advanced diagnostics is the correct location for runtime strategy selection, refresh runtime, restage, runtime internals, storage, build surface, and tunnel plan.

The completed diagnostics flow is the ordered card sequence Runtime controls -> Strategy capability summary -> Runtime lane summary -> Runtime internals. Capability rows with non-green support states reveal their technical note inline from the status chip, and the runtime lane summary info affordance must bring the capability summary card into view instead of opening a generic duplicate dialog.

## Supported Platform

- Android `8.0+` (`minSdk 26`)
- target SDK `36`
- `arm64-v8a` physical-device builds
- `x86_64` emulator builds

## Profile Semantics

Supported inputs:

- `vless://` REALITY share links
- `ess://` aliases that normalize to the same VLESS + REALITY model
- canonical JSON `ProfileIr`

Supported outbound subset:

- VLESS
- REALITY
- TCP
- `encryption=none` or omitted
- required server name/SNI
- required REALITY public key
- required REALITY short ID
- optional VLESS `flow=xtls-rprx-vision`
- optional REALITY spider path from `spx` or `spiderX`

If a profile omits a REALITY spider path, the normalized shared model must use the same effective default across both runtime engines. This is profile semantics, not UI behavior.

## Runtime Model

The runtime selector is a normal user-facing strategy selection:

- `XRAY_TUN2SOCKS`
- `SINGBOX_EMBEDDED`

Both strategies must:

- consume the same sealed profile
- preserve shared VLESS + REALITY semantics
- receive the same routing intent
- report status through the same Binder/runtime contract
- use the same automation path
- expose no management API
- fail closed on real runtime violations

`SINGBOX_EMBEDDED` uses the pinned `libbox-android` Maven dependency and staged sing-box runtime assets. It must not depend on a manually committed random AAR as the source of truth.

## Routing Model

Supported policy components:

- full tunnel
- app allowlist
- app denylist
- explicit direct/proxy/block rules
- generated regional direct rules

Russia direct is the built-in regional preset. It covers:

- `.ru`
- `.su`
- Cyrillic RF TLD via `xn--p1ai`
- `geoip:ru`

Custom direct domains are suffix rules. `example.com` covers `example.com` and all subdomains.

Effective precedence:

1. Android per-app VPN policy.
2. Explicit `BLOCK`.
3. Generated regional `DIRECT`.
4. Explicit `DIRECT` / `PROXY`.
5. Default route.

The direct-domain list shown in the UI must be derived from effective policy, not hardcoded static copy.

## Security Requirements

### Import

- Validate before storage.
- Preview before confirmation.
- Do not auto-connect after import.
- Reject insecure flags, debug endpoints, and compatibility localhost proxy settings.
- Preserve supported REALITY query parameters exactly where they affect runtime behavior.

### Runtime

- `VpnService` owns the Android VPN session.
- Binder is the app-to-runtime control plane.
- The runtime control service is not exported.
- Any external automation request must go through the token-gated relay.
- Management APIs and debug listeners are disabled.
- Runtime health failure tears down the session.
- Listener/exposure audit failure tears down the session when a real forbidden exposure is detected.

### Android Socket Inventory

On some Android builds, SELinux denies app-sandboxed VPN processes access to `/proc/net/tcp*` and `/proc/net/udp*`. Tunguska must not present that as a broken product state.

Expected behavior:

- If socket inventory is readable, run the listener audit and fail closed on forbidden listeners.
- If socket inventory is restricted, report a limited exposure check and show declared runtime topology.
- Do not elevate limited audit confidence into a full security pass.

### Storage And Export

- Store profiles encrypted in app-private storage.
- Store automation status encrypted in app-private storage.
- Export backups as encrypted full-profile envelopes.
- Export audits as encrypted redacted bundles.
- Do not export automation tokens.
- Do not dump raw profile secrets into plaintext diagnostics.

## Automation Contract

Automation is optional and disabled by default.

Supported external actions:

- `io.acionyx.tunguska.action.AUTOMATION_START`
- `io.acionyx.tunguska.action.AUTOMATION_STOP`

Required extra:

- `automation_token`

Optional extra:

- `caller_hint`

Automation must use the current sealed profile and current selected runtime strategy. It must fail explicitly if VPN permission is missing.

## Non-Goals

Tunguska does not currently claim:

- VPN invisibility
- hidden Android VPN transport
- hidden `tun0`-style interface visibility
- production Play distribution
- a multi-profile library
- a general split-tunnel package editor
- a subscription-first UX

Generic VPN visibility is not a defect unless it demonstrates a practical bypass or leak.

## Release Acceptance

A release is materially correct when:

- paste import works
- camera QR import works
- image QR import works
- profile review and confirmation work
- both runtime strategies compile and start for supported profiles
- Chrome or a helper app shows a tunneled public IP while connected
- stopping restores direct public IP
- route test reports VPN/direct/block outcomes clearly
- Russia direct and custom direct domains affect routing policy as documented
- backup and audit exports create files and launch the share sheet
- automation can start/stop the current profile when enabled and token-authenticated
- invalid automation requests fail closed
- no unauthenticated local proxy or management API is exposed

Physical-device validation remains required for stronger runtime/security claims. See [docs/device-validation.md](./docs/device-validation.md).
