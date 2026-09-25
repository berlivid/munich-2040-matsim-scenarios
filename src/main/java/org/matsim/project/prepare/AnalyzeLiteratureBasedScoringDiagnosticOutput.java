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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Read-only, fixed-path analysis of the completed literature-based scoring run.
 * It never starts a controller and publishes reports only after every validation
 * and large-file pass has completed successfully.
 */
public final class AnalyzeLiteratureBasedScoringDiagnosticOutput {
    static final Path OUTPUT = ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT;
    static final Path ANALYSIS = OUTPUT.resolve("analysis");
    static final String RUN_ID = ValidateLiteratureBasedScoringDiagnosticConfig.RUN_ID;
    static final int EXPECTED_PERSONS = 324_043;
    static final long EXPECTED_TRIPS = 540_468;
    static final long EXPECTED_BOTH_INSIDE = 160_603;
    static final double EXPANSION_FACTOR = 20.0;
    static final double QSIM_END = 48 * 3600.0;
    static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    static final Map<String, Double> TARGETS = Map.of(
            "car", 34.0, "pt", 24.0, "bike", 18.0, "walk", 24.0);
    private static final double EPSILON = 1e-6;

    private AnalyzeLiteratureBasedScoringDiagnosticOutput() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The analyzer accepts no arguments");
        OutputFiles files = validateOutput(OUTPUT);
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(
                MunichMunicipalBoundary.loadDefault());

        TripAnalysis plans = analyzePlans(files.plans(), filter);
        validateStructuralTotals(plans);
        TripAnalysis measurements = analyzeTripsCsv(files.trips(), filter);
        List<IterationShare> iterations = readIterationShares(files.modeStats());
        StuckSummary stuck = readStuckEvents(files.events(), plans.bothInsidePersons());

        Map<String, String> reports = buildReports(files, plans, measurements,
                iterations, stuck);
        publishAtomically(OUTPUT, reports);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING DIAGNOSTIC ANALYSIS PASS%n"
                        + "persons=%d trips=%d BOTH_INSIDE=%d%n"
                        + "analysis=%s%nNo Controller or QSim was started.%n",
                plans.personCount(), plans.totalTrips(),
                plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE),
                ANALYSIS);
    }

    static OutputFiles validateOutput(Path output) throws Exception {
        require(Files.isDirectory(output), "Missing diagnostic output directory: " + output);
        require(!Files.exists(output.resolve("analysis")),
                "Analysis already exists and will not be overwritten: "
                        + output.resolve("analysis"));

        Path log = required(output.resolve(RUN_ID + ".logfile.log"), "normal-shutdown log");
        String logTail = Files.readString(log, StandardCharsets.UTF_8);
        require(logTail.contains("shutdown completed."),
                "The diagnostic log contains no normal MATSim shutdown evidence: " + log);

        Path outputConfig = required(output.resolve(RUN_ID + ".output_config.xml"),
                "output config");
        Config expected = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate(false);
        // The productive runner installs SwissRailRaptor before Controller writes
        // output_config.xml; MATSim consequently serializes its default config
        // module even though the versioned input config needs no explicit module.
        expected.addModule(new SwissRailRaptorConfigGroup());
        Config actual = ConfigUtils.loadConfig(outputConfig.toString());
        List<String> differences = semanticConfigDifferences(expected, actual);
        require(differences.isEmpty(), "Output config differs semantically from the versioned "
                + "diagnostic config:\n- " + String.join("\n- ", differences));

        Path trips = required(output.resolve(RUN_ID + ".output_trips.csv.gz"),
                "standard output trips");
        Path plans = required(output.resolve(RUN_ID + ".output_plans.xml.gz"),
                "final output plans");
        Path events = optional(output.resolve(RUN_ID + ".output_events.xml.gz"));
        if (events == null) {
            events = optional(output.resolve("ITERS/it.10/" + RUN_ID + ".10.events.xml.gz"));
        }
        require(events != null, "No readable final events file exists in " + output);
        Path modeStats = optional(output.resolve(RUN_ID + ".modestats.csv"));
        if (modeStats != null) readIterationShares(modeStats);
        return new OutputFiles(outputConfig, trips, plans, events, modeStats);
    }

    static List<String> semanticConfigDifferences(Config expected, Config actual) {
        Map<String, String> expectedModules = canonicalModules(expected);
        Map<String, String> actualModules = canonicalModules(actual);
        Set<String> keys = new java.util.TreeSet<>(expectedModules.keySet());
        keys.addAll(actualModules.keySet());
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            String left = expectedModules.get(key);
            String right = actualModules.get(key);
            if (!java.util.Objects.equals(left, right)) {
                differences.add(key + ": expected=" + printable(left)
                        + ", actual=" + printable(right));
            }
        }
        return differences;
    }

    private static Map<String, String> canonicalModules(Config config) {
        Map<String, String> result = new TreeMap<>();
        config.getModules().forEach((name, group) -> result.put(name, canonical(group)));
        return result;
    }

    private static String canonical(ConfigGroup group) {
        StringBuilder value = new StringBuilder(group.getName())
                .append(new TreeMap<>(group.getParams()));
        group.getParameterSets().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<String> children = entry.getValue().stream()
                            .map(AnalyzeLiteratureBasedScoringDiagnosticOutput::canonical)
                            .sorted().toList();
                    value.append('|').append(entry.getKey()).append(children);
                });
        return value.toString();
    }

    static TripAnalysis analyzeTripsCsv(Path file, MunichTripBoundaryFilter filter)
            throws IOException {
        MutableTripAnalysis result = new MutableTripAnalysis("STANDARD_OUTPUT_TRIPS_TRAVELED_DISTANCE");
        try (BufferedReader reader = reader(file)) {
            String first = reader.readLine();
            require(first != null, "Output trips file is empty: " + file);
            char delimiter = delimiter(first);
            List<String> header = parseCsvLine(first, delimiter);
            Map<String, Integer> columns = columns(header);
            int person = column(columns, "person", "person_id", "personId");
            int mode = column(columns, "main_mode", "mainMode");
            int startX = column(columns, "start_x", "origin_x", "from_x");
            int startY = column(columns, "start_y", "origin_y", "from_y");
            int endX = column(columns, "end_x", "destination_x", "to_x");
            int endY = column(columns, "end_y", "destination_y", "to_y");
            int distance = column(columns, "traveled_distance", "travelled_distance", "distance");
            int travelTime = column(columns, "trav_time", "travel_time");
            String line;
            long row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line, delimiter);
                try {
                    String personId = requiredValue(values, person, "person", row);
                    String mainMode = requiredValue(values, mode, "main_mode", row)
                            .trim().toLowerCase(Locale.ROOT);
                    Coord origin = coordinate(values, startX, startY);
                    Coord destination = coordinate(values, endX, endY);
                    double metres = finiteNonNegative(values, distance, "traveled_distance", row);
                    double seconds = parseTime(requiredValue(values, travelTime,
                            "trav_time", row));
                    require(Double.isFinite(seconds) && seconds >= 0,
                            "Invalid travel time at output-trips row " + row);
                    result.add(personId, filter.classify(origin, destination), mainMode,
                            metres, seconds);
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Invalid output-trips row " + row
                            + ": " + exception.getMessage(), exception);
                }
            }
        }
        return result.freeze();
    }

    static TripAnalysis analyzePlans(Path file, MunichTripBoundaryFilter filter) {
        require(file != null && Files.isRegularFile(file),
                "Missing final output plans for fallback analysis: " + file);
        MutableTripAnalysis result = new MutableTripAnalysis("FINAL_SELECTED_PLAN_STRUCTURE");
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            Plan plan = person.getSelectedPlan();
            require(plan != null, "Person has no selected final plan: " + person.getId());
            result.persons.add(person.getId().toString());
            List<TripStructureUtils.Trip> mainTrips = TripStructureUtils.getTrips(plan);
            List<MunichTripBoundaryFilter.ClassifiedTrip> classified = filter.classify(plan);
            require(mainTrips.size() == classified.size(),
                    "Stage-aware trip mismatch for person " + person.getId());
            for (int index = 0; index < mainTrips.size(); index++) {
                var trip = classified.get(index);
                result.add(person.getId().toString(), trip.category(), trip.inputMainMode(),
                        0, 0);
            }
        });
        reader.readFile(file.toString());
        return result.freeze();
    }

    static void validateStructuralTotals(TripAnalysis trips) {
        require(trips.personCount() == EXPECTED_PERSONS,
                "Unexpected person count: expected " + EXPECTED_PERSONS
                        + ", actual " + trips.personCount());
        require(trips.totalTrips() == EXPECTED_TRIPS,
                "Unexpected main-trip count: expected " + EXPECTED_TRIPS
                        + ", actual " + trips.totalTrips());
        long bothInside = trips.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        require(bothInside == EXPECTED_BOTH_INSIDE,
                "Unexpected BOTH_INSIDE count: expected " + EXPECTED_BOTH_INSIDE
                        + ", actual " + bothInside);
        long invalid = trips.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE);
        require(invalid == 0, "Expected zero invalid trip coordinates, actual " + invalid);
        require(trips.scopeCounts().values().stream().mapToLong(Long::longValue).sum()
                        == trips.totalTrips(),
                "Spatial categories do not sum to all main trips");
    }

    static List<IterationShare> readIterationShares(Path file) throws IOException {
        if (file == null) return List.of();
        List<IterationShare> result = new ArrayList<>();
        try (BufferedReader reader = reader(file)) {
            String first = reader.readLine();
            require(first != null, "Mode-share history is empty: " + file);
            char delimiter = delimiter(first);
            Map<String, Integer> header = columns(parseCsvLine(first, delimiter));
            int iteration = column(header, "iteration");
            Map<String, Integer> modes = new LinkedHashMap<>();
            for (String mode : MODES) modes.put(mode, column(header, mode));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsvLine(line, delimiter);
                int it = Integer.parseInt(values.get(iteration));
                Map<String, Double> shares = new LinkedHashMap<>();
                for (String mode : MODES) {
                    double raw = Double.parseDouble(values.get(modes.get(mode)));
                    shares.put(mode, raw <= 1.0 + EPSILON ? raw * 100.0 : raw);
                }
                result.add(new IterationShare(it, Map.copyOf(shares)));
            }
        }
        result.sort(Comparator.comparingInt(IterationShare::iteration));
        require(result.size() == 11, "Standard mode history must contain iterations 0..10");
        for (int iteration = 0; iteration <= 10; iteration++) {
            require(result.get(iteration).iteration() == iteration,
                    "Missing or duplicated standard mode-history iteration " + iteration);
        }
        return List.copyOf(result);
    }

    static StuckSummary readStuckEvents(Path file, Set<String> bothInsidePersons) {
        require(file != null && Files.isRegularFile(file), "Missing events file: " + file);
        MutableStuckSummary summary = new MutableStuckSummary(bothInsidePersons);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler((PersonStuckEventHandler) summary::accept);
        new MatsimEventsReader(manager).readFile(file.toString());
        return summary.freeze();
    }

    static Map<String, String> buildReports(OutputFiles files, TripAnalysis plans,
            TripAnalysis measurements, List<IterationShare> iterations,
            StuckSummary stuck) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("literature_based_scoring_scope_summary.csv",
                scopeCsv(plans, measurements));
        result.put("literature_based_scoring_final_mode_summary.csv",
                modeCsv(plans, measurements));
        result.put("literature_based_scoring_iteration_mode_shares.csv",
                iterationCsv(iterations));
        result.put("literature_based_scoring_stuck_events.csv", stuckCsv(stuck));
        result.put("literature_based_scoring_diagnostic_report.md",
                report(files, plans, measurements, iterations, stuck));
        return result;
    }

    static void publishAtomically(Path output, Map<String, String> reports)
            throws IOException {
        Path destination = output.resolve("analysis");
        require(!Files.exists(destination),
                "Analysis already exists and will not be overwritten: " + destination);
        Path temporary = output.resolve(".analysis-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            for (var report : reports.entrySet()) {
                require(!report.getKey().contains("/") && !report.getKey().contains("\\"),
                        "Invalid analysis filename: " + report.getKey());
                Files.writeString(temporary.resolve(report.getKey()), report.getValue(),
                        StandardCharsets.UTF_8);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } catch (IOException | RuntimeException exception) {
            deleteTemporaryTree(temporary);
            throw exception;
        }
    }

    private static String scopeCsv(TripAnalysis plans, TripAnalysis measurements) {
        StringBuilder csv = new StringBuilder("spatial_category,plan_trip_count,"
                + "share_of_all_plan_trips_percent,measurement_record_count,"
                + "measurement_coverage_percent,measurement_gap_records\n");
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            long planCount = plans.scopeCounts().get(category);
            long measuredCount = measurements.scopeCounts().get(category);
            csv.append(category).append(',').append(planCount).append(',')
                    .append(number(percent(planCount, plans.totalTrips()))).append(',')
                    .append(measuredCount).append(',')
                    .append(number(percent(measuredCount, planCount))).append(',')
                    .append(planCount - measuredCount).append('\n');
        }
        csv.append("ALL,").append(plans.totalTrips()).append(",100.000000000,")
                .append(measurements.totalTrips()).append(',')
                .append(number(percent(measurements.totalTrips(), plans.totalTrips())))
                .append(',').append(plans.totalTrips() - measurements.totalTrips())
                .append('\n');
        return csv.toString();
    }

    private static String modeCsv(TripAnalysis plans, TripAnalysis measurements) {
        long scopeTrips = plans.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        double scopeMetres = measurements.modeMetrics().values().stream()
                .mapToDouble(ModeMetric::distanceMetres).sum();
        StringBuilder csv = new StringBuilder("mode,plan_trip_count,trip_share_percent,"
                + "target_trip_share_percent,deviation_percentage_points,"
                + "measurement_record_count,measurement_coverage_percent,"
                + "measurement_gap_records,sample_pkm,pkm_share_percent,"
                + "expanded_daily_pkm_factor_20,mean_trip_distance_km,"
                + "mean_travel_time_minutes\n");
        Set<String> rows = new java.util.LinkedHashSet<>(MODES);
        rows.addAll(new java.util.TreeSet<>(plans.unexpectedModes().keySet()));
        rows.addAll(new java.util.TreeSet<>(measurements.unexpectedModes().keySet()));
        for (String mode : rows) {
            ModeMetric planMetric = plans.modeMetrics().getOrDefault(mode, ModeMetric.ZERO);
            ModeMetric measuredMetric = measurements.modeMetrics()
                    .getOrDefault(mode, ModeMetric.ZERO);
            Double target = TARGETS.get(mode);
            double tripShare = percent(planMetric.trips(), scopeTrips);
            csv.append(mode).append(',').append(planMetric.trips()).append(',')
                    .append(number(tripShare)).append(',')
                    .append(target == null ? "" : number(target)).append(',')
                    .append(target == null ? "" : number(tripShare - target)).append(',')
                    .append(measuredMetric.trips()).append(',')
                    .append(number(percent(measuredMetric.trips(), planMetric.trips())))
                    .append(',').append(planMetric.trips() - measuredMetric.trips())
                    .append(',').append(number(measuredMetric.distanceMetres() / 1000.0))
                    .append(',').append(number(percent(measuredMetric.distanceMetres(),
                            scopeMetres))).append(',')
                    .append(number(measuredMetric.distanceMetres() / 1000.0
                            * EXPANSION_FACTOR)).append(',')
                    .append(number(measuredMetric.trips() == 0 ? 0
                            : measuredMetric.distanceMetres() / measuredMetric.trips()
                            / 1000.0)).append(',')
                    .append(number(measuredMetric.trips() == 0 ? 0
                            : measuredMetric.travelTimeSeconds() / measuredMetric.trips()
                            / 60.0)).append('\n');
        }
        return csv.toString();
    }

    private static String iterationCsv(List<IterationShare> iterations) {
        StringBuilder csv = new StringBuilder("iteration,scope,car_share_percent,"
                + "pt_share_percent,bike_share_percent,walk_share_percent,source,note\n");
        if (iterations.isEmpty()) {
            csv.append(",UNAVAILABLE,,,,,NO_STANDARD_MODE_SHARE_HISTORY,")
                    .append("Exact BOTH_INSIDE iteration shares cannot be reconstructed\n");
        } else {
            for (IterationShare row : iterations) {
                csv.append(row.iteration()).append(",WHOLE_SIMULATED_POPULATION,")
                        .append(number(row.shares().get("car"))).append(',')
                        .append(number(row.shares().get("pt"))).append(',')
                        .append(number(row.shares().get("bike"))).append(',')
                        .append(number(row.shares().get("walk"))).append(',')
                        .append("STANDARD_MATSIM_MODESTATS,")
                        .append("Not a Munich BOTH_INSIDE calibration value\n");
            }
        }
        return csv.toString();
    }

    private static String stuckCsv(StuckSummary stuck) {
        StringBuilder csv = new StringBuilder("aggregation,key,event_count,unique_person_count\n");
        csv.append("TOTAL,ALL,").append(stuck.totalEvents()).append(',')
                .append(stuck.uniquePersons()).append('\n');
        appendCounts(csv, "MODE", stuck.byMode());
        appendCounts(csv, "HOUR", stuck.byHour());
        appendCounts(csv, "EVENT_TIME_SECONDS", stuck.byExactTime());
        csv.append("BOUNDARY,EXACTLY_48H,").append(stuck.exactlyAt48Hours()).append(",\n")
                .append("BOUNDARY,FINAL_HOUR_47_TO_48,")
                .append(stuck.inFinalHour()).append(",\n")
                .append("PERSON_SCOPE,HAS_AT_LEAST_ONE_BOTH_INSIDE_TRIP,,")
                .append(stuck.uniquePersonsWithBothInsideTrip()).append('\n');
        return csv.toString();
    }

    private static void appendCounts(StringBuilder csv, String aggregation,
            Map<?, Long> counts) {
        counts.forEach((key, value) -> csv.append(aggregation).append(',').append(key)
                .append(',').append(value).append(",\n"));
    }

    private static String report(OutputFiles files, TripAnalysis plans,
            TripAnalysis measurements, List<IterationShare> iterations,
            StuckSummary stuck) {
        long bothInside = plans.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        long measuredBothInside = measurements.scopeCounts().get(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        long recordDifference = plans.totalTrips() - measurements.totalTrips();
        double recordDifferencePercent = percent(recordDifference, plans.totalTrips());
        StringBuilder markdown = new StringBuilder("# Literature-based scoring diagnostic results\n\n")
                .append("## Run and validation\n\n")
                .append("The protected run `").append(RUN_ID)
                .append("` completed normally for iterations 0–10. Its output configuration is "
                        + "semantically identical to the versioned diagnostic configuration. The "
                        + "protected 2019 inputs passed their byte-hash checks. This analyzer did "
                        + "not start MATSim, Controller or QSim.\n\n")
                .append("## Scope and definitions\n\n")
                .append("The primary scope is **BOTH_INSIDE**: both trip endpoints are covered by "
                        + "the City of Munich municipal boundary; boundary points count as inside. "
                        + "MATSim stage legs are represented by the standard analysis main mode and "
                        + "are not counted as separate trips. The regional population remains in "
                        + "the simulation but does not enter the primary summary.\n\n")
                .append("The authoritative final selected plans contain ")
                .append(plans.personCount()).append(" persons, ")
                .append(plans.totalTrips()).append(" main trips and ").append(bothInside)
                .append(" BOTH_INSIDE trips. These complete plan counts define the spatial and "
                        + "trip-based modal-split denominators. There is no evidence of population "
                        + "loss or truncated selected plans.\n\n")
                .append("The standard `output_trips` writer provides ")
                .append(measurements.totalTrips()).append(" measurement records, ")
                .append(recordDifference).append(" fewer than the selected plans. Overall distance-"
                        + "and-time coverage is ")
                .append(number(percent(measurements.totalTrips(), plans.totalTrips())))
                .append("%; the retained difference is ").append(number(recordDifferencePercent))
                .append("% (").append(String.format(Locale.ROOT, "%.3f", recordDifferencePercent))
                .append("% when rounded to three decimal places). Within BOTH_INSIDE, ")
                .append(measuredBothInside).append(" of ").append(bothInside)
                .append(" plan trips have standard measurement records. No distance or travel time "
                        + "is imputed for absent records. Passenger-kilometres are sums of travelled "
                        + "distance across the available standard records only. The factor-20 "
                        + "value expands the five-percent sample to one model day; it is not an annual "
                        + "estimate. Car passenger-kilometres are not vehicle-kilometres. Reliable car "
                        + "Fkm require a separate event-based vehicle analysis.\n\n")
                .append("## Final BOTH_INSIDE result\n\n")
                .append("| Mode | Plan trips | Share | Target | Difference | Measured records | "
                        + "Coverage | Record gap | Sample Pkm | Pkm share | Mean km | "
                        + "Mean minutes |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        double totalMetres = measurements.modeMetrics().values().stream()
                .mapToDouble(ModeMetric::distanceMetres).sum();
        for (String mode : MODES) {
            ModeMetric planMetric = plans.modeMetrics().getOrDefault(mode, ModeMetric.ZERO);
            ModeMetric measuredMetric = measurements.modeMetrics()
                    .getOrDefault(mode, ModeMetric.ZERO);
            double share = percent(planMetric.trips(), bothInside);
            markdown.append('|').append(mode).append('|').append(planMetric.trips()).append('|')
                    .append(number(share)).append("%|").append(number(TARGETS.get(mode)))
                    .append("%|").append(number(share - TARGETS.get(mode))).append(" pp|")
                    .append(measuredMetric.trips()).append('|')
                    .append(number(percent(measuredMetric.trips(), planMetric.trips())))
                    .append("%|").append(planMetric.trips() - measuredMetric.trips())
                    .append('|').append(number(measuredMetric.distanceMetres() / 1000.0))
                    .append('|').append(number(percent(measuredMetric.distanceMetres(),
                            totalMetres))).append("%|")
                    .append(number(measuredMetric.trips() == 0 ? 0
                            : measuredMetric.distanceMetres() / measuredMetric.trips()
                            / 1000.0)).append('|')
                    .append(number(measuredMetric.trips() == 0 ? 0
                            : measuredMetric.travelTimeSeconds() / measuredMetric.trips()
                            / 60.0)).append("|\n");
        }
        markdown.append("\nPkm shares are validation outcomes; alternative-specific constants "
                        + "do not directly force them. Mode-specific record gaps are aggregate "
                        + "plan-versus-writer differences, not imputed trip matches. Unexpected "
                        + "main modes in final plans: ")
                .append(plans.unexpectedModes().isEmpty() ? "none" : plans.unexpectedModes())
                .append(".\n\n## Iteration evidence\n\n");
        if (iterations.isEmpty()) {
            markdown.append("No standard MATSim mode-share history was available. Exact Munich "
                    + "BOTH_INSIDE shares cannot be reconstructed for each iteration from the "
                    + "preserved standard outputs; no values were interpolated.\n\n");
        } else {
            markdown.append("The standard MATSim mode-share history covers iterations 0–10, but "
                    + "it describes the whole simulated population. It is exported for technical "
                    + "context only and must not be presented as the Munich calibration series. "
                    + "Exact BOTH_INSIDE iteration shares are unavailable and were not invented.\n\n");
        }
        markdown.append("## Stuck-event observations\n\n")
                .append("The streamed events contain ").append(stuck.totalEvents())
                .append(" PersonStuckEvents affecting ").append(stuck.uniquePersons())
                .append(" unique persons. ").append(stuck.exactlyAt48Hours())
                .append(" events occur exactly at 48:00:00 and ").append(stuck.inFinalHour())
                .append(" occur from hour 47 through the 48-hour boundary. ")
                .append(stuck.uniquePersonsWithBothInsideTrip())
                .append(" affected persons have at least one BOTH_INSIDE trip. These are observations, "
                        + "not automatic causal classifications.\n\n")
                .append("## Assessment and limitations\n\n")
                .append("The short run diagnoses the literature-based starting vector; it is not a "
                        + "calibrated model. Its primary test is whether the final BOTH_INSIDE modal "
                        + "split provides a defensible starting point for ASC calibration with Walk "
                        + "fixed as reference. Limitations include the five-percent synthetic sample, "
                        + "the territorial rather than residence-based scope, unavailable exact "
                        + "BOTH_INSIDE iteration histories, the ")
                .append(recordDifference)
                .append("-record standard-writer coverage difference, and the lack of "
                        + "vehicle-kilometre analysis.\n\n")
                .append("**Proceeding status:** ")
                .append(plans.unexpectedModes().isEmpty() && stuck.totalEvents() == 0
                        ? "SUITABLE_FOR_ASC_CALIBRATION_REVIEW"
                        : "REVIEW_REQUIRED_BEFORE_ASC_CALIBRATION")
                .append(". The result must be inspected before the first ASC-calibration round.\n");
        return markdown.toString();
    }

    private static void deleteTemporaryTree(Path path) {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try { Files.deleteIfExists(candidate); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static Path required(Path path, String label) {
        require(Files.isRegularFile(path), "Missing " + label + ": " + path);
        return path;
    }

    private static Path optional(Path path) {
        return Files.isRegularFile(path) ? path : null;
    }

    private static BufferedReader reader(Path file) throws IOException {
        InputStream input = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) input = new GZIPInputStream(input);
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static char delimiter(String header) {
        return header.chars().filter(c -> c == ';').count()
                >= header.chars().filter(c -> c == ',').count() ? ';' : ',';
    }

    static List<String> parseCsvLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == delimiter && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        require(!quoted, "Unclosed CSV quote");
        values.add(value.toString());
        return values;
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            result.put(header.get(index).strip().toLowerCase(Locale.ROOT), index);
        }
        return result;
    }

    private static int column(Map<String, Integer> columns, String... alternatives) {
        for (String alternative : alternatives) {
            Integer index = columns.get(alternative.toLowerCase(Locale.ROOT));
            if (index != null) return index;
        }
        throw new IllegalStateException("Missing required CSV column; accepted names="
                + List.of(alternatives));
    }

    private static String requiredValue(List<String> values, int index, String name, long row) {
        require(index < values.size() && !values.get(index).isBlank(),
                "Missing " + name + " at row " + row);
        return values.get(index);
    }

    private static Coord coordinate(List<String> values, int x, int y) {
        if (x >= values.size() || y >= values.size()
                || values.get(x).isBlank() || values.get(y).isBlank()) return null;
        try {
            double east = Double.parseDouble(values.get(x));
            double north = Double.parseDouble(values.get(y));
            return Double.isFinite(east) && Double.isFinite(north)
                    ? new Coord(east, north) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double finiteNonNegative(List<String> values, int index,
            String name, long row) {
        double value = Double.parseDouble(requiredValue(values, index, name, row));
        require(Double.isFinite(value) && value >= 0,
                "Invalid " + name + " at row " + row);
        return value;
    }

    static double parseTime(String value) {
        String stripped = value.strip();
        if (!stripped.contains(":")) return Double.parseDouble(stripped);
        String[] parts = stripped.split(":", -1);
        require(parts.length == 3, "Invalid MATSim time: " + value);
        return Duration.ofHours(Long.parseLong(parts[0]))
                .plusMinutes(Long.parseLong(parts[1]))
                .plusMillis(Math.round(Double.parseDouble(parts[2]) * 1000.0))
                .toMillis() / 1000.0;
    }

    private static double percent(double part, double total) {
        return total == 0 ? 0 : part / total * 100.0;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String printable(String value) {
        if (value == null) return "<missing>";
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record OutputFiles(Path config, Path trips, Path plans, Path events, Path modeStats) { }

    record ModeMetric(long trips, double distanceMetres, double travelTimeSeconds) {
        static final ModeMetric ZERO = new ModeMetric(0, 0, 0);
    }

    record TripAnalysis(int personCount, long totalTrips,
                        Map<MunichTripBoundaryFilter.SpatialCategory, Long> scopeCounts,
                        Map<String, ModeMetric> modeMetrics,
                        Map<String, Long> unexpectedModes,
                        Set<String> bothInsidePersons, String distanceSource) { }

    record IterationShare(int iteration, Map<String, Double> shares) { }

    record StuckSummary(long totalEvents, int uniquePersons, Map<String, Long> byMode,
                        Map<Integer, Long> byHour, Map<Double, Long> byExactTime,
                        long exactlyAt48Hours, long inFinalHour,
                        int uniquePersonsWithBothInsideTrip) { }

    private static final class MutableTripAnalysis {
        private final String distanceSource;
        private final Set<String> persons = new HashSet<>();
        private final Set<String> bothInsidePersons = new HashSet<>();
        private final EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> scopes =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        private final Map<String, MutableMetric> modes = new TreeMap<>();
        private final Map<String, Long> unexpected = new TreeMap<>();
        private long trips;

        private MutableTripAnalysis(String distanceSource) {
            this.distanceSource = distanceSource;
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                scopes.put(category, 0L);
            }
        }

        private void add(String person, MunichTripBoundaryFilter.SpatialCategory category,
                String mode, double distance, double travelTime) {
            persons.add(person);
            trips++;
            scopes.merge(category, 1L, Long::sum);
            if (category != MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) return;
            bothInsidePersons.add(person);
            String normalized = mode == null || mode.isBlank()
                    ? "unknown" : mode.toLowerCase(Locale.ROOT);
            modes.computeIfAbsent(normalized, ignored -> new MutableMetric())
                    .add(distance, travelTime);
            if (!MODES.contains(normalized)) unexpected.merge(normalized, 1L, Long::sum);
        }

        private TripAnalysis freeze() {
            Map<String, ModeMetric> metrics = new TreeMap<>();
            modes.forEach((mode, metric) -> metrics.put(mode, metric.freeze()));
            return new TripAnalysis(persons.size(), trips, Map.copyOf(scopes),
                    Map.copyOf(metrics), Map.copyOf(unexpected),
                    Set.copyOf(bothInsidePersons), distanceSource);
        }
    }

    private static final class MutableMetric {
        private long trips;
        private double distance;
        private double time;

        private void add(double metres, double seconds) {
            trips++;
            distance += metres;
            time += seconds;
        }

        private ModeMetric freeze() { return new ModeMetric(trips, distance, time); }
    }

    private static final class MutableStuckSummary {
        private final Set<String> bothInsidePersons;
        private final Set<String> persons = new HashSet<>();
        private final Set<String> scopedPersons = new HashSet<>();
        private final Map<String, Long> modes = new TreeMap<>();
        private final Map<Integer, Long> hours = new TreeMap<>();
        private final Map<Double, Long> times = new TreeMap<>();
        private long total;
        private long exact;
        private long finalHour;

        private MutableStuckSummary(Set<String> bothInsidePersons) {
            this.bothInsidePersons = bothInsidePersons;
        }

        private void accept(PersonStuckEvent event) {
            total++;
            String person = event.getPersonId().toString();
            persons.add(person);
            if (bothInsidePersons.contains(person)) scopedPersons.add(person);
            String mode = event.getLegMode() == null || event.getLegMode().isBlank()
                    ? "unknown" : event.getLegMode().toLowerCase(Locale.ROOT);
            modes.merge(mode, 1L, Long::sum);
            hours.merge((int) Math.floor(event.getTime() / 3600.0), 1L, Long::sum);
            times.merge(event.getTime(), 1L, Long::sum);
            if (Math.abs(event.getTime() - QSIM_END) <= EPSILON) exact++;
            if (event.getTime() >= QSIM_END - 3600.0 - EPSILON
                    && event.getTime() <= QSIM_END + EPSILON) finalHour++;
        }

        private StuckSummary freeze() {
            return new StuckSummary(total, persons.size(), Map.copyOf(modes),
                    Map.copyOf(hours), Map.copyOf(times), exact, finalHour,
                    scopedPersons.size());
        }
    }
}
