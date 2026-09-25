# Munich demographic and municipal-boundary inputs

This directory contains the external demographic inputs and municipal boundary
used to prepare the MATSim population for 2040.

## Files

- `population_projection_2023_2040.csv`: population by age group for 2023 and
  the assumed values for 2040.
- `munich_boundary.json`: merged municipal boundary used to distinguish City of
  Munich residents from the surrounding region.

## Population 2023

- Source: Bayerisches Landesamt fuer Statistik, GENESIS-Online
- Table: `12411-004z`
- Retrieved: 2026-07-30
- URL:
  <https://www.statistikdaten.bayern.de/genesis/online?operation=abruftabelleBearbeiten&levelindex=1&levelid=1785421119775&auswahloperation=abruftabelleAuspraegungAuswaehlen&auswahlverzeichnis=ordnungsstruktur&auswahlziel=werteabruf&code=12411-004z&auswahltext=&nummer=3&variable=3&name=GEMEIN&nummer=4&variable=4&name=AGR017&werteabruf=Werteabruf#abreadcrumb>

The values in the CSV sum to 1,488,719 residents in 2023.

## Population projection 2040

- Source: City of Munich, Demografiebericht Teil 1A 2025
- Retrieved: 2026-07-30
- URL:
  <https://stadt.muenchen.de/dam/jcr:a520e26a-2650-469e-9cc0-837cebc2807b/2025-07-16_DemografieberichtTeil1A2025_Web.pdf>

The 2040 values were prepared from the published projection using linear
interpolation. They sum to 1,752,066 residents. The precise interpolation
assumption should be retained when the projection is cited in the thesis.

## Municipal boundary

- Source: City of Munich Open Data Portal
- Dataset: Munich city-district boundaries
- Retrieved: 2026-07-30
- URL:
  <https://opendata.muenchen.de/dataset/vablock_stadtbezirke_opendata>

Preparation:

1. Download the official city-district polygons as GeoJSON.
2. Merge all district polygons into one municipal boundary.
3. Reproject the result from ETRS89 / UTM zone 32N (`EPSG:25832`) to
   DHDN / 3-degree Gauss-Krueger zone 4 (`EPSG:31468`).
4. Save the result as `munich_boundary.json`.

The reprojection is required because the MATSim Munich population uses
`EPSG:31468`. The prepared file has SHA-256
`EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26`.
GeoJSON does not embed a CRS declaration in this file; the CRS is established
by this documented preparation and its compatible projected coordinate range.
The file root is a `GeometryCollection`; its effective municipal geometry is a
valid, non-empty `MultiPolygon` with three polygon components.

## Usage

The original and prepared inputs in this directory are read by:

- `AnalyzeMunichPopulation`
- `CreateMunichPopulation2040`
- `AnalyzeMunichTripBoundary` through the read-only
  `MunichMunicipalBoundary` and `MunichTripBoundaryFilter` components

The full scaling method is documented in:

`docs/methodology/population_2040.md`

The trip-analysis scope is documented separately in:

`docs/methodology/munich_spatial_analysis_scope.md`
