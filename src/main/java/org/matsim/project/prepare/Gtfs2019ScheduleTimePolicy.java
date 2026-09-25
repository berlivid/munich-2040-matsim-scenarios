package org.matsim.project.prepare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

/** Fail-closed temporal policy for the synthetic-2019 calibration schedule. */
final class Gtfs2019ScheduleTimePolicy {
    static final double SECONDS_PER_HOUR = 3_600;
    static final double FIRST_FOLLOWING_SERVICE_DAY = 24 * SECONDS_PER_HOUR;
    static final double LONG_DURATION_REVIEW_THRESHOLD = 8 * SECONDS_PER_HOUR;
    static final double MAX_ACCEPTED_SERVICE_HORIZON = 48 * SECONDS_PER_HOUR;

    private Gtfs2019ScheduleTimePolicy() { }

    static Audit audit(TransitSchedule schedule) {
        List<RouteTiming> timings = new ArrayList<>();
        double latestDeparture = Double.NEGATIVE_INFINITY;
        double largestArrivalOffset = Double.NEGATIVE_INFINITY;
        double largestDepartureOffset = Double.NEGATIVE_INFINITY;
        double latestVehicleArrival = Double.NEGATIVE_INFINITY;
        long vehicleArrivalsAfter24h = 0;
        long vehicleArrivalsAfter30h = 0;

        for (var line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                RouteOffsets offsets = offsets(route);
                largestArrivalOffset = Math.max(largestArrivalOffset, offsets.largestArrival());
                largestDepartureOffset = Math.max(largestDepartureOffset, offsets.largestDeparture());
                double routeLatestDeparture = Double.NEGATIVE_INFINITY;
                double routeLatestArrival = Double.NEGATIVE_INFINITY;
                String latestDepartureId = "";
                String latestVehicleId = "";
                for (var departure : route.getDepartures().values()) {
                    double departureTime = departure.getDepartureTime();
                    double vehicleArrival = departureTime + offsets.finalArrival();
                    validateTiming(line.getId() + "/" + route.getId() + "/"
                            + departure.getId(), departureTime, offsets.duration());
                    require(Double.isFinite(vehicleArrival),
                            "Transit vehicle arrival is not finite: " + line.getId() + "/"
                                    + route.getId() + "/" + departure.getId());
                    if (vehicleArrival > FIRST_FOLLOWING_SERVICE_DAY) vehicleArrivalsAfter24h++;
                    if (vehicleArrival > 30 * SECONDS_PER_HOUR) vehicleArrivalsAfter30h++;
                    latestDeparture = Math.max(latestDeparture, departureTime);
                    latestVehicleArrival = Math.max(latestVehicleArrival, vehicleArrival);
                    if (vehicleArrival > routeLatestArrival) {
                        routeLatestArrival = vehicleArrival;
                        routeLatestDeparture = departureTime;
                        latestDepartureId = departure.getId().toString();
                        latestVehicleId = departure.getVehicleId() == null
                                ? "" : departure.getVehicleId().toString();
                    }
                }
                require(routeLatestArrival > Double.NEGATIVE_INFINITY,
                        "Transit route has no departures: " + line.getId() + "/" + route.getId());
                timings.add(new RouteTiming(line.getId().toString(), route.getId().toString(),
                        route.getTransportMode(), offsets.duration(), routeLatestDeparture,
                        routeLatestArrival, latestDepartureId, latestVehicleId));
            }
        }
        require(latestVehicleArrival > 0, "Schedule contains no finite vehicle arrival");
        double qsimEndTime = nextWholeHour(latestVehicleArrival);
        require(qsimEndTime <= MAX_ACCEPTED_SERVICE_HORIZON,
                "Derived QSim end time exceeds 48 hours: " + formatTime(qsimEndTime));
        List<RouteTiming> review = timings.stream()
                .filter(t -> t.duration() > LONG_DURATION_REVIEW_THRESHOLD)
                .sorted(Comparator.comparingDouble(RouteTiming::duration).reversed()
                        .thenComparing(RouteTiming::lineId).thenComparing(RouteTiming::routeId))
                .toList();
        return new Audit(latestDeparture, largestArrivalOffset, largestDepartureOffset,
                latestVehicleArrival, qsimEndTime, vehicleArrivalsAfter24h,
                vehicleArrivalsAfter30h, List.copyOf(review));
    }

    static double nextWholeHour(double latestVehicleArrival) {
        require(Double.isFinite(latestVehicleArrival) && latestVehicleArrival >= 0,
                "Latest vehicle arrival is not finite");
        return (Math.floor(latestVehicleArrival / SECONDS_PER_HOUR) + 1) * SECONDS_PER_HOUR;
    }

    static void validateConfiguredEndTime(double endTime, Audit audit) {
        require(Double.isFinite(endTime), "qsim.endTime must be explicitly defined and finite");
        require(endTime == audit.qsimEndTime(), "qsim.endTime=" + formatTime(endTime)
                + " differs from the derived service horizon " + formatTime(audit.qsimEndTime()));
        require(audit.latestVehicleArrival() < endTime,
                "A transit vehicle can remain active at or beyond qsim.endTime");
        require(endTime <= MAX_ACCEPTED_SERVICE_HORIZON,
                "qsim.endTime exceeds the accepted 48-hour service horizon");
    }

    static void validateTiming(String id, double departure, double duration) {
        require(Double.isFinite(duration) && duration >= 0
                        && duration < MAX_ACCEPTED_SERVICE_HORIZON,
                "Excessive route duration for " + id + ": " + formatTime(duration));
        require(Double.isFinite(departure) && departure >= 0
                        && departure + duration < MAX_ACCEPTED_SERVICE_HORIZON,
                "Service exceeds the accepted horizon for " + id + ": "
                        + formatTime(departure + duration));
    }

    private static RouteOffsets offsets(TransitRoute route) {
        double firstEvent = Double.POSITIVE_INFINITY;
        double largestArrival = Double.NEGATIVE_INFINITY;
        double largestDeparture = Double.NEGATIVE_INFINITY;
        for (TransitRouteStop stop : route.getStops()) {
            double arrival = stop.getArrivalOffset().orElse(Double.NaN);
            double departure = stop.getDepartureOffset().orElse(Double.NaN);
            if (Double.isFinite(arrival)) {
                firstEvent = Math.min(firstEvent, arrival);
                largestArrival = Math.max(largestArrival, arrival);
            }
            if (Double.isFinite(departure)) {
                firstEvent = Math.min(firstEvent, departure);
                largestDeparture = Math.max(largestDeparture, departure);
            }
        }
        require(Double.isFinite(firstEvent), "Transit route has no finite stop offsets: " + route.getId());
        if (!Double.isFinite(largestArrival)) largestArrival = largestDeparture;
        if (!Double.isFinite(largestDeparture)) largestDeparture = largestArrival;
        double finalArrival = route.getStops().getLast().getArrivalOffset().orElse(
                route.getStops().getLast().getDepartureOffset().orElse(Double.NaN));
        require(Double.isFinite(finalArrival) && finalArrival >= firstEvent,
                "Transit route has no valid final arrival offset: " + route.getId());
        return new RouteOffsets(largestArrival, largestDeparture, finalArrival,
                Math.max(largestArrival, largestDeparture) - firstEvent);
    }

    static String formatTime(double seconds) {
        long rounded = Math.round(seconds);
        return "%d:%02d:%02d".formatted(rounded / 3_600,
                rounded % 3_600 / 60, rounded % 60);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record RouteOffsets(double largestArrival, double largestDeparture,
                                double finalArrival, double duration) { }

    record RouteTiming(String lineId, String routeId, String mode, double duration,
                       double latestDeparture, double latestArrival,
                       String latestDepartureId, String latestVehicleId) { }

    record Audit(double latestDeparture, double largestArrivalOffset,
                 double largestDepartureOffset, double latestVehicleArrival,
                 double qsimEndTime, long vehicleArrivalsAfter24h,
                 long vehicleArrivalsAfter30h, List<RouteTiming> longDurationRoutes) {
        String summary() {
            return "GTFS 2019 TIME AUDIT: latestDeparture=" + formatTime(latestDeparture)
                    + ", largestArrivalOffset=" + formatTime(largestArrivalOffset)
                    + ", largestDepartureOffset=" + formatTime(largestDepartureOffset)
                    + ", latestVehicleArrival=" + formatTime(latestVehicleArrival)
                    + ", derivedQSimEnd=" + formatTime(qsimEndTime)
                    + ", vehicleArrivalsAfter24h=" + vehicleArrivalsAfter24h
                    + ", vehicleArrivalsAfter30h=" + vehicleArrivalsAfter30h
                    + ", routesOver8h=" + longDurationRoutes.size();
        }
    }
}
