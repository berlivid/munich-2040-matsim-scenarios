package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.LeastCostRaptorRouteSelector;
import ch.sbb.matsim.routing.pt.raptor.OccupancyData;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

/** Structural, routing, and server-side iteration-zero validation of the synthetic-2019 inputs. */
public final class ValidateGtfs2019CalibrationInput {
    private ValidateGtfs2019CalibrationInput() { }

    public static void main(String[] args) {
        require(args.length == 0, "This validation does not accept program arguments");
        Config config = loadAndValidateConfig();
        Scenario scenario = ScenarioUtils.loadScenario(config);
        validateScenario(scenario);
        validateTimeHorizon(config, scenario);
        runRoutingChecks(scenario);

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.run();
        System.out.println("GTFS 2019 END-TO-END VALIDATION PASS: iteration 0 completed normally.");
    }

    /** Used by focused local tests; deliberately does not load the population or start QSim. */
    static void validateStructureOnly() {
        Config config = loadAndValidateConfig();
        Scenario scenario = CreateGtfs2019CalibrationTransit.loadPublished();
        validateScenario(scenario);
        validateTimeHorizon(config, scenario);
        runRoutingChecks(scenario);
        System.out.println("GTFS 2019 STRUCTURAL VALIDATION PASS: no QSim was started.");
    }

    private static Config loadAndValidateConfig() {
        require(Files.isRegularFile(CreateGtfs2019CalibrationTransit.VALIDATION_CONFIG),
                "Missing validation config");
        Config config = ConfigUtils.loadConfig(CreateGtfs2019CalibrationTransit.VALIDATION_CONFIG.toString());
        require(config.transit().isUseTransit(), "useTransit is false");
        require(config.controller().getFirstIteration() == 0 && config.controller().getLastIteration() == 0,
                "Validation config is not restricted to iteration 0");
        require(config.replanning().getStrategySettings().stream().noneMatch(s ->
                        s.getStrategyName() != null && s.getStrategyName().toLowerCase().contains("modechoice")),
                "Mode choice is active");
        require("munich-calibration-2019".equals(config.controller().getRunId()),
                "Unexpected validation runId");
        require(config.qsim().getNumberOfThreads() == 2, "QSim must use two threads");
        require(config.qsim().getEndTime().isDefined(),
                "qsim.endTime is undefined; refusing to start an unbounded QSim");
        require(Double.isFinite(config.qsim().getEndTime().seconds()),
                "qsim.endTime is not finite");
        require(config.controller().getOverwriteFileSetting()
                        == org.matsim.core.controler.OutputDirectoryHierarchy
                        .OverwriteFileSetting.failIfDirectoryExists,
                "Validation output must fail if its directory already exists");
        require(config.network().getInputFile().endsWith("input_transit/network-with-pt.xml.gz"),
                "Unexpected validation network");
        require(config.plans().getInputFile().contains("munich-v1.0-5pct.plans.xml"),
                "Unexpected validation population");
        return config;
    }

    private static void validateTimeHorizon(Config config, Scenario scenario) {
        Gtfs2019ScheduleTimePolicy.Audit audit =
                Gtfs2019ScheduleTimePolicy.audit(scenario.getTransitSchedule());
        Gtfs2019ScheduleTimePolicy.validateConfiguredEndTime(
                config.qsim().getEndTime().seconds(), audit);
        System.out.println(audit.summary());
        for (Gtfs2019ScheduleTimePolicy.RouteTiming route : audit.longDurationRoutes()) {
            System.out.printf("GTFS 2019 LONG-DURATION REVIEW: line=%s route=%s mode=%s "
                            + "duration=%s latestDeparture=%s latestArrival=%s vehicle=%s%n",
                    route.lineId(), route.routeId(), route.mode(),
                    Gtfs2019ScheduleTimePolicy.formatTime(route.duration()),
                    Gtfs2019ScheduleTimePolicy.formatTime(route.latestDeparture()),
                    Gtfs2019ScheduleTimePolicy.formatTime(route.latestArrival()),
                    route.latestVehicleId());
        }
    }

    private static void validateScenario(Scenario scenario) {
        require(!scenario.getNetwork().getLinks().isEmpty(), "Network is empty");
        require(!scenario.getTransitSchedule().getTransitLines().isEmpty(), "Schedule is empty");
        require(!scenario.getTransitVehicles().getVehicles().isEmpty(), "Transit vehicles are empty");
        Set<?> vehicleIds = scenario.getTransitVehicles().getVehicles().keySet();
        scenario.getTransitSchedule().getTransitLines().values().forEach(line ->
                line.getRoutes().values().forEach(route -> {
                    route.getStops().forEach(stop -> require(stop.getStopFacility().getLinkId() != null
                                    && scenario.getNetwork().getLinks().containsKey(stop.getStopFacility().getLinkId()),
                            "Invalid stop link reference: " + stop.getStopFacility().getId()));
                    route.getDepartures().values().forEach(departure ->
                            require(departure.getVehicleId() != null
                                            && vehicleIds.contains(departure.getVehicleId()),
                                    "Missing departure vehicle: " + departure.getVehicleId()));
                }));
    }

    private static void runRoutingChecks(Scenario scenario) {
        OccupancyData occupancy = new OccupancyData();
        SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(),
                scenario.getTransitVehicles(), RaptorUtils.createStaticConfig(scenario.getConfig()),
                scenario.getNetwork(), occupancy);
        SwissRailRaptor router = new SwissRailRaptor(data,
                new DefaultRaptorParametersForPerson(scenario.getConfig()),
                new LeastCostRaptorRouteSelector(),
                new DefaultRaptorStopFinder(new DefaultRaptorIntermodalAccessEgress(), Map.of()),
                new DefaultRaptorInVehicleCostCalculator(), new DefaultRaptorTransferCostCalculator());
        for (String mode : List.of("bus", "tram", "subway", "rail")) routeOne(scenario, router, mode);
        System.out.println("GTFS 2019 VALIDATION PASS: inputs load, references close, and bus/tram/subway/rail route with SwissRailRaptor.");
    }

    private static void routeOne(Scenario scenario, SwissRailRaptor router, String mode) {
        var candidates = scenario.getTransitSchedule().getTransitLines().values().stream()
                .sorted(Comparator.comparing(line -> line.getId().toString()))
                .flatMap(line -> line.getRoutes().values().stream()
                        .filter(route -> mode.equals(route.getTransportMode()))
                        .filter(route -> route.getStops().size() >= 3 && !route.getDepartures().isEmpty())
                        .map(route -> new Candidate(line, route)))
                .toList();
        for (Candidate candidate : candidates) {
            TransitRoute route = candidate.route();
            var fromStop = route.getStops().getFirst().getStopFacility();
            var toStop = route.getStops().getLast().getStopFacility();
            if (fromStop.getId().equals(toStop.getId()) || fromStop.getLinkId() == null || toStop.getLinkId() == null) continue;
            var fromLink = scenario.getNetwork().getLinks().get(fromStop.getLinkId());
            var toLink = scenario.getNetwork().getLinks().get(toStop.getLinkId());
            if (fromLink == null || toLink == null) continue;
            double departure = route.getDepartures().values().stream().mapToDouble(d -> d.getDepartureTime()).min().orElseThrow();
            var person = PopulationUtils.getFactory().createPerson(Id.createPersonId("gtfs2019-" + mode));
            List<? extends PlanElement> result = router.calcRoute(
                    FacilitiesUtils.wrapLinkAndCoord(fromLink, fromStop.getCoord()),
                    FacilitiesUtils.wrapLinkAndCoord(toLink, toStop.getCoord()),
                    departure, departure, departure, person, person.getAttributes());
            if (result == null) continue;
            boolean usesMode = result.stream().filter(Leg.class::isInstance).map(Leg.class::cast)
                    .filter(leg -> leg.getRoute() instanceof TransitPassengerRoute)
                    .map(leg -> (TransitPassengerRoute) leg.getRoute())
                    .anyMatch(pt -> {
                        TransitLine line = scenario.getTransitSchedule().getTransitLines().get(pt.getLineId());
                        TransitRoute used = line == null ? null : line.getRoutes().get(pt.getRouteId());
                        return used != null && mode.equals(used.getTransportMode());
                    });
            if (usesMode) {
                System.out.printf("ROUTE PASS %s: line=%s route=%s from=%s to=%s departure=%.0f%n",
                        mode, candidate.line().getId(), route.getId(), fromStop.getName(), toStop.getName(), departure);
                return;
            }
        }
        throw new IllegalStateException("No representative " + mode + " connection was routable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private record Candidate(TransitLine line, TransitRoute route) { }
}
