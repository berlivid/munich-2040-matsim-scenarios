# BAU and Fast Track 2040 public-transport methodology

## Research design

The thesis compares two Munich transport scenarios for 2040. BAU uses the cleaned GTFS 2037 forecast as the common reference. Fast Track adds infrastructure that could be completed or accelerated in association with a hypothetical Munich Olympic Games: the U9 trunk, the U4 extension and two Nordring services. Pricing is outside the final thesis scope. Differences between model outputs are modelled scenario contrasts under stated assumptions; they are not causal forecasts with exact real-world probabilities.

The scenarios use the Munich MATSim model developed from the project’s existing five-percent Munich scenario. They share the same projected 2040 population, facilities and road network. The population method is documented in [`docs/methodology/population_2040.md`](../methodology/population_2040.md). Coordinates use `EPSG:31468`. GTFS source coordinates are WGS84. The GTFS calendar date 13 February 2026 activates the forecast feed technically and does not define either the forecast year or the 2040 scenario year.

## Evidence and modelling status

Three statuses are kept separate:

1. **Source facts** are taken from the infrastructure matrix, official project descriptions and unchanged GTFS source records.
2. **Derived values** are calculated reproducibly from the cleaned GTFS, including existing stop IDs, U6 anchor departures and comparator running speeds.
3. **Scenario assumptions** fill information that official sources do not specify, including proxy coordinates, transfer time, Nordring clock times and dwell rules.

The versioned [`fast_track_service_specification.csv`](../../original-input-data/mvv_gtfs_2037/fast_track_service_specification.csv) records service rules. [`fast_track_stop_decisions.csv`](../../original-input-data/mvv_gtfs_2037/fast_track_stop_decisions.csv) records stop reuse, new IDs, coordinate evidence and transfer assumptions. The Excel workbook remains the substantive source; the CSV files are the executable transcription.

## GTFS preparation and BAU definition

The unchanged raw feed was audited before modification. The audit is in [`gtfs2037_raw_audit.md`](gtfs2037_raw_audit.md). The Munich feed was then selected by trips serving at least two distinct stops inside the base-network extent while retaining each selected trip’s complete stop sequence. Referenced stops, parents, shapes, agencies, services and valid transfers were retained. The custom `München=1` field was used only as a plausibility check. Route types were corrected in the derived feed to standard GTFS tram `0`, subway `1`, rail `2` and bus `3`; the source files were not edited. The technical service is `service_id=1` on 13 February 2026.

The immutable processing basis is `gtfs2037_munich_clean.zip` (SHA-256 `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A`). A separate common-measures stage adds the Poccistraße regional stop and Berduxstraße S-Bahn stop to both scenarios and documents the Sendlinger Spange without changing the regular timetable. Its method is documented in [`gtfs2037_common_measures_method.md`](gtfs2037_common_measures_method.md). The resulting BAU feed is `gtfs2037_munich_bau.zip`, SHA-256 `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54`; the Fast Track builder uses this BAU feed as its baseline.

## Fast Track services

| Service | Complete stop pattern | Operational representation |
|---|---|---|
| U9 | Münchner Freiheit – Elisabethplatz – Pinakotheken – Hauptbahnhof – Esperantoplatz – Impler-/Poccistraße – Harras | New subway route; Theresienstraße and U29 excluded |
| U4 extension | Arabellapark – Cosimapark – Englschalking – Messestadt West | Extend 398 existing U4 trips without increasing frequency; Fideliopark and Pellegrinistraße excluded |
| FT-NR-A | Dachau – Karlsfeld – Lassallestraße – BMW-FIZ – Euro-Industriepark – Johanneskirchen – Englschalking – Daglfing – Trudering – Haar | 40 departures per direction, 30-minute interval, 04:30–24:00; Gronsdorf excluded |
| FT-NR-B | Feldmoching – Lassallestraße – BMW-FIZ – Euro-Industriepark – Englschalking – Riem | 40 departures per direction, 30-minute interval, 04:30–24:00; Johanneskirchen and Daglfing excluded |

The four-track Daglfing–Johanneskirchen measure is represented only as an enabling condition for Nordring operation. The GTFS does not model physical track capacity. Existing S8 rows and timetable remain unchanged.

### U9 timetable

U9 operating times are derived from U6 trips that serve both Münchner Freiheit and Harras. Direction 0 inherits the U6 departure at Münchner Freiheit; direction 1 inherits the departure at Harras. The U3 is a plausibility comparator but does not supply departures.

The builder retains one U9 trip per `direction_id + first-stop departure time`. If multiple U6 trips produce the same key, the lexicographically smallest source `trip_id` is selected. This removes 18 additional generated records at exact duplicate times. Positive intervals below two minutes are retained because their U6 sources have distinguishable full-length or short-turn patterns. No unsupported fixed headway or minimum-headway rule is imposed.

The resulting U9 contains 259 direction-0 and 261 direction-1 departures, 520 in total, with no exact duplicate departure within a direction. The first-stop departure remains identical to the selected U6 anchor departure. Each of the five intermediate stops has 20 seconds dwell, based on the directly relevant U6 median and consistent U4/U5 coding. Origin and terminal dwell are zero. Compared with the former zero-dwell representation, five intermediate dwells add 100 seconds end to end.

### Nordring timetable and dwell

Both Nordring lines use a regularized scenario timetable from 04:30 through 24:00, with 40 departures per direction at 30-minute intervals. It is designed for transparent comparison and is not an operationally validated railway timetable. Intermediate dwell remains zero in the main scenario because this follows the median coding of the S-Bahn comparators. A future sensitivity test will apply 60 seconds at intermediate Nordring stops; that sensitivity scenario has not been implemented.

### Stops, spatial proxies and transfers

Future-station coordinates are scenario proxies, not official platform locations. Existing interchange parents are reused where appropriate, while new directional platforms use stable `FT_` IDs. Esperantoplatz uses an approved square-centroid proxy. Impler-/Poccistraße uses the approved midpoint of the existing Implerstraße and Poccistraße parents. Cosimapark uses Cosimabad as a proxy. Dedicated platforms are created beneath the existing Münchner Freiheit, Hauptbahnhof and Englschalking parents.

The two U9 Impler-/Poccistraße platforms connect bidirectionally to five existing U3/U6 platforms and the two common regional-rail platforms. The Fast Track feed retains the 20 previously established directed U9–U3/U6 relations and adds eight directed U9–regional relations, giving 28 U9-related transfer relations in total. Each uses 300 seconds, a transparent walking-time scenario assumption rather than an operationally validated interchange time. The BAU feed separately contains eight 180-second regional–U3/U6 relations derived from the existing Poccistraße transfer matrix.

### Approved Mobility Hub transfer-time proxy

Twelve Fast Track Mobility Hubs are implemented as a separate scenario assumption, with four hubs in each of three analytical classes: large, medium and small. The versioned hub and StopArea inventory and deterministic relation-level references are documented in [`docs/methodology/mobility_hubs_fast_track.md`](../methodology/mobility_hubs_fast_track.md). After GTFS conversion, pseudonetwork creation and vehicle creation, the final Fast Track schedule post-processor changes exactly 790 positive existing directed cross-stop `minimalTransferTimes` inside the approved interchange complexes. It applies reductions of 20%, 15% and 10%, respectively, subject to a 60-second floor; 103 self-relations remain unchanged, and no relation or reverse direction is created. BAU is outside the measure.

## Build result

The deterministic Fast Track ZIP is `original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip`, SHA-256 `3F82E66EB7B0999D210B639BC85571CC59D06E9969FA53146FA5CA43D9578A0F`.

| Object | BAU | Fast Track | Difference |
|---|---:|---:|---:|
| Routes | 1,733 | 1,736 | +3 |
| Trips | 70,620 | 71,300 | +680 |
| Stops | 54,631 | 54,655 | +24 |
| Stop times | 1,341,931 | 1,348,045 | +6,114 |
| Transfers | 95,884 | 95,912 | +28 |

The 680 new trips comprise 520 U9, 80 FT-NR-A and 80 FT-NR-B trips. Separately, 398 existing U4 trips are extended without changing their number of departures. Their obsolete optional `shape_id` is cleared; no unsupported shape is invented. Agency, calendar and shape tables remain byte-identical to BAU.

## MATSim conversion

Both feeds use one shared converter. It transforms WGS84 to `EPSG:31468`, imports minimum transfer times, creates a PT pseudonetwork and creates one transit vehicle per departure. The original car network is preserved semantically and byte-identical source networks are used in both scenarios. New pseudolinks allow only `pt`, never `car`. Vehicle types and capacities are MATSim converter defaults and are not calibrated operator forecasts.

Current MATSim counts, activated input paths and focused integration-test results are maintained in [`matsim_2040_transit_inputs.md`](matsim_2040_transit_inputs.md). Both scenario configs activate their scenario-specific transit inputs. The final Round-5 calibration and BAU/Fast Track production simulations completed on the university server; their formal status remains documented in the [production scenario contract](../methodology/production_2040_scenario_contract.md).

## Reproduction

```powershell
# Analyze or rebuild Fast Track GTFS
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode analyze
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_fast_track_gtfs2037.ps1 -Mode build

# Rebuild only Fast Track MATSim transit inputs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode analyze
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode build

# Read-only validation of both published MATSim input sets
$env:MAVEN_OPTS='-Xmx12g'
.\mvnw.cmd -q exec:java '-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit' '-Dexec.args=--validate-existing'
```

Generated ZIP and MATSim XML files are Git-ignored. Specifications, Java code, tests, scripts and methodology documents are version-controlled.

## Limitations and pending work

- PT pseudonetwork links are synthetic routing geometry, not surveyed track alignments.
- Future station coordinates and new running times use explicit proxies and derivations.
- Neither U9 nor Nordring has an operationally validated timetable.
- Vehicle capacities remain uncertain and use converter defaults.
- Scenario configuration activation and the final P3/P4 technical smoke workflow are documented separately.
- Olympic Village and Media Village have a simple relocated-demand population representation; facilities and differentiated Games-time demand remain pending.
- The Mobility Hub transfer-time proxy is implemented and technically tested; shared mobility, parking and physical hub features remain outside the model, and the proxy alone cannot establish a causal modal-split effect.
- Remaining road and non-PT infrastructure measures are not yet implemented.
- The Nordring dwell sensitivity and other sensitivity tests remain pending. The completed final calibration and BAU/Fast Track production runs remain subject to the limitations above.
