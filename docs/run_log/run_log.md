# MATSim Run Log

## 2026-08-19: initial common BAU/Fast Track rail-measure build (superseded)

- Read rows 13, 18 and 19 of the unchanged infrastructure workbook and transcribed the executable rules into `common_service_specification.csv`.
- The fail-closed analyzer reported zero blockers. It selected 234 Poccistraße regional trips (117 per direction) and 203 regular S2 trips for Berduxstraße (94 direction 0; 109 direction 1).
- Existing parent centroids `106206` and `162054` are used only as explicit spatial scenario proxies. Four new directional rail-platform IDs prevent bus or underground platforms from being reclassified.
- The forecast feed encoded zero median dwell. This provisional choice was superseded on 20 August after stronger project-internal current-GTFS evidence supported 60 seconds.
- The Sendlinger Spange creates no GTFS row because the workbook specifies no published regular-weekday timetable change. Normal-day MATSim does not represent disruption resilience or diversion operations.
- Built provisional zero-dwell artifacts. Their obsolete hashes are intentionally omitted; the current artifacts and hashes are recorded in the 20 August entry below.
- MATSim transit inputs were not regenerated in this initial step. This status was superseded by the 20 August regeneration and validation; no simulation was run.

This file records technical runs and data-preparation steps in chronological
order. Methodological details are documented separately under `docs/methodology`.

## 2026-07-15 – Day 1: Project setup

### Repository and development environment

- Repository cloned locally.
- Project imported into IntelliJ as a Maven project.
- Java version: 21.
- MATSim version: 2025.0.

### Build

- Command: `mvnw.cmd clean package`
- Result: successful.

### Equil example

- Config: `scenarios/equil/config.xml`
- Purpose: verify that the project builds and that a basic MATSim scenario runs.

## 2026-07-17 – Day 2: Munich reference run

### Scenario

- Current config path: `scenarios/munich_base_2023/config_base.xml`
- Iterations: `lastIteration = 0`
- Population sample: 5%
- `flowCapacityFactor = 0.05`
- `storageCapacityFactor = 0.05`
- Java heap: approximately 4 GB
- Java version: 21

### Inputs

- Network: `studyNetworkDense.xml`
- Population: `munich-v1.0-5pct.plans.xml`
- The original Munich network and population were obtained from the public
  MATSim Munich scenario and are now stored locally.

### Result

- Network loaded successfully.
- 324,043 persons loaded successfully.
- Iteration 0 completed.
- Process finished with exit code 0.
- Output files were generated successfully.
- No fatal error or `OutOfMemoryError` occurred.
- The run was slow because the available Java heap was nearly fully used.

### Important limitations

- The model is currently a technical Munich reference scenario.
- It has not yet been validated as a calibrated 2023 baseline.
- Public transport is not simulated explicitly.
- No transit schedule or transit vehicles are included.
- The source year and calibration status of the public Munich population still
  need to be clarified.

## 2026-07-30 – Day 3: Preparation of the 2040 population

### Input preparation

- Population projection:
  `original-input-data/munich-demography/population_projection_2023_2040.csv`
- Munich municipal boundary:
  `original-input-data/munich-demography/munich_boundary.json`
- Boundary reprojected to DHDN / 3-degree Gauss-Krueger zone 4
  (`EPSG:31468`) to match the MATSim coordinates.
- Data sources and transformations:
  `original-input-data/munich-demography/README.md`

### Base-population analysis

Class:
`src/main/java/org/matsim/project/prepare/AnalyzeMunichPopulation.java`

- Total persons: 324,043
- Home inside Munich: 68,770
- Home outside Munich: 147,655
- Without home activity: 107,618
- Without home coordinate: 0
- Work inside Munich: 28,328
- Work outside Munich: 52,852
- Education inside Munich: 6,555
- Education outside Munich: 12,323
- Other inside Munich: 108,532
- Other outside Munich: 157,631

### Population scaling

Class:
`src/main/java/org/matsim/project/prepare/CreateMunichPopulation2040.java`

- Official population 2023: 1,488,719
- Projected population 2040: 1,752,066
- Growth factor: 1.176895
- Original MATSim residents inside Munich: 68,770
- Target MATSim residents inside Munich: 80,935
- Added cloned residents: 12,165
- Final MATSim population: 336,208
- Fixed random seed: 2040

### Generated files

- `scenarios/munich_bau_2040/population_2040.xml`
- `scenarios/munich_fast_track_2040/population_2040.xml`

The two files were generated from the same population and verified as
byte-identical. They are excluded from Git because of their size. The scenario
configs now reference these generated files.

### Validation of the 2040 population

- Total persons: 336,208
- Home inside Munich: 80,935
- Home outside Munich: 147,655
- Without home activity: 107,618
- Without home coordinate: 0
- Work inside Munich: 30,425
- Work outside Munich: 53,405
- Education inside Munich: 7,538
- Education outside Munich: 12,373
- Other inside Munich: 116,409
- Other outside Munich: 158,236

### Status

- Population generation compiled successfully.
- Both generated populations were read successfully by MATSim.
- No full BAU-2040 or Fast-Track-2040 simulation has been run yet.
- Detailed method: `docs/methodology/population_2040.md`

## 2026-07-31 – Day 5: Current MVV public-transport reference

### Raw GTFS input

- Dataset: `Soll-Fahrplandaten MVV-Gesamtnetz (GTFS)`
- Provider: Münchner Verkehrs- und Tarifverbund GmbH (MVV)
- Raw file: `original-input-data/mvv_gtfs_2026/gesamt_gtfs.zip`
- Portal release label: `06/2026`
- Internal `feed_info.txt` version: `20260705`
- Feed validity: 2026-06-15 through 2026-09-30
- Accessed: 2026-07-31
- License: CC BY 4.0
- Representative service day: Wednesday, 2026-09-16
- Source details and checksum:
  `original-input-data/mvv_gtfs_2026/README.md`

The selected date lies inside the feed validity period and no service explicitly
named `Special` is added for this date in `calendar_dates.txt`.

### GTFS conversion

Class:
`src/main/java/org/matsim/project/prepare/CreateCurrentMvvTransit.java`

Conversion settings:

- Base network: `scenarios/munich_base_2023/studyNetworkDense.xml`
- Coordinate transformation: WGS84 (`EPSG:4326`) to `EPSG:31468`
- Create PT pseudonetwork and transit vehicles: enabled
- Copy early and late departures: enabled
- Extended GTFS route types: enabled
- Stop merging: disabled (`doNotMerge`)

Generated files:

- `scenarios/munich_base_2023/input_transit/studyNetworkDense-with-pt.xml.gz`
- `scenarios/munich_base_2023/input_transit/transitSchedule-current.xml.gz`
- `scenarios/munich_base_2023/input_transit/transitVehicles-current.xml.gz`

Conversion result:

- Active services: 915
- Schedule-based departures before overnight copies: 44,536
- Frequency-based departures: 0
- Transit stop facilities reported after conversion: 39,799
- Transit lines: 857
- Transit vehicles/departures after overnight copies: 47,226

The conversion completed with exit code 0. The GTFS loader reported that
`shapes.txt` was present but empty. This is acceptable for the chosen
pseudonetwork approach.

### PT test configuration

Config:
`scenarios/munich_base_2023/config_pt_test.xml`

- Original `config_base.xml` retained unchanged.
- Network changed to the generated network with PT pseudolinks.
- Transit schedule and transit vehicles activated.
- `useTransit = true`
- Coordinate system: `EPSG:31468`
- `firstIteration = 0`
- `lastIteration = 0`
- `flowCapacityFactor = 0.05`
- `storageCapacityFactor = 0.05`
- Java heap: 8 GB

The first PT test had no explicit QSim end time. Transit vehicles remaining in
the simulation caused simulated time to continue far beyond the timetable; the
run was stopped manually after more than 1,900 simulated hours. The config was
then corrected by setting:

`qsim.endTime = 30:00:00`

### Successful Iteration-0 PT test

- MATSim version: 2025.0
- Java version: 21
- Population loaded: 324,043 persons
- SwissRailRaptor routes: 6,925
- SwissRailRaptor departures: 47,226
- SwissRailRaptor route stops: 130,758
- SwissRailRaptor stop facilities: 30,079
- SwissRailRaptor transfer connections: 1,729,986
- QSim completed through 30:00:00.
- Iteration 0 completed.
- Leg histogram PT segments: 179,650
- Process shut down normally.
- No fatal error or `OutOfMemoryError` occurred.
- Iteration runtime reported by `stopwatch.csv`: approximately 6 minutes and
  23 seconds, excluding initial scenario loading and routing preparation.

The output was written to:

`scenarios/munich_base_2023/output/pt_test`

Scenario outputs are excluded from Git.

### Warnings and limitations

- MATSim enlarged storage capacity on short PT pseudolinks. This was non-fatal
  but modifies PT pseudonetwork flow dynamics.
- Bus and tram routes are not mapped to the real road network.
- Transit vehicle types and capacities are generic converter defaults.
- No automatic car/PT mode-choice strategy is configured.
- PT crowding and capacities are not calibrated for the 5% population sample.
- The timetable represents 2026 and must not be labelled as a historical 2023
  timetable or as a 2040 timetable.

### Status

- The current MVV GTFS feed was converted successfully.
- MATSim schedule-based PT routing and explicit transit vehicle simulation were
  verified technically.
- The generated 2026 PT files are retained as a current reference and as a
  potential starting point for documented BAU-2040 and Fast-Track-2040 PT
  modifications.

## 2040 GTFS and MATSim transit preparation — 19 August 2026

- The cleaned BAU GTFS remained unchanged: SHA-256 `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A`.
- Fast Track GTFS was rebuilt with 520 U9 trips, 160 Nordring trips and 398 extended U4 trips: SHA-256 `6F89D39827EE3279162C2E1648C563ED93B59F14D13722F682CD20F41B466579`.
- U9 exact direction/time duplicates were removed; positive sub-two-minute intervals were retained; five intermediate stops use 20-second dwell.
- Fast Track MATSim inputs contain 71,300 departures and vehicles. Both published input sets passed read-only MATSim reference, road-network, S8, U4, transfer and PT-only-link validation.
- BAU and Fast Track configs remain inactive. No full 2040 simulation was run.
## 19 August 2026 — 2040 public-transport activation and smoke tests

- Activated the scenario-local combined network, transit schedule and transit vehicles in both production configs; set `useTransit=true` and `EPSG:31468` without changing population, scoring, capacity, strategy or mode-choice settings.
- Added reproducible full-load/reference validation and focused SwissRailRaptor queries. All required Fast Track services and platform interchanges were routable; BAU excludes U9 and both Nordring lines.
- Confirmed the Impler-/Poccistraße route uses the dedicated U9 platform and existing U3 platform with the documented minimum-transfer assumption. No transfer-data change was required.
- Ran separate iteration-zero, 09:30–11:00 controller tests with one synthetic PT agent and one synthetic car agent per scenario. Both agents completed in both scenarios. No production population was loaded, and no full simulation was run.

## 20 August 2026 — common-stop dwell correction and transit regeneration

- Audited positive intermediate passenger-stop dwell observations in the project-internal current MVV GTFS. Regular S2 provided 9,079 observations (median 60 seconds; 10th–90th percentile 60–120 seconds). RE5, RB40 and RB54 provided 2,934 regional observations (median 60 seconds; 10th–90th percentile 60–240 seconds).
- Applied 60 seconds dwell at Poccistraße and Berduxstraße. Arrival/departure records do not identify braking and acceleration separately, so no additional penalty was added. All later trip times shift by 60 seconds; trip counts, departure patterns and express exclusions remain unchanged.
- Added eight directed 180-second Poccistraße regional–U3/U6 transfers, derived from the existing station transfer matrix. Fast Track additionally contains 28 directed 300-second U9 relations to U3/U6 and regional platforms.
- Rebuilt both deterministic GTFS feeds: BAU `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54`; Fast Track `3F82E66EB7B0999D210B639BC85571CC59D06E9969FA53146FA5CA43D9578A0F`. Repeat builds produced identical hashes.
- Regenerated and reloaded both MATSim input sets. BAU has 249,194 nodes, 561,990 links, 80,764 stop facilities and 70,620 departures; Fast Track has 249,216 nodes, 562,029 links, 80,805 stop facilities and 71,300 departures.
- Focused SwissRailRaptor tests reached both new stops and completed the Poccistraße regional-to-U3/U6 transfer with 192 seconds of transfer/access time. All retained Fast Track service tests passed. No full simulation was run.

## 23 August 2026 — GTFS-2019 validation time-horizon correction

- A Uni-server validation run continued beyond 33,000 simulated hours because
  `qsim.endTime` was undefined. The `30:00:00` value in the Hermes module did
  not constrain QSim.
- A QSim-free audit found a latest departure of `29:40:00`, a maximum route
  offset of `32:35:00`, and a latest vehicle arrival of `42:30:00`.
- The longest values were traced unchanged to internally monotonic overnight
  and multi-day coach stop times in both the synthetic subset and source ZIP;
  no route was excluded and no stop time was rewritten.
- The 2019 generator now derives `qsim.endTime=43:00:00`, the first complete
  hour after the latest accepted vehicle arrival. A 48-hour fail-closed bound
  prevents unreviewed multi-day services from creating an excessive horizon.
- Compilation, six focused tests, full structural reference validation and
  representative bus/tram/subway/rail routing passed. No full local QSim was
  started. The incomplete server output remains invalid until step 03 is rerun.

## 24 August 2026 — synthetic-2019 server validation completed

- The corrected synthetic 2019 reference input was validated on the Uni server
  with sufficient Java heap and the complete 324,043-person population.
- MATSim completed iteration 0 and terminated normally with process exit code
  0. The validator reported `GTFS 2019 END-TO-END VALIDATION PASS`.
- This establishes technical end-to-end readiness of the synthetic 2019 transit
  input. It does not independently establish historical feed provenance.
- No mode choice was active and no behavioural calibration was performed.

## 24 August 2026 — Munich trip-boundary preflight

- Added a read-only origin-and-destination analysis filter based on the
  versioned City of Munich administrative boundary in EPSG:31468. Points on the
  boundary are included through JTS `covers`.
- Streamed all 324,043 selected plans once and identified 540,468 main trips
  with MATSim stage-activity handling. The primary `BOTH_INSIDE` sample contains
  160,603 trips (29.715543%). No invalid-coordinate trip was found.
- The complete regional population and every scenario input remain unchanged.
  No modal split, passenger-kilometre or vehicle-kilometre result was produced,
  and no mode choice or simulation was started.

## 28 August 2026 — literature-based scoring diagnostic prepared

- Created the alternative branch from `f8b0210`, the verified first parent of
  the commit that introduced the former mode-choice calibration setup.
- Added a protected iterations-0--10 diagnostic using the unchanged synthetic
  2019 inputs, seed 4711, five-percent capacity factors, SwissRailRaptor and the
  technical 48-hour QSim horizon.
- Reset car, PT, walk and bike ASCs to zero; fixed walk as the permanent
  reference; set direct travel-time and distance utilities to zero; introduced
  EUR 0.20/km for car; and used observed Munich speeds of 4.8 km/h for walk and
  13.7 km/h for bike.
- Retained `ChangeExpBeta=0.8`, `ReRoute=0.1`,
  `SubtourModeChoice=0.1` and `BrainExpBeta=1.0`. Car availability remains
  disabled because no defensible attributes exist.
- Added a read-only fail-closed validator, thin server runner, focused tests and
  IntelliJ run configurations 05 and 06. Local work did not start Controller or
  QSim and created no diagnostic output.

## 28 August 2026 — literature-based scoring result analyzer prepared

- Added Run 07 as a fixed-path, read-only analyzer for the completed university-
  server diagnostic. It cannot select another scenario and does not start
  Controller or QSim.
- The analyzer validates normal shutdown, iterations 0–10, semantic output-
  config identity, the five-percent setup and protected input hashes before
  reading results. It fails on missing final trips/plans, events or the expected
  structural totals of 324,043 persons, 540,468 main trips and 160,603
  `BOTH_INSIDE` trips.
- Final `BOTH_INSIDE` indicators include analysis-main-mode shares, sample and
  factor-20 daily Pkm, mean distance, mean travel time and target deviations.
  The other four territorial categories remain visible.
- Standard iteration mode shares are explicitly labelled as whole-population
  values. PersonStuckEvents are streamed and reported without automatic causal
  interpretation. The five small files are published atomically only after all
  checks pass; large output files remain ignored and server-local.
- Local verification uses only synthetic fixtures. The real analyzer is to run
  on the university server, after which only its generated `analysis` folder is
  copied back for review before any ASC-calibration round.

## 28 August 2026 — scoring trip-count mismatch diagnostic prepared

- Added read-only Run 07A to compare selected input and final output plans by
  person using the established MATSim main-trip and stage-activity definition.
- The diagnostic links PersonStuckEvents and reports missing, additional and
  structurally incomplete final plans without changing the simulation output.
  It writes only the protected `trip-count-diagnostic` folder and does not run
  Controller or QSim.

## 28 August 2026 — scoring result coverage correction prepared

- The person-level diagnostic confirmed 324,043 persons and 540,468 main trips
  in both input and final selected plans; no person or selected-plan trip is
  missing or additional.
- Run 07 now derives spatial categories and modal shares from all final plan
  trips. The 540,211 standard output-trip records are used only for distance,
  Pkm and travel-time measurements, with the 257-record (0.048%) coverage gap
  reported explicitly and without imputation.

## 28 August 2026 — scoring trip-distance audit prepared

- Added read-only Run 08 to compare the unchanged input selected plans with the
  iteration-10 selected plans for the `BOTH_INSIDE` scope.
- Trips are matched by person, main-trip index, main-activity types and endpoint
  coordinates. The audit separates invariant Euclidean OD distance from
  coverage-dependent travelled route distance and fails on duplicate,
  unmatched or structurally changed trips.
- Added distribution, distance-bin, active-mode threshold, mode-transition and
  long-active-origin reports. The supplied walk and bike thresholds are
  explicitly diagnostic rather than empirical behavioural limits.
- The large final plans were unavailable locally, so only compilation and
  focused synthetic tests were run. The real audit remains a server-side
  read-only step and no Controller or QSim was started.

## 28 August 2026 — literature-based scoring calibration Round 1 prepared

- Confirmed 160,603 diagnostic `BOTH_INSIDE` trips and final shares of car
  30.435919628%, PT 16.714507201%, bike 21.801585275% and walk 31.047987896%.
- Applied the full walk-referenced log-ratio formula, producing ASCs car
  0.368217221, PT 0.619256967, bike 0.065869246 and walk 0.000000000.
- Added the protected iterations-0--40 Round-1 config, fail-closed Run 09,
  server Run 10 and recovery-only Run 10B. Structural scoring, inputs and
  strategies remain unchanged.
- Added exact iteration-end `BOTH_INSIDE` mode histories, late-window statistics
  for iterations 31--40, final distance/Pkm indicators, active-mode distance
  checks, StuckEvent reporting and a damped non-executing Round-2 recommendation.
- Local preparation compiled and tested the implementation and ran only the
  read-only validator. No Controller or QSim was started locally.

## 28 August 2026 — literature-based scoring calibration Round 2 prepared

- Verified the Round-1 late means for iterations 31--40 and reproduced the
  damped walk-referenced recommendation: car 0.258598439, PT 0.611403971, bike
  -0.348664107 and walk 0.000000000.
- Added the protected fresh-start iterations-0--60 Round-2 configuration and
  thin Run 11, Run 12 and recovery-only Run 12B entry points. The original
  population and every structural scoring and technical setting remain fixed.
- Generalized the existing Round-1 analyzer for the Round-2 late window
  (iterations 51--60) and equivalent seven-file analysis package instead of
  creating a second analysis implementation.
- Corrected the decision interpretation prospectively: cumulative early
  StuckEvents remain reported, while only late-window and final-iteration
  incidence enter the calibration decision.
- Local preparation was limited to compilation, focused tests and the read-only
  configuration validator. No Controller or QSim was started.

## 29 August 2026 - final literature-based scoring calibration Round 3 prepared

- Verified the Round-2 late means for iterations 51--60 and reproduced the
  damped walk-referenced ASCs: car -0.052867606, PT 0.408378132, bike
  -0.851722801 and walk 0.000000000.
- Added the protected fresh-start Round-3 configuration and Run 13, Run 14 and
  recovery-only Run 14B by parameterizing the existing Round-2 entry points;
  no duplicate Round-3 Java pipeline was created.
- Added the final three-way assessment and explicit closest-late-result
  reporting. Round 3 produces no Round-4 recommendation.
- Structural scoring, protected inputs, 48-hour horizon, strategies and the
  160,603-trip `BOTH_INSIDE` scope remain unchanged. Local work was restricted
  to compilation, focused tests and the read-only validator; QSim was not run.

## 29 August 2026 - binding final literature-based scoring calibration Round 4 prepared

- Verified the complete Round-3 analysis and its iteration-51--60 means: car
  36.726399880%, PT 23.538974988%, bike 23.268058505% and walk 16.466566627%.
- Applied the authorized damped logarithmic update at full precision and
  normalized against walk. The Round-4 ASCs are car -0.27979614837234024, PT
  0.22971538337764302, bike -1.1684385773353396 and walk exactly 0.0.
- Added a strict fresh-start Round-4 configuration, versioned derivation, Run
  15, Run 16 and recovery-only Run 16B by extending the existing parameterized
  validator and analyzer. Round 4 differs from Round 3 only in run identity,
  protected output and the four ASCs.
- The final assessment compares Rounds 1--4 and retains Round 3 if it is the
  better stable candidate. The binding stop rule prohibits Round 5 and further
  ASC recommendations. Pkm remains a validation outcome, not a direct target.
- Local verification did not start Controller or QSim. Protected inputs and
  the BAU and Fast Track scenario files remain unchanged.

## 29 August 2026 - post-hoc final literature-based scoring calibration Round 5 prepared

- Reviewed the stable Round-4 late means (car 35.710291838%, PT 24.795800826%,
  bike 20.591022584% and walk 18.902884753%) after Round 4 had originally been
  defined as the endpoint. One conservative post-hoc update was then explicitly
  authorized without changing the pre-defined acceptance criteria.
- Applied the walk-normalized logarithmic update with damping 0.25. The exact
  Round-5 ASCs are car -0.35175057259662179, PT 0.16187543976517921, bike
  -1.2617442557140233 and walk 0.0.
- Extended the existing parameterized validator, runner and analyzer for Run
  17, Run 18 and recovery-only Run 18B. Round 5 compares all late means from
  Rounds 1--5 and selects itself over Round 4 only under the unchanged class,
  stability, deviation and distance rules. No Round 6 is produced.
- Added `-Djava.awt.headless=true` to the tracked Run 16 and Run 18 server
  configurations solely to permit chart generation without a display device;
  this is not a model parameter.
- Structural scoring, inputs and previous outputs remain unchanged. Pkm remains
  a validation outcome, and local verification starts neither Controller nor
  QSim.

## 30 August 2026 - contract-compliant 2040 production configs prepared

- Generated `config_bau_2040_mode_choice.xml` and `config_fast_track_2040_mode_choice.xml` from the frozen Round-5 config through one MATSim Config API implementation. The legacy 2040 configs remain unchanged and are not production-ready.
- Applied only the allowlisted scenario identity, protected output and population, network, schedule and transit-vehicle references. BAU and Fast Track retain identical scoring, mode choice, strategies, seed, iterations, QSim horizon, routing, transit and technical settings.
- Added a read-only paired validator covering all 149 shared contract rows, all 13 manifest hashes, complete order-independent config semantics, scenario input assignment and default-deny difference checking.
- Added focused negative, hash, output-protection and byte-idempotence tests. Config generation and validation created no output directory and changed no protected model input.
- No Controller, QSim, smoke test or production simulation was started. `BOTH_INSIDE` remains a later analysis filter over the complete regional simulation population, and neither 2040 projection was recalibrated against unavailable 2040 observations.

## 30 August 2026 - shared 2040 production analysis prepared

- Added one scenario-parameterised analysis specification, iteration listener, read-only postprocessor, event metric stream and strict output validator for both BAU and Fast Track. No scenario-specific analyzer was copied.
- Fixed the territorial scope to stage-aware MATSim main trips with both main-activity endpoints covered by the canonical Munich boundary. The 2040 denominator is derived from each final population and the historical 160,603 calibration trips are not imposed.
- Fixed iterations 51--60 as the late window and added mean, minimum, maximum, range, linear trend and iteration-level PersonStuckEvent evidence under one set of quality limits.
- Defined main-mode Pkm and travel time from final standard output trips; event-based private-car Fkm under MATSim 2025.0 first-/last-link conventions; and event-based PT Pkm, Fkm and boardings by bus, tram, subway and rail. Transfers partition PT Pkm without becoming additional main trips.
- Added ten-file atomic publication, exact run/config/input/boundary validation and fail-closed checks for missing iterations, coordinates, measurements, links, schedule routes, stops, departures, vehicles and unknown modes.
- Focused synthetic tests cover boundary and stage handling, all main modes, factor-20 scaling, late statistics, missing iterations, car and transit vehicle distances, bus, subway and bus--subway transfer events, reference failures and wrong-scenario configs. Read-only regression reproduces the documented Round-5 late modal means.
- MATSim 2025.0 already writes final events, plans and trips under the approved configs, so no production config or generator change was required. No Controller, QSim, smoke test, production run, scenario comparison, external-cost calculation or visualization was started.

## 30 August 2026 - validated 2040 production runners prepared

- Added one `BAU|FAST_TRACK` runner architecture for read-only input validation, protected iteration-zero smoke execution and automatic smoke validation, full production execution, and controller-free recovery analysis. No scenario-specific Java runner was duplicated.
- The input gate reuses the paired 149-parameter config validator and all thirteen manifest hashes, then checks population chains and coordinates, network and transit references, car connectivity, scenario identity, Fast Track restrictions and representative SwissRailRaptor routing for every available bus, tram, subway and rail mode.
- Smoke configs are derived only in memory. The approved overrides are smoke identity, separate fail-if-present output, iteration 0 and four schedule-anchored agents (`smoke-car`, `smoke-pt`, `smoke-walk`, `smoke-bike`). Fast Track is anchored to an actual U9 route. All production behaviour, routing, transit, network and QSim settings remain unchanged.
- The smoke gate requires normal shutdown, completed iteration 0, expected departures and arrivals, PT boarding and alighting, car link events, no stuck person, closed link and vehicle references, correct scenario identity and no Fast Track car traversal of the thirteen restricted links. Smoke results have no scientific interpretation.
- The production runner installs SwissRailRaptor and `Production2040AnalysisListener` exactly once, preserves failed outputs, and postprocesses only after normal Controller shutdown. Recovery accepts only a normal complete simulation and never starts Controller or QSim.
- Added eight tracked IntelliJ configurations: P1, P2, P3, P4, P7, P8, P7B and P8B. P3/P4 include automatic output validation, so redundant P5/P6 configurations were not added. QSim entries use headless 4--16 GB settings; validators and recovery use headless 2--8 GB settings.
- Offline compilation, focused no-QSim tests, paired production-config validation, XML parsing and protected-hash checks passed locally. No local Controller, QSim, smoke test or production output was started or created.

## 30 August 2026 - production smoke activity types corrected

- Server evidence showed that BAU input validation and iteration-zero QSim completed through the configured horizon, but scoring then rejected the synthetic activity type `smoke_origin` because it had no utility parameters.
- The four in-memory smoke plans now use the existing production-scored type `home` at both ends, forming closed home-to-home tours. Person IDs, coordinates, links, departure times, modes and schedule route anchors are unchanged; neither production config nor any scenario input was modified.
- A fail-closed pre-Controller check rejects any non-stage synthetic activity type not present in the active `ScoringConfigGroup`. Focused tests cover all four agents, the protected IDs and route anchors, and rejection of an unknown activity type without starting Controller or QSim.

## 30 August 2026 - post-run SwissRailRaptor serialization normalized

- Server evidence showed a normally completed BAU smoke QSim and scoring phase whose automatic validation failed only because MATSim 2025.0 serialized the installed SwissRailRaptor module's thirteen defaults into the output config while the approved input config contains no explicit module. The existing smoke output is preserved and must not be rerun.
- One shared post-run semantic comparator now accepts only that exact thirteen-value serialization and only when the expected config has no explicit `swissRailRaptor` module. Changed, missing or additional values, parameter sets and unrelated differences remain fail-closed. Pre-run contract and config comparison is unchanged.
- Smoke-output validation and full production-output/analysis validation use the same comparator. `P3B` and `P4B` provide read-only smoke recovery, checking normal shutdown, iteration 0, output-config semantics, all four persons, car and PT event evidence, zero stuck persons and protected-input hashes without constructing Controller or QSim.

## 30 August 2026 - 2040 runner output-state phases separated

- Server evidence confirmed that P3B validated the preserved BAU smoke and P4 completed the Fast Track smoke, while P7 stopped before Controller creation because the input validator incorrectly treated the required BAU smoke evidence as a blocker. No BAU production output was created.
- P1 and P2 now validate only contract-compliant configs and protected scenario inputs, independent of all output-directory state. P3 and P4 own their fail-if-present checks for both their smoke target and scenario production target.
- P7 and P8 share one pre-production gate: the selected production target must be absent, then both existing smoke outputs must pass their complete read-only validators before any production scenario or Controller is created. Existing smoke outputs are preserved and reused; no local Controller, QSim, smoke test or production simulation was started for this correction.

## 30 August 2026 - BAU production analysis identity validation corrected

- The server BAU production simulation completed all iterations and shut down normally. Its postprocessor published a complete BAU-labelled analysis package, but final validation then reported `ANALYSIS_FAILED_AFTER_NORMAL_SIMULATION`.
- The failure was confined to Markdown identity validation: the shared report generator correctly wrote the human-readable heading `# BAU 2040 production analysis`, while the validator searched that report for the machine-readable CSV identifier `BAU_2040`. All published CSV rows, the run ID, output config and scenario-specific path identified BAU correctly.
- Generation and validation now share one exact heading function for BAU and Fast Track. A wrong, mixed or stale report heading still fails closed, while the preserved BAU analysis can be validated through the read-only P7B recovery entry point. The completed simulation must not be repeated; no Controller or QSim was started for this correction.

## 30 August 2026 - shared 2040 accounting-scope analysis prepared

- Added one controller-free analyzer for the completed BAU and Fast Track outputs. It separates endpoint-based `BOTH_INSIDE` demand from every main trip of persons with a documented Munich `home`; missing or invalid homes remain visible as `UNRESOLVED` and are excluded rather than inferred from trip origins.
- Modal split and Pkm reuse selected-plan MATSim main trips, stage exclusion, the common main-mode definition and routed standard-output trip distance. Private demand is expanded by factor 20. Bike-km is explicitly derived under one person per bike, while walk remains Pkm only.
- Extended the existing event-distance stream with an optional observer instead of copying its first-/last-link logic. Private-car segments are joined to person and main-trip index and reconcile across all endpoint categories to the unchanged regional car Fkm. The earlier regional car and PT Fkm remain valid regional metrics but are unsuitable for a trip-scoped `BOTH_INSIDE` external-cost calculation.
- Added territorial PT service Fkm using exact straight node-to-node intersection fractions for boundary-crossing links. Uncut bus, tram, subway, rail and other service must reconcile to the existing regional PT Fkm before clipping; full-scale PT supply is never multiplied by 20.
- Added atomic nine-file publication under each scenario's existing `analysis/accounting_scopes`, strict mixed/partial/stale output rejection and thin headless P9/P10 IntelliJ entries. Illustrative 365-day equivalents are mechanically labelled and are not validated annual totals. Focused synthetic tests and compilation run locally without Controller, QSim or simulation; the real analyses remain server-only.

## 31 August 2026 - accounting-scope output-trip coverage validation corrected

- P9 found that the BAU standard final `output_trips` file has fewer rows than the complete structural final selected-plan trip index. Every available row had already passed the strict indexed key, duplicate, main-mode and endpoint-category checks. The failure was therefore caused by an incorrect 100%-completeness requirement in the new accounting-scope analyzer, not by evidence of a mixed or stale scenario output.
- The accounting-scope analyzer now follows the established production-analysis rule: it requires at least the shared 99% measurement coverage overall and separately for every applicable `BOTH_INSIDE` and `MUNICH_RESIDENTS` main-mode cell. It still requires output-row count to equal unique keys, forbids rows beyond the structural index, and retains all exact row-level checks. Modal split and expanded trip counts use the structural index; Pkm, distance and travel time use valid standard-output-trip measurements.
- The quality CSV and Markdown report now distinguish structural, measured, missing and valid-distance/time counts and show coverage plus missing indexed trips by endpoint category and resident status. Missing rows are described neutrally as unavailable standard output-trip measurements; no causal claim is made. This is a post-processing-only correction, so the preserved BAU output can be rerun through P9 without repeating QSim or Controller.

## 31 August 2026 - valid zero-geometry PT pseudolinks supported in territorial accounting

- The first BAU P9 accounting-scope attempt stopped before publication at `pt_4192`, a valid MATSim PT self-loop with a 50 m model length but identical from-node and to-node coordinates (`pt_102065`, anchor `(4414786.48, 5327680.14)`). The production network is not corrupt; the fault was the analyzer's prior assumption that every used PT link has positive straight node-to-node geometry.
- Ordinary finite, non-zero-geometry links retain exact line/polygon intersection clipping. Identical-coordinate links are now explicitly labelled `POINT_ANCHORED_PSEUDOLINK`: a strictly interior anchor assigns the full model length, an outside-and-not-covered anchor assigns zero, and a boundary anchor fails closed as spatially ambiguous. Non-finite coordinates and invalid model lengths fail. Zero-model-length PT links are reported separately and contribute zero territorial distance.
- The reports and quality checks expose used pseudolink counts, model length, inside/outside classifications, proxy-assigned territorial service and its territorial-PT-Fkm share. The point-anchor treatment is a documented modelling proxy for absent spatial extent, not geometric clipping. Uncut PT service reconciliation remains unchanged. A read-only inventory recorded 4,362 BAU and 4,367 Fast Track zero-geometry links; respectively 4,353 and 4,358 are positive-length 50 m PT pseudolinks, with nine zero-model-length links in each network.
- This is a post-processing-only correction. The preserved BAU production output remains the input to P9; no Controller, QSim or simulation is repeated.

## 15 September 2026 - PT cost-allocation extension prepared

- Added one controller-free, scenario-parameterised P11/P12 extension for the existing BAU and Fast Track production packages. It validates the normal completed source output, existing production analysis, existing accounting-scope package, run identity, configs, protected inputs, and source reconciliation before writing a new atomic `analysis/pt_cost_allocation` package.
- The extension streams final events once and counts territorial in-vehicle Pkm for every actual non-driver PT passenger. It does not apply BOTH_INSIDE, resident, or home-location filtering to this new numerator. Passenger movements share the established link clipping and PT pseudolink treatment with territorial service, and first-/last-link corrections use the passenger snapshot from the original movement rather than a later onboard set.
- Occupancy is expanded territorial all-passenger Pkm divided by unscaled full-service territorial Fkm. Existing validated BOTH_INSIDE in-vehicle Pkm is expanded by 20 and divided by that occupancy for accounting-allocated Fkm. Zero denominators are explicit fail-closed or valid-zero-activity cases; allocated Fkm is not capped to territorial Fkm and is not presented as marginal avoidable service.
- Added four compact result files, transparent other-mode handling, annualized 365-day Excel fields, and vehicle-type metadata that explicitly declines to infer train-carriage or Excel-unit conversions. Focused synthetic tests and compilation passed locally without Controller, QSim, routing, or a real production-data execution. The complete final server outputs remain required for P11/P12; no production result was processed locally.

## 16 September 2026 - territorial external-cost transfer table prepared

- Added one shared, controller-free BAU/Fast Track analyzer and two argument-free IntelliJ server configurations: `Analyze Existing BAU 2040 Territorial Cost Inputs` and `Analyze Existing Fast Track 2040 Territorial Cost Inputs`. They use canonical production output paths, validate normal shutdown, scenario/output identity, protected hashes, final root events and the already validated accounting/PT packages, then atomically publish exactly one XLSX workbook under `analysis/territorial_cost_inputs/`.
- The new table is territorial: it clips final-event private-car movement and completed teleported walk/bike legs to the municipal boundary without a resident, home or BOTH_INSIDE filter. Car reuses the established first-/last-link movement convention and reconciles its uncut total to regional Fkm. Walk and bike use their individual leg endpoints and modeled leg distance; incomplete, stuck, missing-distance and missing-coordinate evidence is reported rather than silently filled.
- PT Pkm and service Fkm are transferred directly from validated all-passenger territorial PT accounting and reconciled to the existing territorial PT-service report. No allocated BOTH_INSIDE Fkm or 2023 modal split enters this new table. Passenger demand is expanded once; full service remains unscaled; vehicle-unit conversion warnings and PT pseudolink proxy contributions remain visible.
- Added focused synthetic tests for inside/outside/crossing movements, outside-to-outside crossings, car/transit separation, PT scaling and pseudolink diagnostics, walk-stage and bike clipping, incomplete/missing active evidence, numeric XLSX cells/totals, and scenario-independent BAU/Fast Track behavior. Local verification is limited to compilation and read-only tests; neither Controller nor QSim nor a production-data analysis was run.
