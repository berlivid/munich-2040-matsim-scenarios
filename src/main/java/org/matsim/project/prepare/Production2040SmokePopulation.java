package org.matsim.project.prepare;

import java.util.Comparator;
import java.util.List;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/** Creates four deterministic in-memory agents from actual schedule facilities. */
final class Production2040SmokePopulation {
    private Production2040SmokePopulation() { }

    static SmokeFixture install(Scenario scenario,
            Production2040RunSupport.RunDefinition definition) {
        Production2040Contract.require(scenario.getPopulation().getPersons().isEmpty(),
                "Smoke scenario unexpectedly loaded the production population");
        RouteFixture route = chooseRoute(scenario, definition.fastTrack());
        double departure = route.route().getDepartures().values().stream()
                .mapToDouble(value -> value.getDepartureTime()).min().orElseThrow();
        addPerson(scenario, "smoke-car", TransportMode.car, route.from(), route.to(), departure);
        addPerson(scenario, "smoke-pt", TransportMode.pt, route.from(), route.to(), departure);
        addPerson(scenario, "smoke-walk", TransportMode.walk, route.from(), route.to(), departure);
        addPerson(scenario, "smoke-bike", TransportMode.bike, route.from(), route.to(), departure);
        Production2040Contract.require(scenario.getPopulation().getPersons().keySet().stream()
                        .map(Object::toString).collect(java.util.stream.Collectors.toSet())
                        .equals(Production2040RunSupport.SMOKE_PERSON_IDS),
                "Smoke population identity differs from the protected fixture");
        validateScorableActivityTypes(scenario);
        return new SmokeFixture(route.line().getId().toString(), route.route().getId().toString(),
                route.route().getTransportMode(), route.from().getId().toString(),
                route.to().getId().toString(), departure);
    }

    static RouteFixture chooseRoute(Scenario scenario, boolean fastTrack) {
        List<RouteFixture> candidates = scenario.getTransitSchedule().getTransitLines().values()
                .stream().sorted(Comparator.comparing(line -> line.getId().toString()))
                .flatMap(line -> line.getRoutes().values().stream()
                        .filter(route -> route.getStops().size() >= 3
                                && !route.getDepartures().isEmpty())
                        .filter(route -> fastTrack ? isFastTrackU9(line)
                                : "subway".equals(Production2040AnalysisSpec.normalizePtRouteMode(
                                route.getTransportMode())))
                        .map(route -> fixture(line, route)))
                .filter(java.util.Objects::nonNull).toList();
        Production2040Contract.require(!candidates.isEmpty(),
                fastTrack ? "No actual Fast Track U9 route can anchor the smoke fixture"
                        : "No actual BAU subway route can anchor the smoke fixture");
        return candidates.getFirst();
    }

    private static RouteFixture fixture(TransitLine line, TransitRoute route) {
        TransitStopFacility from = route.getStops().getFirst().getStopFacility();
        TransitStopFacility to = route.getStops().getLast().getStopFacility();
        if (from.getLinkId() == null || to.getLinkId() == null || from.getId().equals(to.getId()))
            return null;
        return new RouteFixture(line, route, from, to);
    }

    private static boolean isFastTrackU9(TransitLine line) {
        return line.getId().toString().equals("FT_U9") || "FT_U9".equals(line.getName())
                || line.getId().toString().endsWith("FT_U9");
    }

    private static void addPerson(Scenario scenario, String id, String mode,
            TransitStopFacility from, TransitStopFacility to, double departure) {
        Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(id));
        Plan plan = scenario.getPopulation().getFactory().createPlan();
        var origin = scenario.getPopulation().getFactory().createActivityFromCoord(
                "home", from.getCoord());
        origin.setEndTime(departure);
        plan.addActivity(origin);
        plan.addLeg(scenario.getPopulation().getFactory().createLeg(mode));
        plan.addActivity(scenario.getPopulation().getFactory().createActivityFromCoord(
                "home", to.getCoord()));
        person.addPlan(plan);
        scenario.getPopulation().addPerson(person);
    }

    static void validateScorableActivityTypes(Scenario scenario) {
        var scoring = scenario.getConfig().scoring();
        var unknown = scenario.getPopulation().getPersons().values().stream()
                .flatMap(person -> person.getPlans().stream())
                .flatMap(plan -> plan.getPlanElements().stream())
                .filter(Activity.class::isInstance)
                .map(Activity.class::cast)
                .map(Activity::getType)
                .filter(type -> type == null || !TripStructureUtils.isStageActivityType(type))
                .filter(type -> type == null || scoring.getActivityParams(type) == null)
                .map(String::valueOf).collect(java.util.stream.Collectors.toCollection(
                        java.util.TreeSet::new));
        Production2040Contract.require(unknown.isEmpty(),
                "Smoke population uses activity types without scoring parameters: " + unknown);
    }

    record SmokeFixture(String lineId, String routeId, String routeMode,
                        String fromStopId, String toStopId, double departureTime) { }
    record RouteFixture(TransitLine line, TransitRoute route,
                        TransitStopFacility from, TransitStopFacility to) { }
}
