package org.matsim.project.prepare;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

/**
 * Controller-free allocation of existing territorial PT service to existing
 * BOTH_INSIDE in-vehicle demand. This class never constructs a Controller or QSim.
 */
public final class AnalyzeProduction2040PtCostAllocation {
    static final String SUBDIRECTORY = "pt_cost_allocation";
    static final String OTHER_ROUTE_MODE = "ferry/other";
    static final int DAYS_PER_YEAR = 365;
    static final double RECONCILIATION_TOLERANCE_METRES =
            AnalyzeProduction2040AccountingScopes.RECONCILIATION_TOLERANCE_METRES;

    private AnalyzeProduction2040PtCostAllocation() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: AnalyzeProduction2040PtCostAllocation BAU|FAST_TRACK");
        analyze(args[0]);
    }

    static void analyze(String scenarioArgument) throws Exception {
        var definition = Production2040AnalysisSpec.scenario(scenarioArgument);
        Path destination = definition.analysisDirectory().resolve(SUBDIRECTORY);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "PT cost-allocation analysis already exists and will not be overwritten: "
                        + Production2040Contract.projectPath(destination));
        var files = ValidateProduction2040AnalysisOutput.validatePublished(definition);
        Map<String, String> accountingReports =
                ValidateProduction2040AccountingScopes.validatePublished(definition);

        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        var index = Production2040AccountingScopes.read(files.plans(), boundary);
        AnalyzeProduction2040AccountingScopes.validatePlanIndex(index);

        Scenario eventScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(eventScenario.getNetwork()).readFile(files.network().toString());
        new TransitScheduleReader(eventScenario).readFile(files.schedule().toString());
        new MatsimVehicleReader(eventScenario.getTransitVehicles()).readFile(
                files.vehicles().toString());

        var accounting = new Production2040AccountingEventMetrics(eventScenario.getNetwork(),
                boundary, index);
        var regional = new Production2040VehicleMetrics(eventScenario.getNetwork(),
                eventScenario.getTransitSchedule(), eventScenario.getTransitVehicles(),
                bothInsideFlags(index), accounting);
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(regional);
        new MatsimEventsReader(manager).readFile(files.events().toString());

        var regionalResult = regional.result();
        var accountingResult = accounting.result();
        var regionalReferences = AnalyzeProduction2040AccountingScopes
                .readRegionalReferences(definition);
        AnalyzeProduction2040AccountingScopes.validateEventMetrics(regionalResult,
                accountingResult, regionalReferences);

        SourceInputs sources = readSourceInputs(definition, accountingReports);
        validateBothInsidePkm(regionalResult, sources);
        validateTerritorialFkm(accountingResult, sources);
        List<Allocation> allocations = calculateAllocations(sources, accountingResult,
                regionalResult);
        validateAllocations(allocations);
        List<VehicleMetadata> vehicleMetadata = readVehicleMetadata(
                eventScenario.getTransitSchedule(), eventScenario.getTransitVehicles());

        Map<String, String> reports = buildReports(definition, allocations, vehicleMetadata,
                regionalResult, accountingResult, sources);
        ValidateProduction2040PtCostAllocation.validateBundle(definition, reports);
        Production2040AnalysisSpec.require(files.protectedInputSnapshot().equals(
                        Production2040Contract.protectedInputSnapshot(
                                Production2040Contract.loadAndValidate())),
                "A protected input changed during PT cost-allocation analysis");
        Production2040AnalysisSpec.require(accountingReports.equals(
                        ValidateProduction2040AccountingScopes.validatePublished(definition)),
                "The accounting-scope source package changed during PT cost-allocation analysis");
        Production2040AnalysisSpec.require(sources.equals(readSourceInputs(definition,
                        accountingReports)),
                "The existing PT source CSVs changed during PT cost-allocation analysis");
        publishAtomically(definition.analysisDirectory(), destination, reports);
        System.out.printf(Locale.ROOT,
                "2040 PT COST-ALLOCATION ANALYSIS PASS%nscenario=%s output=%s%n"
                        + "No Controller or QSim was started.%n",
                definition.scenarioId(), Production2040Contract.projectPath(destination));
    }

    private static Map<Id<Person>, List<Boolean>> bothInsideFlags(
            Production2040AccountingScopes.Index index) {
        Map<Id<Person>, List<Boolean>> flags = new HashMap<>();
        index.persons().forEach((person, scope) -> flags.put(person, scope.trips().stream()
                .map(trip -> trip.included(Production2040AccountingScopes.Scope.BOTH_INSIDE))
                .toList()));
        return Map.copyOf(flags);
    }

    static SourceInputs readSourceInputs(Production2040AnalysisSpec.ScenarioDefinition definition,
            Map<String, String> accountingReports) throws IOException {
        Path pkmFile = definition.analysisDirectory().resolve("final_pt_pkm_by_route_mode.csv");
        Production2040AnalysisSpec.require(Files.isRegularFile(pkmFile),
                "Missing existing BOTH_INSIDE PT Pkm report " + pkmFile);
        Map<String, SourcePkm> pkm = new TreeMap<>();
        for (Map<String, String> row : csvRows(Files.readString(pkmFile, StandardCharsets.UTF_8),
                pkmFile.toString())) {
            requireScenario(row, definition, pkmFile.toString());
            Production2040AnalysisSpec.require("person_km".equals(row.get("unit")),
                    "Existing PT Pkm report has an unexpected unit");
            requireClose(parse(row, "sample_factor", pkmFile.toString()),
                    Production2040AnalysisSpec.SAMPLE_FACTOR, "Existing PT Pkm sample factor");
            String rawMode = Production2040AnalysisSpec.normalizePtRouteMode(
                    required(row, "pt_route_mode", pkmFile.toString()));
            double samplePkm = parse(row, "sample_in_vehicle_person_km", pkmFile.toString());
            double expandedPkm = parse(row, "expanded_in_vehicle_person_km_factor_20",
                    pkmFile.toString());
            requireCloseKilometres(expandedPkm,
                    Production2040AnalysisSpec.expanded(samplePkm),
                    "Existing BOTH_INSIDE PT Pkm scaling for " + rawMode);
            double coverage = parse(row, "measurement_coverage_percent", pkmFile.toString());
            long missingReferences = parseLong(row, "missing_route_or_vehicle_references",
                    pkmFile.toString());
            Production2040AnalysisSpec.require(coverage >= 100.0 && missingReferences == 0,
                    "Existing BOTH_INSIDE PT Pkm is not fully validated for " + rawMode);
            String definitionText = required(row, "definition", pkmFile.toString())
                    .toLowerCase(Locale.ROOT);
            Production2040AnalysisSpec.require(definitionText.contains("both_inside")
                            && definitionText.contains("in-vehicle"),
                    "Existing PT Pkm definition is not the validated BOTH_INSIDE in-vehicle metric");
            SourcePkm value = new SourcePkm(samplePkm, expandedPkm,
                    parseLong(row, "sample_boardings", pkmFile.toString()), coverage,
                    missingReferences, Set.of(rawMode));
            pkm.merge(routeGroup(rawMode), value, SourcePkm::plus);
        }

        String territorial = accountingReports.get("final_territorial_pt_fkm_by_route_mode.csv");
        Production2040AnalysisSpec.require(territorial != null,
                "Validated accounting-scope package lacks territorial PT Fkm");
        Map<String, Double> fkm = new TreeMap<>();
        for (Map<String, String> row : csvRows(territorial,
                "final_territorial_pt_fkm_by_route_mode.csv")) {
            requireScenario(row, definition, "final_territorial_pt_fkm_by_route_mode.csv");
            if ("TOTAL".equals(row.get("pt_route_mode"))) continue;
            Production2040AnalysisSpec.require("TERRITORIAL_PT_SERVICE".equals(
                            row.get("scope_id")) && "vehicle_km".equals(row.get("unit")),
                    "Existing territorial PT Fkm row has an unexpected scope or unit");
            requireClose(parse(row, "sample_factor",
                    "final_territorial_pt_fkm_by_route_mode.csv"), 1.0,
                    "Existing territorial PT Fkm service scale");
            Production2040AnalysisSpec.require("NOT_APPLICABLE".equals(
                            row.get("factor_20_daily_vehicle_km")),
                    "Territorial PT supply must not have a factor-20 value");
            String mode = required(row, "pt_route_mode",
                    "final_territorial_pt_fkm_by_route_mode.csv");
            double value = parse(row, "full_service_daily_vehicle_km",
                    "final_territorial_pt_fkm_by_route_mode.csv");
            fkm.merge(routeGroup(mode), value, Double::sum);
        }
        for (String mode : Production2040AnalysisSpec.PT_ROUTE_MODES) {
            Production2040AnalysisSpec.require(pkm.containsKey(mode),
                    "Existing BOTH_INSIDE PT Pkm lacks principal route mode " + mode);
            Production2040AnalysisSpec.require(fkm.containsKey(mode),
                    "Existing territorial PT Fkm lacks principal route mode " + mode);
        }
        return new SourceInputs(Map.copyOf(pkm), Map.copyOf(fkm));
    }

    static void validateBothInsidePkm(Production2040VehicleMetrics.Result regional,
            SourceInputs sources) {
        Map<String, VehiclePassenger> observed = groupedVehiclePassengers(
                regional.ptByRouteMode());
        Set<String> modes = new TreeSet<>(sources.bothInsidePkmByGroup().keySet());
        modes.addAll(observed.keySet());
        for (String mode : modes) {
            SourcePkm source = sources.bothInsidePkmByGroup().getOrDefault(mode,
                    SourcePkm.ZERO);
            VehiclePassenger event = observed.getOrDefault(mode, VehiclePassenger.ZERO);
            requireCloseKilometres(event.relevantPassengerMetres() / 1000.0,
                    source.samplePkm(), "Existing BOTH_INSIDE PT Pkm reconciliation for " + mode);
            Production2040AnalysisSpec.require(event.relevantBoardings() == source.boardings(),
                    "Existing BOTH_INSIDE PT boarding reconciliation fails for " + mode);
        }
    }

    static void validateTerritorialFkm(Production2040AccountingEventMetrics.Result accounting,
            SourceInputs sources) {
        Map<String, PtService> reconstructed = groupedServices(accounting.ptByRouteMode());
        Set<String> modes = new TreeSet<>(sources.territorialFkmByGroup().keySet());
        modes.addAll(reconstructed.keySet());
        for (String mode : modes) {
            Production2040AnalysisSpec.require(sources.territorialFkmByGroup().containsKey(mode),
                    "Existing territorial PT Fkm lacks event-observed route mode " + mode);
            PtService event = reconstructed.getOrDefault(mode, PtService.ZERO);
            requireCloseKilometres(event.territorialMetres() / 1000.0,
                    sources.territorialFkmByGroup().get(mode),
                    "Territorial PT Fkm reconciliation for " + mode);
        }
    }

    static List<Allocation> calculateAllocations(SourceInputs sources,
            Production2040AccountingEventMetrics.Result accounting,
            Production2040VehicleMetrics.Result regional) {
        Map<String, PassengerMovement> passenger = groupedPassengerMovements(
                accounting.ptPassengerByRouteMode());
        Map<String, VehiclePassenger> vehiclePassenger = groupedVehiclePassengers(
                regional.ptByRouteMode());
        Map<String, PtService> service = groupedServices(accounting.ptByRouteMode());
        LinkedHashSet<String> modes = new LinkedHashSet<>(Production2040AnalysisSpec.PT_ROUTE_MODES);
        modes.addAll(new TreeSet<>(sources.territorialFkmByGroup().keySet()));
        modes.addAll(new TreeSet<>(sources.bothInsidePkmByGroup().keySet()));
        modes.addAll(new TreeSet<>(passenger.keySet()));
        modes.addAll(new TreeSet<>(service.keySet()));

        List<Allocation> result = new ArrayList<>();
        for (String mode : modes) {
            Production2040AnalysisSpec.require(sources.territorialFkmByGroup().containsKey(mode),
                    "No validated territorial PT Fkm input for route mode " + mode);
            PassengerMovement allPassengers = passenger.getOrDefault(mode,
                    PassengerMovement.ZERO);
            SourcePkm bothInside = sources.bothInsidePkmByGroup().getOrDefault(mode,
                    SourcePkm.ZERO);
            VehiclePassenger allBoardings = vehiclePassenger.getOrDefault(mode,
                    VehiclePassenger.ZERO);
            PtService eventService = service.getOrDefault(mode, PtService.ZERO);
            Set<String> components = new TreeSet<>();
            components.addAll(allPassengers.componentModes());
            components.addAll(bothInside.componentModes());
            components.addAll(allBoardings.componentModes());
            components.addAll(eventService.componentModes());
            if (components.isEmpty()) components.add(mode);
            result.add(calculate(mode, components, allPassengers,
                    sources.territorialFkmByGroup().get(mode), bothInside, allBoardings));
        }
        return List.copyOf(result);
    }

    static Allocation calculate(String mode, Set<String> componentModes,
            PassengerMovement allPassengers, double territorialFullServiceFkm,
            SourcePkm bothInside, VehiclePassenger allBoardings) {
        requireFiniteNonNegative(allPassengers.territorialMetres(),
                "Territorial all-passenger PT Pkm for " + mode);
        requireFiniteNonNegative(territorialFullServiceFkm,
                "Territorial full-service PT Fkm for " + mode);
        requireFiniteNonNegative(bothInside.samplePkm(),
                "BOTH_INSIDE PT Pkm for " + mode);
        double samplePkm = allPassengers.territorialMetres() / 1000.0;
        double expandedPkm = Production2040AnalysisSpec.expanded(samplePkm);
        double expandedBothInside = Production2040AnalysisSpec.expanded(bothInside.samplePkm());
        String occupancyStatus;
        String allocationStatus;
        double occupancy;
        double allocatedFkm;
        if (territorialFullServiceFkm == 0.0) {
            Production2040AnalysisSpec.require(expandedPkm == 0.0 && expandedBothInside == 0.0,
                    "Zero territorial PT Fkm is inconsistent with passenger activity for " + mode);
            occupancy = Double.NaN;
            allocatedFkm = Double.NaN;
            occupancyStatus = "NOT_APPLICABLE_VALID_ZERO_ACTIVITY";
            allocationStatus = "NOT_APPLICABLE_VALID_ZERO_ACTIVITY";
        } else {
            occupancy = expandedPkm / territorialFullServiceFkm;
            Production2040AnalysisSpec.require(Double.isFinite(occupancy) && occupancy >= 0.0,
                    "Invalid average occupancy for " + mode);
            if (occupancy == 0.0) {
                Production2040AnalysisSpec.require(expandedBothInside == 0.0,
                        "Zero average occupancy cannot allocate non-zero BOTH_INSIDE Pkm for "
                                + mode);
                allocatedFkm = 0.0;
                occupancyStatus = "VALID_ZERO_PASSENGER_ACTIVITY";
                allocationStatus = "VALID_ZERO_PASSENGER_ACTIVITY";
            } else {
                allocatedFkm = expandedBothInside / occupancy;
                Production2040AnalysisSpec.require(Double.isFinite(allocatedFkm)
                                && allocatedFkm >= 0.0,
                        "Invalid allocated BOTH_INSIDE Fkm for " + mode);
                occupancyStatus = "PASS_REPORTED_FOR_REVIEW";
                allocationStatus = "PASS_ACCOUNTING_ALLOCATION";
            }
        }
        return new Allocation(mode, Set.copyOf(componentModes), samplePkm, expandedPkm,
                territorialFullServiceFkm, occupancy, occupancyStatus, bothInside.samplePkm(),
                expandedBothInside, allocatedFkm, allocationStatus,
                allPassengers.movementEvents(), allBoardings.boardings(),
                allBoardings.completedBoardings(), bothInside.boardings(),
                bothInside.coveragePercent());
    }

    static void validateAllocations(List<Allocation> allocations) {
        for (Allocation value : allocations) {
            requireFiniteNonNegative(value.territorialAllPassengerSamplePkm(),
                    "Territorial all-passenger sample Pkm");
            requireFiniteNonNegative(value.territorialAllPassengerExpandedPkm(),
                    "Territorial all-passenger expanded Pkm");
            requireFiniteNonNegative(value.territorialFullServiceFkm(),
                    "Territorial full-service Fkm");
            requireFiniteNonNegative(value.bothInsideSamplePkm(), "BOTH_INSIDE sample Pkm");
            requireFiniteNonNegative(value.bothInsideExpandedPkm(),
                    "BOTH_INSIDE expanded Pkm");
            if (Double.isNaN(value.occupancy())) {
                Production2040AnalysisSpec.require(Double.isNaN(value.allocatedBothInsideFkm()),
                        "Undefined occupancy must not have allocated Fkm");
                continue;
            }
            requireFiniteNonNegative(value.occupancy(), "Average occupancy");
            requireFiniteNonNegative(value.allocatedBothInsideFkm(),
                    "Allocated BOTH_INSIDE Fkm");
            requireCloseKilometres(value.allocatedBothInsideFkm() * value.occupancy(),
                    value.bothInsideExpandedPkm(),
                    "Allocated Fkm multiplied by occupancy for " + value.mode());
        }
    }

    private static Map<String, PassengerMovement> groupedPassengerMovements(
            Map<String, Production2040AccountingEventMetrics.PtPassenger> source) {
        Map<String, PassengerMovement> result = new TreeMap<>();
        source.forEach((rawMode, value) -> result.merge(routeGroup(rawMode),
                new PassengerMovement(value.uncutMetres(), value.territorialMetres(),
                        value.movementEvents(), value.crossingLinkCount(),
                        value.crossingPassengerMetres(), Set.of(rawMode)),
                PassengerMovement::plus));
        return Map.copyOf(result);
    }

    private static Map<String, VehiclePassenger> groupedVehiclePassengers(
            Map<String, Production2040VehicleMetrics.PtMetric> source) {
        Map<String, VehiclePassenger> result = new TreeMap<>();
        source.forEach((rawMode, value) -> result.merge(routeGroup(rawMode),
                new VehiclePassenger(value.relevantPassengerMetres(), value.boardings(),
                        value.completedBoardings(), value.relevantBoardings(),
                        value.relevantCompletedBoardings(), Set.of(rawMode)),
                VehiclePassenger::plus));
        return Map.copyOf(result);
    }

    private static Map<String, PtService> groupedServices(
            Map<String, Production2040AccountingEventMetrics.PtService> source) {
        Map<String, PtService> result = new TreeMap<>();
        source.forEach((rawMode, value) -> result.merge(routeGroup(rawMode),
                new PtService(value.uncutMetres(), value.territorialMetres(),
                        value.crossingLinkCount(), value.crossingServiceMetres(), Set.of(rawMode)),
                PtService::plus));
        return Map.copyOf(result);
    }

    static List<VehicleMetadata> readVehicleMetadata(TransitSchedule schedule,
            Vehicles transitVehicles) {
        Map<Id<Vehicle>, VehicleScheduleUse> uses = new HashMap<>();
        schedule.getTransitLines().values().forEach(line -> line.getRoutes().values()
                .forEach(route -> {
                    String mode = Production2040AnalysisSpec.normalizePtRouteMode(
                            route.getTransportMode());
                    route.getDepartures().values().forEach(departure -> {
                        Vehicle vehicle = transitVehicles.getVehicles().get(departure.getVehicleId());
                        Production2040AnalysisSpec.require(vehicle != null,
                                "Transit schedule references missing vehicle "
                                        + departure.getVehicleId());
                        uses.computeIfAbsent(vehicle.getId(), ignored -> new VehicleScheduleUse())
                                .add(mode);
                    });
                }));
        Map<String, MutableVehicleMetadata> grouped = new TreeMap<>();
        uses.forEach((vehicleId, use) -> {
            Vehicle vehicle = transitVehicles.getVehicles().get(vehicleId);
            VehicleType type = vehicle.getType();
            Production2040AnalysisSpec.require(type != null,
                    "Transit vehicle has no vehicle type " + vehicleId);
            grouped.computeIfAbsent(type.getId().toString(), ignored ->
                    new MutableVehicleMetadata(type)).add(vehicleId, use);
        });
        List<VehicleMetadata> result = new ArrayList<>();
        grouped.forEach((type, value) -> result.add(value.freeze()));
        return List.copyOf(result);
    }

    static Map<String, String> buildReports(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            List<Allocation> allocations, List<VehicleMetadata> vehicleMetadata,
            Production2040VehicleMetrics.Result regional,
            Production2040AccountingEventMetrics.Result accounting, SourceInputs sources) {
        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("pt_external_cost_inputs.csv", externalInputsCsv(definition, allocations));
        reports.put("pt_cost_allocation_quality_checks.csv", qualityCsv(definition, allocations,
                regional, accounting, sources));
        reports.put("pt_vehicle_unit_metadata.csv", vehicleMetadataCsv(definition,
                vehicleMetadata));
        reports.put("pt_cost_allocation_report.md", report(definition, allocations,
                vehicleMetadata, accounting));
        return Map.copyOf(reports);
    }

    private static String externalInputsCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            List<Allocation> allocations) {
        StringBuilder csv = new StringBuilder("scenario_id,pt_route_mode,component_normalized_route_modes,unit,demand_sample_factor,demand_expansion_factor,supply_expansion_factor,day_basis,territorial_all_passenger_sample_pkm_per_day,territorial_all_passenger_expanded_pkm_per_day,territorial_all_passenger_expanded_pkm_per_year_365,territorial_all_passenger_million_pkm_per_year_365,territorial_full_service_fkm_per_day,territorial_full_service_fkm_per_year_365,territorial_full_service_million_fkm_per_year_365,average_occupancy_expanded_pkm_per_full_service_fkm,occupancy_status,both_inside_in_vehicle_sample_pkm_per_day,both_inside_in_vehicle_expanded_pkm_per_day,both_inside_in_vehicle_expanded_pkm_per_year_365,both_inside_in_vehicle_million_pkm_per_year_365,allocated_both_inside_fkm_per_day,allocated_both_inside_fkm_per_year_365,allocated_both_inside_million_fkm_per_year_365,allocation_status,all_passenger_link_movement_event_count,all_passenger_boarding_count,all_passenger_completed_boarding_count,both_inside_source_boarding_count,both_inside_source_coverage_percent,data_coverage_status,definition\n");
        for (Allocation value : allocations) {
            double allAnnual = value.territorialAllPassengerExpandedPkm() * DAYS_PER_YEAR;
            double serviceAnnual = value.territorialFullServiceFkm() * DAYS_PER_YEAR;
            double bothAnnual = value.bothInsideExpandedPkm() * DAYS_PER_YEAR;
            double allocatedAnnual = Double.isNaN(value.allocatedBothInsideFkm()) ? Double.NaN
                    : value.allocatedBothInsideFkm() * DAYS_PER_YEAR;
            csv.append(definition.scenarioId()).append(',').append(value.mode()).append(',')
                    .append(quote(joinSorted(value.componentModes())))
                    .append(",person_km_vehicle_km_and_ratio,")
                    .append(number(Production2040AnalysisSpec.SAMPLE_FACTOR)).append(',')
                    .append(number(Production2040AnalysisSpec.EXPANSION_FACTOR)).append(",1.0,")
                    .append("technical_weekday,")
                    .append(number(value.territorialAllPassengerSamplePkm())).append(',')
                    .append(number(value.territorialAllPassengerExpandedPkm())).append(',')
                    .append(number(allAnnual)).append(',').append(number(allAnnual / 1_000_000.0))
                    .append(',').append(number(value.territorialFullServiceFkm())).append(',')
                    .append(number(serviceAnnual)).append(',')
                    .append(number(serviceAnnual / 1_000_000.0)).append(',')
                    .append(optionalNumber(value.occupancy())).append(',')
                    .append(value.occupancyStatus()).append(',')
                    .append(number(value.bothInsideSamplePkm())).append(',')
                    .append(number(value.bothInsideExpandedPkm())).append(',')
                    .append(number(bothAnnual)).append(',')
                    .append(number(bothAnnual / 1_000_000.0)).append(',')
                    .append(optionalNumber(value.allocatedBothInsideFkm())).append(',')
                    .append(optionalNumber(allocatedAnnual)).append(',')
                    .append(optionalNumber(Double.isNaN(allocatedAnnual) ? Double.NaN
                            : allocatedAnnual / 1_000_000.0)).append(',')
                    .append(value.allocationStatus()).append(',')
                    .append(value.allPassengerMovementEvents()).append(',')
                    .append(value.allPassengerBoardings()).append(',')
                    .append(value.allPassengerCompletedBoardings()).append(',')
                    .append(value.bothInsideSourceBoardings()).append(',')
                    .append(number(value.bothInsideSourceCoveragePercent())).append(',')
                    .append("PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE,")
                    .append(quote("territorial all-passenger Pkm is event-observed in-vehicle movement within Munich for every non-driver passenger; BOTH_INSIDE Pkm is the existing validated route-stop metric; demand is expanded once by 20 while full-service PT Fkm is not expanded; annual values are mechanical 365-day equivalents; allocated Fkm is an accounting quantity"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String qualityCsv(Production2040AnalysisSpec.ScenarioDefinition definition,
            List<Allocation> allocations, Production2040VehicleMetrics.Result regional,
            Production2040AccountingEventMetrics.Result accounting, SourceInputs sources) {
        StringBuilder csv = new StringBuilder("scenario_id,unit,check_id,status,observed_value,threshold_or_expected,definition\n");
        check(csv, definition, "validated_production_output", "PASS", "validated", "exact",
                "normal shutdown, run identity, output config, production analysis and protected inputs");
        check(csv, definition, "validated_accounting_scope_source", "PASS", "validated", "exact",
                "existing accounting-scope package and territorial PT Fkm source");
        check(csv, definition, "pt_event_reference_integrity", "PASS",
                Long.toString(regional.missingTransitReferences() + regional.unmatchedAlightings()
                        + regional.openBoardings()), "0",
                "all transit routes, vehicles, boarding/alighting evidence and stop references reconcile");
        check(csv, definition, "transit_driver_exclusion", "PASS", "handled at event state",
                "driver not in passenger set",
                "TransitDriverStarts identifies the driver; passenger movement is called only for non-driver onboard persons");
        check(csv, definition, "pt_pseudolink_method", "PASS", "shared accounting clip",
                "geometric clip or strict point anchor",
                "territorial passenger Pkm reuses the existing ordinary-link and zero-geometry PT pseudolink treatment");
        check(csv, definition, "pt_zero_model_length_links", "PASS",
                Long.toString(accounting.ptPseudolinks().zeroModelLengthLinks()),
                "reported; zero territorial distance",
                "zero-model-length PT links contribute zero territorial service and passenger distance");
        for (Allocation value : allocations) {
            check(csv, definition, "both_inside_pt_pkm_reconciliation_" + value.mode(), "PASS",
                    number(value.bothInsideSamplePkm()), "existing final_pt_pkm_by_route_mode.csv",
                    "existing BOTH_INSIDE in-vehicle Pkm was reproduced from the validated event/schedule pipeline");
            check(csv, definition, "territorial_pt_fkm_reconciliation_" + value.mode(), "PASS",
                    number(value.territorialFullServiceFkm()),
                    "existing accounting_scopes/final_territorial_pt_fkm_by_route_mode.csv",
                    "reconstructed territorial service reconciles before it is used for occupancy");
            check(csv, definition, "territorial_all_passenger_pkm_" + value.mode(), "REPORTED",
                    number(value.territorialAllPassengerSamplePkm()),
                    "sample person_km/day",
                    "all passenger movement is not filtered by residence or BOTH_INSIDE endpoints");
            check(csv, definition, "occupancy_" + value.mode(), "REPORTED",
                    optionalNumber(value.occupancy()), value.occupancyStatus(),
                    "expanded territorial all-passenger Pkm divided by full-service territorial Fkm; reported for review without a capacity comparison");
            if (Double.isNaN(value.occupancy())) {
                check(csv, definition, "allocation_identity_" + value.mode(), "REPORTED",
                        "NOT_APPLICABLE", "valid zero activity",
                        "zero territorial service and zero demand leave occupancy and allocation undefined rather than using a zero fallback");
            } else {
                check(csv, definition, "allocation_identity_" + value.mode(), "PASS",
                        number(value.allocatedBothInsideFkm() * value.occupancy()),
                        number(value.bothInsideExpandedPkm()),
                        "allocated Fkm multiplied by occupancy reconciles to expanded BOTH_INSIDE Pkm");
            }
        }
        check(csv, definition, "demand_scaling", "PASS", "20.0", "factor 20 exactly once",
                "sample territorial all-passenger and BOTH_INSIDE demand are expanded once");
        check(csv, definition, "supply_scaling", "PASS", "1.0", "no factor 20",
                "territorial full-service PT Fkm remains at simulated full service scale");
        check(csv, definition, "annualisation", "REPORTED", "365 multiplier",
                "mechanical only", "technical weekday multiplied by 365; not an authoritative annual total");
        check(csv, definition, "vehicle_unit_conversion", "REPORTED", "NOT_INFERRED",
                "external Excel mapping required",
                "no train-to-carriage or MATSim-vehicle-to-Excel-unit conversion is inferred");
        return csv.toString();
    }

    private static String vehicleMetadataCsv(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            List<VehicleMetadata> metadata) {
        StringBuilder csv = new StringBuilder("scenario_id,vehicle_type_id,normalized_route_modes,scheduled_vehicle_count,scheduled_departure_count,seats,standing_room,nominal_seats_plus_standing,vehicle_length_m,vehicle_width_m,pcu_equivalents,maximum_velocity_m_per_s,network_mode,fkm_unit_interpretation,train_carriage_conversion,excel_unit_compatibility_status,definition\n");
        for (VehicleMetadata value : metadata) {
            csv.append(definition.scenarioId()).append(',').append(quote(value.vehicleTypeId()))
                    .append(',').append(quote(joinSorted(value.routeModes()))).append(',')
                    .append(value.scheduledVehicleCount()).append(',')
                    .append(value.scheduledDepartureCount()).append(',')
                    .append(optionalInteger(value.seats())).append(',')
                    .append(optionalInteger(value.standingRoom())).append(',')
                    .append(optionalInteger(value.nominalCapacity())).append(',')
                    .append(optionalFinite(value.lengthMetres())).append(',')
                    .append(optionalFinite(value.widthMetres())).append(',')
                    .append(optionalFinite(value.pcuEquivalents())).append(',')
                    .append(optionalFinite(value.maximumVelocityMetresPerSecond())).append(',')
                    .append(quote(value.networkMode())).append(',')
                    .append(quote("one event-observed MATSim transit vehicle trajectory kilometre for a scheduled vehicle ID"))
                    .append(",NOT_INFERRED,REQUIRES_EXTERNAL_UNIT_MAPPING,")
                    .append(quote("capacity and dimensions are descriptive metadata only; they are not used to validate expanded occupancy or to infer train carriages"))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String report(Production2040AnalysisSpec.ScenarioDefinition definition,
            List<Allocation> allocations, List<VehicleMetadata> vehicleMetadata,
            Production2040AccountingEventMetrics.Result accounting) {
        StringBuilder composition = new StringBuilder();
        for (Allocation value : allocations) {
            if (!composition.isEmpty()) composition.append("; ");
            composition.append(value.mode()).append(": territorial all-passenger sample Pkm=")
                    .append(number(value.territorialAllPassengerSamplePkm()))
                    .append(", full-service territorial Fkm=")
                    .append(number(value.territorialFullServiceFkm()))
                    .append(", occupancy=").append(optionalNumber(value.occupancy()))
                    .append(", allocated BOTH_INSIDE Fkm=")
                    .append(optionalNumber(value.allocatedBothInsideFkm()));
        }
        return "# " + definition.scenarioId().replace('_', ' ')
                + " PT cost allocation\n\n"
                + "## Scope and sources\n\n"
                + "This controller-free package combines three existing or reconstructed activity measures. Territorial all-passenger Pkm is reconstructed from the final event stream for every non-driver person who is actually on board a transit vehicle, without a resident, home-location or `BOTH_INSIDE` filter. It counts only the in-vehicle part of each event movement within the Munich municipal boundary. The existing `final_pt_pkm_by_route_mode.csv` supplies unchanged `BOTH_INSIDE` Pkm: full routed in-vehicle distance between event-observed boarding and alighting stops for main trips whose two endpoints are inside Munich. It excludes access, egress and transfer walking, but a qualifying trip may retain in-vehicle portions outside Munich. Existing `accounting_scopes/final_territorial_pt_fkm_by_route_mode.csv` supplies full-service territorial Fkm.\n\n"
                + "Passenger movement is attributed at the time of every transit link movement to the current non-driver onboard set. Consequently, a first-link remainder or last-link correction is multiplied only by passengers present for that exact event; no later boarding or alighting count is used as a proxy. Transfers are separate boardings on their actual vehicle and route mode, so they neither double-count a passenger segment nor erase a mode change. Repeated link occurrences and direction are preserved by the final event stream. Ordinary links use the established geometric clipping; positive-length zero-geometry PT pseudolinks use the established strict point-anchor proxy; zero-model-length links contribute zero. "
                + accounting.ptPseudolinks().usedPointAnchoredLinks()
                + " used positive-length point-anchored PT pseudolinks and "
                + accounting.ptPseudolinks().zeroModelLengthLinks()
                + " used zero-model-length PT links were observed in the shared event accounting.\n\n"
                + "## Equations and scaling\n\n"
                + "For scenario `s` and route mode `m`, `occupancy(s,m) = expanded territorial all-passenger Pkm(s,m) / territorial full-service Fkm(s,m)`. `allocated BOTH_INSIDE Fkm(s,m) = expanded BOTH_INSIDE in-vehicle Pkm(s,m) / occupancy(s,m)`. Demand values are multiplied by exactly 20 from the five-percent sample. Transit supply remains at full service scale and is multiplied by one. Every annual field is a mechanical `365`-day equivalent; it is not an empirically validated annual total.\n\n"
                + "The allocation assumes that BOTH_INSIDE travellers experience the average territorial occupancy for their route mode. That same average is applied to any outside-Munich in-vehicle portion retained in the BOTH_INSIDE Pkm numerator. Allocated Fkm may therefore exceed territorial Fkm and is an accounting allocation, not marginal or avoidable service. No allocation is capped to force a spatial inequality. A zero territorial Fkm denominator with zero demand is labelled not applicable; any zero denominator with passenger activity fails publication. A positive service denominator with zero passenger Pkm produces an explicit zero-activity status and cannot allocate non-zero BOTH_INSIDE Pkm.\n\n"
                + "## Scenario-specific route-mode composition\n\n"
                + composition + ". Route modes outside bus, tram, subway and rail remain in a transparent `ferry/other` group with their normalized component modes listed in the CSV.\n\n"
                + "## Vehicle unit limitation\n\n"
                + vehicleMetadata.size()
                + " scheduled transit vehicle types are listed in `pt_vehicle_unit_metadata.csv`. A reported Fkm is one event-observed MATSim vehicle trajectory kilometre. Vehicle IDs and vehicle-type metadata do not establish a train-to-carriage conversion or prove that this is the unit expected by an external Excel cost factor. No such conversion has been inferred; an external mapping is required before applying a vehicle-unit-specific factor. Capacity metadata is descriptive only and is not compared with expanded occupancy because those quantities can be on different scales.\n\n"
                + "## Validation and interpretation\n\n"
                + "Publication requires normal completed production output, matching scenario/run/config identity, the complete validated production analysis, the complete validated accounting-scope package, protected input integrity, source Pkm/Fkm reconciliation, event reference integrity, and the allocation identity. The uncut regional PT Fkm is reconciled through the established accounting event validator before territorial source Fkm is used. This analyzer starts no Controller, QSim, routing, or simulation.\n";
    }

    static void publishAtomically(Path parent, Path destination, Map<String, String> reports)
            throws IOException {
        Production2040AnalysisSpec.require(Files.isDirectory(parent),
                "Missing existing production analysis directory " + parent);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "PT cost-allocation analysis already exists and will not be overwritten");
        Path temporary = parent.resolve(".pt-cost-allocation-tmp-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            for (var entry : reports.entrySet()) {
                Production2040AnalysisSpec.require(!entry.getKey().contains("/")
                                && !entry.getKey().contains("\\\\"),
                        "Invalid PT cost-allocation report filename " + entry.getKey());
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
                || !temporary.getFileName().toString().startsWith(".pt-cost-allocation-tmp-")) {
            throw new IllegalStateException("Refusing to clean unexpected temporary path "
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
            throw new IllegalStateException("Could not clean temporary PT cost-allocation output "
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

    private static String routeGroup(String value) {
        String normalized = Production2040AnalysisSpec.normalizePtRouteMode(value);
        return Production2040AnalysisSpec.PT_ROUTE_MODES.contains(normalized)
                ? normalized : OTHER_ROUTE_MODE;
    }

    private static void check(StringBuilder csv,
            Production2040AnalysisSpec.ScenarioDefinition definition, String id, String status,
            String observed, String expected, String explanation) {
        csv.append(definition.scenarioId()).append(",check,").append(id).append(',')
                .append(status).append(',').append(quote(observed)).append(',')
                .append(quote(expected)).append(',').append(quote(explanation)).append('\n');
    }

    private static void requireFiniteNonNegative(double value, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(value) && value >= 0.0,
                "Invalid " + label + ": " + value);
    }

    private static void requireClose(double actual, double expected, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(actual) && Double.isFinite(expected)
                        && Math.abs(actual - expected) <= 1e-12,
                label + " does not reconcile: actual=" + actual + " expected=" + expected);
    }

    private static void requireCloseKilometres(double actual, double expected, String label) {
        Production2040AnalysisSpec.require(Double.isFinite(actual) && Double.isFinite(expected)
                        && Math.abs(actual - expected) * 1000.0
                        <= RECONCILIATION_TOLERANCE_METRES,
                label + " does not reconcile: actual=" + actual + " expected=" + expected);
    }

    private static String number(double value) {
        Production2040AnalysisSpec.require(Double.isFinite(value),
                "Non-finite PT cost-allocation report value");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String optionalNumber(double value) {
        return Double.isNaN(value) ? "NOT_APPLICABLE" : number(value);
    }

    private static String optionalFinite(double value) {
        return Double.isFinite(value) ? number(value) : "NOT_SPECIFIED";
    }

    private static String optionalInteger(Integer value) {
        return value == null ? "NOT_SPECIFIED" : Integer.toString(value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String joinSorted(Set<String> values) {
        return String.join("|", new TreeSet<>(values));
    }

    record SourceInputs(Map<String, SourcePkm> bothInsidePkmByGroup,
                        Map<String, Double> territorialFkmByGroup) { }

    record SourcePkm(double samplePkm, double expandedPkm, long boardings,
                     double coveragePercent, long missingReferences,
                     Set<String> componentModes) {
        static final SourcePkm ZERO = new SourcePkm(0, 0, 0, 100, 0, Set.of());

        SourcePkm plus(SourcePkm other) {
            Set<String> components = new TreeSet<>(componentModes);
            components.addAll(other.componentModes);
            return new SourcePkm(samplePkm + other.samplePkm,
                    expandedPkm + other.expandedPkm, boardings + other.boardings,
                    Math.min(coveragePercent, other.coveragePercent),
                    missingReferences + other.missingReferences, Set.copyOf(components));
        }
    }

    record PassengerMovement(double uncutMetres, double territorialMetres, long movementEvents,
                             long crossingLinkCount, double crossingPassengerMetres,
                             Set<String> componentModes) {
        static final PassengerMovement ZERO = new PassengerMovement(0, 0, 0, 0, 0, Set.of());

        PassengerMovement plus(PassengerMovement other) {
            Set<String> components = new TreeSet<>(componentModes);
            components.addAll(other.componentModes);
            return new PassengerMovement(uncutMetres + other.uncutMetres,
                    territorialMetres + other.territorialMetres,
                    movementEvents + other.movementEvents,
                    crossingLinkCount + other.crossingLinkCount,
                    crossingPassengerMetres + other.crossingPassengerMetres,
                    Set.copyOf(components));
        }
    }

    record VehiclePassenger(double relevantPassengerMetres, long boardings,
                            long completedBoardings, long relevantBoardings,
                            long relevantCompletedBoardings, Set<String> componentModes) {
        static final VehiclePassenger ZERO = new VehiclePassenger(0, 0, 0, 0, 0, Set.of());

        VehiclePassenger plus(VehiclePassenger other) {
            Set<String> components = new TreeSet<>(componentModes);
            components.addAll(other.componentModes);
            return new VehiclePassenger(relevantPassengerMetres + other.relevantPassengerMetres,
                    boardings + other.boardings, completedBoardings + other.completedBoardings,
                    relevantBoardings + other.relevantBoardings,
                    relevantCompletedBoardings + other.relevantCompletedBoardings,
                    Set.copyOf(components));
        }
    }

    record PtService(double uncutMetres, double territorialMetres, long crossingLinkCount,
                     double crossingServiceMetres, Set<String> componentModes) {
        static final PtService ZERO = new PtService(0, 0, 0, 0, Set.of());

        PtService plus(PtService other) {
            Set<String> components = new TreeSet<>(componentModes);
            components.addAll(other.componentModes);
            return new PtService(uncutMetres + other.uncutMetres,
                    territorialMetres + other.territorialMetres,
                    crossingLinkCount + other.crossingLinkCount,
                    crossingServiceMetres + other.crossingServiceMetres,
                    Set.copyOf(components));
        }
    }

    record Allocation(String mode, Set<String> componentModes,
                      double territorialAllPassengerSamplePkm,
                      double territorialAllPassengerExpandedPkm,
                      double territorialFullServiceFkm, double occupancy, String occupancyStatus,
                      double bothInsideSamplePkm, double bothInsideExpandedPkm,
                      double allocatedBothInsideFkm, String allocationStatus,
                      long allPassengerMovementEvents, long allPassengerBoardings,
                      long allPassengerCompletedBoardings, long bothInsideSourceBoardings,
                      double bothInsideSourceCoveragePercent) { }

    record VehicleMetadata(String vehicleTypeId, Set<String> routeModes,
                           long scheduledVehicleCount, long scheduledDepartureCount,
                           Integer seats, Integer standingRoom, Integer nominalCapacity,
                           double lengthMetres, double widthMetres, double pcuEquivalents,
                           double maximumVelocityMetresPerSecond, String networkMode) { }

    private static final class VehicleScheduleUse {
        private long departures;
        private final Set<String> modes = new TreeSet<>();

        private void add(String mode) {
            departures++;
            modes.add(mode);
        }
    }

    private static final class MutableVehicleMetadata {
        private final VehicleType type;
        private final Set<Id<Vehicle>> vehicles = new TreeSet<>(Comparator.comparing(Object::toString));
        private final Set<String> modes = new TreeSet<>();
        private long departures;

        private MutableVehicleMetadata(VehicleType type) {
            this.type = type;
        }

        private void add(Id<Vehicle> vehicle, VehicleScheduleUse use) {
            vehicles.add(vehicle);
            modes.addAll(use.modes);
            departures += use.departures;
        }

        private VehicleMetadata freeze() {
            VehicleCapacity capacity = type.getCapacity();
            Integer seats = capacity == null ? null : capacity.getSeats();
            Integer standing = capacity == null ? null : capacity.getStandingRoom();
            Integer total = seats == null || standing == null ? null : seats + standing;
            return new VehicleMetadata(type.getId().toString(), Set.copyOf(modes), vehicles.size(),
                    departures, seats, standing, total, type.getLength(), type.getWidth(),
                    type.getPcuEquivalents(), type.getMaximumVelocity(),
                    type.hasNetworkMode() ? type.getNetworkMode() : "NOT_SPECIFIED");
        }
    }
}
