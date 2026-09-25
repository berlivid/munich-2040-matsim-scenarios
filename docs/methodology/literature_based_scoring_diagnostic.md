# Literature-based scoring diagnostic for the synthetic 2019 Munich scenario

## Purpose and status

This configuration is a short technical and behavioural diagnostic, not a
calibrated model. It provides a transparent alternative starting point to the
earlier automatic constant-adjustment experiments. It runs the unchanged
regional five-percent population and validated synthetic 2019 public-transport
supply for iterations 0--10. It contains no empirical modal-share targets and
does not modify an alternative-specific constant automatically.

The diagnostic asks whether a deliberately simple, literature-informed scoring
vector produces technically coherent behaviour before any scenario-specific
constant calibration. Its output cannot by itself demonstrate convergence or
fitness for the thesis scenarios.

## Why the former Munich values are not a validated mode-choice vector

The public Munich scenario repository states that its plans are output from
the MITO travel-demand model. Moeckel et al. describe the associated
application as one in which MATSim performed dynamic traffic assignment:
behavioural adjustments other than route choice were disabled and the demand
was supplied by MITO. The old Munich `configBase.xml` therefore documents a
technically usable assignment configuration, but its common
`marginalUtilityOfTraveling=-6` values and zero monetary distance rates are not
evidence that those values were estimated or validated as a Munich mode-choice
model. They are not adopted as such here.

Sources: the [original Munich scenario](https://github.com/matsim-scenarios/matsim-munich),
its [base configuration](https://github.com/matsim-scenarios/matsim-munich/blob/master/scenarios/tumTbBase/configBase.xml),
and [Moeckel et al. (2020)](https://doi.org/10.1155/2020/1902162).

## Scoring logic

The MATSim scoring function evaluates both activity performance and travel.
Setting the direct mode-specific
`marginalUtilityOfTraveling_util_hr` to zero therefore does not make travel
time free. Travel still displaces activity time and consequently carries the
opportunity cost associated with the positive utility of performing
activities. The [MATSim User Guide](https://www.matsim.org/files/book/partOne-latest.pdf)
explains this resource-value mechanism. The official configuration source also
describes mode-specific travel utility as additional to the opportunity cost
of time and records zero as the VSP reference value.

PT waiting receives an additional `-6 utils/hour` because waiting for a
vehicle is a distinct burden on top of the time removed from activities. A
line switch receives `-1 util`. These values are transparent starting
assumptions, not estimates from Munich observations.

Walk is the permanent reference alternative and its ASC is exactly zero. The
[official MATSim consistency checker](https://matsim.org/doxygen/_vsp_config_consistency_checker_impl_8java_source.html)
warns that a non-zero walk constant is also applied to PT access and egress
walk legs. Fixing walk at zero avoids that internal inconsistency and provides
an identifiable reference for later calibration. The next calibration stage,
if authorised after reviewing this diagnostic, may adjust only car, PT and
bike ASCs.

All four initial ASCs are reset to zero so that this run diagnoses the stated
structural and transferred parameters without carrying forward constants from
the earlier calibration paths.

## Parameter vector and provenance

| Parameter | Value | Status and rationale |
|---|---:|---|
| Activity performing utility | `6.0 utils/hour` | MATSim structural starting value retained from the technical 2019 basis; it creates the opportunity cost of travel time. |
| Marginal utility of money | `1.0 utils/money unit` | Inherited technical normalization; one model money unit is interpreted as one euro for the transferred car-cost assumption. |
| Direct travel-time utility, all four choice modes | `0.0 utils/hour` | Transferred modelling assumption consistent with isolating the opportunity-cost mechanism; requires scenario-specific validation. |
| Direct distance utility, all four choice modes | `0.0 utils/metre` | Transferred simplifying assumption; monetary car cost remains separate. |
| PT waiting utility | `-6.0 utils/hour` | Transferred modelling assumption, additional to displaced activity time. |
| Line-switch utility | `-1.0 util` | Inherited technical setting, not empirically re-estimated here. |
| Car monetary distance rate | `-0.00020 money units/metre` | Transferred assumption equivalent to EUR 0.20/km. The value matches the versioned Bavaria-eqasim default car cost, but is not claimed as a Munich estimate. |
| PT, walk and bike monetary distance rates | `0.0` | Simplifying assumption for this diagnostic; fares and ownership costs are not modelled. |
| Car, PT, walk and bike ASCs | `0.0` | Neutral diagnostic start. Walk remains fixed; the other three still require Munich-specific calibration. |
| Walk routing speed | `1.333333333 m/s` (`4.8 km/h`) | Munich empirical observation transferred from Rayaprolu's reported weighted average. |
| Bike routing speed | `3.805555556 m/s` (`13.7 km/h`) | Munich empirical observation transferred from Rayaprolu's reported weighted average. |
| Beeline-distance factor | `1.3` | Inherited routing assumption, not an observed Munich coefficient. |

Rayaprolu reports weighted average observed speeds of 4.8 km/h for walk and
13.7 km/h for bicycle in the Munich mode-choice dataset; these are used only
as teleported routing speeds. See [Rayaprolu (2017)](https://www.mos.ed.tum.de/fileadmin/w00ccp/tb/theses/Rayaprolu_2017.pdf).

The car-cost value is transferred from the versioned
[Bavaria-eqasim cost parameters](https://github.com/TUM-VT/eqasim-java-bavaria/blob/da91c58746f15540572cd585f6cb98da662f4c03/bavaria/src/main/java/org/eqasim/bavaria/mode_choice/parameters/BavariaCostParameters.java).
It is a transparent plausibility assumption, not empirical evidence from the
synthetic population.

## Technical settings inherited without behavioural interpretation

The original population, network, transit schedule, transit vehicles,
`EPSG:31468`, five-percent flow and storage capacity factors, seed 4711,
activity types and typical durations, early-departure and late-arrival
settings, transit simulation and SwissRailRaptor routing remain unchanged. The
48-hour QSim horizon is retained from the later technical cutoff diagnosis so
that the known 43-hour end-of-simulation artefact is not reintroduced. It is a
technical boundary, not a calibrated behavioural parameter.

Replanning uses `ChangeExpBeta=0.8`, `ReRoute=0.1` and
`SubtourModeChoice=0.1`, with `BrainExpBeta=1.0`. The only choice alternatives
are car, PT, walk and bike; car and bike remain chain based.

`considerCarAvailability=false` is a modelling limitation. The source
population has no defensible licence, household vehicle-ownership or
person-specific availability attributes. Inventing them would create false
precision. The diagnostic consequently permits car as an alternative for all
agents and must be interpreted accordingly.

## Parameters deliberately not transferred

The versioned
[Bavaria-eqasim mode parameters](https://github.com/TUM-VT/eqasim-java-bavaria/blob/da91c58746f15540572cd585f6cb98da662f4c03/bavaria/src/main/java/org/eqasim/bavaria/mode_choice/parameters/BavariaModeParameters.java)
are retained only as comparison benchmarks. Their discrete-choice time and
alternative coefficients are not copied into classic MATSim scoring.
[Hörl (2021)](https://doi.org/10.1016/j.procs.2021.03.088) shows that directly
using discrete-choice parameters in MATSim is not technically sound without a
consistent integration of the choice-error structure.

The [Vienna MATSim study](https://www.mdpi.com/2071-1050/14/1/428) is a
methodological comparator showing that modal constants and other validation
indicators can be handled explicitly. Its numerical parameters are not
transferred to Munich.

## Interpretation and next decision

Physical modal shares, passenger-kilometres and mean trip distances are
outputs for validation. ASCs change relative plan utilities; they cannot
directly force route distances or absolute Pkm totals. Review of the short run
must therefore separate technical execution, modal response, distance
plausibility and convergence.

If the diagnostic is technically sound, only car, PT and bike constants may be
calibrated against the approved Munich-resident trip-share evidence, while the
walk constant remains zero. Any scoring specification ultimately accepted for
the thesis must then be frozen and used identically in BAU and Fast Track.
Otherwise scenario differences would combine infrastructure effects with a
change in behavioural assumptions.

## Reproduction

1. Run `05 Validate Literature-Based 2019 Scoring Diagnostic` and require the
   explicit validation PASS.
2. Confirm that
   `scenarios/munich_calibration_2019/output/literature-based-scoring-diagnostic`
   does not exist.
3. Run `06 Run Literature-Based 2019 Scoring Diagnostic` once on the university
   server.
4. After normal completion, run
   `07 Analyze Literature-Based 2019 Scoring Diagnostic` on the server. This
   read-only process validates the output config and protected inputs, streams
   the final standard trip and event files, and publishes only the small
   generated `analysis` folder.
5. Copy that `analysis` folder to the local project and review it before
   preparing any ASC-calibration round. The large plans and events remain
   ignored and server-local.
6. Run `08 Audit Literature-Based Scoring Trip Distances` on the server before
   deciding whether ASC calibration is appropriate. Run 08 reads the unchanged
   input and iteration-10 selected plans and publishes only
   `analysis/distance-audit`; it does not run MATSim.

## Read-only result analysis

The primary final-result scope is the established territorial indicator
`BOTH_INSIDE`: both trip endpoints are inside or on the Munich municipal
boundary. The complete regional population remains simulated, while
`ORIGIN_ONLY`, `DESTINATION_ONLY`, `BOTH_OUTSIDE` and invalid-coordinate trips
are reported separately. This diagnostic deliberately predates the later
resident-cohort architecture and must not be described as a residence-based
calibration.

The analyzer uses the complete final selected plans as the authoritative source
for persons, stage-aware main trips, spatial categories and the trip-based
modal split. MATSim's standard `output_trips.csv.gz` is streamed separately as
the measurement source for travelled distance, passenger-kilometres and travel
time. The completed diagnostic contains all 324,043 persons and 540,468 plan
trips, while the standard writer contains 540,211 records. The 257-record gap
(0.048%) is reported as measurement coverage and is not treated as population
or selected-plan loss. Missing distance and time values are not imputed.

For each of car, PT, bike and walk, the analyzer reports final trip shares,
sample passenger-kilometres, Pkm shares, factor-20 daily sample expansion, mean
distance and mean travel time. No annualization is applied. Car
passenger-kilometres must not be interpreted as vehicle-kilometres; reliable
car Fkm require a separate event-based vehicle analysis. Standard MATSim
iteration mode shares, when available, cover the whole simulated population
and are labelled accordingly. They are not substituted for unavailable
iteration-specific Munich `BOTH_INSIDE` shares.

PersonStuckEvents are streamed and summarized by mode, hour and exact time,
including observations at the 48-hour boundary. The analyzer reports affected
persons with at least one `BOTH_INSIDE` trip but does not infer causes. All
validation and large-file reads must succeed before the five reports are
published atomically. Existing analysis is never overwritten.

Local preparation compiles and validates the configuration only. It does not
start Controller or QSim.

## Read-only trip-distance audit

Run 08 addresses one narrow diagnostic question: were long final walk and bike
trips inherited from the input population, or did they enter those modes during
iterations 0--10? The scope remains the territorial `BOTH_INSIDE` indicator.
MATSim `TripStructureUtils` removes stage activities, and the standard MATSim
analysis main mode is used. Input and final trips are matched by person ID,
main-trip index, main-activity types and exact EPSG:31468 endpoint coordinates.
Missing, duplicate or structurally different trips cause a fail-closed result.

Euclidean origin-destination distance is the primary comparison because the
same matched trip retains its endpoints when its mode changes. Travelled route
distance is secondary and mode dependent. It is summed from selected-plan leg
routes only when every leg provides a finite distance; missing values are not
imputed. The optional standard output-trips file is counted as a secondary
coverage cross-check, not used to redefine the authoritative selected-plan
denominator. OD distance, travelled distance and passenger-kilometres therefore
remain explicitly distinct.

The walk thresholds of 3, 5 and 10 km and bike thresholds of 5, 10 and 20 km
are transparent diagnostic cut-offs, not observed behavioural limits. The
audit evaluates full distributions, bins and input-to-final mode transitions,
not just means. Its report treats a distribution shift as material only for a
descriptive audit flag: a mean or p90 change of at least 10% together with a
change of at least one percentage point at the first mode-specific threshold.
This is not a behavioural parameter or an empirical threshold.

The generated directory contains distributions, fixed distance bins, threshold
comparisons, mode transitions, the input modes of long final active trips and
an English interpretation report. ASC calibration may proceed only after the
report is reviewed. Predominantly inherited long trips require input-plan
investigation; predominantly generated cases require review of mode-specific
time/distance scoring and the unrestricted choice set. A maximum-distance rule
would add a behavioural assumption and is not justified by the audit thresholds
alone.

## Literature-based calibration Round 1

The completed diagnostic contains 160,603 `BOTH_INSIDE` main trips. Its final
physical shares are car 30.435919628%, PT 16.714507201%, bike 21.801585275% and
walk 31.047987896%, compared with targets of 34%, 24%, 18% and 24%. Round 1 is
the first actual calibration step. It changes only three alternative-specific
constants, the run identity, protected output directory and last iteration;
the complete structural scoring specification remains frozen.

Walk remains the zero reference. For each other mode (i), the update is:

`ASC_i = ln[(target_i / diagnostic_i) / (target_walk / diagnostic_walk)]`

Using the versioned diagnostic shares gives car `0.368217221`, PT
`0.619256967`, bike `0.065869246` and walk exactly `0.000000000`. These are
transparent calibration adjustments, not empirically estimated behavioural
coefficients. Bike receives a small positive constant despite exceeding its
absolute target because constants are relative to walk, which is even more
overrepresented against its target.

Round 1 retains the original population and all technical inputs, seed 4711,
48-hour QSim horizon, 5% capacity factors, SwissRailRaptor, mode speeds, car
operating cost, activity scoring, choice set, chain-based modes and strategy
weights. The missing car-availability and ownership attributes remain a
limitation; `considerCarAvailability=false` is unchanged. Neither maximum
active-mode distances nor mode-specific distance penalties are introduced.
The distance audit showed that most long final walk and bike trips were
inherited, while ten iterations were insufficient to establish whether the
tails persist after adequate mode-choice exposure.

Iterations 31--40 form the late assessment window. A result is acceptable only
when every late mean is within +/-2 percentage points of its target, every
absolute trend is below 0.10 percentage points per iteration, every late range
is no greater than 2 percentage points, the project-specific stuck incidence
remains no greater than 0.10%, no unexpected mode appears and the active-mode
distance tails do not materially worsen under the existing audit rule. These
are thesis-specific review criteria rather than universal MATSim standards.
A stable miss leads to `ONE_MORE_ASC_ROUND_REQUIRED`; instability or structural
warning evidence leads to `STRUCTURAL_REVIEW_REQUIRED`.

Run 10 records exact selected-plan `BOTH_INSIDE` shares and StuckEvents for all
iterations 0--40, then automatically publishes the final analysis. Run 10B is
recovery-only: it summarizes an already completed output and never starts QSim.
Its Round-2 constants are a non-executing recommendation using a damping factor
of 0.5. Pkm, travelled distances and distance distributions remain validation
outcomes and are not direct ASC targets.

## Literature-based calibration Round 2

Round 1 did not satisfy the modal-share targets. Over iterations 31--40 its
late means were car 32.082277417%, PT 18.475869068%, bike 31.253899367% and
walk 18.187954148%. Round 2 uses these late means, rather than the possibly
transient final iteration, in the documented damped update:

`new_ASC_i = current_ASC_i + 0.5 * ln[(target_i / lateMean_i) / (target_walk / lateMean_walk)]`

The resulting fixed ASCs are car `0.258598439`, PT `0.611403971`, bike
`-0.348664107` and walk `0.000000000`. Bike is reduced strongly because its
late share substantially exceeded its target. Car falls slightly relative to
walk, while PT changes very little because the PT-to-walk imbalance was already
close to the corresponding target ratio. The factor 0.5 is a conservative,
study-specific damping choice. The constants are calibration adjustments, not
empirically estimated behavioural coefficients.

Round 2 starts afresh from the unchanged original population; it is not warm
started from Round-1 plans. Apart from its run identity, protected output,
last iteration and ASCs, it is identical to Round 1. It covers iterations
0--60, retains `fractionOfIterationsToDisableInnovation=0.8` (innovation is
disabled after iteration 48), and evaluates iterations 51--60. This makes the
rounds directly comparable while allowing a longer post-innovation assessment.

StuckEvents are reported cumulatively and per iteration, but calibration
decisions use only late-window and final-iteration incidence. Early temporary
events therefore remain visible without automatically causing structural
failure. The project-specific review threshold is at most 0.10% of the 160,603
`BOTH_INSIDE` denominator per late or final iteration. Acceptance additionally
requires every late mean within +/-2 percentage points, absolute trends below
0.10 percentage points per iteration, ranges no greater than 2 percentage
points, a constant denominator, no unexpected modes and no material worsening
of active-mode distance tails. A stable target miss produces
`ONE_FINAL_ASC_UPDATE_REQUIRED`; technical or late-run instability produces
`STRUCTURAL_REVIEW_REQUIRED`. No distance limit, distance penalty, new
car-availability assumption, Pkm target or warm start is introduced.

## Literature-based calibration Round 3

Round 2 was technically stable but remained outside the trip-share targets.
Its iteration-51--60 means were car 36.717620468%, PT 20.864927803%, bike
28.515656619% and walk 13.901795110%. The final round applies the same damped,
walk-referenced formula to those late means:

`new_ASC_i = current_ASC_i + 0.5 * ln[(target_i / lateMean_i) / (target_walk / lateMean_walk)]`

The resulting ASCs are car `-0.052867606`, PT `0.408378132`, bike
`-0.851722801` and walk `0.000000000`. All three non-reference constants fall
because walk was more underrepresented relative to its target than each other
mode. Bike receives the largest reduction because it was the most strongly
overrepresented mode in the Round-2 late window. These remain transparent ASC
adjustments rather than empirically estimated behavioural coefficients.

Round 3 starts again from the unchanged original population and retains the
complete structural scoring, routing, capacity, strategy and 48-hour technical
configuration. It runs iterations 0--60, disables innovation after iteration
48 and uses iterations 51--60 for the final assessment. This fresh-start design
keeps all rounds comparable and excludes warm-start effects.

Round 3 was initially designated as the final planned calibration round.
`ACCEPT_CALIBRATION` requires all
late means within +/-2 percentage points, trends below 0.10 percentage points
per iteration, ranges no greater than 2 percentage points, no denominator or
mode inconsistency, acceptable late/final StuckEvent incidence and no material
active-mode distance deterioration. A technically valid and stable result with
all residuals no greater than 4 percentage points is reported as
`ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION`. A larger residual, instability or
technical inconsistency is reported as `CALIBRATION_TARGET_NOT_REACHED`.
Round 3 created no automatic Round-4 recommendation. Its preserved analysis
subsequently showed late means of car 36.726399880%, PT 23.538974988%, bike
23.268058505% and walk 16.466566627%. It therefore did not reach the target,
and its active-mode distance assessment also required review. This evidence
motivated one manually authorized and strictly bounded final correction.

## Literature-based calibration Round 4 and the post-hoc stop-rule exception

Round 4 changes only the alternative-specific constants. It applies the same
damping factor of 0.5 in two explicit steps:

`temporary_ASC_m = old_ASC_m + 0.5 * ln(target_share_m / observed_share_m)`

`new_ASC_m = temporary_ASC_m - temporary_ASC_walk`, with `new_ASC_walk = 0`

The full-precision derivation is versioned in
`calibration_specifications/round_4_constant_derivation.csv`. It produces car
`-0.27979614837234024`, PT `0.22971538337764302`, bike
`-1.1684385773353396` and walk exactly `0.0`. Walk remains the permanent
reference. No travel-time, cost, speed, distance, activity, routing, capacity,
strategy or availability parameter changes. The run starts from the unchanged
original 2019 population, runs iterations 0--60 with the 48-hour horizon and
uses iterations 51--60 for assessment.

The predefined Round-3 decision rules remain binding. Round 4 reports
`ACCEPT`, `ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION` or
`CALIBRATION_TARGET_NOT_REACHED`. Round 4 was originally specified as the
binding endpoint and its implementation did not calculate a subsequent vector.
Its stable late means were car 35.710291838%, PT 24.795800826%, bike
20.591022584% and walk 18.902884753%. After reviewing this evidence, one
additional conservative round was authorized post hoc. This exception is
reported explicitly and does not alter any acceptance criterion.

## Binding final literature-based calibration Round 5

Round 5 applies the same walk-normalized logarithmic method with the reduced
damping factor 0.25:

`temporary_ASC_m = old_ASC_m + 0.25 * ln(target_share_m / observed_share_m)`

`new_ASC_m = temporary_ASC_m - temporary_ASC_walk`, with `new_ASC_walk = 0`

The full-precision result is car `-0.35175057259662179`, PT
`0.16187543976517921`, bike `-1.2617442557140233` and walk exactly `0.0`.
Only these ASCs and the run identity change. The original population, frozen
structural scoring, routing, strategies, technical settings, 48-hour horizon
and iterations 0--60 remain identical to Round 4.

Round 5 is not automatically preferred because it is newer. It is selected
only if technically stable and either in a stronger pre-defined acceptance
class, or in the same class with a lower summed absolute trip-share deviation
and no relevant additional active-distance deterioration. Otherwise Round 4
remains the final parameter set. A walk residual just below five percentage
points does not change the fixed four-percentage-point class boundary. No Round
6 or further constants are produced. Residual deviations remain model
limitations. Pkm, distance and travel-time indicators remain validation and
result measures rather than direct ASC targets. The selected scoring
specification must subsequently be frozen and transferred unchanged to BAU and
Fast Track.
