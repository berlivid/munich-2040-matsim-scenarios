package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

class Production2040AnalysisTest {
    private static final Id<Person> PASSENGER = Id.createPersonId("passenger");
    private static final Id<Person> PASSENGER_2 = Id.createPersonId("passenger-2");
    private static final Id<Person> DRIVER = Id.createPersonId("driver");
    private static final Map<String, String> EXPECTED_RAPTOR_DEFAULTS = Map.ofEntries(
            Map.entry("intermodalAccessEgressModeSelection", "CalcLeastCostModePerStop"),
            Map.entry("intermodalLegOnlyHandling", "forbid"),
            Map.entry("scoringParameters", "Default"),
            Map.entry("transferCalculation", "Initial"),
            Map.entry("transferPenaltyBaseCost", "0.0"),
            Map.entry("transferPenaltyCostPerTravelTimeHour", "0.0"),
            Map.entry("transferPenaltyMaxCost", "Infinity"),
            Map.entry("transferPenaltyMinCost", "-Infinity"),
            Map.entry("transferWalkMargin", "5.0"),
            Map.entry("useCapacityConstraints", "false"),
            Map.entry("useIntermodalAccessEgress", "false"),
            Map.entry("useModeMappingForPassengers", "false"),
            Map.entry("useRangeQuery", "false"));

    @Test
    void definesScenarioNeutralScalingAndLateWindow() {
        assertEquals(20.0, Production2040AnalysisSpec.expanded(1.0));
        assertEquals(0.05, Production2040AnalysisSpec.SAMPLE_FACTOR);
        assertEquals(51, Production2040AnalysisSpec.LATE_FIRST);
        assertEquals(60, Production2040AnalysisSpec.LATE_LAST);
        assertEquals("BAU_2040", Production2040AnalysisSpec.scenario("bau").scenarioId());
        assertEquals("FAST_TRACK_2040",
                Production2040AnalysisSpec.scenario("Fast Track").scenarioId());
        assertThrows(IllegalArgumentException.class,
                () -> Production2040AnalysisSpec.scenario("calibration"));
    }

    @Test
    void calculatesTrendAndRangeAndFailsOnMissingIteration() {
        List<Production2040AnalysisSpec.IterationSnapshot> rows = iterations(iteration ->
                Map.of("car", 25L + iteration, "pt", 25L, "bike", 25L,
                        "walk", 25L - iteration));
        var car = Production2040AnalysisSpec.lateStatistics(rows).get("car");
        assertEquals(1.0, car.linearTrendPpPerIteration(), 1e-12);
        assertEquals(9.0, car.rangePercentagePoints(), 1e-12);
        assertThrows(IllegalStateException.class,
                () -> Production2040AnalysisSpec.lateStatistics(rows.subList(0, 60)));
    }

    @Test
    void reproducesRound5LateModalMeansReadOnly() throws Exception {
        Path file = Path.of("scenarios/munich_calibration_2019/output/"
                + "literature-based-scoring-calibration-round-5/analysis/"
                + "round_5_iteration_mode_shares.csv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Production2040AnalysisSpec.IterationSnapshot> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
            rows.add(new Production2040AnalysisSpec.IterationSnapshot(
                    Integer.parseInt(f[0]), Long.parseLong(f[1]),
                    Map.of("car", Long.parseLong(f[2]), "pt", Long.parseLong(f[4]),
                            "bike", Long.parseLong(f[6]), "walk", Long.parseLong(f[8])),
                    Map.of()));
        }
        var late = Production2040AnalysisSpec.lateStatistics(rows);
        assertEquals(35.290934790, late.get("car").meanSharePercent(), 5e-10);
        assertEquals(24.763796442, late.get("pt").meanSharePercent(), 5e-10);
        assertEquals(20.252797270, late.get("bike").meanSharePercent(), 5e-10);
        assertEquals(19.692471498, late.get("walk").meanSharePercent(), 5e-10);
    }

    @Test
    void identifiesAllExpectedMainModesAndKeepsPtStagesTogether() throws Exception {
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        Coord inside = new Coord(boundary.geometry().getInteriorPoint().getX(),
                boundary.geometry().getInteriorPoint().getY());
        var factory = PopulationUtils.getFactory();
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
            Plan plan = factory.createPlan();
            plan.addActivity(factory.createActivityFromCoord("home", inside));
            plan.addLeg(factory.createLeg(mode));
            plan.addActivity(factory.createActivityFromCoord("work", inside));
            assertEquals(mode, filter.classify(plan).getFirst().inputMainMode());
        }
    }

    @Test
    void measuresMainTripPkmMedianTimeAndMissingValuesWithoutBeelineFallback(
            @TempDir Path temp) throws Exception {
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        Coord inside = new Coord(boundary.geometry().getInteriorPoint().getX(),
                boundary.geometry().getInteriorPoint().getY());
        Path trips = temp.resolve("trips.csv.gz");
        String header = "person;main_mode;start_x;start_y;end_x;end_y;traveled_distance;trav_time\n";
        String row1 = "p1;car;" + inside.getX() + ';' + inside.getY() + ';'
                + inside.getX() + ';' + inside.getY() + ";1000;00:10:00\n";
        String row2 = "p2;car;" + inside.getX() + ';' + inside.getY() + ';'
                + inside.getX() + ';' + inside.getY() + ";3000;00:20:00\n";
        String row3 = "p3;car;" + inside.getX() + ';' + inside.getY() + ';'
                + inside.getX() + ';' + inside.getY() + ";;00:05:00\n";
        try (var writer = new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(trips)), StandardCharsets.UTF_8)) {
            writer.write(header + row1 + row2 + row3);
        }
        var result = AnalyzeProduction2040Output.readTripMeasurements(trips,
                new MunichTripBoundaryFilter(boundary));
        var car = result.byMode().get("car");
        assertEquals(2, car.validRecords());
        assertEquals(1, car.invalidRecords());
        assertEquals(2000.0, car.meanDistanceMetres());
        assertEquals(2000.0, car.medianDistanceMetres());
        assertEquals(900.0, car.meanTimeSeconds());
        assertTrue(result.distanceDefinition().contains("not event distance"));
    }

    @Test
    void carFkmUsesFirstAndLastLinkFractionsWithoutPersonDoubleCounting() {
        Fixture fixture = fixture();
        Id<Vehicle> vehicle = Id.createVehicleId("car");
        fixture.metrics.handleEvent(new VehicleEntersTrafficEvent(0, DRIVER,
                fixture.link1, vehicle, "car", 0.5));
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, vehicle));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, vehicle, fixture.link2));
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(3, PASSENGER_2, vehicle));
        fixture.metrics.handleEvent(new VehicleLeavesTrafficEvent(4, DRIVER,
                fixture.link2, vehicle, "car", 0.25));
        var result = fixture.metrics.result();
        assertEquals(100.0, result.carMetres(), 1e-12);
        assertEquals(1, result.carVehicles());
    }

    @Test
    void transitVehicleIsExcludedFromCarFkm() {
        Fixture fixture = fixture();
        Id<Vehicle> vehicle = Id.createVehicleId("bus");
        fixture.startTransit(vehicle, "bus");
        fixture.metrics.handleEvent(new VehicleEntersTrafficEvent(0, DRIVER,
                fixture.link1, vehicle, "car", 1.0));
        fixture.metrics.handleEvent(new LinkEnterEvent(1, vehicle, fixture.link2));
        assertEquals(0.0, fixture.metrics.result().carMetres());
        assertEquals(200.0, fixture.metrics.result().ptByRouteMode().get("bus")
                .vehicleMetres());
    }

    @Test
    void busPassengerKilometresUseActualBoardedLinkEvents() {
        Fixture fixture = fixture();
        Id<Vehicle> bus = Id.createVehicleId("bus");
        fixture.startRelevantTrip(PASSENGER);
        fixture.startTransit(bus, "bus");
        fixture.arrive(bus, fixture.stop1, 0.5);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.link1));
        fixture.arrive(bus, fixture.stop2, 2.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER, bus));
        var metric = fixture.metrics.result().ptByRouteMode().get("bus");
        assertEquals(200.0, metric.relevantPassengerMetres());
        assertEquals(1, metric.relevantBoardings());
    }

    @Test
    void subwayPassengerKilometresAreClassifiedFromSchedule() {
        Fixture fixture = fixture();
        Id<Vehicle> subway = Id.createVehicleId("subway");
        fixture.startRelevantTrip(PASSENGER);
        fixture.startTransit(subway, "subway");
        fixture.arrive(subway, fixture.stop1, 0.5);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, subway));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, subway, fixture.link2));
        fixture.arrive(subway, fixture.stop2, 2.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER, subway));
        assertEquals(200.0, fixture.metrics.result().ptByRouteMode().get("subway")
                .relevantPassengerMetres());
    }

    @Test
    void busSubwayTransferPartitionsPkmButKeepsOneMainTrip() {
        Fixture fixture = fixture();
        Id<Vehicle> bus = Id.createVehicleId("bus");
        Id<Vehicle> subway = Id.createVehicleId("subway");
        fixture.startRelevantTrip(PASSENGER);
        fixture.startTransit(bus, "bus");
        fixture.startTransit(subway, "subway");
        fixture.arrive(bus, fixture.stop1, 0.5);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.link1));
        fixture.arrive(bus, fixture.stop2, 2.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER, bus));
        fixture.arrive(subway, fixture.stop1, 3.5);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(4, PASSENGER, subway));
        fixture.metrics.handleEvent(new LinkEnterEvent(5, subway, fixture.link2));
        fixture.arrive(subway, fixture.stop2, 5.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(6, PASSENGER, subway));
        var result = fixture.metrics.result();
        assertEquals(200.0, result.ptByRouteMode().get("bus").relevantPassengerMetres());
        assertEquals(200.0, result.ptByRouteMode().get("subway").relevantPassengerMetres());
        assertEquals(2, result.ptByRouteMode().values().stream()
                .mapToLong(Production2040VehicleMetrics.PtMetric::relevantBoardings).sum());
    }

    @Test
    void ptFkmIsNotMultipliedByPassengerCount() {
        Fixture fixture = fixture();
        Id<Vehicle> bus = Id.createVehicleId("bus");
        fixture.startRelevantTrip(PASSENGER);
        fixture.startRelevantTrip(PASSENGER_2);
        fixture.startTransit(bus, "bus");
        fixture.arrive(bus, fixture.stop1, 0.5);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, bus));
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER_2, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.link1));
        fixture.arrive(bus, fixture.stop2, 2.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER, bus));
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER_2, bus));
        var metric = fixture.metrics.result().ptByRouteMode().get("bus");
        assertEquals(100.0, metric.vehicleMetres());
        assertEquals(400.0, metric.relevantPassengerMetres());
    }

    @Test
    void stageActivityEndDoesNotAdvanceMainTrip() {
        Fixture fixture = fixture();
        Id<Vehicle> bus = Id.createVehicleId("bus");
        fixture.startRelevantTrip(PASSENGER);
        fixture.metrics.handleEvent(new ActivityEndEvent(0.5, PASSENGER, fixture.link1,
                null, "pt interaction"));
        fixture.startTransit(bus, "bus");
        fixture.arrive(bus, fixture.stop1, 0.75);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, PASSENGER, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.link1));
        fixture.arrive(bus, fixture.stop2, 2.5);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3, PASSENGER, bus));
        assertEquals(200.0, fixture.metrics.result().ptByRouteMode().get("bus")
                .relevantPassengerMetres());
    }

    @Test
    void reportsUnknownPtModeAndMissingReferencesAndLinks() {
        Fixture fixture = fixture();
        Id<Vehicle> unknown = Id.createVehicleId("unknown");
        fixture.startTransit(unknown, "ferry");
        assertTrue(fixture.metrics.result().ptByRouteMode().containsKey("ferry"));

        Id<Vehicle> missingRoute = Id.createVehicleId("missing-route");
        fixture.metrics.handleEvent(new TransitDriverStartsEvent(0, DRIVER, missingRoute,
                Id.create("missing-line", TransitLine.class),
                Id.create("missing-route", TransitRoute.class),
                Id.create("dep", Departure.class)));
        fixture.metrics.handleEvent(new LinkEnterEvent(1, missingRoute,
                Id.createLinkId("missing-link")));
        fixture.metrics.handleEvent(new VehicleArrivesAtFacilityEvent(2, missingRoute,
                Id.create("missing-stop", TransitStopFacility.class), 0));
        var result = fixture.metrics.result();
        assertEquals(2, result.missingTransitReferences());
        assertEquals(1, result.missingLinks());
    }

    @Test
    void reportsMissingTransitVehicleReference() {
        Fixture fixture = fixture();
        Id<Vehicle> bus = Id.createVehicleId("bus");
        fixture.startTransit(bus, "bus");
        Production2040VehicleMetrics strict = new Production2040VehicleMetrics(
                fixture.scenario.getNetwork(), fixture.scenario.getTransitSchedule(),
                fixture.scenario.getTransitVehicles(), Map.of());
        strict.handleEvent(new TransitDriverStartsEvent(0, DRIVER, bus,
                Id.create("line-" + bus, TransitLine.class),
                Id.create("route-" + bus, TransitRoute.class),
                Id.create("departure-" + bus, Departure.class)));
        assertEquals(1, strict.result().missingTransitReferences());
    }

    @Test
    void failsClosedOnWrongRunIdChangedConfigAndSwappedScenario() {
        Config bau = ConfigUtils.loadConfig(Production2040Contract.BAU.configPath().toString());
        Config identical = ConfigUtils.loadConfig(Production2040Contract.BAU.configPath().toString());
        ValidateProduction2040AnalysisOutput.validateOutputConfig(bau, identical,
                Production2040Contract.BAU.runId());
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(bau, identical,
                        "wrong-run"));
        Config changed = ConfigUtils.loadConfig(Production2040Contract.BAU.configPath().toString());
        changed.global().setRandomSeed(999);
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(bau, changed,
                        Production2040Contract.BAU.runId()));
        Config fast = ConfigUtils.loadConfig(
                Production2040Contract.FAST_TRACK.configPath().toString());
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(bau, fast,
                        Production2040Contract.BAU.runId()));
    }

    @Test
    void acceptsOnlyExactMatsim2025SwissRailRaptorRuntimeDefaultsPostRun() {
        Config expected = bauConfig();
        assertFalse(expected.getModules().containsKey(
                Production2040PostRunConfigComparison.SWISS_RAIL_RAPTOR_MODULE));
        assertEquals(EXPECTED_RAPTOR_DEFAULTS, Production2040PostRunConfigComparison
                .expectedMatsim2025Defaults());

        Config exact = withRuntimeSwissRailRaptor(Map.of(), Set.of());
        assertTrue(AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(expected, exact).stream()
                .anyMatch(value -> value.startsWith("swissRailRaptor:")));
        ValidateProduction2040AnalysisOutput.validateOutputConfig(expected, exact,
                Production2040Contract.BAU.runId());
        ValidateMatsim2040ProductionSmokeOutput.validateOutputConfig(expected, exact,
                Production2040Contract.BAU.runId());

        Config changed = withRuntimeSwissRailRaptor(
                Map.of("useRangeQuery", "true"), Set.of());
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(expected, changed,
                        Production2040Contract.BAU.runId()));

        Config additional = withRuntimeSwissRailRaptor(
                Map.of("unsupportedRuntimeParameter", "value"), Set.of());
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(expected, additional,
                        Production2040Contract.BAU.runId()));

        Config missing = withRuntimeSwissRailRaptor(Map.of(),
                Set.of("transferWalkMargin"));
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(expected, missing,
                        Production2040Contract.BAU.runId()));

        Config unrelated = withRuntimeSwissRailRaptor(Map.of(), Set.of());
        ConfigGroup unrelatedModule = new ConfigGroup("unrelatedRuntimeModule");
        unrelatedModule.addParam("unexpected", "value");
        unrelated.addModule(unrelatedModule);
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateOutputConfig(expected, unrelated,
                        Production2040Contract.BAU.runId()));
    }

    @Test
    void reportBundleRejectsWrongScenarioAndPartialPublication() {
        var definition = Production2040AnalysisSpec.scenario("BAU");
        Map<String, String> reports = new LinkedHashMap<>();
        for (String name : Production2040AnalysisSpec.OUTPUT_FILES) {
            reports.put(name, name.endsWith(".csv")
                    ? "scenario_id,sample_factor,unit,value\nFAST_TRACK_2040,0.05,test,1\n"
                    : "report");
        }
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateReportBundle(definition, reports));
        reports.remove("final_car_fkm.csv");
        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateReportBundle(definition, reports));
    }

    @Test
    void reportBundleAcceptsExactBauAndFastTrackHeadings() {
        var bau = Production2040AnalysisSpec.scenario("BAU");
        var fastTrack = Production2040AnalysisSpec.scenario("FAST_TRACK");

        ValidateProduction2040AnalysisOutput.validateReportBundle(bau,
                validReportBundle(bau));
        ValidateProduction2040AnalysisOutput.validateReportBundle(fastTrack,
                validReportBundle(fastTrack));
    }

    @Test
    void reportBundleRejectsDeliberatelyMismatchedHeading() {
        var bau = Production2040AnalysisSpec.scenario("BAU");
        Map<String, String> reports = validReportBundle(bau);
        reports.put("analysis_report.md",
                Production2040AnalysisSpec.reportHeading(
                        Production2040AnalysisSpec.scenario("FAST_TRACK")) + "\n");

        assertThrows(IllegalStateException.class, () ->
                ValidateProduction2040AnalysisOutput.validateReportBundle(bau, reports));
    }

    private static Map<String, String> validReportBundle(
            Production2040AnalysisSpec.ScenarioDefinition definition) {
        Map<String, String> reports = new LinkedHashMap<>();
        for (String name : Production2040AnalysisSpec.OUTPUT_FILES) {
            reports.put(name, name.endsWith(".csv")
                    ? "scenario_id,sample_factor,unit,value\n" + definition.scenarioId()
                            + ",0.05,test,1\n"
                    : Production2040AnalysisSpec.reportHeading(definition) + "\n");
        }
        return reports;
    }

    private static List<Production2040AnalysisSpec.IterationSnapshot> iterations(
            java.util.function.IntFunction<Map<String, Long>> function) {
        List<Production2040AnalysisSpec.IterationSnapshot> result = new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            Map<String, Long> modes = function.apply(iteration <= 50 ? 0 : iteration - 51);
            long total = modes.values().stream().mapToLong(Long::longValue).sum();
            result.add(new Production2040AnalysisSpec.IterationSnapshot(iteration, total,
                    modes, Map.of()));
        }
        return result;
    }

    private static Config bauConfig() {
        return ConfigUtils.loadConfig(Production2040Contract.BAU.configPath().toString());
    }

    private static Config withRuntimeSwissRailRaptor(Map<String, String> replacements,
            Set<String> removals) {
        Config config = bauConfig();
        Map<String, String> values = new LinkedHashMap<>(
                Production2040PostRunConfigComparison.expectedMatsim2025Defaults());
        removals.forEach(values::remove);
        values.putAll(replacements);
        ConfigGroup raptor = new ConfigGroup(
                Production2040PostRunConfigComparison.SWISS_RAIL_RAPTOR_MODULE);
        values.forEach(raptor::addParam);
        config.addModule(raptor);
        return config;
    }

    private static Fixture fixture() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Node n1 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n1"),
                new Coord(0, 0));
        Node n2 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n2"),
                new Coord(100, 0));
        Node n3 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n3"),
                new Coord(300, 0));
        Id<Link> l1 = Id.createLinkId("l1");
        Id<Link> l2 = Id.createLinkId("l2");
        NetworkUtils.createAndAddLink(scenario.getNetwork(), l1, n1, n2,
                100, 10, 1000, 1);
        NetworkUtils.createAndAddLink(scenario.getNetwork(), l2, n2, n3,
                200, 10, 1000, 1);
        Map<Id<Person>, List<Boolean>> scope = Map.of(PASSENGER, List.of(true),
                PASSENGER_2, List.of(true));
        var scheduleFactory = scenario.getTransitSchedule().getFactory();
        Id<TransitStopFacility> stop1 = Id.create("stop1", TransitStopFacility.class);
        Id<TransitStopFacility> stop2 = Id.create("stop2", TransitStopFacility.class);
        var facility1 = scheduleFactory.createTransitStopFacility(stop1, new Coord(50, 0), false);
        var facility2 = scheduleFactory.createTransitStopFacility(stop2, new Coord(250, 0), false);
        facility1.setLinkId(l1);
        facility2.setLinkId(l2);
        scenario.getTransitSchedule().addStopFacility(facility1);
        scenario.getTransitSchedule().addStopFacility(facility2);
        return new Fixture(scenario, l1, l2, stop1, stop2,
                new Production2040VehicleMetrics(scenario.getNetwork(),
                        scenario.getTransitSchedule(), scope));
    }

    private record Fixture(Scenario scenario, Id<Link> link1, Id<Link> link2,
                           Id<TransitStopFacility> stop1, Id<TransitStopFacility> stop2,
                           Production2040VehicleMetrics metrics) {
        void startRelevantTrip(Id<Person> person) {
            metrics.handleEvent(new ActivityEndEvent(0, person, link1, null, "home"));
        }

        void startTransit(Id<Vehicle> vehicle, String mode) {
            var factory = scenario.getTransitSchedule().getFactory();
            Id<TransitLine> lineId = Id.create("line-" + vehicle, TransitLine.class);
            Id<TransitRoute> routeId = Id.create("route-" + vehicle, TransitRoute.class);
            TransitLine line = factory.createTransitLine(lineId);
            TransitRoute route = factory.createTransitRoute(routeId,
                    RouteUtils.createLinkNetworkRouteImpl(link1, link2),
                    List.of(factory.createTransitRouteStop(
                                    scenario.getTransitSchedule().getFacilities().get(stop1), 0, 0),
                            factory.createTransitRouteStop(
                                    scenario.getTransitSchedule().getFacilities().get(stop2), 60, 60)),
                    mode);
            Id<Departure> departureId = Id.create("departure-" + vehicle, Departure.class);
            Departure departure = factory.createDeparture(departureId, 0);
            departure.setVehicleId(vehicle);
            route.addDeparture(departure);
            line.addRoute(route);
            scenario.getTransitSchedule().addTransitLine(line);
            metrics.handleEvent(new TransitDriverStartsEvent(0, DRIVER, vehicle, lineId,
                    routeId, departureId));
        }

        void arrive(Id<Vehicle> vehicle, Id<TransitStopFacility> stop, double time) {
            metrics.handleEvent(new VehicleArrivesAtFacilityEvent(time, vehicle, stop, 0));
        }
    }
}
