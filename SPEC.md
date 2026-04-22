# Product Specification

## Document Status

- Project: Tunguska
- Status: current implementation specification
- Release line: `v0.2.x`
- Source of truth for research background: [deep-research-report (3).md](./deep-research-report%20(3).md)

This document describes the product as it exists now and the boundaries it intentionally keeps.

## Product Goal

Tunguska is an Android VPN client for VLESS + REALITY profiles that prioritizes:

- safe import
- predictable routing
- encrypted local state
- fail-closed runtime behavior
- explicit rejection of exploitable local proxy surfaces
- optional token-gated external automation for orchestrators such as Anubis

## Supported Scope

### Platform

- Android `8.0+`
- `arm64-v8a` shipping builds

### Connection Model

Supported import inputs:

- `vless://` REALITY share links
- `ess://` aliases that normalize to the same VLESS + REALITY model
- canonical JSON `ProfileIr`

Supported protocol subset:

- VLESS outbound
- REALITY security
- TCP transport
- `encryption=none` or omitted
- optional `xtls-rprx-vision` flow
- optional `spx` / `spiderX` REALITY spider path

### Routing model

The supported routing model combines:

- full tunnel
- app allowlist
- app denylist
- regional bypass presets
- explicit direct / proxy / block rules

The shipping preset is `RU First`.

- new and newly imported profiles default to `RU+`
- `RU+` expands to `.ru`, `.su`, `.рф` / `xn--p1ai`, and `geoip:ru`
- existing stored profiles are not silently rewritten; the app asks once whether to enable the preset

### Primary User Flow

The primary product flow is:

1. paste or scan a profile
2. validate and preview it
3. confirm import
4. connect through Android `VpnService`
5. monitor runtime state and health
6. stop cleanly
7. optionally export encrypted backup or redacted diagnostics

An advanced secondary flow also exists:

1. enable automation explicitly
2. rotate or copy the current integration token
3. let an external orchestrator request `START` or `STOP`
4. keep all runtime actions on the same stored profile and Binder-backed runtime path

## Runtime Specification

### Process model

- `:app` process handles UI, storage, import, route preview, and exports
- `:vpnservice` process owns `VpnService`, Binder control, runtime session, listener audit, and watchdog
- an exported no-UI automation relay activity may request start or stop, but it does not expose the runtime control service directly

### Active runtime lane

The active runtime lane for `v0.2.x` is `xray+tun2socks`.

- Android `VpnService` retains ownership of the TUN descriptor
- Xray is packaged from the Linux release lane for the current Android MVP path
- imported share-link profiles default to `SystemDns`; explicit JSON profiles may still carry custom DNS settings
- `geoip.dat` and `geosite.dat` are staged into the runtime workspace from the pinned Xray asset set

Required properties:

- `VpnService` owns the TUN interface
- the internal bridge is loopback-only
- bridge credentials are random per session
- bridge port is random per session
- no enabled management API
- no enabled debug listener

## Security Requirements

### Import

- no unsafe flag downgrades
- no hidden auto-connect on import
- explicit preview before persistence
- rejection of insecure TLS flags, debug endpoints, and compatibility localhost proxy flags in imported profiles

### Routing

- full tunnel, allowlist, and denylist are supported
- `RU First` regional bypass is supported
- loopback must remain local
- route preview must reflect the same policy model used by the runtime
- effective precedence is fixed:
  - split-tunnel package policy
  - explicit `BLOCK`
  - generated regional `DIRECT`
  - explicit `DIRECT` / `PROXY`
  - default action

### Runtime hardening

- Binder is the intended app-to-runtime control plane
- the only supported external automation bridge is an explicit token-gated activity
- runtime listener self-audit must run against the current UID
- only the expected authenticated loopback bridge may be allowed as a local listener in the active lane
- watchdog failure must stop the runtime and tear down the VPN

### Storage and diagnostics

- profile storage must be encrypted
- export artifacts must be encrypted
- redacted diagnostic bundles must avoid raw secret leakage by default
- automation state may be exported only in redacted form; automation tokens must never be exported

## Explicit Non-Goals

The product does not currently target:

- total VPN invisibility
- interface-name randomization such as hiding `tun0`
- a fully hidden subscription-centric product surface
- production store-signing or Play distribution in `v0.2.4`

## Frozen Secondary Surface

Subscription fetch, trust pinning, scheduling, inbox, and notification code still exists in the app. That surface is frozen and secondary.

It must:

- keep compiling
- keep tests passing
- stay outside the primary VPN acceptance path

## Release Acceptance

The release line is considered materially correct when:

- import by paste works
- import by camera QR works
- import by image QR works
- VPN permission flow works
- a real routed client on a physical device shows a different public IP under VPN than without VPN
- the default `RU+` preset keeps Russian destinations direct
- a non-RU public-IP endpoint still goes through the VPN
- stopping the VPN returns the client to the direct IP
- split routing behaves as configured
- listener self-audit and watchdog fail closed on violation
- token-gated automation may start and stop the current stored profile without exposing an unauthenticated control surface
- GitHub Actions build installable APK artifacts and GitHub Releases publish versioned APKs

The stronger security claim still depends on the real-device detector matrix in [docs/mvp-device-validation.md](./docs/mvp-device-validation.md).
