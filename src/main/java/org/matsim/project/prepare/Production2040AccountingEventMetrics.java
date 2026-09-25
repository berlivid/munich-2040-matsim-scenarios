package org.matsim.project.prepare;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

/** Attributes the shared event-distance stream to demand scopes and territorial PT service. */
final class Production2040AccountingEventMetrics
        implements Production2040VehicleMetrics.MovementObserver {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double FRACTION_EPSILON = 1e-12;
    private final Network network;
    private final MunichMunicipalBoundary boundary;
    private final Production2040AccountingScopes.Index index;
    private final Map<Id<Link>, LinkClip> clips = new HashMap<>();
    private final Map<Id<Vehicle>, CarSegment> openCarSegments = new HashMap<>();
    private final Set<Id<Vehicle>> ignoredTrafficVehicles = new HashSet<>();
    private final Map<MunichTripBoundaryFilter.SpatialCategory, Double> endpointCarMetres =
            new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
    private final Map<Production2040AccountingScopes.Scope, Double> scopeCarMetres =
            new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<Production2040AccountingScopes.Scope, Set<Id<Vehicle>>> scopeCarVehicles =
            new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<Production2040AccountingScopes.Scope, Set<Production2040AccountingScopes.TripKey>>
            scopeCarTrips = new EnumMap<>(Production2040AccountingScopes.Scope.class);
    private final Map<String, MutablePtService> pt = new TreeMap<>();
    private final Map<String, MutablePtPassenger> ptPassengers = new TreeMap<>();
    private final Set<Production2040AccountingScopes.TripKey> stuckTrips = new HashSet<>();
    private double pointAnchoredTerritorialServiceMetres;
    private double zeroModelLengthTerritorialServiceMetres;
    private long unmatchedPersons;
    private long unmatchedTrips;
    private long repeatedVehicleEnters;
    private long unmatchedVehicleLeaves;
    private long unattributedCarMovementEvents;

    Production2040AccountingEventMetrics(Network network, MunichMunicipalBoundary boundary,
            Production2040AccountingScopes.Index index) {
        this.network = java.util.Objects.requireNonNull(network);
        this.boundary = java.util.Objects.requireNonNull(boundary);
        this.index = java.util.Objects.requireNonNull(index);
        reset();
    }

    @Override
    public void reset() {
        clips.clear();
        openCarSegments.clear();
        ignoredTrafficVehicles.clear();
        endpointCarMetres.clear();
        scopeCarMetres.clear();
        scopeCarVehicles.clear();
        scopeCarTrips.clear();
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            endpointCarMetres.put(category, 0.0);
        }
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            scopeCarMetres.put(scope, 0.0);
            scopeCarVehicles.put(scope, new HashSet<>());
            scopeCarTrips.put(scope, new HashSet<>());
        }
        pt.clear();
        ptPassengers.clear();
        stuckTrips.clear();
        pointAnchoredTerritorialServiceMetres = 0;
        zeroModelLengthTerritorialServiceMetres = 0;
        unmatchedPersons = 0;
        unmatchedTrips = 0;
        repeatedVehicleEnters = 0;
        unmatchedVehicleLeaves = 0;
        unattributedCarMovementEvents = 0;
    }

    @Override
    public void trafficEnter(Id<Vehicle> vehicle, Id<Person> person, String networkMode,
            Integer mainTripIndex, boolean transit) {
        if (transit || !"car".equals(networkMode)) {
            ignoredTrafficVehicles.add(vehicle);
            return;
        }
        if (openCarSegments.containsKey(vehicle)) repeatedVehicleEnters++;
        Production2040AccountingScopes.PersonScope personScope = index.persons().get(person);
        if (personScope == null) unmatchedPersons++;
        Production2040AccountingScopes.TripScope trip = index.trip(person, mainTripIndex);
        if (trip == null) unmatchedTrips++;
        openCarSegments.put(vehicle, new CarSegment(person, trip));
    }

    @Override
    public void movement(Id<Vehicle> vehicle, Id<Person> person, Id<Link> linkId,
            double metres, boolean transit, String ptMode) {
        if (transit) {
            Link link = network.getLinks().get(linkId);
            Production2040AnalysisSpec.require(link != null,
                    "Accounting movement refers to missing link " + linkId);
            LinkClip clip = clips.computeIfAbsent(linkId, ignored -> clip(link, boundary));
            MutablePtService metric = pt.computeIfAbsent(
                    ptMode == null ? "unknown" : ptMode, ignored -> new MutablePtService());
            if (clip.modelLinkMetres() == 0) {
                Production2040AnalysisSpec.require(Math.abs(metres) <= FRACTION_EPSILON,
                        "Zero-model-length PT link has non-zero event distance " + linkId);
            }
            double territorialMetres = clip.modelLinkMetres() == 0 ? 0
                    : metres * clip.insideFraction();
            metric.uncutMetres += metres;
            metric.territorialMetres += territorialMetres;
            if (clip.method() == LinkClipMethod.POINT_ANCHORED_PSEUDOLINK
                    && clip.modelLinkMetres() > 0) {
                pointAnchoredTerritorialServiceMetres += territorialMetres;
            }
            if (clip.modelLinkMetres() == 0) {
                zeroModelLengthTerritorialServiceMetres += territorialMetres;
            }
            if (clip.category() == LinkLocation.CROSSING && Math.abs(metres) > 0) {
                metric.crossingLinks.add(linkId);
                metric.crossingServiceMetres += metres;
            }
            return;
        }
        CarSegment segment = openCarSegments.get(vehicle);
        if (segment == null) {
            if (!ignoredTrafficVehicles.contains(vehicle) && person != null
                    && Math.abs(metres) > 0) unattributedCarMovementEvents++;
            return;
        }
        segment.metres += metres;
    }

    /**
     * Counts only passengers who are actually on board for this event movement.
     * No selected-plan, resident, or endpoint filter is applied here: this is
     * territorial demand from all simulated PT passengers.
     */
    @Override
    public void passengerMovement(Id<Vehicle> vehicle, Id<Person> passenger, Id<Link> linkId,
            double metres, String ptMode) {
        Production2040AnalysisSpec.require(passenger != null,
                "PT passenger movement has no person ID");
        Link link = network.getLinks().get(linkId);
        Production2040AnalysisSpec.require(link != null,
                "PT passenger movement refers to missing link " + linkId);
        LinkClip clip = clips.computeIfAbsent(linkId, ignored -> clip(link, boundary));
        if (clip.modelLinkMetres() == 0) {
            Production2040AnalysisSpec.require(Math.abs(metres) <= FRACTION_EPSILON,
                    "Zero-model-length PT link has non-zero passenger distance " + linkId);
        }
        MutablePtPassenger metric = ptPassengers.computeIfAbsent(
                ptMode == null ? "unknown" : ptMode, ignored -> new MutablePtPassenger());
        double territorialMetres = clip.modelLinkMetres() == 0 ? 0
                : metres * clip.insideFraction();
        metric.uncutMetres += metres;
        metric.territorialMetres += territorialMetres;
        metric.movementEvents++;
        if (clip.category() == LinkLocation.CROSSING && Math.abs(metres) > 0) {
            metric.crossingLinks.add(linkId);
            metric.crossingPassengerMetres += metres;
        }
    }

    @Override
    public void trafficLeave(Id<Vehicle> vehicle, Id<Person> person) {
        boolean ignored = ignoredTrafficVehicles.remove(vehicle);
        CarSegment segment = openCarSegments.remove(vehicle);
        if (segment == null) {
            if (!ignored) unmatchedVehicleLeaves++;
            return;
        }
        if (!segment.person.equals(person)) unmatchedVehicleLeaves++;
        if (segment.trip == null) return;
        Production2040AnalysisSpec.require(segment.metres >= -1e-6,
                "Negative attributed car segment distance for " + segment.trip.key());
        double metres = Math.max(0, segment.metres);
        endpointCarMetres.merge(segment.trip.endpointCategory(), metres, Double::sum);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            if (!segment.trip.included(scope)) continue;
            scopeCarMetres.merge(scope, metres, Double::sum);
            scopeCarVehicles.get(scope).add(vehicle);
            scopeCarTrips.get(scope).add(segment.trip.key());
        }
    }

    @Override
    public void personStuck(Id<Person> person, Integer mainTripIndex) {
        Production2040AccountingScopes.TripScope trip = index.trip(person, mainTripIndex);
        if (trip != null) stuckTrips.add(trip.key());
    }

    Result result() {
        Map<String, PtService> frozenPt = new TreeMap<>();
        pt.forEach((mode, metric) -> frozenPt.put(mode, metric.freeze(clips)));
        Map<String, PtPassenger> frozenPtPassengers = new TreeMap<>();
        ptPassengers.forEach((mode, metric) -> frozenPtPassengers.put(mode, metric.freeze(clips)));
        Set<Id<Link>> allCrossingLinks = new HashSet<>();
        pt.values().forEach(metric -> allCrossingLinks.addAll(metric.crossingLinks));
        double allCrossingModelMetres = allCrossingLinks.stream().map(clips::get)
                .mapToDouble(LinkClip::modelLinkMetres).sum();
        Map<Production2040AccountingScopes.Scope, CarScope> cars =
                new EnumMap<>(Production2040AccountingScopes.Scope.class);
        for (var scope : Production2040AccountingScopes.Scope.values()) {
            long stuck = stuckTrips.stream().map(index.trips()::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(trip -> "car".equals(trip.mainMode()) && trip.included(scope)).count();
            cars.put(scope, new CarScope(scopeCarMetres.get(scope),
                    scopeCarVehicles.get(scope).size(), scopeCarTrips.get(scope).size(), stuck));
        }
        return new Result(Map.copyOf(cars), Map.copyOf(endpointCarMetres), Map.copyOf(frozenPt),
                Map.copyOf(frozenPtPassengers), allCrossingLinks.size(), allCrossingModelMetres,
                pointAnchoredSummary(),
                unmatchedPersons, unmatchedTrips, repeatedVehicleEnters,
                unmatchedVehicleLeaves, unattributedCarMovementEvents,
                openCarSegments.size());
    }

    static LinkClip clip(Link link, MunichMunicipalBoundary boundary) {
        double modelLength = link.getLength();
        Production2040AnalysisSpec.require(Double.isFinite(modelLength) && modelLength >= 0,
                "PT link has invalid model length " + link.getId());
        Coordinate from = coordinate(link, true);
        Coordinate to = coordinate(link, false);
        if (from.equals2D(to)) {
            var anchor = GEOMETRY_FACTORY.createPoint(from);
            boolean strictlyInside = boundary.geometry().contains(anchor);
            boolean covered = boundary.geometry().covers(anchor);
            Production2040AnalysisSpec.require(strictlyInside || !covered,
                    "Boundary-ambiguous point-anchored PT pseudolink " + link.getId());
            return new LinkClip(strictlyInside ? 1.0 : 0.0,
                    strictlyInside ? LinkLocation.INSIDE : LinkLocation.OUTSIDE, modelLength,
                    LinkClipMethod.POINT_ANCHORED_PSEUDOLINK);
        }
        double fraction = geometricInsideFraction(from, to, boundary);
        LinkLocation category = fraction <= FRACTION_EPSILON ? LinkLocation.OUTSIDE
                : fraction >= 1.0 - FRACTION_EPSILON ? LinkLocation.INSIDE
                : LinkLocation.CROSSING;
        return new LinkClip(fraction, category, modelLength,
                LinkClipMethod.GEOMETRIC_LINE_CLIP);
    }

    /** Exact inside fraction for a finite, non-zero straight segment in the MATSim CRS. */
    static double geometricInsideFraction(Coordinate from, Coordinate to,
            MunichMunicipalBoundary boundary) {
        Production2040AnalysisSpec.require(from != null && to != null
                        && Double.isFinite(from.x) && Double.isFinite(from.y)
                        && Double.isFinite(to.x) && Double.isFinite(to.y),
                "Territorial segment has non-finite endpoint coordinates");
        Production2040AnalysisSpec.require(!from.equals2D(to),
                "Territorial segment has coincident endpoint coordinates");
        var line = GEOMETRY_FACTORY.createLineString(new Coordinate[]{from, to});
        double geometricLength = line.getLength();
        Production2040AnalysisSpec.require(Double.isFinite(geometricLength)
                        && geometricLength > 0,
                "Territorial segment has invalid straight-line geometry");
        double insideLength = line.intersection(boundary.geometry()).getLength();
        double fraction = Math.max(0.0, Math.min(1.0, insideLength / geometricLength));
        Production2040AnalysisSpec.require(Double.isFinite(fraction)
                        && fraction >= 0.0 && fraction <= 1.0,
                "Territorial segment clip fraction is outside [0,1]");
        return fraction;
    }

    static java.util.List<ZeroGeometryLink> inventoryZeroGeometryLinks(Network network) {
        java.util.List<ZeroGeometryLink> result = new java.util.ArrayList<>();
        for (Link link : network.getLinks().values()) {
            Coordinate from = coordinate(link, true);
            Coordinate to = coordinate(link, false);
            if (!from.equals2D(to)) continue;
            double modelLength = link.getLength();
            Production2040AnalysisSpec.require(Double.isFinite(modelLength) && modelLength >= 0,
                    "PT link has invalid model length " + link.getId());
            result.add(new ZeroGeometryLink(link.getId().toString(), modelLength,
                    Set.copyOf(link.getAllowedModes()), from.x, from.y,
                    link.getFromNode().getId().toString(), link.getToNode().getId().toString()));
        }
        result.sort(java.util.Comparator.comparing(ZeroGeometryLink::linkId));
        return java.util.List.copyOf(result);
    }

    private static Coordinate coordinate(Link link, boolean fromNode) {
        Coord coordinate = (fromNode ? link.getFromNode() : link.getToNode()).getCoord();
        Production2040AnalysisSpec.require(coordinate != null
                        && Double.isFinite(coordinate.getX())
                        && Double.isFinite(coordinate.getY()),
                "PT link has non-finite " + (fromNode ? "from" : "to")
                        + " coordinate " + link.getId());
        return new Coordinate(coordinate.getX(), coordinate.getY());
    }

    private PtPseudolinkSummary pointAnchoredSummary() {
        long used = 0;
        double modelMetres = 0;
        long inside = 0;
        double insideModelMetres = 0;
        long outside = 0;
        double outsideModelMetres = 0;
        long zeroModelLength = 0;
        for (LinkClip clip : clips.values()) {
            if (clip.modelLinkMetres() == 0) zeroModelLength++;
            if (clip.method() != LinkClipMethod.POINT_ANCHORED_PSEUDOLINK
                    || clip.modelLinkMetres() == 0) continue;
            used++;
            modelMetres += clip.modelLinkMetres();
            if (clip.category() == LinkLocation.INSIDE) {
                inside++;
                insideModelMetres += clip.modelLinkMetres();
            } else if (clip.category() == LinkLocation.OUTSIDE) {
                outside++;
                outsideModelMetres += clip.modelLinkMetres();
            } else {
                throw new IllegalStateException("Point-anchored PT pseudolink has unsupported "
                        + "location " + clip.category());
            }
        }
        return new PtPseudolinkSummary(used, modelMetres, inside, insideModelMetres, outside,
                outsideModelMetres, 0, pointAnchoredTerritorialServiceMetres, zeroModelLength,
                zeroModelLengthTerritorialServiceMetres);
    }

    enum LinkLocation { INSIDE, OUTSIDE, CROSSING }
    enum LinkClipMethod { GEOMETRIC_LINE_CLIP, POINT_ANCHORED_PSEUDOLINK }
    record LinkClip(double insideFraction, LinkLocation category, double modelLinkMetres,
                    LinkClipMethod method) { }
    record ZeroGeometryLink(String linkId, double modelLinkMetres, Set<String> allowedModes,
                            double anchorX, double anchorY, String fromNodeId, String toNodeId) { }
    record PtPseudolinkSummary(long usedPointAnchoredLinks, double uncutModelMetres,
                               long insideLinks, double insideModelMetres, long outsideLinks,
                               double outsideModelMetres, long boundaryAmbiguousAnchors,
                               double territorialServiceMetres, long zeroModelLengthLinks,
                               double zeroModelLengthTerritorialServiceMetres) {
        static final PtPseudolinkSummary ZERO = new PtPseudolinkSummary(0, 0, 0, 0, 0, 0,
                0, 0, 0, 0);
    }
    record CarScope(double metres, long vehicles, long trips, long stuckTrips) { }
    record PtService(double uncutMetres, double territorialMetres, long crossingLinkCount,
                     double crossingLinkModelMetres, double crossingServiceMetres) { }
    record PtPassenger(double uncutMetres, double territorialMetres, long movementEvents,
                       long crossingLinkCount, double crossingPassengerMetres) { }
    record Result(Map<Production2040AccountingScopes.Scope, CarScope> carByScope,
                  Map<MunichTripBoundaryFilter.SpatialCategory, Double> carByEndpointCategory,
                  Map<String, PtService> ptByRouteMode,
                  Map<String, PtPassenger> ptPassengerByRouteMode,
                  long crossingLinkCount, double crossingLinkModelMetres,
                  PtPseudolinkSummary ptPseudolinks,
                  long unmatchedPersons, long unmatchedTrips, long repeatedVehicleEnters,
                  long unmatchedVehicleLeaves, long unattributedCarMovementEvents,
                  long incompleteCarSegments) {
        Result(Map<Production2040AccountingScopes.Scope, CarScope> carByScope,
               Map<MunichTripBoundaryFilter.SpatialCategory, Double> carByEndpointCategory,
               Map<String, PtService> ptByRouteMode,
               long crossingLinkCount, double crossingLinkModelMetres,
               long unmatchedPersons, long unmatchedTrips, long repeatedVehicleEnters,
               long unmatchedVehicleLeaves, long unattributedCarMovementEvents,
               long incompleteCarSegments) {
            this(carByScope, carByEndpointCategory, ptByRouteMode, Map.of(),
                    crossingLinkCount, crossingLinkModelMetres, PtPseudolinkSummary.ZERO, unmatchedPersons,
                    unmatchedTrips, repeatedVehicleEnters, unmatchedVehicleLeaves,
                    unattributedCarMovementEvents, incompleteCarSegments);
        }
    }

    private static final class CarSegment {
        private final Id<Person> person;
        private final Production2040AccountingScopes.TripScope trip;
        private double metres;

        private CarSegment(Id<Person> person, Production2040AccountingScopes.TripScope trip) {
            this.person = person;
            this.trip = trip;
        }
    }

    private static final class MutablePtService {
        private double uncutMetres;
        private double territorialMetres;
        private double crossingServiceMetres;
        private final Set<Id<Link>> crossingLinks = new HashSet<>();

        private PtService freeze(Map<Id<Link>, LinkClip> clips) {
            double modelMetres = crossingLinks.stream()
                    .map(clips::get).mapToDouble(LinkClip::modelLinkMetres).sum();
            return new PtService(uncutMetres, territorialMetres, crossingLinks.size(),
                    modelMetres, crossingServiceMetres);
        }
    }

    private static final class MutablePtPassenger {
        private double uncutMetres;
        private double territorialMetres;
        private long movementEvents;
        private double crossingPassengerMetres;
        private final Set<Id<Link>> crossingLinks = new HashSet<>();

        private PtPassenger freeze(Map<Id<Link>, LinkClip> clips) {
            double crossingModelMetres = crossingLinks.stream().map(clips::get)
                    .mapToDouble(LinkClip::modelLinkMetres).sum();
            Production2040AnalysisSpec.require(Double.isFinite(crossingModelMetres),
                    "Invalid crossing-link passenger geometry");
            return new PtPassenger(uncutMetres, territorialMetres, movementEvents,
                    crossingLinks.size(), crossingPassengerMetres);
        }
    }
}
