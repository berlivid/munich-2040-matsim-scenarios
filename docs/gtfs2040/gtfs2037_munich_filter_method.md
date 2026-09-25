# Method for deriving the Munich GTFS 2037 study feed

## Purpose and analytical role

This processing step creates a reproducible public transport baseline for the later comparison of the 2040 Business as Usual (BAU) and Fast Track scenarios. It does not add, remove or redesign infrastructure measures. Its purpose is narrower: to select the services that interact meaningfully with the existing MATSim study network, repair only metadata that prevents technical use, and make the source modes interpretable to MATSim.

The unchanged source remains in `original-input-data/mvv_gtfs_2037/raw`. The derived feed is a modelling input, not a corrected edition of the source dataset. Decisions made here must therefore be applied identically to both later scenarios.

## Reproducible implementation

The permanent implementation is `src/main/java/org/matsim/project/prepare/BuildMunichGtfs2037.java`, launched through `src/main/scripts/gtfs2040/build_munich_gtfs2037.ps1`. Java was used because no functional Python interpreter is installed in the project environment. The implementation still follows the requested memory-efficient approach: CSV tables are read record by record, and the large `stops.txt`, `stop_times.txt` and `shapes.txt` tables are never loaded completely into memory. Using MATSim's coordinate transformation also keeps the spatial calculation consistent with the later converter.

From the project root, reproduce the cleaned feed with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "src\main\scripts\gtfs2040\build_munich_gtfs2037.ps1"
```

A non-writing classification and boundary check is available with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "src\main\scripts\gtfs2040\build_munich_gtfs2037.ps1" -DryRun
```

The resulting ZIP is `original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_clean.zip`. It contains GTFS tables directly at the ZIP root. Its SHA-256 is `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A` and its size is 23,516,272 bytes. Two independent complete builds produced this same hash. The raw, header-only `calendar_dates.txt` is omitted because it contains no exception records and is optional when `calendar.txt` is present. The service definition itself remains unchanged in `calendar.txt`.

## Spatial and functional inclusion rule

The study boundary is derived directly from all nodes in `scenarios/munich_base_2023/studyNetworkDense.xml`. The rectangular extent in `EPSG:31468` is:

| Measure | Value |
|---|---:|
| Network nodes used to derive the extent | 212,772 |
| Minimum easting | 4,385,168.792 |
| Maximum easting | 4,543,105.115 |
| Minimum northing | 5,272,818.572 |
| Maximum northing | 5,442,831.203 |
| Width | 157.9 km |
| Height | 170.0 km |

GTFS stop coordinates are transformed from WGS84 to `EPSG:31468`. A trip is selected if it serves at least two **distinct GTFS stop IDs** whose transformed coordinates lie within the rectangular network extent. Requiring two stops identifies a service that operates within, rather than merely touches, the study area.

After a trip is selected, its complete stop sequence is retained, including stops outside the boundary. This avoids creating artificial termini and preserves the timetable context of regional services entering or leaving the study area. Routes are retained only through selected trips. The processing then retains the referenced stops, parent stations, shapes, agencies, service calendar and transfers whose two stops both remain in the derived feed.

The custom `München=1` field is not a selection rule. It is used only to compare the spatial result with the source producer's own flag.

## Counts before and after filtering

| Entity | Raw feed | Derived feed | Share retained |
|---|---:|---:|---:|
| Routes | 6,175 | 1,733 | 28.1% |
| Trips | 202,643 | 70,620 | 34.8% |
| Stops inside the boundary | 183,259 source stop records | 47,337 | 25.8% of source records |
| Stop records written, including parents and outside calls | 183,259 | 54,627 | 29.8% |
| Stop-time rows | 3,570,103 | 1,341,494 | 37.6% |
| Shape points | 3,460,257 | 1,441,848 | 41.7% |
| Internal transfers | not used as a selection unit | 95,876 | not comparable |
| Agencies | not used as a selection unit | 18 | not comparable |
| Calendar service rows | 1 | 1 | 100% |

The retained subset is materially smaller than the Germany-wide source and converted within the available memory. It is therefore technically proportionate. However, the network extent is a broad regional rectangle rather than a municipal Munich boundary; this limitation is discussed below.

## Mode classification and route-type correction

All 6,175 source routes have `route_type=0`, which means tram under the standard GTFS definition. This source value cannot distinguish buses, underground services or rail and is corrected only in the derived feed. Classification uses source route IDs, names and explicit custom fields. The script fails before writing a feed if a retained route has no reliable rule or if a rail flag conflicts with a non-rail suffix classification.

| Evidence rule, applied in listed priority | Interpreted mode | Corrected GTFS type | Retained routes |
|---|---|---:|---:|
| Munich forecast route name identifies tram | Tram | 0 | 16 |
| Augsburg forecast route name, corroborated through sampled stop sequences | Tram | 0 | 2 |
| Munich forecast route name identifies U-Bahn | Subway | 1 | 6 |
| S-Bahn name field or forecast S-Bahn name | Rail | 2 | 12 |
| `Fährlinie_BY=1` | Ferry | 4 | 5 |
| Deutschlandtakt flag or line name | Rail | 2 | 87 |
| `Analyselinie_Schiene=1` | Rail | 2 | 80 |
| `BMW-Werkslinie=1` | Bus | 3 | 25 |
| `L...` Bavarian regional family, corroborated through sampled stops | Bus | 3 | 18 |
| Named Bad Aibling/Großkarolinenfeld local families, corroborated through sampled stops | Bus | 3 | 5 |
| Route ID suffix `_0` | Tram | 0 | 24 |
| Route ID suffix `_1` | Subway | 1 | 7 |
| Route ID suffix `_2` | Rail | 2 | 8 |
| Route ID suffix `_3` | Bus | 3 | 1,438 |

The five ferry routes are retained as standard GTFS type 4. Classifying them as one of tram, subway, rail or bus would knowingly misstate the source's explicit ferry flag. No retained route remains unresolved.

The resulting mode distribution is:

| Mode | Routes | Trips | Original type | Corrected type |
|---|---:|---:|---:|---:|
| Tram | 42 | 9,198 | 0 | 0 |
| Subway | 13 | 4,777 | 0 | 1 |
| Rail, including S-Bahn and regional rail | 187 | 8,478 | 0 | 2 |
| Bus | 1,486 | 48,101 | 0 | 3 |
| Ferry | 5 | 66 | 0 | 4 |

The route-level evidence is exported in `docs/gtfs2040/gtfs2037_munich_routes.csv`. It records each route ID and name, original and corrected types, classification basis, retained trip count and the relevant source flags.

## Agency metadata corrections

Valid agency values are preserved. The derived feed makes 17 field-level technical replacements across the retained agencies:

| Source problem | Technical replacement | Scope and interpretation |
|---|---|---|
| Agency `unknown` has timezone `unknown` | `Europe/Berlin` | One required timezone repair; the agency identity remains `unknown` |
| Bare `http://` is not a valid agency URL | `https://example.invalid/gtfs-agency/<agency_id>` | 16 retained agencies: `unknown`, `1`, `3`, `4`, `5`, `6`, `7`, `8`, `9`, `11`, `12`, `32`, `36`, `55`, `71`, `1198` |

The reserved `.invalid` domain makes the replacement syntactically valid while explicitly avoiding a false claim that an unknown or incomplete agency belongs to MVV, DB or another operator. It is a technical placeholder, not factual operator information.

## Comparison with the source Munich flag

The source contains 68 routes marked `München=1`. All 68 are selected by the spatial rule, so there are no Munich-flagged routes excluded. A further 1,665 selected routes are not marked `München=1`. These include regional and local services that have at least two stops in the MATSim network extent.

This comparison shows that the custom field is a useful completeness check for the explicitly marked Munich routes, but it is too narrow to define the transport supply interacting with the regional MATSim network.

## Integrity checks

The builder stops without finalising the ZIP when any check fails. The final build passed the following checks:

- primary IDs are unique in agencies, routes, trips and stops; shape point keys are unique;
- every retained foreign key resolves to a retained route, trip, stop, parent station, agency, service or shape as applicable;
- every retained trip has stop-time rows and every stop-time trip exists;
- stop sequences are strictly increasing within each trip;
- stop and shape coordinates are finite and within valid WGS84 ranges;
- arrival and departure times use valid GTFS time syntax, departure does not precede arrival at a stop, and time does not decrease between consecutive stops;
- every retained route has a documented classification and corrected type;
- only transfers with both endpoints retained are written; and
- every root-level ZIP table can be opened again and has the expected row count.

The final GTFS reader used by MATSim reported 0 errors.

## MATSim conversion and verification

`CreateGtfs2037MunichTransit.java` converts the cleaned feed for service date 13 February 2026. This is a technical timetable date, not the scenario year. The converter reads the unchanged base network, transforms WGS84 to `EPSG:31468`, interprets standard GTFS route types, builds a PT pseudonetwork and vehicles, and writes only to `scenarios/munich_gtfs2037_clean_test/input_transit`.

Run it from the project root with:

```powershell
$env:MAVEN_OPTS='-Xmx12g'
.\mvnw.cmd -q exec:java "-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit"
```

The project compiled successfully. The converter created all three outputs and then loaded the generated network, transit schedule and vehicle file again. Generated and re-read counts were identical:

| MATSim entity | Count |
|---|---:|
| Transit stops | 80,751 |
| Transit lines | 1,733 |
| Transit routes | 14,303 |
| Departures | 70,620 |
| Transit vehicles | 70,620 |
| Total network nodes after adding the pseudonetwork | 249,190 |
| Total network links after adding the pseudonetwork | 561,977 |

No MATSim simulation was started. No BAU, Fast Track, population or final scenario configuration was changed.

## Assumptions and limitations

- The rectangle is derived from the base network's extreme nodes. It is reproducible and aligned with the model, but it is not an administrative boundary and does not measure distance to a network link. It includes a broad 157.9 by 170.0 km regional area, including services such as Augsburg local transport, regional buses and Bavarian ferries where they satisfy the same two-stop rule.
- Complete selected trips can extend outside the rectangle. This is intentional for service continuity, but it expands the stop and shape geography beyond the study boundary.
- Mode inference relies on source naming conventions, flags and encoded route-ID suffixes because the supplied `route_type` field is unusable. The inventory makes those rules auditable, but a later source revision could require revised rules.
- The `München=1` comparison confirms inclusion of all explicitly flagged routes but cannot independently validate the 1,665 unmarked routes.
- Placeholder agency URLs are technical syntax repairs and must not be interpreted as operator websites.
- MATSim warned about a pre-existing base-network link with 0.5 lanes and one generated zero-length PT link (`pt_0`). The former is not caused by this feed. The latter is compatible with coincident consecutive stop coordinates or calls in the source and should be investigated before a production simulation, even though conversion and re-read validation succeeded.
- The test conversion deliberately does not merge GTFS stops. This preserves source identities for auditing but contributes to the number of MATSim transit facilities.

For the later BAU/Fast Track comparison, this cleaned feed should be frozen as a common baseline. Scenario differences should then be introduced in separate derived feeds through explicit infrastructure rules. Otherwise, changes in filtering or classification could be mistaken for political scenario effects.

## Recommended next step

The cleaned feed is now frozen as the BAU public-transport baseline. Fast Track additions are implemented in a separate derived ZIP under version-controlled service and stop specifications; see [`gtfs2037_fast_track_method.md`](gtfs2037_fast_track_method.md). The scenario-specific transit inputs are active in the contract-compliant production configurations; focused routing and final technical-smoke methodology are maintained in [`matsim_2040_transit_inputs.md`](matsim_2040_transit_inputs.md).
