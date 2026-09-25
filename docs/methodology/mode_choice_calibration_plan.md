# Mode-choice calibration plan

## Purpose and evidence boundary

This document prepares a deliberately small mode-choice calibration for the Munich MATSim project. It does not activate mode choice and does not change a production configuration. It separates three types of information:

- **Observed project facts:** the current configuration and population contents reported below.
- **External observations still required:** survey or count targets that are not stored in this repository.
- **Modelling decisions:** the proposed calibration scope, parameters and stopping rule.

The objective is a common behavioural baseline. Only after this common calibration may differences between BAU 2040 and Fast Track 2040 be interpreted as scenario results.

## 1. Recommended calibration year

This pre-calibration provenance assessment selected **2019** for the final Round-5 calibration. The road-network artifact has a 2019 modification date and the input is the public `munich-v1.0` model, while project documentation explicitly states that the source year of the public population is unresolved. No complete, independently documented 2023 scenario exists: the folder name `munich_base_2023` is not evidence of a 2023 behavioural base, and its optional PT files represent a 2026 service day. That provenance limitation remains part of the final interpretation; 2023 was not used merely because it appears in the directory name.

The repository now contains the structurally and end-to-end validated synthetic 2019 public-transport reference extracted from the approved combined forecast dataset. This resolves the technical PT-input gap but does not turn that supply into an independently sourced historical snapshot. Definitive population and road-network provenance remains required. If the core inputs cannot be defended as a 2019 behavioural baseline, the calibration year remains unresolved rather than being relabelled.

## 2. Observed targets required

The minimum external target package is an average-working-day 2019 dataset with a documented population, geography and trip definition. It must provide:

- trip-based modal shares for `car`, `pt`, `walk` and `bike` using the same two-endpoints-inside-Munich trip geography as the simulation analysis;
- `ride` share only if survey coding is compatible with the MATSim passenger mode;
- mean trip distance and mean door-to-door travel time by mode;
- sample weights, exact boundary treatment, handling of inbound/outbound trips and treatment of respondents without a complete day;
- car ownership, driving-licence and household vehicle availability distributions, preferably linked to person or household types;
- uncertainty intervals or at least survey sample sizes.

Targets from different years or definitions must not be silently combined. Counts may be used as an additional traffic-volume check, but they are not a substitute for person-trip modal shares.

## 3. Modal-split definition

The calibration metric is the share of **main-mode trips between consecutive main activities** in selected plans. Stage activities such as `pt interaction` are excluded. A routed PT journey with access and egress legs counts once as `pt`; it is not counted as several walk and PT legs. The proposed hierarchy for the rare case of a multi-leg trip is `car`, `ride`, `pt`, `bike`, then `walk`, and the same algorithm must be used for observations and simulation exports.

The approved primary geographic sample is main trips for which **both the origin and destination main activities are inside or exactly on the City of Munich administrative boundary**. Inbound, outbound and wholly external trips are excluded from the primary calibration metric, irrespective of the person's home location. They remain in the simulation and therefore continue to affect traffic and public-transport conditions. Missing-coordinate trips are reported separately and fail closed. This is an origin-and-destination analysis filter, not a resident filter; the complete method and preflight are documented in `munich_spatial_analysis_scope.md`. The five-percent expansion factor does not change shares when every person has the same weight, but weighted survey targets must still be applied correctly.

The current population statistic in this document is a simpler **input-plan leg share**, not a calibrated or observed modal split. The base population has one direct leg per trip and no routed stages, so it is a useful readiness diagnostic but must not be confused with a post-routing output statistic.

## 4. Initial modes

The first calibration should allow `car`, `pt`, `walk` and `bike`. These four modes are canonical and present in the input population. `ride` is currently absent and should remain excluded until a compatible empirical target and a clear passenger-mode interpretation exist. `other` is also absent and should not be offered as a choice alternative.

Car and bike are chain-based in the MATSim 2025.0 default `SubtourModeChoice` configuration. This preserves vehicle continuity over a closed subtour. Bike remains teleported in the current project; its constant can be calibrated, but network-specific cycling measures still cannot be evaluated credibly without a separate calibrated bicycle network and router.

## 5. Minimal technical configuration changes

A new non-production calibration configuration should be created only after the reference-year inputs are confirmed. It should:

1. activate explicit, year-consistent PT and SwissRailRaptor routing;
2. consolidate the two legacy `strategy` modules into one canonical `replanning` module;
3. use `ChangeExpBeta` with weight 0.8, `ReRoute` with weight 0.1 and `SubtourModeChoice` with weight 0.1;
4. explicitly set `SubtourModeChoice` modes to `car,pt,walk,bike`, chain-based modes to `car,bike`, behaviour to `fromSpecifiedModesToSpecifiedModes` and single-trip probability to zero;
5. keep random seed 4711 and the five-percent QSim capacity factors;
6. run enough iterations for mode shares and scores to stabilise; 100 iterations is a reasonable first diagnostic, not a fixed scientific requirement.

The current iteration-zero production configs are technical input checks, not calibration configs. No `SubtourModeChoice` strategy is active today.

## 6. Parameters to calibrate

The first calibration round changes only the alternative-specific mode constants for `car`, `pt`, `walk` and `bike`. One constant must be fixed as the reference (recommended: `car = 0`) so that the remaining constants are identifiable. The `pt`, `walk` and `bike` constants are then adjusted to reduce the difference between simulated and observed shares.

Car availability is a separate decision. The base population has no `carAvail`, licence or vehicle-availability attributes. The preferred solution is to add defensible availability attributes in a separate, documented population-preparation step and then set `considerCarAvailability=true`. A minimal aggregate sensitivity could retain `false`, but it would offer car to persons without an observed availability constraint and must be reported as a strong limitation.

## 7. Parameters initially held fixed

Initially retain the existing scoring and routing values: marginal utility of travel time of -6 utils/hour for each configured mode, zero marginal distance utility, zero monetary distance rate, performing utility 6 utils/hour, PT waiting utility -6 utils/hour and line-switch utility -1. Keep the current teleported speeds and beeline factors (`bike` 4.167 m/s, `walk` 0.833 m/s, factor 1.3), `networkModes=car`, SwissRailRaptor defaults and the existing activity parameters.

Time, distance, money, parking, fares, transfer penalties and value-of-time parameters should change only when an empirical source or a specific behavioural hypothesis justifies them. Otherwise, mode constants would no longer be the only adjustment and parameter compensation would become difficult to interpret.

## 8. Simulation–observation comparison

For each parameter vector, use the same two-endpoints-inside-Munich trip filter for observed and simulated data. Report modal share, mean trip distance and mean door-to-door travel time by mode, plus the number of eligible persons and trips. Use the mean of several stable late iterations rather than a single noisy iteration. Report the unresolved 107,618 open plans separately because they contain no repeated location and may not form a mutable subtour.

The current uncalibrated input-plan leg shares across all 324,043 persons are: car 45.319%, PT 10.828%, walk 29.788% and bike 14.064% (540,468 legs). They describe the synthetic input, not observed Munich behaviour and not a model prediction.

## 9. Iterative calibration procedure

Use a transparent coordinate-descent procedure:

1. run the confirmed baseline with the initial constants;
2. compute share errors in percentage points for the four modes;
3. adjust one non-reference constant at a time in the direction of its error, using small fixed steps (for example 0.25 utils, halved after an overshoot);
4. rerun from the same initial population and random seed;
5. repeat until the stopping rule is met;
6. repeat the final vector with at least two additional random seeds to check robustness.

Every run must record the complete parameter vector, input hashes, seed, iteration window and resulting metrics. A more complex optimiser or an eqasim pipeline is not justified for the first calibration stage.

## 10. Stopping rule

Stop when each main-mode share is within 1–2 percentage points of its observed target, no mode shows a systematic late-iteration trend, and mean distances and travel times are plausible relative to observations. If share agreement requires implausible travel times or very large constants, stop and diagnose input scope, PT year, car availability or routing before adding parameters.

## 11. Transfer to BAU and Fast Track

After validation, freeze exactly the same calibrated scoring parameters, mode-choice settings, eligible modes, random-seed policy and analysis definition in BAU and Fast Track. Do not recalibrate BAU and Fast Track to separate modal-split targets. Scenario-specific supply and demand changes must be allowed to produce their own differences under one common behavioural model.

Future modal-split differences are interpretable as scenario outputs only after this common baseline calibration. Before that point, apparent differences reflect uncalibrated configuration and cannot support causal or forecast claims.

## 12. Methodological limitations and readiness finding

The population is technically clean but only partly ready. All 324,043 persons have exactly one selected plan; all 540,468 leg modes are canonical; activity/leg alternation and coordinates are complete; and no unknown modes or pre-existing routes occur. However, only 216,425 persons (66.789%) have a closed repeated-location plan, while 107,618 (33.211%) have no repeated location. Every selected plan is monomodal, and no availability attributes exist. These facts support a focused `SubtourModeChoice` experiment but require eligibility reporting and an explicit car-availability decision.

The synthetic 2019 PT reference passed the full 324,043-person iteration-zero server validation on 24 August 2026 and supplied the basis for the final Round-5 calibration. The broader baseline-year provenance remains the main substantive uncertainty: a folder labelled 2023 contains an older public road/population model whose provenance is unresolved. The all-zero mode constants describe the pre-calibration starting state, not the retained Round-5 configuration. The final calibration decision retains this provenance limitation and the documented residual target deviation.
