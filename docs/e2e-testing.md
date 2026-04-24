# E2E Testing

This document describes Tunguska's local validation strategy.

## Test Tiers

Use the smallest tier that proves the change.

1. Compile and unit tests for domain/compiler/runtime/export changes.
2. Focused app instrumentation for UI behavior.
3. Focused headed emulator smoke for Chrome/IP and runtime behavior.
4. Joint Tunguska + Anubis E2E before committing runtime/automation changes and before releases.
5. Physical-device validation for stronger runtime/security claims.

Do not run the joint Anubis proof as a normal inner-loop check.

## Local Modules

- `app/src/androidTest`: Tunguska-only UI and runtime instrumentation.
- `jointtesthost/src/androidTest`: neutral host for Tunguska + Anubis orchestration.
- `trafficprobe`: helper app for direct-vs-tunneled public IP checks.
- `tools/emulator`: emulator startup, Chrome bootstrap, UI dumps, diagnostics.
- `tools/integration/run-anubis-e2e.ps1`: full joint Anubis proof.

## Privacy Rules

- Keep real VLESS/REALITY fixtures outside git.
- Prefer pre-staged emulator settings or environment variables over command-line secrets.
- Do not commit raw `logs/` artifacts.
- Share only redacted exports or scrubbed logs outside the repo.
- Tracked tests must use synthetic example profile data unless they explicitly read a local live fixture.

## Environment

PowerShell helpers resolve:

- Android SDK from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, or standard local SDK location.
- Java from `JAVA_HOME` or local helper resolution.
- Anubis checkout from `-AnubisRepo` or sibling `../anubis`.

Required local pieces:

- configured AVD such as `tunguska-api34`
- Chrome in system image or `tools/browser/chrome.apk`
- Shizuku for Anubis flows
- local Anubis checkout for joint proof
- GitHub Packages credentials or `.tmp/maven` for `libbox-android`

## Inner-Loop Commands

Compile and unit tests:

```powershell
.\gradlew.bat :vpnservice:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
```

Focused routing/security UI proofs:

```powershell
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain
adb shell am instrument -w -e class io.acionyx.tunguska.app.RegionalBypassProofTest,io.acionyx.tunguska.app.SecurityExportProofTest,io.acionyx.tunguska.app.AdvancedDiagnosticsProofTest io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner
```

`AdvancedDiagnosticsProofTest` is the focused UI proof for the completed runtime-strategy diagnostics slice. It verifies that Advanced diagnostics renders the strategy capability and runtime lane summary cards, that the runtime lane `(i)` action scrolls back into the capability summary instead of opening the old generic dialog, and that a `Supported with limits` capability chip expands its inline technical note.

Focused Chrome/IP proofs for both runtime lanes:

```powershell
adb shell am instrument -w -e class io.acionyx.tunguska.app.ChromeIpProofTest,io.acionyx.tunguska.app.SingboxChromeIpProofTest io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner
```

## Runtime Smoke

Use the smoke helper when you need the full headed emulator setup:

```powershell
$env:TUNGUSKA_REAL_SHARE_LINK = "<real share link>"
.\tools\emulator\run-vpn-smoke.ps1
```

The smoke path installs Tunguska plus helper apps, runs the selected instrumentation, and pulls diagnostics into `logs/`.

## Routing Proof Coverage

The focused routing proof validates:

- new imports default to Russia direct
- `.ru`, `.su`, and `xn--p1ai` policy rows are visible only when the preset is enabled
- custom direct domains are normalized and removable
- route test requires pressing `Test route`
- route test reports VPN/direct/block as user-facing outcomes
- route result becomes stale after input changes
- runtime GeoIP hints are shown when the policy depends on runtime datasets

It does not prove live Russian GeoIP dataplane behavior. That belongs to physical-device validation in [docs/device-validation.md](./device-validation.md).

## Joint Tunguska + Anubis Proof

The canonical joint proof is:

- `io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest`

Run it through:

```powershell
$env:TUNGUSKA_REAL_SHARE_LINK = "<real share link>"
.\tools\integration\run-anubis-e2e.ps1 -AnubisRepo ..\anubis
```

The runner performs this sequence for each runtime strategy:

1. Start a headed emulator.
2. Ensure Chrome and Shizuku are ready.
3. Install Tunguska, `trafficprobe`, and `jointtesthost`.
4. Install Anubis from the local checkout.
5. Prepare Tunguska automation fixture with the live profile and selected runtime strategy.
6. Run the neutral-host joint proof.
7. Pull diagnostics into `logs/`.

Default runtime strategies:

- `XRAY_TUN2SOCKS`
- `SINGBOX_EMBEDDED`

Use `-RuntimeStrategies XRAY_TUN2SOCKS` or `-RuntimeStrategies SINGBOX_EMBEDDED` only for targeted investigation. Full pre-release validation should run both.

## Diagnostics Modes

`run-anubis-e2e.ps1` defaults to:

- `-DiagnosticsMode Fast`

Fast mode writes lightweight step markers for successful steps and still captures screenshots, hierarchies, logcat, connectivity, VPN, and service dumps on failures.

Use full mode only when you need visual artifacts for every successful step:

```powershell
.\tools\integration\run-anubis-e2e.ps1 -AnubisRepo ..\anubis -DiagnosticsMode Full
```

`tools/emulator/pull-diagnostics.ps1` pulls artifacts through a single tar stream when available, falling back to per-file copy only when needed.

## Acceptance Bar

The joint proof is green when:

- Tunguska remains frozen while idle under Anubis control.
- Anubis unfreezes Tunguska before automation start.
- Tunguska reaches `RUNNING`.
- The helper app observes a tunneled public IP different from direct baseline.
- Anubis stops Tunguska cleanly.
- Tunguska returns to idle.
- Direct public IP is restored.
- Anubis freezes Tunguska again after shutdown.

## Artifacts

Local artifacts are written under `logs/`, including:

- `tunguska-smoke-*`
- `anubis-smoke-*`
- `ui-latest.xml`

These are git-ignored because they can contain device-local details, public IP observations, and runtime state.
