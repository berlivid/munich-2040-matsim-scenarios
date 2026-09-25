# Reproduction and validation

## Operating boundary

Use a clean Git checkout, Java 21, and the repository Maven Wrapper. The wrapper is configured for Maven 3.9.8. The PowerShell GTFS scripts resolve the project root from their own location and invoke the wrapper.

A full reproduction requires the authorised external inputs described in [Data availability](data_availability.md). This repository does not supply those inputs, their distribution rights, or a universal runtime/disk guarantee.

Production, calibration, GTFS conversion, and QSim belong on an adequately equipped headless machine or server. The retained run configurations provide the following operating guidance:

- 4 GiB for Build 2040 Production Configs;
- 6 GiB for the GTFS builders;
- 12 GiB for MATSim-transit creation and validation;
- 16 GiB for full calibration, smoke, and production runs.

## Wrapper-based local checks

From the repository root on Windows:

~~~powershell
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd -q "-Dtest=LiteratureBasedScoringCalibrationRound1Test,LiteratureBasedScoringCalibrationRound2Test,LiteratureBasedScoringCalibrationRound3Test,LiteratureBasedScoringCalibrationRound4Test,LiteratureBasedScoringCalibrationRound5Test,Production2040RunnersTest,Production2040TerritorialModeComparisonTest" test
~~~

These are source/configuration and temporary-fixture checks. They do not run Controller, QSim, GTFS conversion, calibration, smoke, or production simulations.

Run the listed set from a clean release checkout. The Round-1--Round-5 configuration tests deliberately reject local changes below the BAU or Fast Track scenario paths, so they are expected to fail while an uncommitted release-hardening diff is present.

Tests for GTFS builders, full MATSim transit inputs, populations, production-input loading, or existing production output require excluded scenario inputs and/or controlled runtime evidence. Treat those inputs as documented prerequisites, not as part of a source-only automated test promise.

No CI workflow is added or changed by this release hardening. Automated full tests require excluded scenario inputs; use the Maven Wrapper commands above for local source-only validation and run data-dependent checks only in an authorised environment with the required inputs.

## Authoritative build and validation entry points

Use the retained IntelliJ configurations from the repository root. The internal IntelliJ module remains matsim-example-project.

| Purpose | Authoritative entry point |
| --- | --- |
| Build synthetic 2019 calibration GTFS | 01 Build Synthetic GTFS 2019 |
| Create 2019 calibration MATSim transit input | 02 Create GTFS 2019 Calibration Transit |
| Validate 2019 calibration input | 03 Validate GTFS 2019 Calibration Input. This executes iteration-zero QSim. |
| Validate final Round-5 configuration | 17 Validate Literature-Based Scoring Calibration Round 5 |
| Run final Round-5 calibration | 18 Run Literature-Based Scoring Calibration Round 5 |
| Recover existing Round-5 analysis | 18B Analyze Existing Literature-Based Scoring Calibration Round 5 |
| Build protected 2040 production configurations | Build 2040 Production Configs, then Validate 2040 Production Configs |
| Validate BAU/Fast Track inputs | P1 Validate BAU 2040 Production Input and P2 Validate Fast Track 2040 Production Input |
| Run/validate BAU/Fast Track smoke evidence | P3 Run BAU 2040 Production Smoke Test; P4 Run Fast Track 2040 Production Smoke Test; P3B Validate Existing BAU 2040 Production Smoke Output; P4B Validate Existing Fast Track 2040 Production Smoke Output |
| Run final production | P7 Run BAU 2040 Production, then P8 Run Fast Track 2040 Production |
| Recover production analysis | P7B Analyze Existing BAU 2040 Production Output and P8B Analyze Existing Fast Track 2040 Production Output |
| Run output analyses | P9 Analyze Existing BAU 2040 Accounting Scopes; P10 Analyze Existing Fast Track 2040 Accounting Scopes; P11 Analyze Existing BAU 2040 PT Cost Allocation; P12 Analyze Existing Fast Track 2040 PT Cost Allocation; Analyze Existing BAU 2040 Territorial Cost Inputs; Analyze Existing Fast Track 2040 Territorial Cost Inputs; then Analyze Existing BAU and Fast Track 2040 Territorial Mode Comparison |

The final Round-5 calibration run and both 2040 production simulations are documented as completed on the university server. Round 5 remains the selected final parameter candidate with its documented formal calibration status; it is not a claim of a new 2040 behavioural calibration.

## GTFS and MATSim-transit build order

After the approved external data is restored:

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_munich_gtfs2037.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_common_gtfs2037.ps1 -Mode build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario bau -Mode build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode build
~~~

Build BAU before Fast Track because the Fast Track GTFS package is derived from the BAU package. Follow with Build 2040 Production Configs, Validate 2040 Production Configs, P1, and P2.

The PowerShell scripts compile with the Maven Wrapper and call the pinned exec-maven-plugin. They are build operations and must not be run as a source-only validation check.

## Smoke, production, recovery, and analysis

P3 and P4 are the only smoke runners. They use the canonical paths:

- scenarios/munich_bau_2040/output/smoke-production-r5
- scenarios/munich_fast_track_2040/output/smoke-production-r5

Each smoke runner requires both its own smoke target and its scenario production-output directory to be absent. P3B/P4B validate an existing completed canonical smoke output without constructing a Controller.

Run BAU production before Fast Track production. P7/P8 are sequential server operations and require preserved smoke evidence. They must not overwrite an existing production directory.

P7B/P8B recover or validate production analysis only after normal simulation shutdown. P9/P10, P11/P12, and the territorial analyzers require the complete controlled production evidence specified in the [production scenario contract](../methodology/production_2040_scenario_contract.md), including the normal-shutdown runtime log. Do not replace that evidence with copied small analysis folders.
