# Methodology: Munich Population 2040

## Role in the thesis design

BAU and Fast Track use the same projected five-percent population as their demographic baseline. Fast Track then relocates a fixed number of existing `home` and `work` activity locations to the Olympic Village and Media Village. The number of persons, activity times, modes, plan structures and person attributes remain unchanged. The resulting comparison therefore represents a simple spatial demand scenario, not a causal forecast of Games-related population or employment.

## Purpose

This document describes how the common 2040 MATSim population is derived from
the existing 5% Munich population and how the Fast Track village population is
derived from that unchanged baseline. BAU continues to represent the common
demographic projection. Fast Track additionally represents a transparent,
scenario-specific relocation of existing demand.

## Relevant files

### Original and external inputs

- Base population:
  `scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml`
- Population projection:
  `original-input-data/munich-demography/population_projection_2023_2040.csv`
- Munich municipal boundary:
  `original-input-data/munich-demography/munich_boundary.json`
- Source documentation:
  `original-input-data/munich-demography/README.md`
- Measure source:
  `original-input-data/mvv_gtfs_2037/Infrastructure_measures.xlsx`, first
  worksheet, rows 20-21 and columns L-R

### Preparation classes

- Spatial analysis:
  `src/main/java/org/matsim/project/prepare/AnalyzeMunichPopulation.java`
- Population generation:
  `src/main/java/org/matsim/project/prepare/CreateMunichPopulation2040.java`
- Fast Track village relocation:
  `src/main/java/org/matsim/project/prepare/CreateFastTrackVillagePopulation2040.java`

### Generated scenario inputs

- `scenarios/munich_bau_2040/population_2040.xml`
- `scenarios/munich_fast_track_2040/population_2040.xml`
- `scenarios/munich_fast_track_2040/population_2040_fast_track.xml`

The generated XML files are excluded from Git because they are large. The two
common files are reproduced by `CreateMunichPopulation2040`; the scenario-specific
Fast Track file is then reproduced by `CreateFastTrackVillagePopulation2040`.

## Spatial classification

The public Munich population contains agents from a wider region. The official
population projection for the City of Munich must therefore not be applied to
all agents.

Each person is classified using the coordinate of the first activity whose type
is `home`:

- If the coordinate lies inside the municipal boundary, the person is treated
  as a Munich resident.
- If the coordinate lies outside the municipal boundary, the person is treated
  as a resident of the surrounding region.
- Persons without a `home` activity are not assigned to either resident group.
- Persons with a `home` activity but without a coordinate are reported
  separately.

The municipal boundary and the MATSim population use DHDN / 3-degree
Gauss-Krueger zone 4 (`EPSG:31468`). Points located exactly on the municipal
boundary are classified as inside Munich.

## Base-population result

The 5% MATSim population contains:

| Classification | Persons |
|---|---:|
| Total population | 324,043 |
| Home inside Munich | 68,770 |
| Home outside Munich | 147,655 |
| Without home activity | 107,618 |
| Without home coordinate | 0 |

For comparison, 5% of the official 2023 population of 1,488,719 would equal
74,436 persons. The MATSim population therefore does not reproduce the official
2023 total exactly. For this reason, relative growth is applied to the existing
MATSim population instead of forcing it to an absolute 5% target.

## Growth factor

The city-wide growth factor is calculated from the population projection:

```text
growth factor = population 2040 / population 2023
              = 1,752,066 / 1,488,719
              = 1.176895
```

This represents population growth of approximately 17.69%.

The target number of Munich residents in the MATSim sample is:

```text
target residents = round(68,770 × 1.176895)
                 = 80,935
```

The number of additional agents is:

```text
additional agents = 80,935 − 68,770
                  = 12,165
```

The final regional MATSim population therefore contains:

```text
324,043 + 12,165 = 336,208 persons
```

## Generation procedure

`CreateMunichPopulation2040` performs the following steps:

1. Read the 2023 and 2040 values from the projection CSV.
2. Read the complete base MATSim population.
3. Read and validate the municipal boundary.
4. Select all persons whose first `home` activity is inside Munich.
5. Draw 12,165 persons randomly from this group with replacement.
6. Deep-copy all plans and person attributes of every selected donor.
7. Assign a new unique ID to every copied person.
8. Add the copied persons to the unchanged original population.
9. Write the BAU 2040 population.
10. Copy the exact same population to the Fast Track 2040 scenario.

A fixed random seed of `2040` is used. This makes the donor selection
reproducible.

Generated IDs follow this pattern:

```text
munich2040_<clone number>_from_<source person ID>
```

## Fast Track village relocation

The production Fast Track population is derived from the unchanged local
`population_2040.xml`. WGS84 input follows the conventional latitude/longitude
notation below; the transformation passes longitude as x and latitude as y to
MATSim. Both coordinates are transformed to `EPSG:31468`.

In the inspected workbook range, cells `L20` and `L21` both cite the official
Munich City Council decision at
`https://www.olympiabewerbung-muenchen.com/wp-content/uploads/Beschluss_der_Vollversammlung_Stadtrat_Muenchen.pdf`.
Cells M-R contain no further substantive source or implementation value. The
two WGS84 centroids and relocation counts are explicit scenario inputs supplied
for this implementation; they are not inferred from the blank workbook cells.

| Site | WGS84 latitude | WGS84 longitude | EPSG:31468 x | EPSG:31468 y | Car link | Distance to link |
|---|---:|---:|---:|---:|---|---:|
| Olympic Village | 48.153739 | 11.658821 | 4,474,723.879 | 5,335,134.368 | `3215` | 2.0 m |
| Media Village | 48.142233 | 11.657852 | 4,474,646.104 | 5,333,855.294 | `416540` | 190.0 m |

The nearest-link search considers only links whose allowed modes include
`car` and measures the shortest Euclidean distance to each link segment. A
build fails if the nearest such link is more than 1,000 metres away; no road
link is invented.

The builder sorts eligible person IDs before applying fixed-seed shuffles
(`20402021` for Home and `20402022` for Work). It assigns persons in this order:

| Site | Home persons | Work persons | Changed Home activities | Changed Work activities |
|---|---:|---:|---:|---:|
| Olympic Village | 525 | 175 | 1,050 | 175 |
| Media Village | 175 | 58 | 350 | 58 |

Home-eligible persons have at least one `home` activity in the selected plan.
Work-eligible persons have at least one `work` activity and are excluded if
they were already selected for a Home relocation. This keeps the two effects
analytically distinct. Every matching activity in the selected plan is moved
to the same site coordinate and assigned the site's car link. Other plans and
all other activity fields remain unchanged. No person is added or removed.

## Scenario configuration

BAU uses the unchanged common population:

```xml
<param name="inputPlansFile" value="population_2040.xml" />
```

Fast Track uses the derived village population:

```xml
<param name="inputPlansFile" value="population_2040_fast_track.xml" />
```

Because the model continues to represent a 5% sample, the capacity factors
remain:

```xml
<param name="flowCapacityFactor" value="0.05" />
<param name="storageCapacityFactor" value="0.05" />
```

## Validation

`AnalyzeMunichPopulation` was run against the generated BAU population:

| Classification | Persons |
|---|---:|
| Total population | 336,208 |
| Home inside Munich | 80,935 |
| Home outside Munich | 147,655 |
| Without home activity | 107,618 |
| Without home coordinate | 0 |

The common BAU and Fast Track source populations remain byte-identical with
SHA-256 `FF93581E4FF105BE86408102BFA3D45CC0CC06C200763DA01B0DC344C4323C6B`.
The derived Fast Track file contains the same 336,208 persons and differs only
through the documented selected-plan activity coordinates and link references.

## Assumptions and limitations

- Only residents with a `home` coordinate inside Munich are scaled.
- Residents outside Munich remain unchanged because no surrounding-region
  projection is currently applied.
- Persons without a `home` activity remain unchanged.
- The method copies complete daily plans. Consequently, copied agents retain
  the activity coordinates, activity times, modes and other characteristics of
  their source agents.
- Multiple copies may be drawn from the same source person because sampling is
  performed with replacement.
- The copied agents do not represent newly constructed dwellings or a changed
  spatial population distribution. The 2023 spatial distribution is preserved.
- The available source population contains no age attribute. The age groups in
  the projection CSV are therefore summed to calculate the city-wide growth
  factor but are not assigned to individual MATSim agents.
- Age-specific mobility changes cannot currently be represented.
- The approach scales resident demand but does not separately model changing
  commuter, visitor or freight demand.
- BAU retains the common demand distribution; Fast Track changes only the
  documented village activity locations.
- The relocation counts are modelling assumptions for a five-percent sample.
  They are not independently derived resident or job forecasts.
- The two points are demand centroids, not official parcel boundaries or
  building locations.
- Existing car links provide routing access. No new local street network,
  facility capacity or pedestrian access is represented.
- Permanent residents, temporary athlete/media accommodation, workers and
  visitors are not separated behaviourally.
- This is a demand-scaling method, not a demographic population synthesis or a
  calibration of the 2023 base scenario.

## Reproduction and checks

In IntelliJ:

1. Run `CreateMunichPopulation2040.main()` to regenerate both 2040 population
   files.
2. Run `AnalyzeMunichPopulation.main()` with this program argument:

   ```text
   scenarios/munich_bau_2040/population_2040.xml
   ```

3. Confirm that `Total persons` equals 336,208 and `Home inside Munich` equals
   80,935.
4. Run `CreateFastTrackVillagePopulation2040.main()` to generate
   `scenarios/munich_fast_track_2040/population_2040_fast_track.xml`.
5. Run `CreateFastTrackVillagePopulation2040Test` and rerun the builder; confirm
   that the output SHA-256 is unchanged.

The line `Expected from 2023 data` in the analysis output is a 2023 reference.
It is not the expected 2040 value.
