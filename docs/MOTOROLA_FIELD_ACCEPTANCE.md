# Motorola / RS2+ / HydroLite Field Acceptance — 2026-10-12

## Evidence state
- **Verified in GitHub CI:** Android PR validation run [38253812215](https://github.com/fieldlogic-lab/TriDroneUpgrades/actions/runs/38253812215) completed JVM tests, lint, and `assembleDebug` successfully for PR #3 (merged). This establishes a compilable build for that PR, **not** a separately verified manual release or installation.
- **Unverified:** manual `android-apk.yml` dispatch and its artifact on current `main`; Android installation; app foreground/background recording; RS2+ Wi-Fi NMEA; HydroLite transport and message format; raw+processed export; state-plane transform and NAVD88 vertical corrections.
- **Open:** [field readiness #1](https://github.com/fieldlogic-lab/TriDroneUpgrades/issues/1), [Codex orchestrator draft PR #4](https://github.com/fieldlogic-lab/TriDroneUpgrades/pull/4). Do not depend on PR #4 for hardware readiness.

## Gate A — Build and installation (no hardware claims from CI)
1. On a reviewed main commit, request explicit owner authorization **before** dispatching `Build TriDrone Android APK` (manual workflow; can publish an updated GitHub Release when signing secrets are configured).
2. Record workflow URL, commit SHA, outcome, artifact checksum, signing certificate fingerprint, application ID and `versionCode`. A PR CI artifact is an alternative for installing a throwaway QA build without publishing a release, subject to package-signature compatibility.
3. On the Moto G Stylus 5G (2023), export existing data before replacing/uninstalling an app. Install the APK, document Android version, permissions (including location/notifications as relevant), battery optimization settings, app version and installer results.
4. Start a new **live** test session outdoors. Walk with device screen on, then screen locked and app backgrounded for at least 10 minutes. Stop, relaunch, inspect session count, timestamps, coordinates, gaps, and app-private file persistence. Reboot and verify retained records.
5. Export a CSV through the *app UI* where implemented; if export is absent, mark **FAIL — feature missing**, not a success based on a private file. Parse header and all rows in a spreadsheet/GIS tool; check actual newline-delimited rows and UTC/monotonic time behavior.

## Gate B — RS2+ GNSS, with phone GPS fallback prohibited
1. On RS2+ enable the desired NMEA output over its verified network interface; record Wi-Fi SSID/IP/port, transport, NMEA sentence types, update rate, correction source, and antenna reference.
2. With phone on the RS2+ network, verify socket stream and sample raw sentences. The receiver must be independently showing a position fix. Record any Android warning about network without Internet.
3. In app, verify GNSS connected, fix type, freshness, correction age, reported H/V precision, and loss/reconnect states separately. Disconnect RS2+ and ensure the app does **not** silently present Android phone GPS as RTK observations.
4. Save raw NMEA and timestamps for reproducibility. Connection alone does not pass survey readiness.

## Gate C — Actual HydroLite Plus (no guessed protocol)
1. At Angelo's with the sounder powered, record exact unit identification, pairing mode, visible Bluetooth services / characteristics or externally supported transport, and successful connection method.
2. Capture a small **raw** sample showing its exact byte/message framing, depth units, line endings, checksums if present, sample frequency and timestamp handling; do not modify internal RS-232 wiring.
3. Compare a stable shallow-water/manual known-depth check with reported sounder output and note transducer draft and sound-speed settings. Reconnect and disconnect while recording; missing-depth epochs must be marked, never fabricated.
4. Only then implement/accept real-depth parsing and synchronized GNSS-depth observations. A simulated sounder stream does not pass Gate C.

## Gate D — Export and survey-control acceptance
- Raw export: independent original GNSS and depth records, session configuration, source identifiers, UTC and monotonic clocks, quality and disconnect flags; verify CSV rows import.
- Processed export: pair epochs only within documented tolerance, flag stale/unpaired samples; preserve raw files. Include provenance.
- Horizontal **EPSG:6539** NAD83(2011) NY Long Island ftUS: only populate easting/northing after validated source CRS and transformation, grid/operation metadata, and comparison to independent project control. Otherwise null/unverified.
- Vertical **EPSG:6360** NAVD88 ftUS: keep bottom elevation null until survey-quality transducer reference, correct draft, geoid/water-level controls and independent check soundings are established. Never reinterpret Android phone altitude or uncorrected HydroLite depth as NAVD88.
- Compare against two or more independent check locations/check depths appropriate to the project's accuracy requirements; document signed residuals and pass/fail criteria **before** acceptance.

## Test record
| Gate | Status | Evidence / issue |
| --- | --- | --- |
| PR CI compile / test / lint | PASS for PR #3 SHA only | [Run 38253812215](https://github.com/fieldlogic-lab/TriDroneUpgrades/actions/runs/38253812215) |
| Main manual APK + signing | UNVERIFIED | Record run URL, SHA, signing fingerprint |
| Motorola install & locked-screen run | UNVERIFIED | Record Android version and CSV |
| RS2+ network and live NMEA | UNVERIFIED | Save raw sample and fix metadata |
| HydroLite physical transport / protocol | UNVERIFIED | Save raw sample, no assumptions |
| Real GNSS+depth synchronized capture | UNVERIFIED | No simulated observations |
| Map / review / user CSV export | UNVERIFIED | Import exported CSV |
| EPSG:6539 / NAVD88 EPSG:6360 | SPECIFIED ONLY | No derived survey Z before validation |

## Stop criteria
Stop and mark the gate blocked if phone recording stops in background, a device connection presents stale data as current, an export contains simulated data labeled live, raw observations are lost, or a coordinate/depth value is presented as survey-certified without independent verification. Record the smallest reproducible failure and only address that blocker before proceeding.

No autopilot, shore view or controller work in this milestone.
