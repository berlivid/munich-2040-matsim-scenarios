package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Plans and, after all decisions have been approved, builds the Fast Track
 * GTFS-2037 scenario feed. The BAU feed containing the shared measures is
 * always treated as the baseline. Large GTFS tables are streamed and only compact metadata for
 * relevant routes, trips and stops is retained in memory.
 *
 * <p>{@code --analyze} always writes a reproducible preflight report and does
 * not alter a GTFS feed. {@code --build} repeats the same checks and refuses
 * to create a ZIP while any critical station, coordinate or service-start
 * decision is unresolved.</p>
 */
public final class BuildFastTrackGtfs2037 {

    private static final Path BASE_ZIP = Path.of(
            "original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_bau.zip"
    );
    private static final Path WORKBOOK = Path.of(
            "original-input-data/mvv_gtfs_2037/Infrastructure_measures.xlsx"
    );
    private static final Path SERVICE_SPEC = Path.of(
            "original-input-data/mvv_gtfs_2037/fast_track_service_specification.csv"
    );
    private static final Path STOP_DECISIONS = Path.of(
            "original-input-data/mvv_gtfs_2037/fast_track_stop_decisions.csv"
    );
    private static final Path PREFLIGHT = Path.of(
            "original-input-data/mvv_gtfs_2037/generated/fast_track_preflight"
    );
    private static final Path OUTPUT_ZIP = Path.of(
            "original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip"
    );
    private static final Path BUILD_REPORT = Path.of(
            "docs/gtfs2040/gtfs2037_fast_track_build_report.md"
    );
    private static final List<String> GTFS_FILES = List.of(
            "agency.txt", "calendar.txt", "routes.txt", "trips.txt",
            "stop_times.txt", "stops.txt", "shapes.txt", "transfers.txt"
    );
    private static final long ZIP_TIME =
            Instant.parse("1980-01-01T00:00:00Z").toEpochMilli();
    private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-02-13");
    private static final String U2 = "MUC_U2_neu Prognose";
    private static final String U3 = "MUC_U3_neu Prognose";
    private static final String U4 = "MUC_U4_neu Prognose";
    private static final String U6 = "MUC_U6_neu Prognose";
    private static final String S1 =
            "S1_Prognose_Ebersberg/Leuchtenbergring-Schwaigerlohe/Freising";
    private static final String S2 =
            "S2_Prognose_Petershausen/Altomünster-Holzkirchen";
    private static final String S2_BASE = "162456_109";
    private static final String S4 = "S4_Prognose_Geltendorf-Grafing";
    private static final String S8 = "S8_Prognose_Herrsching-Schwaigerlohe";

    private BuildFastTrackGtfs2037() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1
                || !("--analyze".equals(args[0]) || "--build".equals(args[0]))) {
            throw new IllegalArgumentException(
                    "Use exactly one mode: --analyze or --build"
            );
        }
        boolean build = "--build".equals(args[0]);
        Processor processor = new Processor();
        Analysis analysis = processor.analyze();
        processor.writePreflight(analysis);
        processor.printSummary(analysis);
        if (!build) {
            System.out.println("Analyze mode completed; no GTFS feed was changed.");
            return;
        }
        if (!analysis.blockers().isEmpty()) {
            throw new IllegalStateException(
                    "Fast Track build deliberately stopped: "
                            + analysis.blockers().size()
                            + " critical decisions remain unresolved. See "
                            + PREFLIGHT.resolve("unresolved_stops.csv")
                            + " and " + PREFLIGHT.resolve("preflight_report.md")
            );
        }
        processor.build(analysis);
    }

    private static final class Processor {
        private final Map<String, ServiceSpec> services = new LinkedHashMap<>();
        private final Map<String, StopDecision> decisions = new LinkedHashMap<>();
        private final Map<String, Stop> stops = new HashMap<>();
        private final Map<String, Route> routes = new HashMap<>();
        private final Map<String, Trip> trips = new HashMap<>();
        private final Map<String, StopUsage> usageByParent = new HashMap<>();
        private final Map<PlatformKey, Set<String>> platforms = new HashMap<>();
        private final Map<String, List<Call>> comparisonTrips = new HashMap<>();
        private final Map<String, List<Integer>> endpointDepartures = new HashMap<>();
        private final List<String> sourceChecks = new ArrayList<>();

        Analysis analyze() throws Exception {
            requireInputs();
            readServiceSpecification();
            readStopDecisions();
            verifyWorkbookSource();

            try (ZipFile zip = new ZipFile(BASE_ZIP.toFile(), StandardCharsets.UTF_8)) {
                requireGtfsEntries(zip);
                readStops(zip);
                readRoutes(zip);
                readTrips(zip);
                scanStopTimes(zip);
            }

            List<StopMapping> mappings = resolveStops();
            List<String> blockers = new ArrayList<>();
            for (StopMapping mapping : mappings) {
                if (!mapping.resolved()) {
                    blockers.add(mapping.measureId() + " / " + mapping.logicalStop()
                            + ": " + mapping.status());
                }
            }
            for (ServiceSpec service : services.values()) {
                if (service.createsService()
                        && service.routeId().startsWith("FT_NR_")
                        && service.firstDeparture().isBlank()) {
                    blockers.add(service.measureId()
                            + ": first_departure is not approved in the service specification");
                } else if (service.createsService()
                        && service.routeId().startsWith("FT_NR_")) {
                    int finalDeparture = parseTime(service.firstDeparture())
                            + (service.departuresPerDirection() - 1)
                            * service.headwayMinutes() * 60;
                    if (service.headwayMinutes() != 30
                            || service.departuresPerDirection() != 40
                            || finalDeparture != parseTime("24:00:00")) {
                        blockers.add(service.measureId()
                                + ": approved regularized timetable must contain 40 "
                                + "departures per direction at 30-minute intervals and end "
                                + "at 24:00");
                    }
                }
            }

            List<ServicePlan> plans = createServicePlans(mappings, blockers);
            return new Analysis(
                    List.copyOf(mappings), List.copyOf(plans), List.copyOf(blockers),
                    Map.copyOf(stops), Map.copyOf(routes), Map.copyOf(trips),
                    deepCopyCalls(comparisonTrips)
            );
        }

        private void requireInputs() {
            for (Path input : List.of(BASE_ZIP, WORKBOOK, SERVICE_SPEC, STOP_DECISIONS)) {
                if (!Files.isRegularFile(input)) {
                    throw new IllegalStateException("Required input is missing: " + input);
                }
            }
        }

        private void requireGtfsEntries(ZipFile zip) {
            for (String file : GTFS_FILES) {
                if (zip.getEntry(file) == null) {
                    throw new IllegalStateException(
                            "Cleaned base ZIP is missing root entry " + file
                    );
                }
            }
        }

        private void readServiceSpecification() throws IOException {
            try (CsvFile table = new CsvFile(SERVICE_SPEC)) {
                String[] row;
                while ((row = table.next()) != null) {
                    ServiceSpec spec = new ServiceSpec(
                            table.get(row, "source_row"),
                            table.get(row, "measure_id"),
                            table.get(row, "route_id"),
                            table.get(row, "route_long_name"),
                            table.get(row, "action"),
                            table.get(row, "mode"),
                            parseInteger(table.get(row, "route_type"), "route_type"),
                            table.get(row, "agency_strategy"),
                            splitPipe(table.get(row, "stop_pattern")),
                            parseOptionalInteger(table.get(row, "headway_minutes")),
                            parseOptionalInteger(table.get(row, "departures_per_direction")),
                            table.get(row, "first_departure"),
                            splitPipe(table.get(row, "comparison_routes")),
                            table.get(row, "operation_rule"),
                            splitPipe(table.get(row, "excluded_stops")),
                            table.get(row, "source_fact"),
                            table.get(row, "model_assumption"),
                            Boolean.parseBoolean(table.get(row, "creates_gtfs_service")),
                            table.get(row, "deduplication_key"),
                            table.get(row, "source_trip_selection_rule"),
                            parseOptionalInteger(table.get(row,
                                    "intermediate_dwell_seconds")),
                            parseOptionalInteger(table.get(row, "origin_dwell_seconds")),
                            parseOptionalInteger(table.get(row, "terminal_dwell_seconds")),
                            table.get(row, "dwell_sensitivity_note")
                    );
                    if (services.put(spec.measureId(), spec) != null) {
                        throw new IllegalStateException(
                                "Duplicate measure_id in service specification: "
                                        + spec.measureId()
                        );
                    }
                }
            }
            Set<String> expected = Set.of(
                    "FT-NR-A", "FT-NR-B", "FT-NR-ENABLER", "FT-U9", "FT-U4-EXT"
            );
            if (!services.keySet().equals(expected)) {
                throw new IllegalStateException(
                        "Service specification does not contain exactly the expected measures: "
                                + services.keySet()
                );
            }
            ServiceSpec u9 = services.get("FT-U9");
            if (!"direction_id+first_stop_departure_time".equals(
                    u9.deduplicationKey())
                    || !"lexicographically_smallest_source_trip_id".equals(
                            u9.sourceTripSelectionRule())
                    || u9.intermediateDwellSeconds() != 20
                    || u9.originDwellSeconds() != 0
                    || u9.terminalDwellSeconds() != 0) {
                throw new IllegalStateException(
                        "FT-U9 requires the approved deterministic deduplication rule "
                                + "and 0/20/0-second origin/intermediate/terminal dwell."
                );
            }
            for (String measure : List.of("FT-NR-A", "FT-NR-B")) {
                ServiceSpec nordring = services.get(measure);
                if (nordring.intermediateDwellSeconds() != 0
                        || !nordring.dwellSensitivityNote().contains("60 seconds")) {
                    throw new IllegalStateException(
                            measure + " must retain zero dwell and document the future "
                                    + "60-second sensitivity test."
                    );
                }
            }
        }

        private void readStopDecisions() throws IOException {
            try (CsvFile table = new CsvFile(STOP_DECISIONS)) {
                String[] row;
                while ((row = table.next()) != null) {
                    StopDecision decision = new StopDecision(
                            table.get(row, "measure_id"),
                            table.get(row, "logical_stop"),
                            table.get(row, "planned_parent_id"),
                            table.get(row, "planned_direction_0_id"),
                            table.get(row, "planned_direction_1_id"),
                            table.get(row, "resolution_status"),
                            table.get(row, "existing_reference_stop_id"),
                            table.get(row, "stop_lat"),
                            table.get(row, "stop_lon"),
                            table.get(row, "coordinate_source"),
                            table.get(row, "assumption_strength"),
                            splitPipe(table.get(row, "transfer_target_stop_ids")),
                            parseOptionalInteger(table.get(
                                    row, "minimum_transfer_time_seconds"
                            )),
                            table.get(row, "decision_note")
                    );
                    String key = decisionKey(decision.measureId(), decision.logicalStop());
                    if (decisions.put(key, decision) != null) {
                        throw new IllegalStateException(
                                "Duplicate stop decision: " + key
                        );
                    }
                }
            }
        }

        private void verifyWorkbookSource() throws Exception {
            Map<String, String> cells = XlsxCells.read(WORKBOOK, "Maßnahmen");
            checkWorkbook(cells, "M14", "FT-NR-A", "FT-NR-B");
            checkWorkbook(cells, "N14", "Dachau", "Feldmoching", "Riem");
            checkWorkbook(cells, "O14", "30", "40");
            checkWorkbook(cells, "P14", "Gronsdorf", "Johanneskirchen", "Daglfing");
            checkWorkbook(cells, "M15", "S8", "FT-NR");
            checkWorkbook(cells, "O15", "existing S8 timetable is retained");
            checkWorkbook(cells, "R15", "enabling the Nordring service");
            checkWorkbook(cells, "M29", "U9");
            checkWorkbook(cells, "N29", "Esperantoplatz", "Harras");
            checkWorkbook(cells, "P29", "Theresienstraße");
            checkWorkbook(cells, "M30", "U4");
            checkWorkbook(cells, "N30", "Cosimapark", "Messestadt West");
            checkWorkbook(cells, "P30", "Fideliopark", "Pellegrinistraße");
            sourceChecks.add("Workbook sheet Maßnahmen and source cells M:R were read successfully.");
            sourceChecks.add("Rows 14, 15, 29 and 30 contain every service and exclusion in the CSV specification.");
        }

        private void checkWorkbook(
                Map<String, String> cells,
                String reference,
                String... requiredFragments
        ) {
            String value = cells.getOrDefault(reference, "");
            for (String fragment : requiredFragments) {
                if (!normalize(value).contains(normalize(fragment))) {
                    throw new IllegalStateException(
                            "Workbook source check failed: " + reference
                                    + " does not contain '" + fragment + "'. Value: " + value
                    );
                }
            }
            sourceChecks.add(reference + " verified");
        }

        private void readStops(ZipFile zip) throws IOException {
            readZipTable(zip, "stops.txt", table -> {
                int id = table.column("stop_id");
                int name = table.column("stop_name");
                int lat = table.column("stop_lat");
                int lon = table.column("stop_lon");
                int type = table.column("location_type");
                int parent = table.column("parent_station");
                String[] row;
                while ((row = table.next()) != null) {
                    Stop stop = new Stop(
                            row[id], row[name], parseDouble(row[lat], "stop_lat"),
                            parseDouble(row[lon], "stop_lon"), row[type], row[parent]
                    );
                    if (stops.put(stop.id(), stop) != null) {
                        throw new IllegalStateException("Duplicate stop_id: " + stop.id());
                    }
                }
            });
        }

        private void readRoutes(ZipFile zip) throws IOException {
            readZipTable(zip, "routes.txt", table -> {
                int id = table.column("route_id");
                int agency = table.column("agency_id");
                int name = table.column("route_long_name");
                int type = table.column("route_type");
                String[] row;
                while ((row = table.next()) != null) {
                    Route route = new Route(
                            row[id], row[agency], row[name],
                            parseInteger(row[type], "route_type")
                    );
                    if (routes.put(route.id(), route) != null) {
                        throw new IllegalStateException("Duplicate route_id: " + route.id());
                    }
                }
            });
        }

        private void readTrips(ZipFile zip) throws IOException {
            readZipTable(zip, "trips.txt", table -> {
                int id = table.column("trip_id");
                int route = table.column("route_id");
                int direction = table.column("direction_id");
                int headsign = table.column("trip_headsign");
                int shape = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (!routes.containsKey(row[route])) {
                        throw new IllegalStateException(
                                "Trip references missing route " + row[route]
                        );
                    }
                    Trip trip = new Trip(
                            row[id], row[route], row[direction], row[headsign], row[shape]
                    );
                    if (trips.put(trip.id(), trip) != null) {
                        throw new IllegalStateException("Duplicate trip_id: " + trip.id());
                    }
                }
            });
        }

        private void scanStopTimes(ZipFile zip) throws IOException {
            Set<String> relevantParents = allCandidateParents();
            Set<String> comparisonRoutes = new HashSet<>();
            services.values().forEach(s -> comparisonRoutes.addAll(s.comparisonRoutes()));
            comparisonRoutes.addAll(List.of(U2, S2_BASE, S4));

            readZipTable(zip, "stop_times.txt", table -> {
                int tripIndex = table.column("trip_id");
                int stopIndex = table.column("stop_id");
                int arrival = table.column("arrival_time");
                int departure = table.column("departure_time");
                int sequence = table.column("stop_sequence");
                String[] row;
                while ((row = table.next()) != null) {
                    Trip trip = trips.get(row[tripIndex]);
                    if (trip == null) {
                        throw new IllegalStateException(
                                "stop_times references missing trip " + row[tripIndex]
                        );
                    }
                    Stop stop = stops.get(row[stopIndex]);
                    if (stop == null) {
                        throw new IllegalStateException(
                                "stop_times references missing stop " + row[stopIndex]
                        );
                    }
                    String parent = stop.parentOrSelf();
                    Route route = routes.get(trip.routeId());
                    if (relevantParents.contains(parent)) {
                        StopUsage usage = usageByParent.computeIfAbsent(
                                parent, ignored -> new StopUsage()
                        );
                        usage.calls++;
                        usage.routeIds.add(route.id());
                        usage.routeTypes.add(route.type());
                        PlatformKey key = new PlatformKey(
                                route.id(), parent, trip.directionId()
                        );
                        platforms.computeIfAbsent(key, ignored -> new TreeSet<>())
                                .add(stop.id());
                    }
                    if (comparisonRoutes.contains(route.id())) {
                        comparisonTrips.computeIfAbsent(trip.id(), ignored -> new ArrayList<>())
                                .add(new Call(
                                        stop.id(), parent,
                                        parseTime(row[arrival]), parseTime(row[departure]),
                                        parseInteger(row[sequence], "stop_sequence")
                                ));
                    }
                }
            });
            comparisonTrips.values().forEach(calls ->
                    calls.sort(Comparator.comparingInt(Call::sequence))
            );
        }

        private Set<String> allCandidateParents() {
            Set<String> result = new HashSet<>();
            for (ServiceSpec service : services.values()) {
                for (String logicalStop : service.stopPattern()) {
                    result.addAll(candidateParents(logicalStop).stream().map(Stop::id).toList());
                }
            }
            for (StopDecision decision : decisions.values()) {
                if (!decision.existingReferenceStopId().isBlank()) {
                    Stop reference = stops.get(decision.existingReferenceStopId());
                    if (reference != null) {
                        result.add(reference.parentOrSelf());
                    }
                }
            }
            return result;
        }

        private List<StopMapping> resolveStops() {
            List<StopMapping> result = new ArrayList<>();
            for (ServiceSpec service : services.values()) {
                if (!service.createsService()) {
                    continue;
                }
                int sequence = 0;
                for (String logicalStop : service.stopPattern()) {
                    StopDecision decision = decisions.get(
                            decisionKey(service.measureId(), logicalStop)
                    );
                    List<Stop> candidates = candidateParents(logicalStop);
                    if (decision != null) {
                        result.add(mappingFromDecision(
                                service, sequence++, logicalStop, decision, candidates
                        ));
                    } else {
                        result.add(resolveExistingStop(
                                service, sequence++, logicalStop, candidates
                        ));
                    }
                }
            }
            return result;
        }

        private StopMapping mappingFromDecision(
                ServiceSpec service,
                int sequence,
                String logicalStop,
                StopDecision decision,
                List<Stop> candidates
        ) {
            boolean approved = "approved".equals(decision.resolutionStatus());
            double lat = Double.NaN;
            double lon = Double.NaN;
            if (approved) {
                if (!Set.of("authoritative", "derived", "scenario assumption")
                        .contains(decision.assumptionStrength())) {
                    throw new IllegalStateException(
                            "Approved decision has no valid assumption strength: "
                                    + service.measureId() + " / " + logicalStop
                    );
                }
                if ("scenario assumption".equals(decision.assumptionStrength())
                        && !decision.coordinateSource().startsWith(
                                "Scenario assumption:"
                        )) {
                    throw new IllegalStateException(
                            "Scenario proxy is not explicitly labelled in coordinate_source: "
                                    + service.measureId() + " / " + logicalStop
                    );
                }
                lat = parseDouble(decision.stopLat(), "approved stop_lat");
                lon = parseDouble(decision.stopLon(), "approved stop_lon");
                if (!validLatLon(lat, lon)) {
                    throw new IllegalStateException(
                            "Approved decision has invalid coordinates: "
                                    + service.measureId() + " / " + logicalStop
                    );
                }
            }
            return new StopMapping(
                    service.measureId(), sequence, logicalStop,
                    approved ? decision.plannedDirection0Id() : "",
                    approved ? decision.plannedDirection1Id() : "",
                    decision.plannedParentId(), lat, lon,
                    approved, decision.resolutionStatus(),
                    candidateSummary(candidates), candidateModes(candidates),
                    decision.assumptionStrength() + ": "
                            + decision.coordinateSource() + " " + decision.decisionNote(),
                    approved
            );
        }

        private StopMapping resolveExistingStop(
                ServiceSpec service,
                int sequence,
                String logicalStop,
                List<Stop> candidates
        ) {
            int targetType = service.routeType();
            List<Stop> modeCandidates = candidates.stream()
                    .filter(stop -> usageByParent.getOrDefault(
                            stop.id(), new StopUsage()
                    ).routeTypes.contains(targetType))
                    .toList();
            if (modeCandidates.size() != 1) {
                return new StopMapping(
                        service.measureId(), sequence, logicalStop, "", "", "",
                        Double.NaN, Double.NaN, false,
                        modeCandidates.isEmpty()
                                ? "unresolved_no_existing_target_mode_station"
                                : "unresolved_multiple_existing_target_mode_stations",
                        candidateSummary(candidates), candidateModes(candidates),
                        "No unique same-mode parent station was found.", false
                );
            }
            Stop parent = modeCandidates.get(0);
            Set<String> d0 = new TreeSet<>();
            Set<String> d1 = new TreeSet<>();
            for (String referenceRoute : referenceRoutes(service, logicalStop)) {
                d0.addAll(platforms.getOrDefault(
                        new PlatformKey(referenceRoute, parent.id(), "0"), Set.of()
                ));
                d1.addAll(platforms.getOrDefault(
                        new PlatformKey(referenceRoute, parent.id(), "1"), Set.of()
                ));
            }
            Set<String> all = new TreeSet<>(d0);
            all.addAll(d1);
            if (d0.size() != 1 || d1.size() != 1) {
                if (all.size() == 1) {
                    String only = all.iterator().next();
                    d0 = Set.of(only);
                    d1 = Set.of(only);
                } else {
                    return new StopMapping(
                            service.measureId(), sequence, logicalStop, "", "",
                            parent.id(), parent.lat(), parent.lon(), false,
                            "unresolved_platform_assignment", candidateSummary(candidates),
                            candidateModes(candidates),
                            "Same-mode station exists, but template routes do not identify "
                                    + "one platform per direction. direction_0=" + d0
                                    + "; direction_1=" + d1,
                            false
                    );
                }
            }
            return new StopMapping(
                    service.measureId(), sequence, logicalStop,
                    d0.iterator().next(), d1.iterator().next(), parent.id(),
                    parent.lat(), parent.lon(), true, "resolved_existing_same_mode",
                    candidateSummary(candidates), candidateModes(candidates),
                    "Reused parent station and direction-specific platforms served by "
                            + String.join("|", referenceRoutes(service, logicalStop)),
                    false
            );
        }

        private List<String> referenceRoutes(ServiceSpec service, String logicalStop) {
            if ("FT-U4-EXT".equals(service.measureId())
                    && "Messestadt West".equals(logicalStop)) {
                return List.of(U2);
            }
            if (service.measureId().startsWith("FT-NR")) {
                return switch (logicalStop) {
                    case "Dachau", "Karlsfeld" -> List.of(S2);
                    case "Riem" -> List.of(S2_BASE);
                    case "Feldmoching" -> List.of(S1);
                    case "Johanneskirchen", "Englschalking", "Daglfing" -> List.of(S8);
                    case "Trudering", "Haar" -> List.of(S4);
                    default -> service.comparisonRoutes();
                };
            }
            return service.comparisonRoutes();
        }

        private List<Stop> candidateParents(String logicalStop) {
            String logical = normalize(logicalStop);
            List<Stop> result = new ArrayList<>();
            for (Stop stop : stops.values()) {
                if (!"1".equals(stop.locationType()) || !inMunichRegion(stop)) {
                    continue;
                }
                String name = normalize(stop.name());
                boolean match = switch (logicalStop) {
                    case "Dachau" -> name.equals(normalize("Dachau Bahnhof"));
                    case "Hauptbahnhof" -> name.equals(normalize(
                            "Hauptbahnhof (S, U, Bus, Tram)"
                    ));
                    case "Euro-Industriepark" -> name.startsWith(
                            normalize("Euro-Industriepark")
                    );
                    case "Impler-/Poccistraße" -> name.equals(normalize("Implerstraße"))
                            || name.equals(normalize("Poccistraße"));
                    case "Cosimapark" -> name.equals(logical)
                            || name.equals(normalize("Cosimabad"));
                    default -> name.equals(logical);
                };
                if (match) {
                    result.add(stop);
                }
            }
            result.sort(Comparator.comparing(Stop::id));
            return result;
        }

        private boolean inMunichRegion(Stop stop) {
            return stop.lat() >= 47.6 && stop.lat() <= 48.5
                    && stop.lon() >= 11.0 && stop.lon() <= 12.1;
        }

        private String candidateSummary(List<Stop> candidates) {
            if (candidates.isEmpty()) {
                return "";
            }
            List<String> values = new ArrayList<>();
            for (Stop stop : candidates) {
                values.add(stop.id() + ":" + stop.name() + "@"
                        + formatCoordinate(stop.lat()) + "," + formatCoordinate(stop.lon()));
            }
            return String.join("|", values);
        }

        private String candidateModes(List<Stop> candidates) {
            Set<String> values = new TreeSet<>();
            for (Stop stop : candidates) {
                StopUsage usage = usageByParent.get(stop.id());
                if (usage != null) {
                    for (int type : usage.routeTypes) {
                        values.add(modeName(type));
                    }
                }
            }
            return String.join("|", values);
        }

        private List<ServicePlan> createServicePlans(
                List<StopMapping> mappings,
                List<String> blockers
        ) {
            List<ServicePlan> result = new ArrayList<>();
            for (ServiceSpec service : services.values()) {
                if (!service.createsService()) {
                    result.add(new ServicePlan(
                            service.measureId(), service.routeId(), "document_only",
                            "Existing S8 retained without changes", "", "", "", "",
                            "", "", "", "No timetable is generated.", true,
                            "Infrastructure prerequisite only; no GTFS rows are created."
                    ));
                    continue;
                }
                Stats stats = statsFor(service.comparisonRoutes());
                boolean mapped = mappings.stream()
                        .filter(m -> m.measureId().equals(service.measureId()))
                        .allMatch(StopMapping::resolved);
                boolean startReady = !service.routeId().startsWith("FT_NR_")
                        || !service.firstDeparture().isBlank();
                boolean ready = mapped && startReady;
                String generation = switch (service.action()) {
                    case "extend_route" -> "Extend all existing U4 trips at Arabellapark; "
                            + "preserve their original timetable and calculate only new segment times.";
                    case "create_route" -> service.routeId().equals("FT_U9")
                            ? "Retain one U9 trip per direction and exact U6 anchor time; "
                                    + "select the lexicographically smallest source trip for "
                                    + "duplicate keys and retain positive sub-two-minute intervals."
                            : service.firstDeparture().isBlank()
                                    ? "Generate " + service.departuresPerDirection()
                                            + " departures per direction at "
                                            + service.headwayMinutes() + "-minute intervals after "
                                            + "first_departure is approved."
                                    : "Generate " + service.departuresPerDirection()
                                            + " departures per direction at "
                                            + service.headwayMinutes() + "-minute intervals from "
                                            + service.firstDeparture() + " through 24:00:00 as an "
                                            + "approved regularized scenario timetable.";
                    default -> service.operationRule();
                };
                String proposedFirst = "";
                String proposedLast = "";
                String uncertainty = "No additional clock-time decision is proposed.";
                if (service.routeId().startsWith("FT_NR_") && !stats.earliest().isBlank()) {
                    int interval = service.headwayMinutes() * 60;
                    boolean approvedStart = !service.firstDeparture().isBlank();
                    int first = approvedStart
                            ? parseTime(service.firstDeparture())
                            : ((parseTime(stats.earliest()) + interval - 1) / interval)
                                    * interval;
                    proposedFirst = formatTime(first);
                    proposedLast = formatTime(first
                            + (service.departuresPerDirection() - 1) * interval);
                    uncertainty = approvedStart
                            ? "Approved scenario assumption: a regularized clock-face timetable "
                                    + "runs from " + proposedFirst + " through " + proposedLast
                                    + ". It is not an operationally validated railway timetable."
                            : "Proposal only: round the earliest observed comparator start "
                                    + "up to the next full 30-minute clock interval. The workbook "
                                    + "fixes frequency and trip count but not the first departure; "
                                    + "explicit approval remains required.";
                }
                result.add(new ServicePlan(
                        service.measureId(), service.routeId(), service.action(),
                        String.join("|", service.comparisonRoutes()),
                        stats.earliest(), stats.latest(), stats.medianHeadway(),
                        stats.medianSpeed(), stats.medianDwell(), proposedFirst,
                        proposedLast, uncertainty, ready, generation
                ));
            }
            return result;
        }

        private Stats statsFor(List<String> routeIds) {
            List<Integer> starts = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();
            List<Double> speeds = new ArrayList<>();
            List<Integer> dwells = new ArrayList<>();
            Map<String, List<Integer>> startsByDirection = new HashMap<>();
            for (Map.Entry<String, List<Call>> entry : comparisonTrips.entrySet()) {
                Trip trip = trips.get(entry.getKey());
                if (trip == null || !routeIds.contains(trip.routeId())) {
                    continue;
                }
                List<Call> calls = entry.getValue();
                if (calls.isEmpty()) {
                    continue;
                }
                starts.add(calls.get(0).departure());
                ends.add(calls.get(calls.size() - 1).arrival());
                startsByDirection.computeIfAbsent(
                        trip.routeId() + ":" + trip.directionId(), ignored -> new ArrayList<>()
                ).add(calls.get(0).departure());
                for (int i = 0; i < calls.size(); i++) {
                    Call current = calls.get(i);
                    dwells.add(Math.max(0, current.departure() - current.arrival()));
                    if (i > 0) {
                        Call previous = calls.get(i - 1);
                        int seconds = current.arrival() - previous.departure();
                        Stop a = stops.get(previous.stopId());
                        Stop b = stops.get(current.stopId());
                        if (seconds > 0 && a != null && b != null) {
                            double kmh = haversineMeters(a.lat(), a.lon(), b.lat(), b.lon())
                                    / seconds * 3.6;
                            if (kmh >= 5 && kmh <= 160) {
                                speeds.add(kmh);
                            }
                        }
                    }
                }
            }
            List<Integer> headways = new ArrayList<>();
            for (List<Integer> values : startsByDirection.values()) {
                Collections.sort(values);
                for (int i = 1; i < values.size(); i++) {
                    int difference = values.get(i) - values.get(i - 1);
                    if (difference > 0 && difference <= 3600) {
                        headways.add(difference);
                    }
                }
            }
            return new Stats(
                    starts.isEmpty() ? "" : formatTime(Collections.min(starts)),
                    ends.isEmpty() ? "" : formatTime(Collections.max(ends)),
                    headways.isEmpty() ? "" : medianInteger(headways) + " seconds",
                    speeds.isEmpty() ? "" : String.format(
                            Locale.ROOT, "%.1f km/h", medianDouble(speeds)
                    ),
                    dwells.isEmpty() ? "" : medianInteger(dwells) + " seconds"
            );
        }

        void writePreflight(Analysis analysis) throws IOException {
            Files.createDirectories(PREFLIGHT);
            writeStopMapping(analysis.mappings());
            writeUnresolved(analysis.mappings());
            writeServicePlan(analysis.plans());
            writePreflightReport(analysis);
        }

        private void writeStopMapping(List<StopMapping> mappings) throws IOException {
            Path output = PREFLIGHT.resolve("stop_mapping.csv");
            try (CsvWriter writer = new CsvWriter(output, List.of(
                    "measure_id", "sequence", "logical_stop", "direction_0_stop_id",
                    "direction_1_stop_id", "parent_station", "stop_lat", "stop_lon",
                    "status", "resolved", "creates_new_stop", "candidate_stations",
                    "candidate_served_modes", "evidence_and_decision"
            ))) {
                for (StopMapping mapping : mappings) {
                    writer.write(List.of(
                            mapping.measureId(), Integer.toString(mapping.sequence()),
                            mapping.logicalStop(), mapping.direction0Id(), mapping.direction1Id(),
                            mapping.parentId(), formatCoordinate(mapping.lat()),
                            formatCoordinate(mapping.lon()), mapping.status(),
                            Boolean.toString(mapping.resolved()),
                            Boolean.toString(mapping.createsNewStop()), mapping.candidates(),
                            mapping.candidateModes(), mapping.evidence()
                    ));
                }
            }
        }

        private void writeUnresolved(List<StopMapping> mappings) throws IOException {
            Path output = PREFLIGHT.resolve("unresolved_stops.csv");
            try (CsvWriter writer = new CsvWriter(output, List.of(
                    "measure_id", "logical_stop", "status", "candidate_stations",
                    "candidate_served_modes", "required_decision"
            ))) {
                for (StopMapping mapping : mappings) {
                    if (!mapping.resolved()) {
                        writer.write(List.of(
                                mapping.measureId(), mapping.logicalStop(), mapping.status(),
                                mapping.candidates(), mapping.candidateModes(), mapping.evidence()
                        ));
                    }
                }
            }
        }

        private void writeServicePlan(List<ServicePlan> plans) throws IOException {
            Path output = PREFLIGHT.resolve("service_plan.csv");
            try (CsvWriter writer = new CsvWriter(output, List.of(
                    "measure_id", "route_id", "action", "comparison_routes",
                    "observed_earliest_departure", "observed_latest_arrival",
                    "observed_median_headway", "observed_median_moving_speed",
                    "observed_median_dwell", "proposed_first_departure",
                    "proposed_last_departure", "derivation_uncertainty",
                    "applied_intermediate_dwell_seconds", "origin_dwell_seconds",
                    "terminal_dwell_seconds", "deduplication_key",
                    "source_trip_selection_rule", "dwell_sensitivity_note",
                    "build_ready", "generation_rule"
            ))) {
                for (ServicePlan plan : plans) {
                    ServiceSpec specification = services.get(plan.measureId());
                    writer.write(List.of(
                            plan.measureId(), plan.routeId(), plan.action(),
                            plan.comparisonRoutes(), plan.earliest(), plan.latest(),
                            plan.medianHeadway(), plan.medianSpeed(), plan.medianDwell(),
                            plan.proposedFirst(), plan.proposedLast(), plan.uncertainty(),
                            Integer.toString(specification.intermediateDwellSeconds()),
                            Integer.toString(specification.originDwellSeconds()),
                            Integer.toString(specification.terminalDwellSeconds()),
                            specification.deduplicationKey(),
                            specification.sourceTripSelectionRule(),
                            specification.dwellSensitivityNote(),
                            Boolean.toString(plan.buildReady()), plan.generationRule()
                    ));
                }
            }
        }

        private void writePreflightReport(Analysis analysis) throws IOException {
            Path output = PREFLIGHT.resolve("preflight_report.md");
            long resolved = analysis.mappings().stream().filter(StopMapping::resolved).count();
            StringBuilder text = new StringBuilder();
            text.append("# Fast Track GTFS 2037 preflight report\n\n")
                    .append("This report is generated by `BuildFastTrackGtfs2037 --analyze`. ")
                    .append("No GTFS feed is modified in analyze mode.\n\n")
                    .append("## Source boundary\n\n")
                    .append("- Baseline: `").append(BASE_ZIP).append("`\n")
                    .append("- Policy source: `").append(WORKBOOK)
                    .append("`, sheet `Maßnahmen`, rows 14, 15, 29 and 30, columns M:R\n")
                    .append("- Machine-readable service decisions: `").append(SERVICE_SPEC)
                    .append("`\n")
                    .append("- Stop decisions requiring approval: `").append(STOP_DECISIONS)
                    .append("`\n")
                    .append("- Technical service date remains 2026-02-13 (`service_id=1`).\n\n")
                    .append("The workbook is a source of policy requirements. The CSV files are ")
                    .append("versioned modelling inputs. Automatic timetable metrics are evidence ")
                    .append("from the cleaned GTFS baseline, not policy facts.\n\n")
                    .append("## Result\n\n")
                    .append("- Stop mappings resolved: ").append(resolved).append(" of ")
                    .append(analysis.mappings().size()).append("\n")
                    .append("- Critical blockers: ").append(analysis.blockers().size()).append("\n")
                    .append("- Build permitted: ").append(analysis.blockers().isEmpty())
                    .append("\n\n")
                    .append("## Critical blockers\n\n");
            if (analysis.blockers().isEmpty()) {
                text.append("None.\n");
            } else {
                for (String blocker : analysis.blockers()) {
                    text.append("- ").append(blocker).append("\n");
                }
            }
            text.append("\n## Service derivation\n\n");
            for (ServicePlan plan : analysis.plans()) {
                ServiceSpec specification = services.get(plan.measureId());
                text.append("### ").append(plan.measureId()).append("\n\n")
                        .append("- Action: ").append(plan.action()).append("\n")
                        .append("- Comparison routes: ").append(plan.comparisonRoutes()).append("\n")
                        .append("- Observed operating window: ").append(plan.earliest())
                        .append(" to ").append(plan.latest()).append("\n")
                        .append("- Observed median headway: ").append(plan.medianHeadway()).append("\n")
                        .append("- Observed median moving speed: ").append(plan.medianSpeed()).append("\n")
                        .append("- Observed median dwell: ").append(plan.medianDwell()).append("\n")
                        .append("- Applied intermediate dwell: ")
                        .append(specification.intermediateDwellSeconds()).append(" seconds\n")
                        .append("- Origin/terminal dwell: ")
                        .append(specification.originDwellSeconds()).append("/")
                        .append(specification.terminalDwellSeconds()).append(" seconds\n")
                        .append("- Deduplication key: ")
                        .append(specification.deduplicationKey()).append("\n")
                        .append("- Source selection: ")
                        .append(specification.sourceTripSelectionRule()).append("\n")
                        .append("- Dwell sensitivity: ")
                        .append(specification.dwellSensitivityNote()).append("\n")
                        .append("- Proposed first departure: ").append(plan.proposedFirst()).append("\n")
                        .append("- Proposed last departure: ").append(plan.proposedLast()).append("\n")
                        .append("- Uncertainty: ").append(plan.uncertainty()).append("\n")
                        .append("- Rule: ").append(plan.generationRule()).append("\n")
                        .append("- Build-ready: ").append(plan.buildReady()).append("\n\n");
            }
            text.append("## Shape handling\n\n")
                    .append("New and extended Fast Track trips use an empty optional `shape_id`. ")
                    .append("No geographic path is invented. The project test suite contains a ")
                    .append("MATSim conversion test demonstrating that the installed converter reads ")
                    .append("a trip with an empty `shape_id`; the PT pseudonetwork is constructed from ")
                    .append("the ordered stops. Existing S8 trips and shapes remain unchanged.\n\n")
                    .append("## Analytical limitation\n\n")
                    .append("A name match is not sufficient to turn a bus stop into a future rail or ")
                    .append("underground station. Until authoritative coordinates or an explicitly ")
                    .append("approved station-anchor assumption are entered in the versioned stop ")
                    .append("decision file, `--build` stops before creating the Fast Track ZIP.\n");
            Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
        }

        void printSummary(Analysis analysis) {
            long resolved = analysis.mappings().stream().filter(StopMapping::resolved).count();
            System.out.println("Fast Track GTFS-2037 preflight completed.");
            System.out.println("Workbook source checks: " + sourceChecks.size());
            System.out.println("Stop mappings resolved: " + resolved + "/"
                    + analysis.mappings().size());
            System.out.println("Critical blockers: " + analysis.blockers().size());
            for (String blocker : analysis.blockers()) {
                System.out.println("  BLOCKED: " + blocker);
            }
            System.out.println("Preflight directory: " + PREFLIGHT);
        }

        void build(Analysis analysis) throws Exception {
            Path parent = OUTPUT_ZIP.getParent();
            Files.createDirectories(parent);
            Path work = Files.createTempDirectory(parent, ".fast-track-build-");
            try {
                BuildContext context = prepareBuildContext(analysis);
                try (ZipFile base = new ZipFile(BASE_ZIP.toFile(), StandardCharsets.UTF_8)) {
                    copyUnchanged(base, work, Set.of(
                            "routes.txt", "trips.txt", "stop_times.txt", "stops.txt",
                            "transfers.txt"
                    ));
                    writeRoutes(base, work.resolve("routes.txt"), context);
                    writeStops(base, work.resolve("stops.txt"), context);
                    writeTrips(base, work.resolve("trips.txt"), context);
                    writeStopTimes(base, work.resolve("stop_times.txt"), context);
                    writeTransfers(base, work.resolve("transfers.txt"), context);
                }
                Validation validation = validateFolder(work, context);
                Path candidateZip = work.resolve(OUTPUT_ZIP.getFileName());
                writeDeterministicZip(work, candidateZip, validation.rows());
                MatsimVerification matsim = verifyMatsimConversion(candidateZip, context);
                Files.move(
                        candidateZip, OUTPUT_ZIP, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
                writeBuildReport(context, validation, matsim);
                System.out.println("Fast Track GTFS-2037 build completed: " + OUTPUT_ZIP);
                System.out.println("SHA-256: " + sha256(OUTPUT_ZIP));
                System.out.println("Added routes: " + context.newServices().size());
                System.out.println("Added trips: " + context.generatedTrips().size());
                System.out.println("Extended U4 trips: " + context.extendedU4Trips().size());
                System.out.println("Added explicit transfers: "
                        + context.generatedTransfers().size());
                System.out.println("MATSim transfer verification: PASS ("
                        + matsim.verifiedFastTrackTransfers() + " explicit relations)");
            } finally {
                deleteTemporaryTree(work);
            }
        }

        private BuildContext prepareBuildContext(Analysis analysis) {
            Map<String, List<StopMapping>> mappings = new LinkedHashMap<>();
            for (StopMapping mapping : analysis.mappings()) {
                mappings.computeIfAbsent(mapping.measureId(), ignored -> new ArrayList<>())
                        .add(mapping);
            }
            mappings.values().forEach(list -> list.sort(
                    Comparator.comparingInt(StopMapping::sequence)
            ));

            Map<String, DerivedTiming> timings = new HashMap<>();
            for (ServicePlan plan : analysis.plans()) {
                if (plan.action().equals("document_only")) {
                    continue;
                }
                double speed = parseLeadingDouble(plan.medianSpeed(), 32.0);
                ServiceSpec specification = services.get(plan.measureId());
                int dwell = specification.intermediateDwellSeconds();
                timings.put(plan.measureId(), new DerivedTiming(speed, dwell));
            }

            Set<String> extendedU4 = new TreeSet<>();
            for (Map.Entry<String, List<Call>> entry : comparisonTrips.entrySet()) {
                Trip trip = trips.get(entry.getKey());
                if (trip != null && U4.equals(trip.routeId()) && !entry.getValue().isEmpty()) {
                    List<Call> calls = entry.getValue();
                    if ("107688".equals(calls.get(0).parentId())
                            || "107688".equals(calls.get(calls.size() - 1).parentId())) {
                        extendedU4.add(trip.id());
                    }
                }
            }

            List<GeneratedTrip> generated = new ArrayList<>();
            createU9Trips(generated, mappings.get("FT-U9"), timings.get("FT-U9"));
            createNordringTrips(generated, services.get("FT-NR-A"),
                    mappings.get("FT-NR-A"), timings.get("FT-NR-A"));
            createNordringTrips(generated, services.get("FT-NR-B"),
                    mappings.get("FT-NR-B"), timings.get("FT-NR-B"));

            List<ServiceSpec> newServices = services.values().stream()
                    .filter(s -> "create_route".equals(s.action()))
                    .toList();
            List<GeneratedTransfer> generatedTransfers = createApprovedTransfers();
            return new BuildContext(
                    mappings, timings, List.copyOf(generated), Set.copyOf(extendedU4),
                    newServices, generatedTransfers
            );
        }

        private List<GeneratedTransfer> createApprovedTransfers() {
            Map<String, GeneratedTransfer> result = new TreeMap<>();
            for (StopDecision decision : decisions.values()) {
                if (decision.transferTargetStopIds().isEmpty()) {
                    continue;
                }
                if (!"approved".equals(decision.resolutionStatus())) {
                    throw new IllegalStateException(
                            "Transfer decision is not approved: "
                                    + decision.measureId() + " / " + decision.logicalStop()
                    );
                }
                if (decision.minimumTransferTimeSeconds() <= 0) {
                    throw new IllegalStateException(
                            "Approved transfer decision requires a positive minimum time: "
                                    + decision.measureId() + " / " + decision.logicalStop()
                    );
                }
                for (String target : decision.transferTargetStopIds()) {
                    Stop targetStop = stops.get(target);
                    if (targetStop == null || !"0".equals(targetStop.locationType())) {
                        throw new IllegalStateException(
                                "Transfer target is not an existing GTFS platform: " + target
                        );
                    }
                    boolean commonRegionalPlatform = target.startsWith(
                            "BAU_POCCISTRASSE_RAIL_D")
                            && "106206".equals(targetStop.parentStation());
                    if (!platformUsedByComparisonSubway(target)
                            && !commonRegionalPlatform) {
                        throw new IllegalStateException(
                                "Transfer target is neither a U3/U6 platform nor an approved "
                                        + "common Poccistraße regional platform: "
                                        + target
                        );
                    }
                    for (String platform : List.of(
                            decision.plannedDirection0Id(),
                            decision.plannedDirection1Id()
                    )) {
                        addTransfer(result, platform, target,
                                decision.minimumTransferTimeSeconds());
                        addTransfer(result, target, platform,
                                decision.minimumTransferTimeSeconds());
                    }
                }
            }
            return List.copyOf(result.values());
        }

        private boolean platformUsedByComparisonSubway(String stopId) {
            for (Map.Entry<PlatformKey, Set<String>> entry : platforms.entrySet()) {
                if ((U3.equals(entry.getKey().routeId()) || U6.equals(entry.getKey().routeId()))
                        && entry.getValue().contains(stopId)) {
                    return true;
                }
            }
            return false;
        }

        private void addTransfer(
                Map<String, GeneratedTransfer> transfers,
                String from,
                String to,
                int seconds
        ) {
            if (from.isBlank() || to.isBlank()) {
                throw new IllegalStateException("Approved transfer has a blank platform ID.");
            }
            String key = from + "\u0000" + to;
            GeneratedTransfer previous = transfers.putIfAbsent(
                    key, new GeneratedTransfer(from, to, seconds)
            );
            if (previous != null && previous.seconds() != seconds) {
                throw new IllegalStateException(
                        "Conflicting approved transfer times for " + from + " -> " + to
                );
            }
        }

        private void createU9Trips(
                List<GeneratedTrip> output,
                List<StopMapping> mapping,
                DerivedTiming timing
        ) {
            if (mapping == null || timing == null) {
                throw new IllegalStateException("U9 build context is incomplete.");
            }
            StopMapping north = mapping.get(0);
            StopMapping south = mapping.get(mapping.size() - 1);
            List<U9TemplateCandidate> candidates = new ArrayList<>();
            for (Map.Entry<String, List<Call>> entry : comparisonTrips.entrySet()) {
                Trip template = trips.get(entry.getKey());
                if (template == null || !U6.equals(template.routeId())) {
                    continue;
                }
                List<Call> calls = entry.getValue();
                int northIndex = indexOfParent(calls, north.parentId());
                int southIndex = indexOfParent(calls, south.parentId());
                if (northIndex < 0 || southIndex < 0 || northIndex == southIndex) {
                    continue;
                }
                int direction = northIndex < southIndex ? 0 : 1;
                int departure = calls.get(direction == 0 ? northIndex : southIndex)
                        .departure();
                candidates.add(new U9TemplateCandidate(
                        direction, departure, template.id()
                ));
            }
            Map<U9Key, String> selectedTemplates = selectU9Templates(candidates);
            int index = 0;
            for (Map.Entry<U9Key, String> selected : selectedTemplates.entrySet()) {
                int direction = selected.getKey().direction();
                int departure = selected.getKey().departure();
                List<StopMapping> ordered = new ArrayList<>(mapping);
                if (direction == 1) {
                    Collections.reverse(ordered);
                }
                output.add(generatedTrip(
                        "FT_U9_" + direction + "_" + String.format(Locale.ROOT, "%04d", index++),
                        "FT_U9", Integer.toString(direction),
                        ordered.get(ordered.size() - 1).logicalStop(), departure,
                        ordered, timing
                ));
            }
            if (index == 0) {
                throw new IllegalStateException(
                        "No U6 template trips serve both U9 anchor stations."
                );
            }
        }

        private void createNordringTrips(
                List<GeneratedTrip> output,
                ServiceSpec service,
                List<StopMapping> mapping,
                DerivedTiming timing
        ) {
            int first = parseTime(service.firstDeparture());
            for (int direction = 0; direction <= 1; direction++) {
                List<StopMapping> ordered = new ArrayList<>(mapping);
                if (direction == 1) {
                    Collections.reverse(ordered);
                }
                for (int departure = 0; departure < service.departuresPerDirection(); departure++) {
                    int start = first + departure * service.headwayMinutes() * 60;
                    String id = service.routeId() + "_" + direction + "_"
                            + String.format(Locale.ROOT, "%02d", departure);
                    output.add(generatedTrip(
                            id, service.routeId(), Integer.toString(direction),
                            ordered.get(ordered.size() - 1).logicalStop(), start,
                            ordered, timing
                    ));
                }
            }
        }

        private GeneratedTrip generatedTrip(
                String id,
                String routeId,
                String direction,
                String headsign,
                int start,
                List<StopMapping> ordered,
                DerivedTiming timing
        ) {
            List<GeneratedCall> calls = new ArrayList<>();
            int clock = start;
            for (int i = 0; i < ordered.size(); i++) {
                StopMapping mapping = ordered.get(i);
                if (i > 0) {
                    StopMapping previous = ordered.get(i - 1);
                    clock += segmentSeconds(previous, mapping, timing.speedKmh());
                }
                int arrival = clock;
                int departure = i == 0 || i == ordered.size() - 1
                        ? arrival : arrival + timing.dwellSeconds();
                calls.add(new GeneratedCall(
                        direction.equals("0") ? mapping.direction0Id() : mapping.direction1Id(),
                        arrival, departure, i
                ));
                clock = departure;
            }
            return new GeneratedTrip(id, routeId, direction, headsign, calls);
        }

        private int segmentSeconds(
                StopMapping a,
                StopMapping b,
                double speedKmh
        ) {
            double meters = haversineMeters(a.lat(), a.lon(), b.lat(), b.lon());
            int raw = (int) Math.round(meters / (speedKmh / 3.6));
            return Math.max(60, ((raw + 15) / 30) * 30);
        }

        private void copyUnchanged(
                ZipFile base,
                Path folder,
                Set<String> replaced
        ) throws IOException {
            Files.createDirectories(folder);
            for (String name : GTFS_FILES) {
                if (!replaced.contains(name)) {
                    try (InputStream input = base.getInputStream(base.getEntry(name))) {
                        Files.copy(input, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        private void writeRoutes(ZipFile base, Path output, BuildContext context)
                throws IOException {
            try (ZipCsvFile table = new ZipCsvFile(base, "routes.txt");
                    CsvWriter writer = new CsvWriter(output, table.header())) {
                String[] row;
                while ((row = table.next()) != null) {
                    writer.write(Arrays.asList(row));
                }
                for (ServiceSpec service : context.newServices()) {
                    Map<String, String> values = new HashMap<>();
                    values.put("route_id", service.routeId());
                    values.put("agency_id", service.routeId().equals("FT_U9")
                            ? routes.get(U6).agencyId() : routes.get(S8).agencyId());
                    values.put("route_long_name", service.routeLongName());
                    values.put("route_type", Integer.toString(service.routeType()));
                    values.put("route_color", service.routeType() == 1 ? "0065BD" : "008A45");
                    values.put("route_text_color", "FFFFFF");
                    values.put("München", "1");
                    values.put("Prognosenetz_2037", "1");
                    values.put("ÖPSV_Prognosemaßnahme", "1");
                    if (service.routeType() == 2) {
                        values.put("Analyselinie_Schiene", "1");
                    }
                    writer.write(rowForHeader(table.header(), values));
                }
            }
        }

        private void writeStops(ZipFile base, Path output, BuildContext context)
                throws IOException {
            Set<String> existing = new HashSet<>(stops.keySet());
            Map<String, StopMapping> physical = new LinkedHashMap<>();
            for (List<StopMapping> values : context.mappings().values()) {
                for (StopMapping mapping : values) {
                    if (mapping.createsNewStop()) {
                        physical.putIfAbsent(mapping.parentId(), mapping);
                    }
                }
            }
            try (ZipCsvFile table = new ZipCsvFile(base, "stops.txt");
                    CsvWriter writer = new CsvWriter(output, table.header())) {
                String[] row;
                while ((row = table.next()) != null) {
                    writer.write(Arrays.asList(row));
                }
                for (StopMapping mapping : physical.values()) {
                    if (!existing.contains(mapping.parentId())) {
                        writer.write(rowForHeader(table.header(), Map.of(
                                "stop_id", mapping.parentId(),
                                "stop_name", mapping.logicalStop(),
                                "stop_lat", formatCoordinate(mapping.lat()),
                                "stop_lon", formatCoordinate(mapping.lon()),
                                "location_type", "1"
                        )));
                        existing.add(mapping.parentId());
                    }
                    for (String platform : List.of(
                            mapping.direction0Id(), mapping.direction1Id()
                    )) {
                        if (existing.add(platform)) {
                            writer.write(rowForHeader(table.header(), Map.of(
                                    "stop_id", platform,
                                    "stop_name", mapping.logicalStop(),
                                    "stop_lat", formatCoordinate(mapping.lat()),
                                    "stop_lon", formatCoordinate(mapping.lon()),
                                    "location_type", "0",
                                    "parent_station", mapping.parentId()
                            )));
                        }
                    }
                }
            }
        }

        private void writeTrips(ZipFile base, Path output, BuildContext context)
                throws IOException {
            try (ZipCsvFile table = new ZipCsvFile(base, "trips.txt");
                    CsvWriter writer = new CsvWriter(output, table.header())) {
                int id = table.column("trip_id");
                int shape = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (context.extendedU4Trips().contains(row[id])) {
                        row[shape] = "";
                    }
                    writer.write(Arrays.asList(row));
                }
                for (GeneratedTrip trip : context.generatedTrips()) {
                    writer.write(rowForHeader(table.header(), Map.of(
                            "route_id", trip.routeId(),
                            "service_id", "1",
                            "trip_id", trip.id(),
                            "trip_headsign", trip.headsign(),
                            "direction_id", trip.directionId(),
                            "shape_id", ""
                    )));
                }
            }
        }

        private void writeStopTimes(ZipFile base, Path output, BuildContext context)
                throws IOException {
            Map<String, GeneratedTrip> generated = new LinkedHashMap<>();
            for (GeneratedTrip trip : context.generatedTrips()) {
                generated.put(trip.id(), trip);
            }
            try (ZipCsvFile table = new ZipCsvFile(base, "stop_times.txt");
                    CsvWriter writer = new CsvWriter(output, table.header())) {
                int tripIndex = table.column("trip_id");
                String current = null;
                List<String[]> buffer = new ArrayList<>();
                String[] row;
                while ((row = table.next()) != null) {
                    if (!row[tripIndex].equals(current)) {
                        if (current != null) {
                            writeBaseTripCalls(writer, table.header(), current, buffer, context);
                        }
                        current = row[tripIndex];
                        buffer.clear();
                    }
                    buffer.add(row.clone());
                }
                if (current != null) {
                    writeBaseTripCalls(writer, table.header(), current, buffer, context);
                }
                for (GeneratedTrip trip : generated.values()) {
                    writeGeneratedCalls(writer, table.header(), trip);
                }
            }
        }

        private void writeBaseTripCalls(
                CsvWriter writer,
                List<String> header,
                String tripId,
                List<String[]> source,
                BuildContext context
        ) throws IOException {
            if (!context.extendedU4Trips().contains(tripId)) {
                for (String[] row : source) {
                    writer.write(Arrays.asList(row));
                }
                return;
            }
            List<StopMapping> extension = context.mappings().get("FT-U4-EXT");
            Trip trip = trips.get(tripId);
            DerivedTiming timing = context.timings().get("FT-U4-EXT");
            int stopIndex = header.indexOf("stop_id");
            int arrivalIndex = header.indexOf("arrival_time");
            int departureIndex = header.indexOf("departure_time");
            int sequenceIndex = header.indexOf("stop_sequence");
            boolean arabellaLast = "107688".equals(
                    stops.get(source.get(source.size() - 1)[stopIndex]).parentOrSelf()
            );
            List<StopMapping> beyond = new ArrayList<>(extension.subList(1, extension.size()));
            if (arabellaLast) {
                for (int i = 0; i < source.size(); i++) {
                    String[] retained = source.get(i).clone();
                    retained[sequenceIndex] = Integer.toString(i);
                    writer.write(Arrays.asList(retained));
                }
                int clock = parseTime(source.get(source.size() - 1)[departureIndex]);
                StopMapping previous = extension.get(0);
                for (int i = 0; i < beyond.size(); i++) {
                    StopMapping next = beyond.get(i);
                    clock += segmentSeconds(previous, next, timing.speedKmh());
                    int arrival = clock;
                    int departure = i == beyond.size() - 1
                            ? arrival : arrival + timing.dwellSeconds();
                    writeGeneratedCall(writer, header, tripId, new GeneratedCall(
                            platformForDirection(next, trip.directionId()),
                            arrival, departure, source.size() + i
                    ), source.size() + i);
                    clock = departure;
                    previous = next;
                }
            } else {
                List<StopMapping> reversed = new ArrayList<>(beyond);
                Collections.reverse(reversed);
                int total = 0;
                StopMapping previous = extension.get(0);
                for (StopMapping next : beyond) {
                    total += segmentSeconds(previous, next, timing.speedKmh());
                    if (next != beyond.get(beyond.size() - 1)) {
                        total += timing.dwellSeconds();
                    }
                    previous = next;
                }
                int clock = parseTime(source.get(0)[arrivalIndex]) - total;
                List<GeneratedCall> prefix = new ArrayList<>();
                for (int i = 0; i < reversed.size(); i++) {
                    StopMapping current = reversed.get(i);
                    if (i > 0) {
                        StopMapping previousGenerated = reversed.get(i - 1);
                        clock += segmentSeconds(
                                previousGenerated, current, timing.speedKmh()
                        );
                    }
                    int arrival = clock;
                    int departure = i == 0
                            ? arrival : arrival + timing.dwellSeconds();
                    prefix.add(new GeneratedCall(
                            platformForDirection(current, trip.directionId()),
                            arrival, departure, i
                    ));
                    clock = departure;
                }
                for (int i = 0; i < prefix.size(); i++) {
                    writeGeneratedCall(writer, header, tripId, prefix.get(i), i);
                }
                for (int i = 0; i < source.size(); i++) {
                    String[] retained = source.get(i).clone();
                    retained[sequenceIndex] = Integer.toString(prefix.size() + i);
                    writer.write(Arrays.asList(retained));
                }
            }
        }

        private String platformForDirection(StopMapping mapping, String direction) {
            return "0".equals(direction) ? mapping.direction0Id() : mapping.direction1Id();
        }

        private void writeGeneratedCalls(
                CsvWriter writer,
                List<String> header,
                GeneratedTrip trip
        ) throws IOException {
            for (int sequence = 0; sequence < trip.calls().size(); sequence++) {
                writeGeneratedCall(
                        writer, header, trip.id(), trip.calls().get(sequence), sequence
                );
            }
        }

        private void writeGeneratedCall(
                CsvWriter writer,
                List<String> header,
                String tripId,
                GeneratedCall call,
                int sequence
        ) throws IOException {
            writer.write(rowForHeader(header, Map.of(
                        "trip_id", tripId,
                        "arrival_time", formatTime(call.arrival()),
                        "departure_time", formatTime(call.departure()),
                        "stop_id", call.stopId(),
                        "stop_sequence", Integer.toString(sequence),
                        "pickup_type", "0",
                        "drop_off_type", "0"
                )));
        }

        private void writeTransfers(
                ZipFile base,
                Path output,
                BuildContext context
        ) throws IOException {
            Set<String> pairs = new HashSet<>();
            try (ZipCsvFile table = new ZipCsvFile(base, "transfers.txt");
                    CsvWriter writer = new CsvWriter(output, table.header())) {
                int from = table.column("from_stop_id");
                int to = table.column("to_stop_id");
                String[] row;
                while ((row = table.next()) != null) {
                    pairs.add(row[from] + "\u0000" + row[to]);
                    writer.write(Arrays.asList(row));
                }
                for (GeneratedTransfer transfer : context.generatedTransfers()) {
                    String key = transfer.fromStopId() + "\u0000" + transfer.toStopId();
                    if (!pairs.add(key)) {
                        throw new IllegalStateException(
                                "Generated transfer duplicates a baseline transfer: "
                                        + transfer.fromStopId() + " -> " + transfer.toStopId()
                        );
                    }
                    writer.write(rowForHeader(table.header(), Map.of(
                            "from_stop_id", transfer.fromStopId(),
                            "to_stop_id", transfer.toStopId(),
                            "transfer_type", "2",
                            "min_transfer_time", Integer.toString(transfer.seconds())
                    )));
                }
            }
        }

        private Validation validateFolder(Path folder, BuildContext context)
                throws IOException {
            Map<String, Long> rows = new LinkedHashMap<>();
            Set<String> agencies = uniqueIds(folder.resolve("agency.txt"), "agency_id", rows);
            Set<String> servicesSet = uniqueIds(folder.resolve("calendar.txt"), "service_id", rows);
            Set<String> routeIds = uniqueIds(folder.resolve("routes.txt"), "route_id", rows);
            Set<String> tripIds = uniqueIds(folder.resolve("trips.txt"), "trip_id", rows);
            Set<String> stopIds = uniqueIds(folder.resolve("stops.txt"), "stop_id", rows);
            Set<String> shapeIds = shapeIds(folder.resolve("shapes.txt"), rows);
            validateRoutes(folder.resolve("routes.txt"), agencies);
            validateStops(folder.resolve("stops.txt"), stopIds);
            validateTrips(folder.resolve("trips.txt"), routeIds, servicesSet, shapeIds);
            validateStopTimes(folder.resolve("stop_times.txt"), tripIds, stopIds, rows);
            validateTransfers(
                    folder.resolve("transfers.txt"), stopIds,
                    context.generatedTransfers(), rows
            );
            return new Validation(Collections.unmodifiableMap(
                    new LinkedHashMap<>(rows)
            ));
        }

        private Set<String> uniqueIds(Path file, String column, Map<String, Long> rows)
                throws IOException {
            Set<String> ids = new HashSet<>();
            long count = 0;
            try (CsvFile table = new CsvFile(file)) {
                int index = table.column(column);
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    if (row[index].isBlank() || !ids.add(row[index])) {
                        throw new IllegalStateException(
                                "Blank or duplicate " + column + " in " + file + ": " + row[index]
                        );
                    }
                }
            }
            rows.put(file.getFileName().toString(), count);
            return ids;
        }

        private Set<String> shapeIds(Path file, Map<String, Long> rows) throws IOException {
            Set<String> ids = new HashSet<>();
            long count = 0;
            try (CsvFile table = new CsvFile(file)) {
                int id = table.column("shape_id");
                int lat = table.column("shape_pt_lat");
                int lon = table.column("shape_pt_lon");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    double latitude = parseDouble(row[lat], "shape_pt_lat");
                    double longitude = parseDouble(row[lon], "shape_pt_lon");
                    if (!validLatLon(latitude, longitude)) {
                        throw new IllegalStateException("Invalid shape coordinate: " + row[id]);
                    }
                    ids.add(row[id]);
                }
            }
            rows.put(file.getFileName().toString(), count);
            return ids;
        }

        private void validateRoutes(Path file, Set<String> agencies) throws IOException {
            try (CsvFile table = new CsvFile(file)) {
                int id = table.column("route_id");
                int agency = table.column("agency_id");
                int type = table.column("route_type");
                String[] row;
                while ((row = table.next()) != null) {
                    requireReference(agencies, row[agency], "agency", row[id]);
                    if (row[id].startsWith("FT_")) {
                        int routeType = parseInteger(row[type], "route_type");
                        if (routeType != 1 && routeType != 2) {
                            throw new IllegalStateException(
                                    "Fast Track route has invalid route_type: " + row[id]
                            );
                        }
                    }
                }
            }
        }

        private void validateStops(Path file, Set<String> stopIds) throws IOException {
            try (CsvFile table = new CsvFile(file)) {
                int id = table.column("stop_id");
                int lat = table.column("stop_lat");
                int lon = table.column("stop_lon");
                int parent = table.column("parent_station");
                String[] row;
                while ((row = table.next()) != null) {
                    double latitude = parseDouble(row[lat], "stop_lat");
                    double longitude = parseDouble(row[lon], "stop_lon");
                    if (!validLatLon(latitude, longitude)) {
                        throw new IllegalStateException("Invalid stop coordinate: " + row[id]);
                    }
                    if (!row[parent].isBlank()) {
                        requireReference(stopIds, row[parent], "parent station", row[id]);
                    }
                }
            }
        }

        private void validateTrips(
                Path file,
                Set<String> routeIds,
                Set<String> serviceIds,
                Set<String> shapeIds
        ) throws IOException {
            try (CsvFile table = new CsvFile(file)) {
                int id = table.column("trip_id");
                int route = table.column("route_id");
                int service = table.column("service_id");
                int shape = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    requireReference(routeIds, row[route], "route", row[id]);
                    requireReference(serviceIds, row[service], "service", row[id]);
                    if (!row[shape].isBlank()) {
                        requireReference(shapeIds, row[shape], "shape", row[id]);
                    }
                }
            }
        }

        private void validateStopTimes(
                Path file,
                Set<String> tripIds,
                Set<String> stopIds,
                Map<String, Long> rows
        ) throws IOException {
            Set<String> seenTrips = new HashSet<>();
            Set<String> completed = new HashSet<>();
            String current = null;
            int previousSequence = -1;
            int previousDeparture = -1;
            int currentCalls = 0;
            long count = 0;
            try (CsvFile table = new CsvFile(file)) {
                int trip = table.column("trip_id");
                int stop = table.column("stop_id");
                int arrival = table.column("arrival_time");
                int departure = table.column("departure_time");
                int sequence = table.column("stop_sequence");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    requireReference(tripIds, row[trip], "trip", row[trip]);
                    requireReference(stopIds, row[stop], "stop", row[trip]);
                    if (!row[trip].equals(current)) {
                        if (current != null) {
                            requireMinimumCalls(current, currentCalls);
                            completed.add(current);
                        }
                        if (completed.contains(row[trip])) {
                            throw new IllegalStateException(
                                    "Trip reappears in stop_times: " + row[trip]
                            );
                        }
                        current = row[trip];
                        previousSequence = -1;
                        previousDeparture = -1;
                        currentCalls = 0;
                    }
                    int sequenceValue = parseInteger(row[sequence], "stop_sequence");
                    int arrivalValue = parseTime(row[arrival]);
                    int departureValue = parseTime(row[departure]);
                    if (sequenceValue <= previousSequence
                            || arrivalValue < previousDeparture
                            || departureValue < arrivalValue) {
                        throw new IllegalStateException(
                                "Invalid sequence or time order for trip " + row[trip]
                        );
                    }
                    previousSequence = sequenceValue;
                    previousDeparture = departureValue;
                    currentCalls++;
                    seenTrips.add(row[trip]);
                }
            }
            if (current != null) {
                requireMinimumCalls(current, currentCalls);
            }
            if (!seenTrips.equals(tripIds)) {
                Set<String> missing = new HashSet<>(tripIds);
                missing.removeAll(seenTrips);
                throw new IllegalStateException("Trips without stop times: "
                        + missing.stream().limit(10).toList());
            }
            rows.put(file.getFileName().toString(), count);
        }

        private void requireMinimumCalls(String tripId, int calls) {
            if (calls < 2) {
                throw new IllegalStateException(
                        "Trip has fewer than two stop times: " + tripId
                );
            }
        }

        private void validateTransfers(
                Path file,
                Set<String> stopIds,
                List<GeneratedTransfer> expectedGenerated,
                Map<String, Long> rows
        ) throws IOException {
            long count = 0;
            Set<String> pairs = new HashSet<>();
            Map<String, Integer> generated = new HashMap<>();
            try (CsvFile table = new CsvFile(file)) {
                int from = table.column("from_stop_id");
                int to = table.column("to_stop_id");
                int type = table.column("transfer_type");
                int minimum = table.column("min_transfer_time");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    requireReference(stopIds, row[from], "transfer from-stop", row[from]);
                    requireReference(stopIds, row[to], "transfer to-stop", row[to]);
                    String key = row[from] + "\u0000" + row[to];
                    if (!pairs.add(key)) {
                        throw new IllegalStateException(
                                "Duplicate transfer relation: " + row[from] + " -> " + row[to]
                        );
                    }
                    int transferType = parseInteger(row[type], "transfer_type");
                    int seconds = row[minimum].isBlank()
                            ? 0 : parseInteger(row[minimum], "min_transfer_time");
                    if (transferType < 0 || transferType > 3 || seconds < 0
                            || (transferType == 2 && row[minimum].isBlank())) {
                        throw new IllegalStateException(
                                "Invalid transfer rule: " + row[from] + " -> " + row[to]
                        );
                    }
                    if (row[from].startsWith("FT_") || row[to].startsWith("FT_")) {
                        generated.put(key, seconds);
                    }
                }
            }
            for (GeneratedTransfer transfer : expectedGenerated) {
                String key = transfer.fromStopId() + "\u0000" + transfer.toStopId();
                if (!Integer.valueOf(transfer.seconds()).equals(generated.get(key))) {
                    throw new IllegalStateException(
                            "Approved transfer missing or has the wrong minimum time: "
                                    + transfer.fromStopId() + " -> " + transfer.toStopId()
                    );
                }
            }
            rows.put(file.getFileName().toString(), count);
        }

        private void writeDeterministicZip(
                Path folder,
                Path output,
                Map<String, Long> expectedRows
        ) throws IOException {
            Path temporary = Files.createTempFile(
                    output.getParent(), output.getFileName() + ".", ".tmp"
            );
            try {
                try (ZipOutputStream zip = new ZipOutputStream(
                        Files.newOutputStream(temporary), StandardCharsets.UTF_8
                )) {
                    zip.setLevel(Deflater.BEST_COMPRESSION);
                    for (String file : GTFS_FILES) {
                        ZipEntry entry = new ZipEntry(file);
                        entry.setTime(ZIP_TIME);
                        zip.putNextEntry(entry);
                        Files.copy(folder.resolve(file), zip);
                        zip.closeEntry();
                    }
                }
                validateZip(temporary, expectedRows);
                Files.move(
                        temporary, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private void validateZip(Path zipPath, Map<String, Long> expectedRows)
                throws IOException {
            try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
                List<String> names = zip.stream().filter(entry -> !entry.isDirectory())
                        .map(ZipEntry::getName).sorted().toList();
                if (!names.equals(GTFS_FILES.stream().sorted().toList())) {
                    throw new IllegalStateException("Unexpected GTFS ZIP entries: " + names);
                }
                for (String name : GTFS_FILES) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8
                    ))) {
                        if (reader.readLine() == null) {
                            throw new IllegalStateException("Empty ZIP entry: " + name);
                        }
                        long rows = reader.lines().count();
                        if (rows != expectedRows.get(name)) {
                            throw new IllegalStateException(
                                    "ZIP reread row count differs for " + name
                            );
                        }
                    }
                }
            }
        }

        private MatsimVerification verifyMatsimConversion(
                Path zipPath,
                BuildContext context
        ) {
            var config = ConfigUtils.createConfig();
            config.global().setCoordinateSystem("EPSG:31468");
            Scenario scenario = ScenarioUtils.createScenario(config);
            GtfsConverter.newBuilder()
                    .setFeed(zipPath)
                    .setDate(SERVICE_DATE)
                    .setTransform(TransformationFactory.getCoordinateTransformation(
                            TransformationFactory.WGS84, "EPSG:31468"
                    ))
                    .setScenario(scenario)
                    .setUseExtendedRouteTypes(false)
                    .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                    .setIncludeMinimalTransferTimes(true)
                    .build()
                    .convert();

            int verifiedFastTrackTransfers = 0;
            for (GeneratedTransfer transfer : context.generatedTransfers()) {
                Id<TransitStopFacility> from = Id.create(
                        transfer.fromStopId(), TransitStopFacility.class
                );
                Id<TransitStopFacility> to = Id.create(
                        transfer.toStopId(), TransitStopFacility.class
                );
                if (!scenario.getTransitSchedule().getFacilities().containsKey(from)
                        || !scenario.getTransitSchedule().getFacilities().containsKey(to)) {
                    throw new IllegalStateException(
                            "MATSim conversion omitted a transfer stop: "
                                    + transfer.fromStopId() + " -> " + transfer.toStopId()
                    );
                }
                double actual = scenario.getTransitSchedule().getMinimalTransferTimes()
                        .get(from, to);
                if (Double.compare(actual, transfer.seconds()) != 0) {
                    throw new IllegalStateException(
                            "MATSim conversion did not retain transfer "
                                    + transfer.fromStopId() + " -> " + transfer.toStopId()
                                    + ": expected " + transfer.seconds() + " seconds, got "
                                    + actual
                    );
                }
                verifiedFastTrackTransfers++;
            }

            long minimalTransferRelations = 0;
            var iterator = scenario.getTransitSchedule().getMinimalTransferTimes().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                minimalTransferRelations++;
            }
            long transitRoutes = scenario.getTransitSchedule().getTransitLines().values()
                    .stream().mapToLong(line -> line.getRoutes().size()).sum();
            long departures = scenario.getTransitSchedule().getTransitLines().values()
                    .stream().flatMap(line -> line.getRoutes().values().stream())
                    .mapToLong(route -> route.getDepartures().size()).sum();
            return new MatsimVerification(
                    scenario.getTransitSchedule().getFacilities().size(),
                    scenario.getTransitSchedule().getTransitLines().size(),
                    transitRoutes, departures, minimalTransferRelations,
                    verifiedFastTrackTransfers
            );
        }

        private void writeBuildReport(
                BuildContext context,
                Validation validation,
                MatsimVerification matsim
        )
                throws Exception {
            Files.createDirectories(BUILD_REPORT.getParent());
            String report = "# Fast Track GTFS 2037 build report\n\n"
                    + "The feed was created from the deterministic BAU GTFS containing the shared Poccistraße and Berduxstraße measures. "
                    + "All critical entries in the versioned service and stop specifications "
                    + "were approved before build mode was permitted.\n\n"
                    + "- Baseline SHA-256: `" + sha256(BASE_ZIP) + "`\n"
                    + "- Output: `" + OUTPUT_ZIP.toString().replace('\\', '/') + "`\n"
                    + "- SHA-256: `" + sha256(OUTPUT_ZIP) + "`\n"
                    + "- New routes: " + context.newServices().size() + "\n"
                    + "- New trips: " + context.generatedTrips().size() + "\n"
                    + "  - FT_U9: " + generatedTripCount(context, "FT_U9") + "\n"
                    + "  - FT_NR_A: " + generatedTripCount(context, "FT_NR_A") + "\n"
                    + "  - FT_NR_B: " + generatedTripCount(context, "FT_NR_B") + "\n"
                    + "- Extended U4 trips: " + context.extendedU4Trips().size() + "\n"
                    + "- New stop rows: " + countNewStopRows(context) + "\n"
                    + "- New directed transfer relations: "
                    + context.generatedTransfers().size() + "\n"
                    + "- Validated rows: " + validation.rows() + "\n\n"
                    + "## MATSim conversion verification\n\n"
                    + "The completed ZIP was converted in memory for the technical service "
                    + "date 2026-02-13 with WGS84-to-EPSG:31468 transformation, unmerged "
                    + "GTFS stops and minimal transfer-time import enabled. No MATSim "
                    + "simulation was run.\n\n"
                    + "- Transit stops: " + matsim.transitStops() + "\n"
                    + "- Transit lines: " + matsim.transitLines() + "\n"
                    + "- Transit routes: " + matsim.transitRoutes() + "\n"
                    + "- Departures: " + matsim.departures() + "\n"
                    + "- Minimal transfer-time relations: "
                    + matsim.minimalTransferRelations() + "\n"
                    + "- Explicit Impler-/Poccistraße relations verified: "
                    + matsim.verifiedFastTrackTransfers() + "\n\n"
                    + "## Approved timetable rules\n\n"
                    + "U9 retains one departure for each direction and exact U6 anchor "
                    + "departure time. If several U6 trips produce the same key, the "
                    + "lexicographically smallest source `trip_id` is selected. Positive "
                    + "sub-two-minute intervals remain because their source trips have "
                    + "distinguishable full-length or short-turn patterns. The five "
                    + "intermediate U9 stops have 20-second dwell; origin and terminal "
                    + "dwell are zero. Nordring intermediate dwell remains zero in the main "
                    + "scenario; a future 60-second sensitivity test is documented but not "
                    + "implemented.\n\n"
                    + "New and extended trips have an empty optional `shape_id`; no shape was "
                    + "invented. Existing S8 rows were copied without modification. All new "
                    + "station coordinates are approved scenario proxies rather than official "
                    + "future platform locations. The 300-second Impler-/Poccistraße transfer "
                    + "time and the regularized Nordring timetable are scenario assumptions, "
                    + "not operationally validated values.\n";
            Files.writeString(BUILD_REPORT, report, StandardCharsets.UTF_8);
        }

        private long generatedTripCount(BuildContext context, String routeId) {
            return context.generatedTrips().stream()
                    .filter(trip -> routeId.equals(trip.routeId()))
                    .count();
        }

        private long countNewStopRows(BuildContext context) {
            Set<String> ids = new HashSet<>(stops.keySet());
            long count = 0;
            for (List<StopMapping> mappings : context.mappings().values()) {
                for (StopMapping mapping : mappings) {
                    if (!mapping.createsNewStop()) {
                        continue;
                    }
                    if (ids.add(mapping.parentId())) {
                        count++;
                    }
                    if (ids.add(mapping.direction0Id())) {
                        count++;
                    }
                    if (ids.add(mapping.direction1Id())) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    private static void readZipTable(
            ZipFile zip,
            String name,
            ThrowingConsumer<ZipCsvFile> consumer
    ) throws IOException {
        try (ZipCsvFile table = new ZipCsvFile(zip, name)) {
            consumer.accept(table);
        }
    }

    private static List<String> rowForHeader(
            List<String> header,
            Map<String, String> values
    ) {
        List<String> row = new ArrayList<>(header.size());
        for (String column : header) {
            row.add(values.getOrDefault(column, ""));
        }
        return row;
    }

    private static void requireReference(
            Set<String> ids,
            String reference,
            String relation,
            String owner
    ) {
        if (!ids.contains(reference)) {
            throw new IllegalStateException(
                    "Missing " + relation + " reference " + reference + " from " + owner
            );
        }
    }

    private static int indexOfParent(List<Call> calls, String parent) {
        for (int i = 0; i < calls.size(); i++) {
            if (parent.equals(calls.get(i).parentId())) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, List<Call>> deepCopyCalls(
            Map<String, List<Call>> input
    ) {
        Map<String, List<Call>> result = new HashMap<>();
        input.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static String decisionKey(String measure, String stop) {
        return measure + "\u0000" + stop;
    }

    private static List<String> splitPipe(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\|", -1))
                .map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Invalid " + field + ": " + value, error);
        }
    }

    private static int parseOptionalInteger(String value) {
        return value == null || value.isBlank() ? 0 : parseInteger(value, "integer");
    }

    static Map<U9Key, String> selectU9Templates(
            List<U9TemplateCandidate> candidates
    ) {
        Map<U9Key, String> selected = new TreeMap<>(
                Comparator.comparingInt(U9Key::direction)
                        .thenComparingInt(U9Key::departure)
        );
        for (U9TemplateCandidate candidate : candidates) {
            selected.merge(
                    new U9Key(candidate.direction(), candidate.departure()),
                    candidate.sourceTripId(),
                    (left, right) -> left.compareTo(right) <= 0 ? left : right
            );
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(selected));
    }

    private static double parseDouble(String value, String field) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Invalid " + field + ": " + value, error);
        }
    }

    private static int parseTime(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid GTFS time: " + value);
        }
        int hour = parseInteger(parts[0], "hour");
        int minute = parseInteger(parts[1], "minute");
        int second = parseInteger(parts[2], "second");
        if (hour < 0 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            throw new IllegalStateException("Invalid GTFS time: " + value);
        }
        return hour * 3600 + minute * 60 + second;
    }

    private static String formatTime(int seconds) {
        int hour = seconds / 3600;
        int minute = seconds % 3600 / 60;
        int second = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
    }

    private static String formatCoordinate(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }

    private static boolean validLatLon(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("ß", "ss")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static String modeName(int routeType) {
        return switch (routeType) {
            case 0 -> "tram";
            case 1 -> "subway";
            case 2 -> "rail";
            case 3 -> "bus";
            case 4 -> "ferry";
            default -> "route_type_" + routeType;
        };
    }

    private static double haversineMeters(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double radius = 6_371_000;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static int medianInteger(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double medianDouble(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double parseLeadingDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.split(" ")[0]);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static int parseLeadingInteger(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.split(" ")[0]);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(
                Files.newInputStream(file), digest
        )) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void deleteTemporaryTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class XlsxCells {
        private XlsxCells() {
        }

        static Map<String, String> read(Path workbook, String sheetName) throws Exception {
            try (ZipFile zip = new ZipFile(workbook.toFile(), StandardCharsets.UTF_8)) {
                Document workbookXml = parseXml(zip, "xl/workbook.xml");
                Document relationships = parseXml(zip, "xl/_rels/workbook.xml.rels");
                String relationId = null;
                NodeList sheets = workbookXml.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "sheet"
                );
                for (int i = 0; i < sheets.getLength(); i++) {
                    Element sheet = (Element) sheets.item(i);
                    if (sheetName.equals(sheet.getAttribute("name"))) {
                        relationId = sheet.getAttributeNS(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id"
                        );
                    }
                }
                if (relationId == null) {
                    throw new IllegalStateException("Workbook sheet not found: " + sheetName);
                }
                String target = null;
                NodeList relations = relationships.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/package/2006/relationships",
                        "Relationship"
                );
                for (int i = 0; i < relations.getLength(); i++) {
                    Element relation = (Element) relations.item(i);
                    if (relationId.equals(relation.getAttribute("Id"))) {
                        target = relation.getAttribute("Target");
                    }
                }
                if (target == null) {
                    throw new IllegalStateException("Worksheet relationship is missing.");
                }
                String sheetPath = target.startsWith("/")
                        ? target.substring(1) : "xl/" + target;
                List<String> shared = sharedStrings(zip);
                Document sheet = parseXml(zip, sheetPath);
                Map<String, String> result = new HashMap<>();
                NodeList cells = sheet.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "c"
                );
                for (int i = 0; i < cells.getLength(); i++) {
                    Element cell = (Element) cells.item(i);
                    String reference = cell.getAttribute("r");
                    String type = cell.getAttribute("t");
                    String raw = childText(cell, "v");
                    if ("s".equals(type) && !raw.isBlank()) {
                        raw = shared.get(Integer.parseInt(raw));
                    } else if ("inlineStr".equals(type)) {
                        raw = descendantText(cell, "t");
                    }
                    result.put(reference, raw);
                }
                return result;
            }
        }

        private static List<String> sharedStrings(ZipFile zip) throws Exception {
            if (zip.getEntry("xl/sharedStrings.xml") == null) {
                return List.of();
            }
            Document document = parseXml(zip, "xl/sharedStrings.xml");
            NodeList items = document.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "si"
            );
            List<String> result = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                result.add(descendantText((Element) items.item(i), "t"));
            }
            return result;
        }

        private static Document parseXml(ZipFile zip, String entry) throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try (InputStream input = zip.getInputStream(zip.getEntry(entry))) {
                return factory.newDocumentBuilder().parse(input);
            }
        }

        private static String childText(Element parent, String localName) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element element
                        && localName.equals(element.getLocalName())) {
                    return element.getTextContent();
                }
            }
            return "";
        }

        private static String descendantText(Element parent, String localName) {
            NodeList nodes = parent.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/spreadsheetml/2006/main", localName
            );
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                text.append(nodes.item(i).getTextContent());
            }
            return text.toString();
        }
    }

    private interface ThrowingConsumer<T> {
        void accept(T value) throws IOException;
    }

    private static class CsvReader implements AutoCloseable {
        private final BufferedReader reader;
        private final List<String> header;
        private final Map<String, Integer> columns = new HashMap<>();
        private long line = 1;

        CsvReader(InputStream input) throws IOException {
            reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8), 1 << 20
            );
            String first = reader.readLine();
            if (first == null) {
                throw new IllegalStateException("CSV input is empty.");
            }
            if (!first.isEmpty() && first.charAt(0) == '\ufeff') {
                first = first.substring(1);
            }
            header = List.copyOf(parseCsv(first));
            for (int i = 0; i < header.size(); i++) {
                columns.put(header.get(i), i);
            }
        }

        List<String> header() {
            return header;
        }

        int column(String name) {
            Integer index = columns.get(name);
            if (index == null) {
                throw new IllegalStateException("Missing CSV column: " + name);
            }
            return index;
        }

        String get(String[] row, String column) {
            return row[column(column)];
        }

        String[] next() throws IOException {
            String value = reader.readLine();
            if (value == null) {
                return null;
            }
            line++;
            List<String> parsed = parseCsv(value);
            if (parsed.size() != header.size()) {
                throw new IllegalStateException(
                        "CSV column count differs at line " + line
                                + ": expected " + header.size() + ", found " + parsed.size()
                );
            }
            return parsed.toArray(String[]::new);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class CsvFile extends CsvReader {
        CsvFile(Path file) throws IOException {
            super(Files.newInputStream(file));
        }
    }

    private static final class ZipCsvFile extends CsvReader {
        ZipCsvFile(ZipFile zip, String name) throws IOException {
            super(zip.getInputStream(zip.getEntry(name)));
        }
    }

    private static final class CsvWriter implements AutoCloseable {
        private final BufferedWriter writer;

        CsvWriter(Path output, List<String> header) throws IOException {
            Files.createDirectories(output.getParent());
            writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(output), StandardCharsets.UTF_8
            ), 1 << 20);
            write(header);
        }

        void write(List<String> values) throws IOException {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    writer.write(',');
                }
                writer.write(escapeCsv(values.get(i)));
            }
            writer.newLine();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    private static List<String> parseCsv(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                result.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Unclosed quoted CSV field.");
        }
        result.add(field.toString());
        return result;
    }

    private static String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private record ServiceSpec(
            String sourceRow,
            String measureId,
            String routeId,
            String routeLongName,
            String action,
            String mode,
            int routeType,
            String agencyStrategy,
            List<String> stopPattern,
            int headwayMinutes,
            int departuresPerDirection,
            String firstDeparture,
            List<String> comparisonRoutes,
            String operationRule,
            List<String> excludedStops,
            String sourceFact,
            String modelAssumption,
            boolean createsService,
            String deduplicationKey,
            String sourceTripSelectionRule,
            int intermediateDwellSeconds,
            int originDwellSeconds,
            int terminalDwellSeconds,
            String dwellSensitivityNote
    ) {
    }

    record U9Key(int direction, int departure) {
    }

    record U9TemplateCandidate(int direction, int departure, String sourceTripId) {
    }

    private record StopDecision(
            String measureId,
            String logicalStop,
            String plannedParentId,
            String plannedDirection0Id,
            String plannedDirection1Id,
            String resolutionStatus,
            String existingReferenceStopId,
            String stopLat,
            String stopLon,
            String coordinateSource,
            String assumptionStrength,
            List<String> transferTargetStopIds,
            int minimumTransferTimeSeconds,
            String decisionNote
    ) {
    }

    private record Stop(
            String id,
            String name,
            double lat,
            double lon,
            String locationType,
            String parentStation
    ) {
        String parentOrSelf() {
            return parentStation == null || parentStation.isBlank() ? id : parentStation;
        }
    }

    private record Route(String id, String agencyId, String name, int type) {
    }

    private record Trip(
            String id,
            String routeId,
            String directionId,
            String headsign,
            String shapeId
    ) {
    }

    private static final class StopUsage {
        long calls;
        final Set<String> routeIds = new TreeSet<>();
        final Set<Integer> routeTypes = new TreeSet<>();
    }

    private record PlatformKey(String routeId, String parentId, String directionId) {
    }

    private record Call(
            String stopId,
            String parentId,
            int arrival,
            int departure,
            int sequence
    ) {
    }

    private record StopMapping(
            String measureId,
            int sequence,
            String logicalStop,
            String direction0Id,
            String direction1Id,
            String parentId,
            double lat,
            double lon,
            boolean resolved,
            String status,
            String candidates,
            String candidateModes,
            String evidence,
            boolean createsNewStop
    ) {
    }

    private record ServicePlan(
            String measureId,
            String routeId,
            String action,
            String comparisonRoutes,
            String earliest,
            String latest,
            String medianHeadway,
            String medianSpeed,
            String medianDwell,
            String proposedFirst,
            String proposedLast,
            String uncertainty,
            boolean buildReady,
            String generationRule
    ) {
    }

    private record Stats(
            String earliest,
            String latest,
            String medianHeadway,
            String medianSpeed,
            String medianDwell
    ) {
    }

    private record Analysis(
            List<StopMapping> mappings,
            List<ServicePlan> plans,
            List<String> blockers,
            Map<String, Stop> stops,
            Map<String, Route> routes,
            Map<String, Trip> trips,
            Map<String, List<Call>> comparisonTrips
    ) {
    }

    private record DerivedTiming(double speedKmh, int dwellSeconds) {
    }

    private record GeneratedCall(String stopId, int arrival, int departure, int sequence) {
    }

    private record GeneratedTrip(
            String id,
            String routeId,
            String directionId,
            String headsign,
            List<GeneratedCall> calls
    ) {
    }

    private record GeneratedTransfer(
            String fromStopId,
            String toStopId,
            int seconds
    ) {
    }

    private record BuildContext(
            Map<String, List<StopMapping>> mappings,
            Map<String, DerivedTiming> timings,
            List<GeneratedTrip> generatedTrips,
            Set<String> extendedU4Trips,
            List<ServiceSpec> newServices,
            List<GeneratedTransfer> generatedTransfers
    ) {
    }

    private record Validation(Map<String, Long> rows) {
    }

    private record MatsimVerification(
            long transitStops,
            long transitLines,
            long transitRoutes,
            long departures,
            long minimalTransferRelations,
            int verifiedFastTrackTransfers
    ) {
    }
}
