# MVP Device Validation

This document defines the real-device validation matrix for Tunguska `v0.1.x`.

## Current Status

- Release exists: `v0.2.1`
- Headed emulator smoke proves import, connect, Chrome public-IP change, stop, and split-routing parity with a helper app
- Functional real-device traffic has been confirmed
- Full detector-backed validation on a physical device is still pending

## Active Runtime Under Test

- active runtime: `xray+tun2socks`
- comparison runtime: `libbox`
- product success metric: `import or scan -> preview -> confirm -> connect -> Chrome IP changes -> traffic passes -> stop -> direct IP returns`

## Blocking Findings

Any of the following blocks a stronger release claim:

- open unauthenticated localhost SOCKS or HTTP proxy
- reachable Xray API, HandlerService, sing-box management API, pprof, or similar debug or control endpoint
- confirmed split-tunnel bypass where an excluded app can obtain tunnel egress
- confirmed underlying-network leak while VPN is active
- excluded-app or Termux reproduction of tunnel egress through the VPN network or `--interface tun0`
- runtime start, reconnect, or stop failing in normal device use

## Non-Blocking Findings

These do not block release by themselves:

- `TRANSPORT_VPN`
- foreground notification visibility
- active tunnel interface visibility such as `tun0`
- generic route, MTU, or interface heuristics that only prove a VPN exists

## Required Tools

Prepare and validate against:

- `https://github.com/xtclovver/RKNHardering`
- `https://github.com/cherepavel/VPN-Detector`
- `https://github.com/runetfreedom/per-app-split-bypass-poc`
- a Termux-style excluded-app public-IP check

Helper scripts:

- [prepare-detector-workspace.ps1](/C:/src/tunguska/tools/mvp/prepare-detector-workspace.ps1)
- [new-validation-report.ps1](/C:/src/tunguska/tools/mvp/new-validation-report.ps1)

## Validation Flow

1. Install Tunguska plus detector apps on a physical Android device.
2. Import a real `vless://` or `ess://` REALITY profile by paste.
3. Import the same profile by camera QR.
4. Import the same profile by image QR.
5. Save the validated profile and obtain `VpnService` permission.
6. Connect, confirm Chrome or another routed client moves to the VPN egress IP, disconnect, and reconnect.
7. Validate full tunnel, allowlist, and denylist behavior with at least one excluded app and one routed app.
8. Confirm loopback stays local.
9. Run `RKNHardering` and confirm no bypass-grade findings.
10. Run `VPN-Detector` and treat generic VPN visibility as informational unless it demonstrates a practical leak.
11. Run `per-app-split-bypass-poc` and confirm no usable unauthenticated localhost proxy is exposed.
12. Run a Termux-style excluded-app public-IP check and capture whether tunnel egress can be reproduced.
13. Export a redacted diagnostic bundle and attach it to the validation report.

## Acceptance Notes

- The product does not claim VPN invisibility.
- The product does claim that bypass-grade local attack surfaces are the real blocker.
- If `xray+tun2socks` passes this matrix, it remains the primary runtime for the next release line.
- If `libbox` later proves materially better on the same matrix, it can replace the active lane without changing profile storage or Binder contracts.
