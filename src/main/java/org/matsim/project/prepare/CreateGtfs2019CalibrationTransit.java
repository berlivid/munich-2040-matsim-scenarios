package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.vehicles.MatsimVehicleWriter;

/** Builds and validates the isolated MATSim transit inputs for synthetic 2019 calibration. */
public final class CreateGtfs2019CalibrationTransit {
    static final Path GTFS = BuildSyntheticGtfs2019Reference.OUTPUT;
    static final Path BASE_NETWORK = BuildSyntheticGtfs2019Reference.NETWORK;
    static final Path BASE_CONFIG = Path.of("scenarios/munich_base_2023/config_base.xml");
    static final Path OUTPUT_DIR = Path.of("scenarios/munich_calibration_2019/input_transit");
    static final Path VALIDATION_CONFIG = Path.of("scenarios/munich_calibration_2019/config_input_validation.xml");
    static final LocalDate TECHNICAL_DATE = LocalDate.of(2026, 2, 13);
    private static final String CRS = "EPSG:31468";

    private CreateGtfs2019CalibrationTransit() { }

    public static void main(String[] args) throws Exception {
        BuildResult result = build();
        System.out.print(result.asText());
    }

    static BuildResult build() throws Exception {
        require(Files.isRegularFile(GTFS), "Missing synthetic subset: " + GTFS);
        require(Files.isRegularFile(BASE_NETWORK), "Missing public base network: " + BASE_NETWORK);
        require(Files.isRegularFile(BASE_CONFIG), "Missing base config: " + BASE_CONFIG);

        Scenario scenario = loadRoad(BASE_NETWORK);
        Set<String> roadNodes = ids(scenario.getNetwork().getNodes().keySet());
        Set<String> roadLinks = ids(scenario.getNetwork().getLinks().keySet());
        Set<String> carLinks = new TreeSet<>();
        scenario.getNetwork().getLinks().forEach((id, link) -> {
            if (link.getAllowedModes().contains(TransportMode.car)) carLinks.add(id.toString());
        });

        CoordinateTransformation transformation = TransformationFactory
                .getCoordinateTransformation(TransformationFactory.WGS84, CRS);
        GtfsConverter.newBuilder()
                .setFeed(GTFS)
                .setDate(TECHNICAL_DATE)
                .setTransform(transformation)
                .setScenario(scenario)
                .setUseExtendedRouteTypes(true)
                .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                .setIncludeMinimalTransferTimes(true)
                .build().convert();
        new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), "pt_2019_")
                .createNetwork();
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles())
                .run(TransportMode.pt);
        validate(scenario, roadNodes, roadLinks, carLinks);
        Gtfs2019ScheduleTimePolicy.Audit expectedTimeAudit =
                Gtfs2019ScheduleTimePolicy.audit(scenario.getTransitSchedule());
        Counts expected = Counts.from(scenario);

        Files.createDirectories(OUTPUT_DIR);
        Path work = Files.createTempDirectory(OUTPUT_DIR, ".gtfs2019-build-");
        try {
            FilesBundle candidate = new FilesBundle(work.resolve("network-with-pt.xml.gz"),
                    work.resolve("transitSchedule.xml.gz"), work.resolve("transitVehicles.xml.gz"));
            new NetworkWriter(scenario.getNetwork()).write(candidate.network().toString());
            new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(candidate.schedule().toString());
            new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(candidate.vehicles().toString());

            Scenario reread = load(candidate);
            validate(reread, roadNodes, roadLinks, carLinks);
            Gtfs2019ScheduleTimePolicy.Audit actualTimeAudit =
                    Gtfs2019ScheduleTimePolicy.audit(reread.getTransitSchedule());
            Counts actual = Counts.from(reread);
            require(expected.equals(actual), "Counts changed during write/reread: " + expected + " vs " + actual);
            require(expectedTimeAudit.latestDeparture() == actualTimeAudit.latestDeparture()
                            && expectedTimeAudit.latestVehicleArrival() == actualTimeAudit.latestVehicleArrival()
                            && expectedTimeAudit.qsimEndTime() == actualTimeAudit.qsimEndTime(),
                    "Schedule timing changed during write/reread");

            FilesBundle output = new FilesBundle(OUTPUT_DIR.resolve("network-with-pt.xml.gz"),
                    OUTPUT_DIR.resolve("transitSchedule.xml.gz"), OUTPUT_DIR.resolve("transitVehicles.xml.gz"));
            move(candidate.network(), output.network());
            move(candidate.schedule(), output.schedule());
            move(candidate.vehicles(), output.vehicles());
            writeValidationConfig(actualTimeAudit);
            return new BuildResult(actual, modeCounts(reread), sha256(output.network()),
                    sha256(output.schedule()), sha256(output.vehicles()), actualTimeAudit.summary());
        } finally { deleteTree(work); }
    }

    static Scenario loadPublished() { return load(new FilesBundle(
            OUTPUT_DIR.resolve("network-with-pt.xml.gz"), OUTPUT_DIR.resolve("transitSchedule.xml.gz"),
            OUTPUT_DIR.resolve("transitVehicles.xml.gz")));
    }

    private static Scenario loadRoad(Path network) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem(CRS);
        config.network().setInputFile(network.toString());
        return ScenarioUtils.loadScenario(config);
    }

    private static Scenario load(FilesBundle files) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem(CRS);
        config.network().setInputFile(files.network().toString());
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile(files.schedule().toString());
        config.transit().setVehiclesFile(files.vehicles().toString());
        return ScenarioUtils.loadScenario(config);
    }

    private static void validate(Scenario scenario, Set<String> roadNodes, Set<String> roadLinks,
                                 Set<String> carLinks) {
        require(scenario.getNetwork().getNodes().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()).containsAll(roadNodes),
                "A base road node was lost");
        require(scenario.getNetwork().getLinks().keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()).containsAll(roadLinks),
                "A base road link was lost");
        for (String id : carLinks) require(scenario.getNetwork().getLinks().get(org.matsim.api.core.v01.Id.createLinkId(id))
                .getAllowedModes().contains(TransportMode.car), "Car mode lost on base link " + id);
        var schedule = scenario.getTransitSchedule();
        require(!schedule.getTransitLines().isEmpty(), "No transit lines");
        schedule.getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> {
            require(route.getStops().size() >= 2, "Transit route with fewer than two stops: " + route.getId());
            route.getStops().forEach(stop -> require(stop.getStopFacility().getLinkId() != null
                    && scenario.getNetwork().getLinks().containsKey(stop.getStopFacility().getLinkId()),
                    "Invalid stop link reference: " + stop.getStopFacility().getId()));
            route.getDepartures().values().forEach(departure -> require(departure.getVehicleId() != null
                    && scenario.getTransitVehicles().getVehicles().containsKey(departure.getVehicleId()),
                    "Departure has no vehicle: " + departure.getId()));
        }));
        Map<String, Long> modes = modeCounts(scenario);
        for (String required : List.of("bus", "tram", "subway", "rail")) {
            require(modes.getOrDefault(required, 0L) > 0, "Missing converted route mode " + required);
        }
    }

    private static Map<String, Long> modeCounts(Scenario scenario) {
        Map<String, Long> modes = new TreeMap<>();
        scenario.getTransitSchedule().getTransitLines().values().forEach(line ->
                line.getRoutes().values().forEach(route -> modes.merge(route.getTransportMode(), 1L, Long::sum)));
        return Map.copyOf(modes);
    }

    static void writeValidationConfig(Gtfs2019ScheduleTimePolicy.Audit timeAudit) throws Exception {
        Config config = ConfigUtils.loadConfig(BASE_CONFIG.toString());
        config.global().setCoordinateSystem(CRS);
        config.global().setRandomSeed(4711);
        config.network().setInputFile("input_transit/network-with-pt.xml.gz");
        config.plans().setInputFile("../munich_base_2023/munich-v1.0-5pct.plans.xml");
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile("input_transit/transitSchedule.xml.gz");
        config.transit().setVehiclesFile("input_transit/transitVehicles.xml.gz");
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(0);
        config.controller().setRunId("munich-calibration-2019");
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        config.controller().setOutputDirectory(
                "scenarios/munich_calibration_2019/output/input-validation-qsim2");
        config.qsim().setFlowCapFactor(0.05);
        config.qsim().setStorageCapFactor(0.05);
        config.qsim().setNumberOfThreads(2);
        config.qsim().setEndTime(timeAudit.qsimEndTime());
        Gtfs2019ScheduleTimePolicy.validateConfiguredEndTime(
                config.qsim().getEndTime().seconds(), timeAudit);
        boolean modeChoice = config.replanning().getStrategySettings().stream()
                .anyMatch(s -> s.getStrategyName() != null
                        && s.getStrategyName().toLowerCase(Locale.ROOT).contains("modechoice"));
        require(!modeChoice, "Base config unexpectedly activates mode choice");
        Files.createDirectories(VALIDATION_CONFIG.getParent());
        Path temp = Files.createTempFile(VALIDATION_CONFIG.getParent(), ".config-2019-", ".xml");
        try { new ConfigWriter(config).write(temp.toString()); move(temp, VALIDATION_CONFIG); }
        finally { Files.deleteIfExists(temp); }
    }

    private static Set<String> ids(Set<?> ids) {
        Set<String> result = new TreeSet<>(); ids.forEach(id -> result.add(id.toString())); return result;
    }
    private static void move(Path from, Path to) throws Exception {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream in = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record FilesBundle(Path network, Path schedule, Path vehicles) { }
    public record Counts(long nodes, long links, long stops, long lines, long routes,
                         long departures, long vehicles, long transfers) {
        static Counts from(Scenario scenario) {
            long routes = scenario.getTransitSchedule().getTransitLines().values().stream()
                    .mapToLong(line -> line.getRoutes().size()).sum();
            long departures = scenario.getTransitSchedule().getTransitLines().values().stream()
                    .flatMap(line -> line.getRoutes().values().stream()).mapToLong(r -> r.getDepartures().size()).sum();
            long transfers = 0;
            var transferIterator = scenario.getTransitSchedule().getMinimalTransferTimes().iterator();
            while (transferIterator.hasNext()) { transferIterator.next(); transfers++; }
            return new Counts(scenario.getNetwork().getNodes().size(), scenario.getNetwork().getLinks().size(),
                    scenario.getTransitSchedule().getFacilities().size(), scenario.getTransitSchedule().getTransitLines().size(),
                    routes, departures, scenario.getTransitVehicles().getVehicles().size(), transfers);
        }
    }
    public record BuildResult(Counts counts, Map<String, Long> routeModes, String networkSha,
                              String scheduleSha, String vehiclesSha, String timeAudit) {
        String asText() { return "counts=" + counts + "\nrouteModes=" + routeModes
                + "\nnetworkSha256=" + networkSha + "\nscheduleSha256=" + scheduleSha
                + "\nvehiclesSha256=" + vehiclesSha + "\n" + timeAudit
                + "\nValidation=PASS\n"; }
    }
}
