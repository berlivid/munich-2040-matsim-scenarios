package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only preflight of the approved Munich origin-and-destination trip filter. */
public final class AnalyzeMunichTripBoundary {
    static final Path POPULATION = Path.of(
            "scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml");
    static final Path OUTPUT = Path.of("generated/munich_trip_boundary_preflight");

    private AnalyzeMunichTripBoundary() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This preflight accepts no arguments");
        Result result = analyze(POPULATION, MunichMunicipalBoundary.DEFAULT_FILE);
        write(result, OUTPUT);
        System.out.print(result.consoleSummary());
    }

    static Result analyze(Path population, Path boundaryFile) throws IOException {
        if (!Files.isRegularFile(population)) {
            throw new IllegalArgumentException("Population file is missing: " + population.toAbsolutePath());
        }
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.load(boundaryFile);
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        Counters counters = new Counters();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        // MATSim may parse persons in parallel. Serialising the tiny counter update
        // keeps the preflight deterministic while the population remains streaming.
        reader.addAlgorithm(person -> {
            synchronized (counters) {
                classifyPerson(person, filter, boundary, counters);
            }
        });
        reader.readFile(population.toString());
        return counters.result(population.normalize(), boundary);
    }

    private static void classifyPerson(Person person, MunichTripBoundaryFilter filter,
                                       MunichMunicipalBoundary boundary, Counters counters) {
        counters.persons++;
        Plan plan = person.getSelectedPlan();
        if (plan == null) {
            counters.personsWithoutTrips++;
            return;
        }
        counters.selectedPlans++;
        var trips = filter.classify(plan);
        if (trips.isEmpty()) counters.personsWithoutTrips++;

        Set<Activity> endpoints = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var trip : trips) {
            counters.trips++;
            counters.byCategory.merge(trip.category(), 1L, Long::sum);
            counters.byMode.computeIfAbsent(trip.inputMainMode(), ignored -> categoryMap())
                    .merge(trip.category(), 1L, Long::sum);
            if (trip.category() == MunichTripBoundaryFilter.SpatialCategory
                    .INVALID_OR_MISSING_COORDINATE) counters.invalidCoordinateTrips++;
            if (trip.origin() != null) endpoints.add(trip.origin());
            if (trip.destination() != null) endpoints.add(trip.destination());
        }
        for (Activity activity : endpoints) {
            if (!boundary.isValidCoordinate(activity.getCoord())) continue;
            double distance = boundary.distanceToBoundaryMetres(activity.getCoord());
            if (distance == 0.0) counters.endpointsExactlyOnBoundary++;
            if (distance <= MunichMunicipalBoundary.PRACTICAL_BOUNDARY_TOLERANCE_METRES) {
                counters.endpointsWithinOneMetre++;
            }
        }
    }

    private static EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> categoryMap() {
        EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> map =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) map.put(category, 0L);
        return map;
    }

    private static void write(Result result, Path output) throws IOException {
        Files.createDirectories(output);
        writeAtomically(output.resolve("boundary_summary.csv"), result.summaryCsv());
        writeAtomically(output.resolve("boundary_by_input_mode.csv"), result.byModeCsv());
        writeAtomically(output.resolve("preflight_report.md"), result.report());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    record Result(Path population, Path boundaryFile, String boundarySha256,
                  String sourceGeoJsonType, String geometryType, boolean valid, boolean empty, int geometryCount,
                  String crs, String envelope, long persons, long selectedPlans,
                  long trips, long personsWithoutTrips, long invalidCoordinateTrips,
                  long endpointsExactlyOnBoundary, long endpointsWithinOneMetre,
                  Map<MunichTripBoundaryFilter.SpatialCategory, Long> byCategory,
                  Map<String, Map<MunichTripBoundaryFilter.SpatialCategory, Long>> byMode) {

        String summaryCsv() {
            StringBuilder out = new StringBuilder("metric,category,count,share_percent,value,notes\n");
            metadata(out, "population_file", population.toString(), "unchanged input");
            metadata(out, "boundary_file", boundaryFile.toString(), "unchanged administrative boundary");
            metadata(out, "boundary_sha256", boundarySha256, "read-only SHA-256");
            metadata(out, "boundary_geojson_root_type", sourceGeoJsonType, "source file root geometry type");
            metadata(out, "boundary_geometry_type", geometryType, "parsed GeoJSON geometry");
            metadata(out, "boundary_valid", Boolean.toString(valid), "JTS validity");
            metadata(out, "boundary_empty", Boolean.toString(empty), "JTS emptiness");
            metadata(out, "boundary_geometry_count", Integer.toString(geometryCount), "top-level geometries");
            metadata(out, "boundary_crs", crs, "same CRS as population coordinates");
            metadata(out, "boundary_envelope", envelope, "EPSG:31468 metres");
            count(out, "persons_loaded", "", persons, Double.NaN, "streamed once");
            count(out, "selected_plans", "", selectedPlans, Double.NaN, "one selected plan per counted person where present");
            count(out, "main_trips", "", trips, 100.0, "between consecutive MATSim main activities");
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                long value = byCategory.getOrDefault(category, 0L);
                count(out, "spatial_category", category.name(), value, share(value, trips),
                        category == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE
                                ? "primary Munich analysis sample" : "excluded from primary sample");
            }
            count(out, "persons_without_main_trip", "", personsWithoutTrips,
                    share(personsWithoutTrips, persons), "no main trip in selected plan");
            count(out, "invalid_coordinate_trips", "", invalidCoordinateTrips,
                    share(invalidCoordinateTrips, trips), "missing or non-finite endpoint coordinate");
            count(out, "main_activity_endpoints_exactly_on_boundary", "",
                    endpointsExactlyOnBoundary, Double.NaN, "distance equals zero in EPSG:31468");
            count(out, "main_activity_endpoints_within_one_metre_of_boundary", "",
                    endpointsWithinOneMetre, Double.NaN, "includes exact boundary points");
            return out.toString();
        }

        String byModeCsv() {
            StringBuilder out = new StringBuilder(
                    "input_main_mode,spatial_category,count,share_within_mode_percent,share_all_trips_percent\n");
            for (var modeEntry : byMode.entrySet()) {
                long modeTotal = modeEntry.getValue().values().stream().mapToLong(Long::longValue).sum();
                for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                    long value = modeEntry.getValue().getOrDefault(category, 0L);
                    out.append(csv(modeEntry.getKey())).append(',').append(category).append(',')
                            .append(value).append(',').append(formatShare(share(value, modeTotal)))
                            .append(',').append(formatShare(share(value, trips))).append('\n');
                }
            }
            return out.toString();
        }

        String report() {
            StringBuilder out = new StringBuilder("# Munich trip-boundary preflight\n\n")
                    .append("## Scope and decision\n\n")
                    .append("This is a read-only diagnostic of the approved analysis filter. A main trip enters the primary Munich sample only when both its origin and destination main activities are covered by the administrative City of Munich boundary. Boundary points are included. The regional population, network and public-transport supply remain unfiltered and unchanged. No modal split, passenger-kilometre or vehicle-kilometre result is calculated here.\n\n")
                    .append("## Spatial reference\n\n")
                    .append("- Boundary: `").append(boundaryFile).append("`\n")
                    .append("- SHA-256: `").append(boundarySha256).append("`\n")
                    .append("- GeoJSON root/effective JTS geometry: ").append(sourceGeoJsonType)
                    .append(" / ").append(geometryType).append("; valid=").append(valid)
                    .append("; empty=").append(empty).append("; top-level geometries=")
                    .append(geometryCount).append("\n")
                    .append("- CRS: ").append(crs).append("\n")
                    .append("- Envelope: `").append(envelope).append("`\n\n")
                    .append("The project documentation records that the official district polygons were merged and transformed from EPSG:25832 to EPSG:31468. The coordinate range is compatible with the MATSim population CRS.\n\n")
                    .append("## Results\n\n")
                    .append("Persons loaded: ").append(persons).append("; selected plans: ")
                    .append(selectedPlans).append("; main trips: ").append(trips)
                    .append("; persons without an identifiable main trip: ")
                    .append(personsWithoutTrips).append(".\n\n")
                    .append("| Spatial category | Trips | Share |\n|---|---:|---:|\n");
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                long count = byCategory.getOrDefault(category, 0L);
                out.append("| ").append(category).append(" | ").append(count).append(" | ")
                        .append(formatShare(share(count, trips))).append("% |\n");
            }
            out.append("\nInvalid or missing-coordinate trips: ").append(invalidCoordinateTrips)
                    .append(". Main-activity endpoint observations exactly on the boundary: ")
                    .append(endpointsExactlyOnBoundary).append("; within one metre (including exact): ")
                    .append(endpointsWithinOneMetre).append(". These endpoint counts de-duplicate shared main activities within each selected plan.\n\n")
                    .append("## Technical method and limitations\n\n")
                    .append("MATSim `TripStructureUtils` and its stage-activity predicate identify trips between consecutive main activities. Routed interaction activities therefore do not create additional analysis trips. The input main mode is a technical property of the existing selected plan, not an observed or calibrated modal share. The one-metre boundary diagnostic is reported in the projected metre-based CRS; it is not used to alter classification. Absolute results from the primary sample exclude every boundary-crossing trip and must be interpreted with that scope.\n");
            return out.toString();
        }

        String consoleSummary() {
            StringBuilder out = new StringBuilder("Munich trip-boundary preflight PASS\n")
                    .append("persons=").append(persons).append(" selectedPlans=")
                    .append(selectedPlans).append(" mainTrips=").append(trips).append('\n');
            for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
                long count = byCategory.getOrDefault(category, 0L);
                out.append(category).append('=').append(count).append(" (")
                        .append(formatShare(share(count, trips))).append("%)\n");
            }
            return out.toString();
        }

        private static void metadata(StringBuilder out, String metric, String value, String notes) {
            out.append(metric).append(",,,,").append(csv(value)).append(',').append(csv(notes)).append('\n');
        }

        private static void count(StringBuilder out, String metric, String category, long count,
                                  double share, String notes) {
            out.append(metric).append(',').append(category).append(',').append(count).append(',')
                    .append(formatShare(share)).append(",,").append(csv(notes)).append('\n');
        }

        private static double share(long count, long total) {
            return total == 0 ? Double.NaN : 100.0 * count / total;
        }

        private static String formatShare(double value) {
            return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
        }
    }

    private static final class Counters {
        long persons;
        long selectedPlans;
        long trips;
        long personsWithoutTrips;
        long invalidCoordinateTrips;
        long endpointsExactlyOnBoundary;
        long endpointsWithinOneMetre;
        final EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long> byCategory = categoryMap();
        final TreeMap<String, EnumMap<MunichTripBoundaryFilter.SpatialCategory, Long>> byMode =
                new TreeMap<>();

        Result result(Path population, MunichMunicipalBoundary boundary) {
            Map<String, Map<MunichTripBoundaryFilter.SpatialCategory, Long>> modes = new TreeMap<>();
            byMode.forEach((mode, categories) -> modes.put(mode, Map.copyOf(categories)));
            return new Result(population, boundary.source(), boundary.sha256(),
                    boundary.sourceGeoJsonType(), boundary.geometryType(),
                    boundary.isValid(), boundary.isEmpty(), boundary.geometryCount(), boundary.crs(),
                    boundary.envelope().toString(), persons, selectedPlans, trips, personsWithoutTrips,
                    invalidCoordinateTrips, endpointsExactlyOnBoundary, endpointsWithinOneMetre,
                    Map.copyOf(byCategory), Collections.unmodifiableMap(modes));
        }
    }
}
