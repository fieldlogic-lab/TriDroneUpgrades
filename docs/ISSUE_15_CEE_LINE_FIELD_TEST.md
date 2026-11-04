# Issue 15 — CEE-LINE USB field test

## Protected baseline and installation

Source anchor: Build 1092, commit `7d9b3aeeaa4a85b88be63608e593633fda02ec1a`.
This test uses application ID `com.massisolutions.tridrone.ceeline.test`, label
**TriDrone CEE-LINE Test**, and version code 2000 plus the CI run number
(local default 2001). It has separate Android storage. The operational APK,
production update feed, and original Motorola are not changed. Install only on
Android phone #2. No merge is authorized.

1. Download the signed APK artifact and verify its supplied SHA-256.
2. On phone #2, allow installation from the app used to open the APK. Install
   **TriDrone CEE-LINE Test**. Keep the production app installed separately.
3. Open Equipment, select **CEE-LINE USB**, and configure both vertical
   measurements. Re-enter GNSS settings because test-app storage is separate.
4. Connect the same tested USB serial adapter. Tap Connect and grant Android
   USB permission. Select the adapter if more than one is present. No separate
   terminal application is required; close terminal apps holding the USB port.
5. Connect the Emlid using the existing Bluetooth or Wi-Fi TCP workflow.
6. Verify a fresh nonzero Water Depth, RTK FIX or FLOAT, then press GO.
7. Stop and inspect Records; export CSV or audit ZIP. ZIP includes raw GNSS,
   raw sounder bytes, configuration metadata and the PNEZD companion.

The APK may use a dedicated test signing key instead of the operational key.
Use the delivered certificate fingerprint to identify it. Future in-place test
updates require the same test key; do not uninstall without exporting records.

## Measurements and units

- Raw DBT means depth below the transducer acoustic reference, positive downward.
- Transducer below waterline is positive when submerged. Zero is allowed only
  as an explicitly entered measurement; unset is distinct from zero.
- **Water Depth = raw DBT + transducer-below-waterline offset.**
- GNSS-to-transducer is a separate positive downward vertical separation from
  the selected GNSS height reference. It is never added to Water Depth.
- With independently verified reference elevation, bottom elevation equals
  `GNSS reference elevation − GNSS-to-transducer separation − DBT`.
  The waterline offset does not enter that formula.
- Example in international feet: `4.20 ft + 0.70 ft = 4.90 ft`.
- NMEA `f` uses international feet (0.3048 m); `F` uses fathoms (1.8288 m).
  Guided setup and projected exports use **US survey feet**, exactly
  1200/3937 m. Canonical processing uses meters. Raw NMEA preserves source units.

CEE-LINE uses 9600 baud, 8 data bits, no parity, 1 stop bit, no flow control.
Checksum-valid `$SDDBT` sentences are required. Meter field is preferred, then
feet, then fathoms; populated fields must agree within 0.03 m (rounding tolerance).
Zero, negative, nonfinite, malformed, bad-checksum and conflicting readings are
flagged and never counted as accepted soundings. No manufacturer range limit
has been established for CEE-LINE; HydroLite retains its existing range guard.

## Synchronization and review

Pairing uses the latest external GNSS epoch received before a depth frame,
within the configured tolerance (default 1000 ms, supported 1–5000 ms), capped
at 3 seconds. Both RTK FIX (4) and FLOAT (5) are accepted with flags preserved.
Receipt UTC and monotonic timestamps are stored; DBT has no sensor timestamp.
Hardware latency is unmeasured. Connection transitions clear previous depth
and GNSS epochs. Stale/invalid/unpaired events remain auditable but are not
accepted soundings. USB reconnect resumes only with fresh paired data.

CSV schema version 2 appends raw DBT, waterline offset, calculated Water Depth,
and GNSS-to-transducer measurement without reinterpreting old records. The
active session keeps its starting configuration when settings change. The live
map displays at most 2000 recent accepted sounding locations and 2000 preview
track positions; full records remain in CSV. Recorded maps use a bounded
sample loaded off the UI thread.

NAVD88 conversion remains unimplemented/unvalidated. No GGA altitude is silently
relabeled NAVD88. Bottom NAVD88 fields stay empty. PNEZD withholds observations
without valid projected coordinates and a verified bottom elevation; an empty
PNEZD therefore means no eligible vertical solution, not an empty CSV survey.
The offline map is a relative track/sounding plot. Planned-line guidance,
mission planning and coverage are unfinished; no basemap or autopilot is added.
HydroLite's eight-field protocol remains provisional and is preserved separately.

## Field validation checklist — phone #2 only

- [ ] Identify APK version, commit, signing fingerprint and SHA-256.
- [ ] Confirm USB permission grant/denial, unplug/replug and recovery without
      duplicate prompts or stale accepted soundings.
- [ ] Verify nonzero DBT against a measured reference at multiple depths.
      The captured Android zero sentence alone does not prove bottom returns.
- [ ] Independently measure both vertical distances and check the displayed
      sum, including the 4.20 + 0.70 = 4.90 example through replay/reference input.
- [ ] Confirm Emlid FIX and FLOAT both record with correct flags; interrupt GNSS
      and USB independently and verify stale/missing states and count behavior.
- [ ] Compare timestamps and measure sounder/GNSS latency at approximately10 Hz.
- [ ] Run a full-duration survey with phone screen off/on and backgrounding;
      inspect battery/USB power and export completeness.
- [ ] Inspect boat track, sounding locations and post-recording review.
- [ ] Recompute Water Depth from exported DBT/offset and confirm units/signs.
- [ ] Confirm NAVD88 fields/PNEZD remain withheld without independent control.
- [ ] Regression-check HydroLite on its verified field transport without
      altering the operational installation.

## Remaining hardware-dependent tests

Adapter chipset/driver compatibility, USB-C host power, nonzero CEE-LINE returns,
receiver configuration and external GNSS accuracy, timing/latency, motion/lever
arms, sound velocity and transducer calibration, benchmark/geoid/datum control,
long-session Android lifecycle and actual field exports require hardware tests.
CI and mock transport tests do not establish survey-grade accuracy.
