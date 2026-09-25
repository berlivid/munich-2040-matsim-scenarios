# Final audit of the infrastructure-measure matrix

## Scope and result

The first worksheet (`Maßnahmen`) of `Infrastructure_measures.xlsx` was read without writing to the workbook. All 27 filled measure rows (Excel rows 5–31) were checked against remote-synchronised commit `52c107e` and the scenario configurations, executable specifications, builders, focused tests, generated scenario inputs, English methodology and scenario READMEs. The workbook's empty `measure_id` cells are represented transparently as `XLSX_ROW_05` through `XLSX_ROW_31` in the audit CSV.

| Verified status | Count |
|---|---:|
| `implemented_directly` | 13 |
| `represented_indirectly` | 7 |
| `not_modelled_by_design` | 7 |
| `information_or_decision_missing` | 0 |
| `implementation_missing` | 0 |

There is no genuine technical implementation gap under the approved scope. This does not mean that every real-world effect is represented: the indirect and excluded measures deliberately remain limited to their documented mechanisms.

## Direct, indirect and excluded representations

Direct implementations comprise the pedestrian-zone car restriction; the Poccistraße and Berduxstraße stops; the Nordring, second trunk, forecast tram and underground projects. The physical four-track Daglfing–Johanneskirchen project, Westkreuz junction, Sendlinger Spange, Mobility Hubs, Olympic Village, Media Village and new Hauptbahnhof are classified as indirect because only an enabling timetable effect, transfer-time proxy, centroid-based demand relocation or transit-node representation is present.

Autonomous public transport, the cycle express route and park corridors are not modelled by design. Automation has no specified independent service delta; bicycle travel is teleported rather than link-routed; and public-space and biodiversity benefits have no current utility mechanism. U26 and both U1 extensions are outside both scenarios. Paul-Gerhardt-Allee is also `not_modelled_by_design`: it is general BAU urban development covered only by the shared 2040 demographic population, not a separate Olympic measure. No network or population change is required for it. Pricing is absent from the matrix and is explicitly outside the final thesis scope.

## Documentation and matrix corrections

The matrix is technically complete but not fully contradiction-free:

1. Row 6 still says `indirekt berücksichtigen`, although the approved car-access mechanism is now directly implemented on 13 Fast Track links. The public-space benefits remain unmodelled and should stay in the limitation column.
2. Row 7 contains no implementation detail in columns L–R. It should record the approved 12-hub transfer-time proxy (790 directed cross-stop relations, 20/15/10% reductions, 60-second floor), while retaining `included_in_GTFS2037=no` because the change is a MATSim schedule post-processing step rather than a GTFS edit.
3. Rows 13, 14, 19, 29 and 30 use `included_in_GTFS2037=no`. This is true for the supplied source feed but false for the project-generated BAU/Fast Track GTFS products. The column should be renamed or split into “present in source GTFS” and “present in generated scenario GTFS” to avoid ambiguity.
4. Row 22's `Modellwirkung` still suggests possible links, facilities and demand changes. That conflicts with the approved decision not to model Paul-Gerhardt-Allee separately. Its name also contains typographical errors. The existing implementation register likewise still calls it partially represented and requests additional spatial reconciliation; this is a documentation contradiction, not an implementation gap.
5. Row 5 says autonomous operation is to be considered indirectly, but no attributable frequency, running-time or capacity change exists. It should be labelled as deliberately not modelled unless such an effect is approved later.
6. The root README does not mention the implemented Mobility Hub step in its status and reproduction summary, although the Fast Track README and methodology do. This is an omission, not a conflicting technical implementation.

Rows 15, 17, 18 and 31 should not be upgraded to direct implementation: their physical capacity, resilience, station-building or junction effects are not present. No further technical step is required for rows classified as implemented, indirect or deliberately excluded; later calibration and sensitivity work are analytical validation rather than missing measure implementation.
