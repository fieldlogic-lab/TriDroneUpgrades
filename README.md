# TriDrone — Hydrographic Survey Logger

Android-based, offline GNSS and hydrographic data logger for the Seafloor Systems TriDrone.

**Current status:** Prototype Android source and build workflow. Verify APK build, Motorola installation, GPS logging, and HydroLite Bluetooth protocol before field use.

- [Android application](app/)
- [Survey coordinate and datum contract](COORDINATE_DATUM.md): NAD83(2011) New York Long Island State Plane EPSG:6539 and NAVD88 EPSG:6360, US survey feet.
- [Project scope and checklist](docs/HYDROGRAPHIC_USV_SCOPE.md)
- [Download APK from GitHub Actions](../../actions/workflows/android-apk.yml) after a successful run.

The logger must preserve original observations. Phone GPS altitude and uncorrected sonar depth are **not** NAVD88 bottom elevations.

Project tracking: [Solutions HQ](https://github.com/fieldlogic-lab/SolutionsHQ).
