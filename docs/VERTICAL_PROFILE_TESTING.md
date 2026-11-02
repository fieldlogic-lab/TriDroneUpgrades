# Vertical setup and portable profiles — draft PR 14

## Baseline and scope

Working build 1092 is workflow run 92 at `7d9b3aeeaa4a85b88be63608e593633fda02ec1a`:
https://github.com/fieldlogic-lab/TriDroneUpgrades/actions/runs/39421088896
The workflow succeeded and the source version-code rule is run number + 1000.
This branch preserves its GNSS parsing, RTK FIX/FLOAT collection, provisional
HydroLite decoder, and existing historical session readers.

The Vertical setup form has two measured separations, in US survey feet:

1. A: declared GNSS antenna/output reference down to sounder acoustic center.
2. B: acoustic center below the waterline (positive downward draft).

Each profile saves these measurements, receiver reference, physical confirmation,
Bluetooth addresses, GNSS transport/TCP settings, external serial baud,
receipt-pairing tolerance, depth output reference, scale, and signed correction.
The picker previews a profile; **Save & use** activates it and disconnects sensors.
Reconnect using that profile. Recording locks equipment edits and imports.
Editing measurements or calibration clears the relevant confirmation.

Share/import transfers a versioned `.properties` file. Imports add copies and
leave existing profiles, active selection, and historical recordings unchanged.
Imported geometry and calibration must be physically rechecked and reconfirmed.

## Depth and vertical control

Raw sounder frames, depths, GNSS observations, and receipt timestamps stay raw.
Let `r` be raw depth converted to ftUS, `s` the scale, and `c` the signed ftUS correction:

- Acoustic-center output: corrected acoustic range = `r*s+c`.
- Waterline output (draft already included): corrected acoustic range = `(r-B)*s+c`.
- Bottom below waterline = corrected acoustic range + `B`, exactly once.
- Antenna-referenced bottom elevation would be verified reference height − `A`
  − corrected acoustic range, with no additional subtraction of `B`.

Unknown output reference, unsupported protocol, invalid range or unconfirmed
calibration withhold corrected results. Waterline depth also needs confirmed
geometry. Operational NAVD88 values remain blank: no verified vertical-control
transformation is installed. A measured geometry or GGA MSL height does not
establish NAVD88 control. Live labels distinguish raw and corrected results.

New recording metadata and CSV rows use one immutable selected-profile snapshot.
Later edits do not recalculate historical files. Collision-safe session names
also protect prior files if the device clock repeats a timestamp.

CEE-LINE/Parani SD1000 is prepared for Bluetooth SPP and **9600 baud on the
adapter's serial side**. Android RFCOMM does not configure the Parani UART.
CEE-LINE currently captures raw diagnostic bytes only; its depth protocol,
framing, units and reference require physical verification before decoded
soundings/record start can be enabled. HydroLite decoding remains provisional.

## Build and inspect

Use JDK 17, Gradle 8.10.2, Android platform 35 and build-tools 35.0.0.

```sh
gradle --no-daemon --max-workers=4 --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleFieldTest
```

With only an isolated Android emulator connected (never either field phone):

```sh
bash .github/scripts/android-smoke.sh
```

The instrumentation suite includes actual rendered UI screenshots, profile
persistence/selection/import, and synthetic recording integration tests for
FIX/FLOAT, raw retention, metadata/CSV snapshots, draft-once arithmetic, absent
NAVD88, stale transport rejection, recording locks, and untouched history.
Synthetic fixtures do not establish physical sensor performance.

## Phone #2 test APK

Use `app/build/outputs/apk/fieldTest/app-fieldTest.apk`, **not** the debug APK.
It is signed with an Android test key, has application ID
`com.massisolutions.tridrone.fieldtest`, and launcher label **TriDrone Field Test**.
It has separate private files/preferences and disables production update feeds.
It can coexist with the original `com.massisolutions.tridrone` app. CI retains
this APK as a PR artifact without creating a release. Test signatures are not
the production update signature; retain the local test key for later updates
of this local test installation.

Install manually on phone #2 using Android's package installer. Do not uninstall,
clear data, overwrite or update build 1092 on phone #1. Do not copy historical
survey files into mutable test fixtures. No physical phone installation or
recording deletion is part of automated checks.

Remaining field gates: verify actual HydroLite/CEE-LINE protocol and units,
Parani wiring/UART configuration, antenna reference, loaded waterline/draft,
known-depth calibration, latency/freshness, Bluetooth reconnects, RS2+ FIX/FLOAT,
independent horizontal/vertical control, and complete phone #2 record/export.
Keep the PR draft; no merge or production publication is authorized.
