# TriDrone — Field Validation and Evidence Protocol
Version: 2026-10-23 · Status: proposed test plan; **not evidence of successful hardware validation**

Applies to Moto G Stylus 5G (2023), Emlid RS2+ GNSS over Wi-Fi NMEA, HydroLite Plus Bluetooth sounder, and the TriDrone Android logger. Related: issues #1 and #7, `COORDINATE_DATUM.md`.

## Rules
- Record actual observations only. Do not use demo/simulation mode in acceptance tests.
- Preserve each raw GNSS sentence, raw sounder message/byte sequence (if capture supports it), arrival timestamps, and exported file without modifying originals.
- Connection is not proof of valid data. Display source, freshness, fix quality, and depth validity separately.
- Use the **US survey foot**: 1 metre = exactly 3937/1200 US survey feet.
- Raw GNSS ellipsoidal elevation, phone altitude, and uncorrected depth are **not** NAVD88 bottom elevation. Leave bottom NAVD88 null until reference point, lever arm, geoid/vertical transformation, and check measurements are verified.
- Mark any unimplemented diagnostic as **NOT TESTABLE** rather than passing it.
- No merge/deployment or claim of survey-grade readiness on the strength of this document.

## Equipment and metadata sheet (complete for each test)
| Field | Record |
|---|---|
| Test ID; UTC start/end | |
| Operator; test site; weather/water conditions | |
| Git commit / branch; app version + build number; APK SHA-256 | |
| Phone model and Android version | |
| RS2+ serial/firmware; NMEA interface (SSID/IP/port); RTK correction source | |
| HydroLite serial; Bluetooth device/address; protocol or payload sample | |
| GNSS reference point (phase centre / ARP / other) and how determined | |
| Vertical offset sign, endpoints, measured distance, units; confirmation | |
| CRS/datum: observed source, requested target, actual applied operation | |
| Known depth/control measurement, instrument, uncertainty | |
| Raw log filename/hash; session ID; exported CSV filename/hash | |

## Run sequence (keep each exported test session separate)

| ID | Procedure | Pass condition | Evidence |
|---|---|---|---|
| T01 | Reboot phone, launch app, connect GNSS through configured RS2+ Wi-Fi NMEA source. Check Emlid Flow/RS2+ quality against app. | App identifies *RS2+* rather than phone GPS, shows live H/V precision, solution type and timestamp with credible source agreement. | Screenshots, RS2+ settings, NMEA and log excerpt |
| T02 | Connect HydroLite over Bluetooth, immerse transducer at controlled known depth; hold steady at least 60 s. | Live depth updates and correctly identifies valid soundings; independently measured depth agrees within **predeclared** tolerance (record comparison, do not assume accuracy). | Raw depth messages, ruler/tape check, UI, CSV |
| T03 | Verify sample where candidate field 2 equals 0.93 m, if encountered; check unit conversion. | Displays 0.93 × 3937/1200 = **3.050675 US survey ft** before display rounding; source field mapping remains provisional until independently verified. | Original message + calculation |
| T04 | Lift transducer out of water or otherwise produce no bottom; test 0, malformed, missing or impossible messages without modifying actual logged data. | No invalid sample is shown as green/valid measured depth; raw message and reason/status retained. If a condition cannot safely be generated, mark NOT TESTABLE. | Status screenshot, raw + processed samples |
| T05 | Disconnect sounder temporarily, then reconnect. | Stale depth becomes clearly invalid; no old depth is paired to new GNSS as a fresh sounding; reconnection recovers without silently backfilling. | Time-stamped screen recording/log, CSV |
| T06 | Disable RS2+ NMEA transport temporarily, then restore while sounder stays connected. | GNSS status degrades; app never substitutes phone GPS and labels it RS2+ RTK; no invented coordinates during gap. | NMEA/log, UI screenshots, CSV |
| T07 | Record 10 min stationary + 10 min underway (safe conditions); include screen-off period. | Export survives screen-off and reopening; timestamps progress; raw capture retained; gap indicators and pairing policy inspectable; independent start/end and row counts documented. | Original session, exported CSV, time notes |
| T08 | Set GNSS-to-transducer vertical separation using explicitly defined physical endpoints, restart app. | Same configuration persists and export records offset/sign/units/reference; unset or unverified settings withhold bottom elevation. | Measurement photo, settings before/after, CSV |
| T09 | Review exported coordinates/depths on post-recovery map, including no-network test if cache is implemented. | Only measured observations plotted; offline capability reported accurately (PASS/FAIL/NOT TESTABLE); no fake positions or elevations. | Screenshot(s), exported session |
| T10 | Compare raw record count, sample timestamps, UTC/monotonic behavior, quality metadata and CSV rows. | All omissions/gaps are accounted for, or documented as failures; no unexplained silent interpolation. | Raw files, CSV, reconciliation table |

## Pairing and correctness checks
1. For three representative records, locate the **original** GNSS and sounder messages and their timestamps; match each to exported row(s). Record delta time in milliseconds and exact pairing algorithm/configuration. **Do not invent a pairing tolerance**: establish and approve one based on observed instrument rates and motion.
2. For at least three controlled depths, independently measure water depth and compare to app raw display/export. Calculate signed residual (app minus independent measurement) in US survey feet; agree tolerance **before** pass/fail classification.
3. Inspect at least one disconnected interval and its first valid reading after reconnect. Ensure status transitions are visible and no stale reading is silently treated as contemporaneous.
4. Inspect CRS and Z columns. EPSG:6539 easting/northing and NAVD88 bottom elevations must remain null/unverified without proven transformations, vertical model/control, and offset application.
5. Archive unchanged originals and compute SHA-256 hashes so later decoder changes can be replayed against the same observations.

## Test results (fill after conducting test)
| ID | PASS / FAIL / NOT TESTABLE / NOT RUN | Evidence file(s), finding, remediation issue |
|---|---|---|
| T01 | NOT RUN | |
| T02 | NOT RUN | |
| T03 | NOT RUN | |
| T04 | NOT RUN | |
| T05 | NOT RUN | |
| T06 | NOT RUN | |
| T07 | NOT RUN | |
| T08 | NOT RUN | |
| T09 | NOT RUN | |
| T10 | NOT RUN | |

**Release decision:** Not field validated until recorded tests have been executed, failures resolved or formally accepted for limited experimental use, and an operator reviews the evidence. Survey-grade/NAVD88 claims additionally require external horizontal/vertical control and documented accuracy assessment.

## Minimal issue report for each failed test
`Test ID | app build + commit | hardware firmware | expected | actual | UTC time | session ID | raw-file reference | CSV reference | screenshot | reproducible steps`.
