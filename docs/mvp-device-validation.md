# MVP Device Validation

This document defines the real-device validation matrix for Tunguska `v0.2.x`.

## Current Status

- Release exists: `v0.2.3`
- Headed emulator harness covers import, UI flow, stop, automation control-path, split-routing helper flows, and artifact capture
- Headed emulator joint Tunguska + Anubis orchestration has been proven for the control path with a real VLESS + REALITY share link passed through CLI or environment, not committed into the repo
- Functional real-device traffic and public-IP change have been confirmed
- Full detector-backed validation on a physical device is still pending

## Active Runtime Under Test

- active runtime: `xray+tun2socks`
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
7. With the default `RU+` preset enabled, open a Russian destination in Chrome and confirm it stays direct.
8. Open a non-RU public-IP endpoint and confirm it still goes through the VPN.
9. Validate full tunnel, allowlist, and denylist behavior with at least one excluded app and one routed app.
10. Confirm loopback stays local.
11. Run `RKNHardering` and confirm no bypass-grade findings.
12. Run `VPN-Detector` and treat generic VPN visibility as informational unless it demonstrates a practical leak.
13. Run `per-app-split-bypass-poc` and confirm no usable unauthenticated localhost proxy is exposed.
14. Run a Termux-style excluded-app public-IP check and capture whether tunnel egress can be reproduced.
15. Export a redacted diagnostic bundle and attach it to the validation report.

## Automation And Anubis Validation

If external orchestration is in scope, validate it separately after the standalone Tunguska path is already green.

1. Enable automation in Tunguska and copy the current token.
2. Confirm Tunguska still rejects invalid-token and disabled-integration requests.
3. Configure Anubis with the Tunguska package and token.
4. Confirm Anubis unfreezes Tunguska before `START`.
5. Confirm Anubis re-freezes Tunguska after confirmed `STOP`.
6. Confirm a real device still shows different public IPs before and after the orchestrated VPN startup.
7. Confirm stopping through Anubis returns the device to the direct IP.
8. Keep the real VLESS test fixture outside git and pass it through CLI or `TUNGUSKA_REAL_SHARE_LINK`.

## Acceptance Notes

- The product does not claim VPN invisibility.
- The product does claim that bypass-grade local attack surfaces are the real blocker.
- The headed emulator is useful for UI and control-path debugging, but the authoritative IP proof for the current arm64-only shipping runtime remains a physical-device check.
- If `xray+tun2socks` passes this matrix, it remains the primary runtime for the next release line.
