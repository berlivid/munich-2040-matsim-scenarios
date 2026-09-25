package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.SAXParserFactory;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Builds a reproducible Munich subset of the unchanged GTFS-2037 source.
 *
 * <p>The analytical selection unit is a complete trip. A trip is retained if
 * it calls at two or more distinct GTFS stops whose transformed coordinates
 * fall inside the rectangular extent of the Munich MATSim network. Once a
 * trip is selected, its full sequence is retained, including calls beyond the
 * boundary. This preserves service continuity and avoids inventing artificial
 * termini at the study boundary.</p>
 *
 * <p>Large GTFS tables are processed record by record. Only identifiers and
 * compact metadata required for relational closure are kept in memory.</p>
 */
public final class BuildMunichGtfs2037 {

    private static final Path RAW = Path.of(
            "original-input-data/mvv_gtfs_2037/raw"
    );
    private static final Path GENERATED = Path.of(
            "original-input-data/mvv_gtfs_2037/generated"
    );
    private static final Path OUTPUT_ZIP = GENERATED.resolve(
            "gtfs2037_munich_clean.zip"
    );
    private static final Path NETWORK = Path.of(
            "scenarios/munich_base_2023/studyNetworkDense.xml"
    );
    private static final Path ROUTE_INVENTORY = Path.of(
            "docs/gtfs2040/gtfs2037_munich_routes.csv"
    );

    private static final List<String> GTFS_FILES = List.of(
            "agency.txt",
            "calendar.txt",
            "calendar_dates.txt",
            "routes.txt",
            "trips.txt",
            "stop_times.txt",
            "stops.txt",
            "shapes.txt",
            "transfers.txt"
    );
    private static final List<String> ZIP_FILES = List.of(
            // The raw calendar_dates table has no data rows. Omitting this
            // optional table avoids presenting an empty table as GTFS data;
            // calendar.txt remains the complete service definition.
            "agency.txt",
            "calendar.txt",
            "routes.txt",
            "trips.txt",
            "stop_times.txt",
            "stops.txt",
            "shapes.txt",
            "transfers.txt"
    );
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("_([0-9]+)$");
    private static final long REPRODUCIBLE_ZIP_TIME =
            Instant.parse("1980-01-01T00:00:00Z").toEpochMilli();

    private BuildMunichGtfs2037() {
        // Utility class
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = Arrays.asList(args).contains("--dry-run");
        new Builder().run(dryRun);
    }

    private static final class Builder {
        private final CoordinateTransformation transformation =
                TransformationFactory.getCoordinateTransformation(
                        TransformationFactory.WGS84,
                        "EPSG:31468"
                );

        private Extent extent;
        private Set<String> insideStopIds;
        private Set<String> retainedTripIds;
        private final Map<String, TripMeta> retainedTrips = new HashMap<>();
        private final Map<String, Integer> retainedTripsPerRoute = new HashMap<>();
        private final Map<String, RouteMeta> retainedRoutes = new TreeMap<>();
        private final Set<String> munichFlaggedRoutes = new TreeSet<>();
        private final Map<String, Classification> classifications = new TreeMap<>();
        private final List<String> classificationErrors = new ArrayList<>();
        private final List<AgencyCorrection> agencyCorrections = new ArrayList<>();

        private long rawTrips;
        private long rawRoutes;
        private long rawStops;
        private long retainedStopTimes;
        private long retainedStops;
        private long retainedShapePoints;
        private long retainedTransfers;

        void run(boolean dryRun) throws Exception {
            requireInputs();
            extent = readNetworkExtent(NETWORK);
            validateExtent(extent);
            insideStopIds = findStopsInsideExtent();
            retainedTripIds = selectTripsByInsideStops();
            readRetainedTripMetadata();
            readAndClassifyRoutes();
            printDryRunSummary();
            validatePlausibility();

            if (!classificationErrors.isEmpty()) {
                System.err.println("Unresolved route classifications:");
                classificationErrors.forEach(value -> System.err.println("  " + value));
                throw new IllegalStateException(
                        "The cleaned feed was not created because "
                                + classificationErrors.size()
                                + " retained routes could not be classified reliably."
                );
            }

            if (dryRun) {
                System.out.println("Dry run completed; no derived files were written.");
                return;
            }

            Files.createDirectories(GENERATED);
            Files.createDirectories(ROUTE_INVENTORY.getParent());
            Path work = Files.createTempDirectory(GENERATED, ".munich-clean-");
            try {
                buildFilteredFiles(work);
                ValidationCounts validation = validateFolder(work);
                writeReproducibleZip(work, OUTPUT_ZIP);
                validateZip(OUTPUT_ZIP, validation.rowsByFile());
                writeRouteInventory();
                printBuildSummary(validation);
            } finally {
                deleteOwnTemporaryDirectory(work);
            }
        }

        private void requireInputs() {
            if (!Files.isRegularFile(NETWORK)) {
                throw new IllegalStateException("Missing MATSim network: " + NETWORK);
            }
            for (String file : GTFS_FILES) {
                Path input = RAW.resolve(file);
                if (!Files.isRegularFile(input)) {
                    throw new IllegalStateException("Missing raw GTFS file: " + input);
                }
            }
        }

        private Set<String> findStopsInsideExtent() throws IOException {
            Set<String> inside = new HashSet<>();
            try (CsvTable table = new CsvTable(RAW.resolve("stops.txt"))) {
                int id = table.column("stop_id");
                int lat = table.column("stop_lat");
                int lon = table.column("stop_lon");
                String[] row;
                while ((row = table.next()) != null) {
                    rawStops++;
                    double latitude = parseCoordinate(row[lat], "stop_lat", table);
                    double longitude = parseCoordinate(row[lon], "stop_lon", table);
                    Coord projected = transformation.transform(
                            new Coord(longitude, latitude)
                    );
                    if (extent.contains(projected.getX(), projected.getY())) {
                        inside.add(row[id]);
                    }
                }
            }
            return inside;
        }

        private Set<String> selectTripsByInsideStops() throws IOException {
            Set<String> selected = new HashSet<>();
            Set<String> completedTrips = new HashSet<>();
            try (CsvTable table = new CsvTable(RAW.resolve("stop_times.txt"))) {
                int trip = table.column("trip_id");
                int stop = table.column("stop_id");
                String currentTrip = null;
                Set<String> distinctInside = new HashSet<>();
                String[] row;
                while ((row = table.next()) != null) {
                    String tripId = row[trip];
                    if (!tripId.equals(currentTrip)) {
                        if (currentTrip != null) {
                            finishTripSelection(
                                    currentTrip,
                                    distinctInside,
                                    selected,
                                    completedTrips
                            );
                        }
                        if (completedTrips.contains(tripId)) {
                            throw new IllegalStateException(
                                    "stop_times.txt is not grouped by trip_id; "
                                            + "trip reappeared: " + tripId
                            );
                        }
                        currentTrip = tripId;
                        distinctInside.clear();
                    }
                    if (insideStopIds.contains(row[stop])) {
                        distinctInside.add(row[stop]);
                    }
                }
                if (currentTrip != null) {
                    finishTripSelection(
                            currentTrip,
                            distinctInside,
                            selected,
                            completedTrips
                    );
                }
            }
            return selected;
        }

        private static void finishTripSelection(
                String tripId,
                Set<String> distinctInside,
                Set<String> selected,
                Set<String> completed
        ) {
            if (distinctInside.size() >= 2) {
                selected.add(tripId);
            }
            completed.add(tripId);
        }

        private void readRetainedTripMetadata() throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("trips.txt"))) {
                int trip = table.column("trip_id");
                int route = table.column("route_id");
                int service = table.column("service_id");
                int shape = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    rawTrips++;
                    if (retainedTripIds.contains(row[trip])) {
                        TripMeta meta = new TripMeta(
                                row[trip], row[route], row[service], row[shape]
                        );
                        retainedTrips.put(meta.tripId(), meta);
                        retainedTripsPerRoute.merge(meta.routeId(), 1, Integer::sum);
                    }
                }
            }
            if (retainedTrips.size() != retainedTripIds.size()) {
                throw new IllegalStateException(
                        "Selected stop-time trips are missing from trips.txt: selected="
                                + retainedTripIds.size() + ", found=" + retainedTrips.size()
                );
            }
        }

        private void readAndClassifyRoutes() throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("routes.txt"))) {
                int id = table.column("route_id");
                int agency = table.column("agency_id");
                int longName = table.column("route_long_name");
                int originalType = table.column("route_type");
                int munich = table.column("M\u00fcnchen");
                int railFlag = table.column("Analyselinie_Schiene");
                int ferryFlag = table.column("F\u00e4hrlinie_BY");
                int bmwFlag = table.column("BMW-Werkslinie");
                int deutschlandtaktFlag = table.column("Deutschlandtakt_2030");
                int deutschlandtaktName = table.column("Linienname_D-Takt");
                int forecast = table.column("Prognosenetz_2037");
                int sbahnName = table.column("Linienname S-Bahn MUC");
                int measure = table.column("\u00d6PSV_Prognosema\u00dfnahme");
                String[] row;
                while ((row = table.next()) != null) {
                    rawRoutes++;
                    if ("1".equals(row[munich])) {
                        munichFlaggedRoutes.add(row[id]);
                    }
                    if (!retainedTripsPerRoute.containsKey(row[id])) {
                        continue;
                    }
                    RouteMeta meta = new RouteMeta(
                            row[id],
                            row[agency],
                            row[longName],
                            row[originalType],
                            row[munich],
                            row[railFlag],
                            row[ferryFlag],
                            row[bmwFlag],
                            row[deutschlandtaktFlag],
                            row[deutschlandtaktName],
                            row[forecast],
                            row[sbahnName],
                            row[measure]
                    );
                    retainedRoutes.put(meta.routeId(), meta);
                    Classification classification = classify(meta);
                    if (classification == null) {
                        classificationErrors.add(
                                meta.routeId() + " | " + meta.routeName()
                                        + " | rail_flag=" + meta.railFlag()
                                        + " | ferry_flag=" + meta.ferryFlag()
                        );
                    } else {
                        classifications.put(meta.routeId(), classification);
                    }
                }
            }
            if (retainedRoutes.size() != retainedTripsPerRoute.size()) {
                throw new IllegalStateException(
                        "Retained trips reference routes missing from routes.txt."
                );
            }
        }

        private Classification classify(RouteMeta route) {
            String id = route.routeId();
            String name = route.routeName();
            String lower = (id + " " + name).toLowerCase(Locale.ROOT);

            if (lower.contains("muc_tram") && lower.contains("prognose")) {
                return new Classification("tram", 0, "forecast route name identifies Munich tram");
            }
            if ((lower.contains("_0_neu prognose")
                    || lower.startsWith("linie 5_augsburg_neu prognose"))) {
                return new Classification(
                        "tram", 0,
                        "forecast route name and sampled stop names identify Augsburg tram"
                );
            }
            if (lower.startsWith("muc_u") && lower.contains("prognose")) {
                return new Classification("subway", 1, "forecast route name identifies Munich U-Bahn");
            }
            if (!route.sbahnName().isBlank()
                    || (lower.startsWith("s") && lower.contains("_prognose_"))) {
                return new Classification("rail", 2, "S-Bahn custom name or forecast route name");
            }
            if ("1".equals(route.ferryFlag())) {
                return new Classification("ferry", 4, "custom F\u00e4hrlinie_BY flag");
            }
            if ("1".equals(route.deutschlandtaktFlag())
                    || !route.deutschlandtaktName().isBlank()) {
                return new Classification(
                        "rail", 2,
                        "Deutschlandtakt flag or Deutschlandtakt line name"
                );
            }
            if ("1".equals(route.railFlag())) {
                return new Classification("rail", 2, "custom Analyselinie_Schiene flag");
            }
            if ("1".equals(route.bmwFlag())) {
                return new Classification("bus", 3, "custom BMW-Werkslinie flag");
            }
            if (id.matches("L[0-9].*")) {
                return new Classification(
                        "bus", 3,
                        "L-prefixed Bavarian regional bus family, corroborated by stop names"
                );
            }
            if (lower.matches("(40 bad aibling|41 bad aibling|"
                    + "44 gro\\u00dfkarolinenfeld|45 bad aibling|"
                    + "46 bad aibling).*")) {
                return new Classification(
                        "bus", 3,
                        "Bad Aibling regional/local route family, corroborated by stop names"
                );
            }

            Matcher matcher = NUMERIC_SUFFIX.matcher(id);
            if (matcher.find()) {
                int encodedType = Integer.parseInt(matcher.group(1));
                Classification suffixClassification = switch (encodedType) {
                    case 0 -> new Classification("tram", 0, "route ID suffix encodes source type 0");
                    case 1 -> new Classification("subway", 1, "route ID suffix encodes source type 1");
                    case 2 -> new Classification("rail", 2, "route ID suffix encodes source type 2");
                    case 3 -> new Classification("bus", 3, "route ID suffix encodes source type 3");
                    default -> encodedType >= 100 && encodedType <= 117
                            ? new Classification(
                                    "rail", 2,
                                    "route ID suffix encodes extended rail type " + encodedType
                            )
                            : null;
                };
                if (suffixClassification != null) {
                    return checkClassificationConflict(route, suffixClassification);
                }
            }

            return null;
        }

        private Classification checkClassificationConflict(
                RouteMeta route,
                Classification classification
        ) {
            if ("1".equals(route.railFlag())
                    && !"rail".equals(classification.mode())) {
                return null;
            }
            return classification;
        }

        private void printDryRunSummary() {
            Set<String> flaggedExcluded = new TreeSet<>(munichFlaggedRoutes);
            flaggedExcluded.removeAll(retainedRoutes.keySet());
            Set<String> unmarkedIncluded = new TreeSet<>(retainedRoutes.keySet());
            unmarkedIncluded.removeAll(munichFlaggedRoutes);

            System.out.printf(
                    Locale.ROOT,
                    "Network extent: %.3f..%.3f x %.3f..%.3f "
                            + "(%.1f x %.1f km; %,d nodes)%n",
                    extent.minX(), extent.maxX(), extent.minY(), extent.maxY(),
                    extent.widthKm(), extent.heightKm(), extent.nodes()
            );
            System.out.printf(
                    Locale.ROOT,
                    "Spatial selection: %,d of %,d stops inside extent; "
                            + "%,d of %,d trips retained; %,d of %,d routes retained.%n",
                    insideStopIds.size(), rawStops,
                    retainedTripIds.size(), rawTrips,
                    retainedRoutes.size(), rawRoutes
            );
            System.out.println(
                    "M\u00fcnchen=1 comparison: flagged routes excluded="
                            + flaggedExcluded.size() + ", unmarked routes included="
                            + unmarkedIncluded.size()
            );
            if (!flaggedExcluded.isEmpty()) {
                System.out.println("Flagged but excluded: " + String.join(" | ", flaggedExcluded));
            }
            System.out.println("Retained routes by inferred mode: " + modeRouteCounts());
            System.out.println("Retained trips by inferred mode: " + modeTripCounts());
            System.out.println("Unresolved retained route classifications: "
                    + classificationErrors.size());
        }

        private void validatePlausibility() {
            if (extent.nodes() < 1_000
                    || extent.widthKm() < 10 || extent.heightKm() < 10
                    || extent.widthKm() > 250 || extent.heightKm() > 250) {
                throw new IllegalStateException(
                        "The MATSim network extent is implausible as a study boundary: "
                                + extent
                );
            }
            Coord munichCentre = transformation.transform(new Coord(11.576, 48.137));
            if (!extent.contains(munichCentre.getX(), munichCentre.getY())) {
                throw new IllegalStateException(
                        "The network extent does not contain Munich city centre."
                );
            }
            if (insideStopIds.size() < 100 || retainedTripIds.size() < 100) {
                throw new IllegalStateException(
                        "The spatial rule retained too little public transport data."
                );
            }
            if (retainedTripIds.size() > rawTrips * 0.75
                    || retainedRoutes.size() > rawRoutes * 0.60) {
                throw new IllegalStateException(
                        "The spatial rule retained an implausibly large share of the "
                                + "Germany-wide feed; no final feed was created."
                );
            }
        }

        private void buildFilteredFiles(Path work) throws IOException {
            copySelectedTrips(work.resolve("trips.txt"));
            Set<String> retainedStopIds = copySelectedStopTimes(
                    work.resolve("stop_times.txt")
            );
            Set<String> parentIds = findRequiredParents(retainedStopIds);
            retainedStopIds.addAll(parentIds);
            copySelectedStops(work.resolve("stops.txt"), retainedStopIds);

            Set<String> shapeIds = retainedTrips.values().stream()
                    .map(TripMeta::shapeId)
                    .collect(java.util.stream.Collectors.toSet());
            copySelectedShapes(work.resolve("shapes.txt"), shapeIds);
            copyCorrectedRoutes(work.resolve("routes.txt"));

            Set<String> agencyIds = retainedRoutes.values().stream()
                    .map(RouteMeta::agencyId)
                    .collect(java.util.stream.Collectors.toSet());
            copyCorrectedAgencies(work.resolve("agency.txt"), agencyIds);

            Set<String> serviceIds = retainedTrips.values().stream()
                    .map(TripMeta::serviceId)
                    .collect(java.util.stream.Collectors.toSet());
            copyById(
                    RAW.resolve("calendar.txt"), work.resolve("calendar.txt"),
                    "service_id", serviceIds
            );
            copyById(
                    RAW.resolve("calendar_dates.txt"),
                    work.resolve("calendar_dates.txt"),
                    "service_id", serviceIds
            );
            copyInternalTransfers(work.resolve("transfers.txt"), retainedStopIds);
        }

        private void copySelectedTrips(Path output) throws IOException {
            filterTable(
                    RAW.resolve("trips.txt"), output,
                    table -> table.column("trip_id"),
                    row -> retainedTripIds.contains(row.key())
            );
        }

        private Set<String> copySelectedStopTimes(Path output) throws IOException {
            Set<String> stops = new HashSet<>();
            try (CsvTable table = new CsvTable(RAW.resolve("stop_times.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int trip = table.column("trip_id");
                int stop = table.column("stop_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (retainedTripIds.contains(row[trip])) {
                        sink.write(row);
                        retainedStopTimes++;
                        stops.add(row[stop]);
                    }
                }
            }
            return stops;
        }

        private Set<String> findRequiredParents(Set<String> retainedStopIds)
                throws IOException {
            Set<String> parents = new HashSet<>();
            try (CsvTable table = new CsvTable(RAW.resolve("stops.txt"))) {
                int id = table.column("stop_id");
                int parent = table.column("parent_station");
                String[] row;
                while ((row = table.next()) != null) {
                    if (retainedStopIds.contains(row[id]) && !row[parent].isBlank()) {
                        parents.add(row[parent]);
                    }
                }
            }
            return parents;
        }

        private void copySelectedStops(Path output, Set<String> retainedStopIds)
                throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("stops.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int id = table.column("stop_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (retainedStopIds.contains(row[id])) {
                        sink.write(row);
                        retainedStops++;
                    }
                }
            }
        }

        private void copySelectedShapes(Path output, Set<String> retainedShapeIds)
                throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("shapes.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int id = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (retainedShapeIds.contains(row[id])) {
                        sink.write(row);
                        retainedShapePoints++;
                    }
                }
            }
        }

        private void copyCorrectedRoutes(Path output) throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("routes.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int id = table.column("route_id");
                int type = table.column("route_type");
                String[] row;
                while ((row = table.next()) != null) {
                    Classification classification = classifications.get(row[id]);
                    if (classification != null) {
                        row[type] = Integer.toString(classification.correctedType());
                        sink.write(row);
                    }
                }
            }
        }

        private void copyCorrectedAgencies(Path output, Set<String> agencyIds)
                throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("agency.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int id = table.column("agency_id");
                int timezone = table.column("agency_timezone");
                int url = table.column("agency_url");
                String[] row;
                while ((row = table.next()) != null) {
                    if (!agencyIds.contains(row[id])) {
                        continue;
                    }
                    if ("unknown".equals(row[id])
                            && !"Europe/Berlin".equals(row[timezone])) {
                        agencyCorrections.add(new AgencyCorrection(
                                row[id], "agency_timezone", row[timezone], "Europe/Berlin"
                        ));
                        row[timezone] = "Europe/Berlin";
                    }
                    if (!isValidHttpUrl(row[url])) {
                        String replacement = "https://example.invalid/gtfs-agency/"
                                + URLEncoder.encode(row[id], StandardCharsets.UTF_8)
                                .replace("+", "%20");
                        agencyCorrections.add(new AgencyCorrection(
                                row[id], "agency_url", row[url], replacement
                        ));
                        row[url] = replacement;
                    }
                    sink.write(row);
                }
            }
        }

        private static boolean isValidHttpUrl(String value) {
            try {
                URI uri = URI.create(value);
                return ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && !uri.getHost().isBlank();
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        private void copyById(Path input, Path output, String key, Set<String> ids)
                throws IOException {
            try (CsvTable table = new CsvTable(input);
                    CsvSink sink = new CsvSink(output, table.header())) {
                int index = table.column(key);
                String[] row;
                while ((row = table.next()) != null) {
                    if (ids.contains(row[index])) {
                        sink.write(row);
                    }
                }
            }
        }

        private void copyInternalTransfers(Path output, Set<String> retainedStopIds)
                throws IOException {
            try (CsvTable table = new CsvTable(RAW.resolve("transfers.txt"));
                    CsvSink sink = new CsvSink(output, table.header())) {
                int from = table.column("from_stop_id");
                int to = table.column("to_stop_id");
                String[] row;
                while ((row = table.next()) != null) {
                    if (retainedStopIds.contains(row[from])
                            && retainedStopIds.contains(row[to])) {
                        sink.write(row);
                        retainedTransfers++;
                    }
                }
            }
        }

        private interface KeyIndex {
            int index(CsvTable table);
        }

        private interface KeyPredicate {
            boolean retain(KeyedRow row);
        }

        private record KeyedRow(String key, String[] fields) {
        }

        private static void filterTable(
                Path input,
                Path output,
                KeyIndex keyIndex,
                KeyPredicate predicate
        ) throws IOException {
            try (CsvTable table = new CsvTable(input);
                    CsvSink sink = new CsvSink(output, table.header())) {
                int index = keyIndex.index(table);
                String[] row;
                while ((row = table.next()) != null) {
                    if (predicate.retain(new KeyedRow(row[index], row))) {
                        sink.write(row);
                    }
                }
            }
        }

        private ValidationCounts validateFolder(Path folder) throws IOException {
            Map<String, Long> rows = new LinkedHashMap<>();
            Set<String> agencies = readUniqueIds(folder.resolve("agency.txt"), "agency_id", rows);
            Set<String> routes = readUniqueIds(folder.resolve("routes.txt"), "route_id", rows);
            Set<String> trips = readUniqueIds(folder.resolve("trips.txt"), "trip_id", rows);
            Set<String> stops = readUniqueIds(folder.resolve("stops.txt"), "stop_id", rows);
            Set<String> services = readUniqueIds(folder.resolve("calendar.txt"), "service_id", rows);
            addIds(folder.resolve("calendar_dates.txt"), "service_id", services, rows);
            Set<String> shapes = readShapeIdsAndValidate(folder.resolve("shapes.txt"), rows);

            validateAgencies(folder.resolve("agency.txt"));
            validateRoutes(folder.resolve("routes.txt"), agencies);
            validateStops(folder.resolve("stops.txt"), stops);
            validateTrips(folder.resolve("trips.txt"), routes, services, shapes);
            validateStopTimes(folder.resolve("stop_times.txt"), trips, stops, rows);
            validateTransfers(folder.resolve("transfers.txt"), stops, rows);

            return new ValidationCounts(rows);
        }

        private Set<String> readUniqueIds(
                Path file,
                String key,
                Map<String, Long> rows
        ) throws IOException {
            Set<String> ids = new HashSet<>();
            long count = 0;
            try (CsvTable table = new CsvTable(file)) {
                int index = table.column(key);
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    if (row[index].isBlank()) {
                        throw new IllegalStateException(
                                "Blank " + key + " in " + file
                        );
                    }
                    if (!ids.add(row[index])) {
                        throw new IllegalStateException(
                                "Duplicate " + key + " in " + file + ": " + row[index]
                        );
                    }
                }
            }
            rows.put(file.getFileName().toString(), count);
            return ids;
        }

        private void addIds(
                Path file,
                String key,
                Set<String> ids,
                Map<String, Long> rows
        ) throws IOException {
            long count = 0;
            try (CsvTable table = new CsvTable(file)) {
                int index = table.column(key);
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    ids.add(row[index]);
                }
            }
            rows.put(file.getFileName().toString(), count);
        }

        private Set<String> readShapeIdsAndValidate(
                Path file,
                Map<String, Long> rows
        ) throws IOException {
            Set<String> ids = new HashSet<>();
            String previous = null;
            long previousSequence = Long.MIN_VALUE;
            long count = 0;
            try (CsvTable table = new CsvTable(file)) {
                int id = table.column("shape_id");
                int sequence = table.column("shape_pt_sequence");
                int lat = table.column("shape_pt_lat");
                int lon = table.column("shape_pt_lon");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    validateLatLon(row[lat], row[lon], "shape", table);
                    long value = parseLong(row[sequence], "shape_pt_sequence", table);
                    if (!row[id].equals(previous) && ids.contains(row[id])) {
                        throw new IllegalStateException(
                                "Shape rows are not grouped; shape reappeared: " + row[id]
                        );
                    }
                    if (row[id].equals(previous) && value <= previousSequence) {
                        throw new IllegalStateException(
                                "Non-increasing shape sequence for " + row[id]
                        );
                    }
                    ids.add(row[id]);
                    previous = row[id];
                    previousSequence = value;
                }
            }
            rows.put(file.getFileName().toString(), count);
            return ids;
        }

        private void validateAgencies(Path file) throws IOException {
            try (CsvTable table = new CsvTable(file)) {
                int timezone = table.column("agency_timezone");
                int url = table.column("agency_url");
                String[] row;
                while ((row = table.next()) != null) {
                    if (row[timezone].isBlank() || "unknown".equals(row[timezone])) {
                        throw new IllegalStateException("Invalid retained agency timezone.");
                    }
                    if (!isValidHttpUrl(row[url])) {
                        throw new IllegalStateException("Invalid retained agency URL: " + row[url]);
                    }
                }
            }
        }

        private void validateRoutes(Path file, Set<String> agencies) throws IOException {
            try (CsvTable table = new CsvTable(file)) {
                int id = table.column("route_id");
                int agency = table.column("agency_id");
                int type = table.column("route_type");
                String[] row;
                while ((row = table.next()) != null) {
                    requireReference(agencies, row[agency], "route agency", row[id]);
                    int value = Integer.parseInt(row[type]);
                    Classification classification = classifications.get(row[id]);
                    if (classification == null
                            || value != classification.correctedType()) {
                        throw new IllegalStateException(
                                "Undocumented or invalid route classification: " + row[id]
                        );
                    }
                }
            }
        }

        private void validateStops(Path file, Set<String> stops) throws IOException {
            try (CsvTable table = new CsvTable(file)) {
                int id = table.column("stop_id");
                int parent = table.column("parent_station");
                int lat = table.column("stop_lat");
                int lon = table.column("stop_lon");
                String[] row;
                while ((row = table.next()) != null) {
                    validateLatLon(row[lat], row[lon], "stop", table);
                    if (!row[parent].isBlank()) {
                        requireReference(stops, row[parent], "parent station", row[id]);
                    }
                }
            }
        }

        private void validateTrips(
                Path file,
                Set<String> routes,
                Set<String> services,
                Set<String> shapes
        ) throws IOException {
            try (CsvTable table = new CsvTable(file)) {
                int id = table.column("trip_id");
                int route = table.column("route_id");
                int service = table.column("service_id");
                int shape = table.column("shape_id");
                String[] row;
                while ((row = table.next()) != null) {
                    requireReference(routes, row[route], "trip route", row[id]);
                    requireReference(services, row[service], "trip service", row[id]);
                    requireReference(shapes, row[shape], "trip shape", row[id]);
                }
            }
        }

        private void validateStopTimes(
                Path file,
                Set<String> trips,
                Set<String> stops,
                Map<String, Long> rows
        ) throws IOException {
            Set<String> tripsWithCalls = new HashSet<>();
            Set<String> completedTrips = new HashSet<>();
            String previousTrip = null;
            long previousSequence = Long.MIN_VALUE;
            int previousDeparture = -1;
            long count = 0;
            try (CsvTable table = new CsvTable(file)) {
                int trip = table.column("trip_id");
                int stop = table.column("stop_id");
                int sequence = table.column("stop_sequence");
                int arrival = table.column("arrival_time");
                int departure = table.column("departure_time");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    requireReference(trips, row[trip], "stop-time trip", row[trip]);
                    requireReference(stops, row[stop], "stop-time stop", row[trip]);
                    if (!row[trip].equals(previousTrip)) {
                        if (previousTrip != null) {
                            completedTrips.add(previousTrip);
                        }
                        if (completedTrips.contains(row[trip])) {
                            throw new IllegalStateException(
                                    "Stop-time rows are not grouped; trip reappeared: "
                                            + row[trip]
                            );
                        }
                        previousDeparture = -1;
                    }
                    long value = parseLong(row[sequence], "stop_sequence", table);
                    if (row[trip].equals(previousTrip) && value <= previousSequence) {
                        throw new IllegalStateException(
                                "Non-increasing stop sequence for trip " + row[trip]
                        );
                    }
                    int arrivalSeconds = parseGtfsTime(row[arrival], table);
                    int departureSeconds = parseGtfsTime(row[departure], table);
                    if (arrivalSeconds > departureSeconds) {
                        throw new IllegalStateException(
                                "Arrival is after departure for trip " + row[trip]
                        );
                    }
                    if (previousDeparture > arrivalSeconds) {
                        throw new IllegalStateException(
                                "Times decrease between stops for trip " + row[trip]
                        );
                    }
                    tripsWithCalls.add(row[trip]);
                    previousTrip = row[trip];
                    previousSequence = value;
                    previousDeparture = departureSeconds;
                }
            }
            if (!tripsWithCalls.equals(trips)) {
                Set<String> missing = new HashSet<>(trips);
                missing.removeAll(tripsWithCalls);
                throw new IllegalStateException(
                        "Trips without stop times: " + missing.stream().limit(10).toList()
                );
            }
            rows.put(file.getFileName().toString(), count);
        }

        private void validateTransfers(
                Path file,
                Set<String> stops,
                Map<String, Long> rows
        ) throws IOException {
            long count = 0;
            try (CsvTable table = new CsvTable(file)) {
                int from = table.column("from_stop_id");
                int to = table.column("to_stop_id");
                String[] row;
                while ((row = table.next()) != null) {
                    count++;
                    requireReference(stops, row[from], "transfer from-stop", row[from]);
                    requireReference(stops, row[to], "transfer to-stop", row[to]);
                }
            }
            rows.put(file.getFileName().toString(), count);
        }

        private static void requireReference(
                Set<String> ids,
                String reference,
                String relation,
                String owner
        ) {
            if (!ids.contains(reference)) {
                throw new IllegalStateException(
                        "Missing " + relation + " reference " + reference
                                + " from " + owner
                );
            }
        }

        private static void validateLatLon(
                String latitude,
                String longitude,
                String kind,
                CsvTable table
        ) {
            double lat = parseCoordinate(latitude, kind + " latitude", table);
            double lon = parseCoordinate(longitude, kind + " longitude", table);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalStateException(
                        "Invalid " + kind + " coordinates at " + table.location()
                );
            }
        }

        private static double parseCoordinate(
                String value,
                String field,
                CsvTable table
        ) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException error) {
                throw new IllegalStateException(
                        "Invalid " + field + " at " + table.location() + ": " + value,
                        error
                );
            }
        }

        private static long parseLong(String value, String field, CsvTable table) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException error) {
                throw new IllegalStateException(
                        "Invalid " + field + " at " + table.location() + ": " + value,
                        error
                );
            }
        }

        private static int parseGtfsTime(String value, CsvTable table) {
            String[] parts = value.split(":", -1);
            if (parts.length != 3) {
                throw new IllegalStateException(
                        "Invalid GTFS time at " + table.location() + ": " + value
                );
            }
            try {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                if (hours < 0 || minutes < 0 || minutes > 59
                        || seconds < 0 || seconds > 59) {
                    throw new NumberFormatException("out of range");
                }
                return hours * 3600 + minutes * 60 + seconds;
            } catch (NumberFormatException error) {
                throw new IllegalStateException(
                        "Invalid GTFS time at " + table.location() + ": " + value,
                        error
                );
            }
        }

        private void writeReproducibleZip(Path folder, Path output)
                throws Exception {
            Path temporary = Files.createTempFile(
                    output.getParent(), output.getFileName() + ".", ".tmp"
            );
            try {
                try (ZipOutputStream zip = new ZipOutputStream(
                        Files.newOutputStream(temporary), StandardCharsets.UTF_8
                )) {
                    zip.setLevel(Deflater.BEST_COMPRESSION);
                    for (String name : ZIP_FILES) {
                        ZipEntry entry = new ZipEntry(name);
                        entry.setTime(REPRODUCIBLE_ZIP_TIME);
                        zip.putNextEntry(entry);
                        Files.copy(folder.resolve(name), zip);
                        zip.closeEntry();
                    }
                }
                try {
                    Files.move(
                            temporary, output,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(
                            temporary, output,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private void validateZip(Path zipPath, Map<String, Long> expectedRows)
                throws IOException {
            try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
                List<String> names = zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .map(ZipEntry::getName)
                        .sorted()
                        .toList();
                List<String> expectedNames = ZIP_FILES.stream().sorted().toList();
                if (!names.equals(expectedNames)) {
                    throw new IllegalStateException(
                            "Generated ZIP root entries differ from the required GTFS files: "
                                    + names
                    );
                }
                for (String name : ZIP_FILES) {
                    long rows;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    zip.getInputStream(zip.getEntry(name)),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                        if (reader.readLine() == null) {
                            throw new IllegalStateException("Empty ZIP entry: " + name);
                        }
                        rows = reader.lines().count();
                    }
                    if (rows != expectedRows.get(name)) {
                        throw new IllegalStateException(
                                "ZIP reread count differs for " + name + ": " + rows
                        );
                    }
                }
            }
        }

        private void writeRouteInventory() throws IOException {
            Path temporary = ROUTE_INVENTORY.resolveSibling(
                    ROUTE_INVENTORY.getFileName() + ".tmp"
            );
            String[] header = {
                    "route_id", "route_name", "inferred_mode",
                    "original_route_type", "corrected_route_type",
                    "selection_reason", "classification_basis",
                    "retained_trips", "agency_id", "munich_flag",
                    "analysis_rail_flag", "ferry_flag", "bmw_works_line_flag",
                    "deutschlandtakt_flag", "deutschlandtakt_line_name",
                    "forecast_network_2037_flag", "forecast_measure_flag",
                    "s_bahn_name"
            };
            try (CsvSink sink = new CsvSink(temporary, header)) {
                for (RouteMeta route : retainedRoutes.values()) {
                    Classification classification = classifications.get(route.routeId());
                    sink.write(new String[] {
                            route.routeId(),
                            route.routeName(),
                            classification.mode(),
                            route.originalType(),
                            Integer.toString(classification.correctedType()),
                            "trip serves at least two distinct stops inside network extent",
                            classification.basis(),
                            Integer.toString(retainedTripsPerRoute.get(route.routeId())),
                            route.agencyId(),
                            route.munichFlag(),
                            route.railFlag(),
                            route.ferryFlag(),
                            route.bmwFlag(),
                            route.deutschlandtaktFlag(),
                            route.deutschlandtaktName(),
                            route.forecastFlag(),
                            route.measureFlag(),
                            route.sbahnName()
                    });
                }
            }
            Files.move(temporary, ROUTE_INVENTORY, StandardCopyOption.REPLACE_EXISTING);
        }

        private void printBuildSummary(ValidationCounts validation) throws Exception {
            System.out.println("Clean Munich GTFS-2037 feed created successfully.");
            System.out.println("Output ZIP: " + OUTPUT_ZIP);
            System.out.println("Output ZIP SHA-256: " + sha256(OUTPUT_ZIP));
            System.out.println("Output ZIP bytes: " + Files.size(OUTPUT_ZIP));
            System.out.println("Validated output rows: " + validation.rowsByFile());
            System.out.println("Retained routes by mode: " + modeRouteCounts());
            System.out.println("Retained trips by mode: " + modeTripCounts());
            System.out.println("Retained stop-time rows: " + retainedStopTimes);
            System.out.println("Retained stop records including parents: " + retainedStops);
            System.out.println("Retained shape points: " + retainedShapePoints);
            System.out.println("Retained internal transfers: " + retainedTransfers);
            System.out.println("Agency metadata replacements: " + agencyCorrections.size());
            for (AgencyCorrection correction : agencyCorrections) {
                System.out.println(
                        "  agency=" + correction.agencyId()
                                + " field=" + correction.field()
                                + " old=" + correction.oldValue()
                                + " new=" + correction.newValue()
                );
            }
        }

        private Map<String, Long> modeRouteCounts() {
            Map<String, Long> counts = new TreeMap<>();
            classifications.values().forEach(value ->
                    counts.merge(value.mode(), 1L, Long::sum)
            );
            return counts;
        }

        private Map<String, Long> modeTripCounts() {
            Map<String, Long> counts = new TreeMap<>();
            for (Map.Entry<String, Integer> entry : retainedTripsPerRoute.entrySet()) {
                Classification classification = classifications.get(entry.getKey());
                if (classification != null) {
                    counts.merge(
                            classification.mode(),
                            entry.getValue().longValue(),
                            Long::sum
                    );
                }
            }
            return counts;
        }
    }

    private static Extent readNetworkExtent(Path network) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        double[] extent = {
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };
        long[] nodes = {0};
        try (InputStream input = Files.newInputStream(network)) {
            InputSource source = new InputSource(input);
            factory.newSAXParser().parse(source, new DefaultHandler() {
                @Override
                public void startElement(
                        String uri,
                        String localName,
                        String qName,
                        Attributes attributes
                ) {
                    if (!"node".equals(qName)) {
                        return;
                    }
                    double x = Double.parseDouble(attributes.getValue("x"));
                    double y = Double.parseDouble(attributes.getValue("y"));
                    extent[0] = Math.min(extent[0], x);
                    extent[1] = Math.min(extent[1], y);
                    extent[2] = Math.max(extent[2], x);
                    extent[3] = Math.max(extent[3], y);
                    nodes[0]++;
                }
            });
        }
        return new Extent(
                extent[0], extent[1], extent[2], extent[3], nodes[0]
        );
    }

    private static void validateExtent(Extent extent) {
        if (!Double.isFinite(extent.minX()) || extent.nodes() == 0) {
            throw new IllegalStateException("No network nodes found in " + NETWORK);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(
                Files.newInputStream(file), digest
        )) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void deleteOwnTemporaryDirectory(Path work) throws IOException {
        Path generated = GENERATED.toAbsolutePath().normalize();
        Path target = work.toAbsolutePath().normalize();
        if (!target.startsWith(generated)
                || !target.getFileName().toString().startsWith(".munich-clean-")) {
            throw new IllegalStateException(
                    "Refusing to clean unexpected temporary path: " + target
            );
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Extent(
            double minX,
            double minY,
            double maxX,
            double maxY,
            long nodes
    ) {
        boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        double widthKm() {
            return (maxX - minX) / 1_000;
        }

        double heightKm() {
            return (maxY - minY) / 1_000;
        }
    }

    private record TripMeta(
            String tripId,
            String routeId,
            String serviceId,
            String shapeId
    ) {
    }

    private record RouteMeta(
            String routeId,
            String agencyId,
            String routeName,
            String originalType,
            String munichFlag,
            String railFlag,
            String ferryFlag,
            String bmwFlag,
            String deutschlandtaktFlag,
            String deutschlandtaktName,
            String forecastFlag,
            String sbahnName,
            String measureFlag
    ) {
    }

    private record Classification(String mode, int correctedType, String basis) {
    }

    private record AgencyCorrection(
            String agencyId,
            String field,
            String oldValue,
            String newValue
    ) {
    }

    private record ValidationCounts(Map<String, Long> rowsByFile) {
    }

    private static final class CsvTable implements AutoCloseable {
        private final Path path;
        private final BufferedReader reader;
        private final String[] header;
        private final Map<String, Integer> columns = new HashMap<>();
        private long recordNumber = 1;

        CsvTable(Path path) throws IOException {
            this.path = path;
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            String first = reader.readLine();
            if (first == null) {
                throw new IllegalStateException("CSV file is empty: " + path);
            }
            if (!first.isEmpty() && first.charAt(0) == '\ufeff') {
                first = first.substring(1);
            }
            this.header = parseCsv(first, path, recordNumber);
            for (int index = 0; index < header.length; index++) {
                if (columns.put(header[index], index) != null) {
                    throw new IllegalStateException(
                            "Duplicate CSV column in " + path + ": " + header[index]
                    );
                }
            }
        }

        String[] next() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            recordNumber++;
            String[] row = parseCsv(line, path, recordNumber);
            if (row.length != header.length) {
                throw new IllegalStateException(
                        "CSV field count differs from header at " + location()
                                + ": expected " + header.length + ", found " + row.length
                );
            }
            return row;
        }

        int column(String name) {
            Integer index = columns.get(name);
            if (index == null) {
                throw new IllegalStateException(
                        "Required column " + name + " is missing from " + path
                );
            }
            return index;
        }

        String[] header() {
            return header.clone();
        }

        String location() {
            return path + " record " + recordNumber;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class CsvSink implements AutoCloseable {
        private final BufferedWriter writer;

        CsvSink(Path path, String[] header) throws IOException {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            write(header);
        }

        void write(String[] row) throws IOException {
            for (int index = 0; index < row.length; index++) {
                if (index > 0) {
                    writer.write(',');
                }
                writeField(writer, row[index]);
            }
            writer.newLine();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    private static String[] parseCsv(String line, Path path, long record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < line.length()
                        && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(value);
            }
        }
        if (quoted) {
            throw new IllegalStateException(
                    "Unclosed CSV quote in " + path + " record " + record
            );
        }
        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    private static void writeField(BufferedWriter writer, String value)
            throws IOException {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (!quote) {
            writer.write(value);
            return;
        }
        writer.write('"');
        writer.write(value.replace("\"", "\"\""));
        writer.write('"');
    }
}
