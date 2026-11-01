# TriDrone live coverage map — milestone 1 (2026-10-31)

## Baseline
Branch starts at PR #8 source commit `7d9b3aeeaa4a85b88be63608e593633fda02ec1a`, **not** `main`. This source is associated with signed test build 92 / TEST BUILD 1092. The installed field-tested APK has not yet been independently matched to that commit; do not claim it is the identical installed build without checking Settings / APK metadata. No merge, release, uninstall, data migration or signing change is authorized.

## No-regression contract
- Preserve HydroLive acquisition, NMEA receiver precision, GNSS/device-receipt pairing, provisional HydroLite decoder, raw GNSS/sounder bytes, original CSV schema, metadata, timestamps, saved sessions, Start/Stop, and export.
- RS2+ NTRIP/Bluetooth corrections and working Wi-Fi NMEA path are not modified for mapping.
- Accept RTK FLOAT (GGA quality 5) as provisional for data capture and mapping, while retaining exact quality flag. Do not pretend FLOAT is FIX, and never enable unverified State Plane or NAVD88 transformations.
- Do not block collection because maps fail, tiles are unavailable, or GPS signal is temporarily weak; show connection, freshness and quality separately.
- Recorded soundings are distinct from a GNSS-only navigation path. Never present navigation-only points as hydrographic coverage.
- A pre-existing session remains immutable; visualization is a derived read-only view.

## Milestone 1 — map from actual sounding records
1. Add a live map entry from Survey; preserve accessible large depth, GNSS/sounder status, precision and Stop control.
2. Read/subscribe to current `hydro_surveys/hydro_<timestamp>.csv` while recording (one writer, read-only map). Render ONLY event rows that contain finite valid latitude/longitude and an actual sounder event. Mark pairing/validity and GNSS quality explicitly. Missing coordinates do not create fabricated positions.
3. Show recorded sounding markers and optionally track polyline. Mark FIX (4), FLOAT (5), and other acceptable quality states distinctly; non-valid depth and stale/unpaired samples are separately flagged or excluded from coverage but remain in source CSV. Follow current valid external GNSS position, without writing phone GPS.
4. Rehydrate from the saved session on view reopen, zoom to measured bounding box, allow pan/zoom, show 'no map/coordinates' clearly. The existing SurveyMapView is a relative offline plot, NOT a basemap. Do not label it aerial imagery or claim an offline tile cache.
5. Rendering decimation is allowed only in the display layer; never drop, edit or coalesce source soundings. Bound redraw/memory on long sessions.
6. Map UI must remain responsive during writes, disconnects and re-connects. Camera cannot silently reset during intentional pan.
7. Keep map entirely optional: failure to display map cannot stop recording or crash acquisition.

## Future milestones, NOT in first code slice
- Mission planning: search/locate project, offline prefetch under explicit licenses, sketch boundary, build/drag transects, compare measured coverage and line spacing.
- Depth-below-assumed-low-water view: assumption clearly named and stored, never imply a validated tidal datum.
- PNEZD based on documented reference only; NAVD88 output withheld pending verified vertical datum and offsets.

## Acceptance
- Golden fixture of recording containing FIX -> FLOAT -> brief GNSS gap -> FIX and valid/invalid soundings; map distinguishes states without losing any raw or CSV records.
- A sounding without valid paired coordinates does not appear at an invented point.
- UI keeps Stop accessible and acquisition continues if map closes, fails to render, or goes to background per existing lifecycle policy.
- CSV and metadata bytes remain unchanged by map access; reopen reconstructs identical coverage.
- JVM tests, lint, debug compile, actual UI smoke checks and independent QA required, tied to exact commit. RS2+/HydroLite physical acceptance remains separately NOT RUN until performed.

## Release gates
Development occurs only on this feature branch with a draft PR. No merge/release workflow/APK publishing or field-use assertion without explicit owner approval. APK installation must preserve app data and signature continuity; do not recommend uninstall as an upgrade path.
