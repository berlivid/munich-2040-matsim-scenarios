package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

class MunichTripBoundaryFilterTest {
    private static MunichMunicipalBoundary boundary;
    private static MunichTripBoundaryFilter filter;
    private static Coord inside;
    private static Coord outside;
    private static Coord onBoundary;
    private static PopulationFactory factory;

    @BeforeAll
    static void loadBoundaryOnce() throws Exception {
        boundary = MunichMunicipalBoundary.loadDefault();
        filter = new MunichTripBoundaryFilter(boundary);
        var interior = boundary.geometry().getInteriorPoint().getCoordinate();
        inside = new Coord(interior.x, interior.y);
        var envelope = boundary.envelope();
        outside = new Coord(envelope.getMinX() - 10_000, envelope.getMinY() - 10_000);
        var edge = boundary.geometry().getBoundary().getCoordinate();
        onBoundary = new Coord(edge.x, edge.y);
        factory = PopulationUtils.getFactory();
    }

    @Test
    void pointSafelyInsideIsCovered() {
        assertTrue(boundary.covers(inside));
    }

    @Test
    void pointSafelyOutsideIsNotCovered() {
        assertFalse(boundary.covers(outside));
    }

    @Test
    void pointExactlyOnBoundaryIsCovered() {
        assertEquals(0.0, boundary.distanceToBoundaryMetres(onBoundary), 1e-9);
        assertTrue(boundary.covers(onBoundary));
    }

    @Test
    void bothEndpointsInside() {
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE,
                filter.classify(inside, inside));
    }

    @Test
    void onlyOriginInside() {
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.ORIGIN_ONLY,
                filter.classify(inside, outside));
    }

    @Test
    void onlyDestinationInside() {
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.DESTINATION_ONLY,
                filter.classify(outside, inside));
    }

    @Test
    void bothEndpointsOutside() {
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.BOTH_OUTSIDE,
                filter.classify(outside, outside));
    }

    @Test
    void missingCoordinateIsInvalid() {
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.INVALID_OR_MISSING_COORDINATE,
                filter.classify(inside, null));
    }

    @Test
    void stageActivityDoesNotSplitMainTrip() {
        Plan plan = stagePlan();
        var trips = filter.classify(plan);
        assertEquals(1, trips.size());
        assertEquals("pt", trips.getFirst().inputMainMode());
        assertEquals(MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE,
                trips.getFirst().category());
    }

    @Test
    void classificationDoesNotChangeInputPlan() {
        Plan plan = stagePlan();
        List<String> before = signature(plan);
        filter.classify(plan);
        assertEquals(before, signature(plan));
    }

    @Test
    void repeatedClassificationIsDeterministic() {
        Plan plan = stagePlan();
        assertEquals(filter.classify(plan), filter.classify(plan));
    }

    private static Plan stagePlan() {
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", inside));
        plan.addLeg(factory.createLeg("walk"));
        plan.addActivity(factory.createActivityFromCoord(
                TripStructureUtils.createStageActivityType("pt"), inside));
        plan.addLeg(factory.createLeg("pt"));
        plan.addActivity(factory.createActivityFromCoord("work", inside));
        return plan;
    }

    private static List<String> signature(Plan plan) {
        return plan.getPlanElements().stream().map(MunichTripBoundaryFilterTest::elementSignature).toList();
    }

    private static String elementSignature(PlanElement element) {
        if (element instanceof Activity activity) {
            return "A|" + activity.getType() + "|" + activity.getCoord() + "|" + activity.getLinkId();
        }
        var leg = (org.matsim.api.core.v01.population.Leg) element;
        return "L|" + leg.getMode() + "|" + leg.getRoute();
    }
}
