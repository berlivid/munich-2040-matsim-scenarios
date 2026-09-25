package org.matsim.project.prepare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;

/**
 * Read-only territorial transport-performance input table for one completed
 * 2040 production output. It never constructs a Controller or QSim.
 */
public final class AnalyzeProduction2040TerritorialCostInputs {
    static final String SUBDIRECTORY = "territorial_cost_inputs";
    static final int DAYS_PER_YEAR = 365;
    static final double CAR_ASSUMED_OCCUPANCY = 1.5;
    static final double TOLERANCE_METRES =
            AnalyzeProduction2040AccountingScopes.RECONCILIATION_TOLERANCE_METRES;
    /*
     * The territorial observer accumulates each movement globally, while the
     * established regional metric accumulates per vehicle and then totals the
     * vehicles. They therefore retain the same movements but not necessarily
     * the same floating-point addition order. Keep millimetre strictness for
     * small totals and allow 1e-11 of the magnitude for large totals: at the
     * reported BAU total this is about 0.032 m, so a one-metre discrepancy
     * still fails.
     */
    static final double ACCUMULATED_DISTANCE_ABSOLUTE_TOLERANCE_METRES = 1e-3;
    static final double ACCUMULATED_DISTANCE_RELATIVE_TOLERANCE = 1e-11;
    static final int INPUT_HEADER_ROW = 4;
    static final int INPUT_DATA_START_ROW = INPUT_HEADER_ROW + 1;
    static final List<String> PRINCIPAL_PT_MODES =
            List.of("bus", "tram", "subway", "rail");
    static final String PT_OCCUPANCY_REPORTED_FOR_REVIEW = "PASS_REPORTED_FOR_REVIEW";
    static final String PT_OCCUPANCY_VALID_ZERO_PASSENGER =
            "VALID_ZERO_PASSENGER_ACTIVITY";
    static final String PT_OCCUPANCY_NOT_APPLICABLE_ZERO_ACTIVITY =
            "NOT_APPLICABLE_VALID_ZERO_ACTIVITY";

    private AnalyzeProduction2040TerritorialCostInputs() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: AnalyzeProduction2040TerritorialCostInputs BAU|FAST_TRACK");
        analyze(args[0]);
    }

    static void analyze(String scenarioArgument) throws Exception {
        var definition = Production2040AnalysisSpec.scenario(scenarioArgument);
        Path destination = definition.analysisDirectory().resolve(SUBDIRECTORY);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "Territorial cost-input analysis already exists and will not be overwritten: "
                        + Production2040Contract.projectPath(destination));

        var files = ValidateProduction2040AnalysisOutput.validatePublished(definition);
        requireFinalRootEvents(definition, files);
        Map<String, String> accountingReports =
                ValidateProduction2040AccountingScopes.validatePublished(definition);
        Map<String, String> ptReports =
                ValidateProduction2040PtCostAllocation.validatePublished(definition);
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        ActiveRoutingDefinition routing = readActiveRouting(files.config());
        ValidatedPtInputs ptInputs = readValidatedPtInputs(definition, ptReports,
                accountingReports);

        TerritorialActiveModeMetrics active = TerritorialActiveModeMetrics.read(files.plans(),
                boundary);
        Scenario eventScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(eventScenario.getNetwork()).readFile(files.network().toString());
        new TransitScheduleReader(eventScenario).readFile(files.schedule().toString());
        new MatsimVehicleReader(eventScenario.getTransitVehicles()).readFile(
                files.vehicles().toString());
        TerritorialCarMetrics car = new TerritorialCarMetrics(eventScenario.getNetwork(), boundary);
        var movement = new Production2040VehicleMetrics(eventScenario.getNetwork(),
                eventScenario.getTransitSchedule(), eventScenario.getTransitVehicles(), Map.of(),
                car);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(movement);
        manager.addHandler(active);
        new MatsimEventsReader(manager).readFile(files.events().toString());

        TerritorialCarResult carResult = car.result();
        ActiveModeResultSet activeResult = active.result();
        validateCarMetrics(carResult, movement.result());
        validateActiveMetrics(activeResult);
        validatePtInputs(ptInputs);
        WorkbookData workbook = buildWorkbookData(definition, files, routing, carResult,
                movement.result(), activeResult, ptInputs, boundary);
        validateWorkbookData(workbook);

        Production2040AnalysisSpec.require(files.protectedInputSnapshot().equals(
                        Production2040Contract.protectedInputSnapshot(
                                Production2040Contract.loadAndValidate())),
                "A protected input changed during territorial cost-input analysis");
        Production2040AnalysisSpec.require(accountingReports.equals(
                        ValidateProduction2040AccountingScopes.validatePublished(definition)),
                "The territorial PT service source changed during territorial cost-input analysis");
        Production2040AnalysisSpec.require(ptReports.equals(
                        ValidateProduction2040PtCostAllocation.validatePublished(definition)),
                "The validated PT cost-allocation source changed during territorial cost-input analysis");
        publishAtomically(definition.analysisDirectory(), destination, workbook);
        System.out.printf(Locale.ROOT,
                "2040 TERRITORIAL COST-INPUT ANALYSIS PASS%nscenario=%s output=%s%n"
                        + "No Controller or QSim was started.%n",
                definition.scenarioId(), Production2040Contract.projectPath(destination));
    }

    static String workbookFileName(Production2040AnalysisSpec.ScenarioDefinition definition) {
        return "BAU_2040".equals(definition.scenarioId())
                ? "BAU_2040_territorial_cost_inputs.xlsx"
                : "Fast_Track_2040_territorial_cost_inputs.xlsx";
    }

    private static void requireFinalRootEvents(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files) {
        Path expected = definition.outputDirectory().resolve(
                definition.contract().runId() + ".output_events.xml.gz").toAbsolutePath()
                .normalize();
        Production2040AnalysisSpec.require(files.events().toAbsolutePath().normalize()
                        .equals(expected),
                "Territorial analysis must use the one root final event file, not an iteration copy");
        Production2040AnalysisSpec.require(!files.events().toString().replace('\\', '/')
                        .contains("/ITERS/"),
                "Territorial analysis must not read an iteration events file");
    }

    static ActiveRoutingDefinition readActiveRouting(Path outputConfig) {
        Config config = ConfigUtils.loadConfig(outputConfig.toString());
        Set<String> networkModes = Set.copyOf(config.routing().getNetworkModes());
        var walk = config.routing().getTeleportedModeParams().get("walk");
        var bike = config.routing().getTeleportedModeParams().get("bike");
        var nonNetworkWalk = config.routing().getTeleportedModeParams().get("non_network_walk");
        Production2040AnalysisSpec.require(walk != null && bike != null && nonNetworkWalk != null,
                "Final output config lacks teleported walk, bike, or non_network_walk parameters");
        Production2040AnalysisSpec.require(!networkModes.contains("walk")
                        && !networkModes.contains("bike")
                        && !networkModes.contains("non_network_walk"),
                "Walk and bike must remain teleported rather than network-routed");
        return new ActiveRoutingDefinition(networkModes, walk.getBeelineDistanceFactor(),
                bike.getBeelineDistanceFactor(), nonNetworkWalk.getBeelineDistanceFactor());
    }

    static ValidatedPtInputs readValidatedPtInputs(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            Map<String, String> ptReports, Map<String, String> accountingReports) {
        String source = ptReports.get("pt_external_cost_inputs.csv");
        Production2040AnalysisSpec.require(source != null,
                "Validated PT cost-allocation package lacks pt_external_cost_inputs.csv");
        Map<String, PtModeInput> byMode = new TreeMap<>();
        for (Map<String, String> row : csvRows(source, "pt_external_cost_inputs.csv")) {
            requireScenario(row, definition, "pt_external_cost_inputs.csv");
            Production2040AnalysisSpec.require("person_km_vehicle_km_and_ratio".equals(
                            required(row, "unit", "pt_external_cost_inputs.csv")),
                    "Validated PT source has an unexpected unit declaration");
            requireClose(parse(row, "demand_sample_factor", "pt_external_cost_inputs.csv"),
                    Production2040AnalysisSpec.SAMPLE_FACTOR,
                    "PT source demand sample factor");
            Production2040AnalysisSpec.require("technical_weekday".equals(required(row,
                            "day_basis", "pt_external_cost_inputs.csv")),
                    "Validated PT source has an unexpected reporting-day convention");
            String mode = required(row, "pt_route_mode", "pt_external_cost_inputs.csv");
            double samplePkm = parse(row, "territorial_all_passenger_sample_pkm_per_day",
                    "pt_external_cost_inputs.csv");
            double expandedPkm = parse(row, "territorial_all_passenger_expanded_pkm_per_day",
                    "pt_external_cost_inputs.csv");
            double fkm = parse(row, "territorial_full_service_fkm_per_day",
                    "pt_external_cost_inputs.csv");
            double demandExpansion = parse(row, "demand_expansion_factor",
                    "pt_external_cost_inputs.csv");
            double supplyExpansion = parse(row, "supply_expansion_factor",
                    "pt_external_cost_inputs.csv");
            Double occupancy = optionalNumber(row,
                    "average_occupancy_expanded_pkm_per_full_service_fkm",
                    "pt_external_cost_inputs.csv");
            Set<String> components = splitComponents(required(row,
                    "component_normalized_route_modes", "pt_external_cost_inputs.csv"));
            String occupancyStatus = required(row, "occupancy_status",
                    "pt_external_cost_inputs.csv");
            String quality = required(row, "data_coverage_status",
                    "pt_external_cost_inputs.csv");
            Production2040AnalysisSpec.require(quality.startsWith("PASS"),
                    "Validated PT source has non-PASS coverage status for " + mode);
            PtModeInput input = new PtModeInput(mode, components, samplePkm, expandedPkm, fkm,
                    occupancy, occupancyStatus, demandExpansion, supplyExpansion, quality);
            validatePtOccupancyStatus(input);
            Production2040AnalysisSpec.require(byMode.put(mode, input) == null,
                    "Duplicate PT route-mode input " + mode);
        }
        for (String mode : PRINCIPAL_PT_MODES) {
            Production2040AnalysisSpec.require(byMode.containsKey(mode),
                    "Validated PT source lacks principal mode " + mode);
        }

        String territorialService = accountingReports.get(
                "final_territorial_pt_fkm_by_route_mode.csv");
        Production2040AnalysisSpec.require(territorialService != null,
                "Validated accounting package lacks territorial PT service CSV");
        Map<String, Double> serviceByMode = new TreeMap<>();
        Map<String, String> total = null;
        for (Map<String, String> row : csvRows(territorialService,
                "final_territorial_pt_fkm_by_route_mode.csv")) {
            requireScenario(row, definition, "final_territorial_pt_fkm_by_route_mode.csv");
            String mode = required(row, "pt_route_mode",
                    "final_territorial_pt_fkm_by_route_mode.csv");
            if ("TOTAL".equals(mode)) {
                Production2040AnalysisSpec.require(total == null,
                        "Validated territorial PT service CSV has duplicate TOTAL rows");
                total = row;
                continue;
            }
            Production2040AnalysisSpec.require("TERRITORIAL_PT_SERVICE".equals(required(row,
                            "scope_id", "final_territorial_pt_fkm_by_route_mode.csv"))
                            && "vehicle_km".equals(required(row, "unit",
                            "final_territorial_pt_fkm_by_route_mode.csv")),
                    "Validated territorial PT service row has an unexpected scope or unit");
            requireClose(parse(row, "sample_factor",
                            "final_territorial_pt_fkm_by_route_mode.csv"), 1.0,
                    "Validated territorial PT service scale for " + mode);
            Production2040AnalysisSpec.require("NOT_APPLICABLE".equals(required(row,
                            "factor_20_daily_vehicle_km",
                            "final_territorial_pt_fkm_by_route_mode.csv")),
                    "Validated territorial PT service has a factor-20 value for " + mode);
            Production2040AnalysisSpec.require(serviceByMode.put(mode, parse(row,
                            "full_service_daily_vehicle_km",
                            "final_territorial_pt_fkm_by_route_mode.csv")) == null,
                    "Duplicate territorial PT service mode " + mode);
        }
        Production2040AnalysisSpec.require(total != null,
                "Validated territorial PT service CSV lacks TOTAL row");
        requireScenario(total, definition, "final_territorial_pt_fkm_by_route_mode.csv");
        Production2040AnalysisSpec.require(serviceByMode.keySet().equals(byMode.keySet()),
                "PT transfer modes differ from the validated territorial PT-service modes");
        for (var entry : byMode.entrySet()) {
            requireClose(entry.getValue().fullServiceFkm(), serviceByMode.get(entry.getKey()),
                    "PT full-service Fkm reconciliation for " + entry.getKey());
        }
        requireClose(parse(total, "full_service_daily_vehicle_km",
                        "final_territorial_pt_fkm_by_route_mode.csv"),
                serviceByMode.values().stream().mapToDouble(Double::doubleValue).sum(),
                "PT total full-service Fkm reconciliation");
        PseudolinkDiagnostics pseudolinks = new PseudolinkDiagnostics(
                parseLong(total, "point_anchored_pseudolink_used_link_count",
                        "final_territorial_pt_fkm_by_route_mode.csv"),
                parse(total, "point_anchored_pseudolink_territorial_service_km",
                        "final_territorial_pt_fkm_by_route_mode.csv"),
                parse(total, "point_anchored_pseudolink_territorial_service_share_percent",
                        "final_territorial_pt_fkm_by_route_mode.csv"),
                parseLong(total, "zero_model_length_pt_link_count",
                        "final_territorial_pt_fkm_by_route_mode.csv"));
        return new ValidatedPtInputs(Map.copyOf(byMode), pseudolinks);
    }

    static void validatePtInputs(ValidatedPtInputs inputs) {
        for (PtModeInput input : inputs.byMode().values()) {
            requireFiniteNonNegative(input.samplePkm(), "PT sample Pkm for " + input.mode());
            requireFiniteNonNegative(input.expandedPkm(), "PT expanded Pkm for " + input.mode());
            requireFiniteNonNegative(input.fullServiceFkm(), "PT full-service Fkm for "
                    + input.mode());
            requireClose(input.expandedPkm(), Production2040AnalysisSpec.expanded(
                    input.samplePkm()), "PT passenger expansion exactly once for " + input.mode());
            requireClose(input.demandExpansion(), Production2040AnalysisSpec.EXPANSION_FACTOR,
                    "PT demand expansion factor for " + input.mode());
            requireClose(input.supplyExpansion(), 1.0,
                    "PT supply expansion factor for " + input.mode());
            validatePtOccupancyStatus(input);
            if (input.fullServiceFkm() == 0.0) {
                Production2040AnalysisSpec.require(input.expandedPkm() == 0.0
                                && input.occupancy() == null,
                        "Zero PT supply must be an explicit zero-activity occupancy case for "
                                + input.mode());
            } else {
                Production2040AnalysisSpec.require(input.occupancy() != null,
                        "Positive PT supply lacks occupancy for " + input.mode());
                requireClose(input.occupancy(), input.expandedPkm() / input.fullServiceFkm(),
                        "PT occupancy for " + input.mode());
            }
        }
        requireFiniteNonNegative(inputs.pseudolinks().territorialServiceKm(),
                "PT pseudolink territorial service");
        Production2040AnalysisSpec.require(inputs.pseudolinks().territorialServiceSharePercent()
                        >= 0.0 && inputs.pseudolinks().territorialServiceSharePercent() <= 100.0,
                "PT pseudolink territorial-service share is outside 0..100 percent");
    }

    private static void validatePtOccupancyStatus(PtModeInput input) {
        switch (input.occupancyStatus()) {
            case PT_OCCUPANCY_REPORTED_FOR_REVIEW ->
                    Production2040AnalysisSpec.require(input.fullServiceFkm() > 0.0
                                    && input.samplePkm() > 0.0 && input.expandedPkm() > 0.0
                                    && input.occupancy() != null && input.occupancy() > 0.0,
                            "PT review occupancy status is inconsistent with positive passenger "
                                    + "activity for " + input.mode());
            case PT_OCCUPANCY_VALID_ZERO_PASSENGER ->
                    Production2040AnalysisSpec.require(input.fullServiceFkm() > 0.0
                                    && input.samplePkm() == 0.0 && input.expandedPkm() == 0.0
                                    && input.occupancy() != null && input.occupancy() == 0.0,
                            "PT zero-passenger status is inconsistent with demand, supply, or "
                                    + "occupancy for " + input.mode());
            case PT_OCCUPANCY_NOT_APPLICABLE_ZERO_ACTIVITY ->
                    Production2040AnalysisSpec.require(input.fullServiceFkm() == 0.0
                                    && input.samplePkm() == 0.0 && input.expandedPkm() == 0.0
                                    && input.occupancy() == null,
                            "PT zero-activity status is inconsistent with demand, supply, or "
                                    + "occupancy for " + input.mode());
            default -> throw new IllegalStateException("Unknown PT occupancy status for "
                    + input.mode() + ": " + input.occupancyStatus());
        }
    }

    static String ptWorkbookQualityStatus(PtModeInput input) {
        return input.occupancyStatus() + "; PASS_VALIDATED_PT_ACCOUNTING; "
                + input.qualityStatus() + "; REQUIRES_EXTERNAL_UNIT_MAPPING";
    }

    static void validateCarMetrics(TerritorialCarResult car,
            Production2040VehicleMetrics.Result regional) {
        requireFiniteNonNegative(car.uncutMetres(), "territorial car uncut movement");
        requireFiniteNonNegative(car.territorialMetres(), "territorial car movement");
        Production2040AnalysisSpec.require(car.territorialMetres()
                        <= car.uncutMetres() + TOLERANCE_METRES,
                "Territorial car movement exceeds uncut car movement");
        requireAccumulatedDistanceCloseMetres(car.uncutMetres(), regional.carMetres(),
                "territorial car uncut event reconciliation with regional car Fkm");
        Production2040AnalysisSpec.require(regional.missingLinks() == 0,
                "Car event stream contains a missing network link");
        Production2040AnalysisSpec.require(car.unclassifiedMovementEvents() == 0,
                "Car event stream contains non-transit movements without VehicleEntersTraffic classification");
        Production2040AnalysisSpec.require(car.repeatedTrafficEnters() == 0
                        && car.unmatchedTrafficLeaves() == 0,
                "Car event stream contains duplicated or unmatched traffic lifecycle events");
        for (VehicleCategory category : car.otherNetworkVehicleCategories().values()) {
            requireFiniteNonNegative(category.uncutMetres(), "additional vehicle category");
            requireFiniteNonNegative(category.territorialMetres(),
                    "additional territorial vehicle category");
            Production2040AnalysisSpec.require(category.territorialMetres()
                            <= category.uncutMetres() + TOLERANCE_METRES,
                    "Additional territorial vehicle category exceeds its uncut movement");
        }
    }

    static void validateActiveMetrics(ActiveModeResultSet active) {
        Production2040AnalysisSpec.require(active.departureSequenceMismatches() == 0
                        && active.unmatchedActiveDepartures() == 0
                        && active.unmatchedActiveArrivals() == 0,
                "Active-mode departure/arrival evidence does not match final selected-plan legs");
        validateActiveMetric(active.walk(), "walk");
        validateActiveMetric(active.bike(), "bike");
        requireCloseMetres(active.walk().territorialMetres(), active.walkMainModeMetres()
                        + active.walkStageMetres(),
                "walk main-mode and stage-distance partition");
    }

    private static void validateActiveMetric(ActiveModeMetric metric, String mode) {
        requireFiniteNonNegative(metric.territorialMetres(), "territorial " + mode + " distance");
        requireFiniteNonNegative(metric.observedModelledMetres(), "observed " + mode
                + " modelled distance");
        Production2040AnalysisSpec.require(metric.territorialMetres()
                        <= metric.observedModelledMetres() + TOLERANCE_METRES,
                "Territorial " + mode + " distance exceeds observed modelled distance");
        Production2040AnalysisSpec.require(metric.validSpatialDistanceLegs()
                        <= metric.completedLegs()
                        && metric.completedLegs() <= metric.plannedLegs(),
                "Invalid active-mode coverage counts for " + mode);
        Production2040AnalysisSpec.require(metric.stuckIncompleteLegs()
                        <= metric.incompleteLegs(),
                "Stuck active-mode legs exceed incomplete legs for " + mode);
    }

    static WorkbookData buildWorkbookData(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files,
            ActiveRoutingDefinition routing, TerritorialCarResult car,
            Production2040VehicleMetrics.Result regional, ActiveModeResultSet active,
            ValidatedPtInputs ptInputs, MunichMunicipalBoundary boundary) {
        double carSampleFkm = car.territorialMetres() / 1000.0;
        double carExpandedFkm = Production2040AnalysisSpec.expanded(carSampleFkm);
        double carExpandedPkm = carExpandedFkm * CAR_ASSUMED_OCCUPANCY;
        double walkExpandedPkm = Production2040AnalysisSpec.expanded(
                active.walk().territorialMetres() / 1000.0);
        double bikeExpandedPkm = Production2040AnalysisSpec.expanded(
                active.bike().territorialMetres() / 1000.0);
        requireClose(carExpandedFkm, carSampleFkm * Production2040AnalysisSpec.EXPANSION_FACTOR,
                "territorial car Fkm expansion exactly once");
        requireClose(carExpandedPkm, carExpandedFkm * CAR_ASSUMED_OCCUPANCY,
                "territorial car Pkm derived occupancy");
        requireClose(walkExpandedPkm, active.walk().territorialMetres() / 1000.0
                        * Production2040AnalysisSpec.EXPANSION_FACTOR,
                "territorial walk expansion exactly once");
        requireClose(bikeExpandedPkm, active.bike().territorialMetres() / 1000.0
                        * Production2040AnalysisSpec.EXPANSION_FACTOR,
                "territorial bike expansion exactly once");
        List<InputRow> rows = new ArrayList<>();
        rows.add(row("Car", carExpandedPkm, carExpandedFkm, CAR_ASSUMED_OCCUPANCY,
                "private-car vehicle-km", "Final event car movement clipped to Munich; Pkm is Fkm × assumed 1.5 persons/car",
                carQuality(car)));
        rows.add(row("Walk", walkExpandedPkm, null, null, "walk person-km",
                "Completed teleported walk legs: final modeled leg distance × endpoint-line fraction inside Munich",
                activeQuality(active.walk())));
        rows.add(row("Bike", bikeExpandedPkm, bikeExpandedPkm, 1.0,
                "bike-km under one-person-per-bike assumption",
                "Completed teleported bike legs: final modeled leg distance × endpoint-line fraction; Fkm derives from one person/bike",
                activeQuality(active.bike())));

        List<PtModeInput> ptComponents = new ArrayList<>();
        for (String mode : PRINCIPAL_PT_MODES) ptComponents.add(ptInputs.byMode().get(mode));
        ptInputs.byMode().values().stream().filter(value -> !PRINCIPAL_PT_MODES.contains(
                        value.mode()))
                .filter(value -> value.expandedPkm() > 0.0 || value.fullServiceFkm() > 0.0)
                .sorted(Comparator.comparing(PtModeInput::mode)).forEach(ptComponents::add);
        for (PtModeInput input : ptComponents) {
            String label = PRINCIPAL_PT_MODES.contains(input.mode())
                    ? title(input.mode()) : "Other PT (" + String.join(", ",
                    new TreeSet<>(input.components())) + ")";
            rows.add(row(label, input.expandedPkm(), input.fullServiceFkm(), input.occupancy(),
                    "MATSim transit vehicle trajectory km",
                    "Validated territorial all-passenger in-vehicle Pkm and validated full-service territorial Fkm; no PT allocation used",
                    ptWorkbookQualityStatus(input)));
        }
        double ptPkm = ptComponents.stream().mapToDouble(PtModeInput::expandedPkm).sum();
        double ptFkm = ptComponents.stream().mapToDouble(PtModeInput::fullServiceFkm).sum();
        rows.add(row("PT total (subtotal, mixed vehicle units)", ptPkm, ptFkm, null,
                "mixed MATSim transit vehicle trajectory km",
                "Subtotal of the PT component rows only; do not replace mode-specific occupancy for costing",
                "SUBTOTAL_MIXED_VEHICLE_UNITS"));
        double allPkm = carExpandedPkm + walkExpandedPkm + bikeExpandedPkm + ptPkm;
        rows.add(row("All-mode Pkm total (PT subtotal excluded)", allPkm, null, null,
                "person-km; no all-mode Fkm", "Car + Walk + Bike + each PT component; PT subtotal is not added again",
                "TOTAL_NO_PT_DOUBLE_COUNT"));

        List<DefinitionRow> definitions = definitions(definition, files, routing, car, regional,
                active, ptInputs, boundary, carExpandedFkm, carExpandedPkm, walkExpandedPkm,
                bikeExpandedPkm);
        return new WorkbookData(definition.scenarioId(), workbookFileName(definition),
                List.copyOf(rows), List.copyOf(definitions));
    }

    private static InputRow row(String mode, double pkm, Double fkm, Double occupancy,
            String unit, String basis, String quality) {
        return new InputRow(mode, pkm, fkm, pkm * DAYS_PER_YEAR / 1_000_000.0,
                fkm == null ? null : fkm * DAYS_PER_YEAR / 1_000_000.0, occupancy, unit,
                basis, quality);
    }

    private static List<DefinitionRow> definitions(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files,
            ActiveRoutingDefinition routing, TerritorialCarResult car,
            Production2040VehicleMetrics.Result regional, ActiveModeResultSet active,
            ValidatedPtInputs ptInputs, MunichMunicipalBoundary boundary, double carExpandedFkm,
            double carExpandedPkm, double walkExpandedPkm, double bikeExpandedPkm) {
        List<DefinitionRow> rows = new ArrayList<>();
        rows.add(def("Scenario ID", definition.scenarioId(), "Shared BAU/Fast Track implementation"));
        rows.add(def("Production run ID", definition.contract().runId(),
                "Validated exact production identity"));
        rows.add(def("Final iteration", Production2040AnalysisSpec.LAST_ITERATION,
                "Only final root production files are used"));
        rows.add(def("Final event source", Production2040Contract.projectPath(files.events()),
                "Read once; root final event file only, never an ITERS copy"));
        rows.add(def("Final plans source", Production2040Contract.projectPath(files.plans()),
                "Selected-plan leg endpoints and modeled active-leg distances"));
        rows.add(def("Final network source", Production2040Contract.projectPath(files.network()),
                "Used for final-event car movement and territorial clipping"));
        rows.add(def("Final schedule / vehicle sources",
                Production2040Contract.projectPath(files.schedule()) + " | "
                        + Production2040Contract.projectPath(files.vehicles()),
                "Used only by the established final-event vehicle classification"));
        rows.add(def("Production-output gate", "PASS", "Normal shutdown, run ID, config, final files and existing production analysis validated"));
        rows.add(def("Municipal boundary", boundary.source().toString().replace('\\', '/'),
                "City of Munich in " + boundary.crs() + "; boundary points use covers"));
        rows.add(def("Municipal boundary canonical SHA-256", Production2040AnalysisSpec.BOUNDARY_HASH,
                "Canonical UTF-8/LF hash required and validated by the production contract"));
        rows.add(def("Territorial rule", "Distance physically inside Munich only",
                "No BOTH_INSIDE, residence, home-activity, origin or destination filter is applied"));
        rows.add(def("Demand scaling", "5% sample × 20", "Car Pkm/Fkm and Walk/Bike Pkm are expanded exactly once; do not apply another factor 20"));
        rows.add(def("PT service scaling", "Full service × 1", "Validated PT Fkm is already full-service supply and is never multiplied by 20"));
        rows.add(def("Car Pkm occupancy assumption", CAR_ASSUMED_OCCUPANCY,
                "Derived Pkm only; occupancy is assumed, not observed passenger movement"));
        rows.add(def("Bike Fkm convention", 1.0,
                "Derived bike Fkm equals bike Pkm under the explicit one-person-per-bike assumption"));
        rows.add(def("Walk Fkm / occupancy", "NOT_APPLICABLE",
                "Walking is reported as person-km only"));
        rows.add(def("Reporting-day and annualization", "Technical reporting day; daily × 365 / 1,000,000",
                "Mechanical 365-day equivalent, not observed annual traffic"));
        rows.add(def("Simulation-horizon qualification", "Final event horizon can extend beyond 24 hours",
                "The final event file is one simulated reporting day, not independent daily observations"));
        rows.add(def("Active-mode routing check", "network modes=" + String.join(",", routing.networkModes()),
                "Walk, bike and non_network_walk are teleported in the actual final output config"));
        rows.add(def("Active-mode spatial approximation",
                "modeled leg distance × straight endpoint segment inside fraction",
                "No second beeline factor: modeled leg distance already carries the routing convention"));
        rows.add(def("Walk beeline factors", routing.walkBeelineFactor(),
                "walk=" + plain(routing.walkBeelineFactor()) + ", bike="
                        + plain(routing.bikeBeelineFactor()) + ", non_network_walk="
                        + plain(routing.nonNetworkWalkBeelineFactor())));
        rows.add(def("Walk main-mode trips, expanded Pkm", active.walkMainModeMetres() / 1000.0
                * Production2040AnalysisSpec.EXPANSION_FACTOR,
                "Completed walking legs within main-mode walk trips"));
        rows.add(def("Walk stages in other main-mode trips, expanded Pkm", active.walkStageMetres()
                / 1000.0 * Production2040AnalysisSpec.EXPANSION_FACTOR,
                "Completed walking access, egress and transfer stages; not PT passenger-km"));
        rows.add(def("Walk active-leg valid coverage (%)", active.walk().validCoveragePercent(),
                activeCoverageText(active.walk())));
        rows.add(def("Bike active-leg valid coverage (%)", active.bike().validCoveragePercent(),
                activeCoverageText(active.bike())));
        rows.add(def("Active-leg unresolved / incomplete evidence", activeIssueText(active),
                "Unresolved values are not substituted with zero and are excluded from territorial distance"));
        rows.add(def("Territorial car Fkm, sample", car.territorialMetres() / 1000.0,
                "Final event movement before demand expansion"));
        rows.add(def("Car final-event reconciliation (sample km)", car.uncutMetres() / 1000.0,
                "Matches established regional car Fkm=" + plain(regional.carMetres() / 1000.0)
                        + "; territorial sample car Fkm=" + plain(car.territorialMetres() / 1000.0)));
        rows.add(def("Car stuck qualification", car.carStuckEvents(),
                "Observed movement before a car PersonStuckEvent is retained; untravelled remainder is never added"));
        rows.add(def("Open final car traffic segments", car.openCarTrafficSegments(),
                "Observed movement is already counted incrementally; open segments are reported, not completed by route inference"));
        rows.add(def("Additional non-private network vehicle categories", categoryText(car),
                "Excluded from Car; transit vehicles are excluded by the established vehicle classification"));
        rows.add(def("PT direct source", Production2040Contract.projectPath(
                        definition.analysisDirectory().resolve(
                                AnalyzeProduction2040PtCostAllocation.SUBDIRECTORY)
                                .resolve("pt_external_cost_inputs.csv")),
                "Validated territorial all-passenger Pkm and territorial full-service Fkm; prior BOTH_INSIDE Fkm allocation is not used"));
        rows.add(def("PT service reconciliation source", Production2040Contract.projectPath(
                        definition.analysisDirectory().resolve(
                                AnalyzeProduction2040AccountingScopes.SUBDIRECTORY)
                                .resolve("final_territorial_pt_fkm_by_route_mode.csv")),
                "Mode-by-mode full-service Fkm must equal the direct PT transfer source"));
        rows.add(def("PT pseudolink territorial-service share (%)",
                ptInputs.pseudolinks().territorialServiceSharePercent(),
                ptInputs.pseudolinks().usedPointAnchoredLinks()
                        + " positive-model-length zero-geometry links use the established strict point-anchor inside/outside proxy; "
                        + plain(ptInputs.pseudolinks().territorialServiceKm())
                        + " territorial service km; " + ptInputs.pseudolinks().zeroModelLengthLinks()
                        + " zero-model-length links contribute zero"));
        rows.add(def("PT vehicle-unit compatibility", "REQUIRES_EXTERNAL_UNIT_MAPPING",
                "One Fkm is a MATSim vehicle trajectory kilometre; no train-to-carriage or external Excel conversion is inferred"));
        rows.add(def("Calibration-scope limitation", "RETAINED", "The existing BOTH_INSIDE analysis versus resident-target calibration mismatch is not resolved by this additional territorial cost table"));
        rows.add(def("Outside this analyzer's scope", "BOTH_INSIDE trip modal shares and resident counts",
                "Existing separate Excel inputs are neither copied nor used as territorial kilometre totals"));
        rows.add(def("Fixed noise / infrastructure costs", "Separate assumptions required",
                "Distance inputs alone do not determine fixed or non-traffic external costs"));
        rows.add(def("Mode coverage", "Car, Walk, Bike, modeled PT service/passengers",
                "This table does not represent all transport in Munich"));
        for (var entry : files.protectedInputSnapshot().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Production2040Contract::projectPath)))
                .toList()) {
            rows.add(def("Protected input SHA-256", Production2040Contract.projectPath(entry.getKey()),
                    entry.getValue()));
        }
        rows.add(def("Expanded daily Car Fkm", carExpandedFkm,
                "Numeric transfer value on Inputs"));
        rows.add(def("Expanded daily Car Pkm", carExpandedPkm,
                "Numeric transfer value on Inputs"));
        rows.add(def("Expanded daily Walk Pkm", walkExpandedPkm,
                "Numeric transfer value on Inputs"));
        rows.add(def("Expanded daily Bike Pkm", bikeExpandedPkm,
                "Numeric transfer value on Inputs"));
        return List.copyOf(rows);
    }

    private static DefinitionRow def(String topic, Object value, String qualification) {
        return new DefinitionRow(topic, value, qualification);
    }

    private static String activeCoverageText(ActiveModeMetric metric) {
        return "planned=" + metric.plannedLegs() + ", departed=" + metric.departedLegs()
                + ", completed=" + metric.completedLegs() + ", valid="
                + metric.validSpatialDistanceLegs() + ", incomplete=" + metric.incompleteLegs()
                + ", missing distance=" + metric.missingDistanceMeasurements()
                + ", missing coordinates=" + metric.missingCoordinates()
                + ", positive-distance coincident endpoints="
                + metric.positiveDistanceCoincidentEndpoints();
    }

    private static String activeIssueText(ActiveModeResultSet active) {
        return "walk incomplete=" + active.walk().incompleteLegs() + ", walk missing distance="
                + active.walk().missingDistanceMeasurements() + ", walk missing coordinates="
                + active.walk().missingCoordinates() + ", walk positive-distance coincident endpoints="
                + active.walk().positiveDistanceCoincidentEndpoints() + ", bike incomplete="
                + active.bike().incompleteLegs() + ", bike missing distance="
                + active.bike().missingDistanceMeasurements() + ", bike missing coordinates="
                + active.bike().missingCoordinates()
                + ", bike positive-distance coincident endpoints="
                + active.bike().positiveDistanceCoincidentEndpoints();
    }

    private static String categoryText(TerritorialCarResult car) {
        if (car.otherNetworkVehicleCategories().isEmpty()) return "none observed";
        StringBuilder result = new StringBuilder();
        car.otherNetworkVehicleCategories().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String mode = entry.getKey();
            VehicleCategory metric = entry.getValue();
            if (!result.isEmpty()) result.append("; ");
            result.append(mode).append(": uncut km=").append(plain(metric.uncutMetres() / 1000.0))
                    .append(", territorial km=")
                    .append(plain(metric.territorialMetres() / 1000.0))
                    .append(", vehicles=").append(metric.vehicles());
        });
        return result.toString();
    }

    private static String carQuality(TerritorialCarResult car) {
        return car.carStuckEvents() == 0 && car.openCarTrafficSegments() == 0
                ? "PASS_EVENT_RECONCILED" : "REPORTED_STUCK_OR_OPEN_PARTIAL_MOVEMENT";
    }

    private static String activeQuality(ActiveModeMetric metric) {
        return metric.hasUnresolvedEvidence() ? "REPORTED_WITH_UNRESOLVED_ACTIVE_LEGS"
                : "PASS_COMPLETED_ACTIVE_LEGS";
    }

    static void validateWorkbookData(WorkbookData data) {
        Production2040AnalysisSpec.require(data.inputRows().size() >= 9,
                "Territorial workbook lacks required transfer rows");
        Map<String, InputRow> byMode = new LinkedHashMap<>();
        for (InputRow row : data.inputRows()) {
            Production2040AnalysisSpec.require(byMode.put(row.mode(), row) == null,
                    "Duplicate territorial workbook input row " + row.mode());
            requireFiniteNonNegative(row.dailyPkm(), "daily Pkm for " + row.mode());
            requireClose(row.annualPkmMillion(), row.dailyPkm() * DAYS_PER_YEAR / 1_000_000.0,
                    "annual Pkm conversion for " + row.mode());
            if (row.dailyFkm() != null) {
                requireFiniteNonNegative(row.dailyFkm(), "daily Fkm for " + row.mode());
                requireClose(row.annualFkmMillion(), row.dailyFkm() * DAYS_PER_YEAR / 1_000_000.0,
                        "annual Fkm conversion for " + row.mode());
            } else {
                Production2040AnalysisSpec.require(row.annualFkmMillion() == null,
                        "Non-applicable Fkm has an annual value for " + row.mode());
            }
        }
        InputRow car = byMode.get("Car");
        Production2040AnalysisSpec.require(car != null && car.dailyFkm() != null,
                "Workbook lacks car Fkm");
        requireClose(car.dailyPkm(), car.dailyFkm() * CAR_ASSUMED_OCCUPANCY,
                "car Pkm equals car Fkm × assumed occupancy");
        InputRow ptTotal = byMode.get("PT total (subtotal, mixed vehicle units)");
        InputRow all = byMode.get("All-mode Pkm total (PT subtotal excluded)");
        Production2040AnalysisSpec.require(ptTotal != null && all != null,
                "Workbook lacks PT subtotal or all-mode Pkm total");
        double directPt = data.inputRows().stream().filter(row -> row != ptTotal)
                .filter(row -> row.mode().equals("Bus") || row.mode().equals("Tram")
                        || row.mode().equals("Subway") || row.mode().equals("Rail")
                        || row.mode().startsWith("Other PT ("))
                .mapToDouble(InputRow::dailyPkm).sum();
        requireClose(ptTotal.dailyPkm(), directPt, "PT subtotal without reallocation");
        double expectedAll = car.dailyPkm() + byMode.get("Walk").dailyPkm()
                + byMode.get("Bike").dailyPkm() + directPt;
        requireClose(all.dailyPkm(), expectedAll, "all-mode Pkm total without PT double count");
        Production2040AnalysisSpec.require(all.dailyFkm() == null,
                "All-mode total must not sum heterogeneous vehicle kilometres");
    }

    private static void publishAtomically(Path parent, Path destination, WorkbookData data)
            throws IOException {
        Production2040AnalysisSpec.require(Files.isDirectory(parent),
                "Missing existing production analysis directory " + parent);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "Territorial cost-input analysis already exists and will not be overwritten");
        Path temporary = parent.resolve(".territorial-cost-inputs-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            Path workbook = temporary.resolve(data.fileName());
            writeWorkbook(workbook, data);
            validateGeneratedWorkbook(workbook, data);
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

    static void writeWorkbook(Path workbookPath, WorkbookData data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                OutputStream output = Files.newOutputStream(workbookPath)) {
            writeInputsSheet(workbook, data);
            writeDefinitionsSheet(workbook, data);
            workbook.write(output);
        }
    }

    private static void writeInputsSheet(XSSFWorkbook workbook, WorkbookData data) {
        var sheet = workbook.createSheet("Inputs");
        sheet.setDisplayGridlines(false);
        Styles styles = Styles.create(workbook);
        Row title = sheet.createRow(1);
        put(title, 0, data.scenarioId().replace('_', ' ') + " territorial cost inputs",
                styles.title());
        Row note = sheet.createRow(2);
        put(note, 0, "All daily values are full-scale transfer values. Do not apply another factor 20.",
                styles.note());
        String[] headers = {"Mode", "Territorial Pkm per reporting day, expanded",
                "Territorial Fkm per reporting day, applicable scale",
                "Annual Pkm, million, 365-day equivalent",
                "Annual Fkm, million, 365-day equivalent", "Average occupancy",
                "Vehicle/distance unit", "Calculation basis", "Quality status"};
        Row header = sheet.createRow(INPUT_HEADER_ROW);
        for (int column = 0; column < headers.length; column++) {
            put(header, column, headers[column], styles.header());
        }
        for (int index = 0; index < data.inputRows().size(); index++) {
            InputRow value = data.inputRows().get(index);
            Row row = sheet.createRow(INPUT_DATA_START_ROW + index);
            boolean subtotal = value.mode().startsWith("PT total");
            boolean total = value.mode().startsWith("All-mode");
            CellStyle text = subtotal ? styles.subtotalText() : total ? styles.totalText()
                    : styles.text();
            CellStyle numeric = subtotal ? styles.subtotalNumber() : total ? styles.totalNumber()
                    : styles.number();
            CellStyle occupancy = subtotal ? styles.subtotalOccupancy()
                    : total ? styles.totalOccupancy() : styles.occupancy();
            put(row, 0, value.mode(), text);
            put(row, 1, value.dailyPkm(), numeric);
            putOptionalNumber(row, 2, value.dailyFkm(), numeric);
            put(row, 3, value.annualPkmMillion(), numeric);
            putOptionalNumber(row, 4, value.annualFkmMillion(), numeric);
            putOptionalNumber(row, 5, value.averageOccupancy(), occupancy);
            put(row, 6, value.vehicleUnit(), text);
            put(row, 7, value.calculationBasis(), text);
            put(row, 8, value.qualityStatus(), text);
        }
        sheet.createFreezePane(1, INPUT_DATA_START_ROW);
        sheet.setAutoFilter(new CellRangeAddress(INPUT_HEADER_ROW,
                INPUT_DATA_START_ROW + data.inputRows().size() - 1, 0, headers.length - 1));
        int[] widths = {34, 20, 20, 18, 18, 15, 28, 62, 38};
        for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index,
                widths[index] * 256);
        title.setHeightInPoints(22);
        note.setHeightInPoints(18);
    }

    private static void writeDefinitionsSheet(XSSFWorkbook workbook, WorkbookData data) {
        var sheet = workbook.createSheet("Definitions_and_checks");
        sheet.setDisplayGridlines(false);
        Styles styles = Styles.create(workbook);
        Row title = sheet.createRow(1);
        put(title, 0, data.scenarioId().replace('_', ' ') + " definitions and checks",
                styles.title());
        Row header = sheet.createRow(3);
        put(header, 0, "Topic", styles.header());
        put(header, 1, "Value", styles.header());
        put(header, 2, "Status / qualification", styles.header());
        for (int index = 0; index < data.definitions().size(); index++) {
            DefinitionRow value = data.definitions().get(index);
            Row row = sheet.createRow(4 + index);
            put(row, 0, value.topic(), styles.definitionText());
            if (value.value() instanceof Number number) put(row, 1, number.doubleValue(),
                    styles.definitionNumber());
            else put(row, 1, value.value().toString(), styles.definitionText());
            put(row, 2, value.qualification(), styles.definitionText());
        }
        sheet.createFreezePane(1, 4);
        sheet.setColumnWidth(0, 38 * 256);
        sheet.setColumnWidth(1, 42 * 256);
        sheet.setColumnWidth(2, 100 * 256);
        title.setHeightInPoints(22);
    }

    static void validateGeneratedWorkbook(Path workbookPath, WorkbookData expected)
            throws IOException {
        Production2040AnalysisSpec.require(Files.isRegularFile(workbookPath),
                "Territorial workbook was not written");
        try (InputStream input = Files.newInputStream(workbookPath);
                Workbook workbook = WorkbookFactory.create(input)) {
            Production2040AnalysisSpec.require(workbook.getNumberOfSheets() == 2
                            && workbook.getSheet("Inputs") != null
                            && workbook.getSheet("Definitions_and_checks") != null,
                    "Territorial workbook must contain exactly Inputs and Definitions_and_checks");
            var inputs = workbook.getSheet("Inputs");
            for (int index = 0; index < expected.inputRows().size(); index++) {
                InputRow row = expected.inputRows().get(index);
                Row sheetRow = inputs.getRow(INPUT_DATA_START_ROW + index);
                Production2040AnalysisSpec.require(sheetRow != null
                                && row.mode().equals(stringValue(sheetRow.getCell(0))),
                        "Territorial workbook input row is missing or reordered");
                requireNumericCell(sheetRow.getCell(1), row.dailyPkm(), "daily Pkm");
                requireOptionalNumericCell(sheetRow.getCell(2), row.dailyFkm(), "daily Fkm");
                requireNumericCell(sheetRow.getCell(3), row.annualPkmMillion(), "annual Pkm");
                requireOptionalNumericCell(sheetRow.getCell(4), row.annualFkmMillion(),
                        "annual Fkm");
                requireOptionalNumericCell(sheetRow.getCell(5), row.averageOccupancy(),
                        "occupancy");
            }
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                for (Row row : workbook.getSheetAt(sheetIndex)) {
                    for (Cell cell : row) Production2040AnalysisSpec.require(
                            cell.getCellType() != CellType.FORMULA,
                            "Territorial workbook must not depend on uncalculated formulas");
                }
            }
        }
        try (ZipFile zip = new ZipFile(workbookPath.toFile())) {
            boolean external = zip.stream().anyMatch(entry -> entry.getName().startsWith(
                    "xl/externalLinks/"));
            Production2040AnalysisSpec.require(!external,
                    "Territorial workbook contains an external workbook link");
        }
    }

    private static void requireNumericCell(Cell cell, double expected, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.NUMERIC,
                "Territorial workbook " + label + " is not a numeric Excel cell");
        requireClose(cell.getNumericCellValue(), expected,
                "Territorial workbook numeric value for " + label);
    }

    private static void requireOptionalNumericCell(Cell cell, Double expected, String label) {
        if (expected == null) {
            Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.STRING
                            && "NOT_APPLICABLE".equals(cell.getStringCellValue()),
                    "Territorial workbook " + label + " must be explicitly not applicable");
        } else requireNumericCell(cell, expected, label);
    }

    private static String stringValue(Cell cell) {
        return cell == null ? "" : cell.getStringCellValue();
    }

    private static void put(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column, CellType.STRING);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void put(Row row, int column, double value, CellStyle style) {
        requireFiniteNonNegative(value, "workbook numeric value");
        Cell cell = row.createCell(column, CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void putOptionalNumber(Row row, int column, Double value, CellStyle style) {
        if (value == null) put(row, column, "NOT_APPLICABLE", style);
        else put(row, column, value, style);
    }

    private static void deleteTemporary(Path temporary, Path parent) {
        if (!temporary.normalize().getParent().equals(parent.normalize())
                || !temporary.getFileName().toString().startsWith(
                        ".territorial-cost-inputs-tmp-")) {
            throw new IllegalStateException("Refusing to clean unexpected territorial temporary path "
                    + temporary);
        }
        if (!Files.exists(temporary)) return;
        try (var paths = Files.walk(temporary)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (IOException | java.io.UncheckedIOException error) {
            throw new IllegalStateException("Could not clean temporary territorial output "
                    + temporary, error);
        }
    }

    private static List<Map<String, String>> csvRows(String source, String label) {
        String[] lines = source.split("\\R");
        Production2040AnalysisSpec.require(lines.length >= 2, "CSV has no rows: " + label);
        List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                lines[0], ',');
        Production2040AnalysisSpec.require(header.size() == Set.copyOf(header).size(),
                "Duplicate CSV header: " + label);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.length; line++) {
            if (lines[line].isBlank()) continue;
            List<String> values = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                    lines[line], ',');
            Production2040AnalysisSpec.require(values.size() == header.size(),
                    "Malformed CSV row in " + label);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), values.get(column));
            }
            rows.add(Map.copyOf(row));
        }
        Production2040AnalysisSpec.require(!rows.isEmpty(), "CSV has no data rows: " + label);
        return List.copyOf(rows);
    }

    private static void requireScenario(Map<String, String> row,
            Production2040AnalysisSpec.ScenarioDefinition definition, String label) {
        Production2040AnalysisSpec.require(definition.scenarioId().equals(row.get("scenario_id")),
                "Source CSV belongs to another scenario: " + label);
    }

    private static String required(Map<String, String> row, String column, String label) {
        String value = row.get(column);
        Production2040AnalysisSpec.require(value != null && !value.isBlank(),
                "Missing " + column + " in " + label);
        return value;
    }

    private static double parse(Map<String, String> row, String column, String label) {
        try {
            double value = Double.parseDouble(required(row, column, label));
            requireFiniteNonNegative(value, column + " in " + label);
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Invalid " + column + " in " + label, error);
        }
    }

    private static long parseLong(Map<String, String> row, String column, String label) {
        try {
            long value = Long.parseLong(required(row, column, label));
            Production2040AnalysisSpec.require(value >= 0,
                    "Negative " + column + " in " + label);
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Invalid " + column + " in " + label, error);
        }
    }

    private static Double optionalNumber(Map<String, String> row, String column,
            String label) {
        String value = required(row, column, label);
        if ("NOT_APPLICABLE".equals(value)) return null;
        try {
            double result = Double.parseDouble(value);
            requireFiniteNonNegative(result, column + " in " + label);
            return result;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Invalid optional " + column + " in " + label,
                    error);
        }
    }

    private static Set<String> splitComponents(String value) {
        Set<String> result = new TreeSet<>();
        for (String component : value.split("\\|")) {
            String normalized = component.trim();
            if (!normalized.isEmpty()) result.add(normalized);
        }
        Production2040AnalysisSpec.require(!result.isEmpty(), "PT component mode is blank");
        return Set.copyOf(result);
    }

    private static String title(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void requireFiniteNonNegative(double value, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(value) && value >= 0.0,
                "Invalid " + label + ": " + value);
    }

    private static void requireClose(double actual, double expected, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(actual) && Double.isFinite(expected)
                        && Math.abs(actual - expected) <= 1e-9,
                label + " does not reconcile: actual=" + actual + " expected=" + expected);
    }

    private static void requireCloseMetres(double actual, double expected, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(actual) && Double.isFinite(expected)
                        && Math.abs(actual - expected) <= TOLERANCE_METRES,
                label + " does not reconcile: actual=" + actual + " expected=" + expected);
    }

    static void requireAccumulatedDistanceCloseMetres(double actual, double expected,
            String label) {
        boolean finite = Double.isFinite(actual) && Double.isFinite(expected);
        double absoluteDifference = finite ? Math.abs(actual - expected) : Double.NaN;
        double magnitude = finite ? Math.max(Math.abs(actual), Math.abs(expected)) : Double.NaN;
        double relativeDifference = magnitude == 0.0 ? 0.0 : absoluteDifference / magnitude;
        double permittedTolerance = finite ? Math.max(
                ACCUMULATED_DISTANCE_ABSOLUTE_TOLERANCE_METRES,
                ACCUMULATED_DISTANCE_RELATIVE_TOLERANCE * magnitude) : Double.NaN;
        Production2040AnalysisSpec.require(finite && absoluteDifference <= permittedTolerance,
                label + " does not reconcile: actual=" + actual + " expected=" + expected
                        + " absoluteDifference=" + absoluteDifference
                        + " relativeDifference=" + relativeDifference
                        + " permittedTolerance=" + permittedTolerance);
    }

    private static String plain(double value) {
        requireFiniteNonNegative(value, "display value");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    record ActiveRoutingDefinition(Set<String> networkModes, double walkBeelineFactor,
                                   double bikeBeelineFactor,
                                   double nonNetworkWalkBeelineFactor) { }

    record PtModeInput(String mode, Set<String> components, double samplePkm,
                       double expandedPkm, double fullServiceFkm, Double occupancy,
                       String occupancyStatus, double demandExpansion, double supplyExpansion,
                       String qualityStatus) { }

    record PseudolinkDiagnostics(long usedPointAnchoredLinks, double territorialServiceKm,
                                 double territorialServiceSharePercent,
                                 long zeroModelLengthLinks) { }

    record ValidatedPtInputs(Map<String, PtModeInput> byMode,
                             PseudolinkDiagnostics pseudolinks) { }

    record InputRow(String mode, double dailyPkm, Double dailyFkm, double annualPkmMillion,
                    Double annualFkmMillion, Double averageOccupancy, String vehicleUnit,
                    String calculationBasis, String qualityStatus) { }

    record DefinitionRow(String topic, Object value, String qualification) { }

    record WorkbookData(String scenarioId, String fileName, List<InputRow> inputRows,
                        List<DefinitionRow> definitions) { }

    /**
     * Uses the established first-/last-link movement stream but applies no
     * person, main-trip, residence, or endpoint-scope filter to private cars.
     */
    static final class TerritorialCarMetrics implements Production2040VehicleMetrics.MovementObserver {
        private final Network network;
        private final MunichMunicipalBoundary boundary;
        private final Map<Id<Vehicle>, ActiveTraffic> traffic = new HashMap<>();
        private final Set<Id<Vehicle>> transitTraffic = new HashSet<>();
        private final Map<Id<Link>, Production2040AccountingEventMetrics.LinkClip> clips =
                new HashMap<>();
        private final MutableVehicleCategory car = new MutableVehicleCategory();
        private final Map<String, MutableVehicleCategory> other = new TreeMap<>();
        private final Set<Id<Person>> stuckCarPersons = new HashSet<>();
        private long carStuckEvents;
        private long unclassifiedMovementEvents;
        private long repeatedTrafficEnters;
        private long unmatchedTrafficLeaves;

        TerritorialCarMetrics(Network network, MunichMunicipalBoundary boundary) {
            this.network = java.util.Objects.requireNonNull(network);
            this.boundary = java.util.Objects.requireNonNull(boundary);
        }

        @Override
        public void reset() {
            traffic.clear();
            transitTraffic.clear();
            clips.clear();
            car.reset();
            other.clear();
            stuckCarPersons.clear();
            carStuckEvents = 0;
            unclassifiedMovementEvents = 0;
            repeatedTrafficEnters = 0;
            unmatchedTrafficLeaves = 0;
        }

        @Override
        public void trafficEnter(Id<Vehicle> vehicle, Id<Person> person, String networkMode,
                Integer mainTripIndex, boolean transit) {
            if (transit) {
                transitTraffic.add(vehicle);
                return;
            }
            String mode = Production2040AnalysisSpec.normalizeMainMode(networkMode);
            if (traffic.put(vehicle, new ActiveTraffic(mode, person)) != null) {
                repeatedTrafficEnters++;
            }
        }

        @Override
        public void movement(Id<Vehicle> vehicle, Id<Person> person, Id<Link> linkId,
                double metres, boolean transit, String ptMode) {
            if (transit || Math.abs(metres) <= 0.0) return;
            ActiveTraffic state = traffic.get(vehicle);
            if (state == null) {
                unclassifiedMovementEvents++;
                return;
            }
            Link link = network.getLinks().get(linkId);
            Production2040AnalysisSpec.require(link != null,
                    "Territorial car movement refers to missing link " + linkId);
            var clip = clips.computeIfAbsent(linkId,
                    ignored -> Production2040AccountingEventMetrics.clip(link, boundary));
            Production2040AnalysisSpec.require(clip.insideFraction() >= 0.0
                            && clip.insideFraction() <= 1.0,
                    "Territorial car clipping fraction is outside [0,1]");
            MutableVehicleCategory target = "car".equals(state.mode()) ? car
                    : other.computeIfAbsent(state.mode(), ignored -> new MutableVehicleCategory());
            target.add(vehicle, linkId, metres, metres * clip.insideFraction(), clip);
        }

        @Override
        public void trafficLeave(Id<Vehicle> vehicle, Id<Person> person) {
            if (transitTraffic.remove(vehicle)) return;
            if (traffic.remove(vehicle) == null) unmatchedTrafficLeaves++;
        }

        @Override
        public void personStuck(Id<Person> person, Integer mainTripIndex, String legMode) {
            if ("car".equals(legMode)) {
                carStuckEvents++;
                stuckCarPersons.add(person);
            }
        }

        TerritorialCarResult result() {
            Map<String, VehicleCategory> categories = new TreeMap<>();
            other.forEach((mode, value) -> categories.put(mode, value.freeze()));
            long openCars = traffic.values().stream().filter(value -> "car".equals(value.mode()))
                    .count();
            return new TerritorialCarResult(car.uncutMetres, car.territorialMetres,
                    car.vehicles.size(), Map.copyOf(categories), carStuckEvents,
                    stuckCarPersons.size(), openCars, unclassifiedMovementEvents,
                    repeatedTrafficEnters, unmatchedTrafficLeaves,
                    car.pointAnchoredLinks.size());
        }

        private record ActiveTraffic(String mode, Id<Person> person) { }

        private static final class MutableVehicleCategory {
            private double uncutMetres;
            private double territorialMetres;
            private final Set<Id<Vehicle>> vehicles = new HashSet<>();
            private final Set<Id<Link>> crossingLinks = new HashSet<>();
            private final Set<Id<Link>> pointAnchoredLinks = new HashSet<>();

            private void add(Id<Vehicle> vehicle, Id<Link> link, double uncut,
                    double territorial, Production2040AccountingEventMetrics.LinkClip clip) {
                uncutMetres += uncut;
                territorialMetres += territorial;
                vehicles.add(vehicle);
                if (clip.category() == Production2040AccountingEventMetrics.LinkLocation.CROSSING) {
                    crossingLinks.add(link);
                }
                if (clip.method() == Production2040AccountingEventMetrics.LinkClipMethod
                        .POINT_ANCHORED_PSEUDOLINK) pointAnchoredLinks.add(link);
            }

            private VehicleCategory freeze() {
                Production2040AnalysisSpec.require(uncutMetres >= -TOLERANCE_METRES
                                && territorialMetres >= -TOLERANCE_METRES,
                        "Negative final territorial vehicle-category distance");
                return new VehicleCategory(Math.max(0.0, uncutMetres),
                        Math.max(0.0, territorialMetres), vehicles.size(), crossingLinks.size(),
                        pointAnchoredLinks.size());
            }

            private void reset() {
                uncutMetres = 0;
                territorialMetres = 0;
                vehicles.clear();
                crossingLinks.clear();
                pointAnchoredLinks.clear();
            }
        }
    }

    record VehicleCategory(double uncutMetres, double territorialMetres, long vehicles,
                           long crossingLinks, long pointAnchoredLinks) { }

    record TerritorialCarResult(double uncutMetres, double territorialMetres,
                                long carVehicles,
                                Map<String, VehicleCategory> otherNetworkVehicleCategories,
                                long carStuckEvents, long uniqueStuckCarPersons,
                                long openCarTrafficSegments,
                                long unclassifiedMovementEvents,
                                long repeatedTrafficEnters, long unmatchedTrafficLeaves,
                                long pointAnchoredCarLinks) { }

    /** Final-plan active legs matched to completed teleportation events. */
    static final class TerritorialActiveModeMetrics implements PersonDepartureEventHandler,
            PersonArrivalEventHandler, PersonStuckEventHandler {
        private final MunichMunicipalBoundary boundary;
        private final Map<Id<Person>, ActiveLegSequence> sequences;
        private final MutableActiveModeMetric walk = new MutableActiveModeMetric();
        private final MutableActiveModeMetric bike = new MutableActiveModeMetric();
        private final Set<PlannedActiveLeg> stuckLegs = Collections.newSetFromMap(
                new IdentityHashMap<>());
        private double walkMainModeMetres;
        private double walkStageMetres;
        private long departureSequenceMismatches;
        private long unmatchedActiveDepartures;
        private long unmatchedActiveArrivals;

        private TerritorialActiveModeMetrics(MunichMunicipalBoundary boundary,
                Map<Id<Person>, ActiveLegSequence> sequences) {
            this.boundary = boundary;
            this.sequences = Map.copyOf(sequences);
            sequences.values().forEach(sequence -> sequence.legs().forEach(leg ->
                    metric(leg.activeMode()).plannedLegs++));
        }

        static TerritorialActiveModeMetrics read(Path plans, MunichMunicipalBoundary boundary)
                throws IOException {
            Production2040AnalysisSpec.require(Files.isRegularFile(plans),
                    "Missing final plans for territorial active-mode accounting: " + plans);
            Map<Id<Person>, ActiveLegSequence> sequences = new HashMap<>();
            var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
            StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
            reader.addAlgorithm(person -> {
                Plan selected = person.getSelectedPlan();
                Production2040AnalysisSpec.require(selected != null,
                        "Final plan has no selected plan for " + person.getId());
                Production2040AnalysisSpec.require(sequences.put(person.getId(),
                                activeSequence(selected)) == null,
                        "Duplicate person in final plans: " + person.getId());
            });
            reader.readFile(plans.toString());
            return new TerritorialActiveModeMetrics(boundary, sequences);
        }

        private static ActiveLegSequence activeSequence(Plan plan) {
            Map<Leg, String> mainModes = new IdentityHashMap<>();
            for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan,
                    StageActivityTypeIdentifier::isStageActivity)) {
                String mainMode = MunichTripBoundaryFilter.identifyInputMainMode(trip);
                for (PlanElement element : trip.getTripElements()) {
                    if (element instanceof Leg leg) mainModes.put(leg, mainMode);
                }
            }
            List<? extends PlanElement> elements = plan.getPlanElements();
            List<PlannedActiveLeg> active = new ArrayList<>();
            for (int index = 0; index < elements.size(); index++) {
                if (!(elements.get(index) instanceof Leg leg)) continue;
                ActiveMode activeMode = activeMode(leg.getMode());
                if (activeMode == null) continue;
                Activity from = index > 0 && elements.get(index - 1) instanceof Activity activity
                        ? activity : null;
                Activity to = index + 1 < elements.size()
                        && elements.get(index + 1) instanceof Activity activity ? activity : null;
                double modeled = leg.getRoute() == null ? Double.NaN : leg.getRoute().getDistance();
                active.add(new PlannedActiveLeg(activeMode, mainModes.getOrDefault(leg, "unknown"),
                        from == null ? null : from.getCoord(), to == null ? null : to.getCoord(),
                        modeled));
            }
            return new ActiveLegSequence(List.copyOf(active));
        }

        @Override
        public void reset(int iteration) {
            sequences.values().forEach(ActiveLegSequence::reset);
            walk.resetObserved();
            bike.resetObserved();
            stuckLegs.clear();
            walkMainModeMetres = 0;
            walkStageMetres = 0;
            departureSequenceMismatches = 0;
            unmatchedActiveDepartures = 0;
            unmatchedActiveArrivals = 0;
        }

        @Override
        public void handleEvent(PersonDepartureEvent event) {
            departure(event.getPersonId(), event.getLegMode());
        }

        @Override
        public void handleEvent(PersonArrivalEvent event) {
            arrival(event.getPersonId(), event.getLegMode());
        }

        @Override
        public void handleEvent(PersonStuckEvent event) {
            stuck(event.getPersonId(), event.getLegMode());
        }

        void departure(Id<Person> person, String mode) {
            ActiveMode actual = activeMode(mode);
            if (actual == null) return;
            ActiveLegSequence sequence = sequences.get(person);
            if (sequence == null || sequence.open() != null || sequence.nextIndex() >= sequence.legs().size()) {
                unmatchedActiveDepartures++;
                return;
            }
            PlannedActiveLeg expected = sequence.legs().get(sequence.nextIndex());
            if (expected.activeMode() != actual) {
                departureSequenceMismatches++;
                return;
            }
            sequence.open(expected);
            sequence.advance();
            metric(actual).departedLegs++;
        }

        void arrival(Id<Person> person, String mode) {
            ActiveMode actual = activeMode(mode);
            if (actual == null) return;
            ActiveLegSequence sequence = sequences.get(person);
            if (sequence == null || sequence.open() == null
                    || sequence.open().activeMode() != actual) {
                unmatchedActiveArrivals++;
                return;
            }
            PlannedActiveLeg leg = sequence.open();
            sequence.clearOpen();
            complete(leg);
        }

        void stuck(Id<Person> person, String mode) {
            ActiveMode actual = activeMode(mode);
            if (actual == null) return;
            ActiveLegSequence sequence = sequences.get(person);
            if (sequence == null || sequence.open() == null
                    || sequence.open().activeMode() != actual) {
                unmatchedActiveArrivals++;
                return;
            }
            stuckLegs.add(sequence.open());
            sequence.clearOpen();
        }

        private void complete(PlannedActiveLeg leg) {
            MutableActiveModeMetric metric = metric(leg.activeMode());
            metric.completedLegs++;
            boolean validDistance = Double.isFinite(leg.modelledMetres())
                    && leg.modelledMetres() >= 0.0;
            boolean validCoordinates = valid(leg.from()) && valid(leg.to());
            if (!validDistance) {
                metric.missingDistanceMeasurements++;
            }
            if (!validCoordinates) {
                metric.missingCoordinates++;
            }
            if (!validDistance || !validCoordinates) {
                return;
            }
            Coordinate from = new Coordinate(leg.from().getX(), leg.from().getY());
            Coordinate to = new Coordinate(leg.to().getX(), leg.to().getY());
            if (from.equals2D(to)) {
                metric.coincidentEndpointLegs++;
                if (leg.modelledMetres() > 0.0) {
                    metric.positiveDistanceCoincidentEndpoints++;
                    return;
                }
                metric.zeroDistanceLegs++;
                metric.validSpatialDistanceLegs++;
                return;
            }
            if (leg.modelledMetres() == 0.0) metric.zeroDistanceLegs++;
            double fraction = Production2040AccountingEventMetrics.geometricInsideFraction(from,
                    to, boundary);
            Production2040AnalysisSpec.require(fraction >= 0.0 && fraction <= 1.0,
                    "Active-mode territorial clipping fraction is outside [0,1]");
            metric.validSpatialDistanceLegs++;
            metric.observedModelledMetres += leg.modelledMetres();
            double territorial = leg.modelledMetres() * fraction;
            metric.territorialMetres += territorial;
            if (leg.activeMode() == ActiveMode.WALK) {
                if ("walk".equals(leg.mainMode())) walkMainModeMetres += territorial;
                else walkStageMetres += territorial;
            }
        }

        ActiveModeResultSet result() {
            ActiveModeMetric walkResult = walk.freeze(stuckLegs, ActiveMode.WALK);
            ActiveModeMetric bikeResult = bike.freeze(stuckLegs, ActiveMode.BIKE);
            return new ActiveModeResultSet(walkResult, bikeResult, walkMainModeMetres,
                    walkStageMetres, departureSequenceMismatches, unmatchedActiveDepartures,
                    unmatchedActiveArrivals);
        }

        private MutableActiveModeMetric metric(ActiveMode mode) {
            return mode == ActiveMode.WALK ? walk : bike;
        }

        private static boolean valid(Coord coordinate) {
            return coordinate != null && Double.isFinite(coordinate.getX())
                    && Double.isFinite(coordinate.getY());
        }

        private static ActiveMode activeMode(String mode) {
            String normalized = Production2040AnalysisSpec.normalizeMainMode(mode);
            return switch (normalized) {
                case "bike" -> ActiveMode.BIKE;
                case "walk", "transit_walk", "non_network_walk", "access_walk", "egress_walk" ->
                        ActiveMode.WALK;
                default -> null;
            };
        }

        private enum ActiveMode { WALK, BIKE }

        private record PlannedActiveLeg(ActiveMode activeMode, String mainMode, Coord from,
                                        Coord to, double modelledMetres) { }

        private static final class ActiveLegSequence {
            private final List<PlannedActiveLeg> legs;
            private int nextIndex;
            private PlannedActiveLeg open;

            private ActiveLegSequence(List<PlannedActiveLeg> legs) {
                this.legs = legs;
            }

            private List<PlannedActiveLeg> legs() { return legs; }
            private int nextIndex() { return nextIndex; }
            private PlannedActiveLeg open() { return open; }
            private void advance() { nextIndex++; }
            private void open(PlannedActiveLeg value) { open = value; }
            private void clearOpen() { open = null; }
            private void reset() { nextIndex = 0; open = null; }
        }

        private static final class MutableActiveModeMetric {
            private long plannedLegs;
            private long departedLegs;
            private long completedLegs;
            private long validSpatialDistanceLegs;
            private long missingDistanceMeasurements;
            private long missingCoordinates;
            private long positiveDistanceCoincidentEndpoints;
            private long coincidentEndpointLegs;
            private long zeroDistanceLegs;
            private double observedModelledMetres;
            private double territorialMetres;

            private void resetObserved() {
                departedLegs = 0;
                completedLegs = 0;
                validSpatialDistanceLegs = 0;
                missingDistanceMeasurements = 0;
                missingCoordinates = 0;
                positiveDistanceCoincidentEndpoints = 0;
                coincidentEndpointLegs = 0;
                zeroDistanceLegs = 0;
                observedModelledMetres = 0;
                territorialMetres = 0;
            }

            private ActiveModeMetric freeze(Set<PlannedActiveLeg> stuck, ActiveMode mode) {
                long stuckIncomplete = stuck.stream().filter(leg -> leg.activeMode() == mode).count();
                return new ActiveModeMetric(plannedLegs, departedLegs, completedLegs,
                        validSpatialDistanceLegs, missingDistanceMeasurements, missingCoordinates,
                        positiveDistanceCoincidentEndpoints, coincidentEndpointLegs,
                        zeroDistanceLegs, plannedLegs - completedLegs, stuckIncomplete,
                        observedModelledMetres, territorialMetres);
            }
        }
    }

    record ActiveModeMetric(long plannedLegs, long departedLegs, long completedLegs,
                            long validSpatialDistanceLegs, long missingDistanceMeasurements,
                            long missingCoordinates, long positiveDistanceCoincidentEndpoints,
                            long coincidentEndpointLegs, long zeroDistanceLegs,
                            long incompleteLegs, long stuckIncompleteLegs,
                            double observedModelledMetres, double territorialMetres) {
        double validCoveragePercent() {
            return plannedLegs == 0 ? 100.0
                    : Production2040AnalysisSpec.percent(validSpatialDistanceLegs, plannedLegs);
        }

        boolean hasUnresolvedEvidence() {
            return incompleteLegs > 0 || missingDistanceMeasurements > 0 || missingCoordinates > 0
                    || positiveDistanceCoincidentEndpoints > 0;
        }
    }

    record ActiveModeResultSet(ActiveModeMetric walk, ActiveModeMetric bike,
                               double walkMainModeMetres, double walkStageMetres,
                               long departureSequenceMismatches,
                               long unmatchedActiveDepartures,
                               long unmatchedActiveArrivals) { }

    private record Styles(CellStyle title, CellStyle note, CellStyle header, CellStyle text,
                          CellStyle number, CellStyle occupancy, CellStyle subtotalText,
                          CellStyle subtotalNumber, CellStyle subtotalOccupancy,
                          CellStyle totalText, CellStyle totalNumber, CellStyle totalOccupancy,
                          CellStyle definitionText, CellStyle definitionNumber) {
        private static Styles create(XSSFWorkbook workbook) {
            DataFormat formats = workbook.createDataFormat();
            Font body = workbook.createFont();
            body.setFontName("Arial");
            body.setFontHeightInPoints((short) 10);
            Font titleFont = workbook.createFont();
            titleFont.setFontName("Arial");
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setBold(true);
            Font headerFont = workbook.createFont();
            headerFont.setFontName("Arial");
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            CellStyle title = workbook.createCellStyle();
            title.setFont(titleFont);
            CellStyle note = workbook.createCellStyle();
            note.setFont(body);
            note.setWrapText(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            CellStyle text = workbook.createCellStyle();
            text.setFont(body);
            text.setVerticalAlignment(VerticalAlignment.CENTER);
            text.setWrapText(true);
            CellStyle number = workbook.createCellStyle();
            number.cloneStyleFrom(text);
            number.setAlignment(HorizontalAlignment.RIGHT);
            number.setDataFormat(formats.getFormat("#,##0.000"));
            CellStyle occupancy = workbook.createCellStyle();
            occupancy.cloneStyleFrom(text);
            occupancy.setAlignment(HorizontalAlignment.RIGHT);
            occupancy.setDataFormat(formats.getFormat("0.000"));
            CellStyle subtotalText = workbook.createCellStyle();
            subtotalText.cloneStyleFrom(text);
            subtotalText.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            subtotalText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font subtotalFont = workbook.createFont();
            subtotalFont.setFontName("Arial");
            subtotalFont.setFontHeightInPoints((short) 10);
            subtotalFont.setBold(true);
            subtotalText.setFont(subtotalFont);
            CellStyle subtotalNumber = workbook.createCellStyle();
            subtotalNumber.cloneStyleFrom(subtotalText);
            subtotalNumber.setAlignment(HorizontalAlignment.RIGHT);
            subtotalNumber.setDataFormat(formats.getFormat("#,##0.000"));
            CellStyle subtotalOccupancy = workbook.createCellStyle();
            subtotalOccupancy.cloneStyleFrom(subtotalText);
            subtotalOccupancy.setAlignment(HorizontalAlignment.RIGHT);
            subtotalOccupancy.setDataFormat(formats.getFormat("0.000"));
            CellStyle totalText = workbook.createCellStyle();
            totalText.cloneStyleFrom(subtotalText);
            totalText.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            CellStyle totalNumber = workbook.createCellStyle();
            totalNumber.cloneStyleFrom(totalText);
            totalNumber.setAlignment(HorizontalAlignment.RIGHT);
            totalNumber.setDataFormat(formats.getFormat("#,##0.000"));
            CellStyle totalOccupancy = workbook.createCellStyle();
            totalOccupancy.cloneStyleFrom(totalText);
            totalOccupancy.setAlignment(HorizontalAlignment.RIGHT);
            totalOccupancy.setDataFormat(formats.getFormat("0.000"));
            CellStyle definitionText = workbook.createCellStyle();
            definitionText.cloneStyleFrom(text);
            definitionText.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle definitionNumber = workbook.createCellStyle();
            definitionNumber.cloneStyleFrom(definitionText);
            definitionNumber.setAlignment(HorizontalAlignment.RIGHT);
            definitionNumber.setDataFormat(formats.getFormat("#,##0.000"));
            return new Styles(title, note, header, text, number, occupancy, subtotalText,
                    subtotalNumber, subtotalOccupancy, totalText, totalNumber, totalOccupancy,
                    definitionText, definitionNumber);
        }
    }
}
