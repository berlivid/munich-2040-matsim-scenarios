package org.matsim.project.prepare;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * Creates the synthetic 2019 reference supply from the combined forecast data.
 * Complete trips are retained only when at least two distinct calls fall inside
 * the public Munich model-network extent. No selected trip is cut at that extent.
 */
public final class BuildSyntheticGtfs2019Reference {
    static final Path SOURCE = AnalyzeGtfs2019CalibrationInput.SOURCE;
    static final Path NETWORK = Path.of("scenarios/munich_base_2023/studyNetworkDense.xml");
    static final Path OUTPUT = Path.of("original-input-data/mvv_gtfs_2019/synthetic_2019_reference.zip");
    static final String EXPECTED_SOURCE_SHA =
            "92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4";
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("_([0-9]+)$");
    private static final long ZIP_TIME = Instant.parse("1980-01-01T00:00:00Z").toEpochMilli();
    private static final List<String> TABLES = List.of("agency.txt", "calendar.txt", "routes.txt",
            "trips.txt", "stop_times.txt", "stops.txt", "shapes.txt", "transfers.txt");

    private BuildSyntheticGtfs2019Reference() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "analyze" : args[0];
        if (!mode.equals("analyze") && !mode.equals("build")) {
            throw new IllegalArgumentException("Use analyze or build");
        }
        Result result = new Builder().run(mode.equals("build"));
        System.out.print(result.asText());
    }

    static Result analyze() throws Exception { return new Builder().run(false); }

    private static final class Builder {
        private final CoordinateTransformation transform = TransformationFactory
                .getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:31468");
        private final Map<String, StopMeta> stops = new HashMap<>();
        private final Map<String, RouteMeta> analysisRoutes = new HashMap<>();
        private final Map<String, TripMeta> analysisTrips = new HashMap<>();
        private final Map<String, Classification> classifications = new TreeMap<>();
        private final Set<String> insideStops = new HashSet<>();
        private final Set<String> selectedTrips = new HashSet<>();
        private final Set<String> selectedRoutes = new TreeSet<>();
        private final Set<String> selectedStops = new HashSet<>();
        private final Set<String> selectedShapes = new HashSet<>();
        private final Set<String> selectedServices = new HashSet<>();
        private final Set<String> selectedAgencies = new HashSet<>();
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, Long> modeRoutes = new TreeMap<>();
        private final Map<String, Long> modeTrips = new TreeMap<>();
        private long sourceRoutes, sourceTrips, sourceStopTimes;

        Result run(boolean build) throws Exception {
            require(Files.isRegularFile(SOURCE), "Missing source " + SOURCE);
            require(Files.isRegularFile(NETWORK), "Missing base network " + NETWORK);
            require(EXPECTED_SOURCE_SHA.equals(AnalyzeGtfs2019CalibrationInput.sha256(SOURCE)),
                    "Source checksum differs from approved specification");
            AnalyzeGtfs2019CalibrationInput.Analysis raw =
                    AnalyzeGtfs2019CalibrationInput.analyze(SOURCE);
            require(raw.blockers().isEmpty(), "Raw GTFS reference validation failed: " + raw.blockers());
            Extent extent = extent();
            try (ZipFile zip = zip(SOURCE)) {
                readStops(zip, extent);
                readRoutes(zip);
                readTrips(zip);
                selectTrips(zip);
                closeSelection(zip);
                classifySelectedRoutes();
                if (build) write(zip);
            }
            String outputSha = build ? AnalyzeGtfs2019CalibrationInput.sha256(OUTPUT) : "NOT_WRITTEN";
            if (build) {
                AnalyzeGtfs2019CalibrationInput.Analysis subset =
                        AnalyzeGtfs2019CalibrationInput.analyze(OUTPUT);
                require(subset.blockers().isEmpty(), "Generated subset failed validation: " + subset.blockers());
                require(subset.routes() == selectedRoutes.size(), "Subset route count mismatch");
                require(subset.trips() == selectedTrips.size(), "Subset trip count mismatch");
                require(subset.analysis2019FlaggedRoutes() == subset.routes(),
                        "Subset contains a route outside Analyse_2019=1");
            }
            return new Result(sourceRoutes, analysisRoutes.size(), sourceTrips,
                    analysisTrips.size(), selectedRoutes.size(), selectedTrips.size(),
                    selectedStops.size(), counts.getOrDefault("stop_times.txt", 0L),
                    counts.getOrDefault("shapes.txt", 0L),
                    counts.getOrDefault("transfers.txt", 0L), Map.copyOf(modeRoutes),
                    Map.copyOf(modeTrips), outputSha, build);
        }

        private Extent extent() {
            Config config = ConfigUtils.createConfig();
            config.network().setInputFile(NETWORK.toString());
            Scenario scenario = ScenarioUtils.loadScenario(config);
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (var node : scenario.getNetwork().getNodes().values()) {
                minX = Math.min(minX, node.getCoord().getX()); maxX = Math.max(maxX, node.getCoord().getX());
                minY = Math.min(minY, node.getCoord().getY()); maxY = Math.max(maxY, node.getCoord().getY());
            }
            require(scenario.getNetwork().getNodes().size() > 1_000, "Implausible base network");
            return new Extent(minX, maxX, minY, maxY);
        }

        private void readStops(ZipFile zip, Extent extent) throws Exception {
            rows(zip, "stops.txt", (reader, row) -> {
                String id = value(reader, row, "stop_id");
                double lat = Double.parseDouble(value(reader, row, "stop_lat"));
                double lon = Double.parseDouble(value(reader, row, "stop_lon"));
                Coord c = transform.transform(new Coord(lon, lat));
                stops.put(id, new StopMeta(id, optional(reader, row, "parent_station")));
                if (extent.contains(c)) insideStops.add(id);
            });
        }

        private void readRoutes(ZipFile zip) throws Exception {
            rows(zip, "routes.txt", (reader, row) -> {
                sourceRoutes++;
                String id = value(reader, row, "route_id");
                if (!"1".equals(optional(reader, row, "Analyse_2019"))) return;
                analysisRoutes.put(id, new RouteMeta(id, value(reader, row, "agency_id"),
                        value(reader, row, "route_long_name"),
                        optional(reader, row, "Analyselinie_Schiene"),
                        optional(reader, row, "F\u00e4hrlinie_BY"),
                        optional(reader, row, "BMW-Werkslinie"),
                        optional(reader, row, "Deutschlandtakt_2030"),
                        optional(reader, row, "Linienname_D-Takt"),
                        optional(reader, row, "Linienname S-Bahn MUC")));
            });
        }

        private void readTrips(ZipFile zip) throws Exception {
            rows(zip, "trips.txt", (reader, row) -> {
                sourceTrips++;
                String route = value(reader, row, "route_id");
                if (!analysisRoutes.containsKey(route)) return;
                String id = value(reader, row, "trip_id");
                analysisTrips.put(id, new TripMeta(id, route, value(reader, row, "service_id"),
                        optional(reader, row, "shape_id")));
            });
        }

        private void selectTrips(ZipFile zip) throws Exception {
            final String[] current = {null};
            Set<String> calls = new HashSet<>();
            Set<String> completed = new HashSet<>();
            rows(zip, "stop_times.txt", (reader, row) -> {
                sourceStopTimes++;
                String trip = value(reader, row, "trip_id");
                if (!trip.equals(current[0])) {
                    finish(current[0], calls, completed);
                    require(!completed.contains(trip), "stop_times trip reappears: " + trip);
                    current[0] = trip;
                    calls.clear();
                }
                if (analysisTrips.containsKey(trip)) {
                    String stop = value(reader, row, "stop_id");
                    if (insideStops.contains(stop)) calls.add(stop);
                }
            });
            finish(current[0], calls, completed);
        }

        private void finish(String trip, Set<String> calls, Set<String> completed) {
            if (trip == null) return;
            if (calls.size() >= 2 && analysisTrips.containsKey(trip)) selectedTrips.add(trip);
            completed.add(trip);
        }

        private void closeSelection(ZipFile zip) throws Exception {
            for (String id : selectedTrips) {
                TripMeta trip = analysisTrips.get(id);
                selectedRoutes.add(trip.routeId());
                selectedServices.add(trip.serviceId());
                if (!trip.shapeId().isBlank()) selectedShapes.add(trip.shapeId());
            }
            for (String id : selectedRoutes) selectedAgencies.add(analysisRoutes.get(id).agencyId());
            rows(zip, "stop_times.txt", (reader, row) -> {
                if (selectedTrips.contains(value(reader, row, "trip_id"))) {
                    selectedStops.add(value(reader, row, "stop_id"));
                }
            });
            boolean changed;
            do {
                changed = false;
                for (String id : List.copyOf(selectedStops)) {
                    StopMeta stop = stops.get(id);
                    require(stop != null, "Selected stop missing: " + id);
                    if (!stop.parent().isBlank() && selectedStops.add(stop.parent())) changed = true;
                }
            } while (changed);
        }

        private void classifySelectedRoutes() {
            for (String id : selectedRoutes) {
                Classification c = classify(analysisRoutes.get(id));
                require(c != null, "Unresolved retained route classification: " + id);
                classifications.put(id, c);
                modeRoutes.merge(c.mode(), 1L, Long::sum);
            }
            for (String id : selectedTrips) {
                modeTrips.merge(classifications.get(analysisTrips.get(id).routeId()).mode(), 1L, Long::sum);
            }
        }

        private Classification classify(RouteMeta route) {
            String id = route.id();
            String lower = (id + " " + route.name()).toLowerCase(Locale.ROOT);
            if (lower.contains("muc_tram") && lower.contains("prognose")) return c("tram", 0);
            if (lower.contains("_0_neu prognose") || lower.startsWith("linie 5_augsburg_neu prognose")) return c("tram", 0);
            if (lower.startsWith("muc_u") && lower.contains("prognose")) return c("subway", 1);
            if (!route.sbahnName().isBlank() || lower.startsWith("s") && lower.contains("_prognose_")) return c("rail", 2);
            if ("1".equals(route.ferryFlag())) return c("ferry", 4);
            if ("1".equals(route.deutschlandtaktFlag()) || !route.deutschlandtaktName().isBlank()) return c("rail", 2);
            if ("1".equals(route.railFlag())) return c("rail", 2);
            if ("1".equals(route.bmwFlag())) return c("bus", 3);
            if (id.matches("L[0-9].*")) return c("bus", 3);
            if (lower.matches("(40 bad aibling|41 bad aibling|44 gro\\u00dfkarolinenfeld|45 bad aibling|46 bad aibling).*")) return c("bus", 3);
            Matcher matcher = NUMERIC_SUFFIX.matcher(id);
            if (!matcher.find()) return null;
            int encoded = Integer.parseInt(matcher.group(1));
            Classification result = switch (encoded) {
                case 0 -> c("tram", 0); case 1 -> c("subway", 1);
                case 2 -> c("rail", 2); case 3 -> c("bus", 3);
                default -> encoded >= 100 && encoded <= 117 ? c("rail", 2) : null;
            };
            return result != null && "1".equals(route.railFlag()) && !result.mode().equals("rail") ? null : result;
        }

        private static Classification c(String mode, int type) { return new Classification(mode, type); }

        private void write(ZipFile source) throws Exception {
            Files.createDirectories(OUTPUT.getParent());
            Path temp = Files.createTempFile(OUTPUT.getParent(), ".synthetic-2019-", ".zip");
            counts.clear();
            try {
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                    out.setLevel(Deflater.BEST_COMPRESSION);
                    for (String table : TABLES) writeTable(source, out, table);
                }
                move(temp, OUTPUT);
            } finally { Files.deleteIfExists(temp); }
        }

        private void writeTable(ZipFile source, ZipOutputStream out, String name) throws Exception {
            ZipEntry sourceEntry = source.getEntry(name);
            require(sourceEntry != null, "Missing table " + name);
            ZipEntry entry = new ZipEntry(name); entry.setTime(ZIP_TIME); out.putNextEntry(entry);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 20);
            try (AnalyzeGtfs2019CalibrationInput.CsvReader reader =
                         new AnalyzeGtfs2019CalibrationInput.CsvReader(source, sourceEntry)) {
                writeCsv(writer, reader.header());
                List<String> row;
                long count = 0;
                while ((row = reader.next()) != null) {
                    if (!retain(name, reader, row)) continue;
                    List<String> copy = new ArrayList<>(row);
                    if (name.equals("routes.txt")) {
                        copy.set(reader.column("route_type"), Integer.toString(
                                classifications.get(value(reader, row, "route_id")).type()));
                    } else if (name.equals("agency.txt")) {
                        int timezone = reader.column("agency_timezone");
                        if (copy.get(timezone).isBlank() || copy.get(timezone).equals("unknown")) copy.set(timezone, "Europe/Berlin");
                        int url = reader.column("agency_url");
                        if (!copy.get(url).startsWith("http://") && !copy.get(url).startsWith("https://")) {
                            copy.set(url, "https://example.invalid/gtfs-agency/" + value(reader, row, "agency_id"));
                        }
                    }
                    writeCsv(writer, copy); count++;
                }
                writer.flush(); counts.put(name, count);
            }
            out.closeEntry();
        }

        private boolean retain(String table, AnalyzeGtfs2019CalibrationInput.CsvReader r, List<String> row) {
            return switch (table) {
                case "agency.txt" -> selectedAgencies.contains(value(r, row, "agency_id"));
                case "calendar.txt" -> selectedServices.contains(value(r, row, "service_id"));
                case "routes.txt" -> selectedRoutes.contains(value(r, row, "route_id"));
                case "trips.txt" -> selectedTrips.contains(value(r, row, "trip_id"));
                case "stop_times.txt" -> selectedTrips.contains(value(r, row, "trip_id"));
                case "stops.txt" -> selectedStops.contains(value(r, row, "stop_id"));
                case "shapes.txt" -> selectedShapes.contains(value(r, row, "shape_id"));
                case "transfers.txt" -> selectedStops.contains(value(r, row, "from_stop_id"))
                        && selectedStops.contains(value(r, row, "to_stop_id"));
                default -> false;
            };
        }
    }

    private static void rows(ZipFile zip, String name,
                             BiConsumer<AnalyzeGtfs2019CalibrationInput.CsvReader, List<String>> consumer)
            throws Exception {
        ZipEntry entry = zip.getEntry(name); require(entry != null, "Missing table " + name);
        try (AnalyzeGtfs2019CalibrationInput.CsvReader reader =
                     new AnalyzeGtfs2019CalibrationInput.CsvReader(zip, entry)) {
            List<String> row; while ((row = reader.next()) != null) consumer.accept(reader, row);
        }
    }

    private static String value(AnalyzeGtfs2019CalibrationInput.CsvReader r, List<String> row, String field) {
        return r.value(row, field, true);
    }
    private static String optional(AnalyzeGtfs2019CalibrationInput.CsvReader r, List<String> row, String field) {
        return r.value(row, field, false);
    }
    private static ZipFile zip(Path file) throws IOException { return new ZipFile(file.toFile(), StandardCharsets.UTF_8); }
    private static void writeCsv(BufferedWriter writer, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) writer.write(',');
            String v = fields.get(i);
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                writer.write('"'); writer.write(v.replace("\"", "\"\"")); writer.write('"');
            } else writer.write(v);
        }
        writer.write("\r\n");
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record StopMeta(String id, String parent) { }
    record RouteMeta(String id, String agencyId, String name, String railFlag, String ferryFlag,
                     String bmwFlag, String deutschlandtaktFlag, String deutschlandtaktName,
                     String sbahnName) { }
    record TripMeta(String id, String routeId, String serviceId, String shapeId) { }
    record Classification(String mode, int type) { }
    record Extent(double minX, double maxX, double minY, double maxY) {
        boolean contains(Coord c) { return c.getX() >= minX && c.getX() <= maxX && c.getY() >= minY && c.getY() <= maxY; }
    }
    public record Result(long sourceRoutes, long analysisRoutes, long sourceTrips, long analysisTrips,
                         long modelRoutes, long modelTrips, long stops, long stopTimes,
                         long shapePoints, long transfers, Map<String, Long> routesByMode,
                         Map<String, Long> tripsByMode, String outputSha256, boolean written) {
        String asText() {
            return "sourceRoutes=" + sourceRoutes + "\nAnalyse_2019_routes=" + analysisRoutes
                    + "\nsourceTrips=" + sourceTrips + "\nAnalyse_2019_trips=" + analysisTrips
                    + "\nmodelRoutes=" + modelRoutes + "\nmodelTrips=" + modelTrips
                    + "\nstops=" + stops + "\nstopTimes=" + stopTimes + "\nshapePoints=" + shapePoints
                    + "\ntransfers=" + transfers + "\nroutesByMode=" + routesByMode
                    + "\ntripsByMode=" + tripsByMode + "\noutputSha256=" + outputSha256
                    + "\nwritten=" + written + "\n";
        }
    }
}
