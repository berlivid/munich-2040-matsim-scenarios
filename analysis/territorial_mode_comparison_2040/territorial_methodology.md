# Territorial mode comparison for BAU 2040 and Fast Track 2040

## Purpose and territorial scope

This read-only comparison supports the external-cost comparison by counting modeled movement inside the City of Munich municipal boundary, regardless of residence or trip endpoint. It is an additional territorial perspective; it neither replaces the existing BOTH_INSIDE analysis nor changes the resident-target calibration scope. A trip with both endpoints outside the boundary is included as THROUGH when it has positive reconstructed main-mode distance inside the municipality.

The modal split uses final selected-plan MATSim main trips from iteration 60. Trips are constructed with TripStructureUtils and stage activities do not create extra main trips. A trip is territorially active only when its standard analysis main mode has more than 0.000001 meters of reconstructed modeled distance within the boundary.

## Main-mode reconstruction

Car NetworkRoutes are clipped link by link. PT main trips use only in-vehicle TransitPassengerRoute segments, including transfers, clipped against the corresponding scheduled transit route. Access, egress, and transfer walking alone cannot qualify a pt main trip. Teleported walk and bike legs use modeled leg distance multiplied by the inside fraction of each leg's own straight endpoint segment. No endpoint proxy is used when a required route or coordinate is unavailable: the analyzer fails closed with a route-type diagnostic.

## Pkm, Fkm, and scaling

Territorial Pkm and Fkm are transferred from the validated territorial cost-input workbooks and reconciled against their Inputs!A5:I14 numeric transfer tables before publication. Pkm are assigned to physical movement modes. Consequently, PT access, egress, and transfer walking contributes to walking Pkm but does not create a walk main trip. Mean trip distance is not calculated by dividing physical-mode Pkm by main-mode trip counts.

Private demand is a five-percent sample. Main-trip counts and demand-based Pkm are expanded exactly once by 20. PT Fkm represents full territorial service supply and remains at factor 1. Walking Fkm is not applicable. PT total Fkm is a mixed-vehicle-unit subtotal; no train-to-carriage or external cost-workbook conversion is invented. The existing documented PT pseudolink treatment is retained because Pkm and Fkm are transferred, not reconstructed here.

## Reporting day and limitations

Daily values use a technical weekday. Annual values are mechanical annualized technical-weekday equivalents: daily values multiplied by 365 and divided by 1,000,000. The simulation horizon may extend beyond 24 hours; the final event file is one simulated reporting day, not a sequence of independent daily observations.

The existing BOTH_INSIDE/resident-target mismatch remains a calibration-scope limitation. Fixed noise and infrastructure costs require separate assumptions. The comparison covers the four modeled modes—car, pt, bike, and walk—not all transport in Munich.

## Validated sources

- BAU final selected plans: `scenarios/munich_bau_2040/output/production-mode-choice/munich-bau-2040-mode-choice.output_plans.xml.gz`
- Fast Track final selected plans: `scenarios/munich_fast_track_2040/output/production-mode-choice/munich-fast-track-2040-mode-choice.output_plans.xml.gz`
- BAU territorial cost inputs: `scenarios/munich_bau_2040/output/production-mode-choice/analysis/territorial_cost_inputs/BAU_2040_territorial_cost_inputs.xlsx`
- Fast Track territorial cost inputs: `scenarios/munich_fast_track_2040/output/production-mode-choice/analysis/territorial_cost_inputs/Fast_Track_2040_territorial_cost_inputs.xlsx`
- Boundary: `original-input-data\munich-demography\munich_boundary.json` (EPSG:31468, SHA-256 `7CCDF488C7FBFF1E0AD93CE7D1858B9AC697F09E514DE661975BB231792E0051`)
