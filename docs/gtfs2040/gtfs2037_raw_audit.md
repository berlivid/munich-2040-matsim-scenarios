# Audit of the unchanged GTFS 2037 source

## Purpose and scope

This audit establishes the technical state of the provided forecast feed before spatial filtering or scenario modification. All nine files in `original-input-data/mvv_gtfs_2037/raw` were read record by record. No source row was edited. The audit is a source-data assessment, not a validation of whether the forecast services will exist in 2037 or 2040.

## Main findings

- All files are readable as comma-separated UTF-8 text with a BOM.
- Primary IDs are unique and foreign-key references are closed.
- Every trip has stop times and a referenced shape.
- Stop sequences increase and coordinates and GTFS times are syntactically valid.
- `calendar.txt` contains only `service_id=1`, active on 13 February 2026; `calendar_dates.txt` has no exceptions.
- The date is a technical activation date, not the scenario year.
- The source covers large parts of Germany and is too broad for an unfiltered Munich MATSim conversion.

| File | Source rows |
|---|---:|
| `agency.txt` | 42 |
| `calendar.txt` | 1 |
| `calendar_dates.txt` | 0 |
| `routes.txt` | 6,175 |
| `trips.txt` | 202,643 |
| `stop_times.txt` | 3,570,103 |
| `stops.txt` | 183,259 |
| `shapes.txt` | 3,460,257 |
| `transfers.txt` | 299,395 |

All 202,643 trips use `service_id=1`.

## Mode metadata problem

Every one of the 6,175 raw routes has `route_type=0`. Standard GTFS defines type 0 as tram, so the source incorrectly labels subway, rail and bus routes as tram. This value must not be used unchanged in MATSim. The source was preserved; mode classification was corrected only in the derived Munich feed using route IDs, names and source custom fields. The reproducible classification inventory is `docs/gtfs2040/gtfs2037_munich_routes.csv`.

The custom `München=1` field marks 68 routes. It is useful as a plausibility check for major urban rail services but omits relevant bus services and therefore cannot define the study feed by itself.

## Munich service evidence

The source contains forecast records for the existing Munich U-Bahn, S-Bahn and tram networks. Relevant examples include U4 terminating at Arabellapark, U5 trips to Freiham Zentrum and U6 trips to Martinsried. The audit also found planned-place names in `stops.txt` that are not necessarily served by any `stop_times.txt` record. Presence in the stop table was therefore never interpreted as proof of an operating service.

Representative U4, U5, U6, S1, S2 and S8 stop sequences and queried stop IDs were checked against both `trips.txt` and `stop_times.txt`. These checks informed later stop reuse but did not add missing infrastructure. Some apparent ferry-labelled routes were consequences of unreliable source classification/custom metadata; no Munich ferry service was inferred solely from those labels.

## Agency limitations

Most routes reference incomplete `unknown` agency metadata, and many source agency URLs are invalid placeholders. Derived processing preserves valid agency information, retains unknown attribution rather than assigning it to an operator, and supplies `Europe/Berlin` only where a valid technical timezone is required.

## Decision for subsequent processing

An unfiltered Germany-wide MATSim conversion was deliberately not performed. The source required a transparent Munich trip-based spatial filter and corrected route types before conversion. That method is documented in [`gtfs2037_munich_filter_method.md`](gtfs2037_munich_filter_method.md). Source files remain unchanged and are protected by the checksums recorded in [`original-input-data/mvv_gtfs_2037/README.md`](../../original-input-data/mvv_gtfs_2037/README.md).

## Limitations

Technical consistency does not establish timetable plausibility, operator approval, construction status or future capacity. The source’s provenance is incomplete, its calendar date is artificial for scenario activation, route-mode metadata is defective, and future stop names may represent planning placeholders. These limitations carry into the derived scenarios unless explicitly addressed and documented.
