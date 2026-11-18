# TriDrone v0.4 project and collection storage

## Field workflow

Open **Projects** from Survey or **Projects and collection history** from Sessions. Create a project or open an existing project. The Survey screen shows the selected project and restores that selection when the app opens. Each Start creates a numbered collection. Stop finalizes it, and the next Start creates a new collection in the same project. Live acquisition requires fresh depth from the selected sounder and a paired external GNSS epoch. HydroLite Plus retains its provisional decoder. CEE-LINE USB requires RTK FIX or FLOAT and a configured waterline offset. See the [unified sounder field checklist](UNIFIED_SOUNDER_FIELD_TEST.md).

Collection history shows the start, end, duration, status, and count. Open a collection to see pairing statistics, acquisition settings, and a preview of the first 100 observations. Users can rename projects and collections, and archive or restore projects. Archived projects remain available for review and export. The app provides no deletion action for projects or collections.

## SQLite schema and recovery

The app opens `survey_projects.db` in Android's app-private database directory. Schema version 1 uses SQLite write-ahead logging and foreign keys.

| Table | Records and constraints |
| --- | --- |
| `projects` | UUID, editable name and optional description, UTC creation and modification times, archive state, optional offline map reference and legacy grouping key. |
| `sessions` | UUID and project foreign key, a unique sequential number within the project, editable name, UTC start/end and end-time basis, status, and the acquisition settings and CSV schema captured at Start. |
| `observations` | Unique observation UUID, acquisition sequence, session and project association enforced by a composite foreign key, UTC and monotonic times, nullable latitude/longitude and depth in meters, pairing validity, and the original serialized acquisition row. |
| `app_state` | Most recently selected project. |

Indexes support acquisition-order export and timestamp-ordered position queries by session or project. Position queries use the observation ID as a tie-breaker when multiple observations share a timestamp. Collection counts derive from committed rows.

The recorder commits each decoded event independently, including invalid or unpaired events, before advancing its count. The sensor stream files continue to retain session-scoped raw GNSS and sounder data. A write error stops acquisition and appears on the Survey screen. Finalization failures can be retried on the next Start. When a new process opens the database, it marks any still-recording collection interrupted and uses the last committed observation time or the start time if none was committed. Recording stays stopped after a restart until the operator starts a new collection.

## Upgrade and compatibility

Build 1092 kept live recordings as app-private `hydro_surveys/hydro_<timestamp>.csv` files with supporting metadata and raw captures. Its main export command only selected the newest file. Its timestamp-based filenames could also collide if two starts used the same millisecond. v0.4 uses unique identities for both collections and raw capture filenames.

The first database open discovers compatible hydrographic CSVs in the operational package, including original Build 1092 files. Import runs in a transaction per file, preserves original timestamps and serialized rows, and groups historical collections by the saved project name where available. Imported collections have an estimated end time based on saved observations. The import leaves originals in place and uses a unique legacy filename to avoid duplicate import. Import notices identify unsupported or malformed files and rows. The **Original hydrographic CSV files** action can still share the originals. Historical data that an earlier build already overwrote cannot be reconstructed. Phone GPS, simulated data, and device diagnostics keep their existing separate storage.

The database schema is new in v0.4. An earlier APK cannot browse collections recorded only in this database. Export v0.4 collections before returning to an earlier build. The installable test APK uses the operational application ID so an in-place upgrade can read the original app-private files. Android requires the same signing certificate for an in-place upgrade.

## Export package

Individual collection export retains the original header and acquisition values. HydroLite uses the original 37 columns. CEE-LINE uses 43 columns, including the appended DBT and waterline fields. An individual audit ZIP also includes available raw capture files and metadata. Project export writes a ZIP with:

- `manifest.json` with package schema version 1, project details, measurement units, and a collection index.
- `combined.csv` with `project_id`, `session_id`, and `observation_id` prepended to the acquisition columns. A project with CEE-LINE collections uses the 43-column acquisition schema. Older 37-column rows have empty values in the appended columns. The manifest identifies the combined and individual CSV schema versions.
- `collections/<session-uuid>/observations.csv` and `session.json` for each collection.
- Scoped raw GNSS and sounder captures where available, plus original legacy files where malformed records could not be imported.

CSV rows retain acquisition order. Metadata uses ISO 8601 UTC and CSV timestamps retain their original units and values. The project export preserves the GNSS quality, provisional depth interpretation, geometry and correction settings, pairing status, and withheld NAVD88 fields. Android's document picker lets the operator choose the destination and handles filename conflicts. Exports read the survey database without modifying the source. A destination failure can leave a partial output file and is shown as an export error.

## Field validation

- On a test phone with the signing certificate used by Build 1092, install in place after exporting existing work. Confirm that known legacy files appear in history and compare their exports with the originals.
- Connect the Emlid RS2+ by the deployed transport and confirm fix quality, freshness, and receiver precision before and during collection. Connect HydroLite Plus, verify measured depth and units against known depths, and check freshness, GNSS pairing, and timestamps.
- Run three consecutive collections, recreate the activity during one, and force-stop during a separate disposable collection. Check counts, interrupted status, raw sidecars, independent CSVs, and the combined ZIP after reopening.
- Run a sustained collection and check acquisition cadence and available storage. Exercise a low-storage condition on a disposable device and verify the visible error and recovery.
- Independently verify sensor offsets, datum, and control before survey use. HydroLite decoding remains provisional and NAVD88 output remains withheld without verified vertical control.
