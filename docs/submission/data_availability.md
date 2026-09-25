# Data availability

## Scope

This document distinguishes the reproducible public source repository from inputs and execution evidence that are intentionally excluded from Git. Tracking a small file in this repository does not itself establish permission to redistribute the underlying source data.

## Tracked in Git

The public source repository contains:

- Java source, tests, Maven Wrapper, project configuration, IntelliJ run configurations, and PowerShell build scripts;
- final model configurations, small specifications, manifests, and methodology;
- selected final thesis result tables, including the territorial comparison under analysis/territorial_mode_comparison_2040/;
- selected BAU analysis tables under scenarios/munich_bau_2040/production-mode-choice/analysis/.

The tracked Mobility Hub workbook and other provider-derived small resources remain subject to their providers' terms. Their presence in Git must be reviewed against the final release's redistribution decision.

## Externally obtained source data

The following are intentionally ignored and must be supplied to an authorised reproducer at the exact repository-relative paths required by the scenario manifests:

| Data family | Required location in a checkout | Purpose |
| --- | --- | --- |
| 2019 source GTFS | original-input-data/mvv_gtfs_2019/gtfs_2019.zip | Builds the synthetic 2019 reference supply and calibration transit input. |
| 2037 raw/clean GTFS source data | original-input-data/mvv_gtfs_2037/raw/ and approved clean-feed location | Rebuilds the final GTFS packages when authorised. |
| Base model data | original-input-data/munich-v1/ and scenarios/munich_base_2023/studyNetworkDense.xml plus the required plans input | Provides the base network and population/plans inputs. |
| Scenario input data | scenarios/munich_bau_2040/input_transit/, scenarios/munich_fast_track_2040/input_transit/, and the ignored 2040 population files | Supplies the final network, schedule, vehicles, and population inputs used by the two production configurations. |

Do not replace these files with a newer timetable, a similarly named local file, or an alternative population/network. The release delivery record must state source, version, SHA-256, recipient scope, licence, and required target path.

## Separately delivered GTFS packages

The final scenario packages are not in Git and must be delivered separately only when the data owner permits it:

| Scenario | Intended filename | Required checkout location |
| --- | --- | --- |
| BAU 2040 | gtfs2037_munich_bau.zip | original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_bau.zip |
| Fast Track 2040 | gtfs2037_munich_fast_track.zip | original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip |

The release owner must calculate the SHA-256 on the exact delivered archive and include it in a controlled external-data manifest. The source feed's redistribution rights, attribution requirements, and permitted recipient scope have not been confirmed by this repository; the packages must not be published merely because the transformation code is public.

## Restricted or redistribution-uncertain material

Do not publish any of the following without explicit permission and a documented distribution decision:

- MVV or other provider GTFS and planning feeds;
- municipal/provider workbooks and raw planning source data;
- base-model populations, plans, and any input that may have licensing or privacy restrictions;
- full production outputs, events, plans, or logs;
- a data archive lacking a source record, checksum, licence, and recipient scope.

The ignored original-input-data/mvv_gtfs_2026/ directory contains a legacy external reference ZIP. It is not a final pipeline input and has no tracked provenance-note file.

## Generated MATSim inputs and execution evidence

The scenario-specific input_transit bundles and 2040 population files are generated or restored external inputs. They are excluded from Git because they are large and must match the protected manifests.

Generated simulation output is intentionally excluded. The canonical smoke-output locations are:

- scenarios/munich_bau_2040/output/smoke-production-r5
- scenarios/munich_fast_track_2040/output/smoke-production-r5

The completed full-production directories are also controlled execution evidence. Their normal-shutdown logs, output configuration, final events/plans/trips/network/schedule/vehicles, and analysis packages must be retained privately when recovery analysis or examiner verification requires them. Raw runtime logs are not public source-repository artifacts.

Historical directories named smoke-output/ are noncanonical generated evidence and are not inputs to the final workflow.

## Final result tables

The tracked territorial comparison tables and selected BAU analysis tables are the public final-result layer. They preserve scientific identifiers and values. The complete Fast Track production-output/analysis package and any raw evidence needed to regenerate it remain external controlled material.

For execution order and validation boundaries, see [Reproduction and validation](reproduction.md) and the [production scenario contract](../methodology/production_2040_scenario_contract.md).
