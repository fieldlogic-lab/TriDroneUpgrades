# TriDrone unified sounder field test

TriDrone uses the operational Android package `com.massisolutions.tridrone` and one project and collection history. Select **HydroLite Plus** or **CEE-LINE USB** in Equipment before starting a collection. A project can hold collections captured with either sounder. Stop the current collection before switching equipment. Each collection records the selected sounder, acquisition settings, and geometry at Go.

## Transition from older builds

- For the operational HydroLite app, use the signed unified test APK to upgrade in place on a test phone. Keep the existing installation and export important field records before upgrading. Confirm Android accepts the signature and that prior collections remain visible after the update.
- The standalone CEE-LINE test app (including Build 2039) has a different Android package and private storage. Export its collections and audit ZIPs to shared storage before retiring that app. Verify the exported files can be opened and contain the expected records. The unified app cannot read the private files of the standalone package, and these historical exports remain external archives.
- The unified APK uses the existing operational signing setup. Automated branch builds create test artifacts. The shared update feed and rollback APK are untouched by branch builds.

## CEE-LINE measurements

Use the existing CEE-LINE 9600 baud, 8N1 USB serial configuration. The receiver must send checksum-valid DBT. Water Depth is the raw depth below transducer plus the positive submerged-transducer offset. The GNSS-to-transducer separation is recorded separately and does not enter the Water Depth calculation. Raw NMEA, its original units, and the independently entered offsets remain auditable. Projected and display distances use US survey feet, while raw DBT feet are international feet.

## Verify both sounders on the same installation

1. Connect the Emlid RS2+ and verify receiver quality, timestamp freshness, and reported precision in the unified app.
2. Select **HydroLite Plus**, connect its established transport, and verify a fresh nonzero depth. Record and stop one collection. Confirm the CSV retains the HydroLite schema and provisional depth interpretation.
3. Select **CEE-LINE USB**. Connect the previously tested USB serial adapter, grant Android USB permission, and enter the transducer-below-waterline and GNSS-to-transducer measurements separately. Verify fresh DBT and RTK FIX or FLOAT. Check Water Depth against the raw DBT plus the measured waterline offset. Record and stop a second collection in the same project.
4. Unplug and reconnect the USB adapter. Verify that stale depth is withheld and a fresh epoch is required before resuming observations. Switch back to HydroLite and verify that the old CEE-LINE reading cannot start a HydroLite collection.
5. Reopen the app and export both collections individually and as one project ZIP. Check both original headers, observation counts, equipment snapshots, combined project and session IDs, and the raw capture attachments. Confirm the NAVD88 field stays blank without verified vertical control.

Hardware transport and timing, adapter reconnect and permissions, field cadence, measured offsets, and in-place phone upgrades require physical device validation.
