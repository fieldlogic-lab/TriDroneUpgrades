# QA APK installation and field acceptance

The PR validation workflow runs JVM tests, Android lint, and `assembleDebug` on
the exact PR head commit. Only a successful run uploads
`TriDrone-pr-6-qa-apk` (the PR number varies). This is a downloadable GitHub
Actions artifact, not a GitHub release or an update-feed publication. Download
it while signed into GitHub with access to this repository; artifacts expire
after 14 days.

Extract the archive and check `SHA256SUMS.txt` against `TriDrone-qa-debug.apk`.
`build-info.txt` records the full source commit and workflow run/attempt.
`apk-badging.txt` records the package, actual versionCode/versionName and minimum
Android version. `signing-certificate.txt` records the APK signing certificate.
The APK supports Android 8.0/API 26 and later; compatibility with the Motorola
Moto G Stylus 5G (2023) still requires installation and device testing.

This workflow uses the runner's ephemeral Android debug signing key and no
signing secrets. An installed build signed with a different key cannot be
updated in place with this APK. Version codes also follow this workflow's run
number and may be lower than a previously installed build. **Export and back up
existing records before considering uninstalling**: uninstalling normally
deletes app-private records. Do not uninstall a field device with unexported
sessions. This QA artifact does not replace the persistent signed development
update feed. Android installation requires the operator's normal approval.

## Physical acceptance checklist

- Install and launch on the Motorola; verify screen rotation, outdoor contrast,
  large targets, Android permissions, foreground notifications and screen-lock
  behavior. Test stop/restart, force-stop/relaunch and process interruption;
  existing raw sessions must remain readable and exportable.
- On the RS2+, configure a supported Wi-Fi NMEA TCP output and its actual host
  and port. Verify wrong-network, unavailable-receiver, missing GGA/GST, RTK
  FLOAT, lost corrections, stale input and connection-loss behavior. Observe
  bounded reconnection and confirm neither old precision nor a competing
  Bluetooth service produces a false green status.
- Compare displayed H/V precision and units with receiver GST messages. These
  are receiver-reported 1-sigma precision estimates, not independent survey
  accuracy. Independently validate surveyed control and receiver configuration.
- Use Bluetooth GNSS only if the actual receiver exposes the selected supported
  NMEA interface. Verify saved connection settings against that equipment.
- Verify HydroLite's actual supported Bluetooth transport on hardware before
  attempting diagnostics. Preserve raw bytes and connection evidence. A
  Bluetooth connection or received bytes alone must not produce verified depth
  or a green collection indicator. Do not modify internal sounder wiring.
- Compare raw captures, timestamps, configuration snapshots and CSV exports
  before/after interruption. Never mix legacy synthetic or phone-GPS records
  into receiver survey observations.
- Synchronized survey capture remains disabled until real depth parsing,
  time alignment, acquisition quality, sensor geometry and field acceptance
  are implemented and verified. EPSG:6539 and EPSG:6360 remain the target
  specifications; selecting a datum does not validate a transformation or
  authorize NAVD88 bottom elevations.

No automated build, lint run or JVM test establishes physical receiver/sounder
compatibility or survey-grade accuracy. Merging and release publication still
require the owner's explicit authorization.
