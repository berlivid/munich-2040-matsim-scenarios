# GTFS 2037 source and derived feeds

The nine forecast GTFS text files were copied unchanged to `raw/`, separate from the current MVV feed and from scenario outputs. Their precise external export tool, export date and original pre-copy location were not supplied and cannot be reconstructed from file metadata. They must therefore be described as the provided “GTFS 2037” planning dataset, not as an official published MVV timetable. `Infrastructure_measures.xlsx` is the thesis author’s substantive scenario matrix and is not part of GTFS.

## Calendar and format

The source uses comma-separated UTF-8 files with a BOM and additional non-standard analysis columns. `calendar.txt` contains only `service_id=1`, active on 13 February 2026; `calendar_dates.txt` contains no exceptions. This date is a technical service date used to activate the forecast feed. It is not the 2037 data label or the 2040 scenario year.

## Unchanged source checksums

Counts exclude headers; checksums are SHA-256.

| File | Rows | SHA-256 |
|---|---:|---|
| `agency.txt` | 42 | `D0C5A9975C44EC8F7627A371F75892A87F9C3AC89EA47C8B6EB3B289AEDC3436` |
| `calendar.txt` | 1 | `67C5106670978CDE7E56AFE213A104A774C7A2BC6BB7D23AA9619F7FD8B74660` |
| `calendar_dates.txt` | 0 | `5AEEF05EDF27E5AE331D6E3014CE4C3CFE9346C1A31EABB1473E42904B50D896` |
| `routes.txt` | 6,175 | `55B4A901E34D96109A7D1447267AA28E37952FFF0B8CCE97B3ED621F490F0828` |
| `trips.txt` | 202,643 | `AC6F5517DD04E8623EFADC159E9A85E46EF10E7A790A75122CDFCBE245AA5A5A` |
| `stop_times.txt` | 3,570,103 | `6487553B967AFDBA45F78B3D2CD188433AA624633829AFBCF10D6E0C7EBB8FDF` |
| `stops.txt` | 183,259 | `FEFDCEAF0BC7A81DE65A83F80147E46575C354ABB8899A8ECB073A0C2B22E922` |
| `shapes.txt` | 3,460,257 | `E765F6E9DB4EF101BA9D78E8F8645D873F91A955B79F8006CF3412B667C32146` |
| `transfers.txt` | 299,395 | `BAA04E338567F719CAA1DC1DB501E8E8D0979BBE05B59AC6EE1423F5DDF6BCCA` |

`Infrastructure_measures.xlsx` has SHA-256 `D96EA464BE764965CF4FD2760CCE17DD8AEB0A2661F7B82525678F0229122460` for the version used to transcribe rows 13, 18 and 19. The workbook remains an unchanged external substantive input.

## Derived artifacts

| Artifact | Purpose | SHA-256 |
|---|---|---|
| `generated/gtfs2037_raw.zip` | Unchanged ZIP packaging of the nine source files | `571AFF1D55354F8819D4AAB75F2240F0A780773BF45BC5053FCB79B90645918D` |
| `generated/gtfs2037_munich_clean.zip` | Spatially filtered and mode-corrected immutable processing basis | `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A` |
| `generated/gtfs2037_munich_bau.zip` | Clean feed plus shared Poccistraße and Berduxstraße measures | `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54` |
| `generated/gtfs2037_munich_fast_track.zip` | Common BAU feed plus approved Fast Track services | `3F82E66EB7B0999D210B639BC85571CC59D06E9969FA53146FA5CA43D9578A0F` |

The generated artifacts are Git-ignored. Their executable rules are version-controlled in `common_service_specification.csv`, `fast_track_service_specification.csv`, `fast_track_stop_decisions.csv`, Java source code, tests and PowerShell launchers.

## Known source limitations

All 6,175 raw routes use `route_type=0`, which means tram in standard GTFS and is incorrect for subway, rail and bus. Most routes use incomplete `unknown` agency metadata, all route short names are empty, and the source covers large parts of Germany. The custom `München=1` flag does not identify all relevant Munich bus services and is not a sufficient spatial filter. These problems are corrected or handled only in derived feeds; `raw/` remains unchanged.

See the [raw audit](../../docs/gtfs2040/gtfs2037_raw_audit.md), [filter method](../../docs/gtfs2040/gtfs2037_munich_filter_method.md) and [scenario method](../../docs/gtfs2040/gtfs2037_fast_track_method.md).
