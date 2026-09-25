package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;
import org.matsim.vehicles.Vehicle;

class Production2040TerritorialCostInputsTest {
    private static final double X = 4_400_000;
    private static final double Y = 5_300_000;

    @TempDir Path temporary;

    @Test
    void carClipCountsInsideAndOutsideToOutsideCrossingButExcludesTransit() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        Link inside = link(network, "inside", X + 10, X + 90, 100);
        Link outside = link(network, "outside", X - 90, X - 10, 100);
        Link crossing = link(network, "outside-to-outside", X - 50, X + 150, 200);
        var territorial = new AnalyzeProduction2040TerritorialCostInputs.TerritorialCarMetrics(
                network, boundary);
        var metrics = new Production2040VehicleMetrics(network, scenario.getTransitSchedule(),
                scenario.getTransitVehicles(), Map.of(), territorial);
        traverse(metrics, "inside-car", inside);
        traverse(metrics, "outside-car", outside);
        traverse(metrics, "crossing-car", crossing);

        Id<Vehicle> bus = Id.createVehicleId("transit-bus");
        territorial.trafficEnter(bus, Id.createPersonId("driver"), "car", null, true);
        territorial.movement(bus, Id.createPersonId("driver"), inside.getId(), 100, true, "bus");
        territorial.trafficLeave(bus, Id.createPersonId("driver"));

        var result = territorial.result();
        assertEquals(400.0, result.uncutMetres(), 1e-9);
        assertEquals(200.0, result.territorialMetres(), 1e-9);
        assertEquals(3, result.carVehicles());
        assertTrue(result.otherNetworkVehicleCategories().isEmpty());
        AnalyzeProduction2040TerritorialCostInputs.validateCarMetrics(result, metrics.result());
    }

    @Test
    void accumulatedCarDistanceReconciliationAllowsOnlyFloatingPointOrderNoise() {
        double bauActual = 3.1521145518915625E9;
        double bauExpected = 3.152114551887584E9;

        assertDoesNotThrow(() -> AnalyzeProduction2040TerritorialCostInputs
                .requireAccumulatedDistanceCloseMetres(bauActual, bauExpected,
                        "reported BAU reconciliation"));

        IllegalStateException oneMetre = assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        bauExpected + 1.0, bauExpected, "one-metre difference"));
        assertReconciliationMessage(oneMetre);
        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        bauExpected + 10_000.0, bauExpected, "material difference")));
    }

    @Test
    void accumulatedCarDistanceReconciliationRemainsStrictNearZeroAndRejectsNonFinite() {
        assertDoesNotThrow(() -> AnalyzeProduction2040TerritorialCostInputs
                .requireAccumulatedDistanceCloseMetres(0.0009, 0.0, "near-zero pass"));
        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        0.0011, 0.0, "near-zero failure")));

        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        Double.NaN, 0.0, "NaN actual")));
        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        0.0, Double.NaN, "NaN expected")));
        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        Double.POSITIVE_INFINITY, 0.0, "positive infinity actual")));
        assertReconciliationMessage(assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.requireAccumulatedDistanceCloseMetres(
                        0.0, Double.NEGATIVE_INFINITY, "negative infinity expected")));
    }

    @Test
    void activeLegsUseLegEndpointsAndSeparateWalkingStagesFromMainModeWalk() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Id<Person> personId = Id.createPersonId("active-person");
        Person person = walkingStagePerson(personId);
        var metrics = readActiveMetrics(person);
        metrics.departure(personId, "walk");
        metrics.arrival(personId, "walk");
        metrics.departure(personId, "transit_walk");
        metrics.arrival(personId, "walk");
        metrics.departure(personId, "walk");
        metrics.arrival(personId, "walk");

        var result = metrics.result();
        assertEquals(140.0, result.walk().territorialMetres(), 1e-9);
        assertEquals(130.0, result.walkStageMetres(), 1e-9);
        assertEquals(10.0, result.walkMainModeMetres(), 1e-9);
        assertEquals(3, result.walk().validSpatialDistanceLegs());
        assertEquals(0, result.walk().incompleteLegs());
        AnalyzeProduction2040TerritorialCostInputs.validateActiveMetrics(result);
    }

    @Test
    void bikeLegUsesTheSameCrossingClipAsOtherTerritorialMovements() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Id<Person> personId = Id.createPersonId("crossing-bike");
        Person person = crossingBikePerson(personId);
        var metrics = readActiveMetrics(person);
        metrics.departure(personId, "bike");
        metrics.arrival(personId, "bike");

        var result = metrics.result();
        assertEquals(100.0, result.bike().territorialMetres(), 1e-9);
        assertEquals(1, result.bike().validSpatialDistanceLegs());
        AnalyzeProduction2040TerritorialCostInputs.validateActiveMetrics(result);
    }

    @Test
    void incompleteOrMissingActiveEvidenceIsReportedAndNeverConvertedFromPlannedDistance()
            throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Id<Person> personId = Id.createPersonId("incomplete-active");
        Id<Person> missingCoordinateId = Id.createPersonId("missing-coordinate-active");
        Person person = incompleteAndMissingPerson(personId);
        var metrics = readActiveMetrics(person, missingCoordinatePerson(missingCoordinateId));
        metrics.departure(personId, "bike");
        metrics.stuck(personId, "bike");
        metrics.departure(personId, "walk");
        metrics.arrival(personId, "walk");
        metrics.departure(missingCoordinateId, "walk");
        metrics.arrival(missingCoordinateId, "walk");

        var result = metrics.result();
        assertEquals(0.0, result.bike().territorialMetres(), 1e-9);
        assertEquals(1, result.bike().incompleteLegs());
        assertEquals(1, result.bike().stuckIncompleteLegs());
        assertEquals(1, result.walk().missingDistanceMeasurements());
        assertEquals(1, result.walk().missingCoordinates());
        assertEquals(0.0, result.walk().territorialMetres(), 1e-9);
        AnalyzeProduction2040TerritorialCostInputs.validateActiveMetrics(result);
    }

    @Test
    void validatedPtInputsPreservePassengerExpansionAndPseudolinkDiagnostics() {
        var definition = Production2040AnalysisSpec.scenario("BAU");
        var inputs = AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(definition,
                Map.of("pt_external_cost_inputs.csv", ptSource("BAU_2040")),
                Map.of("final_territorial_pt_fkm_by_route_mode.csv", pseudolinkSource("BAU_2040")));
        AnalyzeProduction2040TerritorialCostInputs.validatePtInputs(inputs);
        var bus = inputs.byMode().get("bus");
        assertEquals(10.0, bus.samplePkm(), 1e-9);
        assertEquals(200.0, bus.expandedPkm(), 1e-9);
        assertEquals(20.0, bus.fullServiceFkm(), 1e-9);
        assertEquals(10.0, bus.occupancy(), 1e-9);
        assertEquals(AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_REPORTED_FOR_REVIEW,
                bus.occupancyStatus());
        assertTrue(AnalyzeProduction2040TerritorialCostInputs.ptWorkbookQualityStatus(bus)
                .contains(AnalyzeProduction2040TerritorialCostInputs
                        .PT_OCCUPANCY_REPORTED_FOR_REVIEW));
        var tram = inputs.byMode().get("tram");
        assertEquals(0.0, tram.occupancy(), 1e-9);
        assertEquals(AnalyzeProduction2040TerritorialCostInputs
                .PT_OCCUPANCY_VALID_ZERO_PASSENGER, tram.occupancyStatus());
        var subway = inputs.byMode().get("subway");
        assertNull(subway.occupancy());
        assertEquals(AnalyzeProduction2040TerritorialCostInputs
                .PT_OCCUPANCY_NOT_APPLICABLE_ZERO_ACTIVITY, subway.occupancyStatus());
        assertEquals(20.0, inputs.byMode().get("ferry/other").expandedPkm(), 1e-9);
        assertEquals(12.5, inputs.pseudolinks().territorialServiceSharePercent(), 1e-9);
    }

    @Test
    void ptOccupancyStatusRejectsUnknownAndInconsistentCombinations() {
        var definition = Production2040AnalysisSpec.scenario("BAU");
        String source = ptSource("BAU_2040");
        Map<String, String> service = Map.of("final_territorial_pt_fkm_by_route_mode.csv",
                pseudolinkSource("BAU_2040"));

        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(definition,
                        Map.of("pt_external_cost_inputs.csv", source.replace(
                                "PASS_REPORTED_FOR_REVIEW", "UNKNOWN_OCCUPANCY_STATUS")),
                        service));
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(definition,
                        Map.of("pt_external_cost_inputs.csv", source.replaceFirst(
                                "PASS_REPORTED_FOR_REVIEW",
                                "VALID_ZERO_PASSENGER_ACTIVITY")), service));
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(definition,
                        Map.of("pt_external_cost_inputs.csv", source.replace(
                                "NOT_APPLICABLE_VALID_ZERO_ACTIVITY",
                                "VALID_ZERO_PASSENGER_ACTIVITY")), service));

        var wrongRatio = AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(
                definition, Map.of("pt_external_cost_inputs.csv", source.replaceFirst(
                        ",technical_weekday,1,10,PASS_REPORTED_FOR_REVIEW",
                        ",technical_weekday,1,9,PASS_REPORTED_FOR_REVIEW")), service);
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialCostInputs.validatePtInputs(wrongRatio));
    }

    @Test
    void availableLocalPtCsvsParseWithoutCompleteSimulationOutputs() throws Exception {
        int parsed = 0;
        for (String scenario : List.of("BAU", "FAST_TRACK")) {
            var definition = Production2040AnalysisSpec.scenario(scenario);
            Path pt = definition.analysisDirectory().resolve(
                    AnalyzeProduction2040PtCostAllocation.SUBDIRECTORY)
                    .resolve("pt_external_cost_inputs.csv");
            Path service = definition.analysisDirectory().resolve(
                    AnalyzeProduction2040AccountingScopes.SUBDIRECTORY)
                    .resolve("final_territorial_pt_fkm_by_route_mode.csv");
            if (!Files.isRegularFile(pt) || !Files.isRegularFile(service)) continue;
            var inputs = AnalyzeProduction2040TerritorialCostInputs.readValidatedPtInputs(
                    definition, Map.of("pt_external_cost_inputs.csv", Files.readString(pt)),
                    Map.of("final_territorial_pt_fkm_by_route_mode.csv",
                            Files.readString(service)));
            AnalyzeProduction2040TerritorialCostInputs.validatePtInputs(inputs);
            assertEquals(AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_REPORTED_FOR_REVIEW,
                    inputs.byMode().get("bus").occupancyStatus());
            parsed++;
        }
        assumeTrue(parsed > 0, "No local PT source/service CSV pair is available");
    }

    @Test
    void xlsxUsesNumericCellsAndTotalsWithoutExternalLinks() throws Exception {
        var data = workbookData("BAU_2040");
        AnalyzeProduction2040TerritorialCostInputs.validateWorkbookData(data);
        Path workbook = temporary.resolve(data.fileName());
        AnalyzeProduction2040TerritorialCostInputs.writeWorkbook(workbook, data);
        AnalyzeProduction2040TerritorialCostInputs.validateGeneratedWorkbook(workbook, data);
        try (InputStream input = Files.newInputStream(workbook);
                Workbook opened = WorkbookFactory.create(input)) {
            var inputs = opened.getSheet("Inputs");
            assertEquals(CellType.NUMERIC, inputs.getRow(
                    AnalyzeProduction2040TerritorialCostInputs.INPUT_DATA_START_ROW).getCell(1)
                    .getCellType());
            assertEquals(150.0, inputs.getRow(
                    AnalyzeProduction2040TerritorialCostInputs.INPUT_DATA_START_ROW).getCell(1)
                    .getNumericCellValue(), 1e-9);
            assertEquals("NOT_APPLICABLE", inputs.getRow(
                    AnalyzeProduction2040TerritorialCostInputs.INPUT_DATA_START_ROW + 1).getCell(2)
                    .getStringCellValue());
            assertEquals("unit", inputs.getRow(
                    AnalyzeProduction2040TerritorialCostInputs.INPUT_DATA_START_ROW).getCell(6)
                    .getStringCellValue());
            assertEquals(415.0, inputs.getRow(
                    AnalyzeProduction2040TerritorialCostInputs.INPUT_DATA_START_ROW + 9).getCell(1)
                    .getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void bauAndFastTrackUseIdenticalScenarioIndependentWorkbookRules() {
        var bau = workbookData("BAU_2040");
        var fast = workbookData("FAST_TRACK_2040");
        AnalyzeProduction2040TerritorialCostInputs.validateWorkbookData(bau);
        AnalyzeProduction2040TerritorialCostInputs.validateWorkbookData(fast);
        assertEquals(bau.inputRows(), fast.inputRows());
        assertEquals("BAU_2040_territorial_cost_inputs.xlsx",
                AnalyzeProduction2040TerritorialCostInputs.workbookFileName(
                        Production2040AnalysisSpec.scenario("BAU")));
        assertEquals("Fast_Track_2040_territorial_cost_inputs.xlsx",
                AnalyzeProduction2040TerritorialCostInputs.workbookFileName(
                        Production2040AnalysisSpec.scenario("FAST_TRACK")));
        assertFalse(AnalyzeProduction2040TerritorialCostInputs.readActiveRouting(
                Production2040Contract.BAU.configPath()).networkModes().contains("walk"));
        assertFalse(AnalyzeProduction2040TerritorialCostInputs.readActiveRouting(
                Production2040Contract.FAST_TRACK.configPath()).networkModes().contains("bike"));
    }

    private AnalyzeProduction2040TerritorialCostInputs.TerritorialActiveModeMetrics
            readActiveMetrics(Person... persons) throws Exception {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        for (Person person : persons) scenario.getPopulation().addPerson(person);
        Path plans = temporary.resolve("active-metrics.plans.xml.gz");
        new PopulationWriter(scenario.getPopulation()).write(plans.toString());
        return AnalyzeProduction2040TerritorialCostInputs.TerritorialActiveModeMetrics.read(
                plans, boundary());
    }

    private Person walkingStagePerson(Id<Person> id) {
        var factory = PopulationUtils.getFactory();
        Person person = factory.createPerson(id);
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(X - 50, Y + 50)));
        plan.addLeg(leg(factory, "walk", 200));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE,
                new Coord(X + 50, Y + 50)));
        plan.addLeg(factory.createLeg("pt"));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE,
                new Coord(X + 70, Y + 50)));
        plan.addLeg(leg(factory, "transit_walk", 30));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(X + 80, Y + 50)));
        plan.addLeg(leg(factory, "walk", 50));
        plan.addActivity(factory.createActivityFromCoord("other", new Coord(X + 180, Y + 50)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private Person incompleteAndMissingPerson(Id<Person> id) {
        var factory = PopulationUtils.getFactory();
        Person person = factory.createPerson(id);
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(X + 10, Y + 50)));
        plan.addLeg(leg(factory, "bike", 100));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(X + 90, Y + 50)));
        plan.addLeg(factory.createLeg("walk"));
        plan.addActivity(factory.createActivityFromCoord("other", new Coord(X + 80, Y + 50)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private Person crossingBikePerson(Id<Person> id) {
        var factory = PopulationUtils.getFactory();
        Person person = factory.createPerson(id);
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(X - 50, Y + 50)));
        plan.addLeg(leg(factory, "bike", 200));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(X + 150, Y + 50)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private Person missingCoordinatePerson(Id<Person> id) {
        var factory = PopulationUtils.getFactory();
        Person person = factory.createPerson(id);
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(X + 10, Y + 50)));
        plan.addLeg(leg(factory, "walk", 100));
        plan.addActivity(factory.createActivityFromLinkId("other", Id.createLinkId("no-coord")));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Leg leg(org.matsim.api.core.v01.population.PopulationFactory factory,
            String mode, double distance) {
        Leg leg = factory.createLeg(mode);
        var route = RouteUtils.createGenericRouteImpl(Id.createLinkId("from"),
                Id.createLinkId("to"));
        route.setDistance(distance);
        leg.setRoute(route);
        return leg;
    }

    private static void traverse(Production2040VehicleMetrics metrics, String id, Link link) {
        Id<Person> person = Id.createPersonId(id);
        Id<Vehicle> vehicle = Id.createVehicleId(id);
        metrics.handleEvent(new VehicleEntersTrafficEvent(0, person, link.getId(), vehicle,
                "car", 0.0));
        metrics.handleEvent(new VehicleLeavesTrafficEvent(1, person, link.getId(), vehicle,
                "car", 1.0));
    }

    private static void assertReconciliationMessage(IllegalStateException error) {
        assertTrue(error.getMessage().contains("actual="));
        assertTrue(error.getMessage().contains("expected="));
        assertTrue(error.getMessage().contains("absoluteDifference="));
        assertTrue(error.getMessage().contains("relativeDifference="));
        assertTrue(error.getMessage().contains("permittedTolerance="));
    }

    private MunichMunicipalBoundary boundary() throws Exception {
        Path file = temporary.resolve("boundary.json");
        Files.writeString(file, "{\"type\":\"Polygon\",\"coordinates\":[[["
                + X + "," + Y + "],[" + (X + 100) + "," + Y + "],["
                + (X + 100) + "," + (Y + 100) + "],[" + X + "," + (Y + 100)
                + "],[" + X + "," + Y + "]]]}", StandardCharsets.UTF_8);
        return MunichMunicipalBoundary.load(file);
    }

    private static Link link(Network network, String id, double fromX, double toX,
            double length) {
        Node from = network.getFactory().createNode(Id.createNodeId(id + "-from"),
                new Coord(fromX, Y + 50));
        Node to = network.getFactory().createNode(Id.createNodeId(id + "-to"),
                new Coord(toX, Y + 50));
        network.addNode(from);
        network.addNode(to);
        Link link = network.getFactory().createLink(Id.createLinkId(id), from, to);
        link.setLength(length);
        network.addLink(link);
        return link;
    }

    private static String ptSource(String scenario) {
        return "scenario_id,pt_route_mode,component_normalized_route_modes,"
                + "unit,demand_sample_factor,"
                + "territorial_all_passenger_sample_pkm_per_day,"
                + "territorial_all_passenger_expanded_pkm_per_day,"
                + "territorial_full_service_fkm_per_day,demand_expansion_factor,day_basis,"
                + "supply_expansion_factor,"
                + "average_occupancy_expanded_pkm_per_full_service_fkm,occupancy_status,data_coverage_status\n"
                + scenario + ",bus,bus,person_km_vehicle_km_and_ratio,0.05,10,200,20,20,technical_weekday,1,10,PASS_REPORTED_FOR_REVIEW,PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE\n"
                + scenario + ",tram,tram,person_km_vehicle_km_and_ratio,0.05,0,0,4,20,technical_weekday,1,0,VALID_ZERO_PASSENGER_ACTIVITY,PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE\n"
                + scenario + ",subway,subway,person_km_vehicle_km_and_ratio,0.05,0,0,0,20,technical_weekday,1,NOT_APPLICABLE,NOT_APPLICABLE_VALID_ZERO_ACTIVITY,PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE\n"
                + scenario + ",rail,rail,person_km_vehicle_km_and_ratio,0.05,0,0,0,20,technical_weekday,1,NOT_APPLICABLE,NOT_APPLICABLE_VALID_ZERO_ACTIVITY,PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE\n"
                + scenario + ",ferry/other,ferry/other,person_km_vehicle_km_and_ratio,0.05,1,20,2,20,technical_weekday,1,10,PASS_REPORTED_FOR_REVIEW,PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE\n";
    }

    private static String pseudolinkSource(String scenario) {
        return "scenario_id,scope_id,sample_factor,unit,pt_route_mode,"
                + "full_service_daily_vehicle_km,factor_20_daily_vehicle_km,"
                + "point_anchored_pseudolink_used_link_count,"
                + "point_anchored_pseudolink_territorial_service_km,"
                + "point_anchored_pseudolink_territorial_service_share_percent,"
                + "zero_model_length_pt_link_count\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,bus,20,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,tram,4,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,subway,0,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,rail,0,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,ferry/other,2,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE,NOT_APPLICABLE\n"
                + scenario + ",TERRITORIAL_PT_SERVICE,1.0,vehicle_km,TOTAL,26,NOT_APPLICABLE,3,4,12.5,1\n";
    }

    private static AnalyzeProduction2040TerritorialCostInputs.WorkbookData workbookData(
            String scenario) {
        List<AnalyzeProduction2040TerritorialCostInputs.InputRow> rows = List.of(
                input("Car", 150, 100.0, 1.5), input("Walk", 25, null, null),
                input("Bike", 10, 10.0, 1.0), input("Bus", 200, 20.0, 10.0),
                input("Tram", 0, 0.0, null), input("Subway", 0, 0.0, null),
                input("Rail", 0, 0.0, null), input("Other PT (ferry/other)", 30, 3.0, 10.0),
                input("PT total (subtotal, mixed vehicle units)", 230, 23.0, null),
                input("All-mode Pkm total (PT subtotal excluded)", 415, null, null));
        String filename = "BAU_2040".equals(scenario) ? "BAU_2040_territorial_cost_inputs.xlsx"
                : "Fast_Track_2040_territorial_cost_inputs.xlsx";
        return new AnalyzeProduction2040TerritorialCostInputs.WorkbookData(scenario, filename,
                rows, List.of(new AnalyzeProduction2040TerritorialCostInputs.DefinitionRow(
                        "Test", "value", "test-only workbook data")));
    }

    private static AnalyzeProduction2040TerritorialCostInputs.InputRow input(String mode,
            double pkm, Double fkm, Double occupancy) {
        return new AnalyzeProduction2040TerritorialCostInputs.InputRow(mode, pkm, fkm,
                pkm * 365 / 1_000_000.0,
                fkm == null ? null : fkm * 365 / 1_000_000.0, occupancy, "unit", "basis",
                "quality");
    }
}
