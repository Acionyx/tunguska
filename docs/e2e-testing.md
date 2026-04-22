# E2E Testing

This document describes the local emulator validation surface for Tunguska and the canonical joint Tunguska + Anubis proof kept in this repository.

## Scope

The local E2E surface is split on purpose:

- `app/src/androidTest`: Tunguska-only instrumentation covering import, connect, stop, automation contract, Chrome IP proof, and split-routing proof
- `jointtesthost/src/androidTest`: neutral host for cross-app Tunguska + Anubis orchestration
- `trafficprobe`: helper app used to prove direct-vs-tunneled public IP behavior
- `tools/emulator`: emulator lifecycle, Chrome bootstrap, UI dumps, and diagnostics collection
- `tools/integration/run-anubis-e2e.ps1`: full joint Tunguska + Anubis headed-emulator run

The neutral host keeps the cross-app proof out of the production app package and makes the Anubis handoff easier to follow.

## Privacy Rules

- Keep real VLESS or REALITY fixtures outside git.
- Pass live fixtures through CLI arguments or `TUNGUSKA_REAL_SHARE_LINK`.
- Do not commit raw diagnostics from `logs/`.
- Share redacted exports or scrubbed logs when reporting issues outside the repo.
- Tracked tests use synthetic example profile data only.

## Environment

The PowerShell helpers now resolve their paths dynamically:

- Android SDK: `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `%LOCALAPPDATA%\Android\Sdk`
- Java: `JAVA_HOME`, or a standard `%ProgramFiles%\Java\jdk-24` install
- Anubis repo for the joint flow: `-AnubisRepo`, or a sibling checkout at `../anubis`

Required local pieces:

- a configured emulator AVD such as `tunguska-api34`
- Chrome in the system image or `tools/browser/chrome.apk`
- Shizuku, which the helper can install on demand
- a local Anubis checkout when running the joint proof

## Tunguska-Only Smoke

Run the local Tunguska smoke suite with a real share link:

```powershell
$env:TUNGUSKA_REAL_SHARE_LINK = "<real share link>"
.\tools\emulator\run-vpn-smoke.ps1
```

This flow starts the emulator, ensures Chrome is available, installs Tunguska plus the helper app, runs the app instrumentation, and pulls diagnostics into `logs/`.

The default smoke suite includes `io.acionyx.tunguska.app.RegionalBypassProofTest`, which validates the visible Regional Bypass controls end to end through the app UI.

## Regional Bypass Local Proof

The local emulator proof covers the user-visible Regional Bypass behavior that Tunguska documents:

- new imports default to `Russia direct`
- `.ru`, `.su`, and `.рф` hosts preview as `DIRECT` when Russia direct is enabled
- non-matching hosts keep the normal routing default while the preview shows the runtime `geoip:ru` hint
- custom `Always direct domains` preview as `DIRECT`
- disabling `Russia direct` returns Russian host previews to the normal routing default

This local proof intentionally validates the feature through the same controls shown on the first screen rather than only through unit tests.

The authoritative dataplane check for runtime `geoip:ru` classification still remains the physical-device validation step in [docs/mvp-device-validation.md](./mvp-device-validation.md), because the emulator proof exercises the preview and profile state, not a Russian public-IP endpoint.

## Joint Tunguska Plus Anubis Proof

The canonical joint proof is `io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest` hosted by `jointtesthost`.

Run it with:

```powershell
$env:TUNGUSKA_REAL_SHARE_LINK = "<real share link>"
.\tools\integration\run-anubis-e2e.ps1 -AnubisRepo ..\anubis
```

That runner performs this sequence for both embedded Tunguska engines, `XRAY_TUN2SOCKS` and `SINGBOX_EMBEDDED`:

1. boots a headed emulator
2. ensures Chrome and Shizuku are ready
3. installs Tunguska, `trafficprobe`, and `jointtesthost`
4. installs Anubis from the local checkout
5. prepares the Tunguska automation fixture with the live share link and the selected runtime lane
6. runs the neutral-host joint instrumentation for that lane
7. repeats fixture plus joint instrumentation for the other embedded lane
8. pulls Tunguska and joint-host diagnostics into `logs/`

The acceptance bar is:

- Tunguska remains frozen while idle under Anubis control
- Anubis unfreezes Tunguska before automation start
- Tunguska reaches `RUNNING`
- the helper app observes a tunneled public IP different from the direct baseline
- Anubis stops Tunguska cleanly
- Tunguska returns to `IDLE`
- the direct public IP is restored
- Anubis freezes Tunguska again after shutdown

## Diagnostics

The helpers write local artifacts under `logs/`, including:

- `tunguska-smoke-*`
- `anubis-smoke-*`
- `ui-latest.xml`

These artifacts are intentionally git-ignored because they can contain device-local details, runtime state, and public IP observations from live validation.

## Sharing With The Anubis Author

When sharing this setup outside the repo, send:

- [docs/anubis-integration.md](./anubis-integration.md)
- [jointtesthost/README.md](../jointtesthost/README.md)
- [tools/integration/run-anubis-e2e.ps1](../tools/integration/run-anubis-e2e.ps1)

That combination shows the automation contract, the neutral-host structure, and the exact local runner without exposing a committed live fixture.
