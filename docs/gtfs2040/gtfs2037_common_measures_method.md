# Shared BAU and Fast Track GTFS measures

## Analytical purpose and source hierarchy

This processing stage adds measures that belong to both 2040 scenarios after geographic cleaning and before Fast Track-specific additions. The substantive source is rows 13, 18 and 19 of `Infrastructure_measures.xlsx`; `common_service_specification.csv` is the version-controlled executable transcription. The raw feed and the cleaned Munich feed are never altered.

## Poccistraße

The regional stop is inserted only into trips on the six route IDs named in Excel that can be placed on the München Hbf–München Ost corridor. The existing Poccistraße parent station `106206` supplies a spatial proxy. New rail-platform IDs are used; existing underground and bus platforms are not reclassified. The proxy is a **scenario assumption**, not an official future regional-platform coordinate. Existing moving time is divided by straight-line distance around the inserted point. All following times are shifted by the derived median dwell, so trip count, departure pattern and frequency remain unchanged.

Affected trips by route are: `E 28.a BY` 40, `E 28.b BY` 80, `E 56 BY` 21, `N 28.a BY` 39, `N 28.b BY` 18 and `N 56 BY` 36. The insertion gap is selected by the smallest straight-line detour through the proxy between Hbf and Ost. Where an inbound `N 28.a BY` record omits an Ost stop, its reverse pattern and eastern calls establish the same corridor; the stop is placed on the final eastern-to-Hbf segment. Each old gap interval is divided in proportion to the two straight-line distances. The current MVV GTFS provides 2,934 positive intermediate passenger-stop observations on RE 5, RB 40 and RB 54. Their median is 60 seconds (10th–90th percentile: 60–240 seconds). The builder therefore applies 60 seconds dwell and shifts every later time by 60 seconds.

The exact and fuzzy source-stop search found no Munich stop named München Süd/Südbahnhof, Lindwurmstraße, Bavariaring or KVR. Distant matches elsewhere in Germany were rejected. Parent `106206` is the only exact local Poccistraße station object (48.125512, 11.550358). Existing children are served by subway and bus routes, so the new rail platforms remain separate IDs beneath the parent. Eight directed transfers connect the two new rail platforms to the existing U3/U6 platforms `106211` and `106212`. Their 180-second minimum follows the existing Poccistraße transfer matrix. A routed MATSim check confirms a regional-to-underground interchange with 192 seconds of transfer/access time; no zero-second interchange is introduced.

## Berduxstraße

The stop is inserted only into the exact forecast S2 route and only where Laim and Obermenzing are consecutive and the trip contains the complete regular western calling pattern (Untermenzing, Allach, Karlsfeld and Dachau). Express S-Bahn routes remain excluded. The existing Berduxstraße parent `162054` supplies a **scenario-assumption** coordinate proxy, while new rail-platform IDs prevent a bus platform from being represented as rail. Running time and dwell follow the same deterministic method as Poccistraße.

The local candidates were Berduxstraße parent `162054` (48.151769, 11.480379), its bus child `162055` 0.046 km away, Paul-Gerhardt-Allee parent `109761` 0.533 km away, Laim parent `106108` 1.888 km away and Obermenzing parent `109792` 1.395 km away. Berduxstraße is the unique exact-name and corridor-intermediate candidate. All selected S2 trips call at the complete regular pattern; separate `S…X` route IDs are excluded.

Each existing Laim–Obermenzing interval is divided in proportion to the straight-line distance via the proxy. The current MVV GTFS provides 9,079 positive intermediate passenger-stop observations on regular S2 trips. Their median is 60 seconds (10th–90th percentile: 60–120 seconds). The builder therefore applies 60 seconds dwell and shifts every later time by 60 seconds. No departure is created or removed.

## Sendlinger Spange

No GTFS row is added. Excel records no published regular-weekday timetable change. Existing S20 and other scheduled services remain unchanged. Reliability, diversion and disruption benefits are therefore represented indirectly in the scenario definition but not in a normal-day MATSim timetable.

## Reproducibility and limitations

Run `powershell -ExecutionPolicy Bypass -File src/main/scripts/gtfs2040/build_common_gtfs2037.ps1 -Mode analyze`, inspect the preflight files, then rerun with `-Mode build` only if the blocker count is zero. The resulting BAU feed becomes the input to the Fast Track builder. Straight-line allocation is reproducible but does not constitute infrastructure-based railway running-time modelling. Arrival/departure data identify dwell but cannot separately identify braking and acceleration. No additional penalty is therefore imposed; the total addition is 60 seconds per new stop. Neither proxy coordinate is an official future platform location.

## Current result

- Poccistraße modified trips: 234 (direction 0: 117, direction 1: 117)
- Berduxstraße modified trips: 203 (direction 0: 94, direction 1: 109)
- Applied regional dwell and total time addition: 60 seconds
- Applied S-Bahn dwell and total time addition: 60 seconds
- Additional braking/acceleration penalty: 0 seconds
- Clean input SHA-256: `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A`
- BAU output SHA-256: `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54`
