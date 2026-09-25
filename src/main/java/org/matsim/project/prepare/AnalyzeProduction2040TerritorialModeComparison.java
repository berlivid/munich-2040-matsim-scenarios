package org.matsim.project.prepare;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Read-only comparison of territorial main-trip counts and previously validated
 * territorial performance indicators for the completed BAU and Fast Track 2040 runs.
 * It never creates a MATSim Controller or QSim.
 */
public final class AnalyzeProduction2040TerritorialModeComparison {
    static final String SUBDIRECTORY = "territorial_mode_comparison_2040";
    static final double POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES = 1e-6;
    static final double SHARE_TOLERANCE_PERCENTAGE_POINTS = 1e-9;
    static final int DAYS_PER_YEAR = 365;
    private static final List<String> MODES = Production2040AnalysisSpec.MAIN_MODES;
    private static final List<String> PT_MODES = Production2040AnalysisSpec.PT_ROUTE_MODES;

    private AnalyzeProduction2040TerritorialModeComparison() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeProduction2040TerritorialModeComparison accepts no arguments");
        analyze();
    }

    static void analyze() throws Exception {
        Path destination = Production2040Contract.ROOT.resolve("analysis").resolve(SUBDIRECTORY);
        Production2040AnalysisSpec.require(!Files.exists(destination),
                "Territorial mode-comparison analysis already exists and will not be overwritten: "
                        + Production2040Contract.projectPath(destination));
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        ScenarioResult bau = readScenario(Production2040AnalysisSpec.scenario("BAU"), boundary);
        ScenarioResult fast = readScenario(Production2040AnalysisSpec.scenario("FAST_TRACK"), boundary);
        Comparison comparison = new Comparison(bau, fast, boundary);
        validateComparison(comparison);
        publishAtomically(destination, comparison);
        System.out.println("2040 TERRITORIAL MODE COMPARISON PASS");
        System.out.println("  output=" + Production2040Contract.projectPath(destination));
        System.out.println("No Controller or QSim was started by the analyzer.");
    }

    private static ScenarioResult readScenario(Production2040AnalysisSpec.ScenarioDefinition definition,
            MunichMunicipalBoundary boundary) throws Exception {
        var files = ValidateProduction2040AnalysisOutput.validatePublished(definition);
        ValidateProduction2040AccountingScopes.validatePublished(definition);
        ValidateProduction2040PtCostAllocation.validatePublished(definition);
        requireFinalRootSources(definition, files);
        var activeRouting = AnalyzeProduction2040TerritorialCostInputs.readActiveRouting(
                files.config());
        CostInputs cost = readCostInputs(definition);
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(files.network().toString());
        new TransitScheduleReader(scenario).readFile(files.schedule().toString());
        TerritorialGeometryCache geometryCache = new TerritorialGeometryCache(scenario.getNetwork(),
                boundary);
        long planAnalysisStartedNanos = System.nanoTime();
        TripAudit audit = readFinalSelectedPlans(files.plans(), scenario.getNetwork(),
                scenario.getTransitSchedule(), boundary, geometryCache);
        reportPlanAnalysisDiagnostics(definition.scenarioId(), audit, geometryCache,
                System.nanoTime() - planAnalysisStartedNanos);
        return new ScenarioResult(definition, files, activeRouting, audit, cost);
    }

    private static void requireFinalRootSources(Production2040AnalysisSpec.ScenarioDefinition definition,
            ValidateProduction2040AnalysisOutput.ValidatedOutput files) {
        Path expectedPlans = definition.outputDirectory().resolve(
                definition.contract().runId() + ".output_plans.xml.gz").toAbsolutePath().normalize();
        Path expectedEvents = definition.outputDirectory().resolve(
                definition.contract().runId() + ".output_events.xml.gz").toAbsolutePath().normalize();
        Production2040AnalysisSpec.require(files.plans().toAbsolutePath().normalize().equals(expectedPlans)
                        && !files.plans().toString().replace('\\', '/').contains("/ITERS/"),
                "Territorial main-trip analysis must use the one root final selected-plan file, "
                        + "not an iteration copy");
        Production2040AnalysisSpec.require(files.events().toAbsolutePath().normalize().equals(expectedEvents)
                        && !files.events().toString().replace('\\', '/').contains("/ITERS/"),
                "Territorial comparison must validate the one root final event file, not an "
                        + "iteration copy");
    }

    static TripAudit readFinalSelectedPlans(Path plans, Network network, TransitSchedule schedule,
            MunichMunicipalBoundary boundary) {
        return readFinalSelectedPlans(plans, network, schedule, boundary,
                new TerritorialGeometryCache(network, boundary));
    }

    static TripAudit readFinalSelectedPlans(Path plans, Network network, TransitSchedule schedule,
            MunichMunicipalBoundary boundary, TerritorialGeometryCache geometryCache) {
        Production2040AnalysisSpec.require(Files.isRegularFile(plans),
                "Missing final selected plans for territorial main-trip analysis: " + plans);
        geometryCache.requireCompatible(network, boundary);
        TripAudit audit = new TripAudit();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> collectPersonTrips(person, schedule, geometryCache, audit));
        reader.readFile(plans.toString());
        audit.requireNoFatalGeometry();
        return audit.freeze();
    }

    static TripAudit collectTrips(Iterable<Person> persons, Network network, TransitSchedule schedule,
            MunichMunicipalBoundary boundary) {
        return collectTrips(persons, network, schedule, boundary,
                new TerritorialGeometryCache(network, boundary));
    }

    static TripAudit collectTrips(Iterable<Person> persons, Network network, TransitSchedule schedule,
            MunichMunicipalBoundary boundary, TerritorialGeometryCache geometryCache) {
        geometryCache.requireCompatible(network, boundary);
        TripAudit audit = new TripAudit();
        for (Person person : persons) collectPersonTrips(person, schedule, geometryCache, audit);
        audit.requireNoFatalGeometry();
        return audit.freeze();
    }

    private static void collectPersonTrips(Person person, TransitSchedule schedule,
            TerritorialGeometryCache geometryCache, TripAudit audit) {
        audit.processedPersons++;
        if (audit.processedPersons % 25_000 == 0) {
            System.out.println("  territorial main-trip progress: persons="
                    + audit.processedPersons + ", main_trips=" + audit.totalMainTrips);
        }
        Plan selected = person.getSelectedPlan();
        if (selected == null) {
            audit.addFatal("missing_selected_plan", "unknown", "none", person.getId().toString());
            return;
        }
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(selected,
                StageActivityTypeIdentifier::isStageActivity);
        for (int index = 0; index < trips.size(); index++) {
            TripStructureUtils.Trip trip = trips.get(index);
            String mainMode = Production2040AnalysisSpec.normalizeMainMode(
                    MunichTripBoundaryFilter.identifyInputMainMode(trip));
            String key = person.getId() + "#" + index;
            audit.totalMainTrips++;
            if (!MODES.contains(mainMode)) {
                audit.unexpectedMainModes.merge(mainMode, 1L, Long::sum);
                audit.addFatal("unexpected_main_mode", mainMode, "main_trip", key);
                continue;
            }
            double territorialMetres;
            try {
                territorialMetres = territorialDistance(mainMode, trip, schedule, geometryCache,
                        audit, key);
            } catch (RuntimeException exception) {
                audit.addFatal("unresolved_route_geometry", mainMode,
                        routeTypes(trip), key + ": " + exception.getMessage());
                continue;
            }
            if (!(territorialMetres > POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES)) {
                audit.excludedByMainMode.merge(mainMode, 1L, Long::sum);
                continue;
            }
            Scope scope = scope(trip.getOriginActivity(), trip.getDestinationActivity(),
                    geometryCache.boundary());
            if (scope == null) {
                audit.invalidCoordinates++;
                audit.addFatal("invalid_origin_or_destination_coordinate", mainMode,
                        routeTypes(trip), key);
                continue;
            }
            audit.incrementIncluded(mainMode, scope);
        }
    }

    private static double territorialDistance(String mainMode, TripStructureUtils.Trip trip,
            TransitSchedule schedule, TerritorialGeometryCache geometryCache,
            TripAudit audit, String key) {
        return switch (mainMode) {
            case "car" -> carDistance(trip, geometryCache, audit, key);
            case "pt" -> ptDistance(trip, schedule, geometryCache, audit, key);
            case "bike" -> teleportedDistance(trip, Set.of("bike"), geometryCache.boundary(),
                    audit, key, "bike");
            case "walk" -> teleportedDistance(trip, Set.of("walk", "transit_walk",
                    "non_network_walk"), geometryCache.boundary(), audit, key, "walk");
            default -> throw new IllegalStateException("Unexpected main mode " + mainMode);
        };
    }

    private static double carDistance(TripStructureUtils.Trip trip,
            TerritorialGeometryCache geometryCache, TripAudit audit, String key) {
        double total = 0.0;
        boolean found = false;
        for (PlanElement element : trip.getTripElements()) {
            if (!(element instanceof Leg leg) || !"car".equals(
                    Production2040AnalysisSpec.normalizeMainMode(leg.getMode()))) continue;
            found = true;
            if (!(leg.getRoute() instanceof NetworkRoute route)) {
                throw new IllegalStateException("car leg route type is " + routeType(leg));
            }
            total += clippedNetworkRoute(route, geometryCache, "car", key);
        }
        if (!found) throw new IllegalStateException("car main trip contains no car NetworkRoute");
        return total;
    }

    private static double ptDistance(TripStructureUtils.Trip trip, TransitSchedule schedule,
            TerritorialGeometryCache geometryCache, TripAudit audit,
            String key) {
        double total = 0.0;
        boolean foundPtLeg = false;
        for (PlanElement element : trip.getTripElements()) {
            if (!(element instanceof Leg leg)) continue;
            if (!(leg.getRoute() instanceof TransitPassengerRoute passengerRoute)) {
                if ("pt".equals(Production2040AnalysisSpec.normalizeMainMode(leg.getMode()))) {
                    throw new IllegalStateException("PT leg route type is " + routeType(leg));
                }
                continue;
            }
            foundPtLeg = true;
            TransitLine line = schedule.getTransitLines().get(passengerRoute.getLineId());
            TransitRoute route = line == null ? null : line.getRoutes().get(passengerRoute.getRouteId());
            if (route == null || route.getRoute() == null) {
                throw new IllegalStateException("missing scheduled transit route "
                        + passengerRoute.getLineId() + "/" + passengerRoute.getRouteId());
            }
            String routeMode = Production2040AnalysisSpec.normalizePtRouteMode(route.getTransportMode());
            double distance = clippedTransitSegment(route, passengerRoute, geometryCache, key);
            if (!PT_MODES.contains(routeMode)) {
                audit.unexpectedPtRouteModes.merge(routeMode, 1L, Long::sum);
                if (distance > POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES) {
                    throw new IllegalStateException("unexpected PT route mode with positive territorial "
                            + "distance: " + routeMode);
                }
            }
            total += distance;
        }
        return foundPtLeg ? total : 0.0;
    }

    private static double teleportedDistance(TripStructureUtils.Trip trip, Set<String> legModes,
            MunichMunicipalBoundary boundary, TripAudit audit, String key,
            String auditMode) {
        double total = 0.0;
        List<? extends PlanElement> elements = trip.getTripElements();
        for (int index = 0; index < elements.size(); index++) {
            if (!(elements.get(index) instanceof Leg leg)) continue;
            String legMode = Production2040AnalysisSpec.normalizeMainMode(leg.getMode());
            if (!legModes.contains(legMode)) continue;
            Activity from = index > 0 && elements.get(index - 1) instanceof Activity value
                    ? value : trip.getOriginActivity();
            Activity to = index + 1 < elements.size() && elements.get(index + 1) instanceof Activity value
                    ? value : trip.getDestinationActivity();
            double modelled = leg.getRoute() == null ? Double.NaN : leg.getRoute().getDistance();
            if (!Double.isFinite(modelled) || modelled < 0.0) {
                audit.missingDistanceMeasurements++;
                throw new IllegalStateException(auditMode + " leg lacks a finite modeled distance");
            }
            if (!valid(from == null ? null : from.getCoord()) || !valid(to == null ? null : to.getCoord())) {
                audit.invalidCoordinates++;
                throw new IllegalStateException(auditMode + " leg lacks finite endpoint coordinates");
            }
            Coordinate start = coordinate(from.getCoord());
            Coordinate end = coordinate(to.getCoord());
            if (start.equals2D(end)) {
                audit.zeroDistanceRoutes++;
                if (modelled > 0.0) throw new IllegalStateException(auditMode
                        + " leg has positive modeled distance but coincident coordinates");
                continue;
            }
            double fraction = Production2040AccountingEventMetrics.geometricInsideFraction(start, end,
                    boundary);
            total += modelled * fraction;
        }
        return total;
    }

    private static double clippedTransitSegment(TransitRoute transitRoute,
            TransitPassengerRoute passengerRoute, TerritorialGeometryCache geometryCache,
            String key) {
        PtSegmentKey segmentKey = new PtSegmentKey(passengerRoute.getLineId(),
                passengerRoute.getRouteId(), passengerRoute.getAccessStopId(),
                passengerRoute.getEgressStopId());
        return geometryCache.territorialPtSegment(segmentKey, transitRoute, passengerRoute, key);
    }

    private static double calculateClippedTransitSegment(TransitRoute transitRoute,
            TransitPassengerRoute passengerRoute, TerritorialGeometryCache geometryCache,
            String key) {
        var access = transitRoute.getStops().stream().map(value -> value.getStopFacility())
                .filter(value -> value.getId().equals(passengerRoute.getAccessStopId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("access stop is absent from transit route"));
        var egress = transitRoute.getStops().stream().map(value -> value.getStopFacility())
                .filter(value -> value.getId().equals(passengerRoute.getEgressStopId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("egress stop is absent from transit route"));
        if (access.getLinkId() == null || egress.getLinkId() == null) {
            throw new IllegalStateException("transit access or egress stop has no link");
        }
        List<Id<Link>> links = networkRouteLinks(transitRoute.getRoute());
        int accessIndex = links.indexOf(access.getLinkId());
        int egressIndex = -1;
        for (int index = accessIndex; index >= 0 && index < links.size(); index++) {
            if (links.get(index).equals(egress.getLinkId())) {
                egressIndex = index;
                break;
            }
        }
        if (accessIndex < 0 || egressIndex < accessIndex) {
            throw new IllegalStateException("transit stop links are not ordered on the scheduled route");
        }
        double total = 0.0;
        // Matches MATSim RouteUtils: the access link is not traveled in-vehicle; the egress link is.
        for (int index = accessIndex + 1; index <= egressIndex; index++) {
            total += clippedLink(links.get(index), geometryCache, "PT transit segment", key);
        }
        return total;
    }

    private static double clippedNetworkRoute(NetworkRoute route,
            TerritorialGeometryCache geometryCache, String mode, String key) {
        double total = 0.0;
        for (Id<Link> link : networkRouteLinks(route)) {
            total += clippedLink(link, geometryCache, mode + " NetworkRoute", key);
        }
        return total;
    }

    private static List<Id<Link>> networkRouteLinks(NetworkRoute route) {
        if (route == null || route.getStartLinkId() == null || route.getEndLinkId() == null) {
            throw new IllegalStateException("NetworkRoute lacks a start or end link");
        }
        List<Id<Link>> result = new ArrayList<>();
        appendRouteLink(result, route.getStartLinkId());
        List<Id<Link>> intermediate = route.getLinkIds();
        if (intermediate == null) {
            throw new IllegalStateException("NetworkRoute has no intermediate-link list");
        }
        for (Id<Link> link : intermediate) appendRouteLink(result, link);
        appendRouteLink(result, route.getEndLinkId());
        return List.copyOf(result);
    }

    private static void appendRouteLink(List<Id<Link>> target, Id<Link> link) {
        if (link == null) throw new IllegalStateException("NetworkRoute contains a null link ID");
        if (target.isEmpty() || !target.getLast().equals(link)) target.add(link);
    }

    private static double clippedLink(Id<Link> id, TerritorialGeometryCache geometryCache,
            String context, String key) {
        return geometryCache.territorialLinkDistance(id, context, key);
    }

    private static void reportPlanAnalysisDiagnostics(String scenarioId, TripAudit audit,
            TerritorialGeometryCache geometryCache, long elapsedNanos) {
        GeometryCacheDiagnostics diagnostics = geometryCache.diagnostics();
        System.out.println("2040 TERRITORIAL PLAN ANALYSIS DIAGNOSTICS");
        System.out.println("  scenario=" + scenarioId);
        System.out.println("  processed_persons=" + audit.processedPersons());
        System.out.println("  processed_main_trips=" + audit.totalMainTrips());
        System.out.println("  unique_link_clipping_calculations="
                + diagnostics.uniqueLinkClippingCalculations());
        System.out.println("  link_cache_hits=" + diagnostics.linkCacheHits());
        System.out.println("  link_cache_misses=" + diagnostics.linkCacheMisses());
        System.out.println("  pt_segment_cache_hits=" + diagnostics.ptSegmentCacheHits());
        System.out.println("  pt_segment_cache_misses=" + diagnostics.ptSegmentCacheMisses());
        System.out.println("  elapsed_analysis_seconds=" + String.format(Locale.ROOT, "%.3f",
                elapsedNanos / 1_000_000_000.0));
    }

    private static Scope scope(Activity origin, Activity destination, MunichMunicipalBoundary boundary) {
        if (!valid(origin == null ? null : origin.getCoord()) || !valid(destination == null
                ? null : destination.getCoord())) return null;
        boolean originInside = boundary.covers(origin.getCoord());
        boolean destinationInside = boundary.covers(destination.getCoord());
        if (originInside && destinationInside) return Scope.INTERNAL;
        if (!originInside && destinationInside) return Scope.INBOUND;
        if (originInside) return Scope.OUTBOUND;
        return Scope.THROUGH;
    }

    private static boolean valid(Coord coord) {
        return coord != null && Double.isFinite(coord.getX()) && Double.isFinite(coord.getY());
    }

    private static Coordinate coordinate(Coord coord) {
        return new Coordinate(coord.getX(), coord.getY());
    }

    private static String routeType(Leg leg) {
        return leg.getRoute() == null ? "null" : leg.getRoute().getClass().getSimpleName();
    }

    private static String routeTypes(TripStructureUtils.Trip trip) {
        return trip.getTripElements().stream().filter(Leg.class::isInstance).map(Leg.class::cast)
                .map(AnalyzeProduction2040TerritorialModeComparison::routeType).distinct()
                .sorted().reduce((left, right) -> left + "|" + right).orElse("none");
    }

    static CostInputs readCostInputs(Production2040AnalysisSpec.ScenarioDefinition definition)
            throws IOException {
        Path workbookPath = definition.analysisDirectory().resolve(
                AnalyzeProduction2040TerritorialCostInputs.SUBDIRECTORY).resolve(
                        AnalyzeProduction2040TerritorialCostInputs.workbookFileName(definition));
        Production2040AnalysisSpec.require(Files.isRegularFile(workbookPath),
                "Missing published territorial cost-input workbook: "
                        + Production2040Contract.projectPath(workbookPath));
        Map<String, CostMetric> rows = new TreeMap<>();
        CostMetric allModePkm = null;
        try (InputStream input = Files.newInputStream(workbookPath); Workbook workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheet("Inputs");
            Production2040AnalysisSpec.require(sheet != null, "Cost-input workbook lacks Inputs sheet");
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getCell(0) == null || row.getCell(0).getCellType() != CellType.STRING) continue;
                String label = row.getCell(0).getStringCellValue();
                String mode = costMode(label);
                String normalizedLabel = label.trim().toLowerCase(Locale.ROOT);
                if (mode == null) {
                    if (normalizedLabel.startsWith("other pt")) {
                        CostMetric other = readCostMetric(row, label);
                        Production2040AnalysisSpec.require(other.dailyPkm()
                                        <= POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES
                                        && (other.dailyFkm() == null || other.dailyFkm()
                                        <= POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES),
                                "Territorial cost inputs contain a positive unexpected PT mode: "
                                        + label);
                    } else if (normalizedLabel.startsWith("all-mode pkm total")) {
                        CostMetric value = readCostMetric(row, label);
                        Production2040AnalysisSpec.require(allModePkm == null,
                                "Duplicate all-mode Pkm total in territorial cost inputs");
                        allModePkm = value;
                    }
                    continue;
                }
                CostMetric value = readCostMetric(row, label);
                Production2040AnalysisSpec.require(rows.put(mode, value) == null,
                        "Duplicate cost-input mode " + mode);
            }
        }
        for (String mode : List.of("car", "walk", "bike", "bus", "tram", "subway", "rail", "pt")) {
            Production2040AnalysisSpec.require(rows.containsKey(mode),
                    "Cost-input workbook lacks " + mode + " row");
        }
        Production2040AnalysisSpec.require(rows.get("walk").dailyFkm() == null,
                "Walking Fkm must be not applicable in territorial cost inputs");
        validateCostMetric("car", rows.get("car"));
        validateCostMetric("walk", rows.get("walk"));
        validateCostMetric("bike", rows.get("bike"));
        for (String mode : PT_MODES) validateCostMetric(mode, rows.get(mode));
        validatePtSubtotal(rows.get("pt"));
        double pkm = 0.0;
        double fkm = 0.0;
        for (String mode : PT_MODES) {
            pkm += rows.get(mode).dailyPkm();
            fkm += Objects.requireNonNull(rows.get(mode).dailyFkm(), "PT Fkm");
        }
        requireClose(pkm, rows.get("pt").dailyPkm(), "PT Pkm subtotal in territorial cost inputs");
        requireClose(fkm, Objects.requireNonNull(rows.get("pt").dailyFkm(), "PT total Fkm").doubleValue(),
                "PT Fkm subtotal in territorial cost inputs");
        Production2040AnalysisSpec.require(allModePkm != null,
                "Cost-input workbook lacks the all-mode Pkm total row");
        validateAllModePkmTotal(allModePkm, rows);
        return new CostInputs(workbookPath, Map.copyOf(rows));
    }

    private static CostMetric readCostMetric(Row row, String label) {
        double dailyPkm = numeric(row.getCell(1), label + " daily Pkm");
        Double dailyFkm = optionalNumeric(row.getCell(2), label + " daily Fkm");
        double annualPkm = numeric(row.getCell(3), label + " annual Pkm");
        Double annualFkm = optionalNumeric(row.getCell(4), label + " annual Fkm");
        Double occupancy = optionalNumeric(row.getCell(5), label + " occupancy");
        String unit = text(row.getCell(6), label + " vehicle/distance unit");
        String basis = text(row.getCell(7), label + " calculation basis");
        String status = text(row.getCell(8), label + " quality status");
        Production2040AnalysisSpec.require(dailyPkm >= 0.0 && (dailyFkm == null || dailyFkm >= 0.0)
                        && (occupancy == null || occupancy >= 0.0),
                "Cost-input workbook contains a negative quantity for " + label);
        requireClose(annualPkm, dailyPkm * DAYS_PER_YEAR / 1_000_000.0,
                "Cost-input annual Pkm conversion for " + label);
        if (dailyFkm == null) {
            Production2040AnalysisSpec.require(annualFkm == null,
                    "Cost-input Fkm applicability differs between daily and annual fields for " + label);
        } else requireClose(annualFkm.doubleValue(), dailyFkm * DAYS_PER_YEAR / 1_000_000.0,
                "Cost-input annual Fkm conversion for " + label);
        return new CostMetric(dailyPkm, dailyFkm, annualPkm, annualFkm, occupancy, unit, basis,
                status);
    }

    private static void validateCostMetric(String mode, CostMetric metric) {
        switch (mode) {
            case "car" -> {
                Production2040AnalysisSpec.require(metric.dailyFkm() != null
                                && metric.occupancy() != null
                                && Set.of("PASS_EVENT_RECONCILED",
                                "REPORTED_STUCK_OR_OPEN_PARTIAL_MOVEMENT")
                                .contains(metric.qualityStatus()),
                        "Unexpected territorial car quality status: " + metric.qualityStatus());
                requireClose(metric.dailyPkm(), metric.dailyFkm() * 1.5,
                        "Territorial car Pkm/Fkm occupancy consistency");
                requireClose(metric.occupancy(), 1.5,
                        "Territorial car occupancy assumption");
            }
            case "walk" -> Production2040AnalysisSpec.require(metric.dailyFkm() == null
                            && metric.occupancy() == null
                            && Set.of("PASS_COMPLETED_ACTIVE_LEGS",
                            "REPORTED_WITH_UNRESOLVED_ACTIVE_LEGS")
                            .contains(metric.qualityStatus()),
                    "Unexpected territorial walk quality status: " + metric.qualityStatus());
            case "bike" -> {
                Production2040AnalysisSpec.require(metric.dailyFkm() != null
                                && metric.occupancy() != null
                                && Set.of("PASS_COMPLETED_ACTIVE_LEGS",
                                "REPORTED_WITH_UNRESOLVED_ACTIVE_LEGS")
                                .contains(metric.qualityStatus()),
                        "Unexpected territorial bike quality status: " + metric.qualityStatus());
                requireClose(metric.dailyPkm(), metric.dailyFkm(),
                        "Territorial bike Pkm/Fkm consistency");
                requireClose(metric.occupancy(), 1.0,
                        "Territorial bike occupancy convention");
            }
            default -> validatePtComponent(mode, metric);
        }
    }

    static void validatePtComponent(String mode, CostMetric metric) {
        Production2040AnalysisSpec.require(metric.dailyFkm() != null,
                "PT component lacks full-service Fkm: " + mode);
        String[] tokens = metric.qualityStatus().split(";", -1);
        Production2040AnalysisSpec.require(tokens.length >= 4
                        && "PASS_VALIDATED_PT_ACCOUNTING".equals(tokens[1].trim())
                        && "REQUIRES_EXTERNAL_UNIT_MAPPING".equals(tokens[tokens.length - 1].trim()),
                "Unexpected territorial PT quality status structure for " + mode + ": "
                        + metric.qualityStatus());
        String occupancyStatus = tokens[0].trim();
        switch (occupancyStatus) {
            case AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_REPORTED_FOR_REVIEW ->
                    Production2040AnalysisSpec.require(metric.dailyFkm() > 0.0
                                    && metric.dailyPkm() > 0.0 && metric.occupancy() != null
                                    && metric.occupancy() > 0.0,
                            "PASS_REPORTED_FOR_REVIEW is numerically inconsistent for " + mode);
            case AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_VALID_ZERO_PASSENGER ->
                    Production2040AnalysisSpec.require(metric.dailyFkm() > 0.0
                                    && isZero(metric.dailyPkm()) && metric.occupancy() != null
                                    && isZero(metric.occupancy()),
                            "VALID_ZERO_PASSENGER_ACTIVITY is numerically inconsistent for "
                                    + mode);
            case AnalyzeProduction2040TerritorialCostInputs.PT_OCCUPANCY_NOT_APPLICABLE_ZERO_ACTIVITY ->
                    Production2040AnalysisSpec.require(isZero(metric.dailyFkm())
                                    && isZero(metric.dailyPkm()) && metric.occupancy() == null,
                            "NOT_APPLICABLE_VALID_ZERO_ACTIVITY is numerically inconsistent for "
                                    + mode);
            default -> throw new IllegalStateException("Unsupported territorial PT occupancy status for "
                    + mode + ": " + occupancyStatus);
        }
        if (metric.occupancy() != null) requireClose(metric.occupancy(),
                metric.dailyPkm() / metric.dailyFkm(),
                "Territorial PT occupancy consistency for " + mode);
    }

    private static void validatePtSubtotal(CostMetric metric) {
        Production2040AnalysisSpec.require(metric.dailyFkm() != null && metric.occupancy() == null
                        && "SUBTOTAL_MIXED_VEHICLE_UNITS".equals(metric.qualityStatus()),
                "Territorial PT subtotal status or applicability is invalid: "
                        + metric.qualityStatus());
    }

    private static void validateAllModePkmTotal(CostMetric allModePkm,
            Map<String, CostMetric> rows) {
        Production2040AnalysisSpec.require(allModePkm.dailyFkm() == null
                        && allModePkm.occupancy() == null
                        && "TOTAL_NO_PT_DOUBLE_COUNT".equals(allModePkm.qualityStatus()),
                "Territorial all-mode Pkm total status or applicability is invalid: "
                        + allModePkm.qualityStatus());
        double expected = rows.get("car").dailyPkm() + rows.get("walk").dailyPkm()
                + rows.get("bike").dailyPkm() + rows.get("pt").dailyPkm();
        requireClose(allModePkm.dailyPkm(), expected,
                "Territorial all-mode Pkm total without PT double counting");
    }

    private static boolean isZero(double value) {
        return Math.abs(value) <= 1e-9;
    }

    private static String costMode(String label) {
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "car" -> "car";
            case "walk" -> "walk";
            case "bike" -> "bike";
            case "bus" -> "bus";
            case "tram" -> "tram";
            case "subway" -> "subway";
            case "rail" -> "rail";
            default -> label.trim().toLowerCase(Locale.ROOT).startsWith("pt total") ? "pt" : null;
        };
    }

    private static double numeric(Cell cell, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.NUMERIC
                        && Double.isFinite(cell.getNumericCellValue()),
                "Cost-input workbook field is not a finite numeric cell: " + label);
        return cell.getNumericCellValue();
    }

    private static Double optionalNumeric(Cell cell, String label) {
        if (cell != null && cell.getCellType() == CellType.STRING
                && "NOT_APPLICABLE".equals(cell.getStringCellValue())) return null;
        return numeric(cell, label);
    }

    private static String text(Cell cell, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.STRING
                        && !cell.getStringCellValue().isBlank(),
                "Cost-input workbook field is not a nonblank text cell: " + label);
        return cell.getStringCellValue();
    }

    private static void validateComparison(Comparison comparison) {
        for (ScenarioResult result : List.of(comparison.bau(), comparison.fast())) {
            result.audit().requireNoFatalGeometry();
            long total = result.audit().includedTotal();
            Production2040AnalysisSpec.require(total > 0,
                    "No territorially active main trips for " + result.definition().scenarioId());
            long counted = MODES.stream().mapToLong(mode -> result.audit().included(mode)).sum();
            Production2040AnalysisSpec.require(counted == total,
                    "Territorial main-trip denominator does not reconcile for "
                            + result.definition().scenarioId());
            double shares = MODES.stream().mapToDouble(mode -> Production2040AnalysisSpec.percent(
                    result.audit().included(mode), total)).sum();
            Production2040AnalysisSpec.require(Math.abs(shares - 100.0)
                            <= SHARE_TOLERANCE_PERCENTAGE_POINTS,
                    "Territorial modal shares do not sum to 100 percent for "
                            + result.definition().scenarioId() + ": " + shares);
        }
    }

    private static void publishAtomically(Path destination, Comparison comparison) throws IOException {
        Path parent = destination.getParent();
        Production2040AnalysisSpec.require(parent != null, "Comparison output has no parent");
        Files.createDirectories(parent);
        Path temporary = Files.createTempDirectory(parent, ".territorial-mode-comparison-");
        try {
            writeCsv(temporary.resolve("territorial_main_mode_summary.csv"), mainModeCsv(comparison));
            writeCsv(temporary.resolve("territorial_pt_detail.csv"), ptCsv(comparison));
            writeCsv(temporary.resolve("territorial_trip_scope_audit.csv"), auditCsv(comparison));
            writeCsv(temporary.resolve("territorial_quality_checks.csv"), qualityCsv(comparison));
            writeCsv(temporary.resolve("territorial_methodology.md"), methodology(comparison));
            Path workbook = temporary.resolve("territorial_mode_comparison_2040.xlsx");
            writeWorkbook(workbook, comparison);
            validatePublished(temporary, comparison);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } catch (IOException | RuntimeException exception) {
            deleteTemporary(temporary);
            throw exception;
        }
    }

    private static String mainModeCsv(Comparison comparison) {
        StringBuilder csv = new StringBuilder("mode,bau_territorial_expanded_trips_per_technical_weekday,fast_track_territorial_expanded_trips_per_technical_weekday,absolute_trip_change,relative_trip_change_percent,bau_trip_based_modal_share_percent,fast_track_trip_based_modal_share_percent,modal_share_change_percentage_points,bau_territorial_annualized_pkm_million,fast_track_territorial_annualized_pkm_million,pkm_absolute_change_million,pkm_relative_change_percent,bau_territorial_annualized_fkm_million,fast_track_territorial_annualized_fkm_million,fkm_absolute_change_million,fkm_relative_change_percent,data_coverage_status\n");
        for (String mode : List.of("car", "pt", "bike", "walk", "TOTAL")) {
            SummaryRow row = summaryRow(comparison, mode);
            csv.append(csv(row.mode())).append(',').append(number(row.bauTrips())).append(',')
                    .append(number(row.fastTrips())).append(',').append(number(row.tripChange()))
                    .append(',').append(optionalNumber(row.relativeTripChange())).append(',')
                    .append(number(row.bauShare())).append(',').append(number(row.fastShare()))
                    .append(',').append(number(row.shareChange())).append(',')
                    .append(number(row.bauPkm())).append(',').append(number(row.fastPkm())).append(',')
                    .append(optionalNumber(row.pkmAbsoluteChange())).append(',')
                    .append(optionalNumber(row.pkmRelativeChange())).append(',')
                    .append(optionalNumber(row.bauFkm())).append(',').append(optionalNumber(row.fastFkm()))
                    .append(',').append(optionalNumber(row.fkmAbsoluteChange())).append(',')
                    .append(optionalNumber(row.fkmRelativeChange())).append(',')
                    .append(csv(row.status())).append('\n');
        }
        return csv.toString();
    }

    private static String ptCsv(Comparison comparison) {
        StringBuilder csv = new StringBuilder("pt_route_mode,vehicle_distance_unit,bau_territorial_annualized_pkm_million,fast_track_territorial_annualized_pkm_million,pkm_absolute_change_million,pkm_relative_change_percent,bau_full_service_annualized_fkm_million,fast_track_full_service_annualized_fkm_million,fkm_absolute_change_million,fkm_relative_change_percent,bau_average_occupancy,fast_track_average_occupancy,occupancy_absolute_change,data_coverage_status\n");
        for (String mode : List.of("bus", "tram", "subway", "rail", "pt")) {
            CostMetric bau = comparison.bau().cost().metrics().get(mode);
            CostMetric fast = comparison.fast().cost().metrics().get(mode);
            csv.append(csv("pt".equals(mode) ? "PT total" : mode)).append(',')
                    .append(csv(comparisonUnit(bau, fast))).append(',')
                    .append(number(bau.annualPkmMillion())).append(',').append(number(fast.annualPkmMillion()))
                    .append(',').append(optionalNumber(difference(bau.annualPkmMillion(), fast.annualPkmMillion())))
                    .append(',').append(optionalNumber(percentChange(bau.annualPkmMillion(), fast.annualPkmMillion())))
                    .append(',').append(optionalNumber(bau.annualFkmMillion())).append(',')
                    .append(optionalNumber(fast.annualFkmMillion())).append(',')
                    .append(optionalNumber(difference(bau.annualFkmMillion(), fast.annualFkmMillion())))
                    .append(',').append(optionalNumber(percentChange(bau.annualFkmMillion(), fast.annualFkmMillion())))
                    .append(',').append(optionalNumber(bau.occupancy())).append(',')
                    .append(optionalNumber(fast.occupancy())).append(',')
                    .append(optionalNumber(difference(bau.occupancy(), fast.occupancy()))).append(',')
                    .append(csv(comparisonStatus(bau, fast, "pt".equals(mode)
                            ? "Mixed vehicle-unit subtotal; do not use this occupancy for costing"
                            : null)))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String auditCsv(Comparison comparison) {
        StringBuilder csv = new StringBuilder("scenario_id,main_mode,trip_scope,final_selected_plan_main_trips,sample_territorial_main_trips,expanded_territorial_main_trips_per_technical_weekday,excluded_main_trips_for_mode,unresolved_route_geometry,invalid_coordinates,missing_distance_measurements,zero_distance_routes,unexpected_main_modes,unexpected_pt_route_modes,final_iteration_stuck_events,final_iteration_relevant_stuck_events\n");
        for (ScenarioResult result : List.of(comparison.bau(), comparison.fast())) {
            for (String mode : MODES) for (Scope scope : Scope.values()) {
                long sample = result.audit().included(mode, scope);
                csv.append(result.definition().scenarioId()).append(',').append(mode).append(',')
                        .append(scope).append(',').append(result.audit().totalMainTrips()).append(',')
                        .append(sample).append(',')
                        .append(number(Production2040AnalysisSpec.expanded(sample))).append(',')
                        .append(result.audit().excludedByMainMode().getOrDefault(mode, 0L)).append(',')
                        .append(result.audit().fatalIssues().size()).append(',')
                        .append(result.audit().invalidCoordinates()).append(',')
                        .append(result.audit().missingDistanceMeasurements()).append(',')
                        .append(result.audit().zeroDistanceRoutes()).append(',')
                        .append(csv(mapText(result.audit().unexpectedMainModes()))).append(',')
                        .append(csv(mapText(result.audit().unexpectedPtRouteModes()))).append(',')
                        .append(result.files().stuckTotals().get(Production2040AnalysisSpec.LAST_ITERATION).events())
                        .append(',').append(result.files().stuckTotals().get(
                                Production2040AnalysisSpec.LAST_ITERATION).relevantEvents()).append('\n');
            }
        }
        return csv.toString();
    }

    private static String qualityCsv(Comparison comparison) {
        StringBuilder csv = new StringBuilder("scenario_id,check,status,detail\n");
        for (ScenarioResult result : List.of(comparison.bau(), comparison.fast())) {
            String id = result.definition().scenarioId();
            check(csv, id, "completed_production_output", "PASS", result.files().output().toString());
            check(csv, id, "root_final_sources_only", "PASS", "plans="
                    + sourcePath(result.files().plans()) + "; events="
                    + sourcePath(result.files().events()));
            check(csv, id, "final_selected_plan_main_trip_scope", "PASS", "TripStructureUtils with MATSim stage-activity handling");
            check(csv, id, "modal_share_sum", "PASS", number(result.audit().shareSum()));
            check(csv, id, "teleported_walk_bike_configuration", "PASS", "network modes="
                    + String.join("|", result.activeRouting().networkModes()));
            check(csv, id, "cost_input_reconciliation", "PASS", sourcePath(result.cost().workbook())
                    + " Inputs!A5:I14");
            check(csv, id, "pt_occupancy_statuses", "PASS", ptStatusText(result.cost()));
            check(csv, id, "route_geometry", "PASS", "No unresolved route geometry");
            check(csv, id, "unexpected_modes", "PASS", "No unexpected main or positive PT route modes");
            ValidateProduction2040AnalysisOutput.StuckTotal stuck = result.files().stuckTotals().get(
                    Production2040AnalysisSpec.LAST_ITERATION);
            check(csv, id, "stuck_event_qualification", "PASS", "events=" + stuck.events()
                    + "; relevant_events=" + stuck.relevantEvents()
                    + "; no remaining route distance inferred");
        }
        check(csv, "COMPARISON", "boundary", "PASS", comparison.boundary().sha256());
        return csv.toString();
    }

    private static String ptStatusText(CostInputs cost) {
        return PT_MODES.stream().map(mode -> mode + "=" + cost.metrics().get(mode)
                .qualityStatus().split(";", -1)[0].trim()).reduce((left, right) -> left + ";"
                        + right).orElseThrow();
    }

    private static String methodology(Comparison comparison) {
        return "# Territorial mode comparison for BAU 2040 and Fast Track 2040\n\n"
                + "## Purpose and territorial scope\n\n"
                + "This read-only comparison supports the external-cost comparison by counting modeled movement "
                + "inside the City of Munich municipal boundary, regardless of residence or trip endpoint. It is an "
                + "additional territorial perspective; it neither replaces the existing BOTH_INSIDE analysis nor changes "
                + "the resident-target calibration scope. A trip with both endpoints outside the boundary is included as "
                + "THROUGH when it has positive reconstructed main-mode distance inside the municipality.\n\n"
                + "The modal split uses final selected-plan MATSim main trips from iteration "
                + Production2040AnalysisSpec.LAST_ITERATION + ". Trips are constructed with TripStructureUtils "
                + "and stage activities do not create extra main trips. A trip is territorially active only when its "
                + "standard analysis main mode has more than "
                + number(POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES)
                + " meters of reconstructed modeled distance within the boundary.\n\n"
                + "## Main-mode reconstruction\n\n"
                + "Car NetworkRoutes are clipped link by link. PT main trips use only in-vehicle TransitPassengerRoute "
                + "segments, including transfers, clipped against the corresponding scheduled transit route. Access, egress, "
                + "and transfer walking alone cannot qualify a pt main trip. Teleported walk and bike legs use modeled leg "
                + "distance multiplied by the inside fraction of each leg's own straight endpoint segment. No endpoint proxy "
                + "is used when a required route or coordinate is unavailable: the analyzer fails closed with a route-type "
                + "diagnostic.\n\n"
                + "## Pkm, Fkm, and scaling\n\n"
                + "Territorial Pkm and Fkm are transferred from the validated territorial cost-input workbooks and reconciled "
                + "against their Inputs!A5:I14 numeric transfer tables before publication. Pkm are assigned to physical movement "
                + "modes. Consequently, PT access, egress, and transfer walking contributes to walking Pkm but does not create a "
                + "walk main trip. Mean trip distance is not calculated by dividing physical-mode Pkm by main-mode trip counts.\n\n"
                + "Private demand is a five-percent sample. Main-trip counts and demand-based Pkm are expanded exactly once by "
                + "20. PT Fkm represents full territorial service supply and remains at factor 1. Walking Fkm is not applicable. "
                + "PT total Fkm is a mixed-vehicle-unit subtotal; no train-to-carriage or external cost-workbook conversion is "
                + "invented. The existing documented PT pseudolink treatment is retained because Pkm and Fkm are transferred, not "
                + "reconstructed here.\n\n"
                + "## Reporting day and limitations\n\n"
                + "Daily values use a technical weekday. Annual values are mechanical annualized technical-weekday equivalents: "
                + "daily values multiplied by 365 and divided by 1,000,000. The simulation horizon may extend beyond 24 hours; "
                + "the final event file is one simulated reporting day, not a sequence of independent daily observations.\n\n"
                + "The existing BOTH_INSIDE/resident-target mismatch remains a calibration-scope limitation. Fixed noise and "
                + "infrastructure costs require separate assumptions. The comparison covers the four modeled modes—car, pt, bike, "
                + "and walk—not all transport in Munich.\n\n"
                + "## Validated sources\n\n"
                + "- BAU final selected plans: `" + sourcePath(comparison.bau().files().plans()) + "`\n"
                + "- Fast Track final selected plans: `" + sourcePath(comparison.fast().files().plans()) + "`\n"
                + "- BAU territorial cost inputs: `" + sourcePath(comparison.bau().cost().workbook()) + "`\n"
                + "- Fast Track territorial cost inputs: `" + sourcePath(comparison.fast().cost().workbook()) + "`\n"
                + "- Boundary: `" + comparison.boundary().source() + "` (" + comparison.boundary().crs()
                + ", SHA-256 `" + comparison.boundary().sha256() + "`)\n";
    }

    private static SummaryRow summaryRow(Comparison comparison, String mode) {
        ScenarioResult bau = comparison.bau();
        ScenarioResult fast = comparison.fast();
        long bauSample = "TOTAL".equals(mode) ? bau.audit().includedTotal() : bau.audit().included(mode);
        long fastSample = "TOTAL".equals(mode) ? fast.audit().includedTotal() : fast.audit().included(mode);
        double bauTrips = Production2040AnalysisSpec.expanded(bauSample);
        double fastTrips = Production2040AnalysisSpec.expanded(fastSample);
        double bauShare = Production2040AnalysisSpec.percent(bauSample, bau.audit().includedTotal());
        double fastShare = Production2040AnalysisSpec.percent(fastSample, fast.audit().includedTotal());
        if ("TOTAL".equals(mode)) { bauShare = 100.0; fastShare = 100.0; }
        CostMetric bauMetric = totalOrMode(bau.cost(), mode);
        CostMetric fastMetric = totalOrMode(fast.cost(), mode);
        Double bauFkm = "TOTAL".equals(mode) ? null : bauMetric.annualFkmMillion();
        Double fastFkm = "TOTAL".equals(mode) ? null : fastMetric.annualFkmMillion();
        return new SummaryRow(mode, bauTrips, fastTrips, fastTrips - bauTrips,
                percentChange(bauTrips, fastTrips), bauShare, fastShare, fastShare - bauShare,
                bauMetric.annualPkmMillion(), fastMetric.annualPkmMillion(), difference(
                        bauMetric.annualPkmMillion(), fastMetric.annualPkmMillion()),
                percentChange(bauMetric.annualPkmMillion(), fastMetric.annualPkmMillion()), bauFkm,
                fastFkm, difference(bauFkm, fastFkm), percentChange(bauFkm, fastFkm),
                comparisonStatus(bauMetric, fastMetric,
                        "TOTAL".equals(mode)
                                ? "Fkm is not aggregated across vehicle units"
                                : null));
    }

    private static String comparisonStatus(CostMetric bau, CostMetric fast, String qualification) {
        String result = "BAU: " + bau.qualityStatus() + " | Fast Track: "
                + fast.qualityStatus();
        return qualification == null ? result : result + " | " + qualification;
    }

    private static String comparisonUnit(CostMetric bau, CostMetric fast) {
        return bau.unit().equals(fast.unit()) ? bau.unit()
                : "BAU: " + bau.unit() + " | Fast Track: " + fast.unit();
    }

    private static CostMetric totalOrMode(CostInputs cost, String mode) {
        if (!"TOTAL".equals(mode)) return cost.metrics().get(mode);
        double daily = cost.metrics().get("car").dailyPkm() + cost.metrics().get("walk").dailyPkm()
                + cost.metrics().get("bike").dailyPkm() + cost.metrics().get("pt").dailyPkm();
        return new CostMetric(daily, null, daily * DAYS_PER_YEAR / 1_000_000.0, null, null,
                "person-km; no all-mode Fkm", "PT subtotal excluded", "TOTAL_NO_PT_DOUBLE_COUNT");
    }

    private static Double percentChange(Double bau, Double fast) {
        if (bau == null || fast == null || bau == 0.0) return null;
        return (fast - bau) * 100.0 / bau;
    }

    private static Double difference(Double bau, Double fast) {
        return bau == null || fast == null ? null : fast - bau;
    }

    static void writeWorkbook(Path file, Comparison comparison) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
            writeSummarySheet(workbook, comparison);
            writePtSheet(workbook, comparison);
            writeAuditSheet(workbook, comparison);
            writeDefinitionsSheet(workbook, comparison);
            workbook.write(output);
        }
    }

    private static void writeSummarySheet(XSSFWorkbook workbook, Comparison comparison) {
        var sheet = workbook.createSheet("Main_Mode_Summary");
        Styles styles = Styles.create(workbook);
        sheet.setDisplayGridlines(false);
        put(sheet.createRow(1), 0, "Territorial transport performance comparison: BAU 2040 and Fast Track 2040", styles.title());
        put(sheet.createRow(2), 0, "Technical weekday; annual values are annualized technical-weekday equivalents.", styles.note());
        String[] header = {"Mode", "BAU territorial expanded trips per technical weekday", "Fast Track territorial expanded trips per technical weekday", "Absolute trip change", "Relative trip change percent", "BAU trip-based modal share percent", "Fast Track trip-based modal share percent", "Modal-share change in percentage points", "BAU territorial annualized Pkm million", "Fast Track territorial annualized Pkm million", "Pkm absolute change million", "Pkm relative change percent", "BAU territorial annualized Fkm million", "Fast Track territorial annualized Fkm million", "Fkm absolute change million", "Fkm relative change percent", "Data coverage status"};
        writeHeader(sheet.createRow(4), header, styles);
        int rowIndex = 5;
        for (String mode : List.of("car", "pt", "bike", "walk", "TOTAL")) {
            SummaryRow value = summaryRow(comparison, mode);
            Row row = sheet.createRow(rowIndex++);
            put(row, 0, value.mode(), styles.text());
            put(row, 1, value.bauTrips(), styles.number()); put(row, 2, value.fastTrips(), styles.number());
            put(row, 3, value.tripChange(), styles.number()); putOptional(row, 4, value.relativeTripChange(), styles.percent());
            put(row, 5, value.bauShare(), styles.percent()); put(row, 6, value.fastShare(), styles.percent());
            put(row, 7, value.shareChange(), styles.percent()); put(row, 8, value.bauPkm(), styles.number());
            put(row, 9, value.fastPkm(), styles.number()); putOptional(row, 10, value.pkmAbsoluteChange(), styles.number());
            putOptional(row, 11, value.pkmRelativeChange(), styles.percent());
            putOptional(row, 12, value.bauFkm(), styles.number()); putOptional(row, 13, value.fastFkm(), styles.number());
            putOptional(row, 14, value.fkmAbsoluteChange(), styles.number());
            putOptional(row, 15, value.fkmRelativeChange(), styles.percent()); put(row, 16, value.status(), styles.text());
        }
        finishTable(sheet, 4, rowIndex - 1, header.length, styles);
    }

    private static void writePtSheet(XSSFWorkbook workbook, Comparison comparison) {
        var sheet = workbook.createSheet("PT_Detail"); Styles styles = Styles.create(workbook); sheet.setDisplayGridlines(false);
        put(sheet.createRow(1), 0, "Territorial public transport detail", styles.title());
        put(sheet.createRow(2), 0, "Pkm are demand-expanded once. Fkm are full-service supply at factor 1.", styles.note());
        String[] header = {"PT route mode", "Vehicle/distance unit", "BAU annualized Pkm million", "Fast Track annualized Pkm million", "Pkm absolute change million", "Pkm relative change percent", "BAU full-service annualized Fkm million", "Fast Track full-service annualized Fkm million", "Fkm absolute change million", "Fkm relative change percent", "BAU average occupancy", "Fast Track average occupancy", "Occupancy absolute change", "Data coverage status"};
        writeHeader(sheet.createRow(4), header, styles); int rowIndex = 5;
        for (String mode : List.of("bus", "tram", "subway", "rail", "pt")) {
            CostMetric bau = comparison.bau().cost().metrics().get(mode); CostMetric fast = comparison.fast().cost().metrics().get(mode);
            Row row = sheet.createRow(rowIndex++); put(row, 0, "pt".equals(mode) ? "PT total" : mode, styles.text());
            put(row, 1, comparisonUnit(bau, fast), styles.text());
            put(row, 2, bau.annualPkmMillion(), styles.number()); put(row, 3, fast.annualPkmMillion(), styles.number()); putOptional(row, 4, difference(bau.annualPkmMillion(), fast.annualPkmMillion()), styles.number()); putOptional(row, 5, percentChange(bau.annualPkmMillion(), fast.annualPkmMillion()), styles.percent());
            putOptional(row, 6, bau.annualFkmMillion(), styles.number()); putOptional(row, 7, fast.annualFkmMillion(), styles.number()); putOptional(row, 8, difference(bau.annualFkmMillion(), fast.annualFkmMillion()), styles.number()); putOptional(row, 9, percentChange(bau.annualFkmMillion(), fast.annualFkmMillion()), styles.percent());
            putOptional(row, 10, bau.occupancy(), styles.number()); putOptional(row, 11, fast.occupancy(), styles.number()); putOptional(row, 12, difference(bau.occupancy(), fast.occupancy()), styles.number());
            put(row, 13, comparisonStatus(bau, fast, "pt".equals(mode)
                    ? "Mixed vehicle-unit subtotal; do not use this occupancy for costing" : null), styles.text());
        }
        finishTable(sheet, 4, rowIndex - 1, header.length, styles);
    }

    private static void writeAuditSheet(XSSFWorkbook workbook, Comparison comparison) {
        var sheet = workbook.createSheet("Trip_Scope_Audit"); Styles styles = Styles.create(workbook); sheet.setDisplayGridlines(false);
        put(sheet.createRow(1), 0, "Territorial main-trip scope audit", styles.title());
        String[] header = {"Scenario", "Main mode", "Trip scope", "Final selected-plan main trips", "Sample territorial main trips", "Expanded territorial main trips per technical weekday", "Excluded main trips for mode", "Unresolved route geometry", "Invalid coordinates", "Missing distance measurements", "Zero-distance routes", "Unexpected main modes", "Unexpected PT route modes", "Final-iteration stuck events", "Final-iteration relevant stuck events"};
        writeHeader(sheet.createRow(3), header, styles); int rowIndex = 4;
        for (ScenarioResult result : List.of(comparison.bau(), comparison.fast())) for (String mode : MODES) for (Scope scope : Scope.values()) {
            Row row = sheet.createRow(rowIndex++); put(row, 0, result.definition().scenarioId(), styles.text()); put(row, 1, mode, styles.text()); put(row, 2, scope.toString(), styles.text());
            long count = result.audit().included(mode, scope);
            put(row, 3, result.audit().totalMainTrips(), styles.number());
            put(row, 4, count, styles.number());
            put(row, 5, Production2040AnalysisSpec.expanded(count), styles.number());
            put(row, 6, result.audit().excludedByMainMode().getOrDefault(mode, 0L), styles.number());
            put(row, 7, result.audit().fatalIssues().size(), styles.number());
            put(row, 8, result.audit().invalidCoordinates(), styles.number());
            put(row, 9, result.audit().missingDistanceMeasurements(), styles.number());
            put(row, 10, result.audit().zeroDistanceRoutes(), styles.number());
            put(row, 11, mapText(result.audit().unexpectedMainModes()), styles.text());
            put(row, 12, mapText(result.audit().unexpectedPtRouteModes()), styles.text());
            put(row, 13, result.files().stuckTotals().get(Production2040AnalysisSpec.LAST_ITERATION).events(), styles.number());
            put(row, 14, result.files().stuckTotals().get(Production2040AnalysisSpec.LAST_ITERATION).relevantEvents(), styles.number());
        }
        finishTable(sheet, 3, rowIndex - 1, header.length, styles);
    }

    private static void writeDefinitionsSheet(XSSFWorkbook workbook, Comparison comparison) {
        var sheet = workbook.createSheet("Definitions");
        Styles styles = Styles.create(workbook);
        sheet.setDisplayGridlines(false);
        put(sheet.createRow(1), 0, "Definitions and sources", styles.title());
        String[] header = {"Topic", "Definition or source"};
        writeHeader(sheet.createRow(3), header, styles);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Scenario and run IDs", "BAU 2040: "
                + comparison.bau().definition().contract().runId() + "; Fast Track 2040: "
                + comparison.fast().definition().contract().runId() + "."});
        rows.add(new String[]{"Final iteration and selected plans", "Iteration "
                + Production2040AnalysisSpec.LAST_ITERATION + "; BAU: "
                + sourcePath(comparison.bau().files().plans()) + "; Fast Track: "
                + sourcePath(comparison.fast().files().plans())
                + ". Only the root final selected-plan file is read; no ITERS copy is used."});
        rows.add(new String[]{"Networks and transit schedules", "BAU network: "
                + sourcePath(comparison.bau().files().network()) + "; BAU schedule: "
                + sourcePath(comparison.bau().files().schedule()) + "; Fast Track network: "
                + sourcePath(comparison.fast().files().network()) + "; Fast Track schedule: "
                + sourcePath(comparison.fast().files().schedule()) + "."});
        rows.add(new String[]{"Production-output gate", "Both scenarios passed the existing normal-shutdown, run identity, output-config, protected-input, accounting-scope, and PT-accounting validators before this comparison began."});
        rows.add(new String[]{"Territorial rule", "A main trip is included only when its analysis main mode has more than "
                + number(POSITIVE_TERRITORIAL_DISTANCE_TOLERANCE_METRES)
                + " meters of reconstructed modeled distance inside the Munich municipal boundary. There is no BOTH_INSIDE, resident, home-activity, origin, or destination filter."});
        rows.add(new String[]{"Trip construction and classification", "MATSim TripStructureUtils with stage-activity handling and the standard analysis main-mode identifier. INTERNAL, INBOUND, OUTBOUND, and THROUGH are assigned from final main-activity endpoints only after positive territorial main-mode movement is established."});
        rows.add(new String[]{"Trip denominator", "Expanded territorially active car, pt, bike, and walk main trips. The mode shares are main-trip shares and sum to 100 percent within the stated numerical tolerance."});
        rows.add(new String[]{"Car territorial reconstruction", "Each final selected-plan car NetworkRoute is clipped link by link with the established municipal geometry. Missing links or non-NetworkRoute car legs fail closed."});
        rows.add(new String[]{"PT territorial reconstruction", "Only in-vehicle TransitPassengerRoute segments qualify a pt main trip. Access, egress, and transfer walking alone cannot qualify pt. Each transfer segment is reconstructed against its scheduled transit route and clipped link by link."});
        rows.add(new String[]{"Walk and bike reconstruction", "The validated output configurations retain walk, bike, and non_network_walk as teleported modes. Each relevant leg uses modeled distance multiplied by the inside fraction of its own straight endpoint segment; no second beeline correction is applied."});
        rows.add(new String[]{"Active-mode routing configurations", "BAU network modes="
                + String.join(",", comparison.bau().activeRouting().networkModes())
                + "; Fast Track network modes="
                + String.join(",", comparison.fast().activeRouting().networkModes()) + "."});
        rows.add(new String[]{"Territorial Pkm", "Transferred from the validated territorial cost-input workbooks, whose Inputs!A5:I14 numeric transfer tables are parsed and reconciled before publication. Pkm are assigned to physical movement modes; PT access, egress, and transfer walking is walking Pkm, not pt Pkm."});
        rows.add(new String[]{"Territorial Fkm", "Transferred from the validated territorial cost-input workbooks. Car and bike Fkm use demand expansion exactly once. PT Fkm is full territorial service supply at factor 1 and is never multiplied by 20. Walking Fkm is not applicable."});
        rows.add(new String[]{"PT occupancy and vehicle units", "The producer's exact occupancy status is preserved in PT_Detail, including PASS_REPORTED_FOR_REVIEW where applicable. PT total is a mixed-vehicle-unit subtotal; no train-to-carriage or external-workbook unit conversion is inferred."});
        rows.add(new String[]{"PT pseudolink treatment", "The transferred PT Pkm and Fkm retain the validated source workbook's documented point-anchored treatment of positive-model-length links with zero geometric length. This comparison does not recompute or reallocate that source accounting."});
        rows.add(new String[]{"Demand scaling", "The private 5-percent demand sample is expanded exactly once by 20 for main-trip counts and demand-based Pkm. No user-applied additional factor 20 is required."});
        rows.add(new String[]{"Reporting day and annualization", "Daily values refer to a technical weekday. Annual values are mechanical annualized technical-weekday equivalents: daily value × 365 / 1,000,000. They are not observed annual traffic."});
        rows.add(new String[]{"Simulation-horizon qualification", "The final event horizon can extend beyond 24 hours and is one simulated reporting day, not a sequence of independent daily observations."});
        rows.add(new String[]{"Stuck and incomplete evidence", stuckQualification(comparison.bau())
                + " " + stuckQualification(comparison.fast())
                + " No untraveled route remainder is inferred."});
        rows.add(new String[]{"Boundary", comparison.boundary().source() + " | "
                + comparison.boundary().crs() + " | canonical SHA-256 "
                + comparison.boundary().sha256() + "."});
        rows.add(new String[]{"BAU territorial cost-input workbook", sourcePath(comparison.bau().cost().workbook())});
        rows.add(new String[]{"Fast Track territorial cost-input workbook", sourcePath(comparison.fast().cost().workbook())});
        rows.add(new String[]{"Calibration-scope limitation", "The existing BOTH_INSIDE analysis versus resident-target calibration mismatch remains a limitation; this additional territorial comparison neither replaces nor recalibrates that scope."});
        rows.add(new String[]{"Outside this comparison's scope", "Existing BOTH_INSIDE trip-modal-share statistics and resident population counts are not transferred. Fixed noise and infrastructure costs require separate assumptions. The comparison covers modeled car, pt, bike, and walk, not all transport in Munich."});
        int index = 4;
        for (String[] row : rows) {
            Row value = sheet.createRow(index++);
            put(value, 0, row[0], styles.text());
            put(value, 1, row[1], styles.text());
        }
        finishTable(sheet, 3, index - 1, header.length, styles);
        sheet.setColumnWidth(0, 38 * 256);
        sheet.setColumnWidth(1, 120 * 256);
    }

    private static String sourcePath(Path path) {
        return Production2040Contract.projectPath(path);
    }

    private static String stuckQualification(ScenarioResult result) {
        ValidateProduction2040AnalysisOutput.StuckTotal stuck = result.files().stuckTotals().get(
                Production2040AnalysisSpec.LAST_ITERATION);
        return result.definition().scenarioId() + " final-iteration stuck events=" + stuck.events()
                + ", relevant stuck events=" + stuck.relevantEvents() + ".";
    }

    private static void finishTable(org.apache.poi.ss.usermodel.Sheet sheet, int headerRow,
            int lastRow, int columns, Styles styles) {
        sheet.createFreezePane(1, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, lastRow, 0, columns - 1));
        for (int column = 0; column < columns; column++) {
            int width = column == 0 ? 28 : 18;
            if (column == columns - 1) width = 48;
            sheet.setColumnWidth(column, width * 256);
        }
    }
    private static void writeHeader(Row row, String[] header, Styles styles) { for (int index = 0; index < header.length; index++) put(row, index, header[index], styles.header()); }
    private static void put(Row row, int column, String value, CellStyle style) { Cell cell = row.createCell(column); cell.setCellValue(value); cell.setCellStyle(style); }
    private static void put(Row row, int column, double value, CellStyle style) { Cell cell = row.createCell(column); cell.setCellValue(value); cell.setCellStyle(style); }
    private static void putOptional(Row row, int column, Double value, CellStyle style) { if (value == null) put(row, column, "N/A", style); else put(row, column, value, style); }

    private static void validatePublished(Path directory, Comparison comparison) throws IOException {
        Set<String> expected = Set.of("territorial_mode_comparison_2040.xlsx", "territorial_main_mode_summary.csv", "territorial_pt_detail.csv", "territorial_trip_scope_audit.csv", "territorial_quality_checks.csv", "territorial_methodology.md");
        try (var stream = Files.list(directory)) { Production2040AnalysisSpec.require(stream.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected), "Territorial comparison output set is incomplete or contains an unexpected file"); }
        validateWorkbook(directory.resolve("territorial_mode_comparison_2040.xlsx"), comparison);
        String quality = Files.readString(directory.resolve("territorial_quality_checks.csv"));
        Production2040AnalysisSpec.require(!quality.contains(",FAIL,"), "Comparison quality report contains FAIL");
    }

    static void validateWorkbook(Path workbookPath, Comparison comparison) throws IOException {
        Production2040AnalysisSpec.require(Files.isRegularFile(workbookPath),
                "Territorial comparison workbook was not written");
        try (InputStream input = Files.newInputStream(workbookPath);
                Workbook workbook = WorkbookFactory.create(input)) {
            Production2040AnalysisSpec.require(workbook.getNumberOfSheets() == 4
                            && workbook.getSheet("Main_Mode_Summary") != null
                            && workbook.getSheet("PT_Detail") != null
                            && workbook.getSheet("Trip_Scope_Audit") != null
                            && workbook.getSheet("Definitions") != null,
                    "Comparison workbook has an unexpected sheet set");
            var summary = workbook.getSheet("Main_Mode_Summary");
            int rowIndex = 5;
            for (String mode : List.of("car", "pt", "bike", "walk", "TOTAL")) {
                SummaryRow expected = summaryRow(comparison, mode);
                Row row = summary.getRow(rowIndex++);
                requireTextCell(row.getCell(0), expected.mode(), "summary mode");
                requireNumericCell(row.getCell(1), expected.bauTrips(), "summary BAU trips");
                requireNumericCell(row.getCell(2), expected.fastTrips(), "summary Fast Track trips");
                requireNumericCell(row.getCell(3), expected.tripChange(), "summary trip change");
                requireOptionalNumericCell(row.getCell(4), expected.relativeTripChange(),
                        "summary relative trip change");
                requireNumericCell(row.getCell(5), expected.bauShare(), "summary BAU share");
                requireNumericCell(row.getCell(6), expected.fastShare(), "summary Fast Track share");
                requireNumericCell(row.getCell(7), expected.shareChange(), "summary share change");
                requireNumericCell(row.getCell(8), expected.bauPkm(), "summary BAU Pkm");
                requireNumericCell(row.getCell(9), expected.fastPkm(), "summary Fast Track Pkm");
                requireOptionalNumericCell(row.getCell(10), expected.pkmAbsoluteChange(),
                        "summary Pkm absolute change");
                requireOptionalNumericCell(row.getCell(11), expected.pkmRelativeChange(),
                        "summary Pkm relative change");
                requireOptionalNumericCell(row.getCell(12), expected.bauFkm(), "summary BAU Fkm");
                requireOptionalNumericCell(row.getCell(13), expected.fastFkm(),
                        "summary Fast Track Fkm");
                requireOptionalNumericCell(row.getCell(14), expected.fkmAbsoluteChange(),
                        "summary Fkm absolute change");
                requireOptionalNumericCell(row.getCell(15), expected.fkmRelativeChange(),
                        "summary Fkm relative change");
                requireNonblankTextCell(row.getCell(16), "summary data coverage status");
            }
            var pt = workbook.getSheet("PT_Detail");
            rowIndex = 5;
            for (String mode : List.of("bus", "tram", "subway", "rail", "pt")) {
                CostMetric bau = comparison.bau().cost().metrics().get(mode);
                CostMetric fast = comparison.fast().cost().metrics().get(mode);
                Row row = pt.getRow(rowIndex++);
                requireTextCell(row.getCell(0), "pt".equals(mode) ? "PT total" : mode,
                        "PT detail mode");
                requireTextCell(row.getCell(1), comparisonUnit(bau, fast), "PT detail unit");
                requireNumericCell(row.getCell(2), bau.annualPkmMillion(), "PT detail BAU Pkm");
                requireNumericCell(row.getCell(3), fast.annualPkmMillion(),
                        "PT detail Fast Track Pkm");
                requireOptionalNumericCell(row.getCell(4), difference(bau.annualPkmMillion(),
                        fast.annualPkmMillion()), "PT detail Pkm absolute change");
                requireOptionalNumericCell(row.getCell(5), percentChange(bau.annualPkmMillion(),
                        fast.annualPkmMillion()), "PT detail Pkm relative change");
                requireOptionalNumericCell(row.getCell(6), bau.annualFkmMillion(),
                        "PT detail BAU Fkm");
                requireOptionalNumericCell(row.getCell(7), fast.annualFkmMillion(),
                        "PT detail Fast Track Fkm");
                requireOptionalNumericCell(row.getCell(8), difference(bau.annualFkmMillion(),
                        fast.annualFkmMillion()), "PT detail Fkm absolute change");
                requireOptionalNumericCell(row.getCell(9), percentChange(bau.annualFkmMillion(),
                        fast.annualFkmMillion()), "PT detail Fkm relative change");
                requireOptionalNumericCell(row.getCell(10), bau.occupancy(),
                        "PT detail BAU occupancy");
                requireOptionalNumericCell(row.getCell(11), fast.occupancy(),
                        "PT detail Fast Track occupancy");
                requireOptionalNumericCell(row.getCell(12), difference(bau.occupancy(),
                        fast.occupancy()), "PT detail occupancy change");
                requireNonblankTextCell(row.getCell(13), "PT detail data coverage status");
            }
            for (int sheet = 0; sheet < workbook.getNumberOfSheets(); sheet++) {
                for (Row row : workbook.getSheetAt(sheet)) {
                    for (Cell cell : row) Production2040AnalysisSpec.require(
                            cell.getCellType() != CellType.FORMULA,
                            "Comparison workbook must not depend on formulas");
                }
            }
        }
        try (ZipFile zip = new ZipFile(workbookPath.toFile())) {
            Production2040AnalysisSpec.require(zip.stream().noneMatch(entry -> entry.getName()
                            .startsWith("xl/externalLinks/")),
                    "Comparison workbook contains an external workbook link");
        }
    }

    private static void requireNumericCell(Cell cell, double expected, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.NUMERIC,
                "Comparison workbook " + label + " is not a numeric Excel cell");
        requireClose(cell.getNumericCellValue(), expected,
                "Comparison workbook numeric value for " + label);
    }

    private static void requireOptionalNumericCell(Cell cell, Double expected, String label) {
        if (expected == null) {
            requireTextCell(cell, "N/A", label + " applicability");
        } else requireNumericCell(cell, expected, label);
    }

    private static void requireTextCell(Cell cell, String expected, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.STRING
                        && expected.equals(cell.getStringCellValue()),
                "Comparison workbook " + label + " is missing or differs");
    }

    private static void requireNonblankTextCell(Cell cell, String label) {
        Production2040AnalysisSpec.require(cell != null && cell.getCellType() == CellType.STRING
                        && !cell.getStringCellValue().isBlank(),
                "Comparison workbook " + label + " is not nonblank text");
    }

    private static void writeCsv(Path file, String content) throws IOException { Files.writeString(file, content, StandardCharsets.UTF_8); }
    private static void deleteTemporary(Path path) { try (var stream = Files.walk(path)) { stream.sorted(Comparator.reverseOrder()).forEach(value -> { try { Files.deleteIfExists(value); } catch (IOException ignored) { } }); } catch (IOException ignored) { } }
    private static void check(StringBuilder csv, String scenario, String check, String status, String detail) { csv.append(csv(scenario)).append(',').append(csv(check)).append(',').append(csv(status)).append(',').append(csv(detail)).append('\n'); }
    private static String csv(String value) { String escaped = value.replace("\"", "\"\""); return escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0 || escaped.indexOf('\n') >= 0 ? '"' + escaped + '"' : escaped; }
    private static String number(double value) { Production2040AnalysisSpec.require(Double.isFinite(value), "Non-finite output value"); return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String optionalNumber(Double value) { return value == null ? "NOT_APPLICABLE" : number(value); }
    private static String mapText(Map<String, Long> values) { return values.isEmpty() ? "NONE" : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(value -> value.getKey() + "=" + value.getValue()).reduce((left, right) -> left + ";" + right).orElseThrow(); }
    private static void requireClose(double actual, double expected, String label) { Production2040AnalysisSpec.require(Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual - expected) <= 1e-9, label + " does not reconcile: actual=" + actual + " expected=" + expected); }

    enum Scope { INTERNAL, INBOUND, OUTBOUND, THROUGH }
    record CostMetric(double dailyPkm, Double dailyFkm, double annualPkmMillion,
                      Double annualFkmMillion, Double occupancy, String unit,
                      String calculationBasis, String qualityStatus) { }
    record CostInputs(Path workbook, Map<String, CostMetric> metrics) { }
    record ScenarioResult(Production2040AnalysisSpec.ScenarioDefinition definition,
                          ValidateProduction2040AnalysisOutput.ValidatedOutput files,
                          AnalyzeProduction2040TerritorialCostInputs.ActiveRoutingDefinition
                                  activeRouting,
                          TripAudit audit, CostInputs cost) { }
    record Comparison(ScenarioResult bau, ScenarioResult fast, MunichMunicipalBoundary boundary) { }
    record SummaryRow(String mode, double bauTrips, double fastTrips, double tripChange,
                      Double relativeTripChange, double bauShare, double fastShare,
                      double shareChange, double bauPkm, double fastPkm,
                      Double pkmAbsoluteChange, Double pkmRelativeChange, Double bauFkm,
                      Double fastFkm, Double fkmAbsoluteChange, Double fkmRelativeChange,
                      String status) { }

    /**
     * Scenario-local cache for immutable territorial geometry results. MATSim 2025's
     * {@link StreamingPopulationReader} invokes its {@code PersonAlgorithm} callbacks
     * synchronously while adding each person, and this analyzer introduces no parallel
     * processing. Ordinary HashMaps and counters therefore preserve the reader's serial
     * aggregation semantics. A cache is constructed in each {@link #readScenario} call;
     * it is never shared between BAU and Fast Track.
     */
    static final class TerritorialGeometryCache {
        private final Network network;
        private final MunichMunicipalBoundary boundary;
        private final Map<Id<Link>, Double> territorialLinkMetres = new HashMap<>();
        private final Map<PtSegmentKey, Double> territorialPtSegmentMetres = new HashMap<>();
        private long uniqueLinkClippingCalculations;
        private long linkCacheHits;
        private long linkCacheMisses;
        private long ptSegmentCacheHits;
        private long ptSegmentCacheMisses;

        TerritorialGeometryCache(Network network, MunichMunicipalBoundary boundary) {
            this.network = Objects.requireNonNull(network, "network");
            this.boundary = Objects.requireNonNull(boundary, "boundary");
        }

        MunichMunicipalBoundary boundary() {
            return boundary;
        }

        void requireCompatible(Network expectedNetwork, MunichMunicipalBoundary expectedBoundary) {
            Production2040AnalysisSpec.require(network == expectedNetwork && boundary == expectedBoundary,
                    "Territorial geometry cache belongs to another scenario network or boundary");
        }

        double territorialLinkDistance(Id<Link> linkId, String context, String key) {
            Double cached = territorialLinkMetres.get(linkId);
            if (cached != null) {
                linkCacheHits++;
                return cached;
            }
            linkCacheMisses++;
            Link link = network.getLinks().get(linkId);
            if (link == null) throw new IllegalStateException(context + " references missing link "
                    + linkId);
            uniqueLinkClippingCalculations++;
            Production2040AccountingEventMetrics.LinkClip clip =
                    Production2040AccountingEventMetrics.clip(link, boundary);
            double fraction = clip.insideFraction();
            if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0
                    || !Double.isFinite(clip.modelLinkMetres())
                    || clip.modelLinkMetres() < 0.0) {
                throw new IllegalStateException(context + " has invalid territorial clipping on "
                        + linkId + " for " + key);
            }
            double territorialMetres = clip.modelLinkMetres() * fraction;
            territorialLinkMetres.put(linkId, territorialMetres);
            return territorialMetres;
        }

        double territorialPtSegment(PtSegmentKey key, TransitRoute transitRoute,
                TransitPassengerRoute passengerRoute, String diagnosticKey) {
            Double cached = territorialPtSegmentMetres.get(key);
            if (cached != null) {
                ptSegmentCacheHits++;
                return cached;
            }
            ptSegmentCacheMisses++;
            double territorialMetres = calculateClippedTransitSegment(transitRoute,
                    passengerRoute, this, diagnosticKey);
            territorialPtSegmentMetres.put(key, territorialMetres);
            return territorialMetres;
        }

        GeometryCacheDiagnostics diagnostics() {
            return new GeometryCacheDiagnostics(uniqueLinkClippingCalculations, linkCacheHits,
                    linkCacheMisses, ptSegmentCacheHits, ptSegmentCacheMisses);
        }
    }

    record PtSegmentKey(Id<TransitLine> lineId, Id<TransitRoute> routeId,
                        Id<TransitStopFacility> accessStopId,
                        Id<TransitStopFacility> egressStopId) { }

    record GeometryCacheDiagnostics(long uniqueLinkClippingCalculations, long linkCacheHits,
                                    long linkCacheMisses, long ptSegmentCacheHits,
                                    long ptSegmentCacheMisses) { }

    static final class TripAudit {
        private final Map<String, Map<Scope, Long>> included = new TreeMap<>();
        private final Map<String, Long> excludedByMainMode = new TreeMap<>();
        private final Map<String, Long> unexpectedMainModes = new TreeMap<>();
        private final Map<String, Long> unexpectedPtRouteModes = new TreeMap<>();
        private final List<String> fatalIssues = new ArrayList<>();
        private long processedPersons;
        private long totalMainTrips;
        private long invalidCoordinates;
        private long missingDistanceMeasurements;
        private long zeroDistanceRoutes;

        private TripAudit() { for (String mode : MODES) { Map<Scope, Long> values = new EnumMap<>(Scope.class); for (Scope scope : Scope.values()) values.put(scope, 0L); included.put(mode, values); excludedByMainMode.put(mode, 0L); } }
        private void incrementIncluded(String mode, Scope scope) { included.get(mode).merge(scope, 1L, Long::sum); }
        long processedPersons() { return processedPersons; }
        long totalMainTrips() { return totalMainTrips; }
        long included(String mode) { return included.get(mode).values().stream().mapToLong(Long::longValue).sum(); }
        long included(String mode, Scope scope) { return included.get(mode).get(scope); }
        long includedTotal() { return MODES.stream().mapToLong(this::included).sum(); }
        double shareSum() { long total = includedTotal(); return MODES.stream().mapToDouble(mode -> Production2040AnalysisSpec.percent(included(mode), total)).sum(); }
        private void addFatal(String type, String mode, String routeType, String detail) { fatalIssues.add(type + " mode=" + mode + " route_type=" + routeType + " detail=" + detail); }
        private void requireNoFatalGeometry() { Production2040AnalysisSpec.require(fatalIssues.isEmpty(), "Territorial main-trip route reconstruction failed: " + String.join(" | ", fatalIssues)); }
        TripAudit freeze() { return this; }
        Map<String, Long> excludedByMainMode() { return Map.copyOf(excludedByMainMode); }
        Map<String, Long> unexpectedMainModes() { return Map.copyOf(unexpectedMainModes); }
        Map<String, Long> unexpectedPtRouteModes() { return Map.copyOf(unexpectedPtRouteModes); }
        List<String> fatalIssues() { return List.copyOf(fatalIssues); }
        long invalidCoordinates() { return invalidCoordinates; }
        long missingDistanceMeasurements() { return missingDistanceMeasurements; }
        long zeroDistanceRoutes() { return zeroDistanceRoutes; }
    }

    private record Styles(CellStyle title, CellStyle note, CellStyle header, CellStyle text,
                          CellStyle number, CellStyle percent) {
        private static Styles create(XSSFWorkbook workbook) {
            XSSFFont body = workbook.createFont();
            body.setFontName("Arial");
            body.setFontHeightInPoints((short) 10);
            XSSFFont titleFont = workbook.createFont();
            titleFont.setFontName("Arial");
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            XSSFFont noteFont = workbook.createFont();
            noteFont.setFontName("Arial");
            noteFont.setItalic(true);
            noteFont.setFontHeightInPoints((short) 10);
            XSSFFont headerFont = workbook.createFont();
            headerFont.setFontName("Arial");
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headerFont.setFontHeightInPoints((short) 10);
            XSSFCellStyle title = workbook.createCellStyle();
            title.setFont(titleFont);
            XSSFCellStyle note = workbook.createCellStyle();
            note.setFont(noteFont);
            XSSFCellStyle header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(
                    new byte[]{31, 78, 121}, null));
            header.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            header.setWrapText(true);
            XSSFCellStyle text = workbook.createCellStyle();
            text.setFont(body);
            text.setWrapText(true);
            XSSFCellStyle number = workbook.createCellStyle();
            number.setFont(body);
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            XSSFCellStyle percent = workbook.createCellStyle();
            percent.setFont(body);
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            return new Styles(title, note, header, text, number, percent);
        }
    }
}
