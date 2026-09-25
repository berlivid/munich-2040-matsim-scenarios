package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only, fail-closed audit of the synthetic GTFS calibration source. */
public final class AnalyzeGtfs2019CalibrationInput {
    public static final Path SOURCE = Path.of(
            "original-input-data/mvv_gtfs_2019/gtfs_2019.zip"
    );
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<String> REQUIRED = Set.of(
            "agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt"
    );
    private static final Set<String> RECOGNIZED = Set.of(
            "agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt",
            "calendar.txt", "calendar_dates.txt", "feed_info.txt", "shapes.txt",
            "transfers.txt", "frequencies.txt", "fare_attributes.txt", "fare_rules.txt",
            "pathways.txt", "levels.txt", "translations.txt", "attributions.txt"
    );

    private AnalyzeGtfs2019CalibrationInput() {
    }

    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? SOURCE : Path.of(args[0]);
        Analysis analysis = analyze(source);
        System.out.print(analysis.asText());
        if (!analysis.blockers().isEmpty()) {
            throw new IllegalStateException(
                    "GTFS 2019 analysis blocked: " + analysis.blockers()
            );
        }
    }

    static Analysis analyze(Path source) throws Exception {
        requireFile(source);
        State state = new State(source, Files.size(source), sha256(source));
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            zip.stream().sorted(Comparator.comparing(ZipEntry::getName)).forEach(entry -> {
                if (!entry.isDirectory()) {
                    state.entries.put(entry.getName(), entry.getSize());
                }
            });
            validateEntries(state);
            if (!state.blockers.isEmpty()) return state.finish();

            readAgencies(zip, state);
            readCalendars(zip, state);
            readStops(zip, state);
            readShapes(zip, state);
            readRoutes(zip, state);
            readTrips(zip, state);
            readStopTimes(zip, state);
            readTransfers(zip, state);
            validateReferencesAndSelectDate(state);
        }
        return state.finish();
    }

    private static void validateEntries(State s) {
        for (String required : REQUIRED) {
            if (!s.entries.containsKey(required)) {
                s.blockers.add("Missing required GTFS table: " + required);
            }
        }
        if (!s.entries.containsKey("calendar.txt")
                && !s.entries.containsKey("calendar_dates.txt")) {
            s.blockers.add("Neither calendar.txt nor calendar_dates.txt is present.");
        }
        s.entries.keySet().stream().filter(name -> !RECOGNIZED.contains(name))
                .forEach(name -> s.warnings.add("Unrecognized extra table: " + name));
        if (!s.entries.containsKey("feed_info.txt")) {
            s.warnings.add("feed_info.txt is absent; publisher and feed-version metadata are unavailable.");
        }
    }

    private static void readAgencies(ZipFile zip, State s) throws Exception {
        read(zip, "agency.txt", table -> {
            String id = table.value("agency_id");
            if (id.isBlank()) id = "<single-agency-blank-id>";
            if (!s.agencies.add(id)) s.blockers.add("Duplicate agency_id: " + id);
            s.agencyNames.put(id, table.value("agency_name"));
            String timezone = table.value("agency_timezone");
            try {
                ZoneId.of(timezone);
            } catch (Exception exception) {
                s.invalidAgencyTimezones.merge(timezone, 1L, Long::sum);
            }
            s.agencyRows++;
        });
    }

    private static void readCalendars(ZipFile zip, State s) throws Exception {
        if (entry(zip, "calendar.txt") != null) {
            read(zip, "calendar.txt", table -> {
                String id = table.required("service_id");
                boolean[] days = new boolean[7];
                String[] names = {"monday", "tuesday", "wednesday", "thursday",
                        "friday", "saturday", "sunday"};
                for (int i = 0; i < names.length; i++) {
                    String value = table.required(names[i]);
                    if (!value.equals("0") && !value.equals("1")) {
                        s.blockers.add("Invalid " + names[i] + " flag for service " + id);
                    }
                    days[i] = value.equals("1");
                }
                LocalDate start = date(table.required("start_date"), s, "calendar start");
                LocalDate end = date(table.required("end_date"), s, "calendar end");
                if (start != null && end != null && end.isBefore(start)) {
                    s.blockers.add("Calendar end precedes start for service " + id);
                }
                if (s.calendars.put(id, new CalendarRule(id, days, start, end)) != null) {
                    s.blockers.add("Duplicate calendar service_id: " + id);
                }
                s.calendarRows++;
            });
        }
        if (entry(zip, "calendar_dates.txt") != null) {
            read(zip, "calendar_dates.txt", table -> {
                String service = table.required("service_id");
                LocalDate date = date(table.required("date"), s, "calendar exception");
                int type = integer(table.required("exception_type"), s,
                        "calendar exception type");
                if (type != 1 && type != 2) {
                    s.blockers.add("Invalid calendar exception_type for " + service);
                }
                if (date != null) {
                    String key = service + "@" + date;
                    if (s.exceptions.put(key, new CalendarException(service, date, type)) != null) {
                        s.blockers.add("Duplicate calendar exception: " + key);
                    }
                }
                s.calendarDateRows++;
            });
        }
    }

    private static void readStops(ZipFile zip, State s) throws Exception {
        read(zip, "stops.txt", table -> {
            String id = table.required("stop_id");
            if (!s.stops.add(id)) s.blockers.add("Duplicate stop_id: " + id);
            String parent = table.value("parent_station");
            if (!parent.isBlank()) s.stopParents.put(id, parent);
            String location = table.value("location_type");
            s.locationTypes.merge(location.isBlank() ? "0_or_blank" : location,
                    1L, Long::sum);
            String latText = table.value("stop_lat");
            String lonText = table.value("stop_lon");
            if (latText.isBlank() || lonText.isBlank()) {
                s.missingCoordinates++;
            } else {
                double lat = decimal(latText, s, "stop_lat " + id);
                double lon = decimal(lonText, s, "stop_lon " + id);
                if (Double.isFinite(lat) && Double.isFinite(lon)) {
                    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                        s.blockers.add("Invalid WGS84 coordinate at stop " + id);
                    }
                    s.minLat = Math.min(s.minLat, lat); s.maxLat = Math.max(s.maxLat, lat);
                    s.minLon = Math.min(s.minLon, lon); s.maxLon = Math.max(s.maxLon, lon);
                    if (lat >= 47.9 && lat <= 48.4 && lon >= 11.1 && lon <= 12.0) {
                        s.munichRegionStops++;
                    }
                    if (lat >= 47.0 && lat <= 55.5 && lon >= 5.0 && lon <= 16.0) {
                        s.germanyBoundsStops++;
                    }
                }
            }
            s.stopRows++;
        });
    }

    private static void readShapes(ZipFile zip, State s) throws Exception {
        if (entry(zip, "shapes.txt") == null) return;
        read(zip, "shapes.txt", table -> {
            s.shapeIds.add(table.required("shape_id"));
            s.shapeRows++;
        });
    }

    private static void readRoutes(ZipFile zip, State s) throws Exception {
        read(zip, "routes.txt", table -> {
            String id = table.required("route_id");
            String agency = table.value("agency_id");
            if (agency.isBlank()) agency = "<single-agency-blank-id>";
            int type = integer(table.required("route_type"), s, "route_type " + id);
            String munich = table.optional("München");
            String analysis2019 = table.optional("Analyse_2019");
            String forecast2037 = table.optional("Prognosenetz_2037");
            String rail = table.optional("Analyselinie_Schiene");
            String ferry = table.optional("Fährlinie_BY");
            String sbahn = table.optional("Linienname S-Bahn MUC");
            if (s.routes.put(id, new Route(id, agency, type, munich, analysis2019,
                    forecast2037, rail, ferry, sbahn)) != null) {
                s.blockers.add("Duplicate route_id: " + id);
            }
            s.routesPerAgency.merge(agency, 1L, Long::sum);
            s.routeTypes.merge(type, 1L, Long::sum);
            if (!isSupportedRouteType(type)) s.unsupportedRouteTypes.add(type);
            if ("1".equals(munich)) s.munichFlaggedRoutes++;
            if ("1".equals(analysis2019)) s.analysis2019FlaggedRoutes++;
            if ("1".equals(forecast2037)) s.forecast2037FlaggedRoutes++;
            if ("1".equals(rail)) s.railFlaggedRoutes++;
            if ("1".equals(ferry)) s.ferryFlaggedRoutes++;
            if (!sbahn.isBlank()) s.sbahnNamedRoutes++;
            s.routeRows++;
        });
    }

    private static void readTrips(ZipFile zip, State s) throws Exception {
        read(zip, "trips.txt", table -> {
            String id = table.required("trip_id");
            Trip trip = new Trip(id, table.required("route_id"),
                    table.required("service_id"), table.value("shape_id"));
            if (s.trips.put(id, trip) != null) s.blockers.add("Duplicate trip_id: " + id);
            s.tripsPerService.merge(trip.serviceId(), 1L, Long::sum);
            Route route = s.routes.get(trip.routeId());
            if (route != null) {
                s.tripsPerRouteType.merge(route.type(), 1L, Long::sum);
                s.tripsPerAgency.merge(route.agencyId(), 1L, Long::sum);
                if ("1".equals(route.analysis2019())) s.analysis2019Trips++;
                if ("1".equals(route.forecast2037())) s.forecast2037Trips++;
                if ("1".equals(route.munich())) s.munichFlaggedTrips++;
                if ("1".equals(route.rail())) s.railFlaggedTrips++;
                if (!route.sbahnName().isBlank()) s.sbahnNamedTrips++;
            }
            s.tripRows++;
        });
    }

    private static void readStopTimes(ZipFile zip, State s) throws Exception {
        read(zip, "stop_times.txt", table -> {
            String trip = table.required("trip_id");
            String stop = table.required("stop_id");
            int sequence = integer(table.required("stop_sequence"), s,
                    "stop_sequence for trip " + trip);
            int arrival = time(table.value("arrival_time"), s, "arrival " + trip);
            int departure = time(table.value("departure_time"), s, "departure " + trip);
            StopTimeState previous = s.lastStopTime.get(trip);
            if (previous != null) {
                if (sequence <= previous.sequence()) s.nonIncreasingSequences++;
                int current = arrival >= 0 ? arrival : departure;
                if (current >= 0 && previous.time() >= 0 && current < previous.time()) {
                    s.nonMonotonicTimes++;
                }
            }
            if (arrival >= 0 && departure >= 0 && departure < arrival) {
                s.departureBeforeArrival++;
            }
            s.lastStopTime.put(trip, new StopTimeState(sequence,
                    departure >= 0 ? departure : arrival));
            s.stopTimesPerTrip.merge(trip, 1, Integer::sum);
            if (!s.trips.containsKey(trip)) s.missingTripStopTimeRefs++;
            if (!s.stops.contains(stop)) s.missingStopStopTimeRefs++;
            s.stopTimeRows++;
        });
    }

    private static void readTransfers(ZipFile zip, State s) throws Exception {
        if (entry(zip, "transfers.txt") == null) return;
        read(zip, "transfers.txt", table -> {
            String from = table.required("from_stop_id");
            String to = table.required("to_stop_id");
            if (!s.stops.contains(from)) s.missingTransferStopRefs++;
            if (!s.stops.contains(to)) s.missingTransferStopRefs++;
            s.transferRows++;
        });
    }

    private static void validateReferencesAndSelectDate(State s) {
        for (Map.Entry<String, String> entry : s.stopParents.entrySet()) {
            if (!s.stops.contains(entry.getValue())) s.missingParentRefs++;
        }
        Set<String> services = new HashSet<>(s.calendars.keySet());
        s.exceptions.values().forEach(exception -> services.add(exception.serviceId()));
        for (Trip trip : s.trips.values()) {
            Route route = s.routes.get(trip.routeId());
            if (route == null) s.missingTripRouteRefs++;
            if (!services.contains(trip.serviceId())) s.missingTripServiceRefs++;
            if (!trip.shapeId().isBlank() && !s.shapeIds.contains(trip.shapeId())) {
                s.missingTripShapeRefs++;
            }
            if (!s.stopTimesPerTrip.containsKey(trip.id())) s.tripsWithoutStopTimes++;
            else if (s.stopTimesPerTrip.get(trip.id()) < 2) s.tripsWithFewerThanTwoStops++;
        }
        for (Route route : s.routes.values()) {
            if (!s.agencies.contains(route.agencyId())) s.missingRouteAgencyRefs++;
        }
        if (!s.unsupportedRouteTypes.isEmpty()) {
            s.blockers.add("Unsupported route_type values: " + s.unsupportedRouteTypes);
        }
        if (s.routeTypes.size() == 1 && s.routeTypes.containsKey(0)
                && (s.railFlaggedRoutes > 0 || s.sbahnNamedRoutes > 0
                || s.ferryFlaggedRoutes > 0)) {
            s.warnings.add("All " + s.routeRows + " source routes use route_type=0 despite "
                    + "custom mode metadata. The approved synthetic-2019 builder must apply the "
                    + "validated GTFS-2037 classification before conversion.");
        }
        if (s.analysis2019FlaggedRoutes != s.routeRows) {
            s.warnings.add((s.routeRows - s.analysis2019FlaggedRoutes)
                    + " routes and " + (s.tripRows - s.analysis2019Trips)
                    + " trips are not marked Analyse_2019=1 and are excluded by the approved rule.");
        }
        if (s.forecast2037FlaggedRoutes > 0 || s.forecast2037Trips > 0) {
            s.warnings.add(s.forecast2037FlaggedRoutes + " routes and "
                    + s.forecast2037Trips + " trips are marked Prognosenetz_2037=1. The approved "
                    + "rule does not use that flag as an exclusion criterion.");
        }
        addReferenceBlocker(s, s.missingParentRefs, "missing parent_station references");
        addReferenceBlocker(s, s.missingTripRouteRefs, "missing trip-to-route references");
        addReferenceBlocker(s, s.missingTripServiceRefs, "missing trip-to-service references");
        addReferenceBlocker(s, s.missingTripShapeRefs, "missing trip-to-shape references");
        addReferenceBlocker(s, s.missingRouteAgencyRefs, "missing route-to-agency references");
        addReferenceBlocker(s, s.missingTripStopTimeRefs, "missing stop_time-to-trip references");
        addReferenceBlocker(s, s.missingStopStopTimeRefs, "missing stop_time-to-stop references");
        addReferenceBlocker(s, s.missingTransferStopRefs, "missing transfer-to-stop references");
        addReferenceBlocker(s, s.tripsWithoutStopTimes, "trips without stop_times");
        addReferenceBlocker(s, s.tripsWithFewerThanTwoStops, "trips with fewer than two stops");
        addReferenceBlocker(s, s.nonIncreasingSequences, "non-increasing stop sequences");
        addReferenceBlocker(s, s.nonMonotonicTimes, "non-monotonic trip times");
        addReferenceBlocker(s, s.departureBeforeArrival, "departures before arrival");

        if (!s.invalidAgencyTimezones.isEmpty()) {
            s.warnings.add("Invalid or unknown agency timezones: " + s.invalidAgencyTimezones);
        }
        if (s.calendars.isEmpty() && s.exceptions.isEmpty()) return;
        LocalDate min = s.calendars.values().stream().map(CalendarRule::start)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate max = s.calendars.values().stream().map(CalendarRule::end)
                .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        for (CalendarException exception : s.exceptions.values()) {
            if (min == null || exception.date().isBefore(min)) min = exception.date();
            if (max == null || exception.date().isAfter(max)) max = exception.date();
        }
        s.validFrom = min; s.validTo = max;
        if (min == null || max == null) {
            s.blockers.add("No finite service validity range can be derived.");
            return;
        }
        if (max.isAfter(min.plusYears(10))) {
            s.blockers.add("Service validity exceeds ten years; date selection is not bounded.");
            return;
        }
        List<ServiceDay> possible = new ArrayList<>();
        for (LocalDate date = min; !date.isAfter(max); date = date.plusDays(1)) {
            Set<String> active = activeServices(s, date);
            long trips = active.stream().mapToLong(id -> s.tripsPerService.getOrDefault(id, 0L)).sum();
            if (trips > 0) possible.add(new ServiceDay(date, active.size(), trips,
                    exceptionCount(s, date)));
        }
        s.serviceDays.addAll(possible);
        List<ServiceDay> regularWednesdays = possible.stream()
                .filter(day -> day.date().getDayOfWeek() == DayOfWeek.WEDNESDAY)
                .filter(day -> day.exceptionCount() == 0)
                .sorted(Comparator.comparingLong(ServiceDay::trips).reversed()
                        .thenComparing(ServiceDay::date))
                .toList();
        if (!regularWednesdays.isEmpty()) {
            s.selectedDate = regularWednesdays.getFirst().date();
            s.dateSelectionReason = "regular Wednesday with the highest trip count; earliest date breaks ties";
        } else if (possible.size() == 1) {
            ServiceDay only = possible.getFirst();
            s.selectedDate = only.date();
            s.dateSelectionReason = "approved technical activation date; not a historical reference date";
            s.warnings.add("No regular Wednesday exists in the feed validity range. The sole active date "
                    + only.date() + " is " + only.date().getDayOfWeek()
                    + "; it is used only as the approved technical activation date.");
        } else {
            s.blockers.add("No exception-free Wednesday with active trips exists; service-date decision required.");
        }
    }

    private static Set<String> activeServices(State s, LocalDate date) {
        Set<String> active = new HashSet<>();
        for (CalendarRule rule : s.calendars.values()) {
            if (rule.start() != null && rule.end() != null
                    && !date.isBefore(rule.start()) && !date.isAfter(rule.end())
                    && rule.days()[date.getDayOfWeek().getValue() - 1]) active.add(rule.id());
        }
        for (CalendarException exception : s.exceptions.values()) {
            if (exception.date().equals(date)) {
                if (exception.type() == 1) active.add(exception.serviceId());
                else if (exception.type() == 2) active.remove(exception.serviceId());
            }
        }
        return active;
    }

    private static long exceptionCount(State s, LocalDate date) {
        return s.exceptions.values().stream().filter(e -> e.date().equals(date)).count();
    }

    private static boolean isSupportedRouteType(int type) {
        return type >= 0 && type <= 7 || type >= 100 && type <= 1702;
    }

    private static void addReferenceBlocker(State s, long count, String label) {
        if (count > 0) s.blockers.add(count + " " + label);
    }

    private static LocalDate date(String value, State s, String label) {
        try {
            return LocalDate.parse(value, GTFS_DATE);
        } catch (Exception exception) {
            s.blockers.add("Invalid " + label + ": " + value);
            return null;
        }
    }

    private static int integer(String value, State s, String label) {
        try {
            return Integer.parseInt(value);
        } catch (Exception exception) {
            s.blockers.add("Invalid integer for " + label + ": " + value);
            return Integer.MIN_VALUE;
        }
    }

    private static double decimal(String value, State s, String label) {
        try {
            return Double.parseDouble(value);
        } catch (Exception exception) {
            s.blockers.add("Invalid decimal for " + label + ": " + value);
            return Double.NaN;
        }
    }

    private static int time(String value, State s, String label) {
        if (value.isBlank()) return -1;
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            s.blockers.add("Invalid GTFS time for " + label + ": " + value);
            return -1;
        }
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int sec = Integer.parseInt(parts[2]);
            if (h < 0 || m < 0 || m > 59 || sec < 0 || sec > 59) throw new NumberFormatException();
            return h * 3600 + m * 60 + sec;
        } catch (NumberFormatException exception) {
            s.blockers.add("Invalid GTFS time for " + label + ": " + value);
            return -1;
        }
    }

    private static void read(ZipFile zip, String name, Consumer<TableRow> consumer)
            throws Exception {
        ZipEntry entry = entry(zip, name);
        if (entry == null) return;
        try (CsvReader reader = new CsvReader(zip, entry)) {
            List<String> row;
            while ((row = reader.next()) != null) consumer.accept(new TableRow(reader, row));
        }
    }

    private static ZipEntry entry(ZipFile zip, String name) {
        return zip.getEntry(name);
    }

    private static void requireFile(Path file) {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing file: " + file);
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    static final class CsvReader implements AutoCloseable {
        private final PushbackReader reader;
        private final Map<String, Integer> columns = new LinkedHashMap<>();
        private long line = 1;

        CsvReader(ZipFile zip, ZipEntry entry) throws IOException {
            reader = new PushbackReader(new BufferedReader(new InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8), 1 << 20), 1);
            List<String> header = record();
            if (header == null) throw new IOException("Empty CSV table: " + entry.getName());
            for (int i = 0; i < header.size(); i++) {
                String name = i == 0 ? header.get(i).replace("\uFEFF", "") : header.get(i);
                if (columns.put(name, i) != null) throw new IOException(
                        "Duplicate column " + name + " in " + entry.getName());
            }
        }

        List<String> next() throws IOException {
            List<String> row = record();
            if (row != null && row.size() != columns.size()) {
                throw new IOException("CSV width mismatch at record " + line
                        + ": expected " + columns.size() + " fields, got " + row.size());
            }
            return row;
        }

        String value(List<String> row, String name, boolean required) {
            Integer index = columns.get(name);
            if (index == null) {
                if (required) throw new IllegalArgumentException("Missing required column: " + name);
                return "";
            }
            return row.get(index).trim();
        }

        List<String> header() { return List.copyOf(columns.keySet()); }

        int column(String name) {
            Integer index = columns.get(name);
            if (index == null) throw new IllegalArgumentException("Missing required column: " + name);
            return index;
        }

        private List<String> record() throws IOException {
            List<String> values = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false, started = false;
            while (true) {
                int raw = reader.read();
                if (raw < 0) {
                    if (!started && field.isEmpty() && values.isEmpty()) return null;
                    if (quoted) throw new IOException("Unclosed quoted CSV field");
                    values.add(field.toString()); line++; return values;
                }
                started = true;
                char c = (char) raw;
                if (quoted) {
                    if (c == '"') {
                        int next = reader.read();
                        if (next == '"') field.append('"');
                        else { quoted = false; if (next >= 0) reader.unread(next); }
                    } else field.append(c);
                } else if (c == '"' && field.isEmpty()) quoted = true;
                else if (c == ',') { values.add(field.toString()); field.setLength(0); }
                else if (c == '\n') { values.add(field.toString()); line++; return values; }
                else if (c == '\r') {
                    int next = reader.read(); if (next != '\n' && next >= 0) reader.unread(next);
                    values.add(field.toString()); line++; return values;
                } else field.append(c);
            }
        }

        @Override public void close() throws IOException { reader.close(); }
    }

    record TableRow(CsvReader reader, List<String> fields) {
        String required(String name) { return reader.value(fields, name, true); }
        String value(String name) { return reader.value(fields, name, true); }
        String optional(String name) { return reader.value(fields, name, false); }
    }

    record Route(String id, String agencyId, int type, String munich, String analysis2019,
                 String forecast2037, String rail, String ferry, String sbahnName) { }
    record Trip(String id, String routeId, String serviceId, String shapeId) { }
    record CalendarRule(String id, boolean[] days, LocalDate start, LocalDate end) { }
    record CalendarException(String serviceId, LocalDate date, int type) { }
    record StopTimeState(int sequence, int time) { }
    public record ServiceDay(LocalDate date, int activeServices, long trips, long exceptionCount) { }

    public record Analysis(
            Path source, long sizeBytes, String sha256, Map<String, Long> entries,
            long agencies, long stops, long routes, long trips, long stopTimes,
            long shapes, long transfers, long calendarRows, long calendarDateRows,
            Map<Integer, Long> routeTypes, Map<Integer, Long> tripsPerRouteType,
            Map<String, Long> routesPerAgency, Map<String, Long> tripsPerAgency,
            Map<String, String> agencyNames,
            Map<String, Long> locationTypes, long munichRegionStops,
            long germanyBoundsStops, long missingCoordinates,
            double minLat, double maxLat, double minLon, double maxLon,
            long munichFlaggedRoutes, long analysis2019FlaggedRoutes,
            long forecast2037FlaggedRoutes, long railFlaggedRoutes,
            long ferryFlaggedRoutes, long sbahnNamedRoutes,
            long munichFlaggedTrips, long analysis2019Trips,
            long forecast2037Trips, long railFlaggedTrips, long sbahnNamedTrips,
            LocalDate validFrom, LocalDate validTo, LocalDate selectedDate,
            String dateSelectionReason, List<ServiceDay> serviceDays,
            Map<String, Long> invalidAgencyTimezones,
            List<String> warnings, List<String> blockers
    ) {
        public String asText() {
            StringBuilder out = new StringBuilder();
            out.append("source=").append(source).append('\n');
            out.append("sizeBytes=").append(sizeBytes).append('\n');
            out.append("sha256=").append(sha256).append('\n');
            out.append("entries=").append(entries).append('\n');
            out.append("rows={agencies=").append(agencies).append(", stops=").append(stops)
                    .append(", routes=").append(routes).append(", trips=").append(trips)
                    .append(", stopTimes=").append(stopTimes).append(", shapes=").append(shapes)
                    .append(", transfers=").append(transfers).append(", calendar=")
                    .append(calendarRows).append(", calendarDates=").append(calendarDateRows)
                    .append("}\n");
            out.append("routeTypes=").append(routeTypes).append('\n');
            out.append("tripsPerRouteType=").append(tripsPerRouteType).append('\n');
            out.append("agencyNames=").append(agencyNames).append('\n');
            out.append("routesPerAgency=").append(routesPerAgency).append('\n');
            out.append("tripsPerAgency=").append(tripsPerAgency).append('\n');
            out.append("locationTypes=").append(locationTypes).append('\n');
            out.append("coordinates={lat=").append(minLat).append("..").append(maxLat)
                    .append(", lon=").append(minLon).append("..").append(maxLon)
                    .append(", munichRegionStops=").append(munichRegionStops)
                    .append(", germanyBoundsStops=").append(germanyBoundsStops)
                    .append(", missing=").append(missingCoordinates).append("}\n");
            out.append("flags={MunichRoutes=").append(munichFlaggedRoutes)
                    .append(", Analyse2019Routes=").append(analysis2019FlaggedRoutes)
                    .append(", Forecast2037Routes=").append(forecast2037FlaggedRoutes)
                    .append(", RailRoutes=").append(railFlaggedRoutes)
                    .append(", FerryRoutes=").append(ferryFlaggedRoutes)
                    .append(", SbahnNamedRoutes=").append(sbahnNamedRoutes)
                    .append(", MunichTrips=").append(munichFlaggedTrips)
                    .append(", Analyse2019Trips=").append(analysis2019Trips)
                    .append(", Forecast2037Trips=").append(forecast2037Trips)
                    .append(", RailTrips=").append(railFlaggedTrips)
                    .append(", SbahnNamedTrips=").append(sbahnNamedTrips)
                    .append("}\n");
            out.append("validity=").append(validFrom).append("..").append(validTo).append('\n');
            out.append("selectedDate=").append(selectedDate).append('\n');
            out.append("dateSelectionReason=").append(dateSelectionReason).append('\n');
            out.append("serviceDays=").append(serviceDays).append('\n');
            out.append("invalidAgencyTimezones=").append(invalidAgencyTimezones).append('\n');
            out.append("warnings=").append(warnings).append('\n');
            out.append("blockers=").append(blockers).append('\n');
            return out.toString();
        }
    }

    private static final class State {
        final Path source; final long size; final String sha;
        final Map<String, Long> entries = new LinkedHashMap<>();
        final Set<String> agencies = new HashSet<>(), stops = new HashSet<>(), shapeIds = new HashSet<>();
        final Map<String, String> stopParents = new HashMap<>(), agencyNames = new TreeMap<>();
        final Map<String, Route> routes = new HashMap<>();
        final Map<String, Trip> trips = new HashMap<>();
        final Map<String, CalendarRule> calendars = new HashMap<>();
        final Map<String, CalendarException> exceptions = new HashMap<>();
        final Map<String, Long> tripsPerService = new HashMap<>();
        final Map<String, Long> routesPerAgency = new TreeMap<>(), tripsPerAgency = new TreeMap<>();
        final Map<Integer, Long> tripsPerRouteType = new TreeMap<>(), routeTypes = new TreeMap<>();
        final Map<String, Long> locationTypes = new TreeMap<>(), invalidAgencyTimezones = new TreeMap<>();
        final Map<String, Integer> stopTimesPerTrip = new HashMap<>();
        final Map<String, StopTimeState> lastStopTime = new HashMap<>();
        final Set<Integer> unsupportedRouteTypes = new TreeSet<>();
        final List<ServiceDay> serviceDays = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> blockers = new ArrayList<>();
        long agencyRows, stopRows, routeRows, tripRows, stopTimeRows, shapeRows, transferRows;
        long calendarRows, calendarDateRows, missingCoordinates, munichRegionStops, germanyBoundsStops;
        long munichFlaggedRoutes, analysis2019FlaggedRoutes, forecast2037FlaggedRoutes;
        long railFlaggedRoutes, ferryFlaggedRoutes, sbahnNamedRoutes;
        long munichFlaggedTrips, analysis2019Trips, forecast2037Trips, railFlaggedTrips, sbahnNamedTrips;
        long missingParentRefs, missingTripRouteRefs, missingTripServiceRefs, missingTripShapeRefs;
        long missingRouteAgencyRefs, missingTripStopTimeRefs, missingStopStopTimeRefs, missingTransferStopRefs;
        long tripsWithoutStopTimes, tripsWithFewerThanTwoStops, nonIncreasingSequences;
        long nonMonotonicTimes, departureBeforeArrival;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        LocalDate validFrom, validTo, selectedDate; String dateSelectionReason = "not selected";

        State(Path source, long size, String sha) {
            this.source = source; this.size = size; this.sha = sha;
        }

        Analysis finish() {
            return new Analysis(source, size, sha, Map.copyOf(entries), agencyRows, stopRows,
                    routeRows, tripRows, stopTimeRows, shapeRows, transferRows, calendarRows,
                    calendarDateRows, Map.copyOf(routeTypes), Map.copyOf(tripsPerRouteType),
                    Map.copyOf(routesPerAgency), Map.copyOf(tripsPerAgency),
                    Map.copyOf(agencyNames),
                    Map.copyOf(locationTypes), munichRegionStops, germanyBoundsStops,
                    missingCoordinates, minLat, maxLat, minLon, maxLon, munichFlaggedRoutes,
                    analysis2019FlaggedRoutes, forecast2037FlaggedRoutes, railFlaggedRoutes,
                    ferryFlaggedRoutes, sbahnNamedRoutes, munichFlaggedTrips,
                    analysis2019Trips, forecast2037Trips, railFlaggedTrips, sbahnNamedTrips,
                    validFrom, validTo, selectedDate,
                    dateSelectionReason, List.copyOf(serviceDays),
                    Map.copyOf(invalidAgencyTimezones), List.copyOf(warnings),
                    List.copyOf(blockers));
        }
    }
}
