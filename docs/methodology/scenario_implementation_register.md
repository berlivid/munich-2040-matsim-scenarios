# BAU 2040 and Fast Track 2040 implementation register

## Purpose and classification method

This register audits the complete measure matrix in `Infrastructure_measures.xlsx` against the current project state. It separates policy inclusion from technical executability: a successfully loaded MATSim transit schedule does not prove that every policy measure is substantively present.

The workbook sheet `Maßnahmen`, rows 5–31, was read from an unchanged temporary copy because the source workbook was open in another process. Most source `measure_id` cells are blank, so the register uses transparent audit identifiers `XLSX_ROW_05` through `XLSX_ROW_31`; these are not claimed to be source identifiers.

Statuses mean:

- **implemented and tested**: the intended service or mechanism is present and has passed at least structural MATSim validation; measure-specific checks already exist or the audited stop pattern is explicit;
- **partially or indirectly represented**: an operating pattern, population distribution or enabling assumption is present, but the physical or behavioural effect is incomplete;
- **specified; implementation pending**: the sites, scope and deterministic parameter rule are approved and previewed, but the production scenario input has deliberately not yet been rewritten;
- **still required**: the matrix includes the measure but the necessary representation is absent or substantively unresolved;
- **intentionally not modelled**: the matrix excludes it or the current model cannot represent its intended effect without a new calibrated method.

The CSV register is authoritative for row-level decisions: [`scenario_implementation_register.csv`](scenario_implementation_register.csv).

## Main findings

| Status | Measures | Interpretation |
|---|---:|---|
| Implemented and tested | 14 | Poccistraße, Berduxstraße, Nordring, second S-Bahn trunk, three forecast tram projects, U5 Pasing, U5 Freiham, U6 Martinsried, U9, U4 East, the pedestrian-zone car restriction and Mobility Hubs |
| Partially or indirectly represented | 7 | Daglfing–Johanneskirchen capacity, Westkreuz junction, Sendlinger Spange, Paul-Gerhardt-Allee, the new Hauptbahnhof, Olympic Village and Media Village |
| Specified; implementation pending | 0 | None |
| Still required | 0 | None |
| Intentionally not modelled | 6 | Autonomous operation, cycle express route, park corridors and the three measures excluded from both scenarios |

### Public-transport completeness

The cleaned forecast feed substantively represents the second S-Bahn trunk: representative S18X, S23X and S24X patterns serve `München Hbf tief 2. Stammstrecke`, `Marienplatz Marienhof 2. Stammstrecke` and `Ostbahnhof 2. Stammstrecke`. It also contains S20 patterns between Pasing, Heimeranplatz, Mittersendling and Solln. This is evidence of a Sendlinger-Spange-related operating path, but not evidence that additional capacity, disruption resilience or a specific frequency improvement has been modelled. The measure therefore remains indirect pending an explicit operating comparison.

The common-measures stage adds dedicated rail-platform records rather than reusing bus or underground platform IDs. Poccistraße is inserted in 234 eligible regional trips on the six Excel-specified routes. Berduxstraße is inserted in 203 regular S2 trips identified by the exact route and complete calling pattern; express routes are excluded. Project-internal current-GTFS evidence supports 60 seconds dwell at each new stop; no separate braking/acceleration penalty can be identified. Eight directed 180-second transfers connect the new Poccistraße rail platforms to U3/U6. Existing parent-station centroids are transparent scenario-assumption proxies, not official future platform coordinates. Both measures are inherited unchanged by Fast Track.

The Sendlinger Spange remains indirect by design. Excel specifies no published normal-weekday timetable change, so existing S20 and other timetable rows remain unchanged. Operational stability, diversions and disruption benefits are not represented in the normal-day MATSim simulation.

The Nordring, U9 and U4 extension are complete within their documented strategic-scenario assumptions. The four-track Daglfing–Johanneskirchen project remains only an enabling condition; neither physical railway capacity nor freight interaction is simulated.

## Population and place-based measures

BAU retains `population_2040.xml`. Fast Track uses `population_2040_fast_track.xml`, deterministically derived from the byte-identical common source population (SHA-256 `FF93581E4FF105BE86408102BFA3D45CC0CC06C200763DA01B0DC344C4323C6B`). The builder relocates 525 Home persons and 175 Work persons to the Olympic Village, and 175 Home persons and 58 Work persons to the Media Village. It creates no new persons and preserves activity times, modes, plan structures and person attributes.

The demand centroids are transformed from supplied WGS84 coordinates to `EPSG:31468`. Olympic Village uses `(4474723.879, 5335134.368)` and car link `3215` at 2.0 metres. Media Village uses `(4474646.104, 5333855.294)` and car link `416540` at 190.0 metres. These are scenario assumptions and routing anchors, not official site boundaries or invented roads.

Existing activities were counted as a diagnostic using circular spatial proxies in `EPSG:31468`. These buffers are not official development boundaries and must not be used to assign final population:

| Diagnostic area | Proxy | Persons with home activity | Persons with work activity | Persons with any activity |
|---|---|---:|---:|---:|
| Olympic Village | 1.5 km around the midpoint of Englschalking and Johanneskirchen | 1,529 | 470 | 4,217 |
| Media Village | 1 km around Trabrennbahn stop 107720 | 240 | 375 | 1,215 |
| Paul-Gerhardt-Allee | 1 km around stop 109761 | 587 | 182 | 1,534 |

The earlier diagnostics demonstrate substantial pre-existing modelled activity. The implemented relocation avoids increasing the total population, but future refinement must still distinguish:

1. **relocation**, where existing synthetic persons or activities are moved while total population is conserved;
2. **additional long-term population**, which must be reconciled with the demographic projection already used to create `population_2040.xml`;
3. **temporary Games-time demand**, which should be represented as separate visitor, worker or accommodation activity chains rather than permanent residents.

Paul-Gerhardt-Allee already contains modelled residents and activities and existing bus service. Whether the demographic forecast already incorporates the full development is unknown. No new residents should be added until the development boundary and the demographic input provenance are reconciled. No measure-specific road-network edit is currently documented.

## Road, walking, cycling and hub measures

BAU and Fast Track retain the same byte-identical base road source. The Fast Track build then applies the versioned [`fast_track_pedestrian_zone_links.csv`](../../original-input-data/mvv_gtfs_2037/fast_track_pedestrian_zone_links.csv): `car` is removed from 11 links on the planned southern Herzog-Wilhelm-Straße section and one link on Kreuzstraße. A thirteenth link, `126449`, is classified separately as a technical boundary connector. Once the three adjacent spatial links `39774`, `39775` and `85662` lose `car`, this one-way exit from node `340075407` is no longer reachable by car. Removing its `car` mode prevents an unusable residual link; it does not extend the spatial pedestrian-zone assumption. All links are retained, no other allowed mode is changed, and BAU is untouched. Exact `origid` plus spatial matching against current OSM centre lines supplies the mapping of the 12 spatial links; network topology supplies the connector classification. The City and Olympic planning documents supply the policy scope, not link-level traffic law.

This represents only the modelled car restriction. Public-space quality, greening and pedestrian amenity remain absent. The build checks all 13 links before and after modification, normalizes only these removals against the shared base-road semantic digest, verifies directed car connectivity between four perimeter nodes and requires every remaining car link to belong to the largest routable component. It stops if an activity explicitly references a restricted link. The current Fast Track population has zero such references, so no activity or population edit was made.

Cycling and park-corridor benefits cannot be represented credibly while bicycle travel is not routed on a calibrated link network. The twelve approved Fast Track Mobility Hubs have a versioned node inventory and a deterministic final schedule post-processor that changes exactly 790 existing directed cross-stop `minimalTransferTimes`; 103 self-relations remain unchanged. This is a deliberately narrow public-transport transfer proxy. Parking, shared mobility, physical hub construction and public-space quality remain outside the model. The full method is documented in [`mobility_hubs_fast_track.md`](mobility_hubs_fast_track.md).

Autonomous public transport is excluded as a technology label because automation itself has no direct MATSim passenger effect. Only independently evidenced changes to frequency, travel time or capacity could be modelled, and none are specified in the matrix.

## Prioritized next implementation list

1. **Regenerate and validate MATSim transit inputs when source GTFS changes.** Convert the rebuilt BAU and Fast Track GTFS feeds, rerun focused routing checks for both new stations, and only then regard the activated configs as current.
2. **Refine village demand without double counting.** Obtain official site boundaries and decide separately how permanent residents, relocated residents, Media Village accommodation, workplaces and temporary Games demand relate to the existing 2040 population.
3. **Plan sensitivity analysis before substantive interpretation.** Treat the Mobility Hub reductions as transparent assumptions and do not infer causal modal-split effects without an activated and calibrated mode-choice setup.

Only after these tasks should lower-priority non-PT measures be considered. The pedestrian-zone car restriction is complete under its documented link-level assumption; any later treatment of deliveries, emergency access, residents, taxis, bicycles or construction phasing requires a separate decision. The Mobility Hub transfer-time proxy is implemented and technically tested, but shared mobility and physical hub features remain outside the model. Cycle and park-corridor measures should remain outside the quantified comparison unless a calibrated active-mode model is added.

## Validation and limitations

This is an implementation audit, not a substantive simulation result. The village update changes only the derived Fast Track population and its production population reference. The pedestrian-zone implementation changes only the allowed modes of the 12 spatially selected Fast Track road links and the one technical boundary connector. The Mobility Hub implementation changes only the values of 790 existing Fast Track transfer relations; the relation set and all schedule structure IDs remain unchanged. BAU, both GTFS feeds and all non-schedule Fast Track inputs remain unchanged. The final P3/P4 four-agent technical smoke workflow and university-server BAU/Fast Track production runs are separate execution evidence; they do not turn this implementation audit into a causal result.

The forecast GTFS is a supplied planning dataset rather than an independently certified operational timetable. Physical railway capacity, construction disruption, station-concourse capacity, pedestrian amenity, cycling quality and land-use development are not automatically created by the PT pseudonetwork. Each later implementation must retain the register's distinction between source facts, modelling decisions and explicit assumptions.
