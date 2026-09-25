package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

class Production2040PtCostAllocationTest {
    private static final double X = 4_400_000;
    private static final double Y = 5_300_000;
    private static final Id<Person> DRIVER = Id.createPersonId("driver");
    private static final Id<Person> NON_RESIDENT = Id.createPersonId("non-resident");
    private static final Id<Person> UNRESOLVED = Id.createPersonId("unresolved");
    private static final Id<Person> TRANSFER = Id.createPersonId("transfer");

    @TempDir Path temporary;

    @Test
    void countsActualAllPassengerSegmentsAcrossDifferentStopsAndExcludesDriver()
            throws Exception {
        Fixture fixture = fixture();
        assertEquals(Production2040AccountingScopes.ResidentStatus.NON_RESIDENT,
                fixture.index.persons().get(NON_RESIDENT).residentStatus());
        assertEquals(Production2040AccountingScopes.ResidentStatus.UNRESOLVED,
                fixture.index.persons().get(UNRESOLVED).residentStatus());
        Id<Vehicle> bus = fixture.addTransit("bus", "bus", fixture.inside, fixture.outside,
                fixture.stop1, fixture.stop2, fixture.stop3, fixture.stop4);
        fixture.start(bus);
        fixture.arrive(bus, fixture.stop1, 0);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, DRIVER, bus));
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, NON_RESIDENT, bus));
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, UNRESOLVED, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.inside));
        fixture.arrive(bus, fixture.stop2, 3);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3.1, NON_RESIDENT, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(4, bus, fixture.crossing));
        fixture.arrive(bus, fixture.stop3, 5);
        fixture.metrics.handleEvent(new LinkEnterEvent(6, bus, fixture.outside));
        fixture.arrive(bus, fixture.stop4, 7);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(7.1, UNRESOLVED, bus));

        var passenger = fixture.accounting.result().ptPassengerByRouteMode().get("bus");
        var vehicle = fixture.metrics.result().ptByRouteMode().get("bus");
        assertEquals(400.0, passenger.uncutMetres(), 1e-9);
        assertEquals(250.0, passenger.territorialMetres(), 1e-9);
        assertEquals(4, passenger.movementEvents());
        assertEquals(2, vehicle.boardings());
        assertEquals(2, vehicle.completedBoardings());
        assertEquals(0, fixture.metrics.result().unmatchedAlightings());
        assertEquals(0, fixture.metrics.result().openBoardings());
    }

    @Test
    void transferPartitionsPassengerDistanceBetweenRouteModesWithoutEndpointFiltering()
            throws Exception {
        Fixture fixture = fixture();
        Id<Vehicle> bus = fixture.addTransit("transfer-bus", "bus", fixture.inside,
                fixture.inside2, fixture.stop1, fixture.stop2);
        Id<Vehicle> subway = fixture.addTransit("transfer-subway", "subway", fixture.crossing,
                fixture.outside, fixture.stop3, fixture.stop4);
        fixture.start(bus);
        fixture.start(subway);
        fixture.arrive(bus, fixture.stop1, 0);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, TRANSFER, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.inside));
        fixture.arrive(bus, fixture.stop2, 3);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(3.1, TRANSFER, bus));
        fixture.arrive(subway, fixture.stop3, 4);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(4.1, TRANSFER, subway));
        fixture.metrics.handleEvent(new LinkEnterEvent(5, subway, fixture.crossing));
        fixture.arrive(subway, fixture.stop4, 6);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(6.1, TRANSFER, subway));

        var result = fixture.accounting.result().ptPassengerByRouteMode();
        assertEquals(100.0, result.get("bus").territorialMetres(), 1e-9);
        assertEquals(50.0, result.get("subway").territorialMetres(), 1e-9);
        assertEquals(2, fixture.metrics.result().ptByRouteMode().values().stream()
                .mapToLong(Production2040VehicleMetrics.PtMetric::boardings).sum());
    }

    @Test
    void lastLinkCorrectionUsesThePassengersWhoReceivedTheOriginalMovement()
            throws Exception {
        Fixture fixture = fixture();
        Id<Vehicle> bus = fixture.addTransit("first-last", "bus", fixture.inside,
                fixture.inside2, fixture.stop1, fixture.stop2);
        fixture.start(bus);
        fixture.arrive(bus, fixture.stop1, 0);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, NON_RESIDENT, bus));
        fixture.metrics.handleEvent(new VehicleEntersTrafficEvent(2, DRIVER, fixture.inside,
                bus, "car", 0.5));
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(2.5, NON_RESIDENT, bus));
        fixture.metrics.handleEvent(new VehicleLeavesTrafficEvent(3, DRIVER, fixture.inside,
                bus, "car", 0.75));

        assertEquals(25.0, fixture.metrics.result().ptByRouteMode().get("bus")
                .vehicleMetres(), 1e-9);
        assertEquals(25.0, fixture.accounting.result().ptPassengerByRouteMode().get("bus")
                .territorialMetres(), 1e-9);
    }

    @Test
    void pointAnchoredAndZeroLengthPtLinksRetainEstablishedClippingRules() throws Exception {
        Fixture fixture = fixture();
        Id<Vehicle> bus = fixture.addTransit("pseudolinks", "bus", fixture.pseudolink,
                fixture.zeroLength, fixture.stop5, fixture.stop6);
        fixture.start(bus);
        fixture.arrive(bus, fixture.stop5, 0);
        fixture.metrics.handleEvent(new PersonEntersVehicleEvent(1, NON_RESIDENT, bus));
        fixture.metrics.handleEvent(new LinkEnterEvent(2, bus, fixture.pseudolink));
        fixture.metrics.handleEvent(new LinkEnterEvent(3, bus, fixture.zeroLength));
        fixture.arrive(bus, fixture.stop6, 4);
        fixture.metrics.handleEvent(new PersonLeavesVehicleEvent(4.1, NON_RESIDENT, bus));

        var result = fixture.accounting.result();
        var passenger = result.ptPassengerByRouteMode().get("bus");
        assertEquals(50.0, passenger.territorialMetres(), 1e-9);
        assertEquals(2, passenger.movementEvents());
        assertEquals(1, result.ptPseudolinks().usedPointAnchoredLinks());
        assertEquals(1, result.ptPseudolinks().zeroModelLengthLinks());
        assertEquals(0.0, result.ptPseudolinks().zeroModelLengthTerritorialServiceMetres(),
                1e-9);
    }

    @Test
    void allocationsExpandDemandExactlyOnceAndHandleZeroActivityExplicitly() {
        var allPassengers = new AnalyzeProduction2040PtCostAllocation.PassengerMovement(
                20_000, 15_000, 12, 1, 10_000, Set.of("bus"));
        var bothInside = new AnalyzeProduction2040PtCostAllocation.SourcePkm(10, 200, 3,
                100, 0, Set.of("bus"));
        var allBoardings = new AnalyzeProduction2040PtCostAllocation.VehiclePassenger(0,
                12, 12, 3, 3, Set.of("bus"));
        var allocation = AnalyzeProduction2040PtCostAllocation.calculate("bus", Set.of("bus"),
                allPassengers, 500, bothInside, allBoardings);
        assertEquals(15.0, allocation.territorialAllPassengerSamplePkm(), 1e-9);
        assertEquals(300.0, allocation.territorialAllPassengerExpandedPkm(), 1e-9);
        assertEquals(0.6, allocation.occupancy(), 1e-12);
        assertEquals(333.3333333333333, allocation.allocatedBothInsideFkm(), 1e-9);
        AnalyzeProduction2040PtCostAllocation.validateAllocations(List.of(allocation));

        var zero = AnalyzeProduction2040PtCostAllocation.calculate("tram", Set.of("tram"),
                AnalyzeProduction2040PtCostAllocation.PassengerMovement.ZERO, 0,
                AnalyzeProduction2040PtCostAllocation.SourcePkm.ZERO,
                AnalyzeProduction2040PtCostAllocation.VehiclePassenger.ZERO);
        assertTrue(Double.isNaN(zero.occupancy()));
        assertTrue(Double.isNaN(zero.allocatedBothInsideFkm()));
        assertEquals("NOT_APPLICABLE_VALID_ZERO_ACTIVITY", zero.occupancyStatus());
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040PtCostAllocation.calculate("rail", Set.of("rail"),
                        allPassengers, 0,
                        AnalyzeProduction2040PtCostAllocation.SourcePkm.ZERO,
                        allBoardings));
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040PtCostAllocation.calculate("subway", Set.of("subway"),
                        AnalyzeProduction2040PtCostAllocation.PassengerMovement.ZERO, 100,
                        bothInside, AnalyzeProduction2040PtCostAllocation.VehiclePassenger.ZERO));
    }

    @Test
    void vehicleMetadataUsesScheduleVehicleTypesWithoutInferringCarriages() throws Exception {
        Fixture fixture = fixture();
        fixture.addTransit("metadata", "rail", fixture.inside, fixture.inside2,
                fixture.stop1, fixture.stop2);

        var rows = AnalyzeProduction2040PtCostAllocation.readVehicleMetadata(
                fixture.scenario.getTransitSchedule(), fixture.scenario.getTransitVehicles());
        assertEquals(1, rows.size());
        assertEquals(Set.of("rail"), rows.getFirst().routeModes());
        assertEquals(1, rows.getFirst().scheduledVehicleCount());
        assertEquals(1, rows.getFirst().scheduledDepartureCount());
    }

    @Test
    void reportsAreScenarioParameterizedAndExistingDestinationsAreProtected()
            throws Exception {
        var allocation = AnalyzeProduction2040PtCostAllocation.calculate("bus", Set.of("bus"),
                new AnalyzeProduction2040PtCostAllocation.PassengerMovement(1_000, 1_000,
                        1, 0, 0, Set.of("bus")), 100,
                new AnalyzeProduction2040PtCostAllocation.SourcePkm(1, 20, 1, 100, 0,
                        Set.of("bus")),
                new AnalyzeProduction2040PtCostAllocation.VehiclePassenger(1_000, 1, 1, 1, 1,
                        Set.of("bus")));
        var regional = new Production2040VehicleMetrics.Result(0, 0, 0, 0, 0, 0, 0, Map.of());
        var accounting = emptyAccounting();
        var sources = new AnalyzeProduction2040PtCostAllocation.SourceInputs(Map.of("bus",
                new AnalyzeProduction2040PtCostAllocation.SourcePkm(1, 20, 1, 100, 0,
                        Set.of("bus"))), Map.of("bus", 100.0));
        var metadata = List.of(new AnalyzeProduction2040PtCostAllocation.VehicleMetadata(
                "test-type", Set.of("bus"), 1, 1, 20, 30, 50, 12, 2, 1, 10, "car"));
        var bau = Production2040AnalysisSpec.scenario("BAU");
        var fast = Production2040AnalysisSpec.scenario("FAST_TRACK");
        Map<String, String> bauReports = AnalyzeProduction2040PtCostAllocation.buildReports(bau,
                List.of(allocation), metadata, regional, accounting, sources);
        Map<String, String> fastReports = AnalyzeProduction2040PtCostAllocation.buildReports(fast,
                List.of(allocation), metadata, regional, accounting, sources);
        ValidateProduction2040PtCostAllocation.validateBundle(bau, bauReports);
        ValidateProduction2040PtCostAllocation.validateBundle(fast, fastReports);
        assertTrue(fastReports.get("pt_cost_allocation_report.md").startsWith(
                "# FAST TRACK 2040 PT cost allocation"));
        assertFalse(bauReports.get("pt_external_cost_inputs.csv").contains(",20.0,20.0,"));

        Path analysis = temporary.resolve("analysis");
        Path destination = analysis.resolve("pt_cost_allocation");
        Files.createDirectories(destination);
        Path sentinel = destination.resolve("preserved.txt");
        Files.writeString(sentinel, "preserve", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                AnalyzeProduction2040PtCostAllocation.publishAtomically(analysis, destination,
                        bauReports));
        assertEquals("preserve", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    private Fixture fixture() throws Exception {
        MunichMunicipalBoundary boundary = boundary();
        List<Person> persons = List.of(person(NON_RESIDENT, "home", new Coord(X - 20, Y + 10)),
                person(UNRESOLVED, "work", new Coord(X + 10, Y + 10)),
                person(TRANSFER, "home", new Coord(X - 20, Y + 20)));
        var index = Production2040AccountingScopes.classify(persons, boundary);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Id<Link> inside = link(scenario.getNetwork(), "inside", X + 10, X + 40, 100);
        Id<Link> inside2 = link(scenario.getNetwork(), "inside2", X + 40, X + 80, 100);
        Id<Link> crossing = link(scenario.getNetwork(), "crossing", X + 80, X + 120, 100);
        Id<Link> outside = link(scenario.getNetwork(), "outside", X + 120, X + 150, 100);
        Id<Link> pseudolink = link(scenario.getNetwork(), "pseudolink", X + 20, X + 20, 50);
        Id<Link> zeroLength = link(scenario.getNetwork(), "zero-length", X + 30, X + 30, 0);
        var factory = scenario.getTransitSchedule().getFactory();
        Id<TransitStopFacility> stop1 = stop(scenario, factory, "s1", inside, X + 15);
        Id<TransitStopFacility> stop2 = stop(scenario, factory, "s2", inside2, X + 50);
        Id<TransitStopFacility> stop3 = stop(scenario, factory, "s3", crossing, X + 90);
        Id<TransitStopFacility> stop4 = stop(scenario, factory, "s4", outside, X + 130);
        Id<TransitStopFacility> stop5 = stop(scenario, factory, "s5", pseudolink, X + 20);
        Id<TransitStopFacility> stop6 = stop(scenario, factory, "s6", zeroLength, X + 30);
        var observer = new Production2040AccountingEventMetrics(scenario.getNetwork(), boundary,
                index);
        var metrics = new Production2040VehicleMetrics(scenario.getNetwork(),
                scenario.getTransitSchedule(), scenario.getTransitVehicles(), Map.of(), observer);
        return new Fixture(scenario, index, observer, metrics, inside, inside2, crossing, outside,
                pseudolink, zeroLength, stop1, stop2, stop3, stop4, stop5, stop6);
    }

    private MunichMunicipalBoundary boundary() throws Exception {
        Path file = temporary.resolve("boundary-" + System.nanoTime() + ".json");
        Files.writeString(file, """
                {"type":"Polygon","coordinates":[[[4400000,5300000],[4400100,5300000],[4400100,5300100],[4400000,5300100],[4400000,5300000]]]}
                """);
        return MunichMunicipalBoundary.load(file);
    }

    private static Person person(Id<Person> id, String originType, Coord origin) {
        Person person = PopulationUtils.getFactory().createPerson(id);
        var plan = PopulationUtils.createPlan();
        plan.addActivity(PopulationUtils.createActivityFromCoord(originType, origin));
        plan.addLeg(PopulationUtils.createLeg(TransportMode.pt));
        plan.addActivity(PopulationUtils.createActivityFromCoord("work", new Coord(X + 20, Y + 20)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Id<Link> link(Network network, String id, double fromX, double toX,
            double length) {
        Node from = NetworkUtils.createAndAddNode(network, Id.createNodeId(id + "-from"),
                new Coord(fromX, Y + 50));
        Node to = NetworkUtils.createAndAddNode(network, Id.createNodeId(id + "-to"),
                new Coord(toX, Y + 50));
        Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), from, to,
                length, 10, 1000, 1);
        return link.getId();
    }

    private static Id<TransitStopFacility> stop(Scenario scenario,
            org.matsim.pt.transitSchedule.api.TransitScheduleFactory factory, String id,
            Id<Link> link, double x) {
        Id<TransitStopFacility> stopId = Id.create(id, TransitStopFacility.class);
        TransitStopFacility stop = factory.createTransitStopFacility(stopId,
                new Coord(x, Y + 50), false);
        stop.setLinkId(link);
        scenario.getTransitSchedule().addStopFacility(stop);
        return stopId;
    }

    private static Production2040AccountingEventMetrics.Result emptyAccounting() {
        Map<Production2040AccountingScopes.Scope, Production2040AccountingEventMetrics.CarScope>
                cars = Map.of(Production2040AccountingScopes.Scope.BOTH_INSIDE,
                        new Production2040AccountingEventMetrics.CarScope(0, 0, 0, 0),
                        Production2040AccountingScopes.Scope.MUNICH_RESIDENTS,
                        new Production2040AccountingEventMetrics.CarScope(0, 0, 0, 0));
        Map<MunichTripBoundaryFilter.SpatialCategory, Double> endpoints = new java.util.EnumMap<>(
                MunichTripBoundaryFilter.SpatialCategory.class);
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            endpoints.put(category, 0.0);
        }
        return new Production2040AccountingEventMetrics.Result(cars, endpoints, Map.of(), 0,
                0, 0, 0, 0, 0, 0, 0);
    }

    private record Fixture(Scenario scenario, Production2040AccountingScopes.Index index,
                           Production2040AccountingEventMetrics accounting,
                           Production2040VehicleMetrics metrics, Id<Link> inside,
                           Id<Link> inside2, Id<Link> crossing, Id<Link> outside,
                           Id<Link> pseudolink, Id<Link> zeroLength,
                           Id<TransitStopFacility> stop1, Id<TransitStopFacility> stop2,
                           Id<TransitStopFacility> stop3, Id<TransitStopFacility> stop4,
                           Id<TransitStopFacility> stop5, Id<TransitStopFacility> stop6) {
        Id<Vehicle> addTransit(String id, String mode, Id<Link> first, Id<Link> last,
                Id<TransitStopFacility>... stops) {
            var vehicleFactory = scenario.getTransitVehicles().getFactory();
            VehicleType type = vehicleFactory.createVehicleType(Id.create("type-" + id,
                    VehicleType.class));
            scenario.getTransitVehicles().addVehicleType(type);
            Id<Vehicle> vehicle = Id.createVehicleId(id);
            scenario.getTransitVehicles().addVehicle(vehicleFactory.createVehicle(vehicle, type));
            var scheduleFactory = scenario.getTransitSchedule().getFactory();
            Id<TransitLine> lineId = Id.create("line-" + id, TransitLine.class);
            Id<TransitRoute> routeId = Id.create("route-" + id, TransitRoute.class);
            TransitLine line = scheduleFactory.createTransitLine(lineId);
            List<org.matsim.pt.transitSchedule.api.TransitRouteStop> routeStops =
                    java.util.Arrays.stream(stops).map(stop -> scheduleFactory
                            .createTransitRouteStop(scenario.getTransitSchedule().getFacilities()
                                    .get(stop), 0, 0)).toList();
            TransitRoute route = scheduleFactory.createTransitRoute(routeId,
                    RouteUtils.createLinkNetworkRouteImpl(first, last), routeStops, mode);
            Id<Departure> departureId = Id.create("departure-" + id, Departure.class);
            Departure departure = scheduleFactory.createDeparture(departureId, 0);
            departure.setVehicleId(vehicle);
            route.addDeparture(departure);
            line.addRoute(route);
            scenario.getTransitSchedule().addTransitLine(line);
            return vehicle;
        }

        void start(Id<Vehicle> vehicle) {
            String id = vehicle.toString();
            metrics.handleEvent(new TransitDriverStartsEvent(0, DRIVER, vehicle,
                    Id.create("line-" + id, TransitLine.class),
                    Id.create("route-" + id, TransitRoute.class),
                    Id.create("departure-" + id, Departure.class)));
        }

        void arrive(Id<Vehicle> vehicle, Id<TransitStopFacility> stop, double time) {
            metrics.handleEvent(new VehicleArrivesAtFacilityEvent(time, vehicle, stop, 0));
        }
    }
}
