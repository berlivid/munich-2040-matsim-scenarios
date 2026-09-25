# Munich synthetic-2019 calibration input

## Analytical role and current status

This isolated scenario supplies the public-transport input for a later common
mode-choice calibration. It combines the original 5-% population and public
road network with a **synthetic 2019 reference supply extracted from the
combined forecast dataset**. It is not a historical MVV GTFS snapshot. The
service date 13 February 2026 is only a technical activation date and must not
be interpreted as the historical reference year.

The GTFS subset, MATSim transit inputs, reference checks, temporal checks and
representative SwissRailRaptor connections are structurally validated. The
server iteration-zero validation and the subsequent final Round-5 calibration
were completed on the university server. No mode-choice strategy is active in
configuration.

## Required server input

The unchanged source archive must be copied manually to:

`original-input-data/mvv_gtfs_2019/gtfs_2019.zip`

Its expected SHA-256 is
`92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4`.
The source ZIP is intentionally ignored by Git and is not transferred through
the repository. The derived GTFS ZIP and all three MATSim transit files are
also ignored; they must be rebuilt on the server from the versioned Java code
and specification.

## Point-and-click sequence on the Uni server

Open the project as a Maven project in IntelliJ and run these shared
configurations in order from the project root:

1. **01 Build Synthetic GTFS 2019** runs
   `org.matsim.project.prepare.BuildSyntheticGtfs2019Reference` with its
   implemented `build` argument. It validates the raw archive, applies the
   approved `Analyse_2019=1` and model-space rules, preserves complete selected
   trips, corrects route types and writes
   `original-input-data/mvv_gtfs_2019/synthetic_2019_reference.zip`.
2. **02 Create GTFS 2019 Calibration Transit** runs
   `org.matsim.project.prepare.CreateGtfs2019CalibrationTransit` with an 8 GB
   maximum heap. It creates and rereads `network-with-pt.xml.gz`,
   `transitSchedule.xml.gz` and `transitVehicles.xml.gz` under
   `scenarios/munich_calibration_2019/input_transit/`. It also audits all
   schedule times and writes the finite, schedule-derived QSim end time to the
   validation config.
3. **03 Validate GTFS 2019 Calibration Input** runs
   `org.matsim.project.prepare.ValidateGtfs2019CalibrationInput` with a 12 GB
   maximum heap. It first repeats structural and representative PT-routing
   checks, then loads the original 324,043-person 5-% population and executes
   only iteration 0 from `config_input_validation.xml`.

The final validation requires at least 8 GB Java heap; 12 GB is recommended
and is configured for step 3. Its output directory must not already exist
because the configuration deliberately uses `failIfDirectoryExists`.

## Historical recovery record (superseded)

An earlier server attempt did not terminate because `qsim.endTime=undefined`;
the similarly named `hermes.endTime=30:00:00` did not control QSim. The
corrected generator derives a finite end time from the accepted schedule, and
the validator fails before QSim if that end time is missing, non-finite,
inconsistent with the schedule, or reached by an accepted vehicle. Services
reaching the following service day remain subject to the fail-closed 48-hour
maximum.

The former deletion-and-rerun procedure applied only to that stopped attempt.
It is retained as historical context, not as a release instruction. Do not
delete or rerun a completed evidence directory as part of a release operation;
the final iteration-zero validation subsequently completed on the university
server.

## Local validation evidence and limitation

The local build, referential checks and representative bus, tram, subway and
rail routes passed. Focused tests also cover the explicit finite end time,
excessive-duration rejection and valid post-midnight service. No full local
QSim was started for this correction. The earlier local full-population attempt
was limited by a 3,936 MB Maven heap, while the later server attempt exposed
the independent undefined-end-time defect. Neither incomplete output may be
used for analysis.

The completed server validation established this input as the basis for the
final Round-5 calibration. The historical incomplete outputs remain invalid
for analysis. BAU 2040, Fast Track 2040 and GTFS 2037 are independent of this
workflow and must not be rebuilt by these configurations.

For provenance, selection counts, conversion assumptions and methodological
limitations, see
[`docs/methodology/gtfs_2019_calibration_input.md`](../../docs/methodology/gtfs_2019_calibration_input.md).

## Literature-based scoring diagnostic

`config_literature_based_scoring_diagnostic.xml` is a separate iterations-0--10
diagnostic. It starts from the unchanged validated 2019 population, network,
schedule and vehicles, retains SwissRailRaptor and the five-percent capacity
factors, and uses the technically established 48-hour horizon. It does not
contain modal-share targets or an automatic ASC update.

The choice set is exactly car, PT, walk and bike; car and bike remain chain
based. All four ASCs start at zero and walk is permanently fixed as the
reference. Direct mode-specific travel-time utilities are zero, while the
positive activity-performing utility preserves the opportunity cost of time.
Car has a transferred EUR 0.20/km operating-cost assumption. Walk and bike use
the Munich empirical speeds 4.8 and 13.7 km/h. Car availability is not checked
because the population contains no defensible licence, ownership or
availability attributes; this is a documented limitation.

Run `05 Validate Literature-Based 2019 Scoring Diagnostic` first. Only after
PASS, and only on the university server, run
`06 Run Literature-Based 2019 Scoring Diagnostic`. The protected output is
`output/literature-based-scoring-diagnostic` and must not already exist. See
[`literature_based_scoring_diagnostic.md`](../../docs/methodology/literature_based_scoring_diagnostic.md)
for parameter provenance and interpretation.

After the server run has shut down normally, execute
`07 Analyze Literature-Based 2019 Scoring Diagnostic` on the server. Run 07 is
strictly read-only: it checks the semantic output configuration, protected
inputs and complete iterations 0--10, then streams the final trips and events.
It writes five small reports below
`output/literature-based-scoring-diagnostic/analysis`. Copy only this generated
analysis folder back to the local project. The large plans and events remain
ignored and server-local. Review the diagnostic before preparing the first ASC
calibration round.

Before any ASC round, run `08 Audit Literature-Based Scoring Trip Distances` on
the server. This read-only audit matches the unchanged input selected-plan
trips to the iteration-10 selected-plan trips and compares `BOTH_INSIDE` walk
and bike distance distributions. Euclidean OD distance is the invariant primary
comparison; route distance is a secondary, coverage-reported measure. The
3/5/10 km walk and 5/10/20 km bike cut-offs are diagnostic thresholds, not
behavioural limits. Run 08 fails on an existing
`output/literature-based-scoring-diagnostic/analysis/distance-audit` directory
and never overwrites the simulation output.

## Literature-based scoring calibration Round 1

`config_literature_based_scoring_calibration_round_1.xml` is a strict
derivation of the diagnostic configuration. It uses the full walk-referenced
log-ratio update and ASCs car `0.368217221`, PT `0.619256967`, bike
`0.065869246`, walk `0.000000000`. All structural scoring and protected inputs
remain unchanged. The output is protected at
`output/literature-based-scoring-calibration-round-1`.

Run `09 Validate Literature-Based Scoring Calibration Round 1` locally and on
the server first. Then execute `10 Run Literature-Based Scoring Calibration
Round 1` once on the server. Run 10 covers iterations 0--40, records exact
`BOTH_INSIDE` selected-plan mode shares and StuckEvents for every iteration,
and automatically creates the analysis after normal shutdown. If only the
automatic postprocessing fails after a complete simulation, use recovery-only
`10B Validate and Summarize Existing Literature-Based Scoring Round 1`; it does
not start Controller or QSim.

## Literature-based scoring calibration Round 2

`config_literature_based_scoring_calibration_round_2.xml` is a strict fresh-
start derivation of Round 1. It uses the original population, iterations 0--60
and ASCs car `0.258598439`, PT `0.611403971`, bike `-0.348664107`, walk
`0.000000000`. Innovation is disabled after iteration 48 and iterations 51--60
form the late assessment window. All protected inputs and structural scoring
remain unchanged. The protected output is
`output/literature-based-scoring-calibration-round-2`.

Run `11 Validate Literature-Based Scoring Calibration Round 2` first. On the
server, execute `12 Run Literature-Based Scoring Calibration Round 2`; it runs
the simulation once and automatically validates and summarizes the result.
Use `12B Validate and Summarize Existing Literature-Based Scoring Round 2`
only to recover analysis from a normally completed existing output. Run 12B is
read-only with respect to MATSim execution and never starts Controller or QSim.
Round-2 acceptance uses late/final per-iteration StuckEvent incidence, while
cumulative early events remain reported as technical evidence.

## Literature-based scoring calibration Round 3

`config_literature_based_scoring_calibration_round_3.xml` is a strict
fresh-start derivation of Round 2. It retains iterations 0--60 and the original
population, and changes only the run identity, protected output and ASCs: car
`-0.052867606`, PT `0.408378132`, bike `-0.851722801`, walk `0.000000000`.
All structural scoring and protected inputs remain unchanged. The protected
output is `output/literature-based-scoring-calibration-round-3`.

Run `13 Validate Literature-Based Scoring Calibration Round 3` first. On the
server, execute `14 Run Literature-Based Scoring Calibration Round 3` once;
normal shutdown is followed automatically by final validation and analysis.
Use recovery-only `14B Validate and Summarize Existing Literature-Based Scoring
Round 3` only if postprocessing fails after a normally completed simulation.
Run 14B never starts Controller or QSim. Round 3 uses iterations 51--60 for its
three-way calibration decision and deliberately produces no automatic
next-round ASC recommendation. The preserved result did not reach the targets,
so one manually authorized final Round 4 follows it without changing the
structural scoring design.

## Literature-based scoring calibration Round 4

`config_literature_based_scoring_calibration_round_4.xml` is a strict
fresh-start derivation of Round 3. Only the run ID, protected output directory
and ASCs differ. The full-precision ASCs are car
`-0.27979614837234024`, PT `0.22971538337764302`, bike
`-1.1684385773353396` and walk `0.0`. Their reproducible derivation is stored in
`calibration_specifications/round_4_constant_derivation.csv`. The protected
output is `output/literature-based-scoring-calibration-round-4`.

Run `15 Validate Literature-Based Scoring Calibration Round 4` first. On the
server, run `16 Run Literature-Based Scoring Calibration Round 4` exactly once;
after normal shutdown it performs the established analysis automatically. Use
recovery-only `16B Analyze Existing Literature-Based Scoring Calibration Round
4` only for a complete existing output when postprocessing must be repeated.
Run 16 now includes `-Djava.awt.headless=true`; this only permits server-side
chart generation without a display and changes no model parameter. Run 16B
never starts Controller or QSim. Round 4 uses iterations 51--60 and applies the
unchanged decision rules. Although it was originally the binding endpoint, its
stable result was reviewed and one post-hoc conservative Round 5 was explicitly
authorized.

## Binding final literature-based scoring calibration Round 5

`config_literature_based_scoring_calibration_round_5.xml` differs from Round 4
only in run ID, protected output and ASCs: car
`-0.35175057259662179`, PT `0.16187543976517921`, bike
`-1.2617442557140233` and walk `0.0`. The damping factor is 0.25 and the
versioned derivation is
`calibration_specifications/round_5_constant_derivation.csv`. The protected
output is `output/literature-based-scoring-calibration-round-5`.

The completed final execution used `17 Validate Literature-Based Scoring
Calibration Round 5`, followed by `18 Run Literature-Based Scoring Calibration
Round 5` on the server. Its headless VM option enables chart creation without
a display, and normal shutdown is followed by analysis. `18B Analyze Existing
Literature-Based Scoring Calibration Round 5` is recovery-only for an already
complete output. For an authorised clean-server reproduction, use the same
order without overwriting preserved evidence. Round 5 is compared with Round 4
under the unchanged acceptance rules and is not automatically preferred. No
Round 6 is permitted. The selected specification is transferred unchanged to
BAU and Fast Track.
