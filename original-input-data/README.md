# Original and derived input data

This directory separates external source data, versioned processing decisions and reproducible generated artifacts from final MATSim scenario inputs.

- `mvv_gtfs_2026/` contains a Git-ignored legacy 2026 MVV reference ZIP.
  It is not a final pipeline input and has no tracked provenance-note file;
  treat it as external reference data subject to its provider's terms.
- `mvv_gtfs_2019/` contains the versioned specification for the synthetic 2019
  reference supply extracted from the combined forecast dataset. Source and
  derived ZIP files remain Git-ignored and are rebuilt on the target system.

- `mvv_gtfs_2037/raw/` contains unchanged forecast GTFS source files and is never edited by the processing tools.
- `mvv_gtfs_2037/common_service_specification.csv`, `fast_track_service_specification.csv` and `fast_track_stop_decisions.csv` are version-controlled modelling decisions.
- `mvv_gtfs_2037/generated/` contains reproducible GTFS ZIP files and preflight reports; large generated artifacts are Git-ignored.
- `munich-demography/` documents inputs used to derive the common 2040 population.
- `fast_track_2040_sources/MobilityHubs_LHM.xlsx` is the unchanged LHM Mobility Hub source workbook; `fast_track_2040_sources/mobility_hubs/approved_mobility_hubs.csv` records the approved twelve-hub Fast Track modelling specification. The specification does not itself alter a transit schedule.

See [the GTFS source README](mvv_gtfs_2037/README.md), [the public-transport methodology](../docs/gtfs2040/gtfs2037_fast_track_method.md), [the Mobility Hub methodology](../docs/methodology/mobility_hubs_fast_track.md) and [the population methodology](../docs/methodology/population_2040.md). Source data retain their providers’ licences and must not be redistributed without checking the applicable terms.
