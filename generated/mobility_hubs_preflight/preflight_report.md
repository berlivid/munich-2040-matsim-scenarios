# Fast Track mobility-hub preflight

> Read-only diagnostic prepared from the LHM mobility-point workbook and the existing Fast Track MATSim transit schedule. No Excel, schedule, network, population, configuration, transfer-time or vehicle file was modified.

## Scope and sources

- **LHM source data:** `original-input-data\fast_track_2040_sources\MobilityHubs_LHM.xlsx`, sheet `ruhver_mp_standort_point`, 120 populated location rows, file size 36370 bytes, source timestamp retained as 1787224949589 ms since epoch.
- **MATSim observation:** `scenarios\munich_fast_track_2040\input_transit\transitSchedule.xml.gz` (SHA-256 `D032A77481947C189EB69E486A132CF9348B4DD8A89384BDCFE8019C19C6A3B6`), with 80805 stop facilities, 1736 TransitLines, 14309 TransitRoutes and 95912 explicit minimal-transfer relations.
- **CRS transformation:** coordinates were read from the Excel `shape` field as EPSG:25832 and transformed through the project-resolved GeoTools/MATSim transformation stack to EPSG:31468. WGS84 was produced only for standards-compliant GeoJSON geometry.

## Method

Every MATSim stop facility within 150 metres of each transformed LHM point was retained. Platform variants were grouped by MATSim `stopAreaId`; a parent facility was grouped with platforms that reference its ID, while otherwise identical stop names formed a fallback group. Line, route and mode counts use only TransitRoutes that actually call at a matched stop. Distinct lines are MATSim TransitLine IDs, and routes are unique line/TransitRoute combinations.

The possible directed transfer-pair count is `L × (L − 1)` for `L` distinct lines. This is a structural indicator, not a timetable-feasibility calculation. Existing transfer times list only explicit MATSim minimal-transfer relations whose endpoints fall inside the matched stop IDs or their stop-area groups. Absence of such a relation does not imply a zero-second transfer.

The LHM flags are compared with observed MATSim modes: `bus` to bus, `tram` to tram, `u_bahn` to subway, and `s_bahn` to the MATSim rail/train category. The last comparison is only a proxy because the schedule mode does not separate S-Bahn from regional rail. Observed modes in the matched data were: bus, rail, subway, tram.

## Selection rule and model assumption

A site is eligible only when its nearest matched stop is at most 150 metres away and at least two distinct MATSim lines serve stops within the radius. Eligible sites were sorted deterministically by distinct line count (descending), distinct MATSim mode count (descending), carsharing spaces (descending), and numeric `mp_id` where possible (ascending; text fallback otherwise). Ranks 1–4 are labelled **large**, 5–8 **medium**, and 9–12 **small**. This four/four/four size split is a modelling assumption for a later MATSim representation and is not an official LHM size classification. Ranks 13–20 are retained only as alternatives.

Eligible sites: **79**; selected sites: **12**; alternatives retained: **8**.

## Selected sites

| Rank | Size | mp_id | LHM name | Nearest stop (m) | Lines | Routes | MATSim modes | Carsharing | Confidence | Issue |
|---:|---|---:|---|---:|---:|---:|---|---:|---|---|
| 1 | large | 312 | ZOB | 29.1 | 52 | 427 | bus;rail;tram | 6 | high | none |
| 2 | large | 506 | Ostbahnhof/Belfortstraße | 23.1 | 25 | 139 | bus;rail;subway;tram | 6 | medium | LHM tram=Nein vs MATSim tram=true |
| 3 | large | 62 | Münchner Freiheit | 24.1 | 18 | 151 | bus;subway;tram | 6 | medium | embedded line break in name |
| 4 | large | 503 | Rosenheimer Platz | 16.7 | 18 | 315 | bus;rail;tram | 5 | medium | LHM bus=Nein vs MATSim bus=true |
| 5 | medium | 203 | Baaderstraße | 87.4 | 18 | 315 | bus;rail;tram | 3 | medium | none |
| 6 | medium | 49 | Giesinger Bahnhof | 23.1 | 17 | 186 | bus;rail;subway;tram | 6 | high | none |
| 7 | medium | 1002 | Moosach Bahnhof | 28.1 | 14 | 103 | bus;rail;subway;tram | 9 | high | none |
| 8 | medium | 402 | Scheidplatz | 2.7 | 14 | 87 | bus;subway;tram | 11 | high | none |
| 9 | small | 510 | Grillparzerstraße | 30.7 | 14 | 85 | bus;tram | 4 | high | none |
| 10 | small | 7 | Rindermarkt | 71.9 | 14 | 298 | bus;rail | 0 | medium | LHM bus=Nein vs MATSim bus=true; LHM u_bahn=Ja vs MATSim subway=false |
| 11 | small | 27 | Rotkreuzplatz | 13.6 | 13 | 83 | bus;subway;tram | 7 | high | none |
| 12 | small | 401 | Hohenzollernplatz | 41.1 | 12 | 48 | bus;tram | 8 | medium | LHM u_bahn=Ja vs MATSim subway=false |

## Alternative candidates 13–20

| Rank | mp_id | LHM name | Nearest stop (m) | Lines | Modes | Carsharing | Confidence | Issue |
|---:|---:|---|---:|---:|---|---:|---|---|
| 13 | 1 | Galeriestraße | 68.6 | 11 | bus;subway | 7 | high | none |
| 14 | 1301 | Arabellapark | 33.2 | 11 | bus;subway | 7 | medium | LHM tram=Ja vs MATSim tram=false |
| 15 | 56 | Pasinger Marienplatz | 44.7 | 11 | bus;tram | 4 | medium | embedded line break in name |
| 16 | 14 | Kidlerplatz | 102.7 | 11 | bus | 7 | medium | embedded line break in address; LHM u_bahn=Ja vs MATSim subway=false |
| 17 | 37 | Messestadt West | 10.6 | 10 | bus;subway | 6 | high | none |
| 18 | 42 | Trudering Bahnhof Süd | 42.4 | 10 | bus;rail | 4 | medium | LHM u_bahn=Ja vs MATSim subway=false |
| 19 | 1905 | Fürstenried West | 15.2 | 10 | bus;subway | 4 | high | none |
| 20 | 12 | Kolumbusplatz | 36.7 | 9 | bus;subway | 7 | medium | embedded line break in name |

## Data problems and uncertainty

- Invalid or blank values in the four requested LHM mode flags: **1**. They are preserved as invalid and are not interpreted as `Nein`.
- Embedded line breaks in names or addresses: **20**. CSV output normalizes display whitespace while recording the issue.
- LHM/MATSim mode-flag mismatches: **48** across all 120 sites ({bus=20, s_bahn=5, tram=8, u_bahn=15}). These are observations, not corrections to the LHM source.
- The 150-metre circle is a diagnostic matching radius rather than an official hub boundary. Nearby stops can represent adjacent but operationally separate stop areas.
- MATSim line and route counts describe the supplied Fast Track schedule on its technical service date; they do not measure frequency, passenger demand, interchange quality or implementation feasibility.
- The PNG is a projected-coordinate diagnostic map without a street basemap. The GeoJSON should be used for precise GIS inspection against authoritative spatial layers.

## Output interpretation

The ranked CSV files implement the stated deterministic rule only. They do not alter transfer times or activate hubs in MATSim. A later implementation still requires a separate decision on what a large, medium or small hub changes in the model.

### Workbook issue inventory

- Excel row 3 / mp_id 41: embedded line break in name
- Excel row 5 / mp_id 9: embedded line break in name
- Excel row 9 / mp_id 14: embedded line break in address
- Excel row 17 / mp_id 38: embedded line break in address
- Excel row 18 / mp_id 56: embedded line break in name
- Excel row 25 / mp_id 23: embedded line break in name
- Excel row 34 / mp_id 45: embedded line break in name
- Excel row 39 / mp_id 29: embedded line break in name
- Excel row 40 / mp_id 13: embedded line break in name
- Excel row 46 / mp_id 34: embedded line break in address
- Excel row 48 / mp_id 11: embedded line break in name
- Excel row 49 / mp_id 46: embedded line break in name
- Excel row 49 / mp_id 46: embedded line break in address
- Excel row 61 / mp_id 62: embedded line break in name
- Excel row 70 / mp_id 59: embedded line break in name
- Excel row 97 / mp_id 12: embedded line break in name
- Excel row 105 / mp_id 4: embedded line break in name
- Excel row 109 / mp_id 32: embedded line break in address
- Excel row 112 / mp_id 3: embedded line break in name
- Excel row 113 / mp_id 310: invalid tram_vorhanden value ''
- Excel row 117 / mp_id 16: embedded line break in name
