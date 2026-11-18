# Issue 7 — live hydrographic acquisition

The existing Survey, Equipment, Settings, Sessions, phone diagnostics, and isolated Demo workflows are retained. Operational acquisition uses only receiver NMEA and sounder Bluetooth bytes. It never substitutes phone GPS or synthetic depth.

## Implemented processing

- `HydroLiteDecoder` provisionally interprets field 2 of eight numeric ASCII fields as meters. A positive reading up to the **conservative, unverified 100 m operational guard** is marked `VALID_PROVISIONAL`; zero, negative, nonnumeric and larger values remain raw and invalid. All seven other fields are logged as unknown; their semantics are not asserted.
- Depth and GNSS freshness expire independently. Live depth expires after 3 seconds; the receiver display expires after 5 seconds. Reconnecting clears old epochs and precision. Connection is never proof of valid depth or position.
- Exact conversion: meters × 3937/1200 = US survey feet. The supplied `1 0.93 0 0 0 11.0 100 0` record yields 3.051175 US survey feet, displayed as 3.05.
- Settings persist in `equipment.properties`. The offset defaults unset, is positive downward, and requires explicit physical ARP or configured receiver output reference plus confirmation. Recording snapshots offset, reference, confirmation, pairing tolerance, source CRS and processing provenance; later setting changes affect only later sessions.
- `BottomElevation.calculate` requires a verified NAVD88 reference elevation at the matching antenna reference and confirmed offset. It subtracts offset and depth once, with no extra antenna correction. **No verified vertical transformation is currently installed, so operational bottom elevations remain blank.** GGA MSL height is preserved and explicitly unverified, never relabeled NAVD88. Horizontal target EPSG:6539 and vertical target EPSG:6360 remain explicit; hydrographic exports retain raw geographic coordinates. Confirmed EPSG:6318 source and RTK fixed quality can produce EPSG:6539 coordinates through the existing projection, explicitly flagged `PROVISIONAL_CONTROL_CHECK_REQUIRED`; unknown source, nonfixed or outside-area observations withhold projected coordinates. Selecting a source CRS does not establish independent field control. Projection arithmetic has an independent PROJ reference regression; hardware datum and control remain field gates.
- Start requires fresh provisional depth paired to an actual external GNSS epoch. Recording retains every subsequent decoded depth event, including invalid depth and missing GNSS. A valid count means numerically valid provisional depth paired with GNSS quality 1–5; it does not certify RTK-fixed or survey-grade observations. Fix quality remains in each row.
- Pairing uses the latest preceding receiver epoch within the configured tolerance (default 1000 ms), with no interpolation. UTC and monotonic receipt timestamps and delta are retained. GGA receiver time-of-day is separately retained. Device receipt time is **not** a proven common sensor measurement epoch; sensor latency must be measured in the field.
- GST H precision is hypot(latitude sigma, longitude sigma), V precision is altitude sigma, receiver-reported 1σ in original meters and display/export feet. GST precision is attached only when GST and GGA receiver UTC time-of-day match (fractional formatting normalized) and receipt age is at most 3 seconds; mismatches withhold precision. GST raw sentence and receipt time are retained when available. Explicit no-fix or malformed checksum-valid GGA immediately invalidates the previous position and displayed precision, including blank-coordinate no-fix messages. HDOP is not treated as precision.

## Files and export

Live hydrographic sessions are separate from existing phone and Demo sessions in `hydro_surveys`. Sessions → Live hydrographic recordings provides first-100-event review, processed CSV sharing and audit ZIP sharing. The ZIP contains CSV, metadata, raw GNSS receipt CSV, a recording-specific raw sounder byte stream, and the initial diagnostic capture when available. Original diagnostic byte captures remain in `sounder_diagnostics`; framing rejects never destroy the raw bytes. Raw streams are flushed during acquisition. Stop recording before final export. Reopening the app after process death does not resume a previous recording.

CSV missing values are empty, with explicit validity, pairing and transformation statuses. Raw messages are correctly CSV quoted. Counts and status distinguish decoded events from valid paired soundings. Missing data are not fabricated.

## Automated checks and remaining acceptance

`HydrographyTest` covers the supplied pool record with fragmented framing replay, exact conversion, zero/malformed/negative/extreme/stale readings, disconnection/reconnection and obsolete transport ownership, pairing bounds, persisted settings, guarded bottom calculation, and CSV values/quoting and provisional EPSG:6539 projection against an independent PROJ reference. The approximately 3,960-record October 19 capture is absent from this repository; request it for full replay rather than generating substitute pool observations.

Required check command (JDK 17, Android platform/build-tools 35, Gradle 8.10.2):

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Field acceptance must still establish: actual HydroLite transport and framing, field semantics, known-depth comparisons/zero behavior/false echoes, RS2+ GGA/GST behavior and reconnects, device clock/latency pairing, antenna reference/calibration configuration, independent horizontal control and vertical transformations, and complete recorded/exported missions on the target Android hardware. A signed APK or passing CI does not establish these.
