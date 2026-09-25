# Munich 2040 MATSim Scenarios

This repository contains the final MATSim model, configurations, validation code, and selected thesis result tables for a political-science master's thesis on two Munich 2040 transport scenarios:

- **BAU 2040:** the common 2037 forecast public-transport supply, Poccistraße and Berduxstraße stops, and unchanged base road component.
- **Fast Track 2040:** BAU plus U9, the U4 extension, two Nordring services, the approved pedestrian-zone car restrictions, Mobility Hub transfer-time proxy, and Olympic/Media Village population relocation.

The final 2019 reference setup uses literature-based scoring and the selected Round-5 calibration candidate. The final Round-5 run and both 2040 production simulations are documented as completed on the university server. Round 5 retains its documented formal calibration status and is not a new behavioural calibration against unavailable 2040 observations.

The public project is named **Munich 2040 MATSim Scenarios**. The Maven artifact and IntelliJ module intentionally remain **matsim-example-project**. Do not rename the artifact, module, Java packages, or source folders.

## Requirements

- Java 21;
- the included Maven Wrapper, configured for Maven 3.9.8;
- PowerShell on Windows for the GTFS build scripts;
- a Git checkout for commands that record or check repository provenance;
- an adequately equipped headless machine or server for conversion, calibration, smoke, and production runs.

The retained launchers use 4 GiB for production-config creation, 6 GiB for GTFS builders, 12 GiB for MATSim-transit creation/validation, and 16 GiB for full calibration and production. These are operating guidance, not a portable runtime or disk guarantee.

## What is tracked and what is not

The Git repository contains the reproducible source code, tests, Maven/IntelliJ launchers, final configurations, specifications, methodology, and selected final tables. Large inputs, provider data, generated MATSim inputs, runtime output, local IDE files, and build products are intentionally excluded.

A full reproduction needs authorised external data restored to its exact repository-relative paths:

| Input | Required location |
| --- | --- |
| 2019 source GTFS | original-input-data/mvv_gtfs_2019/gtfs_2019.zip |
| 2037 raw source feed | original-input-data/mvv_gtfs_2037/raw/ |
| Final BAU GTFS package | original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_bau.zip |
| Final Fast Track GTFS package | original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip |
| Base network and plans | original-input-data/munich-v1/; scenarios/munich_base_2023/studyNetworkDense.xml; scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml |
| Scenario transit/population inputs | scenarios/munich_bau_2040/input_transit/ and population_2040.xml; scenarios/munich_fast_track_2040/input_transit/, population_2040.xml, and population_2040_fast_track.xml |

Do not substitute newer feeds or similarly named local files. Every external delivery needs a source record, SHA-256 calculated on the delivered archive, licence/attribution decision, recipient scope, and target path. The public code licence does not grant redistribution rights for GTFS, provider workbooks, planning data, population inputs, or production outputs.

See [Data availability](docs/submission/data_availability.md) for the complete boundary.

## Authoritative entry points

All retained IntelliJ launchers are in .run and use the internal module name matsim-example-project.

| Purpose | Entry points |
| --- | --- |
| Build 2019 calibration inputs | 01 Build Synthetic GTFS 2019; 02 Create GTFS 2019 Calibration Transit |
| Validate/finalise 2019 calibration | 03 Validate GTFS 2019 Calibration Input; 17 Validate Literature-Based Scoring Calibration Round 5; 18 Run Literature-Based Scoring Calibration Round 5; 18B Analyze Existing Literature-Based Scoring Calibration Round 5 |
| Build BAU 2040 | src/main/scripts/gtfs2040/build_munich_gtfs2037.ps1; src/main/scripts/gtfs2040/build_common_gtfs2037.ps1 -Mode build; src/main/scripts/gtfs2040/build_matsim_2040_transit.ps1 -Scenario bau -Mode build; Build 2040 Production Configs; Validate 2040 Production Configs; P1 Validate BAU 2040 Production Input |
| Build Fast Track 2040 | Build BAU first; src/main/scripts/gtfs2040/build_fast_track_gtfs2037.ps1 -Mode build; src/main/scripts/gtfs2040/build_matsim_2040_transit.ps1 -Scenario fast-track -Mode build; Build 2040 Production Configs; Validate 2040 Production Configs; P2 Validate Fast Track 2040 Production Input |
| Smoke evidence | P3 Run BAU 2040 Production Smoke Test; P4 Run Fast Track 2040 Production Smoke Test; P3B/P4B existing-output validators |
| Final production | P7 Run BAU 2040 Production, then P8 Run Fast Track 2040 Production, sequentially on the server |
| Recovery analysis | P7B Analyze Existing BAU 2040 Production Output; P8B Analyze Existing Fast Track 2040 Production Output |
| Output analysis | P9 Analyze Existing BAU 2040 Accounting Scopes; P10 Analyze Existing Fast Track 2040 Accounting Scopes; P11/P12 PT-cost allocation; the two territorial-cost-input launchers; Analyze Existing BAU and Fast Track 2040 Territorial Mode Comparison |

P1/P2 are controller-free input validators. Configuration 03, P3/P4, P7/P8, GTFS builds, and calibration runs are operational commands that may start QSim, conversion, or full simulation; do not use them as casual local checks.

The canonical smoke locations are:

- scenarios/munich_bau_2040/output/smoke-production-r5
- scenarios/munich_fast_track_2040/output/smoke-production-r5

Historical directories named smoke-output are noncanonical generated evidence. P3/P4 require their target and production-output directory to be absent. P7/P8 require preserved smoke evidence and never overwrite an existing production directory.

## Local source-only validation

Use the Maven Wrapper. The documented data-free test subset is in [Reproduction and validation](docs/submission/reproduction.md).

~~~powershell
.\mvnw.cmd -q -DskipTests compile
~~~

Automated full tests require excluded scenario inputs. No CI workflow is added or changed here; run data-dependent tests only in an authorised environment with the required inputs.

## Results and execution evidence

Selected final result tables remain tracked under analysis/territorial_mode_comparison_2040/ and scenarios/munich_bau_2040/production-mode-choice/analysis/. Full Fast Track results, raw production output, events, plans, and normal-shutdown logs remain controlled execution evidence outside Git. Generated simulation output is intentionally excluded and must not be committed.

The production contract defines the required evidence for recovery analysis and the output analyzers: [Production 2040 scenario contract](docs/methodology/production_2040_scenario_contract.md). Further build, validation, and operational detail is in [Reproduction and validation](docs/submission/reproduction.md).

## Licence

The source code follows the repository LICENSE. External inputs and outputs retain their providers' terms. Do not distribute data or evidence without confirmed permission and attribution requirements.
