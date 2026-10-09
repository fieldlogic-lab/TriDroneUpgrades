# TriDrone Field App — Product Contract
Status: approved direction, not a claim of implemented features.

## Purpose
A field-ready hydrographic survey acquisition app for the TriDrone USV, Emlid Reach RS2+ and HydroLite Plus. Not a general GPS logger or an autopilot. Do not modify the HydroLite's internal serial wiring.

## Four essential workflows
1. **RTK stream**: connect to the RS2+; show fix mode, freshness, correction age, satellite count, and horizontal/vertical accuracy before starting any observation. Never substitute phone GPS silently. Accuracy must be receiver-reported and labeled with confidence/units; HDOP is not accuracy. Explicit unavailable states.
2. **Sensor geometry**: configure signed fore/aft and port/starboard antenna-to-transducer offsets, antenna reference point and vertical distance to the acoustic center, units, vessel axes, datum/height reference, and configuration snapshot per survey. Apply horizontal lever-arm only when heading/orientation is measured or otherwise valid; do not assume phone heading or vessel heading.
3. **Sounder**: separately connect to HydroLite Plus using an externally supported interface; show live depth, units, transducer draft, sounder health and timestamps. Transport/protocol must be verified with actual hardware. Do not assume Bluetooth SPP support.
4. **Observe and record**: single capture action pairs a GNSS epoch with a depth epoch within configurable tolerance, flags age/latency and RTK quality, stores raw messages and processed observations, and declines to create a survey-grade point if required data are missing. No fictitious NAVD88 elevations.

## Main navigation
- **Survey**: prominent persistent top GNSS status bar, sounder status, live position, H/V accuracy, live depth, ready/not-ready reasons, large Capture Observation and Start/Stop continuous survey controls; counts and last observation.
- **Map**: live track, points, selected project and acquisition quality.
- **Devices**: Emlid, sounder connection state, correction status, transport diagnostics and reconnect.
- **Settings**: project, horizontal and vertical CRS, source datum, units, offsets and axis sign conventions, logging quality thresholds, sample tolerance, profiles and favorites.
- **Records**: sessions, observation review, raw and processed CSV/metadata export.

## Nonnegotiable safeguards
- Distinguish **connection** from **position fix** from **survey readiness**.
- Distinguish reported precision from independent positional accuracy and survey control validation.
- Keep live receiver acquisition active before recording.
- Do not treat the existing phone GPS Start Recording button as RTK/sounder synchronized capture.
- Persist complete configuration with each session; changes do not rewrite old sessions.
- Store raw GNSS, raw depth, UTC/monotonic timestamps, offsets and processing provenance.
- Flag missing heading, stale fixes, no vertical control, unverified datum, and no depth stream.
- Build a readable Android app with large outdoor-use touch targets, compact cards, persistent status and clear states inspired by professional GNSS field apps, without copying third-party artwork.

## Delivery sequence
1. Real GNSS/sounder status dashboard and H/V accuracy extraction, with field verification.
2. Sensor geometry settings and validated coordinate-offset model.
3. Sounder connection/protocol proof and live depth.
4. Epoch pairing, manual observation capture, continuous survey recording and export.
5. Map/review polish and production-quality packaging/signing.

## Verification gates
GitHub build success does not establish RS2+ compatibility, HydroLite connectivity, valid RTK accuracy estimates, datum transformation correctness, or survey-grade soundings. Test each on actual hardware.
