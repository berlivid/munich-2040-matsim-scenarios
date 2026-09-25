# Fast Track Mobility Hubs: approved transfer-time proxy

## Purpose and current status

This method documents the implemented and tested representation of twelve Mobility Hubs as a versioned Fast Track 2040 modelling assumption. The production builder changes only approved values of existing MATSim `minimalTransferTimes`. The BAU scenario is outside this measure and remains unchanged.

The authoritative project specification is [`approved_mobility_hubs.csv`](../../original-input-data/fast_track_2040_sources/mobility_hubs/approved_mobility_hubs.csv). The relation-level preview is [`approved_transfer_relations_preview.csv`](../../generated/mobility_hubs_preflight/approved_transfer_relations_preview.csv), and the exclusions and aggregate checks are recorded in [`excluded_transfer_relations_review.csv`](../../generated/mobility_hubs_preflight/excluded_transfer_relations_review.csv) and [`transfer_time_change_summary.csv`](../../generated/mobility_hubs_preflight/transfer_time_change_summary.csv).

## Evidence, project observations and modelling decisions

Three evidence levels are kept separate.

1. **LHM source data.** `original-input-data/fast_track_2040_sources/MobilityHubs_LHM.xlsx`, sheet `ruhver_mp_standort_point`, contains 120 source mobility-point records. Its coordinates and mode/parking fields describe the supplied LHM locations; they do not define MATSim StopAreas or an official large/medium/small classification.
2. **MATSim observations.** The preflight transformed source coordinates from `EPSG:25832` to the model CRS `EPSG:31468`, identified nearby stops, and counted the lines, routes, modes and explicit transfer relations actually present in the Fast Track schedule. The subsequent node review assigned each shortlisted point to one coherent interchange node and excluded unrelated nearby stops. The reviewed pre-implementation schedule had SHA-256 `D032A77481947C189EB69E486A132CF9348B4DD8A89384BDCFE8019C19C6A3B6`; the implemented production schedule is `scenarios/munich_fast_track_2040/input_transit/transitSchedule.xml.gz`, SHA-256 `EC2E07DAD41FEFCEA587B56B5C803DB3A47AD1D4ED1AC7DF5C5D830127EE0921`.
3. **Model assumptions.** Eligibility and the initial ranking used the documented preflight rule: a source point had to be no more than 150 metres from its assigned served stop and the node had to have at least two distinct MATSim TransitLines; eligible points were ordered by line count, mode count, car-sharing spaces and `mp_id`. The final node review then corrected radius-only groupings. The final twelve and their four-large/four-medium/four-small classes were approved as an analytical scenario convention. These classes are not an official LHM classification.

The final selection is:

| Rank | Class | LHM source point | Approved MATSim interchange node | Included directed relations |
|---:|---|---|---|---:|
| 1 | large | Ostbahnhof/Belfortstraße | complete Ostbahnhof complex | 114 |
| 2 | large | ZOB | ZOB/Hackerbrücke complex | 30 |
| 3 | large | Moosach Bahnhof | Moosach station complex | 132 |
| 4 | large | Münchner Freiheit | Münchner Freiheit | 72 |
| 5 | medium | Rosenheimer Platz | Rosenheimer Platz | 20 |
| 6 | medium | Baaderstraße | Isartor proxy | 20 |
| 7 | medium | Giesinger Bahnhof | Giesing station complex | 182 |
| 8 | medium | Scheidplatz | Scheidplatz/Scheidplatz Süd complex | 92 |
| 9 | small | Hohenzollernplatz | Hohenzollernplatz | 12 |
| 10 | small | Grillparzerstraße | Grillparzerstraße | 30 |
| 11 | small | Rotkreuzplatz | Rotkreuzplatz | 56 |
| 12 | small | Galeriestraße | Odeonsplatz proxy | 30 |

Baaderstraße is assigned transparently to Isartor because the LHM point at Baaderstraße 2 has no separate MATSim interchange and Isartor is the reviewed served node within the original 150-metre assignment. Galeriestraße is assigned to Odeonsplatz because the source point lies within the reviewed radius and its bus/U-Bahn function corresponds to that node; the separate Von-der-Tann-Straße surface stop is excluded. Both are proxies, not claims that MATSim contains interchange nodes named Baaderstraße or Galeriestraße.

The complete Hohenzollernplatz StopArea `106583` includes the confirmed U2 facilities `106586` and `106587`. They were just outside the initial platform-level radius, but belong to the same StopArea and therefore remain part of the approved node. Conversely, Marienplatz, Rindermarkt, St.-Jakobs-Platz and Viktualienmarkt are separate stop complexes. After this separation, the Rindermarkt point was only rank 20 in the corrected review and was not approved for the final twelve.

## Transfer-time rule

The model approximates hub quality only through faster transfers already represented explicitly in the Fast Track transit schedule. The class-specific reductions are 20% for large hubs, 15% for medium hubs and 10% for small hubs. For each eligible directed relation:

`proposed_time = max(60 seconds, round(original_time × (1 − reduction)))`

The procedure is deliberately conservative:

- both endpoints must belong to the approved StopAreas or explicitly confirmed facilities of the same hub;
- `fromStopId` and `toStopId` must differ;
- the directed relation must already exist and have a positive finite value;
- each existing direction is handled separately;
- no reverse direction, self-relation, stop, line or transfer relation is created.

The converted pre-implementation schedule contains 893 explicit relations wholly inside the approved hub complexes. The builder changes 790 directed cross-stop relations and excludes 103 self-relations. There are no other within-complex exclusions, no explicit relations crossing an approved hub boundary, no overlapping hub facility sets and no missing approved IDs. Original included values range from 180 to 300 seconds (mean 190.633); implemented values range from 144 to 255 seconds (mean 159.304). The 60-second floor binds zero relations. The complete schedule retains exactly 95,912 directed transfer relations: no relation or reverse direction is created or deleted.

## Interpretation and limitations

The MCube study describes Mobility Hubs as places that combine public transport with shared mobility and related services. Its simplified impact calculation assumes that shorter transfers, better information and a broader offer may reduce **perceived public-transport travel time** by 10–20%. This range is used here only as orientation for a transparent transfer-time proxy. The study's stated network, elasticity and citywide modal-split effects are not imported as MATSim parameters or treated as validation targets. See MCube Consulting GmbH, *Kurzanalyse zur Wirtschaftlichkeit und Nachhaltigkeit der Olympia Bewerbung 20XX in München – Abschlussbericht*, October 2025, pp. 42–43 ([study PDF](https://mcube-cluster.de/wp-content/uploads/2025/10/251009_MCube_Olympia_07.pdf)).

This representation does not model shared-mobility supply, car-sharing parking, bicycle parking, physical construction, accessibility works, information systems or public-space quality. It also does not assert that a percentage reduction in a schedule transfer parameter equals the same percentage change in observed interchange time or generalised journey cost.

The current scenario does not use an actively calibrated mode-choice strategy. Consequently, this measure alone cannot support robust claims about modal-split shifts. Any later simulation result must be interpreted as sensitivity to the stated transfer-time assumption, not as a forecast of the MCube modal-split effects.

## Reproducibility, validation and pipeline position

The specification fixes stable hub IDs, source `mp_id` values, approved StopAreas, the two proxy decisions, class reductions, the 60-second floor, Fast-Track-only scope and approval status. `FastTrackMobilityHubs` reads this specification directly. The generated review CSVs are comparison references, not the sole production input. The implementation derives facilities and relations from each newly converted schedule and fails closed if its 790 changes and 103 exclusions differ from those references.

`CreateGtfs2037MunichTransit` remains the single production pipeline. It converts the Fast Track GTFS, creates the PT pseudonetwork and vehicles, applies the Mobility Hub step as the final schedule post-processing operation, validates the in-memory result, writes all candidate files to a temporary directory, reloads them, repeats structural and relation-level validation, and only then publishes. `build_matsim_2040_transit.ps1` exposes `-Mode analyze`, which writes only a temporary preview under `target/`, and `-Mode build`, which uses the validated temporary-publication path.

The focused test class covers all three reductions, the 60-second floor, self-relation exclusion, direction preservation, unchanged outside relations, unknown StopAreas, unexpected relation counts and deterministic repetition. Ten focused tests passed. Readback retained 1,736 TransitLines, 14,309 TransitRoutes, 80,805 stop facilities, 71,300 departures and 95,912 transfer relations. Network, vehicles, population and configuration remained byte-identical. An iteration-zero Fast Track smoke test used two synthetic persons: the PT traveller boarded and arrived, the car traveller arrived, and neither person became stuck. These checks establish technical executability, not behavioural validity or a calibrated policy effect.

Reproduce the focused steps from the project root:

```powershell
# Analyze the newly converted Fast Track schedule without publishing inputs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode analyze

# Run only the Mobility Hub unit tests
.\mvnw.cmd -q "-Dtest=FastTrackMobilityHubsTest" test

# Build, reread, validate and publish the Fast Track inputs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\src\main\scripts\gtfs2040\build_matsim_2040_transit.ps1 -Scenario fast-track -Mode build
```

The next substantive step is sensitivity analysis around the class reductions and, only after a calibrated mode-choice setup exists, cautious scenario interpretation. The current implementation must not be interpreted as a causal modal-split forecast.
