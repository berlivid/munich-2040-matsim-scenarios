package org.matsim.project.prepare;

import java.util.List;
import java.util.Locale;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/** Classifies main-activity trips without changing the supplied MATSim plan. */
public final class MunichTripBoundaryFilter {
    private static final DefaultAnalysisMainModeIdentifier MAIN_MODE_IDENTIFIER =
            new DefaultAnalysisMainModeIdentifier();
    private final MunichMunicipalBoundary boundary;

    public MunichTripBoundaryFilter(MunichMunicipalBoundary boundary) {
        this.boundary = java.util.Objects.requireNonNull(boundary);
    }

    /**
     * Uses MATSim's stage-activity identifier, so routed interaction activities
     * do not split a journey into artificial analysis trips.
     */
    public List<ClassifiedTrip> classify(Plan plan) {
        if (plan == null) return List.of();
        return TripStructureUtils.getTrips(plan, StageActivityTypeIdentifier::isStageActivity)
                .stream().map(trip -> new ClassifiedTrip(
                        trip.getOriginActivity(), trip.getDestinationActivity(),
                        classify(trip.getOriginActivity(), trip.getDestinationActivity()),
                        identifyInputMainMode(trip)))
                .toList();
    }

    public SpatialCategory classify(Activity origin, Activity destination) {
        return classify(origin == null ? null : origin.getCoord(),
                destination == null ? null : destination.getCoord());
    }

    public SpatialCategory classify(Coord origin, Coord destination) {
        if (!boundary.isValidCoordinate(origin) || !boundary.isValidCoordinate(destination)) {
            return SpatialCategory.INVALID_OR_MISSING_COORDINATE;
        }
        boolean originInside = boundary.covers(origin);
        boolean destinationInside = boundary.covers(destination);
        if (originInside && destinationInside) return SpatialCategory.BOTH_INSIDE;
        if (originInside) return SpatialCategory.ORIGIN_ONLY;
        if (destinationInside) return SpatialCategory.DESTINATION_ONLY;
        return SpatialCategory.BOTH_OUTSIDE;
    }

    static String identifyInputMainMode(TripStructureUtils.Trip trip) {
        try {
            String mode = MAIN_MODE_IDENTIFIER.identifyMainMode(trip.getTripElements());
            return mode == null || mode.isBlank() ? "unknown" : mode.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    public enum SpatialCategory {
        BOTH_INSIDE,
        ORIGIN_ONLY,
        DESTINATION_ONLY,
        BOTH_OUTSIDE,
        INVALID_OR_MISSING_COORDINATE
    }

    public record ClassifiedTrip(Activity origin, Activity destination,
                                 SpatialCategory category, String inputMainMode) { }
}
