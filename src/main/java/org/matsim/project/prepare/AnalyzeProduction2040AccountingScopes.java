package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;

/** Controller-free accounting-scope analysis of one validated 2040 production output. */
public final class AnalyzeProduction2040AccountingScopes {
    static final String SUBDIRECTORY = "accounting_scopes";
    static final double RECONCILIATION_TOLERANCE_METRES = 1e-3;
    static final String DISTANCE_DEFINITION = "MATSim output_trips traveled_distance: complete "
            + "routed main-trip distance, including access, egress and transfer legs; "
            + "not beeline or event distance";

    private AnalyzeProduction2040AccountingScopes() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: AnalyzeProduction2040AccountingScopes BAU|FAST_TRACK");
        analyze(args[0]);
    }

    static void analyze(String scenarioArgument) throws Exception {
        var definition = Production2040AnalysisSpec.scenario(scenarioArgument);
        var files = ValidateProduction2040AnalysisOutput.validatePublished(definition);
        Path destination = definition.analysisDirectory().resolve(SUBDIRECTORY);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "Accounting-scope analysis already exists and will not be overwritten: "
                        + Production2040Contract.projectPath(destination));

        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        var index = Production2040AccountingScopes.read(files.plans(), boundary);
        validatePlanIndex(index);
        ScopeMeasurements measurements = readTripMeasurements(files.trips(), boundary, index);
        validateMeasurements(index, measurements);

        Scenario eventScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(eventScenario.getNetwork()).readFile(files.network().toString());
        new TransitScheduleReader(eventScenario).readFile(files.schedule().toString());
        new MatsimVehicleReader(eventScenario.getTransitVehicles()).readFile(
                files.vehicles().toString());
        var accounting = new Production2040AccountingEventMetrics(eventScenario.getNetwork(),
                boundary, index);
        var regional = new Production2040VehicleMetrics(eventScenario.getNetwork(),
                eventScenario.getTransitSchedule(), eventScenario.getTransitVehicles(),
                Map.of(), accounting);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(regional);
        new MatsimEventsReader(manager).readFile(files.events().toString());
        var regionalResult = regional.result();
        var accountingResult = accounting.result();
        RegionalReferences references = readRegionalReferences(definition);
        validateEventMetrics(regionalResult, accountingResult, references);

        Map<String, String> reports = buildReports(definition, index, measurements,
                regionalResult, accountingResult, references);
        ValidateProduction2040AccountingScopes.validateBundle(definition, reports);
        Production2040AnalysisSpec.require(files.protectedInputSnapshot().equals(
                        Production2040Contract.protectedInputSnapshot(
                                Production2040Contract.loadAndValidate())),
                "A protected input changed during accounting-scope analysis");
        publishAtomically(definition.analysisDirectory(), destination, reports);
        System.out.printf(Locale.ROOT,
                "2040 ACCOUNTING-SCOPE ANALYSIS PASS%nscenario=%s output=%s%n"
                        + "No Controller or QSim was started.%n",
                definition.scenarioId(), Production2040Contract.projectPath(destination));
    }

    static void validatePlanIndex(Production2040AccountingScopes.Index index) {
        Production2040AnalysisSpec.require(!index.persons().isEmpty(),
                "Final plans contain no persons");
        Production2040AnalysisSpec.require(!index.trips().isEmpty(),
                "Final plans contain no main trips");
        long invalid = index.endpointTripCounts().getOrDefault(
                MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE, 0L);
        Production2040AnalysisSpec.require(invalid == 0,
                "Selected plans contain main trips with missing endpoint coordinates");
        Map<String, Long> unexpected = new TreeMap<>();
        index.trips().values().stream()
                .filter(trip -> !Production2040AnalysisSpec.MAIN_MODES.contains(trip.mainMode()))
                .forEach(trip -> unexpected.merge(trip.mainMode(), 1L, Long::sum));
        Production2040AnalysisSpec.require(unexpected.isEmpty(),
                "Unexpected selected-plan main modes: " + unexpected);
    }

    static ScopeMeasurements readTripMeasurements(Path file, MunichMunicipalBoundary boundary,
            Production2040AccountingScopes.Index index) throws IOException {
        Map<Production2040AccountingScopes.Scope, Map<String, MutableTripMetric>> metrics =
                new EnumMap<>(Production2040AccountingScopes.Scope.class);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            Map<String, MutableTripMetric> modes = new TreeMap<>();
            Production2040AnalysisSpec.MAIN_MODES.forEach(mode ->
                    modes.put(mode, new MutableTripMetric()));
            metrics.put(scope, modes);
        }
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        Set<Production2040AccountingScopes.TripKey> seen = new HashSet<>();
        Set<Production2040AccountingScopes.TripKey> validMeasurements = new HashSet<>();
        long rows = 0;
        try (BufferedReader reader = reader(file)) {
            String headerLine = reader.readLine();
            Production2040AnalysisSpec.require(headerLine != null,
                    "Final output trips file is empty");
            char delimiter = headerLine.contains(";") ? ';' : ',';
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                    headerLine, delimiter);
            Map<String, Integer> columns = columns(header);
            int personColumn = column(columns, "person");
            int tripNumberColumn = column(columns, "trip_number");
            int modeColumn = column(columns, "main_mode", "mainMode");
            int distanceColumn = column(columns, "traveled_distance", "travelled_distance");
            int timeColumn = column(columns, "trav_time", "travel_time");
            int startX = column(columns, "start_x");
            int startY = column(columns, "start_y");
            int endX = column(columns, "end_x");
            int endY = column(columns, "end_y");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows++;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                        line, delimiter);
                String person = value(fields, personColumn);
                int oneBasedTrip;
                try {
                    oneBasedTrip = Integer.parseInt(value(fields, tripNumberColumn));
                } catch (NumberFormatException error) {
                    throw new IllegalStateException("Invalid trip_number for person " + person,
                            error);
                }
                Production2040AnalysisSpec.require(oneBasedTrip >= 1,
                        "trip_number must be one-based and positive for " + person);
                var key = new Production2040AccountingScopes.TripKey(person, oneBasedTrip - 1);
                var trip = index.trips().get(key);
                Production2040AnalysisSpec.require(trip != null,
                        "Output trip has no selected-plan match: " + key);
                Production2040AnalysisSpec.require(seen.add(key),
                        "Duplicate output trip: " + key);
                String mode = Production2040AnalysisSpec.normalizeMainMode(
                        value(fields, modeColumn));
                Production2040AnalysisSpec.require(mode.equals(trip.mainMode()),
                        "Output-trip main mode differs from selected plan for " + key);
                var category = filter.classify(coordinate(fields, startX, startY),
                        coordinate(fields, endX, endY));
                Production2040AnalysisSpec.require(category == trip.endpointCategory(),
                        "Output-trip endpoints differ from selected plan for " + key);
                double metres = parseNonNegative(fields, distanceColumn);
                double seconds = parseTime(fields, timeColumn);
                if (isValidTripMeasurement(metres, seconds)) validMeasurements.add(key);
                for (var scope : Production2040AccountingScopes.Scope.values()) {
                    if (trip.included(scope)) metrics.get(scope).get(mode).add(metres, seconds);
                }
            }
        }
        Map<Production2040AccountingScopes.Scope, Map<String, TripMetric>> frozen =
                new EnumMap<>(Production2040AccountingScopes.Scope.class);
        metrics.forEach((scope, modes) -> {
            Map<String, TripMetric> values = new TreeMap<>();
            modes.forEach((mode, metric) -> values.put(mode, metric.freeze()));
            frozen.put(scope, Map.copyOf(values));
        });
        return new ScopeMeasurements(rows, Set.copyOf(seen), Set.copyOf(validMeasurements),
                Map.copyOf(frozen));
    }

    static void validateMeasurements(Production2040AccountingScopes.Index index,
            ScopeMeasurements measurements) {
        Production2040AnalysisSpec.require(measurements.totalRows()
                        == measurements.seenTrips().size(),
                "Final output-trip row count differs from unique selected-plan trip keys");
        Production2040AnalysisSpec.require(measurements.seenTrips().size()
                        <= index.trips().size(),
                "More measured output-trip rows than structural selected-plan trips");
        Production2040AnalysisSpec.require(measurements.validMeasurementTrips().size()
                        <= measurements.seenTrips().size(),
                "More valid distance/time measurements than output-trip rows");

        MeasurementDiagnostics diagnostics = measurementDiagnostics(index, measurements);
        requireCoverage("all structural selected-plan trips",
                diagnostics.overall().measuredTrips(), diagnostics.overall().structuralTrips());
        requireCoverage("all structural selected-plan trips with valid routed distance/time",
                diagnostics.overall().validDistanceTimeTrips(),
                diagnostics.overall().structuralTrips());
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                MeasurementCoverage coverage = diagnostics.byScopeAndMode().get(scope)
                        .get(mode);
                Production2040AnalysisSpec.require(coverage.measuredTrips()
                                <= coverage.structuralTrips(),
                        "More measured output-trip rows than structural selected-plan trips for "
                                + scope + "/" + mode);
                Production2040AnalysisSpec.require(coverage.validDistanceTimeTrips()
                                <= coverage.measuredTrips(),
                        "More valid distance/time measurements than output-trip rows for "
                                + scope + "/" + mode);
                requireCoverage(scope + "/" + mode + " standard output-trip rows",
                        coverage.measuredTrips(), coverage.structuralTrips());
                requireCoverage(scope + "/" + mode + " valid routed distance/time",
                        coverage.validDistanceTimeTrips(), coverage.structuralTrips());
            }
        }
    }

    static void validateEventMetrics(Production2040VehicleMetrics.Result regional,
            Production2040AccountingEventMetrics.Result accounting,
            RegionalReferences references) {
        Production2040AnalysisSpec.require(regional.missingLinks() == 0,
                "Vehicle events refer to missing network links");
        Production2040AnalysisSpec.require(regional.missingTransitReferences() == 0
                        && regional.unmatchedAlightings() == 0 && regional.openBoardings() == 0,
                "Transit event references do not reconcile");
        Production2040AnalysisSpec.require(accounting.unmatchedPersons() == 0
                        && accounting.unmatchedTrips() == 0
                        && accounting.repeatedVehicleEnters() == 0
                        && accounting.unmatchedVehicleLeaves() == 0
                        && accounting.unattributedCarMovementEvents() == 0
                        && accounting.incompleteCarSegments() == 0,
                "Private-car event attribution is ambiguous: " + accounting);
        double endpointSum = accounting.carByEndpointCategory().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        requireClose(endpointSum, regional.carMetres(), "endpoint-category car Fkm");
        requireClose(regional.carMetres(), references.regionalCarMetres(),
                "published regional car Fkm");
        Set<String> modes = new TreeSet<>(references.regionalPtMetres().keySet());
        modes.addAll(accounting.ptByRouteMode().keySet());
        modes.addAll(regional.ptByRouteMode().keySet());
        for (String mode : modes) {
            double observed = regional.ptByRouteMode().getOrDefault(mode, zeroPt())
                    .vehicleMetres();
            double attributed = accounting.ptByRouteMode().getOrDefault(mode, zeroPtService())
                    .uncutMetres();
            double published = references.regionalPtMetres().getOrDefault(mode, 0.0);
            requireClose(observed, attributed, "event PT Fkm for " + mode);
            requireClose(observed, published, "published regional PT Fkm for " + mode);
        }
        var pseudolinks = accounting.ptPseudolinks();
        Production2040AnalysisSpec.require(finiteNonNegative(pseudolinks.uncutModelMetres())
                        && finiteNonNegative(pseudolinks.insideModelMetres())
                        && finiteNonNegative(pseudolinks.outsideModelMetres())
                        && finiteNonNegative(pseudolinks.territorialServiceMetres())
                        && finiteNonNegative(pseudolinks.zeroModelLengthTerritorialServiceMetres()),
                "Invalid PT pseudolink accounting metrics");
        Production2040AnalysisSpec.require(pseudolinks.usedPointAnchoredLinks()
                        == pseudolinks.insideLinks() + pseudolinks.outsideLinks(),
                "Point-anchored PT pseudolink counts do not reconcile");
        requireClose(pseudolinks.uncutModelMetres(), pseudolinks.insideModelMetres()
                        + pseudolinks.outsideModelMetres(),
                "Point-anchored PT pseudolink model lengths");
        Production2040AnalysisSpec.require(pseudolinks.boundaryAmbiguousAnchors() == 0,
                "Boundary-ambiguous PT pseudolink anchors must fail before publication");
        double territorialPt = accounting.ptByRouteMode().values().stream()
                .mapToDouble(Production2040AccountingEventMetrics.PtService::territorialMetres)
                .sum();
        Production2040AnalysisSpec.require(pseudolinks.territorialServiceMetres()
                        <= territorialPt + RECONCILIATION_TOLERANCE_METRES,
                "Point-anchored PT pseudolink service exceeds territorial PT service");
        Production2040AnalysisSpec.require(Math.abs(
                        pseudolinks.zeroModelLengthTerritorialServiceMetres())
                        <= RECONCILIATION_TOLERANCE_METRES,
                "Zero-model-length PT links must contribute zero territorial service");
    }

    static RegionalReferences readRegionalReferences(
            Production2040AnalysisSpec.ScenarioDefinition definition) throws IOException {
        Path analysis = definition.analysisDirectory();
        Path carFile = analysis.resolve("final_car_fkm.csv");
        Path ptFile = analysis.resolve("final_pt_fkm_by_route_mode.csv");
        List<Map<String, String>> carRows = readCsv(carFile);
        Production2040AnalysisSpec.require(carRows.size() == 1,
                "Regional car Fkm report must contain exactly one row");
        requireScenario(carRows.getFirst(), definition, carFile);
        double carMetres = 1000.0 * parse(carRows.getFirst(), "sample_vehicle_km", carFile);
        Map<String, Double> pt = new TreeMap<>();
        for (Map<String, String> row : readCsv(ptFile)) {
            requireScenario(row, definition, ptFile);
            String mode = Production2040AnalysisSpec.normalizePtRouteMode(
                    row.get("pt_route_mode"));
            Production2040AnalysisSpec.require(pt.put(mode, 1000.0 * parse(row,
                    "observed_full_service_vehicle_km", ptFile)) == null,
                    "Duplicate regional PT route mode " + mode);
        }
        return new RegionalReferences(carMetres, Map.copyOf(pt));
    }

    static Map<String, String> buildReports(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index, ScopeMeasurements measurements,
            Production2040VehicleMetrics.Result regional,
            Production2040AccountingEventMetrics.Result accounting,
            RegionalReferences references) {
        MeasurementDiagnostics diagnostics = measurementDiagnostics(index, measurements);
        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("accounting_scope_definition.csv", definitionCsv(definition));
        reports.put("final_modal_split_by_scope.csv", modalCsv(definition, index,
                diagnostics));
        reports.put("final_pkm_by_scope_and_mode.csv", pkmCsv(definition, index,
                measurements, diagnostics));
        reports.put("final_private_car_fkm_by_scope.csv", carCsv(definition, measurements,
                accounting, references));
        reports.put("final_active_mode_distance_by_scope.csv", activeCsv(definition,
                measurements));
        reports.put("final_territorial_pt_fkm_by_route_mode.csv", ptCsv(definition,
                accounting, references));
        reports.put("resident_cohort_summary.csv", residentCsv(definition, index));
        reports.put("accounting_scope_quality_checks.csv", qualityCsv(definition, index,
                diagnostics, regional, accounting, references));
        reports.put("accounting_scope_report.md", report(definition, index, accounting,
                diagnostics));
        return Map.copyOf(reports);
    }

    private static String definitionCsv(
            Production2040AnalysisSpec.ScenarioDefinition definition) {
        String header = "scenario_id,scope_id,accounting_object,spatial_or_cohort_rule,"
                + "scaling_rule,annualisation_rule,boundary_hash,definition\n";
        return header
                + definition.scenarioId() + ",BOTH_INSIDE,private_demand,"
                + quote("both main-activity endpoints covered by Munich boundary") + ','
                + quote("sample counts/Pkm/Fkm multiplied by 20; shares and means unscaled")
                + ',' + quote(annualRule()) + ',' + Production2040AnalysisSpec.BOUNDARY_HASH
                + ',' + quote("MATSim main trips; stage activities excluded") + "\n"
                + definition.scenarioId() + ",MUNICH_RESIDENTS,private_demand,"
                + quote("all main trips of persons with a documented non-stage home activity covered by Munich boundary")
                + ',' + quote("sample counts/Pkm/Fkm multiplied by 20; shares and means unscaled")
                + ',' + quote(annualRule()) + ',' + Production2040AnalysisSpec.BOUNDARY_HASH
                + ',' + quote("residence is never inferred from trip origin") + "\n"
                + definition.scenarioId() + ",TERRITORIAL_PT_SERVICE,public_transport_supply,"
                + quote("event-observed PT vehicle movement clipped geometrically to Munich boundary")
                + ',' + quote("full service scale; no factor-20 multiplication") + ','
                + quote(annualRule()) + ',' + Production2040AnalysisSpec.BOUNDARY_HASH + ','
                + quote("crossing links use inside fraction of straight node-to-node geometry multiplied by MATSim link length") + "\n";
    }

    private static String modalCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index, MeasurementDiagnostics diagnostics) {
        StringBuilder csv = new StringBuilder("scenario_id,scope_id,sample_factor,unit,main_mode,sample_daily_trip_count,unscaled_modal_share_percent,expanded_daily_trip_count_factor_20,illustrative_annual_equivalent_365_days,standard_output_trip_row_count,measurement_coverage_percent,missing_structural_trip_count,definition\n");
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            Map<String, Long> counts = structuralCounts(index, scope);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                long count = counts.get(mode);
                double expanded = Production2040AnalysisSpec.expanded(count);
                MeasurementCoverage coverage = diagnostics.byScopeAndMode().get(scope)
                        .get(mode);
                csv.append(definition.scenarioId()).append(',').append(scope)
                        .append(",0.05,trips,").append(mode).append(',').append(count).append(',')
                        .append(number(Production2040AnalysisSpec.percent(count, total))).append(',')
                        .append(number(expanded)).append(',').append(number(expanded * 365.0))
                        .append(',').append(coverage.measuredTrips()).append(',')
                        .append(number(coverage.measurementCoveragePercent())).append(',')
                        .append(coverage.missingStructuralTrips()).append(',')
                        .append(quote(scopeDefinition(scope)))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String pkmCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index, ScopeMeasurements measurements,
            MeasurementDiagnostics diagnostics) {
        StringBuilder csv = new StringBuilder("scenario_id,scope_id,sample_factor,unit,main_mode,sample_daily_trip_count,expanded_daily_trip_count_factor_20,sample_daily_person_km,expanded_daily_person_km_factor_20,illustrative_annual_equivalent_365_days,pkm_share_percent,mean_trip_distance_km,median_trip_distance_km,mean_travel_time_minutes,measurement_record_count,measurement_coverage_percent,missing_or_invalid_distance_time_count,definition\n");
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            Map<String, Long> counts = structuralCounts(index, scope);
            Map<String, TripMetric> modes = measurements.byScope().get(scope);
            double totalMetres = modes.values().stream().mapToDouble(TripMetric::distanceMetres)
                    .sum();
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                TripMetric metric = modes.get(mode);
                long count = counts.get(mode);
                MeasurementCoverage coverage = diagnostics.byScopeAndMode().get(scope)
                        .get(mode);
                double samplePkm = metric.distanceMetres() / 1000.0;
                double expandedPkm = Production2040AnalysisSpec.expanded(samplePkm);
                csv.append(definition.scenarioId()).append(',').append(scope)
                        .append(",0.05,person_km,").append(mode).append(',').append(count)
                        .append(',').append(number(Production2040AnalysisSpec.expanded(count)))
                        .append(',').append(number(samplePkm)).append(',')
                        .append(number(expandedPkm)).append(',')
                        .append(number(expandedPkm * 365.0)).append(',')
                        .append(number(Production2040AnalysisSpec.percent(
                                metric.distanceMetres(), totalMetres))).append(',')
                        .append(number(metric.meanDistanceMetres() / 1000.0)).append(',')
                        .append(number(metric.medianDistanceMetres() / 1000.0)).append(',')
                        .append(number(metric.meanTimeSeconds() / 60.0)).append(',')
                        .append(metric.validRecords()).append(',')
                        .append(number(coverage.validDistanceTimeCoveragePercent())).append(',')
                        .append(coverage.missingOrInvalidDistanceTimeTrips()).append(',')
                        .append(quote(DISTANCE_DEFINITION))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String carCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            ScopeMeasurements measurements, Production2040AccountingEventMetrics.Result result,
            RegionalReferences references) {
        StringBuilder csv = new StringBuilder("scenario_id,scope_id,sample_factor,unit,sample_daily_vehicle_km,expanded_daily_vehicle_km_factor_20,illustrative_annual_equivalent_365_days,considered_vehicles,considered_main_trips,car_sample_person_km,car_pkm_to_fkm_ratio,unmatched_persons,unmatched_vehicles,unmatched_trips,incomplete_traffic_segments,missing_links,stuck_main_trips,regional_reference_sample_vehicle_km,definition\n");
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            var metric = result.carByScope().get(scope);
            double sampleKm = metric.metres() / 1000.0;
            double expanded = Production2040AnalysisSpec.expanded(sampleKm);
            double pkm = measurements.byScope().get(scope).get("car").distanceMetres() / 1000.0;
            csv.append(definition.scenarioId()).append(',').append(scope)
                    .append(",0.05,vehicle_km,").append(number(sampleKm)).append(',')
                    .append(number(expanded)).append(',').append(number(expanded * 365.0))
                    .append(',').append(metric.vehicles()).append(',').append(metric.trips())
                    .append(',').append(number(pkm)).append(',')
                    .append(number(sampleKm == 0 ? 0 : pkm / sampleKm)).append(',')
                    .append(result.unmatchedPersons()).append(',')
                    .append(result.repeatedVehicleEnters() + result.unmatchedVehicleLeaves()
                            + result.unattributedCarMovementEvents()).append(',')
                    .append(result.unmatchedTrips())
                    .append(',').append(result.incompleteCarSegments()).append(",0,")
                    .append(metric.stuckTrips()).append(',')
                    .append(number(references.regionalCarMetres() / 1000.0)).append(',')
                    .append(quote("private-car event distance attributed by person and selected-plan main-trip index; first/last-link convention shared with regional production metric; transit vehicles excluded; ratio is not interpreted as occupancy"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String activeCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            ScopeMeasurements measurements) {
        StringBuilder csv = new StringBuilder("scenario_id,scope_id,sample_factor,unit,main_mode,distance_metric,sample_daily_distance_km,expanded_daily_distance_km_factor_20,illustrative_annual_equivalent_365_days,vehicle_km_applicability,definition\n");
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            for (String mode : List.of("bike", "walk")) {
                double sample = measurements.byScope().get(scope).get(mode).distanceMetres()
                        / 1000.0;
                double expanded = Production2040AnalysisSpec.expanded(sample);
                boolean bike = mode.equals("bike");
                csv.append(definition.scenarioId()).append(',').append(scope)
                        .append(",0.05,distance_km,").append(mode).append(',')
                        .append(bike ? "derived_bike_km" : "walk_person_km")
                        .append(',').append(number(sample)).append(',').append(number(expanded))
                        .append(',').append(number(expanded * 365.0)).append(',')
                        .append(bike ? "DERIVED_ONE_PERSON_PER_BIKE" : "NOT_APPLICABLE")
                        .append(',').append(quote(bike
                                ? "bike-km equals bike Pkm under an explicit one-person-per-bike convention; not event-observed motor-vehicle Fkm"
                                : "walk distance is Pkm only and is never labelled vehicle-kilometres"))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String ptCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingEventMetrics.Result result,
            RegionalReferences references) {
        Map<String, Production2040AccountingEventMetrics.PtService> grouped = groupPt(
                result.ptByRouteMode());
        StringBuilder csv = new StringBuilder("scenario_id,scope_id,sample_factor,unit,pt_route_mode,full_service_daily_vehicle_km,factor_20_daily_vehicle_km,illustrative_annual_equivalent_365_days,regional_uncut_full_service_vehicle_km,territorial_share_percent,crossing_link_count,crossing_link_model_km,crossing_link_service_vehicle_km,point_anchored_pseudolink_used_link_count,point_anchored_pseudolink_uncut_model_km,point_anchored_pseudolink_inside_link_count,point_anchored_pseudolink_inside_model_km,point_anchored_pseudolink_outside_link_count,point_anchored_pseudolink_outside_model_km,point_anchored_pseudolink_boundary_ambiguous_anchor_count,point_anchored_pseudolink_territorial_service_km,point_anchored_pseudolink_territorial_service_share_percent,zero_model_length_pt_link_count,zero_model_length_pt_link_territorial_service_km,definition\n");
        double totalTerritorial = 0;
        double totalUncut = 0;
        double totalCrossingService = 0;
        for (String mode : List.of("bus", "tram", "subway", "rail", "ferry/other")) {
            var metric = grouped.getOrDefault(mode, zeroPtService());
            double territorial = metric.territorialMetres() / 1000.0;
            double uncut = metric.uncutMetres() / 1000.0;
            totalTerritorial += territorial;
            totalUncut += uncut;
            totalCrossingService += metric.crossingServiceMetres() / 1000.0;
            csv.append(definition.scenarioId())
                    .append(",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,").append(mode).append(',')
                    .append(number(territorial)).append(",NOT_APPLICABLE,")
                    .append(number(territorial * 365.0)).append(',').append(number(uncut))
                    .append(',').append(number(Production2040AnalysisSpec.percent(
                            territorial, uncut))).append(',').append(metric.crossingLinkCount())
                    .append(',').append(number(metric.crossingLinkModelMetres() / 1000.0))
                    .append(',').append(number(metric.crossingServiceMetres() / 1000.0))
                    .append(notApplicablePseudolinkColumns())
                    .append(',').append(quote(ptDefinition())).append('\n');
        }
        var pseudolinks = result.ptPseudolinks();
        csv.append(definition.scenarioId())
                .append(",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,TOTAL,")
                .append(number(totalTerritorial)).append(",NOT_APPLICABLE,")
                .append(number(totalTerritorial * 365.0)).append(',').append(number(totalUncut))
                .append(',').append(number(Production2040AnalysisSpec.percent(
                        totalTerritorial, totalUncut))).append(',')
                .append(result.crossingLinkCount()).append(',')
                .append(number(result.crossingLinkModelMetres() / 1000.0)).append(',')
                .append(number(totalCrossingService)).append(',')
                .append(pseudolinks.usedPointAnchoredLinks()).append(',')
                .append(number(pseudolinks.uncutModelMetres() / 1000.0)).append(',')
                .append(pseudolinks.insideLinks()).append(',')
                .append(number(pseudolinks.insideModelMetres() / 1000.0)).append(',')
                .append(pseudolinks.outsideLinks()).append(',')
                .append(number(pseudolinks.outsideModelMetres() / 1000.0)).append(',')
                .append(pseudolinks.boundaryAmbiguousAnchors()).append(',')
                .append(number(pseudolinks.territorialServiceMetres() / 1000.0)).append(',')
                .append(number(Production2040AnalysisSpec.percent(
                        pseudolinks.territorialServiceMetres(), totalTerritorial * 1000.0)))
                .append(',').append(pseudolinks.zeroModelLengthLinks()).append(',')
                .append(number(pseudolinks.zeroModelLengthTerritorialServiceMetres() / 1000.0))
                .append(',').append(quote(ptDefinition()))
                .append('\n');
        return csv.toString();
    }

    private static String residentCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index) {
        StringBuilder csv = new StringBuilder("scenario_id,cohort_status,sample_factor,unit,sample_person_count,expanded_person_count_factor_20,sample_daily_main_trip_count,expanded_daily_main_trip_count_factor_20,illustrative_annual_equivalent_365_days,included_in_munich_residents_scope,definition\n");
        for (var status : Production2040AccountingScopes.ResidentStatus.values()) {
            long persons = index.personCounts().get(status);
            long trips = index.tripCounts().get(status);
            double expandedTrips = Production2040AnalysisSpec.expanded(trips);
            csv.append(definition.scenarioId()).append(',').append(status)
                    .append(",0.05,persons_and_trips,").append(persons).append(',')
                    .append(number(Production2040AnalysisSpec.expanded(persons))).append(',')
                    .append(trips).append(',').append(number(expandedTrips)).append(',')
                    .append(number(expandedTrips * 365.0)).append(',')
                    .append(status == Production2040AccountingScopes.ResidentStatus.RESIDENT)
                    .append(',').append(quote(status == Production2040AccountingScopes.ResidentStatus.UNRESOLVED
                            ? "no identifiable non-stage home activity with a finite coordinate; excluded from resident cohort"
                            : "first documented selected-plan home activity classified with boundary covers"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String qualityCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index, MeasurementDiagnostics diagnostics,
            Production2040VehicleMetrics.Result regional,
            Production2040AccountingEventMetrics.Result accounting,
            RegionalReferences references) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,check_id,status,observed_value,threshold_or_expected,definition\n");
        check(csv, definition, "validated_source_output", "PASS", "validated", "exact",
                "existing production analysis, normal shutdown, config, run ID and protected hashes");
        check(csv, definition, "boundary_hash", "PASS",
                Production2040AnalysisSpec.BOUNDARY_HASH, "canonical UTF-8/LF SHA-256",
                "Munich municipal boundary");
        MeasurementCoverage overall = diagnostics.overall();
        check(csv, definition, "selected_plan_structural_trip_count", "REPORTED",
                Long.toString(overall.structuralTrips()), "selected-plan main trips",
                "structural final selected-plan trip index used for modal split and expanded counts");
        check(csv, definition, "standard_output_trip_measured_count", "REPORTED",
                Long.toString(overall.measuredTrips()), "unique standard output-trip rows",
                "every available output row must match one indexed selected-plan trip");
        check(csv, definition, "standard_output_trip_missing_count", "REPORTED",
                Long.toString(overall.missingStructuralTrips()), "structural selected-plan trips",
                "standard output-trip measurement not available for every structural selected-plan trip");
        check(csv, definition, "standard_output_trip_coverage_percent",
                coverageStatus(overall.measurementCoveragePercent()),
                number(overall.measurementCoveragePercent()),
                minimumCoverage(), "unique standard output-trip rows divided by structural selected-plan trips");
        check(csv, definition, "valid_distance_time_measurement_count", "REPORTED",
                Long.toString(overall.validDistanceTimeTrips()), "valid routed distance/time rows",
                "valid values used for Pkm, distance and travel-time statistics");
        check(csv, definition, "valid_distance_time_coverage_percent",
                coverageStatus(overall.validDistanceTimeCoveragePercent()),
                number(overall.validDistanceTimeCoveragePercent()), minimumCoverage(),
                "valid routed distance/time rows divided by structural selected-plan trips");
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                MeasurementCoverage coverage = diagnostics.byScopeAndMode().get(scope)
                        .get(mode);
                String prefix = "measurement_" + scope.name().toLowerCase(Locale.ROOT)
                        + '_' + mode;
                check(csv, definition, prefix + "_structural_trip_count", "REPORTED",
                        Long.toString(coverage.structuralTrips()), "selected-plan main trips",
                        "structural count for accounting scope and main mode");
                check(csv, definition, prefix + "_measured_trip_count", "REPORTED",
                        Long.toString(coverage.measuredTrips()), "unique standard output-trip rows",
                        "available rows matching the structural scope and main mode");
                check(csv, definition, prefix + "_missing_trip_count", "REPORTED",
                        Long.toString(coverage.missingStructuralTrips()),
                        "structural selected-plan trips",
                        "standard output-trip measurement not available for every structural selected-plan trip");
                check(csv, definition, prefix + "_coverage_percent",
                        coverageStatus(coverage.measurementCoveragePercent()),
                        number(coverage.measurementCoveragePercent()), minimumCoverage(),
                        "unique standard output-trip rows divided by structural trips");
                check(csv, definition, prefix + "_valid_distance_time_coverage_percent",
                        coverageStatus(coverage.validDistanceTimeCoveragePercent()),
                        number(coverage.validDistanceTimeCoveragePercent()), minimumCoverage(),
                        "valid routed distance/time rows divided by structural trips");
            }
        }
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            check(csv, definition, "missing_indexed_trips_endpoint_"
                            + category.name().toLowerCase(Locale.ROOT), "REPORTED",
                    Long.toString(diagnostics.missingByEndpointCategory().get(category)),
                    "structural selected-plan trips",
                    "missing standard output-trip rows grouped by selected-plan endpoint category");
        }
        for (var status : Production2040AccountingScopes.ResidentStatus.values()) {
            check(csv, definition, "missing_indexed_trips_resident_"
                            + status.name().toLowerCase(Locale.ROOT), "REPORTED",
                    Long.toString(diagnostics.missingByResidentStatus().get(status)),
                    "structural selected-plan trips",
                    "missing standard output-trip rows grouped by documented resident status");
        }
        check(csv, definition, "unexpected_main_modes", "PASS", "0", "0",
                "only car, pt, bike and walk are accepted");
        check(csv, definition, "unresolved_residents",
                index.personCounts().get(Production2040AccountingScopes.ResidentStatus.UNRESOLVED) == 0
                        ? "PASS" : "REPORTED",
                Long.toString(index.personCounts().get(
                        Production2040AccountingScopes.ResidentStatus.UNRESOLVED)),
                "reported and excluded", "residence is never inferred from trip origin");
        check(csv, definition, "private_car_attribution", "PASS",
                Long.toString(accounting.unmatchedPersons() + accounting.unmatchedTrips()
                        + accounting.repeatedVehicleEnters() + accounting.unmatchedVehicleLeaves()
                        + accounting.unattributedCarMovementEvents()
                        + accounting.incompleteCarSegments()), "0",
                "all private-car event segments resolve to one person and selected-plan main trip");
        double endpoint = accounting.carByEndpointCategory().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            check(csv, definition, "private_car_fkm_endpoint_"
                            + category.name().toLowerCase(Locale.ROOT), "REPORTED",
                    number(accounting.carByEndpointCategory().getOrDefault(category, 0.0)
                            / 1000.0), "sample vehicle_km",
                    "mutually exclusive selected-plan endpoint category used in regional reconciliation");
        }
        check(csv, definition, "regional_private_car_fkm_reconciliation", "PASS",
                number(endpoint / 1000.0), number(references.regionalCarMetres() / 1000.0),
                "all endpoint categories sum to unchanged regional event-based car Fkm");
        double eventPt = regional.ptByRouteMode().values().stream()
                .mapToDouble(Production2040VehicleMetrics.PtMetric::vehicleMetres).sum();
        double publishedPt = references.regionalPtMetres().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        check(csv, definition, "regional_pt_fkm_reconciliation", "PASS",
                number(eventPt / 1000.0), number(publishedPt / 1000.0),
                "uncut event PT service equals unchanged final_pt_fkm_by_route_mode.csv");
        var pseudolinks = accounting.ptPseudolinks();
        double territorialPt = accounting.ptByRouteMode().values().stream()
                .mapToDouble(Production2040AccountingEventMetrics.PtService::territorialMetres)
                .sum();
        check(csv, definition, "point_anchored_pt_pseudolinks_used", "REPORTED",
                Long.toString(pseudolinks.usedPointAnchoredLinks()), "distinct event-used links",
                "positive-model-length PT self-loops with identical finite endpoint coordinates use a point-anchor proxy, not geometric clipping");
        check(csv, definition, "point_anchored_pt_pseudolink_uncut_model_km", "REPORTED",
                number(pseudolinks.uncutModelMetres() / 1000.0), "unique model link km",
                "positive MATSim model lengths of distinct used point-anchored pseudolinks");
        check(csv, definition, "point_anchored_pt_pseudolinks_inside", "REPORTED",
                Long.toString(pseudolinks.insideLinks()), "distinct event-used links",
                "anchors strictly inside the Munich polygon; full model length is assigned territorially");
        check(csv, definition, "point_anchored_pt_pseudolink_inside_model_km", "REPORTED",
                number(pseudolinks.insideModelMetres() / 1000.0), "unique model link km",
                "model length of point-anchored pseudolinks classified strictly inside");
        check(csv, definition, "point_anchored_pt_pseudolinks_outside", "REPORTED",
                Long.toString(pseudolinks.outsideLinks()), "distinct event-used links",
                "anchors outside and not covered by the Munich polygon; zero territorial distance is assigned");
        check(csv, definition, "point_anchored_pt_pseudolink_outside_model_km", "REPORTED",
                number(pseudolinks.outsideModelMetres() / 1000.0), "unique model link km",
                "model length of point-anchored pseudolinks classified outside");
        check(csv, definition, "point_anchored_pt_pseudolink_boundary_ambiguous_anchors",
                pseudolinks.boundaryAmbiguousAnchors() == 0 ? "PASS" : "FAIL",
                Long.toString(pseudolinks.boundaryAmbiguousAnchors()), "0",
                "a point anchor covered by but not strictly inside the boundary must fail before publication");
        check(csv, definition, "point_anchored_pt_pseudolink_territorial_service_km",
                "REPORTED", number(pseudolinks.territorialServiceMetres() / 1000.0),
                "full-service vehicle_km",
                "territorial PT service assigned through the documented point-anchor proxy");
        check(csv, definition, "point_anchored_pt_pseudolink_territorial_service_share_percent",
                "REPORTED", number(Production2040AnalysisSpec.percent(
                        pseudolinks.territorialServiceMetres(), territorialPt)),
                "percent of territorial PT Fkm",
                "share of territorial PT service relying on the point-anchor proxy");
        check(csv, definition, "zero_model_length_pt_links_used", "REPORTED",
                Long.toString(pseudolinks.zeroModelLengthLinks()), "distinct event-used links",
                "zero-model-length PT links are reported separately and contribute zero territorial service");
        check(csv, definition, "zero_model_length_pt_link_territorial_service_km",
                Math.abs(pseudolinks.zeroModelLengthTerritorialServiceMetres())
                        <= RECONCILIATION_TOLERANCE_METRES ? "PASS" : "FAIL",
                number(pseudolinks.zeroModelLengthTerritorialServiceMetres() / 1000.0), "0",
                "zero-model-length PT links must not contribute territorial vehicle-kilometres");
        check(csv, definition, "pt_scaling", "PASS", "1.0", "no factor 20",
                "public-transport service is simulated at full service scale");
        check(csv, definition, "annualisation", "REPORTED", "365 multiplier",
                "illustrative only", annualRule());
        return csv.toString();
    }

    private static String report(Production2040AnalysisSpec.ScenarioDefinition definition,
            Production2040AccountingScopes.Index index,
            Production2040AccountingEventMetrics.Result accounting,
            MeasurementDiagnostics diagnostics) {
        return "# " + definition.scenarioId().replace('_', ' ')
                + " accounting scopes\n\n"
                + "## Purpose and accounting scopes\n\nThis controller-free analysis applies one shared method to BAU and Fast Track. `BOTH_INSIDE` contains final selected-plan MATSim main trips whose two main-activity endpoints are covered by the Munich municipal boundary. `MUNICH_RESIDENTS` contains every main trip of a person whose first documented non-stage `home` activity has a finite coordinate covered by that boundary, including trips to or from the surrounding region. "
                + index.personCounts().get(Production2040AccountingScopes.ResidentStatus.UNRESOLVED)
                + " persons have no resolvable documented home and are reported but excluded; residence is never inferred from a trip origin. Stage activities never create main trips.\n\n"
                + "## Demand metrics\n\nModal split and expanded demand trip counts use the complete structural final selected-plan index. Pkm, distance and travel-time statistics use valid standard final `output_trips` records matched by person ID and one-based main-trip number. The standard output-trip measurement is available for "
                + diagnostics.overall().measuredTrips() + " of "
                + diagnostics.overall().structuralTrips() + " structural selected-plan trips ("
                + number(diagnostics.overall().measurementCoveragePercent())
                + "%); valid routed distance/time values are available for "
                + diagnostics.overall().validDistanceTimeTrips() + " trips ("
                + number(diagnostics.overall().validDistanceTimeCoveragePercent())
                + "%). Standard output-trip measurement is not available for every structural selected-plan trip. This is reported neutrally and does not by itself establish a cause. Publication requires at least "
                + minimumCoverage() + " coverage overall and for every applicable demand-scope/main-mode combination. Missing indexed trips by endpoint category are "
                + countMap(diagnostics.missingByEndpointCategory())
                + "; by documented resident status they are "
                + countMap(diagnostics.missingByResidentStatus())
                + ". Every available output row must still match the indexed selected-plan key, main mode and endpoint category exactly; duplicate or extra rows fail validation. Private-demand trip counts, Pkm and car Fkm are sample observations expanded by factor 20; shares, means, medians and travel times are unscaled. Bike-km is a transparent derivative equal to bike Pkm under a one-person-per-bike convention. Walk is reported only as Pkm and never as vehicle-kilometres. The car-Pkm/Fkm ratio is a plausibility ratio, not an occupancy estimate.\n\n"
                + "## Vehicle accounting\n\nPrivate-car Fkm use the final event stream, exclude transit vehicles and share the established MATSim 2025.0 first-/last-link calculation. Each traffic segment is joined to its person and current selected-plan main trip. Endpoint-category totals must reproduce the existing regional private-car Fkm exactly; those earlier regional Fkm remain unchanged but are unsuitable for a `BOTH_INSIDE` external-cost calculation because they include all simulated regional car movement.\n\n"
                + "PT service cannot be assigned uniquely to resident or `BOTH_INSIDE` passengers. It is therefore reported territorially: full links inside Munich count fully, outside links count zero, and crossing links count the fraction of their straight node-to-node segment inside the polygon multiplied by MATSim link length. "
                + accounting.crossingLinkCount() + " distinct event-used PT links cross the boundary. "
                + pseudolinkReport(accounting.ptPseudolinks(), accounting.ptByRouteMode())
                + " Uncut route-mode totals must reproduce the existing regional PT Fkm before territorial classification. PT supply is already full-scale and is not multiplied by 20.\n\n"
                + "## Time interpretation\n\nEvery daily value describes the technical weekday represented by the GTFS/MATSim run. `illustrative_annual_equivalent_365_days` is a mechanically labelled multiplication by 365, not an empirically validated or authoritative annual total. No Controller, QSim, simulation, external-cost calculation or visualization was run by this analyzer.\n";
    }

    static MeasurementDiagnostics measurementDiagnostics(
            Production2040AccountingScopes.Index index, ScopeMeasurements measurements) {
        Map<MunichTripBoundaryFilter.SpatialCategory, Long> missingByEndpoint =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            missingByEndpoint.put(category, 0L);
        }
        Map<Production2040AccountingScopes.ResidentStatus, Long> missingByResident =
                new EnumMap<>(Production2040AccountingScopes.ResidentStatus.class);
        for (var status : Production2040AccountingScopes.ResidentStatus.values()) {
            missingByResident.put(status, 0L);
        }
        index.trips().forEach((key, trip) -> {
            if (!measurements.seenTrips().contains(key)) {
                missingByEndpoint.merge(trip.endpointCategory(), 1L, Long::sum);
                missingByResident.merge(trip.residentStatus(), 1L, Long::sum);
            }
        });

        Map<Production2040AccountingScopes.Scope, Map<String, MeasurementCoverage>> scopes =
                new EnumMap<>(Production2040AccountingScopes.Scope.class);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            Map<String, Long> structural = structuralCounts(index, scope);
            Map<String, MeasurementCoverage> modes = new TreeMap<>();
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                TripMetric metric = measurements.byScope().get(scope).get(mode);
                modes.put(mode, coverage(structural.get(mode), metric.measuredRecords(),
                        metric.validRecords()));
            }
            scopes.put(scope, Map.copyOf(modes));
        }
        return new MeasurementDiagnostics(coverage(index.trips().size(),
                measurements.seenTrips().size(), measurements.validMeasurementTrips().size()),
                Map.copyOf(scopes), Map.copyOf(missingByEndpoint), Map.copyOf(missingByResident));
    }

    private static MeasurementCoverage coverage(long structuralTrips, long measuredTrips,
            long validDistanceTimeTrips) {
        return new MeasurementCoverage(structuralTrips, measuredTrips, validDistanceTimeTrips,
                structuralTrips - measuredTrips, structuralTrips - validDistanceTimeTrips,
                coveragePercent(measuredTrips, structuralTrips),
                coveragePercent(validDistanceTimeTrips, structuralTrips));
    }

    private static double coveragePercent(long measuredTrips, long structuralTrips) {
        return structuralTrips == 0 ? 100.0 : Production2040AnalysisSpec.percent(measuredTrips,
                structuralTrips);
    }

    private static void requireCoverage(String label, long measuredTrips,
            long structuralTrips) {
        double coverage = coveragePercent(measuredTrips, structuralTrips);
        Production2040AnalysisSpec.require(coverage
                        >= Production2040AnalysisSpec.MIN_MEASUREMENT_COVERAGE_PERCENT,
                "Insufficient measurement coverage for " + label + ": " + coverage + "%");
    }

    private static String coverageStatus(double coverage) {
        return coverage >= Production2040AnalysisSpec.MIN_MEASUREMENT_COVERAGE_PERCENT
                ? "PASS" : "FAIL";
    }

    private static String minimumCoverage() {
        return number(Production2040AnalysisSpec.MIN_MEASUREMENT_COVERAGE_PERCENT) + "%";
    }

    private static String countMap(Map<?, Long> counts) {
        StringBuilder text = new StringBuilder();
        counts.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
            if (!text.isEmpty()) text.append(", ");
            text.append(entry.getKey()).append('=').append(entry.getValue());
        });
        return text.toString();
    }

    static Map<String, Long> structuralCounts(Production2040AccountingScopes.Index index,
            Production2040AccountingScopes.Scope scope) {
        Map<String, Long> result = new LinkedHashMap<>();
        Production2040AnalysisSpec.MAIN_MODES.forEach(mode -> result.put(mode, 0L));
        index.trips().values().stream().filter(trip -> trip.included(scope))
                .forEach(trip -> result.merge(trip.mainMode(), 1L, Long::sum));
        return Map.copyOf(result);
    }

    private static Map<String, Production2040AccountingEventMetrics.PtService> groupPt(
            Map<String, Production2040AccountingEventMetrics.PtService> source) {
        Map<String, MutablePtGroup> result = new TreeMap<>();
        source.forEach((mode, value) -> {
            String group = Production2040AnalysisSpec.PT_ROUTE_MODES.contains(mode)
                    ? mode : "ferry/other";
            result.computeIfAbsent(group, ignored -> new MutablePtGroup()).add(value);
        });
        Map<String, Production2040AccountingEventMetrics.PtService> frozen = new TreeMap<>();
        result.forEach((mode, value) -> frozen.put(mode, value.freeze()));
        return Map.copyOf(frozen);
    }

    private static List<Map<String, String>> readCsv(Path file) throws IOException {
        Production2040AnalysisSpec.require(Files.isRegularFile(file),
                "Missing regional reference report " + file);
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            Production2040AnalysisSpec.require(headerLine != null, "Empty CSV " + file);
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                    headerLine, ',');
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                        line, ',');
                Production2040AnalysisSpec.require(fields.size() == header.size(),
                        "Malformed regional reference CSV " + file);
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < header.size(); index++) {
                    row.put(header.get(index), fields.get(index));
                }
                rows.add(Map.copyOf(row));
            }
        }
        return List.copyOf(rows);
    }

    private static void requireScenario(Map<String, String> row,
            Production2040AnalysisSpec.ScenarioDefinition definition, Path file) {
        Production2040AnalysisSpec.require(definition.scenarioId().equals(row.get("scenario_id")),
                "Regional reference belongs to another scenario: " + file);
    }

    private static double parse(Map<String, String> row, String column, Path file) {
        try {
            double value = Double.parseDouble(row.get(column));
            Production2040AnalysisSpec.require(Double.isFinite(value) && value >= 0,
                    "Invalid " + column + " in " + file);
            return value;
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid " + column + " in " + file, error);
        }
    }

    private static void requireClose(double actual, double expected, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(actual)
                        && Double.isFinite(expected)
                        && Math.abs(actual - expected) <= RECONCILIATION_TOLERANCE_METRES,
                label + " does not reconcile: actual=" + actual + " expected=" + expected);
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            Production2040AnalysisSpec.require(result.put(header.get(index), index) == null,
                    "Duplicate output-trips column " + header.get(index));
        }
        return Map.copyOf(result);
    }

    private static int column(Map<String, Integer> columns, String... names) {
        for (String name : names) if (columns.containsKey(name)) return columns.get(name);
        throw new IllegalStateException("Missing output-trips column " + String.join("/", names));
    }

    private static String value(List<String> fields, int index) {
        Production2040AnalysisSpec.require(index >= 0 && index < fields.size(),
                "Short output-trips row");
        return fields.get(index).trim();
    }

    private static Coord coordinate(List<String> fields, int x, int y) {
        try {
            return new Coord(Double.parseDouble(value(fields, x)),
                    Double.parseDouble(value(fields, y)));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static double parseNonNegative(List<String> fields, int index) {
        try {
            double value = Double.parseDouble(value(fields, index));
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (RuntimeException error) {
            return Double.NaN;
        }
    }

    private static double parseTime(List<String> fields, int index) {
        try {
            double value = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseTime(
                    value(fields, index));
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (RuntimeException error) {
            return Double.NaN;
        }
    }

    private static boolean isValidTripMeasurement(double metres, double seconds) {
        return Double.isFinite(metres) && metres >= 0 && Double.isFinite(seconds)
                && seconds >= 0;
    }

    private static BufferedReader reader(Path file) throws IOException {
        var input = Files.newInputStream(file);
        return new BufferedReader(new InputStreamReader(
                file.getFileName().toString().endsWith(".gz")
                        ? new GZIPInputStream(input) : input, StandardCharsets.UTF_8));
    }

    private static void publishAtomically(Path parent, Path destination,
            Map<String, String> reports) throws IOException {
        Production2040AnalysisSpec.require(Files.isDirectory(parent),
                "Missing existing production analysis directory " + parent);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "Accounting-scope analysis already exists and will not be overwritten");
        Path temporary = parent.resolve(".accounting-scopes-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            for (var entry : reports.entrySet()) {
                Production2040AnalysisSpec.require(!entry.getKey().contains("/")
                                && !entry.getKey().contains("\\"),
                        "Invalid accounting report filename " + entry.getKey());
                Files.writeString(temporary.resolve(entry.getKey()), entry.getValue(),
                        StandardCharsets.UTF_8);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination);
            }
        } catch (IOException | RuntimeException error) {
            deleteTemporary(temporary, parent);
            throw error;
        }
    }

    private static void deleteTemporary(Path temporary, Path parent) {
        if (!temporary.normalize().getParent().equals(parent.normalize())
                || !temporary.getFileName().toString().startsWith(".accounting-scopes-tmp-")) {
            throw new IllegalStateException("Refusing to clean unexpected temporary path "
                    + temporary);
        }
        if (!Files.exists(temporary)) return;
        try (var paths = Files.walk(temporary)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        } catch (IOException | java.io.UncheckedIOException error) {
            throw new IllegalStateException("Could not clean temporary accounting output "
                    + temporary, error);
        }
    }

    private static void check(StringBuilder csv,
            Production2040AnalysisSpec.ScenarioDefinition definition, String id, String status,
            String observed, String expected, String explanation) {
        csv.append(definition.scenarioId()).append(",0.05,check,").append(id).append(',')
                .append(status).append(',').append(quote(observed)).append(',')
                .append(quote(expected)).append(',').append(quote(explanation)).append('\n');
    }

    private static String scopeDefinition(Production2040AccountingScopes.Scope scope) {
        return scope == Production2040AccountingScopes.Scope.BOTH_INSIDE
                ? "selected-plan MATSim main trips with both main-activity endpoints covered by Munich boundary; stage activities excluded"
                : "all selected-plan MATSim main trips of persons with a documented covered home activity; stage activities excluded";
    }

    private static String annualRule() {
        return "technical weekday multiplied by 365 as illustrative_annual_equivalent_365_days; not empirically validated annualisation";
    }

    private static String ptDefinition() {
        return "full-service PT event distance within Munich; ordinary links use geometric line clipping (full inside, zero outside, crossing-link MATSim length multiplied by exact inside fraction); identical-endpoint PT pseudolinks use a strict point-anchor proxy and are reported separately; no factor 20";
    }

    private static String notApplicablePseudolinkColumns() {
        return ",NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,"
                + "NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,"
                + "NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE";
    }

    private static String pseudolinkReport(
            Production2040AccountingEventMetrics.PtPseudolinkSummary pseudolinks,
            Map<String, Production2040AccountingEventMetrics.PtService> byMode) {
        double territorialPt = byMode.values().stream()
                .mapToDouble(Production2040AccountingEventMetrics.PtService::territorialMetres)
                .sum();
        return pseudolinks.usedPointAnchoredLinks()
                + " distinct used positive-model-length PT pseudolinks have identical endpoint coordinates and therefore no line geometry. They are not geometrically clipped: their anchor is classified strictly inside ("
                + pseudolinks.insideLinks() + ", "
                + number(pseudolinks.insideModelMetres() / 1000.0)
                + " model km) or outside (" + pseudolinks.outsideLinks() + ", "
                + number(pseudolinks.outsideModelMetres() / 1000.0)
                + " model km); a boundary anchor would fail before publication (reported count "
                + pseudolinks.boundaryAmbiguousAnchors() + "). Their total uncut model length is "
                + number(pseudolinks.uncutModelMetres() / 1000.0)
                + " km, and their point-anchor proxy assigns "
                + number(pseudolinks.territorialServiceMetres() / 1000.0)
                + " territorial service km ("
                + number(Production2040AnalysisSpec.percent(
                        pseudolinks.territorialServiceMetres(), territorialPt))
                + "% of territorial PT Fkm). " + pseudolinks.zeroModelLengthLinks()
                + " distinct used zero-model-length PT links are separately reported and contribute "
                + number(pseudolinks.zeroModelLengthTerritorialServiceMetres() / 1000.0)
                + " territorial service km.";
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static String number(double value) {
        Production2040AnalysisSpec.require(Double.isFinite(value),
                "Non-finite accounting report value");
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static Production2040VehicleMetrics.PtMetric zeroPt() {
        return new Production2040VehicleMetrics.PtMetric(0, 0, 0, 0, 0, 0, 0);
    }

    private static Production2040AccountingEventMetrics.PtService zeroPtService() {
        return new Production2040AccountingEventMetrics.PtService(0, 0, 0, 0, 0);
    }

    record ScopeMeasurements(long totalRows,
                             Set<Production2040AccountingScopes.TripKey> seenTrips,
                             Set<Production2040AccountingScopes.TripKey> validMeasurementTrips,
                             Map<Production2040AccountingScopes.Scope,
                                     Map<String, TripMetric>> byScope) { }

    record MeasurementDiagnostics(MeasurementCoverage overall,
                                  Map<Production2040AccountingScopes.Scope,
                                          Map<String, MeasurementCoverage>> byScopeAndMode,
                                  Map<MunichTripBoundaryFilter.SpatialCategory, Long>
                                          missingByEndpointCategory,
                                  Map<Production2040AccountingScopes.ResidentStatus, Long>
                                          missingByResidentStatus) { }

    record MeasurementCoverage(long structuralTrips, long measuredTrips,
                               long validDistanceTimeTrips, long missingStructuralTrips,
                               long missingOrInvalidDistanceTimeTrips,
                               double measurementCoveragePercent,
                               double validDistanceTimeCoveragePercent) { }

    record TripMetric(long validRecords, long invalidRecords, double distanceMetres,
                      double travelTimeSeconds, List<Double> distancesMetres) {
        long measuredRecords() {
            return validRecords + invalidRecords;
        }
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
                    : (distancesMetres.get(size / 2 - 1)
                    + distancesMetres.get(size / 2)) / 2.0;
        }
    }

    record RegionalReferences(double regionalCarMetres,
                              Map<String, Double> regionalPtMetres) { }

    private static final class MutableTripMetric {
        private long valid;
        private long invalid;
        private double distance;
        private double time;
        private final List<Double> distances = new ArrayList<>();

        private void add(double metres, double seconds) {
            if (!isValidTripMeasurement(metres, seconds)) {
                invalid++;
                return;
            }
            valid++;
            distance += metres;
            time += seconds;
            distances.add(metres);
        }

        private TripMetric freeze() {
            distances.sort(Comparator.naturalOrder());
            return new TripMetric(valid, invalid, distance, time, List.copyOf(distances));
        }
    }

    private static final class MutablePtGroup {
        private double uncut;
        private double territorial;
        private long crossingLinks;
        private double crossingModel;
        private double crossingService;

        private void add(Production2040AccountingEventMetrics.PtService value) {
            uncut += value.uncutMetres();
            territorial += value.territorialMetres();
            crossingLinks += value.crossingLinkCount();
            crossingModel += value.crossingLinkModelMetres();
            crossingService += value.crossingServiceMetres();
        }

        private Production2040AccountingEventMetrics.PtService freeze() {
            return new Production2040AccountingEventMetrics.PtService(uncut, territorial,
                    crossingLinks, crossingModel, crossingService);
        }
    }
}
