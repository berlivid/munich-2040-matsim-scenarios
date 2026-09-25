# BAU 2040 production analysis

## Scope and comparability

The complete regional five-percent population was simulated. The central analysis includes 182517 final selected-plan MATSim main trips whose origin and destination main activities are covered by the Munich municipal boundary (`BOTH_INSIDE`). Stage activities do not create additional trips. BAU and Fast Track use this same code and specification; no 2019 trip denominator is imposed on 2040.

## Scaling and distance definitions

Trip shares, Pkm shares, means, medians and travel times are unscaled. Absolute private-person trips, passenger-kilometres and private-car vehicle-kilometres are reported at sample scale and expanded by factor 20. Main-mode Pkm use MATSim's final `output_trips` `traveled_distance`, the complete routed main-trip distance including PT access, egress and transfers. Missing distances are not replaced by straight-line estimates. Car Fkm use the final event stream and the used network, count each traversed link once under MATSim 2025.0 first/last-link conventions and exclude transit vehicles.

PT submode Pkm use the routed in-vehicle distance between each actual event-observed boarding and alighting stop during a `BOTH_INSIDE` main trip. Route mode and link sequence come from the referenced transit line and route. Transfers partition Pkm across their used submodes but remain one main trip. PT Fkm count each transit vehicle movement once, independent of passengers. Because transit service is supplied at full scale in MATSim, PT Fkm are not multiplied by 20. Route modes outside bus, tram, subway and rail are reported separately and never reassigned; unresolved references fail validation.

## Stability and quality

Iterations 51--60 form the common late window. The reports give mean, minimum, maximum, range and linear trend in percentage points per iteration. PersonStuckEvents are reported by iteration and mode; they are diagnostic rather than automatically causal. All final files are published together only after normal shutdown, exact config and input validation, complete iteration histories, coordinate, sum, measurement and event-reference checks pass.

This file describes one validated scenario result. A BAU--Fast Track comparison is permitted only after both production runs and both analyses independently pass. No external-cost calculation or visualization is part of this pipeline.
