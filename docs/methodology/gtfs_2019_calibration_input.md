# Synthetic 2019 public-transport reference supply

## Purpose and status

This input is a **synthetic 2019 reference supply extracted from the combined
forecast dataset**. It is not a historical MVV GTFS snapshot. The isolated
Analyze/Build/Validate pipeline is implemented, the derived GTFS subset is
referentially closed, and the MATSim network, schedule and vehicles pass
structural, temporal and SwissRailRaptor tests. The full 324,043-person
iteration-zero validation and the subsequent final Round-5 scoring calibration
were completed on the university server. An earlier local run was memory-limited,
and an earlier server run exposed an independent configuration defect: QSim had
no finite end time and therefore continued after the timetable had finished.
Those historical issues are corrected by the deterministic service-horizon
policy described below. The final calibration decision and its remaining
limitations are recorded in the literature-based scoring documentation.

No mode choice is enabled. BAU 2040, Fast Track 2040, GTFS 2037 and the current
GTFS converter are independent and unchanged.

## Source, provenance and approved interpretation

The unchanged source is
`original-input-data/mvv_gtfs_2019/gtfs_2019.zip` (61,666,332 bytes; SHA-256
`92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4`).
It belongs to the same combined delivery as the 2037 forecast data. The
project-approved interpretation is:

- `Analyse_2019=1` defines the synthetic reference subset;
- `Prognosenetz_2037=1` is not an exclusion criterion because unchanged routes
  may occur in both scenarios;
- routes whose `Analyse_2019` value differs from `1` are excluded;
- 13 February 2026 is only the technical GTFS activation date. It is not a
  historical reference date.

The archive has no `feed_info.txt`. No independently verifiable accompanying
documentation was available for the custom scenario flags. Their meaning is
therefore a project decision rather than externally confirmed source metadata.

## Read-only source audit

| GTFS object | Raw rows |
|---|---:|
| Agencies | 42 |
| Stops | 183,258 |
| Routes | 6,175 |
| Trips | 202,643 |
| Stop times | 3,569,531 |
| Shape points | 3,460,257 |
| Transfers | 299,378 |
| Calendar rows | 1 |
| Calendar-date exceptions | 0 |

The source contains all required GTFS tables and no broken route, trip,
service, shape, stop, transfer or parent-station references. Stop sequences
and times are consistent, and all stops have numeric WGS84 coordinates. The
feed is regional rather than Munich-only (latitude 39.084388--56.650333;
longitude 2.359120--24.744194). This broad extent is not interpreted as an
error and is not clipped at the administrative city boundary.

All raw routes incorrectly have `route_type=0`. The builder applies exactly
the custom-metadata and route-ID classification sequence already validated for
the cleaned GTFS-2037 dataset. The dominant source agency uses `unknown` as
timezone; as in the 2037 cleaning step, retained blank/unknown timezones are
normalized to `Europe/Berlin`, and invalid retained agency URLs receive an
explicit `example.invalid` placeholder. These are technical GTFS-validity
corrections, not historical evidence.

## Selection and model-space reduction

The year filter retains 5,948 routes and 185,663 trips before spatial
reduction. The same rule as the cleaned 2037 feed is then applied: a trip is
selected if it calls at two or more distinct stops inside the rectangular
extent of `scenarios/munich_base_2023/studyNetworkDense.xml`. Every stop time
of a selected trip is retained, including regional sections outside the
extent. Thus, selected services are never cut at an artificial boundary.

The referentially closed subset contains:

| Object | Rows |
|---|---:|
| Routes | 1,610 |
| Trips | 59,103 |
| Stops including required parents | 54,299 |
| Stop times | 1,099,312 |
| Shape points | 1,070,463 |
| Internal transfers | 95,387 |

| Corrected mode | Routes | Trips |
|---|---:|---:|
| Bus | 1,486 | 48,101 |
| Tram | 24 | 4,778 |
| Subway | 7 | 2,081 |
| Rail | 88 | 4,077 |
| Ferry | 5 | 66 |

The reproducible subset SHA-256 is
`3C2DCAC534A5C582CC949B56820FB7B6985CEC7BD843FBFB349DD4A00EB2F52E`;
an immediate rebuild produced the identical checksum.

## MATSim conversion

`CreateGtfs2019CalibrationTransit` is separate from every 2037 builder. It loads the original public
Munich road network, converts WGS84 stops to EPSG:31468, uses `doNotMerge`,
extended route types and existing transfer data, creates a PT pseudonetwork,
and creates MATSim transit vehicles. Candidate files are written in a
temporary directory, reread and validated before publication to
`scenarios/munich_calibration_2019/input_transit/`.

| MATSim object | Count |
|---|---:|
| Network nodes | 249,003 |
| Network links | 560,926 |
| Stop facilities | 79,559 |
| Transit lines | 1,610 |
| Transit routes | 13,880 |
| Departures | 59,103 |
| Transit vehicles | 59,103 |
| Explicit transfer relations | 95,387 |

The number of MATSim stop facilities exceeds the GTFS stop count because
`doNotMerge` deliberately preserves route/direction-specific facilities.
Every served facility has a valid network-link reference, every departure has
a vehicle, all original road nodes and links remain present, and `car` remains
allowed on every original car link.

## Temporal audit and service horizon

### Source-data observations

The complete MATSim schedule was audited without starting QSim. Its latest
departure is `29:40:00`; the largest arrival and departure offsets are both
`32:35:00`; and the latest resulting vehicle arrival is `42:30:00`. In total,
1,271 departures arrive after `24:00:00`, 38 arrive after `30:00:00`, and 71
MATSim route patterns have durations longer than eight hours. The eight-hour
value is a review threshold only, not an exclusion criterion.

The four route patterns exceeding 24 hours were traced through the synthetic
subset to identical rows in the unchanged source ZIP:

| MATSim route | Source GTFS trip | Source endpoints/headsign | Source time span | Duration |
|---|---:|---|---:|---:|
| `XXX---2089556_3_0` | `40252` | Dortmund Hbf to Capljina | 05:45--38:20 | 32:35 |
| `XXX---2089556_3_1` | `40253` | Capljina to Dortmund Hbf | 05:30--38:00 | 32:30 |
| `XXX---2089558_3_0` | `40256` | international coach to Dortmund Hbf | 11:00--38:20 | 27:20 |
| `XXX---2089559_3_0` | `40257` | international coach to Tuzla | 05:30--33:00 | 27:30 |

The latest vehicle arrival belongs to route `XXX---2089560_3_1`, source trip
`40259`: its source stop times run from `25:30:00` to `42:30:00`. These values
are already greater than 24 hours in both GTFS archives. They are monotonic and
the GTFS-to-MATSim conversion merely subtracts the first stop time to obtain
route offsets. Thus, the long offsets are not caused by an incorrect rollover
calculation. They represent internally consistent overnight or multi-day
long-distance coach services in the combined dataset. No route or trip is
removed by this correction. Their substantive provenance cannot be verified
independently because accompanying feed documentation is absent.

### Technical correction and modelling rule

The former validation config inherited `qsim.endTime=undefined` from
`config_base.xml`; the `30:00:00` value visible in the XML belongs to the
unused `hermes` module and does not bound QSim. With no QSim limit, remaining
vehicles or agents kept the event queue alive after all scheduled services had
ended, explaining server logs far beyond 33,000 simulated hours. This is a
configuration defect, not a CPU-performance problem and not evidence of a
five-digit GTFS time.

The accepted service window permits monotonic overnight services whose final
vehicle arrival is strictly below 48 hours. Forty-eight hours is a transparent
fail-closed bound for this day-based calibration input: it covers every
observed accepted service through `42:30:00` but requires explicit review of a
trip spanning two complete service days or more. The operative QSim end time is
derived as the first complete clock hour strictly after the latest vehicle
arrival. The current schedule therefore produces `43:00:00`; no accepted
service is truncated.

`CreateGtfs2019CalibrationTransit` derives and writes this value whenever the
transit input is rebuilt. Before QSim, the validator now rejects an undefined
or non-finite end time, a route or vehicle exceeding the 48-hour bound, a
configured horizon different from the derived value, or a vehicle arriving at
or after QSim end time. It prints every route pattern over eight hours for
review. These checks distinguish a technical safety rule from any claim that
long-distance services are empirically representative of Munich travel.

## Validation evidence and final status

The focused JUnit tests pass. They cover an explicit finite end time, rejection
of excessive route duration, acceptance of a valid after-midnight service,
agreement between the schedule-derived and configured horizons, reference
closure and representative PT routing. The produced inputs reload successfully
and SwissRailRaptor returns representative bus, tram, subway and rail
connections. The validation config uses the unchanged 5-% base population,
`useTransit=true`, iteration 0 only, random seed 4711, two QSim threads,
`qsim.endTime=43:00:00` and no mode-choice strategy. No full local QSim was run
for the correction. The subsequent university-server validation completed and
provided the input basis for the final Round-5 scoring calibration. The old
incomplete validation directory is neither a reproducibility input nor a
prerequisite for the final configuration; the final calibration decision and
its documented residual limitations are retained separately.

## Reproducibility and version control

The approved assumptions are recorded in
`original-input-data/mvv_gtfs_2019/synthetic_2019_reference_spec.csv`. Source
and derived ZIP files, the three MATSim transit inputs, and all validation
outputs are intentionally ignored because they are large or generated. Java
builders, focused tests, the validation config, this method note and the
scenario README are suitable for version control.
