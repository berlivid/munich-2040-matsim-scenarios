package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/** Tests assumptions that are critical for deterministic Fast Track builds. */
class BuildFastTrackGtfs2037Test {

    @TempDir
    Path temporaryDirectory;

    @Test
    void u9TemplateSelectionIsDeterministicAndKeepsPositiveShortIntervals() {
        List<BuildFastTrackGtfs2037.U9TemplateCandidate> candidates = List.of(
                new BuildFastTrackGtfs2037.U9TemplateCandidate(1, 10_000, "200"),
                new BuildFastTrackGtfs2037.U9TemplateCandidate(0, 20_000, "300"),
                new BuildFastTrackGtfs2037.U9TemplateCandidate(0, 20_000, "100"),
                new BuildFastTrackGtfs2037.U9TemplateCandidate(1, 10_030, "400")
        );

        Map<BuildFastTrackGtfs2037.U9Key, String> selected =
                BuildFastTrackGtfs2037.selectU9Templates(candidates);

        assertEquals(3, selected.size());
        assertEquals("100", selected.get(new BuildFastTrackGtfs2037.U9Key(0, 20_000)));
        assertEquals("200", selected.get(new BuildFastTrackGtfs2037.U9Key(1, 10_000)));
        assertEquals("400", selected.get(new BuildFastTrackGtfs2037.U9Key(1, 10_030)));
        assertTrue(selected.keySet().stream().toList().indexOf(
                new BuildFastTrackGtfs2037.U9Key(1, 10_000)
        ) < selected.keySet().stream().toList().indexOf(
                new BuildFastTrackGtfs2037.U9Key(1, 10_030)
        ));
    }

    @Test
    void generatedU9ImplementsApprovedCountsTimesAndDwells() throws IOException {
        Path baseline = Path.of("original-input-data/mvv_gtfs_2037/generated/"
                + "gtfs2037_munich_bau.zip");
        Path fastTrack = Path.of("original-input-data/mvv_gtfs_2037/generated/"
                + "gtfs2037_munich_fast_track.zip");

        Map<String, String> u9Directions = tripsForRoute(fastTrack, "FT_U9");
        assertEquals(520, u9Directions.size());
        Map<String, List<TestCall>> u9Calls = callsForTrips(fastTrack,
                u9Directions.keySet());
        Map<Integer, TreeSet<Integer>> u9Departures = new HashMap<>();
        u9Departures.put(0, new TreeSet<>());
        u9Departures.put(1, new TreeSet<>());
        for (Map.Entry<String, String> trip : u9Directions.entrySet()) {
            List<TestCall> calls = u9Calls.get(trip.getKey());
            assertEquals(7, calls.size());
            assertEquals(calls.getFirst().arrival(), calls.getFirst().departure());
            assertEquals(calls.getLast().arrival(), calls.getLast().departure());
            for (int index = 1; index < calls.size() - 1; index++) {
                assertEquals(20, calls.get(index).departure() - calls.get(index).arrival());
            }
            assertTrue(u9Departures.get(Integer.parseInt(trip.getValue()))
                    .add(calls.getFirst().departure()),
                    "Duplicate U9 direction/departure key: " + trip.getKey());
        }
        assertEquals(259, u9Departures.get(0).size());
        assertEquals(261, u9Departures.get(1).size());
        assertEquals(5, positiveIntervalsBelowTwoMinutes(u9Departures.get(0)));
        assertEquals(70, positiveIntervalsBelowTwoMinutes(u9Departures.get(1)));

        Map<Integer, Set<Integer>> sourceAnchors = u6AnchorDepartures(baseline);
        assertEquals(sourceAnchors.get(0), u9Departures.get(0));
        assertEquals(sourceAnchors.get(1), u9Departures.get(1));

        assertEquals(80, tripsForRoute(fastTrack, "FT_NR_A").size());
        assertEquals(80, tripsForRoute(fastTrack, "FT_NR_B").size());
    }

    private static Map<Integer, Set<Integer>> u6AnchorDepartures(Path zipPath)
            throws IOException {
        Map<String, String> u6 = tripsForRoute(zipPath, "MUC_U6_neu Prognose");
        Set<String> north = new HashSet<>();
        Set<String> south = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8);
                BufferedReader reader = reader(zip, "stops.txt")) {
            Map<String, Integer> header = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                String parent = value(row, header, "parent_station");
                if ("107347".equals(parent)) north.add(value(row, header, "stop_id"));
                if ("108566".equals(parent)) south.add(value(row, header, "stop_id"));
            }
        }
        Map<Integer, Set<Integer>> result = Map.of(0, new TreeSet<>(), 1, new TreeSet<>());
        Map<String, int[]> anchorsByTrip = new HashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8);
                BufferedReader reader = reader(zip, "stop_times.txt")) {
            Map<String, Integer> header = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                String trip = value(row, header, "trip_id");
                String direction = u6.get(trip);
                if (direction == null) continue;
                String stop = value(row, header, "stop_id");
                int[] anchors = anchorsByTrip.computeIfAbsent(
                        trip, ignored -> new int[]{-1, -1}
                );
                if (north.contains(stop)) anchors[0] = parseTime(
                        value(row, header, "departure_time")
                );
                if (south.contains(stop)) anchors[1] = parseTime(
                        value(row, header, "departure_time")
                );
            }
        }
        for (Map.Entry<String, int[]> entry : anchorsByTrip.entrySet()) {
            int[] anchors = entry.getValue();
            if (anchors[0] < 0 || anchors[1] < 0) continue;
            int direction = anchors[0] < anchors[1] ? 0 : 1;
            result.get(direction).add(anchors[direction]);
        }
        return result;
    }

    private static Map<String, String> tripsForRoute(Path zipPath, String routeId)
            throws IOException {
        Map<String, String> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8);
                BufferedReader reader = reader(zip, "trips.txt")) {
            Map<String, Integer> header = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                if (routeId.equals(value(row, header, "route_id"))) {
                    result.put(value(row, header, "trip_id"),
                            value(row, header, "direction_id"));
                }
            }
        }
        return result;
    }

    private static Map<String, List<TestCall>> callsForTrips(
            Path zipPath, Set<String> tripIds
    ) throws IOException {
        Map<String, List<TestCall>> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8);
                BufferedReader reader = reader(zip, "stop_times.txt")) {
            Map<String, Integer> header = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                String trip = value(row, header, "trip_id");
                if (tripIds.contains(trip)) {
                    result.computeIfAbsent(trip, ignored -> new ArrayList<>()).add(
                            new TestCall(
                                    parseTime(value(row, header, "arrival_time")),
                                    parseTime(value(row, header, "departure_time"))
                            )
                    );
                }
            }
        }
        return result;
    }

    private static int positiveIntervalsBelowTwoMinutes(TreeSet<Integer> departures) {
        int count = 0;
        Integer previous = null;
        for (int departure : departures) {
            if (previous != null && departure - previous > 0
                    && departure - previous < 120) count++;
            previous = departure;
        }
        return count;
    }

    private static BufferedReader reader(ZipFile zip, String name) throws IOException {
        return new BufferedReader(new InputStreamReader(
                zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8
        ), 1 << 20);
    }

    private static Map<String, Integer> header(String line) {
        List<String> values = parseCsv(line.replace("\ufeff", ""));
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < values.size(); index++) result.put(values.get(index), index);
        return result;
    }

    private static String value(List<String> row, Map<String, Integer> header, String field) {
        return row.get(header.get(field));
    }

    private static int parseTime(String value) {
        String[] parts = value.split(":");
        return Integer.parseInt(parts[0]) * 3600
                + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }

    private static List<String> parseCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (character == ',' && !quoted) {
                result.add(field.toString());
                field.setLength(0);
            } else field.append(character);
        }
        result.add(field.toString());
        return result;
    }

    private record TestCall(int arrival, int departure) {
    }

    @Test
    void matsimConverterAcceptsTripWithoutShapeId() throws IOException {
        Path gtfs = temporaryDirectory.resolve("no-shape.zip");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\n"
                + "A,Test Agency,https://example.invalid,Europe/Berlin\n");
        files.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,"
                + "friday,saturday,sunday,start_date,end_date\n"
                + "1,1,1,1,1,1,1,1,20260213,20260213\n");
        files.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name,"
                + "route_type\nFT_TEST,A,FT,Fast Track test,1\n");
        files.put("trips.txt", "route_id,service_id,trip_id,trip_headsign,direction_id,"
                + "shape_id\nFT_TEST,1,FT_TEST_0,Second stop,0,\n");
        files.put("stops.txt", "stop_id,stop_name,stop_lat,stop_lon,location_type,"
                + "parent_station\nFT_A,First stop,48.100000,11.500000,0,\n"
                + "FT_B,Second stop,48.110000,11.510000,0,\n");
        files.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,"
                + "stop_sequence\nFT_TEST_0,06:00:00,06:00:00,FT_A,0\n"
                + "FT_TEST_0,06:05:00,06:05:00,FT_B,1\n");
        files.put("transfers.txt", "from_stop_id,to_stop_id,transfer_type,"
                + "min_transfer_time\nFT_A,FT_B,2,300\n");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(gtfs))) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        GtfsConverter.newBuilder()
                .setFeed(gtfs)
                .setDate(LocalDate.parse("2026-02-13"))
                .setTransform(coord -> coord)
                .setScenario(scenario)
                .setUseExtendedRouteTypes(false)
                .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                .setIncludeMinimalTransferTimes(true)
                .build()
                .convert();

        assertEquals(1, scenario.getTransitSchedule().getTransitLines().size());
        assertEquals(2, scenario.getTransitSchedule().getFacilities().size());
        assertEquals(1, scenario.getTransitSchedule().getTransitLines().values()
                .iterator().next().getRoutes().size());
        assertEquals(300.0, scenario.getTransitSchedule().getMinimalTransferTimes().get(
                Id.create("FT_A", TransitStopFacility.class),
                Id.create("FT_B", TransitStopFacility.class)
        ));
    }
}
