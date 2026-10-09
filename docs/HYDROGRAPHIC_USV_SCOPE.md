# TriDrone — Hydrographic Survey Logger Scope

Updated: 2026-10-08

## Goal
Use an existing Motorola Moto G Stylus 5G (2023) as a standalone, low-cost GNSS and depth data logger on the existing Seafloor Systems TriDrone. No additional microcomputer or dedicated GNSS hardware for the first milestone.

## Current implementation status
**Build / Android source scaffold committed, not field-validated.**
Source: [Android logger prototype](../README.md).

Initial Kotlin app records Android GPS_PROVIDER locations in a location-type foreground service and writes raw position/timing/quality fields to app-private CSV. It has **not** been compiled into or installed as an APK. No Bluetooth depth collection implemented yet.

## Architecture
- Moto G Stylus 5G (2023): GNSS, processing, local storage, Bluetooth, optional communications.
- HydroLite Plus: existing echosounder interface with Bluetooth connection. Leave internal RS-232 wiring untouched.
- Bluetooth Classic serial access **hypothesis** subject to protocol and hardware tests; do not assume successful connection or sentence formatting.
- Optionally integrate existing Bad Elf Flex Max RTK later after validating externally accessible position/quality data.
- Export raw and processed survey observations to GIS workflows and SurveyOS.

## Milestones
- [x] Select phone-only architecture and target hardware.
- [x] Commit initial Kotlin location-service + local CSV source scaffold.
- [x] Commit Gradle configuration and GitHub Actions APK build workflow (compilation not verified).
- [x] Confirm prior SolutionsHQ GitHub Actions build (#8, APK artifact produced; new repository build pending).
- [ ] Download debug APK artifact and install on Moto G.
- [ ] Install on Moto G Stylus 5G (2023), test permission flow and background locked-screen recording.
- [ ] Implement in-app session list and CSV sharing/export.
- [ ] Probe HydroLite Bluetooth serial service and validate depth message parsing.
- [ ] Record raw depth observations and pair with GNSS via timestamps.
- [x] Define State Plane / NAVD88 metadata and acceptance specification (no coordinate transformation yet).
- [ ] Implement and validate State Plane EPSG:6539 output with explicitly verified datum transformation.
- [ ] Add GNSS-quality checks, RTK support, vertical control, transducer offsets, geoid/water-level corrections and validated NAVD88 bottom elevations.
- [ ] Validate on water with independent check measurements.

## Coordinate standard (agreed 2026-10-08)
- Target: NAD83(2011) / New York Long Island (US survey feet), EPSG:6539 — user confirmed.
- Vertical target: NAVD88 elevations in US survey feet, EPSG:6360. Never label raw echosounder depth or phone altitude NAVD88. Until valid survey-quality vertical control is present, corrected bottom elevation remains null.
- Preserve raw WGS84/geodetic fixes, depth observations, datum metadata, all quality indicators and timestamps.
- See [coordinate and datum contract](../COORDINATE_DATUM.md).

## Guardrails
Phone GNSS is not automatically RTK or survey-grade. Raw depth is not corrected seabed elevation. Preserve original timestamp, monotonic clock, quality metadata and raw depth sentences. Missing GPS/depth readings must never be interpolated silently.

## Out of scope for current milestone
Autopilot, propulsion, remote control, cameras, photogrammetry and autonomous routing.

## Repository
Dedicated source repository: fieldlogic-lab/TriDroneUpgrades. SolutionsHQ retains a project dashboard entry only. The earlier successful APK build in SolutionsHQ does not verify a build in this new repository.
