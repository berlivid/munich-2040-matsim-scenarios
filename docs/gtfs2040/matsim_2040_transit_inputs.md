# MATSim public-transport inputs for the 2040 scenarios

> **Current integration status (25 September 2026):** both MATSim input sets derive from the current BAU and Fast Track GTFS ZIPs. The Fast Track network contains the pedestrian-zone car restriction on 12 spatial links and one technical boundary connector, and its schedule contains the approved Mobility Hub transfer-time proxy. Full readback and focused tests pass. The final Round-5 calibration run and the BAU and Fast Track production simulations completed on the university server; their formal status and limitations are recorded in the [production scenario contract](../methodology/production_2040_scenario_contract.md). Local repository verification remains controller-free.

## Analytical purpose

This step translates the two approved GTFS representations into MATSim inputs and applies the approved Fast Track road and transfer-time assumptions. BAU represents the cleaned 2037 forecast service, while Fast Track adds the previously documented U9 trunk, U4 extension and two Nordring services. Both representations use the same base road network. Fast Track removes only `car` from 12 spatially selected links in the planned Herzog-Wilhelm-Straße and Kreuzstraße pedestrian areas and from one technical boundary connector. It also changes 790 existing directed cross-stop transfer-time values at the twelve approved Mobility Hubs. These deliberate scenario differences must be considered when interpreting results.

## Shared conversion rule

Both feeds are converted with the same Java implementation in `CreateGtfs2037MunichTransit`. The technical service date is 13 February 2026. GTFS coordinates are transformed from WGS84 to EPSG:31468, standard GTFS route types are used, stops are not merged, and GTFS minimum transfer times are included. MATSim then adds a public-transport pseudonetwork and one transit vehicle per departure. Pseudolinks permit only the MATSim `pt` mode and therefore cannot be used by cars. As the final Fast Track schedule post-processing step, `FastTrackMobilityHubs` reads the approved versioned StopArea specification, derives eligible relations from the converted schedule and applies the 20%, 15% and 10% class reductions with a 60-second floor. Analyze mode writes only a temporary preview; build mode validates a temporary XML candidate and publishes only after successful readback.

The road-network input is the project input `scenarios/munich_base_2023/studyNetworkDense.xml` (SHA-256 `FFE53A5CF7386D9255F9C1A7DF0DFD388410F9C84635582699C1826C3AF0572E`) with 212,772 nodes and 499,435 links. A semantic digest covers road-node coordinates and attributes and road-link endpoints, length, free speed, capacity, lanes, allowed modes and attributes. BAU must reproduce this digest unchanged. For Fast Track, validation adds `car` back only for digest comparison on the 13 specified links; equality with the base digest (`B0B3B94A898427CFED6220077773C5C9366374FBDC03E9828BC2B1F0E08830B5`) therefore proves that no other road property or link changed.

The machine-readable restriction is [`fast_track_pedestrian_zone_links.csv`](../../original-input-data/mvv_gtfs_2037/fast_track_pedestrian_zone_links.csv). Official planning documents establish the intended southern Herzog-Wilhelm-Straße and complete Kreuzstraße pedestrian areas. Current OSM centre lines are used only as the technical geometry for mapping those areas to the older MATSim `origid` attributes. Link `126449` is recorded separately as a technical boundary connector: after adjacent links `39774`, `39775` and `85662` lose `car`, it is an unreachable one-way exit from node `340075407`. Its restriction removes an unusable residual car link and is not an additional spatial pedestrian-zone assumption. The approved model assumption is limited to removing `car`; it does not encode pedestrian amenity, greening, access exceptions or implementation phasing.

- **Official planning scope:** [Olympic City Council decision](https://www.olympiabewerbung-muenchen.com/wp-content/uploads/Beschluss_der_Vollversammlung_Stadtrat_Muenchen.pdf) and [City of Munich Freiraumquartierskonzept](https://stadt.muenchen.de/dam/jcr%3A30b10ba4-3c93-436d-9488-90d0ec43a1a7/Freiraumquartierskonzept_Innenstadt_2022pdf.pdf) measures b1/b23.
- **Technical geometry:** current OpenStreetMap centre lines and their OSM way IDs, documented in the generated pedestrian-zone preflight; OSM is not an official project boundary.
- **Model assumption:** remove `car` from exactly 12 spatially selected MATSim links and technical boundary connector `126449` in Fast Track, retain all 13 links and every other allowed mode, and leave BAU unchanged.

## Results

| Measure | BAU 2040 | Fast Track 2040 | Difference |
|---|---:|---:|---:|
| Combined network nodes | 249,194 | 249,216 | +22 |
| Combined network links | 561,990 | 562,029 | +39 |
| Transit stop facilities | 80,764 | 80,805 | +41 |
| Transit lines | 1,733 | 1,736 | +3 |
| Transit routes | 14,303 | 14,309 | +6 |
| Departures | 70,620 | 71,300 | +680 |
| Transit vehicles | 70,620 | 71,300 | +680 |
| Minimum-transfer relations | 95,884 | 95,912 | +28 |

The Fast Track schedule contains `FT_U9` (520 departures: 259 in direction 0 and 261 in direction 1), `FT_NR_A` (80 departures) and `FT_NR_B` (80 departures). Exact U9 direction/time duplicates are absent; positive sub-two-minute intervals are deliberately retained. The U4 retains its number of departures and serves the four new directional Cosimapark and Englschalking platform records. BAU contains eight directed 180-second Poccistraße regional–U3/U6 transfers. Fast Track retains the 20 previously established directed U9–U3/U6 relations and adds eight directed U9–regional relations, giving 28 U9-related relations in total. All survive GTFS conversion and MATSim reloading. The S8 schedule signature is identical in BAU and Fast Track.

The Mobility Hub step changes exactly 790 existing directed cross-stop relations and leaves 103 self-relations unchanged, including two zero-second Ostbahnhof self-relations. It creates and deletes no relation, so the Fast Track total remains 95,912. Original affected values span 180–300 seconds; implemented values span 144–255 seconds. The 60-second floor binds no current relation. TransitLine, TransitRoute, stop-facility and departure ID sets are unchanged by this post-processing step.

Current output checksums:

| File | BAU SHA-256 | Fast Track SHA-256 |
|---|---|---|
| Combined network | `DBE26DE64FFF835F218A8B78001697C114D5C45E1B497B3B27C459CBDA586E12` | `815840C96DD8AA5B6168E14F1DD0BC92B880C4D1912A83F4D9C57AEF0C16DDA5` |
| Transit schedule | `FD8496A4B751EB46C7964FD285ED075BC7E62513152B9284FA7BCE93B28CC30B` | `EC2E07DAD41FEFCEA587B56B5C803DB3A47AD1D4ED1AC7DF5C5D830127EE0921` |
| Transit vehicles | `03F9617EB5D8E0CD464C4C8BF5B4ADC051B78431883BDD21B6A46CFB4B37CDCB` | `57F79CF0C15745A86EE517F8F2D27647F2EA323337EDBEA924B7DE1A2A6B0786` |

## Validation and reproducibility

The tool validates the converted objects before writing and loads all three compressed files again with MATSim before publication. It checks schedule-to-network links, route-network links, departure-to-vehicle and vehicle-to-type references, transfer endpoints and times, PT-only pseudolink modes, preservation of every road-network element and scenario-specific services. For the pedestrian zone it requires the exact 13 links and expected pre-build modes, removes only `car`, verifies the post-build modes after readback, and compares the normalized Fast Track road digest with the unchanged base digest. A directed breadth-first car-routing check requires all four perimeter nodes to remain mutually reachable, and a full component check requires every remaining car link to belong to the largest routable component. For Mobility Hubs it validates exactly 12 hubs, four per class, unique and existing StopAreas, 790 formula-conforming cross-stop changes, 103 excluded self-relations, unchanged direction and relation sets, unchanged schedule structure IDs and exact agreement with the approved preview references. A separate validation mode reloads both published scenarios and compares their common base road source, S8 and U4 departure counts directly.

Run the complete conversion from the project root with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario all

# Fast-Track-only Mobility Hub analysis without publication
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode analyze
```

Validate already generated outputs without repeating GTFS conversion with:

```powershell
$env:MAVEN_OPTS='-Xmx12g'
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd -q exec:java '-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit' '-Dexec.args=--validate-existing'
```

Run only the focused pedestrian-zone tests with:

```powershell
.\mvnw.cmd -q -Dtest=FastTrackPedestrianZonesTest test
```

Run only the focused Mobility Hub tests with:

```powershell
.\mvnw.cmd -q "-Dtest=FastTrackMobilityHubsTest" test
```

## Configuration activation and focused technical tests

Both production configurations use `input_transit/network-with-pt.xml.gz`, `input_transit/transitSchedule.xml.gz` and `input_transit/transitVehicles.xml.gz` relative to their scenario directory. Transit is enabled and the CRS is `EPSG:31468`. Scoring, capacity factors, strategy settings and mode-choice parameters remain aligned. Fast Track references the separate village-relocation population documented in the [population methodology](../methodology/population_2040.md) and its scenario-local network contains the pedestrian-zone restriction. BAU configuration, population and network remain unchanged. The machine-readable configuration comparison is [`matsim_2040_config_comparison.csv`](matsim_2040_config_comparison.csv).

The project runners install SwissRailRaptor whenever transit is enabled. Full configuration loading confirmed 336,208 persons in each production population and all network, schedule and vehicle references. BAU loads the unchanged common population; Fast Track loads its derived village-relocation population. Focused daytime routes verified both new common stops, a regional-to-U3/U6 transfer at Poccistraße, U6 to U9 at Münchner Freiheit, U9 interchange at Hauptbahnhof, the U9-to-U3 interchange at Impler-/Poccistraße, U4 interchange at Englschalking, complete FT-NR-A and FT-NR-B journeys, the U4 extension, absence of the new lines in BAU, and an existing representative BAU connection. The regional-to-U3/U6 test used 192 seconds of transfer/access time against the explicit 180-second minimum. The Impler-/Poccistraße test retains at least its documented 300-second requirement. No further transfer fix was required.

The final protected smoke workflow uses a deterministic four-person population created in memory and production-identical routing, network, transit, QSim and scoring settings. `P3` and `P4` are the only smoke runners; they use the canonical ignored scenario-local `output/smoke-production-r5` directories, require the target to be absent and automatically validate normal shutdown, PT use, car events and absence of stuck agents. The production population is not loaded or modified. Historical local `smoke-output/` trees are noncanonical generated evidence; they are neither inputs nor required evidence.

Use `P1` and `P2` for controller-free production-input validation. `P3` and `P4` are the final controlled smoke workflow, while `P3B` and `P4B` validate an already completed canonical smoke output without repeating QSim.

The smoke workflow establishes technical executability only; it does not validate demand forecasts or policy effects. The selected Round-5 calibration run and both production simulations completed on the university server, but the projections transfer the frozen 2019 parameters and are not separately calibrated against unavailable 2040 observations.

## Limitations and implications

The combined networks contain synthetic PT links constructed from stop sequences; they are routing infrastructure for MATSim, not surveyed railway geometry. Parent stations and other unserved GTFS facilities may legitimately have no network link, whereas every facility used by a transit route is required to have one. Vehicle capacities remain MATSim converter defaults rather than operator-specific forecasts. The Fast Track pedestrian-zone representation changes car routing only; it does not value the public-space benefit and does not yet distinguish delivery, emergency, resident, taxi or bicycle access. Mobility Hubs are represented only through existing PT transfer times: physical infrastructure, shared mobility, parking and public-space quality are absent. MCube modal-split values are not imported, and the transfer-time proxy alone cannot establish a causal modal-split effect. The Fast Track population includes simple Olympic Village and Media Village activity relocations on existing links. Event-time demand and sensitivity analysis remain outside the completed final runs.
