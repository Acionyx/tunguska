# Threat Model

## Scope

This document describes the current Tunguska threat model for the `v0.1.x` product line.

The active product path is:

- import a VLESS + REALITY profile
- validate and persist it locally
- connect through Android `VpnService`
- enforce split routing and local-loopback preservation
- detect forbidden runtime exposure and fail closed on error

## Adversaries

The main assumed adversaries are:

- a hostile co-installed Android app on the same device
- a local network or upstream observer trying to infer or interfere with VPN use
- a malicious or misconfigured profile payload
- a regression in the runtime that opens an unexpected local surface or leaks traffic

The model does not assume protection against a fully compromised OS, kernel malware, or a rooted attacker with full device control.

## Assets To Protect

- imported server details and profile secrets
- split-routing intent and per-app inclusion or exclusion
- VPN egress identity
- diagnostic material and export files
- release integrity and build provenance

## Primary Security Goals

- prevent unauthenticated or debug-manageable localhost exposure
- prevent obvious split-tunnel bypass through the app's own local runtime surfaces
- keep loopback local
- keep profile material encrypted at rest
- keep diagnostics redacted by default
- fail closed when runtime assumptions are violated

## Non-Goals

Tunguska does not currently claim:

- invisibility from Android VPN detection
- disappearance of `tun0` or equivalent interface visibility
- stealth against a fully compromised device

Generic VPN visibility is not treated as a release blocker unless it also proves a practical bypass or leak.

## Trust Boundaries

- `:app` process: UI, import, storage, route preview, export
- `:vpnservice` process: `VpnService`, runtime engine, listener audit, watchdog
- Binder: only intended app-to-runtime control channel
- loopback: treated as hostile shared device surface unless explicitly authenticated and audited

## Implemented Controls

### Process isolation

- The VPN runtime and Binder control service run in the isolated `:vpn` process.
- Runtime control is not exposed through exported Android components.

### Import hardening

- Share links and JSON profiles are validated before storage.
- Import is staged and previewed before confirmation.
- Unsafe flags such as insecure TLS toggles, compatibility localhost proxy mode, and debug endpoints are rejected at import time.

### Runtime lane

- The active lane is `xray+tun2socks`.
- The bridge is restricted to `127.0.0.1`.
- The bridge uses a per-session random port and credentials.
- Management APIs and debug listeners are not enabled in the active lane.

### Split routing

- Split routing is enforced through `VpnService.Builder` package policy.
- Full tunnel, allowlist, and denylist are modeled explicitly.
- Loopback preservation is treated as part of the routing contract.

### Listener self-audit

- Tunguska reads `/proc/net/tcp` and `/proc/net/tcp6`.
- It filters listeners to the current app UID.
- It allows only the runtime's expected authenticated loopback bridge.
- Any other loopback or wildcard listener is treated as a failure condition.

### Watchdog and fail-closed

- Runtime health is checked while connected.
- Health failure stops the session and tears down the VPN.
- Listener-audit failure also tears down the VPN.

### Local data protection

- Profile storage is encrypted in app-private storage.
- Export artifacts are encrypted.
- Diagnostic bundles are redacted by default.

## Residual Risks

These risks are known and currently accepted or still under validation:

- The active runtime still uses an internal authenticated loopback bridge instead of a pure no-loopback data plane.
- The full detector matrix has not yet been completed on physical hardware, even though functional tunneled traffic has already been confirmed on a real phone.
- Subscription and notification code still exists in the app process, even though it is not the primary product path.
- The current public release is a sideload release, not a final store-distributed production channel.

## Release Blockers

The following still block a stronger security claim:

- any unauthenticated localhost listener in the active runtime lane
- any reachable Xray or sing-box management API
- confirmed split-tunnel bypass on a real device
- confirmed underlying-network leak while VPN is active
- real-device detector findings that demonstrate a practical bypass rather than generic VPN visibility

## Validation Reference

The detector and device validation process is documented in [docs/mvp-device-validation.md](./docs/mvp-device-validation.md).
