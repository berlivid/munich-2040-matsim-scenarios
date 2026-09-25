package org.matsim.project.prepare;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

/**
 * Streams final-iteration vehicle events once. Vehicle distance follows the
 * MATSim 2025.0 relative-position convention for first and last links.
 */
final class Production2040VehicleMetrics implements LinkEnterEventHandler,
        VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler,
        TransitDriverStartsEventHandler, PersonEntersVehicleEventHandler,
        PersonLeavesVehicleEventHandler, ActivityEndEventHandler,
        VehicleArrivesAtFacilityEventHandler, PersonStuckEventHandler {
    private final Network network;
    private final TransitSchedule schedule;
    private final Map<Id<Person>, java.util.List<Boolean>> relevantTrips;
    private final Vehicles transitVehicles;
    private final MovementObserver movementObserver;
    private final Map<Id<Person>, Integer> currentTripIndex = new HashMap<>();
    private final Map<Id<Vehicle>, VehicleState> vehicles = new HashMap<>();
    private final Map<String, MutablePtMetric> pt = new TreeMap<>();
    private long missingLinks;
    private long missingTransitReferences;
    private long unmatchedAlightings;

    Production2040VehicleMetrics(Network network, TransitSchedule schedule,
            Map<Id<Person>, java.util.List<Boolean>> relevantTrips) {
        this(network, schedule, null, relevantTrips);
    }

    Production2040VehicleMetrics(Network network, TransitSchedule schedule,
            Vehicles transitVehicles,
            Map<Id<Person>, java.util.List<Boolean>> relevantTrips) {
        this(network, schedule, transitVehicles, relevantTrips, MovementObserver.NONE);
    }

    Production2040VehicleMetrics(Network network, TransitSchedule schedule,
            Vehicles transitVehicles, Map<Id<Person>, java.util.List<Boolean>> relevantTrips,
            MovementObserver movementObserver) {
        this.network = java.util.Objects.requireNonNull(network);
        this.schedule = java.util.Objects.requireNonNull(schedule);
        this.transitVehicles = transitVehicles;
        this.relevantTrips = Map.copyOf(relevantTrips);
        this.movementObserver = java.util.Objects.requireNonNull(movementObserver);
    }

    @Override
    public void reset(int iteration) {
        vehicles.clear();
        pt.clear();
        missingLinks = 0;
        missingTransitReferences = 0;
        unmatchedAlightings = 0;
        currentTripIndex.clear();
        movementObserver.reset();
    }

    @Override
    public void handleEvent(ActivityEndEvent event) {
        if (StageActivityTypeIdentifier.isStageActivity(event.getActType())) return;
        currentTripIndex.merge(event.getPersonId(), 0, (oldValue, ignored) -> oldValue + 1);
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        TransitStopFacility facility = schedule.getFacilities().get(event.getFacilityId());
        if (facility == null) {
            missingTransitReferences++;
        } else {
            state(event.getVehicleId()).currentFacility = facility;
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.transit = true;
        state.transitDriverStarted = true;
        state.driver = event.getDriverId();
        var line = schedule.getTransitLines().get(event.getTransitLineId());
        TransitRoute route = line == null ? null : line.getRoutes().get(event.getTransitRouteId());
        var departure = route == null ? null : route.getDepartures().get(event.getDepartureId());
        boolean validVehicle = transitVehicles == null
                || transitVehicles.getVehicles().containsKey(event.getVehicleId());
        boolean validDeparture = departure != null
                && event.getVehicleId().equals(departure.getVehicleId());
        if (route == null || !validDeparture || !validVehicle
                || route.getTransportMode() == null
                || route.getTransportMode().isBlank()) {
            state.ptMode = "unknown";
            missingTransitReferences++;
        } else {
            state.ptMode = Production2040AnalysisSpec.normalizePtRouteMode(
                    route.getTransportMode());
        }
        state.route = route;
        metric(state.ptMode);
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.networkMode = Production2040AnalysisSpec.normalizeMainMode(event.getNetworkMode());
        state.currentLink = event.getLinkId();
        state.trafficPerson = event.getPersonId();
        movementObserver.trafficEnter(event.getVehicleId(), event.getPersonId(),
                state.networkMode, currentTripIndex.get(event.getPersonId()), state.transit);
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) {
            addMovement(event.getVehicleId(), state, event.getLinkId(),
                    length * (1.0 - boundedPosition(
                    event.getRelativePositionOnLink())));
        }
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        VehicleState state = state(event.getVehicleId());
        state.currentLink = event.getLinkId();
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) {
            addMovement(event.getVehicleId(), state, event.getLinkId(), length);
        }
    }

    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        VehicleState state = state(event.getVehicleId());
        double length = linkLength(event.getLinkId());
        if (Double.isFinite(length)) {
            addMovement(event.getVehicleId(), state, event.getLinkId(),
                    -length * (1.0 - boundedPosition(
                    event.getRelativePositionOnLink())));
        }
        movementObserver.trafficLeave(event.getVehicleId(), event.getPersonId());
        state.currentLink = null;
        state.trafficPerson = null;
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        movementObserver.personStuck(event.getPersonId(), currentTripIndex.get(event.getPersonId()),
                Production2040AnalysisSpec.normalizeMainMode(event.getLegMode()));
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        VehicleState state = state(event.getVehicleId());
        if (!state.transit || event.getPersonId().equals(state.driver)) return;
        if (state.passengers.add(event.getPersonId())) {
            MutablePtMetric metric = metric(state.ptMode);
            metric.boardings++;
            boolean relevant = isRelevantTrip(event.getPersonId());
            if (relevant) metric.relevantBoardings++;
            state.boardings.put(event.getPersonId(), new Boarding(state.currentFacility,
                    relevant));
            if (state.currentFacility == null) missingTransitReferences++;
        }
    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent event) {
        VehicleState state = state(event.getVehicleId());
        if (!state.transit || event.getPersonId().equals(state.driver)) return;
        Boarding boarding = state.boardings.remove(event.getPersonId());
        if (!state.passengers.remove(event.getPersonId()) || boarding == null) {
            unmatchedAlightings++;
            return;
        }
        if (boarding.accessFacility() == null || state.currentFacility == null
                || state.route == null) {
            missingTransitReferences++;
            return;
        }
        double metres;
        try {
            metres = RouteUtils.calcDistance(state.route, boarding.accessFacility(),
                    state.currentFacility, network);
        } catch (RuntimeException error) {
            missingTransitReferences++;
            return;
        }
        if (!Double.isFinite(metres) || metres < 0) {
            missingTransitReferences++;
            return;
        }
        MutablePtMetric metric = metric(state.ptMode);
        metric.completedBoardings++;
        if (boarding.relevant()) metric.relevantCompletedBoardings++;
        metric.passengerMetres += metres;
        if (boarding.relevant()) metric.relevantPassengerMetres += metres;
    }

    private void addMovement(Id<Vehicle> vehicleId, VehicleState state, Id<Link> linkId,
            double metres) {
        if (!Double.isFinite(metres)) return;
        state.distanceMetres += metres;
        movementObserver.movement(vehicleId, state.trafficPerson, linkId, metres,
                state.transit, state.ptMode);
        if (!state.transit) return;
        /*
         * A passenger receives exactly the movement that occurs while the
         * person is on board. In particular, first-/last-link corrections are
         * applied to the passenger set that exists at that event, rather than
         * to a later boarding or alighting count.
         */
        Set<Id<Person>> passengersForMovement = passengersForMovement(state, linkId, metres);
        for (Id<Person> passenger : passengersForMovement) {
            movementObserver.passengerMovement(vehicleId, passenger, linkId, metres,
                    state.ptMode);
        }
        MutablePtMetric metric = metric(state.ptMode);
        metric.vehicleMetres += metres;
    }

    /**
     * A VehicleLeavesTraffic correction removes part of the most recent link
     * movement. It must therefore be applied to the passenger set that was on
     * board for that original movement, not to whoever happens to be on board
     * when the correction event is emitted.
     */
    private static Set<Id<Person>> passengersForMovement(VehicleState state, Id<Link> linkId,
            double metres) {
        if (metres >= 0) {
            Set<Id<Person>> snapshot = state.passengers.isEmpty() ? Set.of()
                    : Collections.unmodifiableSet(new HashSet<>(state.passengers));
            state.lastPtMovementLink = linkId;
            state.lastPtMovementPassengers = snapshot;
            return snapshot;
        }
        Production2040AnalysisSpec.require(state.lastPtMovementLink != null
                        && state.lastPtMovementLink.equals(linkId),
                "PT last-link correction has no matching prior movement on " + linkId);
        return state.lastPtMovementPassengers;
    }

    private boolean isRelevantTrip(Id<Person> person) {
        java.util.List<Boolean> trips = relevantTrips.get(person);
        Integer index = currentTripIndex.get(person);
        return trips != null && index != null && index >= 0 && index < trips.size()
                && trips.get(index);
    }

    private double linkLength(Id<Link> linkId) {
        Link link = network.getLinks().get(linkId);
        if (link == null || !Double.isFinite(link.getLength()) || link.getLength() < 0) {
            missingLinks++;
            return Double.NaN;
        }
        return link.getLength();
    }

    private static double boundedPosition(double value) {
        Production2040AnalysisSpec.require(Double.isFinite(value)
                        && value >= 0.0 && value <= 1.0,
                "Vehicle traffic event has invalid relative link position " + value);
        return value;
    }

    private VehicleState state(Id<Vehicle> vehicle) {
        return vehicles.computeIfAbsent(vehicle, id -> {
            VehicleState state = new VehicleState();
            if (transitVehicles != null && transitVehicles.getVehicles().containsKey(id)) {
                state.transit = true;
            }
            return state;
        });
    }

    private MutablePtMetric metric(String mode) {
        return pt.computeIfAbsent(mode == null ? "unknown" : mode,
                ignored -> new MutablePtMetric());
    }

    Result result() {
        double carMetres = 0;
        long carVehicles = 0;
        long unassignedVehicles = 0;
        long openBoardings = 0;
        long unresolvedTransit = 0;
        for (VehicleState state : vehicles.values()) {
            Production2040AnalysisSpec.require(state.distanceMetres >= -1e-6,
                    "Negative vehicle distance after first/last-link correction");
            if (state.transit) {
                if (!state.transitDriverStarted && Math.abs(state.distanceMetres) > 1e-6) {
                    unresolvedTransit++;
                }
                openBoardings += state.boardings.size();
                continue;
            }
            if ("car".equals(state.networkMode)) {
                carMetres += Math.max(0, state.distanceMetres);
                carVehicles++;
            } else if (Math.abs(state.distanceMetres) > 1e-6) unassignedVehicles++;
        }
        Map<String, PtMetric> frozen = new TreeMap<>();
        pt.forEach((mode, value) -> frozen.put(mode, value.freeze()));
        return new Result(carMetres, carVehicles, unassignedVehicles, missingLinks,
                missingTransitReferences + unresolvedTransit, unmatchedAlightings, openBoardings,
                Map.copyOf(frozen));
    }

    record PtMetric(double vehicleMetres, double passengerMetres,
                    double relevantPassengerMetres, long boardings,
                    long relevantBoardings, long completedBoardings,
                    long relevantCompletedBoardings) { }

    record Result(double carMetres, long carVehicles, long unassignedVehicles,
                  long missingLinks, long missingTransitReferences,
                  long unmatchedAlightings, long openBoardings,
                  Map<String, PtMetric> ptByRouteMode) { }

    private static final class VehicleState {
        private boolean transit;
        private boolean transitDriverStarted;
        private String networkMode = "unknown";
        private String ptMode = "unknown";
        private Id<Person> driver;
        private Id<Person> trafficPerson;
        private TransitRoute route;
        private TransitStopFacility currentFacility;
        private Id<Link> currentLink;
        private Id<Link> lastPtMovementLink;
        private Set<Id<Person>> lastPtMovementPassengers = Set.of();
        private double distanceMetres;
        private final Set<Id<Person>> passengers = new HashSet<>();
        private final Map<Id<Person>, Boarding> boardings = new HashMap<>();
    }

    interface MovementObserver {
        MovementObserver NONE = new MovementObserver() { };

        default void reset() { }
        default void trafficEnter(Id<Vehicle> vehicle, Id<Person> person, String networkMode,
                                  Integer mainTripIndex, boolean transit) { }
        default void movement(Id<Vehicle> vehicle, Id<Person> person, Id<Link> link,
                              double metres, boolean transit, String ptMode) { }
        /**
         * Called only for a non-driver passenger who is on board during one
         * transit-vehicle movement. The distance uses the same event and
         * first-/last-link convention as {@link #movement}.
         */
        default void passengerMovement(Id<Vehicle> vehicle, Id<Person> passenger,
                                       Id<Link> link, double metres, String ptMode) { }
        default void trafficLeave(Id<Vehicle> vehicle, Id<Person> person) { }
        default void personStuck(Id<Person> person, Integer mainTripIndex) { }
        default void personStuck(Id<Person> person, Integer mainTripIndex, String legMode) {
            personStuck(person, mainTripIndex);
        }
    }

    private record Boarding(TransitStopFacility accessFacility, boolean relevant) { }

    private static final class MutablePtMetric {
        private double vehicleMetres;
        private double passengerMetres;
        private double relevantPassengerMetres;
        private long boardings;
        private long relevantBoardings;
        private long completedBoardings;
        private long relevantCompletedBoardings;

        private PtMetric freeze() {
            return new PtMetric(vehicleMetres, passengerMetres,
                    relevantPassengerMetres, boardings, relevantBoardings,
                    completedBoardings, relevantCompletedBoardings);
        }
    }
}
