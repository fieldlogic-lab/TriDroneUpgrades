# Next sprint — field feedback

Owner feedback after successful Motorola QA APK installation: the new layout
looks good and makes operation easy. Preserve the simple Survey screen.

## Requested scope

- Display horizontal and vertical receiver GST precision in feet by default.
  Respect the project's selected display units; retain receiver-native meters
  in raw/internal data and record the conversion and unit definition explicitly.
  Preserve freshness, UNAVAILABLE states and the receiver-reported 1σ label.
- Provide a clearly labeled name for the next collection session before it
  starts, separate from the job name. Persist the name with the session's
  configuration and exports; retain unique internal IDs and existing records.
- Turn the current Settings area into **Project Setup**, with job/project name,
  units, horizontal coordinate system, vertical coordinate system/datum and
  source datum. Keep selections persistent and snapshot them for each session.
  Default coordinate specifications remain NAD83(2011) / New York Long Island
  EPSG:6539 and NAVD88 EPSG:6360, US survey feet.

## Acceptance and safeguards

- Unit conversion must be tested, including the distinction between US survey
  feet and international feet. Project settings must state which definition is
  selected; changing display units must not change the physical observations.
- Test job/session naming, persistence through restart, CSV/metadata export and
  preservation of historical session settings after later project edits.
- Selecting a CRS or datum must not imply a validated transformation, vertical
  correction or independent survey accuracy. Keep unverified projected/bottom
  elevations blank and synchronized collection locked until its existing gates
  are met.
- Keep technical setup outside Survey and preserve the new large indicators.
- Implementation requires Developer work, independent QA and a new verified QA
  APK. These notes do not authorize merging or release publication.

Status: owner approved implementation after field feedback; Developer and
independent QA are executing this scope. Merge and release gates remain unchanged.
