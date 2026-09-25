# Fast Track pedestrian-zone preflight

Date: 2026-08-20
Status: read-only preflight; no MATSim network, population, configuration or workbook file was changed.

> Follow-up implementation: the subsequently approved 12-link list is now versioned in `original-input-data/mvv_gtfs_2037/fast_track_pedestrian_zone_links.csv` and applied only by the reproducible Fast Track MATSim transit-network build. This document remains the read-only geometry preflight record.

## Scope and official planning evidence

Excel row 6 identifies pedestrian zones as a Fast Track measure and proposes closing car links as the modelled mechanism. This preflight is restricted to (1) Herzog-Wilhelm-Straße from Sendlinger-Tor-Platz to Josephspitalstraße and (2) the complete Kreuzstraße. The existing northern pedestrian section between Neuhauser Straße and Herzogspitalstraße is excluded.

The official Olympic planning package describes the conversion of this southern Herzog-Wilhelm-Straße section and Kreuzstraße into pedestrian zones, with 2040 as the intended completion horizon. Its official evaluation annex states that a concrete plan and traffic concept are not yet available. The City of Munich's Freiraumquartierskonzept defines measure b1 for Herzog-Wilhelm-Straße and b23 for Kreuzstraße, connecting the latter to b1 and green-infrastructure objectives. These sources establish planning scope, not exact link-level access rules.

Official sources:

- https://www.olympiabewerbung-muenchen.com/wp-content/uploads/Beschluss_der_Vollversammlung_Stadtrat_Muenchen.pdf
- https://www.olympiabewerbung-muenchen.com/wp-content/uploads/Fragenkatalog_Olympische-Spiele_Muenchen.pdf
- https://stadt.muenchen.de/dam/jcr%3A30b10ba4-3c93-436d-9488-90d0ec43a1a7/Freiraumquartierskonzept_Innenstadt_2022pdf.pdf

## Technical geometry source

OpenStreetMap is used only as the current technical street-centreline source. A small OSM API extract for bbox 11.563,48.131,11.572,48.139 was retrieved on 2026-08-20. OSM is not treated as an official planning boundary. Selected current OSM ways are:

- Herzog-Wilhelm-Straße: [4240775, 4592507, 27747338, 30749947, 30749948, 30749949, 366231814]
- Kreuzstraße: [3927541]

Source and licence information: https://www.openstreetmap.org/copyright

## Model data and method

The inspected network is `scenarios/munich_fast_track_2040/input_transit/network-with-pt.xml.gz` in EPSG:31468. Road links contain `origid` and `type`; no street-name attribute is available. Candidate selection was limited to links whose allowed modes include `car`. Artificial PT-only links were therefore excluded before matching.

A link was retained when its `origid` matched a selected current OSM way, or when its midpoint was within 12.000 m of a target centreline and its direction differed by no more than 35.000 degrees. Current OSM ways with a known different street name were rejected from spatial-only matching. This excludes Sonnenstraße, Sendlinger-Tor-Platz, Josephspitalstraße and other adjacent named streets. Matches are labelled `origid`, `spatial`, or `both`. All car links were inspected independently of direction; reverse links are included where they exist, while current one-way sections legitimately yield only one directed MATSim link.

Selected-plan activities in `scenarios/munich_fast_track_2040/population_2040_fast_track.xml` were counted when they directly referenced a candidate link, or - because most source activities have no link reference - when their coordinates were within 10.000 m of a candidate link segment. Coordinate proximity is a diagnostic assumption, not proof of vehicular access.

## Results

| Street | Directed car links | Sum of directed link lengths (m) | Approx. undirected length (m) | Activities | Persons |
|---|---:|---:|---:|---:|---:|
| Herzog-Wilhelm-Straße | 11 | 700.730 | 609.333 | 20 | 20 |
| Kreuzstraße | 1 | 186.722 | 186.722 | 12 | 9 |

Activity types:

| Street | Activity type | Count |
|---|---|---:|
| Herzog-Wilhelm-Straße | other | 16 |
| Herzog-Wilhelm-Straße | shopping | 3 |
| Herzog-Wilhelm-Straße | work | 1 |
| Kreuzstraße | home | 6 |
| Kreuzstraße | other | 4 |
| Kreuzstraße | shopping | 1 |
| Kreuzstraße | work | 1 |

Of all affected activities, 0 used an explicit candidate-link reference and 32 were identified by the 10 m coordinate rule.

Candidate confidence:

| Confidence | Links |
|---|---:|
| high | 12 |
| medium | 0 |
| low | 0 |

No spatial-only or low-confidence candidate was found. Manual review should nevertheless focus on boundary and junction links `39774`, `39775`, and `85662` at Sendlinger-Tor-Platz, `148354` and `257668` around the Herzog-Wilhelm-Straße/Kreuzstraße/Josephspitalstraße transition, and `493302` as the single directed Kreuzstraße link. Their uncertainty concerns the intended legal closure boundary, not the automated street-name match.

## Assumptions and remaining uncertainty

- The southern Herzog-Wilhelm-Straße OSM centre lines were selected as residential ways ending at or south of latitude 48.1366000; OSM pedestrian ways and all northern segments were excluded.
- MATSim links are straight from-node/to-node segments. A midpoint corridor can miss unusually curved links or retain an intersection stub; the CSV distance, angle and confidence fields support manual review.
- `origid` refers to the OSM snapshot used to build the MATSim road network. A current OSM way may have been split or renumbered since then; spatial matching is intentionally retained as a second method.
- Link-length sums are directional and may count the same physical street twice. The undirected approximation collapses exact reverse node pairs but cannot reconstruct a legal street centreline.
- No official delivery, emergency-access, resident-access, taxi, bicycle, phasing or diversion rules are currently available. No candidate should be edited before these rules and the visual geometry check are approved.
- The proposed action in the CSV is review-only. Removing `car` is explicitly outside this preflight.

## Files for visual review

- `candidate_links.geojson`: overlay in GIS against a current basemap.
- `candidate_links_map.html`: dependency-free schematic comparison of OSM centre lines and MATSim links.
- `candidate_links.csv`: inspect all low/medium-confidence rows, `origid`, distance and angle, especially at street junctions.
