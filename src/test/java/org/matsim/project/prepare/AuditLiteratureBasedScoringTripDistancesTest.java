package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.PtConstants;

class AuditLiteratureBasedScoringTripDistancesTest {
    private static MunichMunicipalBoundary boundary;
    private static MunichTripBoundaryFilter filter;
    private static Coord insideA;
    private static Coord insideB;

    @BeforeAll
    static void prepareBoundary() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        filter = new MunichTripBoundaryFilter(boundary);
        var point = boundary.geometry().getInteriorPoint().getCoordinate();
        insideA = new Coord(point.x, point.y);
        insideB = new Coord(point.x + 500, point.y + 250);
        assertTrue(boundary.covers(insideA));
        assertTrue(boundary.covers(insideB));
    }

    @Test
    void ignoresPtStagesUsesBothInsideAndMatchesModeTransition(@TempDir Path temp) {
        Path input = temp.resolve("input.xml.gz");
        Path output = temp.resolve("output.xml.gz");
        write(input, person("p1", simplePlan("car", insideA, insideB, true)));
        write(output, person("p1", ptPlan(insideA, insideB)));

        var result = AuditLiteratureBasedScoringTripDistances.auditPopulations(input, output, filter);

        assertEquals(1, result.inputPersons());
        assertEquals(1, result.inputTrips());
        assertEquals(1, result.finalTrips(), "A PT interaction must not create a second main trip");
        assertEquals(1, result.finalBothInside());
        assertEquals(0, result.unmatchedInput());
        assertEquals(0, result.unmatchedFinal());
        assertEquals(1, result.transitions().get(
                new AuditLiteratureBasedScoringTripDistances.TransitionKey("car", "pt")).size());
        assertEquals(1, result.finalState().modes.get("pt").od.size());
    }

    @Test
    void detectsChangedTripIdentityAndDuplicateKeys(@TempDir Path temp) {
        Path input = temp.resolve("input.xml.gz");
        Path output = temp.resolve("output.xml.gz");
        write(input, person("p1", simplePlan("walk", insideA, insideB, false)));
        write(output, person("p1", simplePlan("walk", insideA,
                new Coord(insideB.getX() + 1, insideB.getY()), false)));
        assertThrows(IllegalStateException.class,
                () -> AuditLiteratureBasedScoringTripDistances.auditPopulations(input, output, filter));

        var values = new HashMap<AuditLiteratureBasedScoringTripDistances.TripKey,
                AuditLiteratureBasedScoringTripDistances.TripObservation>();
        var key = new AuditLiteratureBasedScoringTripDistances.TripKey("p1", 0);
        var observation = observation(simplePlan("walk", insideA, insideB, false));
        AuditLiteratureBasedScoringTripDistances.putUnique(values, key, observation, "fixture");
        assertThrows(IllegalStateException.class,
                () -> AuditLiteratureBasedScoringTripDistances.putUnique(values, key, observation, "fixture"));

        Path otherOutput = temp.resolve("other-output.xml.gz");
        write(otherOutput, person("p2", simplePlan("walk", insideA, insideB, false)));
        var unmatched = AuditLiteratureBasedScoringTripDistances.auditPopulations(input, otherOutput, filter);
        assertEquals(1, unmatched.unmatchedInput());
        assertEquals(1, unmatched.unmatchedFinal());
    }

    @Test
    void appliesBinsThresholdsAndInterpolatedPercentilesAtBoundaries() {
        assertEquals("0-1 km", AuditLiteratureBasedScoringTripDistances.distanceBin(1000));
        assertEquals(">1-2 km", AuditLiteratureBasedScoringTripDistances.distanceBin(1000.001));
        assertEquals(">2-3 km", AuditLiteratureBasedScoringTripDistances.distanceBin(3000));
        assertEquals(">3-5 km", AuditLiteratureBasedScoringTripDistances.distanceBin(5000));
        assertEquals(">5-10 km", AuditLiteratureBasedScoringTripDistances.distanceBin(10_000));
        assertEquals(">10-20 km", AuditLiteratureBasedScoringTripDistances.distanceBin(20_000));
        assertEquals(">20 km", AuditLiteratureBasedScoringTripDistances.distanceBin(20_000.001));
        assertFalse(AuditLiteratureBasedScoringTripDistances.exceeds(3000, 3));
        assertTrue(AuditLiteratureBasedScoringTripDistances.exceeds(3000.001, 3));
        assertEquals(2.5, AuditLiteratureBasedScoringTripDistances.percentile(
                List.of(1.0, 2.0, 3.0, 4.0), .5), 1e-12);
        assertEquals(3.7, AuditLiteratureBasedScoringTripDistances.percentile(
                List.of(1.0, 2.0, 3.0, 4.0), .9), 1e-12);
    }

    @Test
    void keepsMissingRouteDistanceMissingAndReadsCompleteRoutes() {
        Plan missing = simplePlan("bike", insideA, insideB, false);
        var missingTrip = TripStructureUtils.getTrips(missing).getFirst();
        assertTrue(Double.isNaN(AuditLiteratureBasedScoringTripDistances.routeDistance(missingTrip)));

        Plan routed = simplePlan("bike", insideA, insideB, true);
        var routedTrip = TripStructureUtils.getTrips(routed).getFirst();
        assertEquals(1234, AuditLiteratureBasedScoringTripDistances.routeDistance(routedTrip));
    }

    @Test
    void refusesExistingAuditDirectory(@TempDir Path temp) throws Exception {
        AuditLiteratureBasedScoringTripDistances.requireOutputAbsent(temp.resolve("new"));
        Path existing = Files.createDirectory(temp.resolve("existing"));
        assertThrows(IllegalStateException.class,
                () -> AuditLiteratureBasedScoringTripDistances.requireOutputAbsent(existing));
    }

    private static AuditLiteratureBasedScoringTripDistances.TripObservation observation(Plan plan) {
        var classified = filter.classify(plan).getFirst();
        return AuditLiteratureBasedScoringTripDistances.observation(
                classified, TripStructureUtils.getTrips(plan).getFirst());
    }

    private static Person person(String id, Plan plan) {
        var factory = PopulationUtils.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        return person;
    }

    private static Plan simplePlan(String mode, Coord from, Coord to, boolean route) {
        var factory = PopulationUtils.getFactory();
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", from));
        var leg = factory.createLeg(mode);
        if (route) {
            var generic = RouteUtils.createGenericRouteImpl(
                    Id.createLinkId("from"), Id.createLinkId("to"));
            generic.setDistance(1234);
            leg.setRoute(generic);
        }
        plan.addLeg(leg);
        plan.addActivity(factory.createActivityFromCoord("work", to));
        return plan;
    }

    private static Plan ptPlan(Coord from, Coord to) {
        var factory = PopulationUtils.getFactory();
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", from));
        plan.addLeg(factory.createLeg(TransportMode.transit_walk));
        plan.addActivity(factory.createActivityFromCoord(PtConstants.TRANSIT_ACTIVITY_TYPE,
                new Coord((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2)));
        plan.addLeg(factory.createLeg(TransportMode.pt));
        plan.addActivity(factory.createActivityFromCoord("work", to));
        return plan;
    }

    private static void write(Path file, Person... persons) {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        for (Person person : persons) scenario.getPopulation().addPerson(person);
        new PopulationWriter(scenario.getPopulation()).write(file.toString());
    }
}
