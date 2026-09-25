# Fast Track GTFS 2037 build report

The feed was created from the deterministic BAU GTFS containing the shared Poccistraße and Berduxstraße measures. All critical entries in the versioned service and stop specifications were approved before build mode was permitted.

- Baseline SHA-256: `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54`
- Output: `original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip`
- SHA-256: `3F82E66EB7B0999D210B639BC85571CC59D06E9969FA53146FA5CA43D9578A0F`
- New routes: 3
- New trips: 680
  - FT_U9: 520
  - FT_NR_A: 80
  - FT_NR_B: 80
- Extended U4 trips: 398
- New stop rows: 24
- New directed transfer relations: 28
- Validated rows: {agency.txt=18, calendar.txt=1, routes.txt=1736, trips.txt=71300, stops.txt=54655, shapes.txt=1441848, stop_times.txt=1348045, transfers.txt=95912}

## MATSim conversion verification

The completed ZIP was converted in memory for the technical service date 2026-02-13 with WGS84-to-EPSG:31468 transformation, unmerged GTFS stops and minimal transfer-time import enabled. No MATSim simulation was run.

- Transit stops: 54655
- Transit lines: 1736
- Transit routes: 14309
- Departures: 71300
- Minimal transfer-time relations: 95912
- Explicit Impler-/Poccistraße relations verified: 28

## Approved timetable rules

U9 retains one departure for each direction and exact U6 anchor departure time. If several U6 trips produce the same key, the lexicographically smallest source `trip_id` is selected. Positive sub-two-minute intervals remain because their source trips have distinguishable full-length or short-turn patterns. The five intermediate U9 stops have 20-second dwell; origin and terminal dwell are zero. Nordring intermediate dwell remains zero in the main scenario; a future 60-second sensitivity test is documented but not implemented.

New and extended trips have an empty optional `shape_id`; no shape was invented. Existing S8 rows were copied without modification. All new station coordinates are approved scenario proxies rather than official future platform locations. The 300-second Impler-/Poccistraße transfer time and the regularized Nordring timetable are scenario assumptions, not operationally validated values.
