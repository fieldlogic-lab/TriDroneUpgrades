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
