# Munich spatial scope for calibration and scenario analysis

## Purpose and normative decision

This method defines the spatial observation sample for the later mode-choice
calibration and for comparisons between the 2019 reference, BAU 2040 and Fast
Track 2040. It does not alter simulation demand or supply.

The approved rule is an **origin-and-destination filter**: a main trip belongs
to the primary Munich analysis sample only if both its origin main activity and
its destination main activity lie inside, or exactly on, the administrative
boundary of the City of Munich. `ORIGIN_ONLY`, `DESTINATION_ONLY`,
`BOTH_OUTSIDE` and invalid-coordinate trips are excluded from the primary
sample and reported separately. This is a normative analytical decision, not a
property inferred from MATSim.

The entire regional population remains in every simulation. Regional people,
boundary-crossing trips, the complete road network and complete public-
transport services continue to influence traffic, routing, congestion,
transfers and vehicle occupancy. Only the later calibration and result
aggregation selects trips. No filtered population is produced.

## Administrative boundary and coordinate reference

The spatial reference is
`original-input-data/munich-demography/munich_boundary.json`, SHA-256
`EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26`.
The project input documentation identifies the source as the City of Munich
Open Data Portal dataset of city-district boundaries, retrieved on 30 July
2026. The preparation merged the official district polygons and transformed
them from ETRS89 / UTM zone 32N (`EPSG:25832`) to DHDN / 3-degree
Gauss-Krueger zone 4 (`EPSG:31468`).

The GeoJSON file does not embed a CRS declaration. `EPSG:31468` is therefore
established by the documented preparation process and corroborated by the
coordinate envelope, not discovered from GeoJSON metadata. Its root is a
`GeometryCollection` containing the merged municipal geometry; the effective
JTS geometry is a valid, non-empty `MultiPolygon` with three polygon components
and envelope
`x=4,452,551.528--4,479,484.621`,
`y=5,324,954.154--5,345,694.697`. This range is compatible with the MATSim
population coordinates in `EPSG:31468`.

`MunichMunicipalBoundary` loads, validates and prepares the geometry once per
analysis process. Point classification uses JTS `covers`, rather than
`contains`, so an activity exactly on the administrative boundary counts as
inside. Missing and non-finite coordinates fail closed.

## Main-trip construction

The filter applies only to trips between consecutive MATSim main activities.
It uses `TripStructureUtils` with MATSim's `StageActivityTypeIdentifier`.
Consequently, routed stage activities such as `pt interaction`, `car
interaction`, `bike interaction`, `walk interaction`, `ride interaction` and
other MATSim interaction types do not become trip origins or destinations.
This is relevant for later routed plans even though the current input
population contains one direct leg per trip and no routed PT stages.

Each main trip receives exactly one spatial category:

- `BOTH_INSIDE`: origin and destination are covered by the boundary;
- `ORIGIN_ONLY`: only the origin is covered;
- `DESTINATION_ONLY`: only the destination is covered;
- `BOTH_OUTSIDE`: neither endpoint is covered;
- `INVALID_OR_MISSING_COORDINATE`: at least one endpoint has no finite
  coordinate.

The classifier is read-only. It does not change activities, legs, routes,
plans, selected-plan state or person attributes.

## Relation to resident and territorial principles

This rule is not a resident principle. A resident principle first selects
people by home location and normally retains their trips even when one or both
trip endpoints are outside Munich. The former calibration-plan proposal used
that approach and has been superseded.

It is also narrower than a full territorial principle. A territorial analysis
may include travel that crosses or traverses the city and may allocate only the
distance travelled inside the boundary. The approved rule instead selects
complete trips with two municipal endpoints. It excludes inbound, outbound and
through trips from the primary metric even though those trips remain simulated.

This closed-endpoint definition provides a stable common unit for comparison
with dissertation results only where the dissertation applies the same
origin-and-destination geography and compatible trip/main-mode definitions.
Matching the boundary alone does not establish equivalence: reference year,
survey weights, stage handling and modal categories must also agree. Any
remaining difference in definitions must be disclosed rather than corrected by
changing the simulated population.

## Read-only preflight evidence

The original five-percent calibration population was streamed once. It
contains 324,043 persons, 324,043 selected plans and 540,468 main trips. No
person lacks an identifiable main trip and no trip has an invalid endpoint
coordinate.

| Spatial category | Main trips | Share |
|---|---:|---:|
| `BOTH_INSIDE` | 160,603 | 29.715543% |
| `ORIGIN_ONLY` | 29,926 | 5.537053% |
| `DESTINATION_ONLY` | 36,267 | 6.710296% |
| `BOTH_OUTSIDE` | 313,672 | 58.037109% |
| `INVALID_OR_MISSING_COORDINATE` | 0 | 0.000000% |

No main-activity endpoint is exactly on the boundary in the input population;
30 distinct endpoint activities per selected plan are within one metre. The
one-metre diagnostic is not a classification tolerance: `covers` remains the
operative rule.

The large exclusion share is expected for a regional model and has direct
interpretive consequences. Absolute Munich results refer only to trips with
two municipal endpoints; they are not total traffic within Munich, total travel
by Munich residents or total impacts experienced on Munich links. Regional
traffic still affects the simulation state but is outside the primary result
denominator.

## Reuse and current limits

Exactly the same versioned boundary, trip construction, category logic and
main-mode logic must later be applied to the synthetic 2019 reference, BAU
2040 and Fast Track 2040. BAU and Fast Track must never receive different
spatial filters.

The preflight validates classification only. It does not calculate a final
modal split, passenger-kilometres or vehicle-kilometres and does not activate
mode choice. Calibration still requires compatible observed modal-share
targets, a decision on car availability and a separate non-production
calibration configuration.
