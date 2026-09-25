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
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

/**
 * Read-only structural and routing validator for one approved 2040 production
 * input bundle. It deliberately never creates a Controller or output file.
 */
public final class ValidateMatsim2040ProductionInput {
    private static final Set<String> EXPECTED_PLAN_MODES = Set.of(
            TransportMode.car, TransportMode.pt, TransportMode.walk, TransportMode.bike);

    private ValidateMatsim2040ProductionInput() { }

    public static void main(String[] args) throws Exception {
        Production2040Contract.require(args.length == 1,
                "Usage: ValidateMatsim2040ProductionInput BAU|FAST_TRACK");
        validate(Production2040RunSupport.scenario(args[0]));
    }

    static ValidationSummary validate(Production2040RunSupport.RunDefinition definition)
            throws Exception {
        ValidateProduction2040Configs.validateFiles(Production2040Contract.BAU.configPath(),
                Production2040Contract.FAST_TRACK.configPath(), false);
        var contract = Production2040Contract.loadAndValidate();
        Map<Path, String> protectedBefore = Production2040Contract.protectedInputSnapshot(contract);

        Scenario scenario = ScenarioUtils.loadScenario(
                Production2040RunSupport.productionConfig(definition));
        ValidationSummary summary = validateScenario(definition, scenario);
        validatePtRouting(scenario);
        Production2040Contract.require(protectedBefore.equals(
                        Production2040Contract.protectedInputSnapshot(contract)),
                "Read-only production-input validation changed a protected input");
        System.out.printf("2040 PRODUCTION INPUT VALIDATION PASS scenario=%s persons=%d "
                        + "nodes=%d links=%d lines=%d routes=%d departures=%d vehicles=%d "
                        + "plan_modes=%s pt_route_modes=%s shared_parameters=149/149%n",
                definition.argument(), summary.persons(), summary.nodes(), summary.links(),
                summary.lines(), summary.routes(), summary.departures(), summary.vehicles(),
                summary.planModes(), summary.ptRouteModes());
        System.out.println("No Controller or QSim was started; no output was created.");
        return summary;
    }

    static ValidationSummary validateScenario(Production2040RunSupport.RunDefinition definition,
            Scenario scenario) throws Exception {
        var config = scenario.getConfig();
        Production2040Contract.require("EPSG:31468".equals(config.global().getCoordinateSystem()),
                "Config CRS must be EPSG:31468");
        Production2040Contract.require(config.transit().isUseTransit(), "useTransit must be true");
        Production2040Contract.require(!scenario.getPopulation().getPersons().isEmpty(),
                "Population is empty");
        Production2040Contract.require(!scenario.getNetwork().getLinks().isEmpty(),
                "Network is empty");
        Production2040Contract.require(!scenario.getTransitSchedule().getTransitLines().isEmpty(),
                "Transit schedule is empty");
        Production2040Contract.require(!scenario.getTransitVehicles().getVehicles().isEmpty(),
                "Transit vehicles are empty");

        validateInputCoordinateDomain(scenario);
        Map<String, Long> planModes = validatePopulation(scenario);
        Map<String, Long> routeModes = new TreeMap<>();
        Set<?> vehicleIds = scenario.getTransitVehicles().getVehicles().keySet();
        long routes = 0;
        long departures = 0;
        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                routes++;
                routeModes.merge(Production2040AnalysisSpec.normalizePtRouteMode(
                        route.getTransportMode()), 1L, Long::sum);
                Production2040Contract.require(route.getStops().size() >= 2,
                        "Transit route has fewer than two stops: " + route.getId());
                route.getStops().forEach(stop -> {
                    var facility = stop.getStopFacility();
                    Production2040Contract.require(scenario.getTransitSchedule().getFacilities()
                                    .containsKey(facility.getId()),
                            "Route references an unknown stop " + facility.getId());
                    Production2040Contract.require(facility.getLinkId() != null
                                    && scenario.getNetwork().getLinks().containsKey(facility.getLinkId()),
                            "Stop lacks a valid network link " + facility.getId());
                });
                if (route.getRoute() != null) {
                    if (route.getRoute().getStartLinkId() != null)
                        requireLink(scenario, route.getRoute().getStartLinkId().toString(), route);
                    route.getRoute().getLinkIds().forEach(id ->
                            requireLink(scenario, id.toString(), route));
                    if (route.getRoute().getEndLinkId() != null)
                        requireLink(scenario, route.getRoute().getEndLinkId().toString(), route);
                }
                for (var departure : route.getDepartures().values()) {
                    departures++;
                    Production2040Contract.require(departure.getVehicleId() != null
                                    && vehicleIds.contains(departure.getVehicleId()),
                            "Departure references a missing transit vehicle: "
                                    + departure.getVehicleId());
                }
            }
        }
        scenario.getTransitVehicles().getVehicles().values().forEach(vehicle ->
                Production2040Contract.require(vehicle.getType() != null,
                        "Transit vehicle has no type: " + vehicle.getId()));

        validateScenarioIdentity(definition, scenario);
        FastTrackPedestrianZones.validateCarNetworkConnected(scenario.getNetwork());
        var restrictions = FastTrackPedestrianZones.readSpecification(
                FastTrackPedestrianZones.SPECIFICATION);
        if (definition.fastTrack()) {
            FastTrackPedestrianZones.validateApplied(scenario.getNetwork(), restrictions);
            FastTrackPedestrianZones.validatePerimeterCarConnectivity(scenario.getNetwork());
        } else {
            restrictions.forEach(restriction -> Production2040Contract.require(
                    scenario.getNetwork().getLinks().get(Id.createLinkId(restriction.linkId()))
                            .getAllowedModes().contains(TransportMode.car),
                    "BAU unexpectedly contains Fast Track pedestrian restriction on "
                            + restriction.linkId()));
        }
        return new ValidationSummary(scenario.getPopulation().getPersons().size(),
                scenario.getNetwork().getNodes().size(), scenario.getNetwork().getLinks().size(),
                scenario.getTransitSchedule().getTransitLines().size(), routes, departures,
                scenario.getTransitVehicles().getVehicles().size(), Map.copyOf(planModes),
                Map.copyOf(routeModes));
    }

    private static Map<String, Long> validatePopulation(Scenario scenario) {
        Map<String, Long> modes = new TreeMap<>();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            Production2040Contract.require(person.getSelectedPlan() != null,
                    "Person has no selected plan: " + person.getId());
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            Production2040Contract.require(!elements.isEmpty() && elements.size() % 2 == 1,
                    "Selected plan is not a complete activity-leg chain: " + person.getId());
            for (int index = 0; index < elements.size(); index++) {
                PlanElement element = elements.get(index);
                if (index % 2 == 0) {
                    Production2040Contract.require(element instanceof Activity,
                            "Expected activity in selected plan of " + person.getId());
                    Activity activity = (Activity) element;
                    Production2040Contract.require(activity.getCoord() != null
                                    && Double.isFinite(activity.getCoord().getX())
                                    && Double.isFinite(activity.getCoord().getY()),
                            "Missing or invalid activity coordinate for " + person.getId());
                } else {
                    Production2040Contract.require(element instanceof Leg,
                            "Expected leg in selected plan of " + person.getId());
                    String mode = ((Leg) element).getMode();
                    Production2040Contract.require(EXPECTED_PLAN_MODES.contains(mode),
                            "Unexpected input plan mode " + mode + " for " + person.getId());
                    modes.merge(mode, 1L, Long::sum);
                }
            }
        }
        Production2040Contract.require(modes.keySet().equals(EXPECTED_PLAN_MODES),
                "Population must contain all and only car, pt, walk and bike legs: " + modes);
        return modes;
    }

    private static void validateInputCoordinateDomain(Scenario scenario) {
        scenario.getNetwork().getNodes().values().forEach(node -> {
            double x = node.getCoord().getX();
            double y = node.getCoord().getY();
            Production2040Contract.require(Double.isFinite(x) && Double.isFinite(y)
                            && x >= 3_000_000 && x <= 7_000_000
                            && y >= 3_000_000 && y <= 7_000_000,
                    "Network coordinate is incompatible with EPSG:31468: " + node.getId());
        });
    }

    private static void validateScenarioIdentity(
            Production2040RunSupport.RunDefinition definition, Scenario scenario) {
        Set<String> fastLines = Set.of("FT_U9", "FT_NR_A", "FT_NR_B");
        Set<String> found = new HashSet<>();
        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (String id : fastLines) if (line.getId().toString().equals(id)
                    || id.equals(line.getName()) || line.getId().toString().endsWith(id)) found.add(id);
        }
        Production2040Contract.require(definition.fastTrack() ? found.equals(fastLines) : found.isEmpty(),
                "Fast Track transit measures do not match scenario identity: " + found);
        Production2040Contract.require(definition.contract().runId().equals(
                        scenario.getConfig().controller().getRunId()),
                "Wrong production run ID");
        Production2040Contract.require(Production2040Contract.projectPath(
                        definition.productionOutput()).equals(scenario.getConfig().controller()
                        .getOutputDirectory().replace('\\', '/')),
                "Wrong protected production output directory");
    }

    private static void validatePtRouting(Scenario scenario) {
        OccupancyData occupancy = new OccupancyData();
        SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(),
                scenario.getTransitVehicles(), RaptorUtils.createStaticConfig(scenario.getConfig()),
                scenario.getNetwork(), occupancy);
        SwissRailRaptor router = new SwissRailRaptor(data,
                new DefaultRaptorParametersForPerson(scenario.getConfig()),
                new LeastCostRaptorRouteSelector(),
                new DefaultRaptorStopFinder(new DefaultRaptorIntermodalAccessEgress(), Map.of()),
                new DefaultRaptorInVehicleCostCalculator(),
                new DefaultRaptorTransferCostCalculator());
        for (String mode : Production2040AnalysisSpec.PT_ROUTE_MODES) {
            boolean present = scenario.getTransitSchedule().getTransitLines().values().stream()
                    .flatMap(line -> line.getRoutes().values().stream())
                    .anyMatch(route -> mode.equals(Production2040AnalysisSpec
                            .normalizePtRouteMode(route.getTransportMode())));
            if (present) routeOne(scenario, router, mode);
        }
    }

    private static void routeOne(Scenario scenario, SwissRailRaptor router, String mode) {
        var candidates = scenario.getTransitSchedule().getTransitLines().values().stream()
                .sorted(Comparator.comparing(line -> line.getId().toString()))
                .flatMap(line -> line.getRoutes().values().stream()
                        .filter(route -> mode.equals(Production2040AnalysisSpec
                                .normalizePtRouteMode(route.getTransportMode())))
                        .filter(route -> route.getStops().size() >= 3
                                && !route.getDepartures().isEmpty())
                        .map(route -> new Candidate(line, route))).toList();
        for (Candidate candidate : candidates) {
            TransitRoute route = candidate.route();
            var fromStop = route.getStops().getFirst().getStopFacility();
            var toStop = route.getStops().getLast().getStopFacility();
            if (fromStop.getId().equals(toStop.getId()) || fromStop.getLinkId() == null
                    || toStop.getLinkId() == null) continue;
            var fromLink = scenario.getNetwork().getLinks().get(fromStop.getLinkId());
            var toLink = scenario.getNetwork().getLinks().get(toStop.getLinkId());
            if (fromLink == null || toLink == null) continue;
            double departure = route.getDepartures().values().stream()
                    .mapToDouble(value -> value.getDepartureTime()).min().orElseThrow();
            Person person = PopulationUtils.getFactory().createPerson(
                    Id.createPersonId("production-input-routing-" + mode));
            List<? extends PlanElement> result = router.calcRoute(
                    FacilitiesUtils.wrapLinkAndCoord(fromLink, fromStop.getCoord()),
                    FacilitiesUtils.wrapLinkAndCoord(toLink, toStop.getCoord()),
                    departure, departure, departure, person, person.getAttributes());
            if (result == null) continue;
            boolean usesMode = result.stream().filter(Leg.class::isInstance).map(Leg.class::cast)
                    .filter(leg -> leg.getRoute() instanceof TransitPassengerRoute)
                    .map(leg -> (TransitPassengerRoute) leg.getRoute()).anyMatch(pt -> {
                        TransitLine line = scenario.getTransitSchedule().getTransitLines()
                                .get(pt.getLineId());
                        TransitRoute used = line == null ? null : line.getRoutes().get(pt.getRouteId());
                        return used != null && mode.equals(Production2040AnalysisSpec
                                .normalizePtRouteMode(used.getTransportMode()));
                    });
            if (usesMode) return;
        }
        throw new IllegalStateException("No representative " + mode
                + " connection was routable with SwissRailRaptor");
    }

    private static void requireLink(Scenario scenario, String linkId, TransitRoute route) {
        Production2040Contract.require(scenario.getNetwork().getLinks()
                        .containsKey(Id.createLinkId(linkId)),
                "Transit route " + route.getId() + " references missing link " + linkId);
    }

    record ValidationSummary(long persons, long nodes, long links, long lines,
                             long routes, long departures, long vehicles,
                             Map<String, Long> planModes,
                             Map<String, Long> ptRouteModes) { }
    private record Candidate(TransitLine line, TransitRoute route) { }
}
