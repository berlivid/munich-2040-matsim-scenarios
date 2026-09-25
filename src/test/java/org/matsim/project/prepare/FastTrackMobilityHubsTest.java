package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopArea;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

class FastTrackMobilityHubsTest {

    @Test
    void approvedSpecificationHasTwelveHubsAndFourPerClass() throws Exception {
        List<FastTrackMobilityHubs.Hub> hubs =
                FastTrackMobilityHubs.readSpecification(
                        FastTrackMobilityHubs.SPECIFICATION
                );
        assertEquals(12, hubs.size());
        assertEquals(4, hubs.stream().filter(h -> h.size().equals("large")).count());
        assertEquals(4, hubs.stream().filter(h -> h.size().equals("medium")).count());
        assertEquals(4, hubs.stream().filter(h -> h.size().equals("small")).count());
    }

    @Test
    void largeHubReducesByTwentyPercent() {
        assertSingleChange(0.20, 200, 160);
    }

    @Test
    void mediumHubReducesByFifteenPercent() {
        assertSingleChange(0.15, 200, 170);
    }

    @Test
    void smallHubReducesByTenPercent() {
        assertSingleChange(0.10, 200, 180);
    }

    @Test
    void minimumTransferTimeIsSixtySeconds() {
        assertSingleChange(0.20, 70, 60);
    }

    @Test
    void selfRelationsAreExcludedAndRemainUnchanged() {
        TransitSchedule schedule = schedule("AREA", "A", "B");
        relation(schedule, "A", "A", 0);
        relation(schedule, "A", "B", 180);
        FastTrackMobilityHubs.Analysis analysis = FastTrackMobilityHubs.analyze(
                schedule, List.of(hub("AREA", 0.20)), 1, 1
        );

        FastTrackMobilityHubs.apply(schedule, analysis);

        assertEquals(0, transfer(schedule, "A", "A"));
        assertEquals(144, transfer(schedule, "A", "B"));
    }

    @Test
    void preservesDirectionAndRelationsOutsideHub() {
        TransitSchedule schedule = schedule("AREA", "A", "B");
        addStop(schedule, "OUT", "OUTSIDE");
        relation(schedule, "A", "B", 180);
        relation(schedule, "B", "OUT", 210);
        FastTrackMobilityHubs.Analysis analysis = FastTrackMobilityHubs.analyze(
                schedule, List.of(hub("AREA", 0.20)), 1, 0
        );

        FastTrackMobilityHubs.apply(schedule, analysis);
        Map<FastTrackMobilityHubs.Pair, Double> relations =
                FastTrackMobilityHubs.ScheduleSnapshot.capture(schedule).relations();

        assertEquals(144, relations.get(new FastTrackMobilityHubs.Pair("A", "B")));
        assertEquals(210, relations.get(new FastTrackMobilityHubs.Pair("B", "OUT")));
        assertFalse(relations.containsKey(new FastTrackMobilityHubs.Pair("B", "A")));
        assertEquals(2, relations.size());
    }

    @Test
    void failsForUnknownStopArea() {
        TransitSchedule schedule = schedule("AREA", "A", "B");
        relation(schedule, "A", "B", 180);
        assertThrows(IllegalStateException.class, () -> FastTrackMobilityHubs.analyze(
                schedule, List.of(hub("UNKNOWN", 0.20)), 1, 0
        ));
    }

    @Test
    void failsForUnexpectedRelationCount() {
        TransitSchedule schedule = schedule("AREA", "A", "B");
        relation(schedule, "A", "B", 180);
        assertThrows(IllegalStateException.class, () -> FastTrackMobilityHubs.analyze(
                schedule, List.of(hub("AREA", 0.20)), 2, 0
        ));
    }

    @Test
    void repeatedBuildFromSameBaselineIsDeterministic() {
        TransitSchedule first = schedule("AREA", "A", "B");
        TransitSchedule second = schedule("AREA", "A", "B");
        relation(first, "A", "B", 181);
        relation(second, "A", "B", 181);

        FastTrackMobilityHubs.apply(first, FastTrackMobilityHubs.analyze(
                first, List.of(hub("AREA", 0.15)), 1, 0
        ));
        FastTrackMobilityHubs.apply(second, FastTrackMobilityHubs.analyze(
                second, List.of(hub("AREA", 0.15)), 1, 0
        ));

        assertEquals(
                FastTrackMobilityHubs.ScheduleSnapshot.capture(first),
                FastTrackMobilityHubs.ScheduleSnapshot.capture(second)
        );
        assertEquals(154, transfer(first, "A", "B"));
    }

    private static void assertSingleChange(
            double reduction,
            double original,
            double expected
    ) {
        TransitSchedule schedule = schedule("AREA", "A", "B");
        relation(schedule, "A", "B", original);
        FastTrackMobilityHubs.Analysis analysis = FastTrackMobilityHubs.analyze(
                schedule, List.of(hub("AREA", reduction)), 1, 0
        );
        FastTrackMobilityHubs.apply(schedule, analysis);
        assertEquals(expected, transfer(schedule, "A", "B"));
    }

    private static FastTrackMobilityHubs.Hub hub(String area, double reduction) {
        return new FastTrackMobilityHubs.Hub(
                1, "HUB", "1", "Test hub", "test", "Test node",
                List.of(area), List.of(), reduction, 60,
                "FAST_TRACK_2040", "approved"
        );
    }

    private static TransitSchedule schedule(String area, String... stopIds) {
        TransitSchedule schedule = ScenarioUtils.createScenario(
                ConfigUtils.createConfig()
        ).getTransitSchedule();
        for (String stopId : stopIds) addStop(schedule, stopId, area);
        return schedule;
    }

    private static void addStop(TransitSchedule schedule, String stopId, String area) {
        TransitStopFacility facility = schedule.getFactory().createTransitStopFacility(
                Id.create(stopId, TransitStopFacility.class), new Coord(0, 0), false
        );
        facility.setName(stopId);
        facility.setStopAreaId(Id.create(area, TransitStopArea.class));
        schedule.addStopFacility(facility);
    }

    private static void relation(
            TransitSchedule schedule,
            String from,
            String to,
            double seconds
    ) {
        schedule.getMinimalTransferTimes().set(stopId(from), stopId(to), seconds);
    }

    private static double transfer(TransitSchedule schedule, String from, String to) {
        return schedule.getMinimalTransferTimes().get(stopId(from), stopId(to));
    }

    private static Id<TransitStopFacility> stopId(String id) {
        return Id.create(id, TransitStopFacility.class);
    }
}
