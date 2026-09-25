package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;

/** Read-only, scenario-parameterized postprocessor for one completed production run. */
public final class AnalyzeProduction2040Output {
    private AnalyzeProduction2040Output() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: AnalyzeProduction2040Output BAU|FAST_TRACK");
        analyze(Production2040AnalysisSpec.scenario(args[0]));
    }

    static void analyze(Production2040AnalysisSpec.ScenarioDefinition definition)
            throws Exception {
        var files = ValidateProduction2040AnalysisOutput.validate(definition, true);
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(
                MunichMunicipalBoundary.loadDefault());
        var plans = AnalyzeLiteratureBasedScoringDiagnosticOutput.analyzePlans(
                files.plans(), filter);
        validatePlans(plans);
        Map<Id<Person>, List<Boolean>> relevantTrips = readRelevantTrips(files.plans(), filter);
        TripMeasurements trips = readTripMeasurements(files.trips(), filter);
        validateTripConsistency(plans, trips);

        Scenario eventScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(eventScenario.getNetwork()).readFile(files.network().toString());
        new TransitScheduleReader(eventScenario).readFile(files.schedule().toString());
        new MatsimVehicleReader(eventScenario.getTransitVehicles()).readFile(
                files.vehicles().toString());
        Production2040VehicleMetrics vehicleMetrics = new Production2040VehicleMetrics(
                eventScenario.getNetwork(), eventScenario.getTransitSchedule(),
                eventScenario.getTransitVehicles(), relevantTrips);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(vehicleMetrics);
        new MatsimEventsReader(manager).readFile(files.events().toString());
        Production2040VehicleMetrics.Result vehicles = vehicleMetrics.result();
        validateVehicleMetrics(vehicles);

        Map<String, String> reports = buildReports(definition, files, plans, trips, vehicles);
        ValidateProduction2040AnalysisOutput.validateReportBundle(definition, reports);
        Production2040AnalysisSpec.require(files.protectedInputSnapshot().equals(
                        Production2040Contract.protectedInputSnapshot(
                                Production2040Contract.loadAndValidate())),
                "A protected input changed while analysis was running");
        AnalyzeLiteratureBasedScoringDiagnosticOutput.publishAtomically(
                definition.outputDirectory(), reports);
        System.out.printf(Locale.ROOT,
                "2040 PRODUCTION ANALYSIS PASS%nscenario=%s BOTH_INSIDE=%d analysis=%s%n"
                        + "No Controller or QSim was started by the postprocessor.%n",
                definition.scenarioId(), plans.scopeCounts().get(
                        MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE),
                Production2040Contract.projectPath(definition.analysisDirectory()));
    }

    static void validatePlans(AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans) {
        long invalid = plans.scopeCounts().getOrDefault(
                MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE, 0L);
        Production2040AnalysisSpec.require(invalid == 0,
                "Selected plans contain main trips with missing or invalid activity coordinates");
        Production2040AnalysisSpec.require(plans.unexpectedModes().isEmpty(),
                "Unexpected final main modes: " + plans.unexpectedModes());
        long bothInside = plans.scopeCounts().getOrDefault(
                MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE, 0L);
        long expected = Production2040AnalysisSpec.MAIN_MODES.stream().mapToLong(mode ->
                plans.modeMetrics().getOrDefault(mode,
                        AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO).trips()).sum();
        Production2040AnalysisSpec.require(bothInside == expected,
                "Final BOTH_INSIDE main-mode counts do not reconcile");
    }

    static Map<Id<Person>, List<Boolean>> readRelevantTrips(Path plansFile,
            MunichTripBoundaryFilter filter) {
        Map<Id<Person>, List<Boolean>> result = new HashMap<>();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> {
            var classified = filter.classify(person.getSelectedPlan());
            List<Boolean> flags = classified.stream().map(trip -> trip.category()
                    == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE).toList();
            Production2040AnalysisSpec.require(result.put(person.getId(), flags) == null,
                    "Duplicate person in final plans: " + person.getId());
        });
        reader.readFile(plansFile.toString());
        return Map.copyOf(result);
    }

    static TripMeasurements readTripMeasurements(Path file,
            MunichTripBoundaryFilter filter) throws IOException {
        Map<String, MutableTripMetric> metrics = new TreeMap<>();
        Map<String, Long> unexpected = new TreeMap<>();
        long rows = 0;
        long bothInside = 0;
        try (BufferedReader reader = reader(file)) {
            String headerLine = reader.readLine();
            Production2040AnalysisSpec.require(headerLine != null,
                    "Final trips file is empty");
            char delimiter = headerLine.indexOf(';') >= 0 ? ';' : ',';
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput
                    .parseCsvLine(headerLine, delimiter);
            Map<String, Integer> columns = columns(header);
            int modeColumn = column(columns, "main_mode", "mainMode");
            int startX = column(columns, "start_x", "origin_x", "from_x");
            int startY = column(columns, "start_y", "origin_y", "from_y");
            int endX = column(columns, "end_x", "destination_x", "to_x");
            int endY = column(columns, "end_y", "destination_y", "to_y");
            int distance = column(columns, "traveled_distance", "travelled_distance", "distance");
            int time = column(columns, "trav_time", "travel_time");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows++;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .parseCsvLine(line, delimiter);
                Coord origin = coordinate(fields, startX, startY);
                Coord destination = coordinate(fields, endX, endY);
                var category = filter.classify(origin, destination);
                Production2040AnalysisSpec.require(category
                                != MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE,
                        "Final trips contain a row with missing endpoint coordinates");
                if (category != MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) continue;
                bothInside++;
                String mode = Production2040AnalysisSpec.normalizeMainMode(value(fields, modeColumn));
                if (!Production2040AnalysisSpec.MAIN_MODES.contains(mode)) {
                    unexpected.merge(mode, 1L, Long::sum);
                    continue;
                }
                metrics.computeIfAbsent(mode, ignored -> new MutableTripMetric())
                        .add(parseNonNegative(fields, distance), parseTime(fields, time));
            }
        }
        Map<String, TripMetric> frozen = new TreeMap<>();
        Production2040AnalysisSpec.MAIN_MODES.forEach(mode -> frozen.put(mode,
                metrics.getOrDefault(mode, new MutableTripMetric()).freeze()));
        return new TripMeasurements(rows, bothInside, Map.copyOf(frozen),
                Map.copyOf(unexpected),
                "MATSim output_trips traveled_distance: complete routed main-trip distance, "
                        + "including access, egress and transfer legs; not event distance");
    }

    private static void validateTripConsistency(
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements measurements) {
        Production2040AnalysisSpec.require(measurements.unexpectedModes().isEmpty(),
                "Unexpected measured main modes: " + measurements.unexpectedModes());
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            long structural = plans.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO).trips();
            TripMetric measured = measurements.byMode().get(mode);
            Production2040AnalysisSpec.require(measured.validRecords() <= structural,
                    "More measured than structural trips for " + mode);
            double coverage = Production2040AnalysisSpec.percent(measured.validRecords(), structural);
            Production2040AnalysisSpec.require(coverage
                            >= Production2040AnalysisSpec.MIN_MEASUREMENT_COVERAGE_PERCENT,
                    "Insufficient distance/time coverage for " + mode + ": " + coverage + "%");
        }
    }

    private static void validateVehicleMetrics(Production2040VehicleMetrics.Result result) {
        Production2040AnalysisSpec.require(Double.isFinite(result.carMetres())
                        && result.carMetres() >= 0, "Invalid car vehicle-kilometres");
        Production2040AnalysisSpec.require(result.missingLinks() == 0,
                "Vehicle events refer to missing or invalid network links");
        Production2040AnalysisSpec.require(result.missingTransitReferences() == 0,
                "Transit-driver events refer to missing schedule routes");
        Production2040AnalysisSpec.require(result.unmatchedAlightings() == 0,
                "Transit event stream contains unmatched alightings");
        Production2040AnalysisSpec.require(result.openBoardings() == 0,
                "Transit event stream ends with passengers still boarded");
        result.ptByRouteMode().values().forEach(metric -> {
            Production2040AnalysisSpec.require(finiteNonNegative(metric.vehicleMetres())
                            && finiteNonNegative(metric.passengerMetres())
                            && finiteNonNegative(metric.relevantPassengerMetres()),
                    "Invalid PT distance metric");
        });
    }

    static Map<String, String> buildReports(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements trips, Production2040VehicleMetrics.Result vehicles)
            throws IOException {
        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("iteration_mode_shares.csv", Files.readString(files.iterations(),
                StandardCharsets.UTF_8));
        reports.put("late_iteration_statistics.csv", lateCsv(definition,
                Production2040AnalysisSpec.lateStatistics(files.iterationRows())));
        reports.put("final_main_mode_summary.csv", mainModeCsv(definition, plans));
        reports.put("final_pkm_by_main_mode.csv", pkmCsv(definition, plans, trips));
        reports.put("final_car_fkm.csv", carFkmCsv(definition, plans, trips, vehicles));
        reports.put("final_pt_pkm_by_route_mode.csv", ptPkmCsv(definition, vehicles));
        reports.put("final_pt_fkm_by_route_mode.csv", ptFkmCsv(definition, vehicles));
        reports.put("stuck_events_by_iteration_and_mode.csv", Files.readString(files.stuck(),
                StandardCharsets.UTF_8));
        reports.put("analysis_quality_checks.csv", qualityCsv(definition, files, plans,
                trips, vehicles));
        reports.put("analysis_report.md", report(definition, plans, trips, vehicles));
        return Map.copyOf(reports);
    }

    private static String lateCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Map<String, Production2040AnalysisSpec.LateStatistic> statistics) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,mode,window,mean_share_percent,minimum_share_percent,maximum_share_percent,range_percentage_points,linear_trend_pp_per_iteration,range_status,trend_status,measurement_coverage_percent,definition\n");
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            var value = statistics.get(mode);
            csv.append(definition.scenarioId()).append(",0.05,percent,").append(mode)
                    .append(",51-60,").append(number(value.meanSharePercent())).append(',')
                    .append(number(value.minimumSharePercent())).append(',')
                    .append(number(value.maximumSharePercent())).append(',')
                    .append(number(value.rangePercentagePoints())).append(',')
                    .append(number(value.linearTrendPpPerIteration()))
                    .append(",PASS,PASS,100.000000000000,")
                    .append(quote("unscaled iteration-end BOTH_INSIDE main-trip shares"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String mainModeCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans) {
        long total = plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,main_mode,sample_trip_count,unscaled_trip_share_percent,expanded_trip_count_factor_20,measurement_coverage_percent,definition\n");
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            long count = plans.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO).trips();
            csv.append(definition.scenarioId()).append(",0.05,trips,").append(mode)
                    .append(',').append(count).append(',')
                    .append(number(Production2040AnalysisSpec.percent(count, total))).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(count)))
                    .append(",100.000000000000,")
                    .append(quote("final selected-plan BOTH_INSIDE MATSim main trips; stage activities excluded"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String pkmCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements trips) {
        double totalMetres = trips.byMode().values().stream()
                .mapToDouble(TripMetric::distanceMetres).sum();
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,main_mode,sample_trip_count,expanded_trip_count_factor_20,sample_person_km,expanded_person_km_factor_20,pkm_share_percent,mean_trip_distance_km,median_trip_distance_km,mean_travel_time_minutes,measurement_record_count,measurement_coverage_percent,missing_or_invalid_distance_time_count,definition\n");
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            long count = plans.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO).trips();
            TripMetric metric = trips.byMode().get(mode);
            double pkm = metric.distanceMetres() / 1000.0;
            double coverage = Production2040AnalysisSpec.percent(metric.validRecords(), count);
            csv.append(definition.scenarioId()).append(",0.05,person_km,").append(mode)
                    .append(',').append(count).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(count))).append(',')
                    .append(number(pkm)).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(pkm))).append(',')
                    .append(number(Production2040AnalysisSpec.percent(metric.distanceMetres(), totalMetres)))
                    .append(',').append(number(metric.meanDistanceMetres() / 1000.0))
                    .append(',').append(number(metric.medianDistanceMetres() / 1000.0))
                    .append(',').append(number(metric.meanTimeSeconds() / 60.0))
                    .append(',').append(metric.validRecords()).append(',').append(number(coverage))
                    .append(',').append(count - metric.validRecords()).append(',')
                    .append(quote(trips.distanceDefinition())).append('\n');
        }
        return csv.toString();
    }

    private static String carFkmCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements trips, Production2040VehicleMetrics.Result vehicles) {
        double sampleKm = vehicles.carMetres() / 1000.0;
        double carPkm = trips.byMode().get("car").distanceMetres() / 1000.0;
        return "scenario_id,sample_factor,unit,vehicle_class,sample_vehicle_km,expanded_vehicle_km_factor_20,considered_vehicles,unassigned_vehicles,missing_links,car_sample_person_km,car_pkm_to_fkm_ratio,measurement_coverage_percent,definition\n"
                + definition.scenarioId() + ",0.05,vehicle_km,private_car," + number(sampleKm)
                + ',' + number(Production2040AnalysisSpec.expanded(sampleKm)) + ','
                + vehicles.carVehicles() + ',' + vehicles.unassignedVehicles() + ','
                + vehicles.missingLinks() + ',' + number(carPkm) + ','
                + number(sampleKm == 0 ? 0 : carPkm / sampleKm)
                + ",100.000000000000," + quote("VehicleEntersTraffic remainder of first link plus each LinkEnter once minus untravelled final-link remainder at VehicleLeavesTraffic; transit vehicles excluded") + "\n";
    }

    private static String ptPkmCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040VehicleMetrics.Result vehicles) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,pt_route_mode,sample_in_vehicle_person_km,expanded_in_vehicle_person_km_factor_20,sample_boardings,expanded_boardings_factor_20,measurement_coverage_percent,missing_route_or_vehicle_references,definition\n");
        for (String mode : ptModes(vehicles)) {
            var metric = vehicles.ptByRouteMode().getOrDefault(mode,
                    new Production2040VehicleMetrics.PtMetric(0, 0, 0, 0, 0, 0, 0));
            double pkm = metric.relevantPassengerMetres() / 1000.0;
            double coverage = metric.relevantBoardings() == 0 ? 100.0
                    : Production2040AnalysisSpec.percent(
                            metric.relevantCompletedBoardings(), metric.relevantBoardings());
            csv.append(definition.scenarioId()).append(",0.05,person_km,").append(mode)
                    .append(',').append(number(pkm)).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(pkm))).append(',')
                    .append(metric.relevantBoardings()).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(metric.relevantBoardings())))
                    .append(',').append(number(coverage)).append(',')
                    .append(vehicles.missingTransitReferences())
                    .append(',').append(quote("routed in-vehicle distance between the actual boarding and alighting stops on a BOTH_INSIDE main trip; route mode and link sequence from TransitSchedule; access/egress and transfers are not extra main trips"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String ptFkmCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040VehicleMetrics.Result vehicles) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,pt_route_mode,observed_full_service_vehicle_km,reported_full_service_vehicle_km,considered_boardings,measurement_coverage_percent,missing_route_or_vehicle_references,definition\n");
        for (String mode : ptModes(vehicles)) {
            var metric = vehicles.ptByRouteMode().getOrDefault(mode,
                    new Production2040VehicleMetrics.PtMetric(0, 0, 0, 0, 0, 0, 0));
            double fkm = metric.vehicleMetres() / 1000.0;
            csv.append(definition.scenarioId()).append(",0.05,vehicle_km,").append(mode)
                    .append(',').append(number(fkm)).append(',').append(number(fkm)).append(',')
                    .append(metric.boardings()).append(",100.000000000000,")
                    .append(vehicles.missingTransitReferences()).append(',')
                    .append(quote("actual transit-vehicle link-event distance; service supply is simulated at full scale and is therefore not multiplied by 20"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String qualityCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements trips, Production2040VehicleMetrics.Result vehicles) {
        long denominator = plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,check_id,status,observed_value,threshold_or_expected,definition\n");
        appendCheck(csv, definition, "normal_shutdown_and_config", "PASS", "validated", "exact", "normal shutdown, approved run ID, config semantics and manifest hashes");
        appendCheck(csv, definition, "boundary_hash", "PASS", Production2040AnalysisSpec.BOUNDARY_HASH, "canonical UTF-8/LF SHA-256", "Munich municipal boundary");
        appendCheck(csv, definition, "both_inside_trip_sum", "PASS", Long.toString(denominator), "derived per scenario", "no 2019 denominator is imposed");
        appendCheck(csv, definition, "unexpected_main_modes", "PASS", "0", "0", "unexpected modes are fail-closed");
        appendCheck(csv, definition, "missing_activity_coordinates", "PASS", "0", "0", "all relevant main-activity endpoints have finite coordinates");
        appendCheck(csv, definition, "vehicle_missing_links", "PASS", Long.toString(vehicles.missingLinks()), "0", "all vehicle events resolve to the used network");
        appendCheck(csv, definition, "pt_reference_integrity", "PASS", Long.toString(vehicles.missingTransitReferences() + vehicles.unmatchedAlightings() + vehicles.openBoardings()), "0", "schedule routes and boarding/alighting events reconcile");
        Set<String> otherPtModes = new TreeSet<>(vehicles.ptByRouteMode().keySet());
        otherPtModes.removeAll(Production2040AnalysisSpec.PT_ROUTE_MODES);
        appendCheck(csv, definition, "other_pt_route_modes",
                otherPtModes.isEmpty() ? "PASS" : "REPORTED",
                otherPtModes.toString(), "reported separately",
                "route modes outside bus, tram, subway and rail are not silently reassigned");
        double maxLateStuckIncidence = 0;
        for (int iteration = Production2040AnalysisSpec.LATE_FIRST;
                iteration <= Production2040AnalysisSpec.LATE_LAST; iteration++) {
            maxLateStuckIncidence = Math.max(maxLateStuckIncidence,
                    Production2040AnalysisSpec.percent(files.stuckTotals().get(iteration)
                            .uniqueRelevantPersons(), files.iterationRows().get(iteration)
                            .bothInsideTrips()));
        }
        appendCheck(csv, definition, "late_stuck_person_incidence", "PASS",
                number(maxLateStuckIncidence), "<=0.10 percent",
                "maximum iteration-level incidence among persons with BOTH_INSIDE trips in iterations 51-60");
        Map<String, Production2040AnalysisSpec.LateStatistic> late =
                Production2040AnalysisSpec.lateStatistics(files.iterationRows());
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            appendCheck(csv, definition, "late_range_" + mode, "PASS",
                    number(late.get(mode).rangePercentagePoints()), "<=2.0 pp",
                    "iterations 51-60 modal-share range");
            appendCheck(csv, definition, "late_trend_" + mode, "PASS",
                    number(late.get(mode).linearTrendPpPerIteration()),
                    "absolute value <0.10 pp/iteration",
                    "ordinary least-squares trend across iterations 51-60");
        }
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            long structural = plans.modeMetrics().getOrDefault(mode,
                    AnalyzeLiteratureBasedScoringDiagnosticOutput.ModeMetric.ZERO).trips();
            double coverage = Production2040AnalysisSpec.percent(
                    trips.byMode().get(mode).validRecords(), structural);
            appendCheck(csv, definition, "distance_time_coverage_" + mode, "PASS",
                    number(coverage), ">=99.0", "valid standard output-trip distance and time records");
        }
        return csv.toString();
    }

    private static void appendCheck(StringBuilder csv,
            Production2040AnalysisSpec.ScenarioDefinition definition, String id,
            String status, String observed, String expected, String explanation) {
        csv.append(definition.scenarioId()).append(",0.05,check,").append(id).append(',')
                .append(status).append(',').append(quote(observed)).append(',')
                .append(quote(expected)).append(',').append(quote(explanation)).append('\n');
    }

    private static String report(Production2040AnalysisSpec.ScenarioDefinition definition,
            AnalyzeLiteratureBasedScoringDiagnosticOutput.TripAnalysis plans,
            TripMeasurements trips, Production2040VehicleMetrics.Result vehicles) {
        long denominator = plans.scopeCounts().get(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE);
        return Production2040AnalysisSpec.reportHeading(definition) + "\n\n"
                + "## Scope and comparability\n\nThe complete regional five-percent population was simulated. The central analysis includes "
                + denominator + " final selected-plan MATSim main trips whose origin and destination main activities are covered by the Munich municipal boundary (`BOTH_INSIDE`). Stage activities do not create additional trips. BAU and Fast Track use this same code and specification; no 2019 trip denominator is imposed on 2040.\n\n"
                + "## Scaling and distance definitions\n\nTrip shares, Pkm shares, means, medians and travel times are unscaled. Absolute private-person trips, passenger-kilometres and private-car vehicle-kilometres are reported at sample scale and expanded by factor 20. Main-mode Pkm use MATSim's final `output_trips` `traveled_distance`, the complete routed main-trip distance including PT access, egress and transfers. Missing distances are not replaced by straight-line estimates. Car Fkm use the final event stream and the used network, count each traversed link once under MATSim 2025.0 first/last-link conventions and exclude transit vehicles.\n\n"
                + "PT submode Pkm use the routed in-vehicle distance between each actual event-observed boarding and alighting stop during a `BOTH_INSIDE` main trip. Route mode and link sequence come from the referenced transit line and route. Transfers partition Pkm across their used submodes but remain one main trip. PT Fkm count each transit vehicle movement once, independent of passengers. Because transit service is supplied at full scale in MATSim, PT Fkm are not multiplied by 20. Route modes outside bus, tram, subway and rail are reported separately and never reassigned; unresolved references fail validation.\n\n"
                + "## Stability and quality\n\nIterations 51--60 form the common late window. The reports give mean, minimum, maximum, range and linear trend in percentage points per iteration. PersonStuckEvents are reported by iteration and mode; they are diagnostic rather than automatically causal. All final files are published together only after normal shutdown, exact config and input validation, complete iteration histories, coordinate, sum, measurement and event-reference checks pass.\n\n"
                + "This file describes one validated scenario result. A BAU--Fast Track comparison is permitted only after both production runs and both analyses independently pass. No external-cost calculation or visualization is part of this pipeline.\n";
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            Production2040AnalysisSpec.require(result.put(header.get(index), index) == null,
                    "Duplicate trips CSV header " + header.get(index));
        }
        return Map.copyOf(result);
    }

    private static List<String> ptModes(Production2040VehicleMetrics.Result vehicles) {
        Set<String> modes = new TreeSet<>(Production2040AnalysisSpec.PT_ROUTE_MODES);
        modes.addAll(vehicles.ptByRouteMode().keySet());
        return List.copyOf(modes);
    }

    private static int column(Map<String, Integer> columns, String... candidates) {
        for (String candidate : candidates) if (columns.containsKey(candidate)) return columns.get(candidate);
        throw new IllegalStateException("Missing trips CSV column " + String.join("/", candidates));
    }

    private static String value(List<String> fields, int index) {
        Production2040AnalysisSpec.require(index < fields.size(), "Short trips CSV row");
        return fields.get(index).trim();
    }

    private static Coord coordinate(List<String> fields, int x, int y) {
        try {
            return new Coord(Double.parseDouble(value(fields, x)),
                    Double.parseDouble(value(fields, y)));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static double parseNonNegative(List<String> fields, int index) {
        try {
            double result = Double.parseDouble(value(fields, index));
            return finiteNonNegative(result) ? result : Double.NaN;
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private static double parseTime(List<String> fields, int index) {
        try {
            double result = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseTime(
                    value(fields, index));
            return finiteNonNegative(result) ? result : Double.NaN;
        } catch (RuntimeException error) {
            return Double.NaN;
        }
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static BufferedReader reader(Path file) throws IOException {
        var input = Files.newInputStream(file);
        return new BufferedReader(new InputStreamReader(file.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(input) : input, StandardCharsets.UTF_8));
    }

    private static String number(double value) {
        Production2040AnalysisSpec.require(Double.isFinite(value), "Non-finite report value");
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    record TripMeasurements(long totalRows, long bothInsideRows,
                            Map<String, TripMetric> byMode,
                            Map<String, Long> unexpectedModes,
                            String distanceDefinition) { }

    record TripMetric(long validRecords, long invalidRecords, double distanceMetres,
                      double travelTimeSeconds, List<Double> distancesMetres) {
        double meanDistanceMetres() {
            return validRecords == 0 ? 0 : distanceMetres / validRecords;
        }
        double meanTimeSeconds() {
            return validRecords == 0 ? 0 : travelTimeSeconds / validRecords;
        }
        double medianDistanceMetres() {
            if (distancesMetres.isEmpty()) return 0;
            int size = distancesMetres.size();
            return size % 2 == 1 ? distancesMetres.get(size / 2)
                    : (distancesMetres.get(size / 2 - 1) + distancesMetres.get(size / 2)) / 2.0;
        }
    }

    private static final class MutableTripMetric {
        private long valid;
        private long invalid;
        private double distance;
        private double time;
        private final List<Double> distances = new ArrayList<>();

        void add(double metres, double seconds) {
            if (!finiteNonNegative(metres) || !finiteNonNegative(seconds)) {
                invalid++;
                return;
            }
            valid++;
            distance += metres;
            time += seconds;
            distances.add(metres);
        }

        TripMetric freeze() {
            distances.sort(Comparator.naturalOrder());
            return new TripMetric(valid, invalid, distance, time, List.copyOf(distances));
        }
    }
}
