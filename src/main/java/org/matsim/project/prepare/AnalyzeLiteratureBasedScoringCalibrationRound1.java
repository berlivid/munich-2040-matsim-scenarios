package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/**
 * Shared exact BOTH_INSIDE iteration observer and read-only calibration
 * postprocessor. Runtime CSVs make recovery possible without rerunning QSim.
 */
public final class AnalyzeLiteratureBasedScoringCalibrationRound1
        implements IterationEndsListener, PersonStuckEventHandler {
    static final Path OUTPUT = ValidateLiteratureBasedScoringCalibrationRound1Config.OUTPUT;
    static final Path ANALYSIS = OUTPUT.resolve("analysis");
    static final Path ITERATIONS = ANALYSIS.resolve("round_1_iteration_mode_shares.csv");
    static final Path STUCK = ANALYSIS.resolve("round_1_stuck_events.csv");
    static final String RUN_ID = ValidateLiteratureBasedScoringCalibrationRound1Config.RUN_ID;
    static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    static final int LATE_FIRST = 31;
    static final int LATE_LAST = 40;
    static final double TARGET_TOLERANCE_PP = 2.0;
    static final double TREND_LIMIT_PP = 0.10;
    static final double LATE_RANGE_LIMIT_PP = 2.0;
    static final double STUCK_INCIDENCE_LIMIT_PERCENT = 0.10;

    private final Scenario scenario;
    private final Map<Id<Person>, ScopeSelection> scope;
    private final RoundDefinition definition;
    private final Path analysis;
    private final Path iterationsFile;
    private final Path stuckFile;
    private final List<IterationSnapshot> snapshots = new ArrayList<>();
    private final List<StuckIteration> stuckIterations = new ArrayList<>();
    private int currentIteration = -1;
    private MutableStuck currentStuck = new MutableStuck();

    AnalyzeLiteratureBasedScoringCalibrationRound1(Scenario scenario,
            MunichTripBoundaryFilter filter) {
        this(scenario, filter, round1Definition());
    }

    AnalyzeLiteratureBasedScoringCalibrationRound1(Scenario scenario,
            MunichTripBoundaryFilter filter, RoundDefinition definition) {
        this.scenario = scenario;
        this.definition = definition;
        this.analysis = definition.output().resolve("analysis");
        this.iterationsFile = analysis.resolve(definition.prefix()
                + "_iteration_mode_shares.csv");
        this.stuckFile = analysis.resolve(definition.prefix() + "_stuck_events.csv");
        this.scope = buildScope(scenario, filter);
        long total = scope.values().stream().mapToLong(value -> value.bothInside().cardinality()).sum();
        require(total == ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE,
                "Runtime BOTH_INSIDE scope changed before iteration 0: " + total);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The recovery analyzer accepts no arguments");
        summarizeExistingOutput();
    }

    @Override
    public void reset(int iteration) {
        currentIteration = iteration;
        currentStuck = new MutableStuck();
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        require(currentIteration >= 0, "PersonStuckEvent received outside an iteration");
        currentStuck.add(event, scope.containsKey(event.getPersonId()));
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        require(event.getIteration() == currentIteration,
                "Stuck-event and iteration observers are out of sync");
        IterationSnapshot snapshot = snapshot(event.getIteration(), scenario, scope);
        snapshots.add(snapshot);
        stuckIterations.add(currentStuck.freeze(event.getIteration()));
        try {
            Files.createDirectories(analysis);
            writeAtomically(iterationsFile, iterationCsv(snapshots));
            writeAtomically(stuckFile, stuckCsv(stuckIterations));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not preserve Round-"
                    + definition.roundNumber() + " runtime analysis", exception);
        }
    }

    static Map<Id<Person>, ScopeSelection> buildScope(Scenario scenario,
            MunichTripBoundaryFilter filter) {
        Map<Id<Person>, ScopeSelection> result = new HashMap<>();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            var classified = filter.classify(person.getSelectedPlan());
            BitSet selected = new BitSet(classified.size());
            for (int index = 0; index < classified.size(); index++) {
                if (classified.get(index).category()
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) {
                    selected.set(index);
                }
            }
            if (!selected.isEmpty()) {
                result.put(person.getId(), new ScopeSelection(selected, classified.size()));
            }
        }
        return Map.copyOf(result);
    }

    static IterationSnapshot snapshot(int iteration, Scenario scenario,
            Map<Id<Person>, ScopeSelection> scope) {
        Map<String, Long> modes = new TreeMap<>();
        long denominator = 0;
        for (var scoped : scope.entrySet()) {
            Person person = scenario.getPopulation().getPersons().get(scoped.getKey());
            require(person != null && person.getSelectedPlan() != null,
                    "Scoped person or selected plan disappeared: " + scoped.getKey());
            var trips = TripStructureUtils.getTrips(person.getSelectedPlan(),
                    StageActivityTypeIdentifier::isStageActivity);
            require(trips.size() == scoped.getValue().totalMainTrips(),
                    "Main-trip structure changed for person " + scoped.getKey());
            for (int index = scoped.getValue().bothInside().nextSetBit(0);
                    index >= 0; index = scoped.getValue().bothInside().nextSetBit(index + 1)) {
                String mode = MunichTripBoundaryFilter.identifyInputMainMode(trips.get(index));
                modes.merge(mode, 1L, Long::sum);
                denominator++;
            }
        }
        require(denominator == ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE,
                "Iteration " + iteration + " BOTH_INSIDE denominator changed: " + denominator);
        long expectedModes = MODES.stream().mapToLong(mode -> modes.getOrDefault(mode, 0L)).sum();
        return new IterationSnapshot(iteration, denominator, Map.copyOf(modes),
                denominator - expectedModes);
    }

    static void summarizeExistingOutput() throws Exception {
        Config expected = ValidateLiteratureBasedScoringCalibrationRound1Config
                .loadAndValidate(false);
        summarizeExistingOutput(round1Definition(), expected);
    }

    static void summarizeExistingOutput(RoundDefinition definition, Config expected)
            throws Exception {
        Path output = definition.output();
        Path analysis = output.resolve("analysis");
        Path iterationsFile = analysis.resolve(definition.prefix()
                + "_iteration_mode_shares.csv");
        Path stuckFile = analysis.resolve(definition.prefix() + "_stuck_events.csv");
        validateCompletedOutput(expected, definition, iterationsFile, stuckFile);
        List<IterationSnapshot> iterations = readIterations(iterationsFile,
                definition.lastIteration());
        List<StuckRow> stuck = readStuckRows(stuckFile, definition.lastIteration());
        Map<String, LateStatistic> late = lateStatistics(iterations,
                definition.lateFirst(), definition.lateLast());

        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(
                MunichMunicipalBoundary.loadDefault());
        Path plansFile = required(output.resolve(definition.runId() + ".output_plans.xml.gz"),
                "final output plans");
        Path tripsFile = required(output.resolve(definition.runId() + ".output_trips.csv.gz"),
                "standard output trips");
        var plans = AnalyzeLiteratureBasedScoringDiagnosticOutput.analyzePlans(plansFile, filter);
        AnalyzeLiteratureBasedScoringDiagnosticOutput.validateStructuralTotals(plans);
        var measurements = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .analyzeTripsCsv(tripsFile, filter);

        Path inputPlans = Path.of(expected.plans().getInputFileURL(expected.getContext()).toURI());
        var distances = AuditLiteratureBasedScoringTripDistances
                .auditPopulations(inputPlans, plansFile, filter);
        require(distances.finalBothInside()
                        == ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE,
                "Final distance-audit denominator changed");

        Map<String, String> reports = new LinkedHashMap<>();
        if (definition.constantDerivation() != null) {
            reports.put(definition.prefix() + "_constant_derivation.csv",
                    Files.readString(required(definition.constantDerivation(),
                            "versioned constant derivation"), StandardCharsets.UTF_8));
        }
        reports.put(definition.prefix() + "_late_iteration_statistics.csv",
                lateCsv(late, definition));
        reports.put(definition.prefix() + "_final_mode_summary.csv",
                finalModeCsv(plans, measurements));
        reports.put(definition.prefix() + "_active_mode_distance_summary.csv",
                activeDistanceCsv(distances, definition));
        String status = decisionStatus(iterations, late, stuck, distances, definition);
        if (definition.finalRound()) {
            reports.put(definition.prefix() + "_final_calibration_assessment.csv",
                    finalAssessmentCsv(iterations, late, stuck, distances,
                            definition, status));
        } else {
            reports.put(definition.prefix() + "_recommended_next_constants.csv",
                    recommendationCsv(late, definition));
        }
        reports.put(definition.prefix() + "_report.md",
                report(iterations, late, stuck, plans, distances, definition, status));
        require(new ArrayList<>(reports.keySet()).equals(summaryFileNames(definition)),
                "Calibration summary file set differs from its round specification");
        publishSummaries(analysis, reports);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING CALIBRATION ROUND-%d ANALYSIS PASS%n"
                        + "iterations=0..%d BOTH_INSIDE=%d analysis=%s%n"
                        + "No Controller or QSim was started by the analyzer.%n",
                definition.roundNumber(), definition.lastIteration(),
                plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE),
                analysis);
    }

    static void validateCompletedOutput(Config expected, RoundDefinition definition,
            Path iterationsFile, Path stuckFile) throws Exception {
        Path output = definition.output();
        require(Files.isDirectory(output), "Missing completed Round-"
                + definition.roundNumber() + " output: " + output);
        Path log = required(output.resolve(definition.runId() + ".logfile.log"),
                "normal-shutdown log");
        require(Files.readString(log, StandardCharsets.UTF_8).contains("shutdown completed."),
                "Round-" + definition.roundNumber()
                        + " logfile contains no normal shutdown evidence");
        Path outputConfig = required(output.resolve(definition.runId()
                + ".output_config.xml"), "output config");
        expected.addModule(new SwissRailRaptorConfigGroup());
        Config actual = ConfigUtils.loadConfig(outputConfig.toString());
        List<String> differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(expected, actual);
        require(differences.isEmpty(), "Round-" + definition.roundNumber()
                + " output config differs semantically:\n- " + String.join("\n- ", differences));
        required(iterationsFile, "exact BOTH_INSIDE iteration history");
        required(stuckFile, "runtime stuck-event history");
        required(output.resolve(definition.runId() + ".output_events.xml.gz"),
                "final events");
    }

    static List<IterationSnapshot> readIterations(Path file) throws IOException {
        return readIterations(file, 40);
    }

    static List<IterationSnapshot> readIterations(Path file, int lastIteration)
            throws IOException {
        List<IterationSnapshot> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            require(header != null && header.startsWith("iteration,both_inside_trips,car_count"),
                    "Unexpected iteration-history header");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                Map<String, Long> modes = new LinkedHashMap<>();
                modes.put("car", Long.parseLong(f[2]));
                modes.put("pt", Long.parseLong(f[4]));
                modes.put("bike", Long.parseLong(f[6]));
                modes.put("walk", Long.parseLong(f[8]));
                long denominator = Long.parseLong(f[1]);
                long unexpected = Long.parseLong(f[10]);
                require(modes.values().stream().mapToLong(Long::longValue).sum() + unexpected
                                == denominator,
                        "Iteration mode counts do not reconcile in row " + f[0]);
                result.add(new IterationSnapshot(Integer.parseInt(f[0]), denominator,
                        Map.copyOf(modes), unexpected));
            }
        }
        require(result.size() == lastIteration + 1,
                "Iteration history must contain exactly iterations 0.." + lastIteration);
        for (int i = 0; i <= lastIteration; i++) {
            IterationSnapshot row = result.get(i);
            require(row.iteration() == i, "Missing or reordered calibration iteration " + i);
            require(row.bothInsideTrips()
                            == ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE,
                    "BOTH_INSIDE denominator changed in iteration " + i);
        }
        return List.copyOf(result);
    }

    static Map<String, LateStatistic> lateStatistics(List<IterationSnapshot> rows,
            int first, int last) {
        require(first <= last, "Invalid late window");
        Map<String, LateStatistic> result = new LinkedHashMap<>();
        for (String mode : MODES) {
            List<Point> points = rows.stream()
                    .filter(row -> row.iteration() >= first && row.iteration() <= last)
                    .map(row -> new Point(row.iteration(), row.share(mode))).toList();
            require(points.size() == last - first + 1,
                    "Incomplete late window for " + mode);
            double mean = points.stream().mapToDouble(Point::value).average().orElseThrow();
            double min = points.stream().mapToDouble(Point::value).min().orElseThrow();
            double max = points.stream().mapToDouble(Point::value).max().orElseThrow();
            double meanX = points.stream().mapToDouble(Point::iteration).average().orElseThrow();
            double numerator = points.stream().mapToDouble(point ->
                    (point.iteration() - meanX) * (point.value() - mean)).sum();
            double denominator = points.stream().mapToDouble(point ->
                    Math.pow(point.iteration() - meanX, 2)).sum();
            result.put(mode, new LateStatistic(mean, min, max, max - min,
                    numerator / denominator,
                    mean - ValidateLiteratureBasedScoringCalibrationRound1Config.TARGETS.get(mode)));
        }
        return Map.copyOf(result);
    }

    private static String iterationCsv(List<IterationSnapshot> rows) {
        StringBuilder csv = new StringBuilder("iteration,both_inside_trips,car_count,car_share_percent,pt_count,pt_share_percent,bike_count,bike_share_percent,walk_count,walk_share_percent,unexpected_mode_count,unexpected_modes\n");
        for (IterationSnapshot row : rows) {
            csv.append(row.iteration()).append(',').append(row.bothInsideTrips());
            for (String mode : MODES) csv.append(',').append(row.count(mode)).append(',')
                    .append(number(row.share(mode)));
            Map<String, Long> unexpected = new TreeMap<>(row.modes());
            MODES.forEach(unexpected::remove);
            csv.append(',').append(row.unexpectedModeCount()).append(',')
                    .append(unexpected.toString().replace(',', ';')).append('\n');
        }
        return csv.toString();
    }

    private static String stuckCsv(List<StuckIteration> rows) {
        StringBuilder csv = new StringBuilder("iteration,mode,event_count,unique_persons,exact_48h_events,affected_both_inside_unique_persons\n");
        for (StuckIteration row : rows) {
            Set<String> modes = new HashSet<>(row.byMode().keySet());
            modes.add("ALL");
            modes.stream().sorted().forEach(mode -> {
                StuckMetric metric = mode.equals("ALL") ? row.total() : row.byMode().get(mode);
                csv.append(row.iteration()).append(',').append(mode).append(',')
                        .append(metric.events()).append(',').append(metric.persons().size()).append(',')
                        .append(metric.exact48()).append(',').append(metric.bothInsidePersons().size()).append('\n');
            });
        }
        return csv.toString();
    }

    private static String lateCsv(Map<String, LateStatistic> late,
            RoundDefinition definition) {
        StringBuilder csv = new StringBuilder("mode,window,mean_share_percent,minimum_share_percent,maximum_share_percent,range_percentage_points,linear_trend_pp_per_iteration,target_share_percent,difference_from_target_pp,trend_status,stability_status,target_fit_status\n");
        for (String mode : MODES) {
            LateStatistic s = late.get(mode);
            csv.append(mode).append(',').append(definition.lateFirst()).append('-')
                    .append(definition.lateLast()).append(',').append(number(s.mean())).append(',')
                    .append(number(s.min())).append(',').append(number(s.max())).append(',')
                    .append(number(s.range())).append(',').append(number(s.trend())).append(',')
                    .append(number(target(mode))).append(',').append(number(s.targetDifference())).append(',')
                    .append(Math.abs(s.trend()) < TREND_LIMIT_PP ? "STABLE_TREND" : "UNSTABLE_TREND").append(',')
                    .append(s.range() <= LATE_RANGE_LIMIT_PP ? "STABLE_RANGE" : "UNSTABLE_RANGE").append(',')
                    .append(Math.abs(s.targetDifference()) <= TARGET_TOLERANCE_PP ? "WITHIN_TOLERANCE" : "OUTSIDE_TOLERANCE")
                    .append('\n');
        }
        return csv.toString();
    }

    private static String finalModeCsv(
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis measurements) {
        long denominator = plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        double totalMetres = measurements.modeMetrics().values().stream()
                .mapToDouble(AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric::distanceMetres).sum();
        StringBuilder csv = new StringBuilder("mode,plan_trip_count,trip_share_percent,target_share_percent,deviation_pp,measurement_record_count,measurement_coverage_percent,sample_pkm,pkm_share_percent,mean_trip_distance_km,mean_travel_time_minutes\n");
        for (String mode : MODES) {
            var plan = plans.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO);
            var measured = measurements.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO);
            double share = percent(plan.trips(), denominator);
            csv.append(mode).append(',').append(plan.trips()).append(',').append(number(share)).append(',')
                    .append(number(target(mode))).append(',').append(number(share - target(mode))).append(',')
                    .append(measured.trips()).append(',').append(number(percent(measured.trips(), plan.trips()))).append(',')
                    .append(number(measured.distanceMetres() / 1000.0)).append(',')
                    .append(number(percent(measured.distanceMetres(), totalMetres))).append(',')
                    .append(number(measured.trips() == 0 ? 0 : measured.distanceMetres() / measured.trips() / 1000.0)).append(',')
                    .append(number(measured.trips() == 0 ? 0 : measured.travelTimeSeconds() / measured.trips() / 60.0)).append('\n');
        }
        return csv.toString();
    }

    private static String activeDistanceCsv(
            AuditLiteratureBasedScoringTripDistances.AuditData data,
            RoundDefinition definition) throws IOException {
        StringBuilder csv = new StringBuilder("state,mode,threshold_km,mode_trip_count,trips_over_threshold,within_mode_share_percent,mean_od_km,median_od_km,p90_od_km,inherited_count,generated_count,unmatched_or_uncertain_count\n");
        appendActiveDistanceState(csv, data, data.inputState());
        if (definition.activeDistanceBaseline() != null) {
            int baselineRound = definition.roundNumber() - 1;
            Path baseline = required(definition.activeDistanceBaseline(),
                    "Round-" + baselineRound + " active-mode distance baseline");
            try (BufferedReader reader = Files.newBufferedReader(baseline,
                    StandardCharsets.UTF_8)) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("FINAL,")) {
                        csv.append("ROUND_").append(baselineRound)
                                .append("_FINAL,").append(line.substring(6)).append('\n');
                    }
                }
            }
        }
        appendActiveDistanceState(csv, data, data.finalState());
        return csv.toString();
    }

    private static void appendActiveDistanceState(StringBuilder csv,
            AuditLiteratureBasedScoringTripDistances.AuditData data,
            AuditLiteratureBasedScoringTripDistances.MutableState state) {
            for (String mode : List.of("walk", "bike")) {
                var metric = state.modes.getOrDefault(mode,
                        new AuditLiteratureBasedScoringTripDistances.ModeDistances());
                for (double threshold : AuditLiteratureBasedScoringTripDistances.THRESHOLDS_KM.get(mode)) {
                    long over = metric.od.stream().filter(value ->
                            AuditLiteratureBasedScoringTripDistances.exceeds(value, threshold)).count();
                    csv.append(state.name).append(',').append(mode).append(',').append(number(threshold)).append(',')
                            .append(metric.od.size()).append(',').append(over).append(',')
                            .append(number(percent(over, metric.od.size()))).append(',')
                            .append(km(mean(metric.od))).append(',')
                            .append(km(AuditLiteratureBasedScoringTripDistances.percentile(metric.od, .5))).append(',')
                            .append(km(AuditLiteratureBasedScoringTripDistances.percentile(metric.od, .9))).append(',');
                    if (state == data.finalState()) {
                        csv.append(originCount(data, mode, threshold, "INHERITED")).append(',')
                                .append(originCount(data, mode, threshold, "GENERATED")).append(',')
                                .append(originCount(data, mode, threshold, "UNMATCHED_OR_UNCERTAIN"));
                    } else csv.append(",,");
                    csv.append('\n');
                }
            }
    }

    private static String recommendationCsv(Map<String, LateStatistic> late,
            RoundDefinition definition) {
        Map<String, Double> means = new LinkedHashMap<>();
        late.forEach((mode, statistic) -> means.put(mode, statistic.mean()));
        var recommended = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(definition.currentAscs(), means, 0.5);
        StringBuilder csv = new StringBuilder("mode,current_asc,target_share_percent,late_mean_share_percent,undamped_reference_normalized_update,damping_factor,recommended_damped_update,recommended_round_")
                .append(definition.roundNumber() + 1).append("_asc\n");
        double walkRatio = target("walk") / means.get("walk");
        for (String mode : MODES) {
            double undamped = mode.equals("walk") ? 0
                    : Math.log((target(mode) / means.get(mode)) / walkRatio);
            double damped = mode.equals("walk") ? 0 : 0.5 * undamped;
            csv.append(mode).append(',')
                    .append(number(definition.currentAscs().get(mode))).append(',')
                    .append(number(target(mode))).append(',').append(number(means.get(mode))).append(',')
                    .append(number(undamped)).append(",0.500000000,").append(number(damped)).append(',')
                    .append(number(recommended.get(mode))).append('\n');
        }
        return csv.toString();
    }

    private static String report(List<IterationSnapshot> iterations,
            Map<String, LateStatistic> late, List<StuckRow> stuck,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            AuditLiteratureBasedScoringTripDistances.AuditData distances,
            RoundDefinition definition, String status) throws Exception {
        boolean unexpected = iterations.stream().anyMatch(row -> row.unexpectedModeCount() != 0);
        boolean stable = late.values().stream().allMatch(value ->
                Math.abs(value.trend()) < TREND_LIMIT_PP
                        && value.range() <= LATE_RANGE_LIMIT_PP);
        StuckAssessment stuckAssessment = stuckAssessment(stuck, definition);
        DistanceAssessment distanceAssessment = distanceAssessment(distances, definition);
        boolean distanceWorsened = distanceAssessment.materialWorsening();
        ClosestResult closest = closestLateResult(iterations, definition);
        long finalTrips = plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);

        StringBuilder md = new StringBuilder("# Literature-based scoring calibration Round ")
                .append(definition.roundNumber()).append("\n\n")
                .append("## Scope and execution\n\n")
                .append("The run completed iterations 0--").append(definition.lastIteration())
                .append(" with the frozen literature-based structural scoring and walk as the zero-ASC reference. Every iteration contains exactly ")
                .append(finalTrips).append(" Munich `BOTH_INSIDE` main trips. Stage activities do not create additional trips. Exact iteration values are selected-plan snapshots at MATSim's iteration-end lifecycle point.\n\n")
                .append("No established subtour-readiness classifier exists on this branch, so the optional fixed-versus-mode-choice-capable cohort split is not invented. Missing car-availability attributes remain a limitation and `considerCarAvailability=false` remains unchanged.\n\n")
                .append("## Late window and decision criteria\n\n")
                .append("Iterations ").append(definition.lateFirst()).append("--")
                .append(definition.lateLast()).append(" provide ten post-switch observations. Project review thresholds are: each late mean within +/-2 percentage points, absolute trend below 0.10 percentage points per iteration, range no greater than 2 percentage points, and late-window plus final-iteration affected BOTH_INSIDE persons no greater than 0.10% of the fixed trip denominator per iteration. These are transparent thesis criteria, not universal MATSim standards.\n\n")
                .append("| Mode | Late mean | Target difference | Trend | Range |\n|---|---:|---:|---:|---:|\n");
        for (String mode : MODES) {
            LateStatistic s = late.get(mode);
            md.append('|').append(mode).append('|').append(number(s.mean())).append("%|")
                    .append(number(s.targetDifference())).append(" pp|").append(number(s.trend()))
                    .append(" pp/iteration|").append(number(s.range())).append(" pp|\n");
        }
        md.append("\nPkm, travelled distance and active-mode distance distributions are validation outcomes; they are not direct ASC targets. The final distance summary compares the unchanged input, the prior round where available, and iteration ")
                .append(definition.lastIteration())
                .append(". No maximum walk or bike distance was introduced because the diagnostic audit found mostly inherited long active trips; this round tests their persistence under the unchanged scoring and choice set.\n\n")
                .append("Unexpected modes: ").append(unexpected ? "present" : "none")
                .append(". Cumulative StuckEvents: ").append(stuckAssessment.cumulativeEvents())
                .append("; late-window StuckEvents: ").append(stuckAssessment.lateEvents())
                .append("; final-iteration StuckEvents: ").append(stuckAssessment.finalEvents())
                .append(". Maximum late-window affected-person incidence: ")
                .append(number(stuckAssessment.maxLateIncidence())).append("%; final-iteration incidence: ")
                .append(number(stuckAssessment.finalIncidence()))
                .append("%. Early-iteration events remain documented but do not enter the decision. Material active-mode distance worsening versus input: ")
                .append(distanceAssessment.versusInput() ? "yes" : "no")
                .append("; additional worsening versus Round ")
                .append(definition.roundNumber() - 1).append(": ")
                .append(distanceAssessment.versusPriorRound() ? "yes" : "no")
                .append(".\n\n## Decision\n\n**").append(status).append("**\n\n")
                .append(stable
                        ? "The closest result within the stable late window is iteration "
                        : "No stable late-window result exists; the closest late-window observation is iteration ")
                .append(closest.iteration()).append(" (total deviation ")
                .append(number(closest.sumAbsoluteDeviation())).append(" percentage points): ")
                .append(formatShares(closest.shares())).append(". ");
        if (definition.finalRound()) {
            md.append("This is the final planned ASC round. A technically valid and stable result with every residual no greater than 4 percentage points is accepted only with reported residual deviation; a larger or unstable miss is reported as target not reached. No Round ")
                    .append(definition.roundNumber() + 1)
                    .append(" constants are calculated or created. Any remaining deviation is retained explicitly as a model limitation; Pkm shares remain validation outcomes rather than acceptance targets.\n");
        } else {
            md.append("A damped, walk-referenced next-round recommendation is reported for transparency only. It does not create or run another calibration round.\n");
        }
        if (definition.roundNumber() >= 4) {
            List<CalibrationHistoryRow> history = calibrationHistory(late, status,
                    definition);
            CandidateSelection selection = selectFinalCandidate(history,
                    stable && !unexpected && stuckAssessment.acceptable(),
                    distanceAssessment.versusPriorRound());
            md.append("\n## Calibration history and final parameter selection\n\n")
                    .append("| Round | Car | PT | Bike | Walk | Sum absolute deviation | Status |\n")
                    .append("|---|---:|---:|---:|---:|---:|---|\n");
            for (CalibrationHistoryRow row : history) {
                md.append('|').append(row.round()).append('|')
                        .append(number(row.means().get("car"))).append("%|")
                        .append(number(row.means().get("pt"))).append("%|")
                        .append(number(row.means().get("bike"))).append("%|")
                        .append(number(row.means().get("walk"))).append("%|")
                        .append(number(row.sumAbsoluteDeviation())).append(" pp|")
                        .append(row.status()).append("|\n");
            }
            md.append("\nSelected final parameter candidate: **Round ")
                    .append(selection.round()).append("**. ")
                    .append(selection.reason()).append(" The selection follows the pre-defined acceptance, stability and deviation criteria rather than automatically preferring the latest round. No Round ")
                    .append(definition.roundNumber() + 1)
                    .append(" is calculated or created.\n");
        }
        return md.toString();
    }

    private static String finalAssessmentCsv(List<IterationSnapshot> iterations,
            Map<String, LateStatistic> late, List<StuckRow> stuck,
            AuditLiteratureBasedScoringTripDistances.AuditData distances,
            RoundDefinition definition, String status) throws Exception {
        StuckAssessment assessment = stuckAssessment(stuck, definition);
        ClosestResult closest = closestLateResult(iterations, definition);
        boolean unexpected = iterations.stream().anyMatch(row -> row.unexpectedModeCount() != 0);
        DistanceAssessment distanceAssessment = distanceAssessment(distances, definition);
        StringBuilder csv = new StringBuilder("metric,mode,value,criterion,status\n")
                .append("overall_status,,,").append("final three-way decision,")
                .append(status).append('\n')
                .append("closest_late_iteration,,").append(closest.iteration())
                .append(",minimum summed absolute target deviation,REPORTED\n")
                .append("closest_late_sum_absolute_deviation_pp,,")
                .append(number(closest.sumAbsoluteDeviation()))
                .append(",descriptive only,REPORTED\n");
        for (String mode : MODES) {
            LateStatistic value = late.get(mode);
            csv.append("late_mean_share_percent,").append(mode).append(',')
                    .append(number(value.mean())).append(",target ")
                    .append(number(target(mode))).append(",")
                    .append(Math.abs(value.targetDifference()) <= TARGET_TOLERANCE_PP
                            ? "WITHIN_2_PP" : Math.abs(value.targetDifference()) <= 4.0
                            ? "WITHIN_4_PP" : "OVER_4_PP").append('\n');
        }
        csv.append("maximum_late_stuck_incidence_percent,,")
                .append(number(assessment.maxLateIncidence()))
                .append(",no greater than 0.10%,")
                .append(assessment.maxLateIncidence() <= STUCK_INCIDENCE_LIMIT_PERCENT
                        ? "PASS" : "FAIL").append('\n')
                .append("final_stuck_incidence_percent,,")
                .append(number(assessment.finalIncidence()))
                .append(",no greater than 0.10%,")
                .append(assessment.finalIncidence() <= STUCK_INCIDENCE_LIMIT_PERCENT
                        ? "PASS" : "FAIL").append('\n')
                .append("unexpected_modes,,").append(unexpected ? "present" : "none")
                .append(",none allowed,").append(unexpected ? "FAIL" : "PASS").append('\n')
                .append("active_mode_distance_worsening_vs_input,,")
                .append(distanceAssessment.versusInput() ? "yes" : "no")
                .append(",no material worsening,")
                .append(distanceAssessment.versusInput() ? "FAIL" : "PASS").append('\n')
                .append("active_mode_distance_worsening_vs_round_")
                .append(definition.roundNumber() - 1).append(",,")
                .append(distanceAssessment.versusPriorRound() ? "yes" : "no")
                .append(",no additional material worsening,")
                .append(distanceAssessment.versusPriorRound() ? "FAIL" : "PASS").append('\n')
                .append("automatic_next_round_created,,no,Round ")
                .append(definition.roundNumber()).append(" is final,PASS\n");
        if (definition.roundNumber() >= 4) {
            boolean stable = late.values().stream().allMatch(value ->
                    Math.abs(value.trend()) < TREND_LIMIT_PP
                            && value.range() <= LATE_RANGE_LIMIT_PP);
            List<CalibrationHistoryRow> history = calibrationHistory(late, status,
                    definition);
            CandidateSelection selection = selectFinalCandidate(history,
                    stable && !unexpected && assessment.acceptable(),
                    distanceAssessment.versusPriorRound());
            for (CalibrationHistoryRow row : history) {
                for (String mode : MODES) {
                    csv.append("calibration_history_late_mean,")
                            .append("round_").append(row.round()).append('_').append(mode)
                            .append(',').append(number(row.means().get(mode)))
                            .append(",descriptive comparison,REPORTED\n");
                }
                csv.append("calibration_history_sum_absolute_deviation_pp,round_")
                        .append(row.round()).append(',')
                        .append(number(row.sumAbsoluteDeviation()))
                        .append(",lower is closer to trip-share targets,")
                        .append(row.status()).append('\n');
            }
            csv.append("selected_final_parameter_candidate,,round_")
                    .append(selection.round())
                    .append(",pre-defined acceptance stability and deviation criteria,SELECTED\n")
                    .append("selection_reason,,\"")
                    .append(selection.reason().replace("\"", "\"\""))
                    .append("\",not automatically the latest round,REPORTED\n");
        }
        return csv.toString();
    }

    static List<String> summaryFileNames(RoundDefinition definition) {
        List<String> names = new ArrayList<>();
        if (definition.constantDerivation() != null) {
            names.add(definition.prefix() + "_constant_derivation.csv");
        }
        names.add(definition.prefix() + "_late_iteration_statistics.csv");
        names.add(definition.prefix() + "_final_mode_summary.csv");
        names.add(definition.prefix() + "_active_mode_distance_summary.csv");
        names.add(definition.prefix() + (definition.finalRound()
                ? "_final_calibration_assessment.csv"
                : "_recommended_next_constants.csv"));
        names.add(definition.prefix() + "_report.md");
        return List.copyOf(names);
    }

    static StuckAssessment stuckAssessment(List<StuckRow> stuck,
            RoundDefinition definition) {
        List<StuckRow> allRows = stuck.stream().filter(row -> row.mode().equals("ALL"))
                .toList();
        long cumulative = allRows.stream().mapToLong(StuckRow::events).sum();
        long lateEvents = allRows.stream().filter(row ->
                        row.iteration() >= definition.lateFirst()
                                && row.iteration() <= definition.lateLast())
                .mapToLong(StuckRow::events).sum();
        long maxLateAffected = allRows.stream().filter(row ->
                        row.iteration() >= definition.lateFirst()
                                && row.iteration() <= definition.lateLast())
                .mapToLong(StuckRow::affectedBothInside).max().orElse(0);
        StuckRow finalRow = allRows.stream()
                .filter(row -> row.iteration() == definition.lastIteration())
                .findFirst().orElseThrow();
        double maxLateIncidence = percent(maxLateAffected,
                ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE);
        double finalIncidence = percent(finalRow.affectedBothInside(),
                ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_BOTH_INSIDE);
        return new StuckAssessment(cumulative, lateEvents, finalRow.events(),
                maxLateIncidence, finalIncidence,
                maxLateIncidence <= STUCK_INCIDENCE_LIMIT_PERCENT
                        && finalIncidence <= STUCK_INCIDENCE_LIMIT_PERCENT);
    }

    static String decisionStatus(boolean stable, boolean withinTargets,
            boolean unexpectedModes, boolean stuckAcceptable,
            boolean distanceWorsened, RoundDefinition definition) {
        return decisionStatus(stable, withinTargets, true, unexpectedModes,
                stuckAcceptable, distanceWorsened, definition);
    }

    static String decisionStatus(boolean stable, boolean withinTwo,
            boolean withinFour, boolean unexpectedModes, boolean stuckAcceptable,
            boolean distanceWorsened, RoundDefinition definition) {
        if (definition.finalRound()) {
            if (!stable || !withinFour || unexpectedModes || !stuckAcceptable
                    || distanceWorsened) {
                return "CALIBRATION_TARGET_NOT_REACHED";
            }
            return withinTwo ? (definition.roundNumber() >= 4 ? "ACCEPT"
                    : "ACCEPT_CALIBRATION")
                    : "ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION";
        }
        if (!stable || unexpectedModes || !stuckAcceptable || distanceWorsened) {
            return "STRUCTURAL_REVIEW_REQUIRED";
        }
        return withinTwo ? "ACCEPT_CALIBRATION" : definition.additionalUpdateStatus();
    }

    static String decisionStatus(List<IterationSnapshot> iterations,
            Map<String, LateStatistic> late, List<StuckRow> stuck,
            AuditLiteratureBasedScoringTripDistances.AuditData distances,
            RoundDefinition definition) {
        boolean unexpected = iterations.stream().anyMatch(row -> row.unexpectedModeCount() != 0);
        boolean stable = late.values().stream().allMatch(value ->
                Math.abs(value.trend()) < TREND_LIMIT_PP
                        && value.range() <= LATE_RANGE_LIMIT_PP);
        boolean withinTwo = late.values().stream().allMatch(value ->
                Math.abs(value.targetDifference()) <= TARGET_TOLERANCE_PP);
        boolean withinFour = late.values().stream().allMatch(value ->
                Math.abs(value.targetDifference()) <= 4.0);
        return decisionStatus(stable, withinTwo, withinFour, unexpected,
                stuckAssessment(stuck, definition).acceptable(),
                distanceAssessment(distances, definition).materialWorsening(), definition);
    }

    static ClosestResult closestLateResult(List<IterationSnapshot> iterations,
            RoundDefinition definition) {
        return iterations.stream().filter(row -> row.iteration() >= definition.lateFirst()
                        && row.iteration() <= definition.lateLast())
                .map(row -> {
                    Map<String, Double> shares = new LinkedHashMap<>();
                    double total = 0;
                    for (String mode : MODES) {
                        shares.put(mode, row.share(mode));
                        total += Math.abs(row.share(mode) - target(mode));
                    }
                    return new ClosestResult(row.iteration(), total, Map.copyOf(shares));
                })
                .min(Comparator.comparingDouble(ClosestResult::sumAbsoluteDeviation)
                        .thenComparingInt(ClosestResult::iteration))
                .orElseThrow();
    }

    private static String formatShares(Map<String, Double> shares) {
        StringBuilder value = new StringBuilder();
        for (String mode : MODES) {
            if (!value.isEmpty()) value.append(", ");
            value.append(mode).append(' ').append(number(shares.get(mode))).append('%');
        }
        return value.toString();
    }

    static List<CalibrationHistoryRow> calibrationHistory(
            Map<String, LateStatistic> currentLate, String currentStatus,
            RoundDefinition definition) throws Exception {
        List<CalibrationHistoryRow> history = new ArrayList<>();
        for (int round = 1; round < definition.roundNumber(); round++) {
            history.add(readCalibrationHistoryRow(round, lateFile(round), reportFile(round)));
        }
        Map<String, Double> means = new LinkedHashMap<>();
        currentLate.forEach((mode, statistic) -> means.put(mode, statistic.mean()));
        boolean stable = currentLate.values().stream().allMatch(value ->
                Math.abs(value.trend()) < TREND_LIMIT_PP
                        && value.range() <= LATE_RANGE_LIMIT_PP);
        history.add(new CalibrationHistoryRow(definition.roundNumber(), Map.copyOf(means),
                sumAbsoluteDeviation(means), stable, currentStatus));
        return List.copyOf(history);
    }

    private static Path lateFile(int round) {
        return switch (round) {
            case 1 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_1_LATE;
            case 2 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_2_LATE;
            case 3 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3_LATE;
            case 4 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4_LATE;
            default -> throw new IllegalStateException("Unsupported calibration history round "
                    + round);
        };
    }

    private static Path reportFile(int round) {
        return switch (round) {
            case 1 -> ValidateLiteratureBasedScoringCalibrationRound1Config.OUTPUT.resolve(
                    "analysis/round_1_report.md");
            case 2 -> ValidateLiteratureBasedScoringCalibrationRound2Config.OUTPUT.resolve(
                    "analysis/round_2_report.md");
            case 3 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3_ANALYSIS
                    .resolve("round_3_report.md");
            case 4 -> ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4_ANALYSIS
                    .resolve("round_4_report.md");
            default -> throw new IllegalStateException("Unsupported calibration history round "
                    + round);
        };
    }

    static CalibrationHistoryRow readCalibrationHistoryRow(int round, Path lateFile,
            Path reportFile) throws IOException {
        Map<String, Double> means = new LinkedHashMap<>();
        boolean stable = true;
        try (BufferedReader reader = Files.newBufferedReader(required(lateFile,
                "Round-" + round + " late statistics"), StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                require(f.length >= 11, "Malformed Round-" + round + " late statistics");
                means.put(f[0], Double.parseDouble(f[2]));
                stable &= Math.abs(Double.parseDouble(f[6])) < TREND_LIMIT_PP
                        && Double.parseDouble(f[5]) <= LATE_RANGE_LIMIT_PP;
            }
        }
        require(means.keySet().containsAll(MODES) && means.size() == MODES.size(),
                "Round-" + round + " late statistics do not contain four modes");
        String report = Files.readString(required(reportFile,
                "Round-" + round + " report"), StandardCharsets.UTF_8);
        int start = report.indexOf("**");
        int end = start < 0 ? -1 : report.indexOf("**", start + 2);
        require(start >= 0 && end > start, "Round-" + round + " report has no status");
        return new CalibrationHistoryRow(round, Map.copyOf(means),
                sumAbsoluteDeviation(means), stable,
                report.substring(start + 2, end));
    }

    static CandidateSelection selectFinalCandidate(List<CalibrationHistoryRow> history) {
        return selectFinalCandidate(history, true, false);
    }

    static CandidateSelection selectFinalCandidate(List<CalibrationHistoryRow> history,
            boolean latestTechnicallyStable, boolean latestDistanceWorsenedVersusPrior) {
        int latestRound = history.stream().mapToInt(CalibrationHistoryRow::round)
                .max().orElseThrow();
        CalibrationHistoryRow prior = history.stream()
                .filter(row -> row.round() == latestRound - 1)
                .findFirst().orElseThrow();
        CalibrationHistoryRow latest = history.stream()
                .filter(row -> row.round() == latestRound)
                .findFirst().orElseThrow();
        if (!latestTechnicallyStable) {
            return new CandidateSelection(prior.round(), "Round " + prior.round()
                    + " remains the final candidate because Round " + latest.round()
                    + " is not technically stable.");
        }
        int priorRank = statusRank(prior.status());
        int latestRank = statusRank(latest.status());
        if (latestRank < priorRank) {
            return new CandidateSelection(latest.round(), "Round " + latest.round()
                    + " attains the stronger pre-defined acceptance status.");
        }
        if (priorRank < latestRank) {
            return new CandidateSelection(prior.round(), "Round " + prior.round()
                    + " remains the final candidate because Round " + latest.round()
                    + " attains a weaker acceptance status.");
        }
        if (latest.sumAbsoluteDeviation() < prior.sumAbsoluteDeviation()
                && !latestDistanceWorsenedVersusPrior) {
            return new CandidateSelection(latest.round(), "Rounds " + prior.round()
                    + " and " + latest.round()
                    + " have the same acceptance class; the latter is technically stable, has the lower summed absolute target deviation and shows no relevant additional active-distance worsening.");
        }
        return new CandidateSelection(prior.round(), "Round " + prior.round()
                + " remains the final candidate because Round " + latest.round()
                + " does not satisfy the pre-defined same-class improvement rule.");
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "ACCEPT", "ACCEPT_CALIBRATION" -> 0;
            case "ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION" -> 1;
            default -> 2;
        };
    }

    private static double sumAbsoluteDeviation(Map<String, Double> means) {
        return MODES.stream().mapToDouble(mode ->
                Math.abs(means.get(mode) - target(mode))).sum();
    }

    static boolean materialDistanceWorsening(
            AuditLiteratureBasedScoringTripDistances.AuditData data) {
        for (String mode : List.of("walk", "bike")) {
            var input = data.inputState().modes.get(mode);
            var fin = data.finalState().modes.get(mode);
            if (input == null || fin == null) return true;
            double meanChange = relativeChange(mean(input.od), mean(fin.od));
            double p90Change = relativeChange(
                    AuditLiteratureBasedScoringTripDistances.percentile(input.od, .9),
                    AuditLiteratureBasedScoringTripDistances.percentile(fin.od, .9));
            double threshold = AuditLiteratureBasedScoringTripDistances.THRESHOLDS_KM.get(mode)[0];
            double inShare = percent(input.od.stream().filter(value ->
                    AuditLiteratureBasedScoringTripDistances.exceeds(value, threshold)).count(), input.od.size());
            double outShare = percent(fin.od.stream().filter(value ->
                    AuditLiteratureBasedScoringTripDistances.exceeds(value, threshold)).count(), fin.od.size());
            if ((meanChange >= 10 || p90Change >= 10) && outShare - inShare >= 1.0) return true;
        }
        return false;
    }

    static DistanceAssessment distanceAssessment(
            AuditLiteratureBasedScoringTripDistances.AuditData data,
            RoundDefinition definition) {
        boolean versusInput = materialDistanceWorsening(data);
        if (definition.activeDistanceBaseline() == null) {
            return new DistanceAssessment(versusInput, false);
        }
        try {
            Map<String, ActiveDistanceReference> baseline = readActiveDistanceReference(
                    definition.activeDistanceBaseline());
            boolean versusPriorRound = false;
            for (String mode : List.of("walk", "bike")) {
                double threshold = AuditLiteratureBasedScoringTripDistances
                        .THRESHOLDS_KM.get(mode)[0];
                ActiveDistanceReference reference = baseline.get(mode + "|" + threshold);
                var current = data.finalState().modes.get(mode);
                require(reference != null && current != null,
                        "Missing prior-round/current active-distance comparison for " + mode);
                double currentMean = mean(current.od) / 1000.0;
                double currentP90 = AuditLiteratureBasedScoringTripDistances
                        .percentile(current.od, .9) / 1000.0;
                double currentShare = percent(current.od.stream().filter(value ->
                        AuditLiteratureBasedScoringTripDistances.exceeds(value, threshold))
                        .count(), current.od.size());
                if ((relativeChange(reference.meanOdKm(), currentMean) >= 10
                        || relativeChange(reference.p90OdKm(), currentP90) >= 10)
                        && currentShare - reference.thresholdSharePercent() >= 1.0) {
                    versusPriorRound = true;
                }
            }
            return new DistanceAssessment(versusInput, versusPriorRound);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read prior-round active-distance comparison", exception);
        }
    }

    static Map<String, ActiveDistanceReference> readActiveDistanceReference(Path file)
            throws IOException {
        Map<String, ActiveDistanceReference> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(required(file,
                "active-distance baseline"), StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                require(f.length == 12, "Malformed active-distance baseline row");
                if (!"FINAL".equals(f[0])) continue;
                double threshold = Double.parseDouble(f[2]);
                result.put(f[1] + "|" + threshold,
                        new ActiveDistanceReference(Double.parseDouble(f[5]),
                                Double.parseDouble(f[6]), Double.parseDouble(f[8])));
            }
        }
        return Map.copyOf(result);
    }

    private static List<StuckRow> readStuckRows(Path file, int lastIteration)
            throws IOException {
        List<StuckRow> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            require(reader.readLine() != null, "Empty stuck-event history");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                result.add(new StuckRow(Integer.parseInt(f[0]), f[1], Long.parseLong(f[2]),
                        Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5])));
            }
        }
        require(result.stream().filter(row -> row.mode().equals("ALL")).count()
                        == lastIteration + 1,
                "Stuck-event history must contain one ALL row per iteration");
        return List.copyOf(result);
    }

    private static void publishSummaries(Path analysis, Map<String, String> reports)
            throws IOException {
        for (String name : reports.keySet()) {
            require(!Files.exists(analysis.resolve(name)),
                    "Calibration analysis file already exists and will not be overwritten: " + name);
        }
        Path temporary = analysis.resolve(".calibration-summary-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            for (var report : reports.entrySet()) Files.writeString(
                    temporary.resolve(report.getKey()), report.getValue(), StandardCharsets.UTF_8);
            for (String name : reports.keySet()) {
                moveAtomic(temporary.resolve(name), analysis.resolve(name));
            }
            Files.delete(temporary);
        } catch (IOException | RuntimeException exception) {
            if (Files.exists(temporary)) try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
            throw exception;
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveAtomic(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static long originCount(AuditLiteratureBasedScoringTripDistances.AuditData data,
            String mode, double threshold, String classification) {
        return data.longActive().entrySet().stream().filter(entry ->
                entry.getKey().finalMode().equals(mode)
                        && entry.getKey().thresholdKm() == threshold
                        && entry.getKey().originClassification().equals(classification))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double relativeChange(double before, double after) {
        return before == 0 ? (after == 0 ? 0 : Double.POSITIVE_INFINITY)
                : Math.abs(after - before) / Math.abs(before) * 100.0;
    }

    private static double target(String mode) {
        return ValidateLiteratureBasedScoringCalibrationRound1Config.TARGETS.get(mode);
    }

    private static double percent(double part, double total) {
        return total == 0 ? 0 : part / total * 100.0;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static String km(double metres) {
        return Double.isFinite(metres) ? number(metres / 1000.0) : "";
    }

    private static Path required(Path file, String label) {
        require(Files.isRegularFile(file), "Missing " + label + ": " + file);
        return file;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static RoundDefinition round1Definition() {
        return new RoundDefinition(1, OUTPUT, RUN_ID, 40, LATE_FIRST, LATE_LAST,
                "round_1",
                ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_ASCS,
                "ONE_MORE_ASC_ROUND_REQUIRED", false, null, null);
    }

    record RoundDefinition(int roundNumber, Path output, String runId,
            int lastIteration, int lateFirst, int lateLast, String prefix,
            Map<String, Double> currentAscs, String additionalUpdateStatus,
            boolean finalRound, Path constantDerivation,
            Path activeDistanceBaseline) { }
    record ScopeSelection(BitSet bothInside, int totalMainTrips) { }
    record IterationSnapshot(int iteration, long bothInsideTrips,
            Map<String, Long> modes, long unexpectedModeCount) {
        long count(String mode) { return modes.getOrDefault(mode, 0L); }
        double share(String mode) { return percent(count(mode), bothInsideTrips); }
    }
    record Point(int iteration, double value) { }
    record LateStatistic(double mean, double min, double max, double range,
            double trend, double targetDifference) { }
    record StuckMetric(long events, Set<String> persons, long exact48,
            Set<String> bothInsidePersons) { }
    record StuckIteration(int iteration, Map<String, StuckMetric> byMode,
            StuckMetric total) { }
    record StuckRow(int iteration, String mode, long events, long uniquePersons,
            long exact48, long affectedBothInside) { }
    record StuckAssessment(long cumulativeEvents, long lateEvents, long finalEvents,
            double maxLateIncidence, double finalIncidence, boolean acceptable) { }
    record ClosestResult(int iteration, double sumAbsoluteDeviation,
            Map<String, Double> shares) { }
    record DistanceAssessment(boolean versusInput, boolean versusPriorRound) {
        boolean materialWorsening() { return versusInput || versusPriorRound; }
    }
    record ActiveDistanceReference(double thresholdSharePercent,
            double meanOdKm, double p90OdKm) { }
    record CalibrationHistoryRow(int round, Map<String, Double> means,
            double sumAbsoluteDeviation, boolean stable, String status) { }
    record CandidateSelection(int round, String reason) { }

    private static final class MutableStuck {
        private final Map<String, MutableStuckMetric> modes = new HashMap<>();
        private final MutableStuckMetric total = new MutableStuckMetric();
        void add(PersonStuckEvent event, boolean bothInside) {
            String mode = event.getLegMode() == null || event.getLegMode().isBlank()
                    ? "unknown" : event.getLegMode().toLowerCase(Locale.ROOT);
            modes.computeIfAbsent(mode, ignored -> new MutableStuckMetric()).add(event, bothInside);
            total.add(event, bothInside);
        }
        StuckIteration freeze(int iteration) {
            Map<String, StuckMetric> frozen = new TreeMap<>();
            modes.forEach((mode, metric) -> frozen.put(mode, metric.freeze()));
            return new StuckIteration(iteration, Map.copyOf(frozen), total.freeze());
        }
    }

    private static final class MutableStuckMetric {
        long events;
        long exact48;
        final Set<String> persons = new HashSet<>();
        final Set<String> bothInsidePersons = new HashSet<>();
        void add(PersonStuckEvent event, boolean bothInside) {
            events++;
            persons.add(event.getPersonId().toString());
            if (Math.abs(event.getTime() - 48 * 3600.0) < 1e-9) exact48++;
            if (bothInside) bothInsidePersons.add(event.getPersonId().toString());
        }
        StuckMetric freeze() {
            return new StuckMetric(events, Set.copyOf(persons), exact48,
                    Set.copyOf(bothInsidePersons));
        }
    }
}
