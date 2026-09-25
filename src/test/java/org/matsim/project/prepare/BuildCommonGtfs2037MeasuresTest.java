package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class BuildCommonGtfs2037MeasuresTest {

    @Test
    void analysisIsFailClosedAndSelectsOnlyDocumentedServices() throws Exception {
        String cleanBefore = sha(BuildCommonGtfs2037Measures.INPUT);
        String workbookBefore = sha(BuildCommonGtfs2037Measures.WORKBOOK);

        BuildCommonGtfs2037Measures.Analysis a =
                new BuildCommonGtfs2037Measures.Processor().analyze();

        assertAll(
                () -> assertTrue(a.blockers().isEmpty()),
                () -> assertEquals(234, a.pocciTrips()),
                () -> assertEquals(117, a.pocciD0()),
                () -> assertEquals(117, a.pocciD1()),
                () -> assertEquals(203, a.berduxTrips()),
                () -> assertEquals(94, a.berduxD0()),
                () -> assertEquals(109, a.berduxD1()),
                () -> assertEquals(60, a.regionalDwell()),
                () -> assertEquals(60, a.sDwell()),
                () -> assertEquals(0, a.forecastRegionalDwell()),
                () -> assertEquals(0, a.forecastSDwell()),
                () -> assertEquals(2_934, a.regionalRule().evidenceObservations()),
                () -> assertEquals(9_079, a.sRule().evidenceObservations()),
                () -> assertEquals(cleanBefore, sha(BuildCommonGtfs2037Measures.INPUT)),
                () -> assertEquals(workbookBefore, sha(BuildCommonGtfs2037Measures.WORKBOOK))
        );
    }

    @Test
    void specificationMakesBothSpatialProxiesAndSendlingerSpangeExplicit()
            throws Exception {
        String spec = Files.readString(BuildCommonGtfs2037Measures.SPEC);
        assertAll(
                () -> assertTrue(spec.contains("13,POCCISTRASSE,BAU|FAST_TRACK")),
                () -> assertTrue(spec.contains("18,SENDLINGER_SPANGE,BAU|FAST_TRACK")),
                () -> assertTrue(spec.contains("19,BERDUXSTRASSE,BAU|FAST_TRACK")),
                () -> assertEquals(2, spec.split("Scenario assumption:", -1).length - 1),
                () -> assertTrue(spec.contains("no GTFS timetable change")),
                () -> assertTrue(spec.contains(",60,0,106211|106212,180,current MVV GTFS")),
                () -> assertTrue(spec.contains(",60,0,,0,current MVV GTFS regular S2"))
        );
    }

    @Test
    void publishedBauAndFastTrackContainEachCommonStopExactlyOncePerEligibleTrip()
            throws Exception {
        Map<String, Set<String>> bau = commonCalls(BuildCommonGtfs2037Measures.OUTPUT);
        Map<String, Set<String>> fast = commonCalls(java.nio.file.Path.of(
                "original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_fast_track.zip"));
        assertAll(
                () -> assertEquals(234, bau.get("P").size()),
                () -> assertEquals(203, bau.get("B").size()),
                () -> assertEquals(bau, fast),
                () -> assertEquals(437, bau.get("ALL").size())
        );
    }

    private static Map<String, Set<String>> commonCalls(java.nio.file.Path path)
            throws Exception {
        Map<String, Set<String>> result = new HashMap<>();
        result.put("P", new HashSet<>()); result.put("B", new HashSet<>());
        result.put("ALL", new HashSet<>());
        Map<String, Integer> occurrences = new HashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile());
                BuildCommonGtfs2037Measures.CsvReader r =
                        BuildCommonGtfs2037Measures.csv(zip, "stop_times.txt")) {
            String[] row;
            while ((row = r.next()) != null) {
                String stop = r.get(row, "stop_id"), trip = r.get(row, "trip_id");
                String measure = stop.startsWith("BAU_POCCISTRASSE") ? "P"
                        : stop.startsWith("BAU_BERDUXSTRASSE") ? "B" : null;
                if (measure != null) {
                    assertEquals(60,
                            BuildCommonGtfs2037Measures.time(r.get(row, "departure_time"))
                                    - BuildCommonGtfs2037Measures.time(r.get(row, "arrival_time")),
                            "Unexpected dwell at " + stop);
                    assertEquals("0", r.get(row, "pickup_type"),
                            "Boarding must be allowed at " + stop);
                    assertEquals("0", r.get(row, "drop_off_type"),
                            "Alighting must be allowed at " + stop);
                    result.get(measure).add(trip); result.get("ALL").add(trip);
                    occurrences.merge(trip, 1, Integer::sum);
                }
            }
        }
        assertTrue(occurrences.values().stream().allMatch(n -> n == 1),
                "A common stop was inserted more than once into one trip");
        return result;
    }

    private static String sha(java.nio.file.Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] b = new byte[1 << 20];
            for (int n; (n = in.read(b)) > 0;) d.update(b, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
}
