package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

class Production2040TerritorialModeComparisonTest {
    private static final double X = 4_400_000;
    private static final double Y = 5_300_000;

    @TempDir Path temporary;

    @Test
    void classifiesInternalInboundOutboundThroughAndExcludesOutsideTrips() throws Exception {
        Fixture fixture = fixture();
        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                car("internal", fixture.inside, X + 10, X + 90),
                car("inbound", fixture.inbound, X - 50, X + 50),
                car("outbound", fixture.outbound, X + 50, X + 150),
                car("through", fixture.through, X - 50, X + 150),
                car("outside", fixture.outside, X - 90, X - 10)), fixture.network,
                fixture.scenario.getTransitSchedule(), boundary());

        assertEquals(1, audit.included("car", AnalyzeProduction2040TerritorialModeComparison.Scope.INTERNAL));
        assertEquals(1, audit.included("car", AnalyzeProduction2040TerritorialModeComparison.Scope.INBOUND));
        assertEquals(1, audit.included("car", AnalyzeProduction2040TerritorialModeComparison.Scope.OUTBOUND));
        assertEquals(1, audit.included("car", AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        assertEquals(4, audit.included("car"));
        assertEquals(1, audit.excludedByMainMode().get("car"));
    }

    @Test
    void crossingTeleportedWalkAndBikeUseLegLevelGeometryAndStageActivitiesDoNotSplitTrips()
            throws Exception {
        Fixture fixture = fixture();
        Person walk = stagedWalk("walk", fixture.through);
        Person bike = active("bike", "bike", X - 50, X + 150, 200);
        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(walk, bike),
                fixture.network, fixture.scenario.getTransitSchedule(), boundary());

        assertEquals(1, audit.included("walk", AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        assertEquals(1, audit.included("bike", AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        assertEquals(2, audit.includedTotal());
        assertEquals(100.0, audit.shareSum(), 1e-9);
        assertEquals(20.0, Production2040AnalysisSpec.expanded(audit.included("walk")), 1e-9);
    }

    @Test
    void ptTransfersCountOnceAndWalkingOnlyDoesNotQualifyPtTrip() throws Exception {
        Fixture fixture = fixture();
        TransitSegment inside = fixture.transit("inside", fixture.inside, fixture.inside2, "bus");
        TransitSegment outside = fixture.transit("outside", fixture.outside, fixture.outside2, "tram");
        Person transfer = ptTrip("transfer", inside, inside, X - 20, X + 120, false);
        Person walkingOnly = ptTrip("walking-only", outside, null, X + 10, X + 90, true);

        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(transfer,
                walkingOnly), fixture.network, fixture.scenario.getTransitSchedule(), boundary());

        assertEquals(1, audit.included("pt"));
        assertEquals(1, audit.included("pt", AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        assertEquals(1, audit.excludedByMainMode().get("pt"));
    }

    @Test
    void cachesRepeatedCarLinkWithoutChangingTripClassifications() throws Exception {
        Fixture fixture = fixture();
        MunichMunicipalBoundary boundary = boundary();
        var cache = new AnalyzeProduction2040TerritorialModeComparison.TerritorialGeometryCache(
                fixture.network, boundary);

        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                car("repeated-car-1", fixture.through, X - 50, X + 150),
                car("repeated-car-2", fixture.through, X - 50, X + 150),
                car("repeated-car-3", fixture.through, X - 50, X + 150)), fixture.network,
                fixture.scenario.getTransitSchedule(), boundary, cache);

        assertEquals(3, audit.included("car",
                AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        var diagnostics = cache.diagnostics();
        assertEquals(1, diagnostics.uniqueLinkClippingCalculations());
        assertEquals(1, diagnostics.linkCacheMisses());
        assertEquals(2, diagnostics.linkCacheHits());
        assertEquals(0, diagnostics.ptSegmentCacheMisses());
        assertEquals(0, diagnostics.ptSegmentCacheHits());
    }

    @Test
    void cachesRepeatedPtPassengerSegmentWithoutChangingTripCounts() throws Exception {
        Fixture fixture = fixture();
        MunichMunicipalBoundary boundary = boundary();
        TransitSegment segment = fixture.transit("repeated", fixture.inside, fixture.inside2,
                "bus");
        var cache = new AnalyzeProduction2040TerritorialModeComparison.TerritorialGeometryCache(
                fixture.network, boundary);

        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                ptTrip("repeated-pt-1", segment, null, X - 20, X + 120, false),
                ptTrip("repeated-pt-2", segment, null, X - 20, X + 120, false)),
                fixture.network, fixture.scenario.getTransitSchedule(), boundary, cache);

        assertEquals(2, audit.included("pt"));
        assertEquals(2, audit.included("pt",
                AnalyzeProduction2040TerritorialModeComparison.Scope.THROUGH));
        var diagnostics = cache.diagnostics();
        assertEquals(1, diagnostics.ptSegmentCacheMisses());
        assertEquals(1, diagnostics.ptSegmentCacheHits());
        assertEquals(1, diagnostics.uniqueLinkClippingCalculations());
        assertEquals(1, diagnostics.linkCacheMisses());
        assertEquals(0, diagnostics.linkCacheHits());
    }

    @Test
    void scenarioLocalGeometryCachesDoNotShareValuesOrCounters() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        Fixture bau = fixture();
        Fixture fast = fixture();
        var bauCache = new AnalyzeProduction2040TerritorialModeComparison.TerritorialGeometryCache(
                bau.network, boundary);
        var fastCache = new AnalyzeProduction2040TerritorialModeComparison.TerritorialGeometryCache(
                fast.network, boundary);

        AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                car("bau-car", bau.through, X - 50, X + 150)), bau.network,
                bau.scenario.getTransitSchedule(), boundary, bauCache);
        assertEquals(1, bauCache.diagnostics().linkCacheMisses());
        assertEquals(0, fastCache.diagnostics().linkCacheMisses());
        assertEquals(0, fastCache.diagnostics().uniqueLinkClippingCalculations());

        AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                car("fast-car", fast.through, X - 50, X + 150)), fast.network,
                fast.scenario.getTransitSchedule(), boundary, fastCache);
        assertEquals(1, bauCache.diagnostics().linkCacheMisses());
        assertEquals(0, bauCache.diagnostics().linkCacheHits());
        assertEquals(1, fastCache.diagnostics().linkCacheMisses());
        assertEquals(1, fastCache.diagnostics().uniqueLinkClippingCalculations());
    }

    @Test
    void unexpectedModesAndUnavailableRouteGeometryFailClosed() throws Exception {
        Fixture fixture = fixture();
        Person unexpected = active("unexpected", "ride", X + 10, X + 90, 100);
        IllegalStateException unknown = assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(unexpected),
                        fixture.network, fixture.scenario.getTransitSchedule(), boundary()));
        assertTrue(unknown.getMessage().contains("unexpected_main_mode"));

        Person invalid = active("invalid-car", "car", X + 10, X + 90, 100);
        IllegalStateException invalidRoute = assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(invalid),
                        fixture.network, fixture.scenario.getTransitSchedule(), boundary()));
        assertTrue(invalidRoute.getMessage().contains("unresolved_route_geometry"));
        assertTrue(invalidRoute.getMessage().contains("GenericRoute"));

        TransitSegment unexpectedPt = fixture.transit("unexpected", fixture.inside,
                fixture.inside2, "ferry");
        Person unexpectedPtTrip = ptTrip("unexpected-pt", unexpectedPt, null, X - 20,
                X + 120, false);
        IllegalStateException unexpectedPtError = assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(unexpectedPtTrip),
                        fixture.network, fixture.scenario.getTransitSchedule(), boundary()));
        assertTrue(unexpectedPtError.getMessage().contains("unexpected PT route mode"));
    }

    @Test
    void currentCostInputWorkbooksHaveExpectedPkmFkmRowsAndConversions() throws Exception {
        var bau = AnalyzeProduction2040TerritorialModeComparison.readCostInputs(
                Production2040AnalysisSpec.scenario("BAU"));
        var fast = AnalyzeProduction2040TerritorialModeComparison.readCostInputs(
                Production2040AnalysisSpec.scenario("FAST_TRACK"));
        for (var source : List.of(bau, fast)) {
            assertTrue(source.metrics().get("car").annualPkmMillion() > 0.0);
            assertEquals(0.0, source.metrics().get("walk").dailyFkm() == null ? 0.0 : 1.0);
            assertEquals(source.metrics().get("pt").dailyPkm(),
                    source.metrics().get("bus").dailyPkm() + source.metrics().get("tram").dailyPkm()
                            + source.metrics().get("subway").dailyPkm() + source.metrics().get("rail").dailyPkm(),
                    1e-9);
            for (String mode : List.of("bus", "tram", "subway", "rail")) {
                String occupancyStatus = source.metrics().get(mode).qualityStatus().split(";", -1)[0]
                        .trim();
                assertTrue(Set.of(
                        AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_REPORTED_FOR_REVIEW,
                        AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_VALID_ZERO_PASSENGER,
                        AnalyzeProduction2040TerritorialCostInputs
                                .PT_OCCUPANCY_NOT_APPLICABLE_ZERO_ACTIVITY).contains(occupancyStatus));
            }
        }
    }

    @Test
    void acceptsOnlyDocumentedPtOccupancyStatusesWithConsistentNumbers() {
        assertDoesNotThrow(() -> AnalyzeProduction2040TerritorialModeComparison
                .validatePtComponent("bus", ptMetric(200.0, 20.0, 10.0,
                        "PASS_REPORTED_FOR_REVIEW")));
        assertDoesNotThrow(() -> AnalyzeProduction2040TerritorialModeComparison
                .validatePtComponent("tram", ptMetric(0.0, 4.0, 0.0,
                        "VALID_ZERO_PASSENGER_ACTIVITY")));
        assertDoesNotThrow(() -> AnalyzeProduction2040TerritorialModeComparison
                .validatePtComponent("subway", ptMetric(0.0, 0.0, null,
                        "NOT_APPLICABLE_VALID_ZERO_ACTIVITY")));
        assertThrows(IllegalStateException.class, () -> AnalyzeProduction2040TerritorialModeComparison
                .validatePtComponent("rail", ptMetric(1.0, 1.0, 1.0,
                        "UNKNOWN_OCCUPANCY_STATUS")));
        assertThrows(IllegalStateException.class, () -> AnalyzeProduction2040TerritorialModeComparison
                .validatePtComponent("rail", ptMetric(1.0, 1.0, 1.0,
                        "VALID_ZERO_PASSENGER_ACTIVITY")));
    }

    @Test
    void writesNumericWorkbookCellsAndDoesNotDoubleCountPtPkm() throws Exception {
        Fixture fixture = fixture();
        var audit = AnalyzeProduction2040TerritorialModeComparison.collectTrips(List.of(
                car("workbook-car", fixture.through, X - 50, X + 150)), fixture.network,
                fixture.scenario.getTransitSchedule(), boundary());
        var bau = AnalyzeProduction2040TerritorialModeComparison.readCostInputs(
                Production2040AnalysisSpec.scenario("BAU"));
        var fast = AnalyzeProduction2040TerritorialModeComparison.readCostInputs(
                Production2040AnalysisSpec.scenario("FAST_TRACK"));
        var comparison = new AnalyzeProduction2040TerritorialModeComparison.Comparison(
                scenarioResult(Production2040AnalysisSpec.scenario("BAU"), audit, bau, "bau"),
                scenarioResult(Production2040AnalysisSpec.scenario("FAST_TRACK"), audit, fast,
                        "fast"), boundary());
        Path workbook = temporary.resolve("territorial_mode_comparison_2040.xlsx");
        AnalyzeProduction2040TerritorialModeComparison.writeWorkbook(workbook, comparison);
        AnalyzeProduction2040TerritorialModeComparison.validateWorkbook(workbook, comparison);
        try (var input = Files.newInputStream(workbook); var actual = WorkbookFactory.create(input)) {
            var summary = actual.getSheet("Main_Mode_Summary");
            assertEquals(CellType.NUMERIC, summary.getRow(5).getCell(1).getCellType());
            assertEquals("N/A", summary.getRow(8).getCell(12).getStringCellValue());
            assertEquals(summary.getRow(5).getCell(8).getNumericCellValue()
                            + summary.getRow(6).getCell(8).getNumericCellValue()
                            + summary.getRow(7).getCell(8).getNumericCellValue()
                            + summary.getRow(8).getCell(8).getNumericCellValue(),
                    summary.getRow(9).getCell(8).getNumericCellValue(), 1e-9);
            assertEquals(CellType.NUMERIC,
                    actual.getSheet("PT_Detail").getRow(5).getCell(2).getCellType());
            assertTrue(actual.getSheet("PT_Detail").getRow(5).getCell(13)
                    .getStringCellValue().contains("BAU:"));
        }
    }

    private AnalyzeProduction2040TerritorialModeComparison.CostMetric ptMetric(double pkm,
            double fkm, Double occupancy, String occupancyStatus) {
        return new AnalyzeProduction2040TerritorialModeComparison.CostMetric(pkm, fkm,
                pkm * 365.0 / 1_000_000.0, fkm * 365.0 / 1_000_000.0, occupancy,
                "MATSim transit vehicle trajectory km", "basis", occupancyStatus
                + "; PASS_VALIDATED_PT_ACCOUNTING; PASS_COMPLETE_EVENT_AND_VALIDATED_SOURCE; "
                + "REQUIRES_EXTERNAL_UNIT_MAPPING");
    }

    private AnalyzeProduction2040TerritorialModeComparison.ScenarioResult scenarioResult(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            AnalyzeProduction2040TerritorialModeComparison.TripAudit audit,
            AnalyzeProduction2040TerritorialModeComparison.CostInputs cost, String name) {
        Path root = temporary.resolve(name);
        var files = new ValidateProduction2040AnalysisOutput.ValidatedOutput(root,
                root.resolve("config.xml"), root.resolve("trips.csv.gz"),
                root.resolve("plans.xml.gz"), root.resolve("events.xml.gz"),
                root.resolve("network.xml.gz"), root.resolve("schedule.xml.gz"),
                root.resolve("vehicles.xml.gz"), root.resolve("iterations.csv"),
                root.resolve("stuck.csv"), List.of(), Map.of(60,
                new ValidateProduction2040AnalysisOutput.StuckTotal(0, 0, 0, 0)), Map.of());
        var routing = new AnalyzeProduction2040TerritorialCostInputs.ActiveRoutingDefinition(
                Set.of("car", "pt"), 1.3, 1.3, 1.3);
        return new AnalyzeProduction2040TerritorialModeComparison.ScenarioResult(definition,
                files, routing, audit, cost);
    }

    private Person car(String id, Link link, double originX, double destinationX) {
        var factory = PopulationUtils.getFactory(); Person person = factory.createPerson(Id.createPersonId(id)); Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(originX, Y + 50)));
        Leg leg = factory.createLeg("car"); leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(link.getId(), link.getId())); plan.addLeg(leg);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(destinationX, Y + 50))); person.addPlan(plan); person.setSelectedPlan(plan); return person;
    }

    private Person active(String id, String mode, double originX, double destinationX,
            double distance) {
        var factory = PopulationUtils.getFactory(); Person person = factory.createPerson(Id.createPersonId(id)); Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(originX, Y + 50)));
        Leg leg = factory.createLeg(mode); var route = RouteUtils.createGenericRouteImpl(Id.createLinkId("a"), Id.createLinkId("b")); route.setDistance(distance); leg.setRoute(route); plan.addLeg(leg);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(destinationX, Y + 50))); person.addPlan(plan); person.setSelectedPlan(plan); return person;
    }

    private Person stagedWalk(String id, Link link) {
        var factory = PopulationUtils.getFactory(); Person person = factory.createPerson(Id.createPersonId(id)); Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(X - 50, Y + 50)));
        plan.addLeg(teleported(factory, "walk", 100));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(X + 50, Y + 50)));
        plan.addLeg(teleported(factory, "walk", 100));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(X + 150, Y + 50)));
        person.addPlan(plan); person.setSelectedPlan(plan); return person;
    }

    private Person ptTrip(String id, TransitSegment first, TransitSegment second,
            double originX, double destinationX, boolean walkingOnly) {
        var factory = PopulationUtils.getFactory(); Person person = factory.createPerson(Id.createPersonId(id)); Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(originX, Y + 50)));
        plan.addLeg(teleported(factory, "walk", 100));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(X - 10, Y + 50)));
        plan.addLeg(ptLeg(factory, first));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(X + 110, Y + 50)));
        if (second != null) { plan.addLeg(ptLeg(factory, second)); plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE, new Coord(X + 120, Y + 50))); }
        plan.addLeg(teleported(factory, "walk", 100));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(destinationX, Y + 50)));
        person.addPlan(plan); person.setSelectedPlan(plan); return person;
    }

    private static Leg ptLeg(org.matsim.api.core.v01.population.PopulationFactory factory,
            TransitSegment segment) { Leg leg = factory.createLeg("pt"); leg.setRoute(new DefaultTransitPassengerRoute(segment.access(), segment.line(), segment.route(), segment.egress())); return leg; }
    private static Leg teleported(org.matsim.api.core.v01.population.PopulationFactory factory, String mode, double distance) { Leg leg = factory.createLeg(mode); var route = RouteUtils.createGenericRouteImpl(Id.createLinkId("a"), Id.createLinkId("b")); route.setDistance(distance); leg.setRoute(route); return leg; }

    private Fixture fixture() { Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig()); Network network = scenario.getNetwork(); return new Fixture(scenario, network, link(network, "inside", X + 10, X + 90, 80), link(network, "inside2", X + 20, X + 80, 60), link(network, "inbound", X - 50, X + 50, 100), link(network, "outbound", X + 50, X + 150, 100), link(network, "through", X - 50, X + 150, 200), link(network, "outside", X - 90, X - 10, 80), link(network, "outside2", X - 80, X - 20, 60)); }
    private static Link link(Network network, String id, double fromX, double toX, double length) { Node from = network.getFactory().createNode(Id.createNodeId(id + "-from"), new Coord(fromX, Y + 50)); Node to = network.getFactory().createNode(Id.createNodeId(id + "-to"), new Coord(toX, Y + 50)); network.addNode(from); network.addNode(to); Link link = network.getFactory().createLink(Id.createLinkId(id), from, to); link.setLength(length); network.addLink(link); return link; }
    private MunichMunicipalBoundary boundary() throws Exception { Path file = temporary.resolve("boundary.json"); Files.writeString(file, "{\"type\":\"Polygon\",\"coordinates\":[[[" + X + "," + Y + "],[" + (X + 100) + "," + Y + "],[" + (X + 100) + "," + (Y + 100) + "],[" + X + "," + (Y + 100) + "],[" + X + "," + Y + "]]]}", StandardCharsets.UTF_8); return MunichMunicipalBoundary.load(file); }

    private record Fixture(Scenario scenario, Network network, Link inside, Link inside2,
                           Link inbound, Link outbound, Link through, Link outside,
                           Link outside2) {
        TransitSegment transit(String id, Link accessLink, Link egressLink, String mode) {
            var factory = scenario.getTransitSchedule().getFactory();
            var access = factory.createTransitStopFacility(Id.create("access-" + id, TransitStopFacility.class), accessLink.getFromNode().getCoord(), false); access.setLinkId(accessLink.getId()); scenario.getTransitSchedule().addStopFacility(access);
            var egress = factory.createTransitStopFacility(Id.create("egress-" + id, TransitStopFacility.class), egressLink.getToNode().getCoord(), false); egress.setLinkId(egressLink.getId()); scenario.getTransitSchedule().addStopFacility(egress);
            TransitLine line = factory.createTransitLine(Id.create("line-" + id, TransitLine.class));
            TransitRoute route = factory.createTransitRoute(Id.create("route-" + id, TransitRoute.class), RouteUtils.createLinkNetworkRouteImpl(accessLink.getId(), egressLink.getId()), List.of(factory.createTransitRouteStop(access, 0, 0), factory.createTransitRouteStop(egress, 60, 60)), mode);
            line.addRoute(route); scenario.getTransitSchedule().addTransitLine(line); return new TransitSegment(access, line, route, egress);
        }
    }
    private record TransitSegment(TransitStopFacility access, TransitLine line, TransitRoute route,
                                  TransitStopFacility egress) { }
}
