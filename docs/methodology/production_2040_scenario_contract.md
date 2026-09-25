# Production contract for the BAU 2040 and Fast Track 2040 MATSim scenarios

## Purpose and research design

This document freezes the behavioural parameter set and the methodological comparison contract for the two 2040 production scenarios. BAU is the reference projection. Fast Track differs only through the versioned and tested infrastructure, service and village-demand representations listed in [`production_2040_allowed_differences.csv`](production_2040_allowed_differences.csv). All other settings are common. This default-deny design is necessary because the principal substantive result is the difference between BAU and Fast Track under a controlled modelling framework, not a claim that either scenario is a perfect absolute forecast.

Both scenarios are projections. No observed 2040 travel behaviour exists against which they could be recalibrated. The final 2019 parameter set is therefore transferred unchanged to both scenarios. Any later departure from this contract requires a new, explicit and versioned methodological decision before a production run.

## Final 2019 calibration choice

Round 5 is the selected final parameter candidate. Its exact alternative-specific constants are:

| Mode | ASC |
|---|---:|
| car | -0.35175057259662179 |
| pt | 0.16187543976517921 |
| bike | -1.2617442557140233 |
| walk | 0.0 |

Walk remains the reference alternative with an ASC of exactly zero. The values agree between the Round-5 configuration, the constant-derivation file and the completed analysis. The authoritative configuration is `scenarios/munich_calibration_2019/config_literature_based_scoring_calibration_round_5.xml` (canonical UTF-8/LF SHA-256 `F15CAF50F3FAAF6C13EACB874405C3ABA65BB12419D11918D5CB21D48F4EA25A`).

The Round-5 late means for iterations 51--60 were 35.290934790% car, 24.763796442% public transport, 20.252797270% bicycle and 19.692471498% walk. Against the 34/24/18/24 targets, car and public transport are within two percentage points and bicycle is within four. Walk remains 4.307528502 percentage points below its target. The run was technically stable, had no unexpected modes, and had negligible late stuck-event incidence. Nevertheless, its formal project status is `CALIBRATION_TARGET_NOT_REACHED`, because the walk deviation exceeds four percentage points and active-mode distance limitations remain.

Round 5 was selected over Round 4 under the pre-defined comparison rule: both have the same formal status, but Round 5 is stable, reduces the summed absolute modal-share deviation, and introduces no additional material active-distance deterioration relative to Round 4. This is the final calibration decision. There will be no Round 6. The remaining walk-share deviation and long active-mode distances are reported model limitations, not grounds for concealed retuning.

## Frozen behavioural and technical settings

The complete machine-readable list is [`production_2040_shared_parameters.csv`](production_2040_shared_parameters.csv). It includes every Round-5 ASC, all other scoring terms, activity definitions, mode-choice settings, strategies, routing settings, transit-router settings, run controls and analysis rules. The following points define the core contract:

- Choice alternatives are `car,pt,walk,bike`; car and bicycle are chain-based.
- `considerCarAvailability=false` because the synthetic population has no defensible ownership, licence or vehicle-availability attributes.
- ChangeExpBeta, ReRoute and SubtourModeChoice weights are 0.8, 0.1 and 0.1. They sum to one.
- Plan memory is four. Innovation has a disable fraction of 0.8, is active through iteration 48 and inactive from iteration 49.
- Both scenarios run iterations 0--60 with random seed 4711 and a QSim end time of 48:00:00.
- Flow and storage capacity factors are both 0.05. Global and QSim thread counts are four and two respectively and remain identical between scenarios.
- Public transport is simulated and routed with SwissRailRaptor. Walk and bicycle teleported speeds are 1.333333333 and 3.805555556 metres per second; their beeline-distance factor is 1.3.
- Ride and `other` are not mode-choice alternatives. Existing occurrences may not be silently mapped into a calibrated mode; unexpected main modes must be reported separately and handled fail-closed in the comparative analysis.

The existing `config_bau.xml` and `config_fast_track.xml` are historical technical configurations, not approved production configurations under this contract. They retain iteration 0 only, do not contain the complete frozen Round-5 mode-choice setup, and use legacy strategy weights. They were inspected but not changed. Step 2 must create new production configurations rather than treating these files as already compliant.

## Simulation population and analysis population

The complete regional population remains in each simulation. Background movements are necessary for congestion, routing and public-transport interactions and must not be removed merely because they fall outside the central result scope.

The primary comparative analysis is territorial: it includes every selected-plan main trip whose origin and destination are inside or on the Munich municipal boundary (`BOTH_INSIDE`). The boundary predicate is `covers`, so boundary points count as inside. MATSim stage activities are excluded through the established `TripStructureUtils` handling and do not become separate main trips. Main mode is identified with MATSim's standard analysis main-mode definition, not from individual public-transport access, egress or transfer legs.

This scope must be applied identically to BAU and Fast Track. The 160,603-trip figure belongs to the 2019 five-percent calibration sample and is not imposed as a 2040 trip-count target. Production analysis must report its observed denominators and fail on unexplained inconsistencies.

## Scaling and outcome interpretation

The simulations use a five-percent sample. Modal shares and other ratios are not multiplied by 20. Absolute persons, trips and passenger-kilometres are reported first at sample scale and then expanded by factor 20. Reliable car vehicle-kilometres require event-based vehicle analysis; they must not be inferred by relabelling passenger-kilometres. Sample and expanded values must always be shown separately.

Trip shares remain the primary calibration evidence. Passenger-kilometres, vehicle-kilometres, mean distances and active-mode distance distributions are validation or substantive outcome measures. They are not quantities that the ASCs can directly force. The residual 2019 limitations constrain absolute interpretation of the 2040 projections. The central thesis inference is therefore the controlled BAU--Fast Track delta under one frozen specification.

## Permitted scenario differences

The allowlist is machine-readable in [`production_2040_allowed_differences.csv`](production_2040_allowed_differences.csv). Permitted differences are limited to:

1. run identity and protected output directory;
2. the Fast Track population derived from the common 2040 population through the documented Olympic Village and Media Village relocations;
3. the scenario-local combined network, including Fast Track's documented public-transport pseudonetwork differences and removal of `car` from 12 pedestrian-zone links plus technical connector `126449`;
4. the scenario-local schedule, including Fast Track U9, U4 extension, two Nordring services and the twelve-hub transfer-time proxy;
5. the transit vehicle file generated consistently with each scenario schedule.

The Mobility Hub implementation changes 790 existing directed cross-stop transfer-time values and invents no service or transfer relation. The village implementation relocates activities but creates no persons and changes no times, modes, plan structures or person attributes. The pedestrian-zone implementation changes only the approved link modes and leaves BAU untouched. These narrow mechanisms, their assumptions and their limitations are documented in the existing implementation register and source-specific methodology.

Differences in scoring, ASCs, choice set, strategies, seed, iteration count, innovation timing, horizon, capacities, routing, coordinate system, analysis scope or scaling are prohibited. Measures that are absent from the allowlist are not silently accepted. They require an explicit methodological decision and a new contract version.

## Protected inputs and hash policy

[`production_2040_input_manifest.csv`](production_2040_input_manifest.csv) records every directly referenced scenario input and the versioned build specifications needed to reproduce the intended differences. All direct 2040 network, schedule, vehicle and population files are locally present. Their large generated files are intentionally ignored by Git but protected by recorded SHA-256 values and by their versioned generation specifications.

Hash methods are assigned by provenance, not inferred from file extensions. Git-tracked, uncompressed plain-text configurations and specifications use canonical UTF-8/LF SHA-256: bytes are decoded strictly as UTF-8, CRLF and lone CR are normalized to LF, and the normalized UTF-8 bytes are hashed. This rule covers the frozen Round-5 XML config and the seven unique tracked text paths classified explicitly in the input manifest: the two legacy scenario configs, the Munich boundary, and the common-service, Fast Track service, pedestrian-zone and Mobility Hub specifications. Their recorded expected hashes are the independently verified canonical LF values. Line-ending normalization is the only normalization; any parameter, specification, geometry or other content change still changes the hash and fails validation.

Raw-byte SHA-256 remains mandatory for generated, ignored or manually transferred model inputs, including populations even when stored as uncompressed XML, the base and combined networks, transit schedules, transit vehicles, `.xml.gz` files and GTFS ZIP archives. Raw inputs are never decoded or line-ending-normalized by the contract validator. The manifest's complete canonical-path set is checked explicitly, so a new or reclassified input fails closed until its provenance and hash policy are versioned deliberately.

The current inventory required no reconstruction of missing large files and did not require reading `Infrastructure_measures.xlsx`; the versioned implementation register and source specifications already establish the measures used here. If a future machine lacks an ignored large input, it must be copied from the validated server/workstation or rebuilt through its documented pipeline and must reproduce the manifest hash. It must never be replaced with a guessed file.

## Reproducibility and release rule

Before either production run, a read-only validator must compare the proposed configs semantically against this contract, verify all available hashes, require the output directory to be absent, and prove that the BAU--Fast Track differences are a subset of the allowlist. Both configs must be validated together before either simulation starts. The full regional population is then simulated in both scenarios, and the same `BOTH_INSIDE` analysis and scaling rules are applied after normal completion.

This contract released the implementation of new BAU and Fast Track production configs. It did **not** release a production simulation using the legacy configs. The Step-2 gate is satisfied only by the generated configs documented below after they pass the shared-parameter, input-hash and allowlist checks described above.

## Contract-compliant production configs

The paired configs are now generated from the Round-5 source through the MATSim Config API rather than maintained as independent XML copies:

- BAU: `scenarios/munich_bau_2040/config_bau_2040_mode_choice.xml`
- Fast Track: `scenarios/munich_fast_track_2040/config_fast_track_2040_mode_choice.xml`

`BuildProduction2040Configs` validates the versioned contract and all protected hashes, loads Round 5, changes only the allowlisted run identity, output and scenario-input fields, validates both candidates together and publishes them deterministically. `ValidateProduction2040Configs` is read-only: it checks all 149 shared contract rows, including 135 config-backed values and 14 analysis, scaling and frozen-reference rules; compares the complete semantic module and parameter-set structure independently of XML order; and rejects every non-allowlisted difference. Known path fields are compared after separator normalization, while substantive values are not broadly normalized. Numeric serialization is accepted only when the written value has the identical Java `double` bit representation as the frozen Round-5 value; no numerical tolerance is used.

The production run IDs are `munich-bau-2040-mode-choice` and `munich-fast-track-2040-mode-choice`. Their protected output directories are `scenarios/munich_bau_2040/output/production-mode-choice` and `scenarios/munich_fast_track_2040/output/production-mode-choice`. Neither directory is created by config generation or validation. The legacy `config_bau.xml` and `config_fast_track.xml` remain technical predecessors and must not be used for production.

Structural readiness was followed by the final P3/P4 technical smoke workflow and the BAU and Fast Track production simulations on the university server. The selected Round-5 calibration retains its documented `CALIBRATION_TARGET_NOT_REACHED` formal status; both projections nevertheless retain identical frozen behavioural parameters and are not recalibrated against unavailable 2040 observations. The complete regional population remains a simulation input, and `BOTH_INSIDE` remains a later analysis rule rather than a population filter.

## Shared production analysis

The common analysis method is specified in [`production_2040_analysis.md`](production_2040_analysis.md). One scenario-parameterised listener, postprocessor and validator is used for both production runs. The listener records exact iteration-end `BOTH_INSIDE` mode shares and compact stuck-event summaries; the postprocessor validates a normally completed output before atomically publishing the ten specified result files. The late window is iterations 51--60. Main-mode Pkm retain the validated Round-5 `output_trips` travelled-distance definition, private-car Fkm and PT submode indicators use the final event stream, and the canonical municipal-boundary hash is enforced.

The existing configs already guarantee final-iteration events and normal-shutdown plans and trips through MATSim 2025.0's last-iteration and `dumpDataAtEnd` behaviour. No config, scoring, routing, QSim, input, seed or strategy setting was changed for analysis preparation. Listener installation remains restricted to the server runner. The BAU and Fast Track production simulations completed on the university server; the default-deny recovery and validation gates below define what completed output can be accepted without repeating a simulation.

## Validated server execution contract

One default-deny runner family now serves both scenarios. `ValidateMatsim2040ProductionInput`, `RunMatsim2040ProductionSmokeTest`, `RunMatsim2040Production`, and `AnalyzeExistingMatsim2040ProductionOutput` accept exactly `BAU` or `FAST_TRACK`. Scenario selection changes only the contract-approved config, run identity, output and input bundle. The full runner loads the unchanged production config, installs the established `SwissRailRaptorModule`, and installs `Production2040AnalysisListener` exactly once. It changes no model parameter.

The read-only input validator rechecks 149/149 shared parameters, all manifest hashes and the paired allowlist before loading the selected inputs. It validates the configured and coordinate-domain CRS, population chains and modes, network references and car connectivity, schedule stops, routes, departures and vehicle references, and one SwissRailRaptor connection for every available principal route mode (`bus`, `tram`, `subway`, `rail`). It also proves the Fast Track U9, Nordring and thirteen pedestrian-zone restrictions are present only in Fast Track. P1 and P2 create no output and are deliberately independent of smoke and production output-directory state, so the same input validation remains usable before and after execution.

The smoke configurations exist only in memory. Their only overrides are a smoke run ID, a separate fail-if-present output directory, `lastIteration=0`, and replacement of the production population by four deterministic agents anchored to facilities in the actual scenario schedule: `smoke-car`, `smoke-pt`, `smoke-walk`, and `smoke-bike`. Each synthetic plan is a closed `home`-to-`home` tour because `home` is already defined by the unchanged production scoring configuration; the earlier synthetic labels `smoke_origin` and `smoke_destination` were technically unscorable. Before Controller creation, a fail-closed check rejects every non-stage synthetic activity type absent from the active `ScoringConfigGroup`. A smoke runner itself requires both its target smoke directory and its scenario's production directory to be absent. Fast Track uses an actual U9 route as its anchor. All scoring, routing, network, transit, QSim, horizon, capacities and behavioural settings remain production-identical. The automatic smoke validator requires normal shutdown, iteration 0, all four departures and arrivals, PT boarding and alighting, regular car link events, no stuck agent, valid link and vehicle references, correct scenario inputs, and no Fast Track car event on any of the thirteen restricted links. `P3B Validate Existing BAU 2040 Production Smoke Output` and `P4B Validate Existing Fast Track 2040 Production Smoke Output` rerun this validation read-only and never construct a Controller, allowing a normally completed smoke output to be recovered without repeating QSim. Existing successful smoke outputs are preserved evidence rather than blockers. A smoke PASS demonstrates technical integration only and has no scientific interpretation.

Post-run config comparison uses one shared, default-deny rule for smoke and full production outputs. Pre-run contract and config comparisons remain unchanged and fully strict. If, and only if, the approved expected config has no explicit `swissRailRaptor` module, the MATSim-written output may contain one module with exactly the thirteen MATSim-2025.0 defaults: `intermodalAccessEgressModeSelection=CalcLeastCostModePerStop`, `intermodalLegOnlyHandling=forbid`, `scoringParameters=Default`, `transferCalculation=Initial`, `transferPenaltyBaseCost=0.0`, `transferPenaltyCostPerTravelTimeHour=0.0`, `transferPenaltyMaxCost=Infinity`, `transferPenaltyMinCost=-Infinity`, `transferWalkMargin=5.0`, `useCapacityConstraints=false`, `useIntermodalAccessEgress=false`, `useModeMappingForPassengers=false`, and `useRangeQuery=false`. A missing, changed or additional value, any parameter set, any unrelated module difference, or an explicit expected SwissRailRaptor module remains a validation failure.

Protected outputs are:

- BAU smoke: `scenarios/munich_bau_2040/output/smoke-production-r5`
- Fast Track smoke: `scenarios/munich_fast_track_2040/output/smoke-production-r5`
- BAU production: `scenarios/munich_bau_2040/output/production-mode-choice`
- Fast Track production: `scenarios/munich_fast_track_2040/output/production-mode-choice`

Every path uses `failIfDirectoryExists`. No runner deletes or overwrites an output. A simulation exception is reported as `SIMULATION_FAILED` and its output is preserved. If simulation shutdown is normal but analysis fails, the simulation is not repeated. The controller-free recovery entry point first proves normal completion, then publishes a previously missing analysis or validates an existing complete one. It rejects incomplete, wrong-scenario, smoke and calibration outputs.

Server execution is sequential: validate BAU input; validate Fast Track input; run and require PASS from the BAU smoke; run and require PASS from the Fast Track smoke; review both smoke outputs; run and fully validate BAU production; only then run and fully validate Fast Track production. Before either P7 or P8 can construct a Controller, the shared production gate requires its own production-output directory to be absent and reruns the complete read-only validators for both preserved smoke outputs. Thus successful smoke outputs are mandatory common evidence for both production scenarios. Recovery is used only after normal simulation completion and an analysis problem. Full BAU and Fast Track runs are not parallelised where this would compromise resources or reproducibility. Runtime and memory demand are observation-dependent rather than guaranteed; tracked server configurations provide headless execution with 4--16 GB for QSim entry points and 2--8 GB for validators and recovery.

Preparation and local verification of these entry points did not start Controller, QSim or a smoke test. Server pull and smoke execution remain separate operational actions.
