package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Fixed-path, read-only audit of input and final trip distances for the
 * literature-based scoring diagnostic. No controller is created.
 */
public final class AuditLiteratureBasedScoringTripDistances {
    static final Path OUTPUT = ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT;
    static final Path AUDIT = OUTPUT.resolve("analysis/distance-audit");
    static final String RUN_ID = ValidateLiteratureBasedScoringDiagnosticConfig.RUN_ID;
    static final int EXPECTED_PERSONS = 324_043;
    static final long EXPECTED_TRIPS = 540_468;
    static final long EXPECTED_BOTH_INSIDE = 160_603;
    static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    static final Map<String, double[]> THRESHOLDS_KM = Map.of(
            "walk", new double[] {3, 5, 10},
            "bike", new double[] {5, 10, 20});
    private static final double COORDINATE_EPSILON = 1e-6;

    private AuditLiteratureBasedScoringTripDistances() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The distance audit accepts no arguments");
        require(!Files.exists(AUDIT), "Distance audit already exists and will not be overwritten: " + AUDIT);

        Config config = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate(false);
        Path inputPlans = Path.of(config.plans().getInputFileURL(config.getContext()).toURI());
        Path finalPlans = required(OUTPUT.resolve(RUN_ID + ".output_plans.xml.gz"), "final output plans");
        Path standardTrips = optional(OUTPUT.resolve(RUN_ID + ".output_trips.csv.gz"));
        Path run07Summary = required(OUTPUT.resolve("analysis/literature_based_scoring_final_mode_summary.csv"),
                "Run 07 final mode summary");

        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(MunichMunicipalBoundary.loadDefault());
        AuditData data = auditPopulations(inputPlans, finalPlans, filter);
        require(data.inputPersons() == EXPECTED_PERSONS && data.finalPersons() == EXPECTED_PERSONS,
                "Unexpected person totals: input=" + data.inputPersons() + ", final=" + data.finalPersons());
        require(data.inputTrips() == EXPECTED_TRIPS && data.finalTrips() == EXPECTED_TRIPS,
                "Unexpected main-trip totals: input=" + data.inputTrips() + ", final=" + data.finalTrips());
        require(data.finalBothInside() == EXPECTED_BOTH_INSIDE,
                "Unexpected final BOTH_INSIDE total: expected " + EXPECTED_BOTH_INSIDE
                        + ", actual " + data.finalBothInside());
        require(data.unmatchedInput() == 0 && data.unmatchedFinal() == 0,
                "Input/final trip matching is incomplete: unmatched input=" + data.unmatchedInput()
                        + ", unmatched final=" + data.unmatchedFinal());
        reconcileRun07(run07Summary, data);

        long standardTripRecords = standardTrips == null ? -1 : countDataRows(standardTrips);
        Map<String, String> reports = reports(data, inputPlans, finalPlans, standardTrips, standardTripRecords);
        publishAtomically(reports);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING DISTANCE AUDIT PASS%n"
                        + "persons=%d trips=%d final_BOTH_INSIDE=%d matched=%d%n"
                        + "output=%s%nNo Controller or QSim was started.%n",
                data.finalPersons(), data.finalTrips(), data.finalBothInside(),
                data.transitions().values().stream().mapToLong(List::size).sum(), AUDIT);
    }

    static AuditData auditPopulations(Path inputPlans, Path finalPlans,
            MunichTripBoundaryFilter filter) {
        require(Files.isRegularFile(inputPlans), "Missing input population: " + inputPlans);
        require(Files.isRegularFile(finalPlans), "Missing final output plans: " + finalPlans);

        Map<TripKey, TripObservation> input = new HashMap<>(700_000);
        MutableState inputState = new MutableState("INPUT");
        long[] inputCounts = stream(inputPlans, filter, (person, index, observation) -> {
            TripKey key = new TripKey(person, index);
            putUnique(input, key, observation, "input population");
            inputState.add(observation);
        });

        MutableState finalState = new MutableState("FINAL");
        Map<TransitionKey, List<Double>> transitions = new HashMap<>();
        Map<LongActiveKey, Long> longActive = new HashMap<>();
        long[] unmatchedFinal = {0};
        long[] finalCounts = stream(finalPlans, filter, (person, index, observation) -> {
            finalState.add(observation);
            TripKey key = new TripKey(person, index);
            TripObservation original = input.remove(key);
            if (original == null) {
                unmatchedFinal[0]++;
                addLongActive(longActive, observation, null, "UNMATCHED_OR_UNCERTAIN");
                return;
            }
            require(sameTrip(original, observation), "Trip identity differs for " + key
                    + ": input=" + original.identity() + ", final=" + observation.identity());
            if (observation.category() == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) {
                transitions.computeIfAbsent(new TransitionKey(original.mode(), observation.mode()), ignored -> new ArrayList<>())
                        .add(observation.odMetres());
                String origin = original.mode().equals(observation.mode())
                        && (observation.mode().equals("walk") || observation.mode().equals("bike"))
                        ? "INHERITED" : "GENERATED";
                addLongActive(longActive, observation, original.mode(), origin);
            }
        });

        return new AuditData(inputCounts[0], finalCounts[0], inputCounts[1], finalCounts[1],
                finalState.bothInside, input.size(), unmatchedFinal[0], inputState, finalState,
                freezeTransitions(transitions), Map.copyOf(longActive));
    }

    private static long[] stream(Path file, MunichTripBoundaryFilter filter, TripConsumer consumer) {
        long[] counts = {0, 0};
        Object lock = new Object();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            synchronized (lock) {
                if (failure.get() != null) return;
                try {
                    counts[0]++;
                    Plan plan = person.getSelectedPlan();
                    require(plan != null, "Person has no selected plan in " + file + ": " + person.getId());
                    List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(
                            plan, StageActivityTypeIdentifier::isStageActivity);
                    List<MunichTripBoundaryFilter.ClassifiedTrip> classified = filter.classify(plan);
                    require(trips.size() == classified.size(), "Stage-aware trip mismatch for person " + person.getId());
                    for (int index = 0; index < trips.size(); index++) {
                        counts[1]++;
                        var trip = trips.get(index);
                        var spatial = classified.get(index);
                        consumer.accept(person.getId().toString(), index,
                                observation(spatial, trip));
                    }
                } catch (RuntimeException exception) {
                    failure.compareAndSet(null, exception);
                }
            }
        });
        reader.readFile(file.toString());
        if (failure.get() != null) throw failure.get();
        return counts;
    }

    static TripObservation observation(MunichTripBoundaryFilter.ClassifiedTrip classified,
            TripStructureUtils.Trip trip) {
        Activity origin = classified.origin();
        Activity destination = classified.destination();
        Coord from = origin == null ? null : origin.getCoord();
        Coord to = destination == null ? null : destination.getCoord();
        double od = valid(from) && valid(to)
                ? Math.hypot(to.getX() - from.getX(), to.getY() - from.getY()) : Double.NaN;
        return new TripObservation(classified.inputMainMode(), classified.category(),
                origin == null ? "" : origin.getType(), destination == null ? "" : destination.getType(),
                from == null ? Double.NaN : from.getX(), from == null ? Double.NaN : from.getY(),
                to == null ? Double.NaN : to.getX(), to == null ? Double.NaN : to.getY(), od,
                routeDistance(trip));
    }

    static double routeDistance(TripStructureUtils.Trip trip) {
        double total = 0;
        boolean legFound = false;
        for (var element : trip.getTripElements()) {
            if (element instanceof Leg leg) {
                legFound = true;
                if (leg.getRoute() == null || !Double.isFinite(leg.getRoute().getDistance())
                        || leg.getRoute().getDistance() < 0) return Double.NaN;
                total += leg.getRoute().getDistance();
            }
        }
        return legFound ? total : Double.NaN;
    }

    static boolean sameTrip(TripObservation input, TripObservation output) {
        return Objects.equals(input.originType(), output.originType())
                && Objects.equals(input.destinationType(), output.destinationType())
                && same(input.originX(), output.originX()) && same(input.originY(), output.originY())
                && same(input.destinationX(), output.destinationX())
                && same(input.destinationY(), output.destinationY());
    }

    static <K, V> void putUnique(Map<K, V> values, K key, V value, String source) {
        require(values.putIfAbsent(key, value) == null,
                "Duplicate trip key in " + source + ": " + key);
    }

    static String distanceBin(double metres) {
        require(Double.isFinite(metres) && metres >= 0, "Invalid OD distance: " + metres);
        double km = metres / 1000.0;
        if (km <= 1) return "0-1 km";
        if (km <= 2) return ">1-2 km";
        if (km <= 3) return ">2-3 km";
        if (km <= 5) return ">3-5 km";
        if (km <= 10) return ">5-10 km";
        if (km <= 20) return ">10-20 km";
        return ">20 km";
    }

    static boolean exceeds(double metres, double thresholdKm) {
        return Double.isFinite(metres) && metres > thresholdKm * 1000.0;
    }

    static double percentile(List<Double> values, double probability) {
        require(probability >= 0 && probability <= 1, "Invalid percentile probability");
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = values.stream().sorted().toList();
        double position = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private static Map<TransitionKey, List<Double>> freezeTransitions(Map<TransitionKey, List<Double>> source) {
        Map<TransitionKey, List<Double>> result = new TreeMap<>(Comparator
                .comparing(TransitionKey::inputMode).thenComparing(TransitionKey::finalMode));
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private static void addLongActive(Map<LongActiveKey, Long> result, TripObservation finalTrip,
            String inputMode, String origin) {
        if (finalTrip.category() != MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) return;
        double[] thresholds = THRESHOLDS_KM.get(finalTrip.mode());
        if (thresholds == null) return;
        for (double threshold : thresholds) {
            if (exceeds(finalTrip.odMetres(), threshold)) {
                result.merge(new LongActiveKey(finalTrip.mode(), threshold,
                        inputMode == null ? "" : inputMode, origin), 1L, Long::sum);
            }
        }
    }

    private static Map<String, String> reports(AuditData data, Path inputPlans, Path finalPlans,
            Path standardTrips, long standardTripRecords) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("distance_distribution_by_state_and_mode.csv", distributionCsv(data));
        result.put("distance_bins_by_state_and_mode.csv", binsCsv(data));
        result.put("walk_and_bike_thresholds.csv", thresholdsCsv(data));
        result.put("input_to_final_mode_transitions.csv", transitionsCsv(data));
        result.put("long_active_trip_origin_modes.csv", longActiveCsv(data));
        result.put("distance_audit_report.md", report(data, inputPlans, finalPlans,
                standardTrips, standardTripRecords));
        return result;
    }

    private static String distributionCsv(AuditData data) {
        StringBuilder csv = new StringBuilder("state,mode,trip_count,share_percent,mean_od_km,median_od_km,p75_od_km,p90_od_km,p95_od_km,max_od_km,mean_travelled_km,travelled_distance_coverage_count,travelled_distance_coverage_percent,sample_pkm\n");
        for (MutableState state : List.of(data.inputState(), data.finalState())) {
            for (String mode : modesWithUnexpected(state)) {
                ModeDistances metric = state.modes.getOrDefault(mode, new ModeDistances());
                csv.append(state.name).append(',').append(mode).append(',').append(metric.od.size()).append(',')
                        .append(number(percent(metric.od.size(), state.bothInside))).append(',')
                        .append(km(mean(metric.od))).append(',').append(km(percentile(metric.od, .5))).append(',')
                        .append(km(percentile(metric.od, .75))).append(',').append(km(percentile(metric.od, .9))).append(',')
                        .append(km(percentile(metric.od, .95))).append(',').append(km(max(metric.od))).append(',')
                        .append(km(mean(metric.route))).append(',').append(metric.route.size()).append(',')
                        .append(number(percent(metric.route.size(), metric.od.size()))).append(',')
                        .append(number(sum(metric.route) / 1000.0)).append('\n');
            }
        }
        return csv.toString();
    }

    private static String binsCsv(AuditData data) {
        List<String> bins = List.of("0-1 km", ">1-2 km", ">2-3 km", ">3-5 km", ">5-10 km", ">10-20 km", ">20 km");
        StringBuilder csv = new StringBuilder("state,mode,od_distance_bin,trip_count,within_mode_share_percent\n");
        for (MutableState state : List.of(data.inputState(), data.finalState())) {
            for (String mode : modesWithUnexpected(state)) {
                ModeDistances metric = state.modes.getOrDefault(mode, new ModeDistances());
                Map<String, Long> counts = new HashMap<>();
                metric.od.forEach(value -> counts.merge(distanceBin(value), 1L, Long::sum));
                for (String bin : bins) csv.append(state.name).append(',').append(mode).append(',').append(bin).append(',')
                        .append(counts.getOrDefault(bin, 0L)).append(',')
                        .append(number(percent(counts.getOrDefault(bin, 0L), metric.od.size()))).append('\n');
            }
        }
        return csv.toString();
    }

    private static String thresholdsCsv(AuditData data) {
        StringBuilder csv = new StringBuilder("mode,threshold_km,input_count,input_within_mode_share_percent,final_count,final_within_mode_share_percent,absolute_change,percentage_point_change\n");
        for (String mode : List.of("walk", "bike")) {
            ModeDistances input = data.inputState().modes.getOrDefault(mode, new ModeDistances());
            ModeDistances fin = data.finalState().modes.getOrDefault(mode, new ModeDistances());
            for (double threshold : THRESHOLDS_KM.get(mode)) {
                long in = input.od.stream().filter(value -> exceeds(value, threshold)).count();
                long out = fin.od.stream().filter(value -> exceeds(value, threshold)).count();
                double inShare = percent(in, input.od.size());
                double outShare = percent(out, fin.od.size());
                csv.append(mode).append(',').append(number(threshold)).append(',').append(in).append(',')
                        .append(number(inShare)).append(',').append(out).append(',').append(number(outShare)).append(',')
                        .append(out - in).append(',').append(number(outShare - inShare)).append('\n');
            }
        }
        return csv.toString();
    }

    private static String transitionsCsv(AuditData data) {
        StringBuilder csv = new StringBuilder("input_mode,final_mode,matched_trip_count,share_of_final_both_inside_percent,mean_od_km,median_od_km,p90_od_km\n");
        data.transitions().forEach((key, values) -> csv.append(key.inputMode()).append(',').append(key.finalMode()).append(',')
                .append(values.size()).append(',').append(number(percent(values.size(), data.finalBothInside()))).append(',')
                .append(km(mean(values))).append(',').append(km(percentile(values, .5))).append(',')
                .append(km(percentile(values, .9))).append('\n'));
        return csv.toString();
    }

    private static String longActiveCsv(AuditData data) {
        StringBuilder csv = new StringBuilder("final_mode,threshold_km,origin_classification,input_mode,trip_count,share_of_final_threshold_trips_percent\n");
        Map<String, Long> denominators = new HashMap<>();
        data.longActive().forEach((key, count) -> denominators.merge(key.finalMode() + "|" + key.thresholdKm(), count, Long::sum));
        data.longActive().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator
                        .comparing(LongActiveKey::finalMode).thenComparingDouble(LongActiveKey::thresholdKm)
                        .thenComparing(LongActiveKey::originClassification).thenComparing(LongActiveKey::inputMode)))
                .forEach(entry -> {
                    var key = entry.getKey();
                    long denominator = denominators.get(key.finalMode() + "|" + key.thresholdKm());
                    csv.append(key.finalMode()).append(',').append(number(key.thresholdKm())).append(',')
                            .append(key.originClassification()).append(',').append(key.inputMode()).append(',')
                            .append(entry.getValue()).append(',').append(number(percent(entry.getValue(), denominator))).append('\n');
                });
        return csv.toString();
    }

    private static String report(AuditData data, Path inputPlans, Path finalPlans,
            Path standardTrips, long standardTripRecords) {
        StringBuilder md = new StringBuilder("# Literature-based scoring trip-distance audit\n\n")
                .append("## Audit identity and method\n\n")
                .append("This is a read-only diagnostic audit, not calibration or model validation. It compares the unchanged input selected plans (`")
                .append(inputPlans).append("`) with the iteration-10 final selected plans (`").append(finalPlans).append("`). ")
                .append("Trips are identified with MATSim `TripStructureUtils`, stage activities are ignored, and the standard MATSim analysis main mode is used. ")
                .append("The primary scope is **BOTH_INSIDE**: both main-activity coordinates are covered by the Munich municipal boundary.\n\n")
                .append("Trips are matched exactly by person ID, main-trip index, origin and destination activity types, and EPSG:31468 coordinates. ")
                .append("Matched trips: ").append(data.transitions().values().stream().mapToLong(List::size).sum())
                .append("; unmatched input: ").append(data.unmatchedInput()).append("; unmatched final: ").append(data.unmatchedFinal()).append(". ")
                .append("The final BOTH_INSIDE denominator is ").append(data.finalBothInside()).append(" trips.\n\n")
                .append("Euclidean origin-destination (OD) distance is the primary comparison because it is invariant when a matched trip changes mode. Travelled route distance is secondary and mode-dependent; it is reported only when all corresponding MATSim legs contain a finite route distance. No missing distance is imputed.\n\n")
                .append("The walk thresholds (3, 5 and 10 km) and bike thresholds (5, 10 and 20 km) are transparent diagnostic cut-offs, not empirical behavioural limits.\n\n")
                .append("## Evidence\n\n")
                .append(summaryTable(data)).append('\n')
                .append("Unexpected analysis main modes in INPUT: ").append(unexpected(data.inputState()))
                .append("; in FINAL: ").append(unexpected(data.finalState())).append(".\n\n")
                .append("The optional standard output-trips cross-check is ")
                .append(standardTrips == null ? "unavailable" : "available with " + standardTripRecords + " records")
                .append(". The audit's route-distance indicators use selected-plan leg routes and therefore retain their own explicit coverage. Pkm means passenger-trip kilometres where route distance is measurable; car Pkm must not be interpreted as vehicle-kilometres. Reliable car Fkm require a separate event-based vehicle analysis.\n\n")
                .append("## Long active trips: inherited or generated?\n\n")
                .append(longAnswer(data, "walk")).append('\n').append(longAnswer(data, "bike")).append('\n')
                .append(materialChangeAnswer(data, "walk")).append('\n')
                .append(materialChangeAnswer(data, "bike")).append('\n')
                .append("The transition and threshold CSV files provide the complete evidence. OD-tail changes cannot be caused by route-distance measurement differences; route-distance comparisons can be affected by coverage and routing.\n\n")
                .append("## Interpretation and decision before ASC calibration\n\n")
                .append(decision(data)).append(" The audit does not prove a behavioural cause by itself: generated long active trips can reflect mode-choice scoring and the unrestricted choice set together, whereas inherited cases point to the input plans. A maximum-distance choice-set rule would add a new behavioural assumption and should not be introduced solely from these diagnostic thresholds.\n\n")
                .append("## Files\n\n")
                .append("The distribution, bins, thresholds, transitions and long-active-origin CSV files state every denominator and route-distance coverage value used here.\n");
        return md.toString();
    }

    private static String summaryTable(AuditData data) {
        StringBuilder table = new StringBuilder("| State | Mode | Trips | Mean OD km | P90 OD km | Max OD km | Travelled-distance coverage |\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (MutableState state : List.of(data.inputState(), data.finalState())) for (String mode : MODES) {
            ModeDistances metric = state.modes.getOrDefault(mode, new ModeDistances());
            table.append('|').append(state.name).append('|').append(mode).append('|').append(metric.od.size()).append('|')
                    .append(km(mean(metric.od))).append('|').append(km(percentile(metric.od, .9))).append('|')
                    .append(km(max(metric.od))).append('|').append(number(percent(metric.route.size(), metric.od.size()))).append("%|\n");
        }
        return table.toString();
    }

    private static String longAnswer(AuditData data, String mode) {
        double threshold = THRESHOLDS_KM.get(mode)[0];
        long inherited = originCount(data, mode, threshold, "INHERITED");
        long generated = originCount(data, mode, threshold, "GENERATED");
        long uncertain = originCount(data, mode, threshold, "UNMATCHED_OR_UNCERTAIN");
        String majority = inherited > generated ? "mostly inherited from the input"
                : generated > inherited ? "mostly generated through mode changes" : "evenly split between inherited and generated cases";
        return "For final **" + mode + " trips over " + number(threshold) + " km**, " + inherited
                + " are inherited, " + generated + " are generated, and " + uncertain + " are unmatched or uncertain. They are " + majority + ".";
    }

    private static String materialChangeAnswer(AuditData data, String mode) {
        ModeDistances input = data.inputState().modes.getOrDefault(mode, new ModeDistances());
        ModeDistances fin = data.finalState().modes.getOrDefault(mode, new ModeDistances());
        double meanInput = mean(input.od);
        double meanFinal = mean(fin.od);
        double p90Input = percentile(input.od, .9);
        double p90Final = percentile(fin.od, .9);
        double threshold = THRESHOLDS_KM.get(mode)[0];
        double thresholdInput = percent(input.od.stream().filter(value -> exceeds(value, threshold)).count(), input.od.size());
        double thresholdFinal = percent(fin.od.stream().filter(value -> exceeds(value, threshold)).count(), fin.od.size());
        boolean distributionShift = (relativeChange(meanInput, meanFinal) >= 10
                || relativeChange(p90Input, p90Final) >= 10)
                && Math.abs(thresholdFinal - thresholdInput) >= 1.0;
        return "For **" + mode + "**, mean OD distance changed from " + km(meanInput) + " to "
                + km(meanFinal) + " km, p90 from " + km(p90Input) + " to " + km(p90Final)
                + " km, and the share above " + number(threshold) + " km from "
                + number(thresholdInput) + "% to " + number(thresholdFinal) + "%. Under the audit-only rule "
                + "(at least a 10% mean or p90 change together with at least a one-percentage-point threshold-share change), this is "
                + (distributionShift ? "a material distribution change" : "not a material distribution change")
                + ". This rule is descriptive, not a behavioural limit.";
    }

    private static String decision(AuditData data) {
        long uncertain = data.longActive().entrySet().stream()
                .filter(entry -> entry.getKey().originClassification().equals("UNMATCHED_OR_UNCERTAIN"))
                .mapToLong(Map.Entry::getValue).sum();
        if (uncertain > 0) return "Further investigation is required before ASC calibration because long active trips remain unmatched.";
        long generated = data.longActive().entrySet().stream()
                .filter(entry -> entry.getKey().originClassification().equals("GENERATED"))
                .mapToLong(Map.Entry::getValue).sum();
        long inherited = data.longActive().entrySet().stream()
                .filter(entry -> entry.getKey().originClassification().equals("INHERITED"))
                .mapToLong(Map.Entry::getValue).sum();
        if (generated > inherited) return "The model is not yet ready for ASC-only calibration; mode-specific time/distance scoring should be reviewed first because generated cases dominate the long-active thresholds.";
        if (inherited > generated) return "The model is not yet ready for ASC-only calibration; the inherited input distance tail should be investigated before changing scoring.";
        return "The evidence does not support a unique next correction; further investigation is required before ASC calibration.";
    }

    private static long originCount(AuditData data, String mode, double threshold, String classification) {
        return data.longActive().entrySet().stream()
                .filter(entry -> entry.getKey().finalMode().equals(mode)
                        && entry.getKey().thresholdKm() == threshold
                        && entry.getKey().originClassification().equals(classification))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static String unexpected(MutableState state) {
        Map<String, Integer> counts = new TreeMap<>();
        state.modes.forEach((mode, values) -> {
            if (!MODES.contains(mode)) counts.put(mode, values.od.size());
        });
        return counts.isEmpty() ? "none" : counts.toString();
    }

    private static void reconcileRun07(Path summary, AuditData data) throws IOException {
        Map<String, Long> expected = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(summary, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            require(header != null && header.startsWith("mode,"), "Unexpected Run 07 mode-summary header: " + header);
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                if (fields.length >= 2) expected.put(fields[0], Long.parseLong(fields[1]));
            }
        }
        for (String mode : MODES) {
            long actual = data.finalState().modes.getOrDefault(mode, new ModeDistances()).od.size();
            require(expected.containsKey(mode) && expected.get(mode) == actual,
                    "Run 07 final mode count differs for " + mode + ": expected="
                            + expected.get(mode) + ", audit=" + actual);
        }
    }

    static void requireOutputAbsent(Path output) {
        require(!Files.exists(output), "Distance audit already exists and will not be overwritten: " + output);
    }

    private static void publishAtomically(Map<String, String> reports) throws IOException {
        requireOutputAbsent(AUDIT);
        Path parent = AUDIT.getParent();
        require(Files.isDirectory(parent), "Run 07 analysis directory is missing: " + parent);
        Path temporary = parent.resolve(".distance-audit-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            for (var report : reports.entrySet()) Files.writeString(temporary.resolve(report.getKey()), report.getValue(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, AUDIT, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, AUDIT);
            }
        } catch (IOException | RuntimeException exception) {
            if (Files.exists(temporary)) try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
            throw exception;
        }
    }

    private static long countDataRows(Path file) throws IOException {
        try (BufferedReader reader = reader(file)) {
            long rows = -1;
            while (reader.readLine() != null) rows++;
            return Math.max(0, rows);
        }
    }

    private static BufferedReader reader(Path file) throws IOException {
        InputStream input = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) input = new GZIPInputStream(input);
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static List<String> modesWithUnexpected(MutableState state) {
        List<String> result = new ArrayList<>(MODES);
        state.modes.keySet().stream().filter(mode -> !MODES.contains(mode)).sorted().forEach(result::add);
        return result;
    }

    private static double mean(List<Double> values) { return values.isEmpty() ? Double.NaN : sum(values) / values.size(); }
    private static double sum(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).sum(); }
    private static double max(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN); }
    private static double percent(double numerator, double denominator) { return denominator == 0 ? 0 : numerator / denominator * 100.0; }
    private static double relativeChange(double input, double output) {
        return input == 0 ? (output == 0 ? 0 : Double.POSITIVE_INFINITY)
                : Math.abs(output - input) / Math.abs(input) * 100.0;
    }
    private static String km(double metres) { return Double.isFinite(metres) ? number(metres / 1000.0) : ""; }
    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : ""; }
    private static boolean same(double left, double right) {
        if (Double.isNaN(left) && Double.isNaN(right)) return true;
        return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= COORDINATE_EPSILON;
    }
    private static boolean valid(Coord coord) { return coord != null && Double.isFinite(coord.getX()) && Double.isFinite(coord.getY()); }
    private static Path required(Path path, String label) { require(Files.isRegularFile(path), "Missing " + label + ": " + path); return path; }
    private static Path optional(Path path) { return Files.isRegularFile(path) ? path : null; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @FunctionalInterface interface TripConsumer { void accept(String personId, int index, TripObservation observation); }
    record TripKey(String personId, int tripIndex) { }
    record TransitionKey(String inputMode, String finalMode) { }
    record LongActiveKey(String finalMode, double thresholdKm, String inputMode, String originClassification) { }
    record TripObservation(String mode, MunichTripBoundaryFilter.SpatialCategory category,
            String originType, String destinationType, double originX, double originY,
            double destinationX, double destinationY, double odMetres, double routeMetres) {
        String identity() { return originType + "@" + originX + "/" + originY + "->" + destinationType + "@" + destinationX + "/" + destinationY; }
    }
    static final class ModeDistances { final List<Double> od = new ArrayList<>(); final List<Double> route = new ArrayList<>(); }
    static final class MutableState {
        final String name; final Map<String, ModeDistances> modes = new HashMap<>(); long bothInside;
        MutableState(String name) { this.name = name; }
        void add(TripObservation observation) {
            if (observation.category() != MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) return;
            require(Double.isFinite(observation.odMetres()) && observation.odMetres() >= 0, "Invalid BOTH_INSIDE OD distance");
            bothInside++;
            ModeDistances metric = modes.computeIfAbsent(observation.mode(), ignored -> new ModeDistances());
            metric.od.add(observation.odMetres());
            if (Double.isFinite(observation.routeMetres()) && observation.routeMetres() >= 0) metric.route.add(observation.routeMetres());
        }
    }
    record AuditData(long inputPersons, long finalPersons, long inputTrips, long finalTrips,
            long finalBothInside, long unmatchedInput, long unmatchedFinal,
            MutableState inputState, MutableState finalState,
            Map<TransitionKey, List<Double>> transitions, Map<LongActiveKey, Long> longActive) { }
}
