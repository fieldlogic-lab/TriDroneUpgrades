# Live Survey Map V2

## Approved operational baseline and rollback

The owner confirmed TEST BUILD 1092 on the field phone as the working installation on 2026-11-01.

- Source: `7d9b3aeeaa4a85b88be63608e593633fda02ec1a`, branch `feature/issue-7-live-hydrography` (PR #8).
- APK: `TriDrone-build-92-7d9b3ae.apk`, version `0.3.0-test.92.7d9b3ae`, versionCode `1092`.
- Package: `com.massisolutions.tridrone`; minSdk 26, targetSdk 35.
- APK SHA-256: `176482200c952b503554315d6c914adfa6a7944b58beec01748389eef38d699c`.
- Certificate SHA-256: `d2d543fe2518073124fd01d5bdcb971cc1ab034149dcce8aa99e71a98b983d52`.
- Original artifact: https://github.com/fieldlogic-lab/TriDroneUpgrades/actions/runs/39421088896/artifacts/12263379866.
- Archive SHA-256: `9a24f0bbfa69222b22ca4b970c3b55a8142eabafe888fa8ea59cc83be3fae307`.
- Retained copy in implementation workspace: `/workspace/rollback-build-1092/` (APK, ZIP, BUILD.txt, manifest and verification evidence). A separate downloaded rollback archive is attached to the implementation session.

Existing CI for this source reports JVM tests, lint, assembly, established-key signing and API 35 UI smoke tests passed. This session independently checked actual APK metadata and its APK v2 signature/content digest with Python/OpenSSL. Android apksigner was unavailable locally. The owner confirms the installation works; no RS2+, HydroLite or phone hardware was independently tested in this session.

RS2+ connections, depth acquisition, start/stop/save, precision, coordinate selections, review and CSV/audit exports are the preservation contract. HydroLite field 2 as meters remains **provisional**. Actual protocol semantics, known depth, latency, independent horizontal control and vertical transformations retain the limitations in `ISSUE_7_FIELD_VALIDATION.md`. No NAVD88 bottom elevation is newly enabled.

## Development isolation and validation

`feature/live-survey-map-v2` starts at the exact approved source SHA. The draft PR targets its baseline branch so reviewers see only V2 changes. Neither the baseline branch nor main is modified. Existing signing workflow adds V2 to its push allowlist; its publishing step remains restricted to a manual run on main. New artifact checks require the baseline signer, package, permissions, SDK levels, and versionCode greater than 1092.

Local Gradle execution was attempted but failed (`gradle: command not found`, exit 127). The workspace has no Android SDK or Java compiler; downloads through its configured proxy fail connecting to port 8080. Source was retrieved through the GitHub connector, with all 42 baseline file blob hashes checked against the approved Git tree before editing. GitHub CI is required for executable Android validation. Build success must be reported only after a completed run on the implementation commit.

Do not uninstall the working application or delete its private records. Back up/export and verify existing sessions before installing any development APK. Android may refuse a version downgrade even with the original signer; retaining the rollback APK does not prove a lossless on-device downgrade. Validate rollback on a separate device or with verified backups.

## Map design and compatibility

The existing Canvas relative geographic plot is retained; this is not an OSM or aerial basemap. A separate optional dialog reads the established hydrographic CSV and raw GNSS receipt CSV on one background worker. Acquisition exposes only a short synchronized immutable snapshot of its existing live fix/file; it does not call map code. CSV schemas, sensor ownership, start/stop gates, depth decoding, units, coordinate transformations and stored settings are unchanged. The map has no write path to survey data. Display projections are disposable caches rebuilt after reopening.

Recorded raw GGA positions and paired survey positions form the chronological track. Repeated adjacent positions may omit redundant display vertices, but no source records are deleted. Valid recorded soundings require finite geographic coordinates, a positive provisional depth within the existing guard, the existing paired status and quality 1–5. Invalid/unpaired events remain in exports and are counted as omitted from sounding display. There is no interpolation. Missing raw GNSS files fall back to recorded paired positions. Receipt UTC and full recorded metadata, including available receiver 1σ precision, are inspectable. Provisional raw depth below the transducer is labeled in meters and ftUS; it is never presented as verified bottom elevation.

RTK FIX uses green circles; RTK FLOAT uses amber triangles; other accepted qualities use purple squares. The blue live vessel dot is separate from the blue recorded line and disappears when GNSS becomes stale/disconnected. Recorded paths and soundings persist. Markers batch by screen cell and quality; hit testing uses original records. Exact coincident soundings can overlap, with the nearest/latest record selected. A future record-list selector could make all coincident records individually reachable.

Initial framing uses recorded extent, then the first valid fix when no records exist. Longitude framing uses the smallest wrapped arc. Fit Survey restores recorded extent; Follow Vessel explicitly follows fresh GNSS; pan/pinch/Free Pan suspends follow until explicitly re-enabled. A new survey auto-frames unless the operator already owns navigation. Legacy phone/demo/receiver maps retain their diagnostic auto-framing until manual navigation and now expose framing controls.

Legacy `ObservationGate.validForLiveSurvey` rejects FLOAT but has no production callers; its future gate and tests are left unchanged. Active `HydroLive.start` and `frame` use `HydroGnssState.paired`, accepting qualities 1–5. New tests target this active path explicitly.

## Automated and performance coverage

New unit tests cover active FIX/FLOAT pairing/CSV quality, original-record map projections and metadata, missing/invalid data, partial and multiline CSV reads, chronological/raw GNSS track, saved-session reconstruction, repeated positions, framing/wrap/single points, manual ownership, follow resumption and new-session framing. Instrumentation exercises actual coordinator startup with FLOAT, FIX/FLOAT rows, GNSS drop/reconnect, live map navigation/inspection while frames append, unchanged existing CSV bytes and Stop through the map.

Simulated 100, 1,000 and 10,000-sounding fixtures measure background reader latency, initial rendering, cached redraw, repeated live-update rebuilds, gesture redraws and managed heap growth after GC. Counts and original-record selection are asserted; rendering fallback cannot mask a failure. These are software Canvas/emulator probes with bounded responsiveness assertions, not physical phone/GPU/native-memory or prolonged mission certification. Test results and measured values must be taken from completed CI artifacts, not inferred from test source.

## Motorola Moto G Stylus 5G (2023) field checklist — NOT RUN here

1. Export and verify backups of existing surveys/settings; retain Build 1092 APK separately. Record phone Android version, baseline and V2 APK hashes, installed version and signing certificate. Install V2 as an update without uninstalling; confirm old sessions and settings remain.
2. Repeat the established RS2+ GNSS and HydroLite connection procedure without changes. Verify receiver quality and H/V precision units, stale/no-fix behavior and depth status. Record actual hardware/firmware, connection settings and reference/offset configuration.
3. Start in RTK FLOAT with fresh provisional paired depth; confirm startup and increasing event counts. Obtain RTK FIX and confirm both qualities are recorded and labeled accurately. Do not infer survey control or validated HydroLite field semantics from these labels.
4. Open Live Survey Map during collection. Observe vessel updates, connected chronological track and recorded sounding markers. Tap FIX and FLOAT samples; compare coordinates, UTC, depth in m/ftUS, quality and precision with their exported rows. Verify raw depth is labeled provisional and no NAVD88 bottom is invented.
5. Pan and pinch during acquisition. Verify follow suspends and the view stays put through later fixes. Re-enable Follow Vessel; use Free Pan and Fit Survey independently. Check single-point/tiny surveys and missing-depth/no-coordinate cases safely.
6. Interrupt GNSS, then reconnect using the same workflow. Track/soundings must persist; live dot must disappear while stale, then resume with fresh epochs. Confirm acquisition/export missing-GNSS flags and preserved FIX/FLOAT. Repeat sounder disconnect/reconnect; no fake valid depth should appear.
7. Navigate map, inspect details and close/reopen it during continuous recording. Confirm counts continue and Stop remains accessible on map and Survey. Try screen rotation, background/foreground and phone lock with permitted service behavior; check that no map failure stops acquisition.
8. Stop/save, close/reopen app and reopen the saved survey map. Compare full recorded extent, positions, soundings and selected metadata. Existing process-death recovery does not resume recording automatically. Check old saved phone/demo/RTK review functions and CSV/audit ZIP exports.
9. Compare before/after CSV headers and values for the same controlled replay, including blanks, raw messages, FIX/FLOAT and withheld elevations. Verify acquisition records/settings are untouched by map use.
10. On the Motorola, run sustained realistic-rate surveys and 100/1,000/10,000-sounding replay datasets. Log frame responsiveness, dropped/paired counts, memory, battery/thermal effects and storage, including gestures and reconnects. Emulator timing does not establish phone performance.
11. Independently verify actual HydroLite protocol fields, known water depths, zero/false echoes, latency/pair tolerance, GNSS control/reference/datum and antenna geometry. Continue withholding unverified vertical transformations. Save raw captures and evidence with every field result.

Future imagery, boundaries, planned lines, coverage evaluation/filtering and bottom-elevation processing are intentionally outside this build.
