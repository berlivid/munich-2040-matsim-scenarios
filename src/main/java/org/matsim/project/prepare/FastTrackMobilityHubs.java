package org.matsim.project.prepare;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopArea;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/** Applies the approved Fast-Track-only Mobility Hub transfer-time proxy. */
public final class FastTrackMobilityHubs {

    static final Path SPECIFICATION = Path.of(
            "original-input-data/fast_track_2040_sources/mobility_hubs/approved_mobility_hubs.csv"
    );
    static final Path REFERENCE_PREVIEW = Path.of(
            "generated/mobility_hubs_preflight/approved_transfer_relations_preview.csv"
    );
    static final Path REFERENCE_EXCLUSIONS = Path.of(
            "generated/mobility_hubs_preflight/excluded_transfer_relations_review.csv"
    );
    static final Path REFERENCE_SUMMARY = Path.of(
            "generated/mobility_hubs_preflight/transfer_time_change_summary.csv"
    );
    static final int EXPECTED_HUBS = 12;
    static final int EXPECTED_CHANGES = 790;
    static final int EXPECTED_SELF_RELATIONS = 103;
    static final int EXPECTED_TOTAL_RELATIONS = 95_912;

    private FastTrackMobilityHubs() {
    }

    /** Analyzes the converted baseline and writes a preview without mutating it. */
    static Analysis analyzeApproved(TransitSchedule schedule, Path previewOutput)
            throws Exception {
        List<Hub> hubs = readSpecification(SPECIFICATION);
        Analysis analysis = analyze(
                schedule, hubs, EXPECTED_CHANGES, EXPECTED_SELF_RELATIONS
        );
        compareWithApprovedReferences(analysis);
        writePreview(analysis, previewOutput);
        printSummary("analysis", analysis);
        return analysis;
    }

    /** Applies and immediately validates the approved changes in memory. */
    static Application applyApproved(TransitSchedule schedule) throws Exception {
        List<Hub> hubs = readSpecification(SPECIFICATION);
        Analysis analysis = analyze(
                schedule, hubs, EXPECTED_CHANGES, EXPECTED_SELF_RELATIONS
        );
        compareWithApprovedReferences(analysis);
        Application application = apply(schedule, analysis);
        printSummary("build", analysis);
        return application;
    }

    /** Validates a published schedule against the approved relation reference. */
    static void validatePublished(TransitSchedule schedule) throws Exception {
        List<Hub> hubs = readSpecification(SPECIFICATION);
        resolveHubFacilities(schedule, hubs);
        Map<Pair, Double> actual = relationValues(schedule);
        require(actual.size() == EXPECTED_TOTAL_RELATIONS,
                "Fast Track transfer relation count differs: " + actual.size());

        List<Map<String, String>> preview = readCsv(REFERENCE_PREVIEW);
        require(preview.size() == EXPECTED_CHANGES,
                "Approved preview does not contain 790 relations.");
        for (Map<String, String> row : preview) {
            Pair pair = pair(row);
            Double value = actual.get(pair);
            require(value != null, "Published schedule is missing " + pair);
            require(Double.compare(value, number(row, "proposed_time_s")) == 0,
                    "Published transfer differs from approved proposal for " + pair
                            + ": " + value);
        }

        List<Map<String, String>> exclusions = readCsv(REFERENCE_EXCLUSIONS);
        require(exclusions.size() == EXPECTED_SELF_RELATIONS,
                "Approved exclusion review does not contain 103 relations.");
        for (Map<String, String> row : exclusions) {
            require("self_relation".equals(row.get("exclusion_reason")),
                    "Unexpected approved exclusion reason: " + row.get("exclusion_reason"));
            Pair pair = pair(row);
            require(pair.from().equals(pair.to()), "Approved exclusion is not self-directed: " + pair);
            Double value = actual.get(pair);
            require(value != null, "Published schedule is missing excluded self-relation " + pair);
            require(Double.compare(value, number(row, "original_time_s")) == 0,
                    "Excluded self-relation changed: " + pair);
        }
    }

    static List<Hub> readSpecification(Path path) throws Exception {
        List<Map<String, String>> rows = readCsv(path);
        List<Hub> hubs = new ArrayList<>();
        for (Map<String, String> row : rows) {
            hubs.add(new Hub(
                    integer(row, "approved_rank"), required(row, "hub_id"),
                    required(row, "mp_id"), required(row, "lhm_location_name"),
                    required(row, "size_class"), required(row, "matsim_interchange_node"),
                    split(required(row, "approved_stop_area_ids")),
                    split(row.getOrDefault("explicitly_confirmed_stop_facility_ids", "")),
                    number(row, "reduction_factor"),
                    integer(row, "minimum_transfer_time_s"),
                    required(row, "scenario"), required(row, "decision_status")
            ));
        }
        validateSpecification(hubs, EXPECTED_HUBS);
        return List.copyOf(hubs);
    }

    static Analysis analyze(
            TransitSchedule schedule,
            List<Hub> hubs,
            int expectedChanges,
            int expectedSelfRelations
    ) {
        validateSpecification(hubs, hubs.size());
        Map<Hub, Set<String>> facilities = resolveHubFacilities(schedule, hubs);
        Map<String, Hub> facilityOwners = new HashMap<>();
        facilities.forEach((hub, ids) -> ids.forEach(id -> {
            Hub previous = facilityOwners.putIfAbsent(id, hub);
            if (previous != null) {
                throw new IllegalStateException(
                        "Stop facility belongs to more than one hub: " + id
                                + " (" + previous.hubId() + ", " + hub.hubId() + ")"
                );
            }
        }));

        Map<String, TransitStopFacility> stopById = schedule.getFacilities().values()
                .stream().collect(Collectors.toMap(
                        stop -> stop.getId().toString(), Function.identity()
                ));
        List<Change> changes = new ArrayList<>();
        List<SelfRelation> selfRelations = new ArrayList<>();
        Set<Pair> seen = new HashSet<>();
        var iterator = schedule.getMinimalTransferTimes().iterator();
        while (iterator.hasNext()) {
            iterator.next();
            Pair pair = new Pair(
                    iterator.getFromStopId().toString(),
                    iterator.getToStopId().toString()
            );
            require(seen.add(pair), "Duplicate directed transfer relation: " + pair);
            Hub fromOwner = facilityOwners.get(pair.from());
            Hub toOwner = facilityOwners.get(pair.to());
            if (fromOwner == null || fromOwner != toOwner) {
                continue;
            }
            double original = iterator.getSeconds();
            if (pair.from().equals(pair.to())) {
                selfRelations.add(new SelfRelation(fromOwner, pair, original));
                continue;
            }
            require(Double.isFinite(original) && original > 0,
                    "Approved cross-stop relation has invalid time: " + pair + " = " + original);
            long rounded = Math.round(original * (1.0 - fromOwner.reduction()));
            long proposed = Math.max(fromOwner.minimumSeconds(), rounded);
            require(proposed >= 60, "Proposed transfer is below 60 seconds: " + pair);
            TransitStopFacility from = requireStop(stopById, pair.from());
            TransitStopFacility to = requireStop(stopById, pair.to());
            changes.add(new Change(
                    fromOwner, pair, name(from), area(from), name(to), area(to),
                    original, proposed, rounded < fromOwner.minimumSeconds()
            ));
        }
        changes.sort(Change.ORDER);
        selfRelations.sort(SelfRelation.ORDER);
        require(changes.size() == expectedChanges,
                "Expected " + expectedChanges + " approved cross-stop relations, found "
                        + changes.size());
        require(selfRelations.size() == expectedSelfRelations,
                "Expected " + expectedSelfRelations + " self-relations, found "
                        + selfRelations.size());
        return new Analysis(
                List.copyOf(hubs), Map.copyOf(facilities), List.copyOf(changes),
                List.copyOf(selfRelations)
        );
    }

    static Application apply(TransitSchedule schedule, Analysis analysis) {
        ScheduleSnapshot before = ScheduleSnapshot.capture(schedule);
        for (Change change : analysis.changes()) {
            double previous = schedule.getMinimalTransferTimes().get(
                    stopId(change.pair().from()), stopId(change.pair().to())
            );
            require(Double.compare(previous, change.original()) == 0,
                    "Transfer changed before Mobility Hub application: " + change.pair());
            schedule.getMinimalTransferTimes().set(
                    stopId(change.pair().from()), stopId(change.pair().to()),
                    change.proposed()
            );
        }
        Application result = new Application(analysis, before);
        validateApplied(schedule, result);
        return result;
    }

    static void validateApplied(TransitSchedule schedule, Application application) {
        ScheduleSnapshot after = ScheduleSnapshot.capture(schedule);
        ScheduleSnapshot before = application.before();
        require(after.lineIds().equals(before.lineIds()), "Transit line IDs changed.");
        require(after.routeIds().equals(before.routeIds()), "Transit route IDs changed.");
        require(after.stopIds().equals(before.stopIds()), "Transit stop-facility IDs changed.");
        require(after.departureIds().equals(before.departureIds()), "Transit departure IDs changed.");
        require(after.relations().keySet().equals(before.relations().keySet()),
                "The set of transfer relations changed.");

        Map<Pair, Double> expected = new HashMap<>(before.relations());
        for (Change change : application.analysis().changes()) {
            expected.put(change.pair(), (double) change.proposed());
        }
        require(after.relations().equals(expected),
                "Transfer values differ outside the approved 790 relations or do not match the formula.");
        require(application.analysis().selfRelations().stream().allMatch(self ->
                        Double.compare(after.relations().get(self.pair()), self.original()) == 0),
                "A self-relation changed.");
    }

    private static void compareWithApprovedReferences(Analysis analysis) throws Exception {
        List<Map<String, String>> preview = readCsv(REFERENCE_PREVIEW);
        require(preview.size() == EXPECTED_CHANGES,
                "Approved preview row count differs: " + preview.size());
        Map<Pair, Map<String, String>> previewByPair = uniqueByPair(preview, REFERENCE_PREVIEW);
        require(previewByPair.keySet().equals(
                        analysis.changes().stream().map(Change::pair).collect(Collectors.toSet())),
                "Schedule-derived change pairs differ from the approved preview.");
        for (Change change : analysis.changes()) {
            Map<String, String> row = previewByPair.get(change.pair());
            require(change.hub().hubId().equals(row.get("hub_id")),
                    "Hub differs from preview for " + change.pair());
            require(Double.compare(change.original(), number(row, "original_time_s")) == 0,
                    "Original time differs from preview for " + change.pair());
            require(Double.compare(change.proposed(), number(row, "proposed_time_s")) == 0,
                    "Proposed time differs from preview for " + change.pair());
            require(Double.compare(change.hub().reduction(), number(row, "reduction_factor")) == 0,
                    "Reduction differs from preview for " + change.pair());
        }

        List<Map<String, String>> exclusions = readCsv(REFERENCE_EXCLUSIONS);
        require(exclusions.size() == EXPECTED_SELF_RELATIONS,
                "Approved exclusion row count differs: " + exclusions.size());
        Map<Pair, Map<String, String>> excludedByPair = uniqueByPair(
                exclusions, REFERENCE_EXCLUSIONS
        );
        require(excludedByPair.keySet().equals(
                        analysis.selfRelations().stream().map(SelfRelation::pair)
                                .collect(Collectors.toSet())),
                "Schedule-derived self-relations differ from the approved exclusions.");
        for (SelfRelation self : analysis.selfRelations()) {
            Map<String, String> row = excludedByPair.get(self.pair());
            require("self_relation".equals(row.get("exclusion_reason")),
                    "Non-self exclusion found in approved reference.");
            require(self.hub().hubId().equals(row.get("hub_id")),
                    "Self-relation hub differs from reference for " + self.pair());
            require(Double.compare(self.original(), number(row, "original_time_s")) == 0,
                    "Self-relation time differs from reference for " + self.pair());
        }

        List<Map<String, String>> summary = readCsv(REFERENCE_SUMMARY);
        Map<String, String> total = summary.stream()
                .filter(row -> "TOTAL".equals(row.get("hub_id"))).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Approved summary has no TOTAL row."
                ));
        require(integer(total, "existing_explicit_relations_in_approved_complex")
                        == EXPECTED_CHANGES + EXPECTED_SELF_RELATIONS,
                "Approved summary total differs.");
        require(integer(total, "included_relations") == EXPECTED_CHANGES,
                "Approved summary included count differs.");
        require(integer(total, "excluded_self_relations") == EXPECTED_SELF_RELATIONS,
                "Approved summary self count differs.");
        require(integer(total, "excluded_other_relations") == 0,
                "Approved summary contains other exclusions.");
        require(integer(total, "boundary_relations_observed") == 0,
                "Approved summary contains boundary relations.");
    }

    private static Map<Hub, Set<String>> resolveHubFacilities(
            TransitSchedule schedule,
            List<Hub> hubs
    ) {
        Map<String, Hub> areaOwners = new HashMap<>();
        Map<Hub, Set<String>> result = new LinkedHashMap<>();
        for (Hub hub : hubs) {
            Set<String> facilities = new TreeSet<>();
            for (String area : hub.stopAreas()) {
                Hub previous = areaOwners.putIfAbsent(area, hub);
                if (previous != null) {
                    throw new IllegalStateException(
                            "StopArea belongs to multiple hubs: " + area
                    );
                }
                boolean found = false;
                for (TransitStopFacility facility : schedule.getFacilities().values()) {
                    if (area.equals(facility.getId().toString())
                            || area.equals(area(facility))) {
                        facilities.add(facility.getId().toString());
                        found = true;
                    }
                }
                require(found, "Approved StopArea is missing: " + area);
            }
            for (String facilityId : hub.confirmedFacilities()) {
                TransitStopFacility facility = schedule.getFacilities().get(stopId(facilityId));
                require(facility != null,
                        "Explicitly confirmed stop facility is missing: " + facilityId);
                require(hub.stopAreas().contains(area(facility)),
                        "Confirmed facility is outside approved StopAreas: " + facilityId);
                facilities.add(facilityId);
            }
            require(!facilities.isEmpty(), "Hub has no facilities: " + hub.hubId());
            result.put(hub, Set.copyOf(facilities));
        }
        return result;
    }

    private static void validateSpecification(List<Hub> hubs, int expectedHubs) {
        require(hubs.size() == expectedHubs,
                "Expected " + expectedHubs + " hubs, found " + hubs.size());
        require(hubs.stream().map(Hub::hubId).distinct().count() == hubs.size(),
                "hub_id values must be unique.");
        require(hubs.stream().flatMap(hub -> hub.stopAreas().stream()).distinct().count()
                        == hubs.stream().mapToLong(hub -> hub.stopAreas().size()).sum(),
                "A StopArea occurs in multiple hubs.");
        for (Hub hub : hubs) {
            require(hub.minimumSeconds() >= 60,
                    "Hub floor is below 60 seconds: " + hub.hubId());
            require(hub.reduction() >= 0 && hub.reduction() < 1,
                    "Invalid reduction for " + hub.hubId());
            require(!hub.stopAreas().isEmpty(), "Hub has no StopArea: " + hub.hubId());
        }
        if (expectedHubs == EXPECTED_HUBS) {
            require(hubs.stream().map(Hub::rank).sorted().toList().equals(
                            java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList()),
                    "Approved ranks must be 1 through 12.");
            for (String size : List.of("large", "medium", "small")) {
                require(hubs.stream().filter(hub -> size.equals(hub.size())).count() == 4,
                        "Expected exactly four " + size + " hubs.");
            }
            for (Hub hub : hubs) {
                double expected = switch (hub.size()) {
                    case "large" -> 0.20;
                    case "medium" -> 0.15;
                    case "small" -> 0.10;
                    default -> throw new IllegalStateException(
                            "Unknown size class: " + hub.size()
                    );
                };
                require(Double.compare(hub.reduction(), expected) == 0,
                        "Reduction does not match class for " + hub.hubId());
                require(hub.minimumSeconds() == 60,
                        "Approved minimum must be 60 seconds for " + hub.hubId());
                require("FAST_TRACK_2040".equals(hub.scenario()),
                        "Hub is not Fast-Track-only: " + hub.hubId());
                require("approved".equals(hub.status()),
                        "Hub is not approved: " + hub.hubId());
            }
        }
    }

    private static void writePreview(Analysis analysis, Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("hub_id,approved_rank,size_class,mp_id,lhm_location_name,"
                    + "matsim_interchange_node,scenario,from_stop_id,from_stop_name,"
                    + "from_stop_area_id,to_stop_id,to_stop_name,to_stop_area_id,"
                    + "original_time_s,reduction_factor,proposed_time_s,absolute_change_s,"
                    + "minimum_floor_applied,inclusion_reason\n");
            for (Change change : analysis.changes()) {
                Hub hub = change.hub();
                writeCsvRow(writer, List.of(
                        hub.hubId(), Integer.toString(hub.rank()), hub.size(), hub.mpId(),
                        hub.lhmName(), hub.node(), hub.scenario(), change.pair().from(),
                        change.fromName(), change.fromArea(), change.pair().to(),
                        change.toName(), change.toArea(), seconds(change.original()),
                        String.format(Locale.ROOT, "%.2f", hub.reduction()),
                        Long.toString(change.proposed()),
                        seconds(change.original() - change.proposed()),
                        Boolean.toString(change.floorApplied()),
                        "Existing positive directed cross-stop relation wholly inside the approved hub complex."
                ));
            }
        }
    }

    private static void printSummary(String mode, Analysis analysis) {
        System.out.println("Fast Track Mobility Hubs " + mode + ": PASS");
        analysis.hubs().stream().sorted(Comparator.comparingInt(Hub::rank))
                .forEach(hub -> System.out.printf(
                        Locale.ROOT, "  %02d %s: %d changed, %d self excluded%n",
                        hub.rank(), hub.hubId(),
                        analysis.changes().stream().filter(c -> c.hub().equals(hub)).count(),
                        analysis.selfRelations().stream().filter(s -> s.hub().equals(hub)).count()
                ));
        System.out.println("  Total: " + analysis.changes().size()
                + " changed, " + analysis.selfRelations().size() + " self excluded");
    }

    private static Map<Pair, Double> relationValues(TransitSchedule schedule) {
        Map<Pair, Double> result = new LinkedHashMap<>();
        var iterator = schedule.getMinimalTransferTimes().iterator();
        while (iterator.hasNext()) {
            iterator.next();
            Pair pair = new Pair(iterator.getFromStopId().toString(),
                    iterator.getToStopId().toString());
            require(result.putIfAbsent(pair, iterator.getSeconds()) == null,
                    "Duplicate directed transfer relation: " + pair);
        }
        return Map.copyOf(result);
    }

    private static List<Map<String, String>> readCsv(Path path) throws Exception {
        require(Files.isRegularFile(path), "Required CSV is missing: " + path);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "CSV is empty: " + path);
        List<String> header = parseCsv(lines.getFirst());
        if (!header.isEmpty()) header.set(0, header.getFirst().replace("\ufeff", ""));
        require(header.stream().distinct().count() == header.size(),
                "CSV has duplicate headers: " + path);
        List<Map<String, String>> result = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) continue;
            List<String> values = parseCsv(lines.get(index));
            require(values.size() == header.size(),
                    "CSV row " + (index + 1) + " has " + values.size()
                            + " columns; expected " + header.size() + ": " + path);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), values.get(column));
            }
            result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    private static List<String> parseCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                result.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        require(!quoted, "Unterminated CSV quote.");
        result.add(value.toString());
        return result;
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values)
            throws Exception {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            String value = values.get(index);
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(value);
            }
        }
        writer.write('\n');
    }

    private static Map<Pair, Map<String, String>> uniqueByPair(
            List<Map<String, String>> rows,
            Path source
    ) {
        Map<Pair, Map<String, String>> result = new HashMap<>();
        for (Map<String, String> row : rows) {
            Pair pair = pair(row);
            require(result.putIfAbsent(pair, row) == null,
                    "Duplicate relation in " + source + ": " + pair);
        }
        return result;
    }

    private static Pair pair(Map<String, String> row) {
        return new Pair(required(row, "from_stop_id"), required(row, "to_stop_id"));
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        require(value != null && !value.isBlank(), "Missing CSV value: " + key);
        return value;
    }

    private static double number(Map<String, String> row, String key) {
        return Double.parseDouble(required(row, key));
    }

    private static int integer(Map<String, String> row, String key) {
        return Integer.parseInt(required(row, key));
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(";"))
                .map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    private static TransitStopFacility requireStop(
            Map<String, TransitStopFacility> stops,
            String id
    ) {
        TransitStopFacility result = stops.get(id);
        require(result != null, "Transfer relation references missing stop: " + id);
        return result;
    }

    private static Id<TransitStopFacility> stopId(String id) {
        return Id.create(id, TransitStopFacility.class);
    }

    private static String name(TransitStopFacility facility) {
        return facility.getName() == null ? "" : facility.getName();
    }

    private static String area(TransitStopFacility facility) {
        Id<TransitStopArea> id = facility.getStopAreaId();
        return id == null ? "" : id.toString();
    }

    private static String seconds(double value) {
        return Math.rint(value) == value
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Hub(
            int rank,
            String hubId,
            String mpId,
            String lhmName,
            String size,
            String node,
            List<String> stopAreas,
            List<String> confirmedFacilities,
            double reduction,
            int minimumSeconds,
            String scenario,
            String status
    ) {
    }

    record Pair(String from, String to) {
        @Override
        public String toString() {
            return from + " -> " + to;
        }
    }

    record Change(
            Hub hub,
            Pair pair,
            String fromName,
            String fromArea,
            String toName,
            String toArea,
            double original,
            long proposed,
            boolean floorApplied
    ) {
        static final Comparator<Change> ORDER = Comparator
                .comparingInt((Change change) -> change.hub().rank())
                .thenComparing(change -> change.pair().from())
                .thenComparing(change -> change.pair().to());
    }

    record SelfRelation(Hub hub, Pair pair, double original) {
        static final Comparator<SelfRelation> ORDER = Comparator
                .comparingInt((SelfRelation relation) -> relation.hub().rank())
                .thenComparing(relation -> relation.pair().from())
                .thenComparing(relation -> relation.pair().to());
    }

    record Analysis(
            List<Hub> hubs,
            Map<Hub, Set<String>> facilities,
            List<Change> changes,
            List<SelfRelation> selfRelations
    ) {
    }

    record Application(Analysis analysis, ScheduleSnapshot before) {
    }

    record ScheduleSnapshot(
            Set<String> lineIds,
            Set<String> routeIds,
            Set<String> stopIds,
            Set<String> departureIds,
            Map<Pair, Double> relations
    ) {
        static ScheduleSnapshot capture(TransitSchedule schedule) {
            Set<String> lines = new TreeSet<>();
            Set<String> routes = new TreeSet<>();
            Set<String> departures = new TreeSet<>();
            for (TransitLine line : schedule.getTransitLines().values()) {
                lines.add(line.getId().toString());
                for (TransitRoute route : line.getRoutes().values()) {
                    String routeKey = line.getId() + " / " + route.getId();
                    routes.add(routeKey);
                    route.getDepartures().values().forEach(departure ->
                            departures.add(routeKey + " / " + departure.getId()));
                }
            }
            Set<String> stops = schedule.getFacilities().keySet().stream()
                    .map(Id::toString).collect(Collectors.toCollection(TreeSet::new));
            return new ScheduleSnapshot(
                    Set.copyOf(lines), Set.copyOf(routes), Set.copyOf(stops),
                    Set.copyOf(departures), relationValues(schedule)
            );
        }
    }
}
