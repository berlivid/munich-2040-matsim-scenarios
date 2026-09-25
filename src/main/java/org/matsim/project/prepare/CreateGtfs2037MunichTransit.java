package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;

/**
 * Builds the BAU and Fast Track 2040 MATSim public-transport inputs through
 * one shared conversion and validation pipeline. Existing scenario configs
 * are read only to resolve their declared road-network inputs; they are not
 * modified or activated by this tool.
 */
public final class CreateGtfs2037MunichTransit {

    private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-02-13");
    private static final String CRS = "EPSG:31468";
    private static final Path BASE_ROAD_NETWORK = Path.of(
            "scenarios/munich_base_2023/studyNetworkDense.xml"
    );
    private static final String S8 = "S8_Prognose_Herrsching-Schwaigerlohe";
    private static final String U4 = "MUC_U4_neu Prognose";
    private static final List<String> FAST_TRACK_LINES = List.of(
            "FT_U9", "FT_NR_A", "FT_NR_B"
    );
    private static final List<String> IMPLER_POCCI_PLATFORMS = List.of(
            "FT_U9_IMPLER_POCCI_D0", "FT_U9_IMPLER_POCCI_D1"
    );
    private static final List<String> EXISTING_INTERCHANGE_PLATFORMS = List.of(
            "106211", "106212", "108590", "108591", "108592",
            "BAU_POCCISTRASSE_RAIL_D0", "BAU_POCCISTRASSE_RAIL_D1"
    );
    private static final List<String> COMMON_POCCI_RAIL = List.of(
            "BAU_POCCISTRASSE_RAIL_D0", "BAU_POCCISTRASSE_RAIL_D1"
    );
    private static final List<String> COMMON_POCCI_SUBWAY = List.of("106211", "106212");
    private static final List<String> COMMON_BERDUX = List.of(
            "BAU_BERDUXSTRASSE_S2_D0", "BAU_BERDUXSTRASSE_S2_D1"
    );
    private static final List<Profile> PROFILES = List.of(
            new Profile(
                    "BAU 2040", "bau",
                    Path.of("scenarios/munich_bau_2040/config_bau.xml"),
                    Path.of("original-input-data/mvv_gtfs_2037/generated/"
                            + "gtfs2037_munich_bau.zip"),
                    Path.of("scenarios/munich_bau_2040/input_transit"),
                    54_631, 1_733, 70_620, 95_884, false
            ),
            new Profile(
                    "Fast Track 2040", "fast-track",
                    Path.of("scenarios/munich_fast_track_2040/config_fast_track.xml"),
                    Path.of("original-input-data/mvv_gtfs_2037/generated/"
                            + "gtfs2037_munich_fast_track.zip"),
                    Path.of("scenarios/munich_fast_track_2040/input_transit"),
                    54_655, 1_736, 71_300, 95_912, true
            )
    );

    private CreateGtfs2037MunichTransit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--validate-existing".equals(args[0])) {
            Map<String, ConversionResult> existing = new LinkedHashMap<>();
            for (Profile profile : PROFILES) {
                existing.put(profile.key(), validateExistingOutput(profile));
                System.gc();
            }
            compareScenarios(existing.get("bau"), existing.get("fast-track"));
            printResults(existing.values());
            return;
        }
        boolean analyzeMobilityHubs = args.length == 3
                && "--analyze-mobility-hubs".equals(args[0]);
        String[] selectionArgs = analyzeMobilityHubs
                ? new String[] {args[1], args[2]}
                : args;
        List<Profile> selected = selectProfiles(selectionArgs);
        if (analyzeMobilityHubs && (selected.size() != 1
                || !selected.getFirst().fastTrack())) {
            throw new IllegalArgumentException(
                    "Mobility Hub analysis requires --scenario fast-track."
            );
        }
        Map<String, ConversionResult> results = new LinkedHashMap<>();
        for (Profile profile : selected) {
            ConversionResult result = convert(profile, !analyzeMobilityHubs);
            results.put(profile.key(), result);
            System.gc();
        }
        if (results.size() == 2) {
            compareScenarios(results.get("bau"), results.get("fast-track"));
        }
        printResults(results.values());
    }

    private static ConversionResult validateExistingOutput(Profile profile)
            throws Exception {
        List<FastTrackPedestrianZones.Restriction> pedestrianZones =
                pedestrianZoneRestrictions(profile);
        Path baseNetworkFile = BASE_ROAD_NETWORK;
        Scenario base = loadRoadScenario(baseNetworkFile);
        RoadReference road = RoadReference.from(
                base.getNetwork(), sha256(baseNetworkFile), baseNetworkFile
        );
        Counts baseCounts = Counts.from(base);
        base = null;
        System.gc();

        OutputFiles output = new OutputFiles(
                profile.outputDirectory().resolve("network-with-pt.xml.gz"),
                profile.outputDirectory().resolve("transitSchedule.xml.gz"),
                profile.outputDirectory().resolve("transitVehicles.xml.gz")
        );
        requireRegularFile(output.network());
        requireRegularFile(output.schedule());
        requireRegularFile(output.vehicles());
        Scenario scenario = loadOutputScenario(output);
        validateRoadComponentForProfile(
                scenario.getNetwork(), road, profile, pedestrianZones
        );
        validatePseudoLinks(scenario.getNetwork(), road);
        validateScheduleAndVehicles(scenario);
        validateScenarioContent(scenario.getTransitSchedule(), profile);
        if (profile.fastTrack()) {
            FastTrackMobilityHubs.validatePublished(scenario.getTransitSchedule());
        }
        validatePedestrianZoneActivityReferences(profile, pedestrianZones);
        return new ConversionResult(
                profile, baseNetworkFile, road.sourceFileSha256(),
                road.semanticSha256(), lineSignature(scenario.getTransitSchedule(), S8),
                lineDepartureCount(scenario.getTransitSchedule(), U4), baseCounts,
                Counts.from(scenario), output, sha256(output.network()),
                sha256(output.schedule()), sha256(output.vehicles()), true
        );
    }

    private static List<Profile> selectProfiles(String[] args) {
        if (args.length == 0 || args.length == 1 && "--all".equals(args[0])) {
            return PROFILES;
        }
        if (args.length == 2 && "--scenario".equals(args[0])) {
            return PROFILES.stream().filter(profile -> profile.key().equals(args[1]))
                    .findFirst().map(List::of).orElseThrow(() -> new IllegalArgumentException(
                            "Unknown scenario '" + args[1]
                                    + "'. Use bau, fast-track, or --all."
                    ));
        }
        throw new IllegalArgumentException(
                "Use --all, --scenario bau|fast-track, or --validate-existing."
        );
    }

    private static ConversionResult convert(Profile profile, boolean publishOutput)
            throws Exception {
        List<FastTrackPedestrianZones.Restriction> pedestrianZones =
                pedestrianZoneRestrictions(profile);
        requireRegularFile(profile.configFile());
        requireRegularFile(profile.gtfsFile());
        Path baseNetworkFile = BASE_ROAD_NETWORK;
        requireRegularFile(baseNetworkFile);

        Scenario scenario = loadRoadScenario(baseNetworkFile);
        RoadReference road = RoadReference.from(
                scenario.getNetwork(), sha256(baseNetworkFile), baseNetworkFile
        );
        Counts baseCounts = Counts.from(scenario);

        CoordinateTransformation transformation =
                TransformationFactory.getCoordinateTransformation(
                        TransformationFactory.WGS84, CRS
                );
        GtfsConverter.newBuilder()
                .setFeed(profile.gtfsFile())
                .setDate(SERVICE_DATE)
                .setTransform(transformation)
                .setScenario(scenario)
                .setUseExtendedRouteTypes(false)
                .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                .setIncludeMinimalTransferTimes(true)
                .build()
                .convert();

        validateConvertedGtfsSchedule(scenario.getTransitSchedule(), profile);
        String sourceS8Signature = lineSignature(scenario.getTransitSchedule(), S8);
        long u4Departures = lineDepartureCount(scenario.getTransitSchedule(), U4);

        new CreatePseudoNetwork(
                scenario.getTransitSchedule(), scenario.getNetwork(), "pt_"
        ).createNetwork();
        new CreateVehiclesForSchedule(
                scenario.getTransitSchedule(), scenario.getTransitVehicles()
        ).run(TransportMode.pt);

        FastTrackMobilityHubs.Application mobilityHubs = null;
        if (profile.fastTrack()) {
            if (publishOutput) {
                mobilityHubs = FastTrackMobilityHubs.applyApproved(
                        scenario.getTransitSchedule()
                );
            } else {
                FastTrackMobilityHubs.analyzeApproved(
                        scenario.getTransitSchedule(),
                        Path.of("target/mobility-hubs-analysis/"
                                + "approved_transfer_relations_preview.csv")
                );
            }
        }

        validateRoadComponent(scenario.getNetwork(), road);
        if (profile.fastTrack()) {
            FastTrackPedestrianZones.apply(scenario.getNetwork(), pedestrianZones);
        }
        validateRoadComponentForProfile(
                scenario.getNetwork(), road, profile, pedestrianZones
        );
        validatePseudoLinks(scenario.getNetwork(), road);
        validateScheduleAndVehicles(scenario);
        validateScenarioContent(scenario.getTransitSchedule(), profile);
        validatePedestrianZoneActivityReferences(profile, pedestrianZones);
        Counts generatedCounts = Counts.from(scenario);

        OutputFiles output = new OutputFiles(
                profile.outputDirectory().resolve("network-with-pt.xml.gz"),
                profile.outputDirectory().resolve("transitSchedule.xml.gz"),
                profile.outputDirectory().resolve("transitVehicles.xml.gz")
        );
        if (!publishOutput) {
            return new ConversionResult(
                    profile, baseNetworkFile, road.sourceFileSha256(),
                    road.semanticSha256(), sourceS8Signature, u4Departures,
                    baseCounts, generatedCounts, output,
                    "NOT_WRITTEN", "NOT_WRITTEN", "NOT_WRITTEN", false
            );
        }

        String existingNetworkSha256 = profile.fastTrack()
                && Files.isRegularFile(output.network())
                ? sha256(output.network()) : null;
        String existingVehiclesSha256 = profile.fastTrack()
                && Files.isRegularFile(output.vehicles())
                ? sha256(output.vehicles()) : null;

        Files.createDirectories(profile.outputDirectory());
        Path work = Files.createTempDirectory(
                profile.outputDirectory(), ".matsim-transit-build-"
        );
        try {
            OutputFiles candidate = new OutputFiles(
                    work.resolve("network-with-pt.xml.gz"),
                    work.resolve("transitSchedule.xml.gz"),
                    work.resolve("transitVehicles.xml.gz")
            );
            writeScenario(scenario, candidate);
            scenario = null;
            System.gc();

            Scenario verified = loadOutputScenario(candidate);
            validateRoadComponentForProfile(
                    verified.getNetwork(), road, profile, pedestrianZones
            );
            validatePseudoLinks(verified.getNetwork(), road);
            validateScheduleAndVehicles(verified);
            validateScenarioContent(verified.getTransitSchedule(), profile);
            if (mobilityHubs != null) {
                FastTrackMobilityHubs.validateApplied(
                        verified.getTransitSchedule(), mobilityHubs
                );
                FastTrackMobilityHubs.validatePublished(
                        verified.getTransitSchedule()
                );
            }
            Counts verifiedCounts = Counts.from(verified);
            if (!generatedCounts.equals(verifiedCounts)) {
                throw new IllegalStateException(
                        profile.label() + " reread counts differ: generated="
                                + generatedCounts + ", reread=" + verifiedCounts
                );
            }
            String rereadS8Signature = lineSignature(
                    verified.getTransitSchedule(), S8
            );
            if (!sourceS8Signature.equals(rereadS8Signature)) {
                throw new IllegalStateException(
                        profile.label() + " S8 changed during pseudonetwork creation or I/O."
                );
            }
            if (u4Departures != lineDepartureCount(verified.getTransitSchedule(), U4)) {
                throw new IllegalStateException(
                        profile.label() + " U4 departure count changed during conversion."
                );
            }
            verified = null;
            System.gc();

            String candidateNetworkSha256 = sha256(candidate.network());
            String candidateVehiclesSha256 = sha256(candidate.vehicles());
            if (existingNetworkSha256 != null
                    && !existingNetworkSha256.equals(candidateNetworkSha256)) {
                throw new IllegalStateException(
                        "Fast Track network bytes would change during the Mobility Hub build."
                );
            }
            if (existingVehiclesSha256 != null
                    && !existingVehiclesSha256.equals(candidateVehiclesSha256)) {
                throw new IllegalStateException(
                        "Fast Track vehicle bytes would change during the Mobility Hub build."
                );
            }
            publish(candidate, output);
            return new ConversionResult(
                    profile, baseNetworkFile, road.sourceFileSha256(),
                    road.semanticSha256(), sourceS8Signature, u4Departures,
                    baseCounts, verifiedCounts, output,
                    sha256(output.network()), sha256(output.schedule()),
                    sha256(output.vehicles()), true
            );
        } finally {
            deleteTemporaryTree(work);
        }
    }

    private static Path resolveConfiguredNetwork(Path configFile) {
        Config declared = ConfigUtils.loadConfig(configFile.toString());
        String input = declared.network().getInputFile();
        if (input == null || input.isBlank()) {
            throw new IllegalStateException(
                    "Config does not declare network.inputNetworkFile: " + configFile
            );
        }
        Path configured = Path.of(input);
        if (!configured.isAbsolute()) {
            configured = configFile.toAbsolutePath().normalize().getParent()
                    .resolve(configured).normalize();
        }
        return configured;
    }

    private static Scenario loadRoadScenario(Path networkFile) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem(CRS);
        config.network().setInputFile(networkFile.toString());
        return ScenarioUtils.loadScenario(config);
    }

    private static void validateConvertedGtfsSchedule(
            TransitSchedule schedule,
            Profile profile
    ) {
        long lines = schedule.getTransitLines().size();
        long departures = departureCount(schedule);
        long transfers = transferCount(schedule, true);
        if (schedule.getFacilities().size() != profile.expectedGtfsStops()
                || lines != profile.expectedLines()
                || departures != profile.expectedDepartures()
                || transfers != profile.expectedTransfers()) {
            throw new IllegalStateException(
                    profile.label() + " GTFS conversion counts differ from the audited feed: "
                            + "stops=" + schedule.getFacilities().size()
                            + ", lines=" + lines + ", departures=" + departures
                            + ", transfers=" + transfers
            );
        }
        validateScenarioContent(schedule, profile);
    }

    private static void validateScenarioContent(
            TransitSchedule schedule,
        Profile profile
    ) {
        for (String lineId : FAST_TRACK_LINES) {
            boolean present = findLineByGtfsRouteId(schedule, lineId, false) != null;
            if (present != profile.fastTrack()) {
                throw new IllegalStateException(
                        profile.label() + " has unexpected Fast Track line state for " + lineId
                );
            }
        }
        requireLine(schedule, S8);
        requireLine(schedule, U4);
        validateCommonMeasures(schedule);
        if (profile.fastTrack()) {
            requireLineDepartures(schedule, "FT_U9", 520);
            requireLineDepartures(schedule, "FT_NR_A", 80);
            requireLineDepartures(schedule, "FT_NR_B", 80);
            requireLineStop(schedule, U4, "FT_U4_COSIMAPARK_D0");
            requireLineStop(schedule, U4, "FT_U4_COSIMAPARK_D1");
            requireLineStop(schedule, U4, "FT_U4_ENGLSCHALKING_D0");
            requireLineStop(schedule, U4, "FT_U4_ENGLSCHALKING_D1");
            validateImplerPocciTransfers(schedule);
        } else {
            for (String stop : List.of(
                    "FT_U4_COSIMAPARK_D0", "FT_U4_COSIMAPARK_D1",
                    "FT_U4_ENGLSCHALKING_D0", "FT_U4_ENGLSCHALKING_D1"
            )) {
                if (containsNormalizedLineStop(schedule, U4, stop)) {
                    throw new IllegalStateException(
                            "BAU unexpectedly contains Fast Track U4 stop " + stop
                    );
                }
            }
        }
    }

    private static void validateCommonMeasures(TransitSchedule schedule) {
        long pocciDepartures = departureCountServingAny(schedule, COMMON_POCCI_RAIL);
        long berduxDepartures = departureCountServingAny(schedule, COMMON_BERDUX);
        if (pocciDepartures != 234 || berduxDepartures != 203) {
            throw new IllegalStateException("Common-stop service counts differ: Poccistraße="
                    + pocciDepartures + ", Berduxstraße=" + berduxDepartures);
        }
        for (TransitLine line : schedule.getTransitLines().values()) {
            String source = line.getName() == null ? line.getId().toString() : line.getName();
            if (source.matches(".*S\\d+X.*") && line.getRoutes().values().stream()
                    .anyMatch(route -> route.getStops().stream().anyMatch(stop ->
                            COMMON_BERDUX.stream().anyMatch(id ->
                                    stop.getStopFacility().getId().toString().equals(id)
                                            || stop.getStopFacility().getId().toString().startsWith(id + "."))))) {
                throw new IllegalStateException("Express S-Bahn serves Berduxstraße: " + source);
            }
        }
        for (String rail : COMMON_POCCI_RAIL) for (String subway : COMMON_POCCI_SUBWAY) {
            requireTransfer(schedule, rail, subway, 180);
            requireTransfer(schedule, subway, rail, 180);
        }
    }

    private static long departureCountServingAny(TransitSchedule schedule, List<String> stopIds) {
        return schedule.getTransitLines().values().stream()
                .flatMap(line -> line.getRoutes().values().stream())
                .filter(route -> route.getStops().stream().anyMatch(stop ->
                        stopIds.stream().anyMatch(id -> stop.getStopFacility().getId().toString().equals(id)
                                || stop.getStopFacility().getId().toString().startsWith(id + "."))))
                .mapToLong(route -> route.getDepartures().size()).sum();
    }

    private static void validateImplerPocciTransfers(TransitSchedule schedule) {
        int verified = 0;
        for (String planned : IMPLER_POCCI_PLATFORMS) {
            for (String existing : EXISTING_INTERCHANGE_PLATFORMS) {
                verified += requireTransfer(schedule, planned, existing, 300);
                verified += requireTransfer(schedule, existing, planned, 300);
            }
        }
        if (verified != 28) {
            throw new IllegalStateException(
                    "Expected 28 Impler-/Poccistraße transfer relations, found " + verified
            );
        }
    }

    private static int requireTransfer(
            TransitSchedule schedule,
            String from,
            String to,
            int seconds
    ) {
        Id<TransitStopFacility> fromId = Id.create(from, TransitStopFacility.class);
        Id<TransitStopFacility> toId = Id.create(to, TransitStopFacility.class);
        if (!schedule.getFacilities().containsKey(fromId)
                || !schedule.getFacilities().containsKey(toId)) {
            throw new IllegalStateException(
                    "Transfer endpoint is missing: " + from + " -> " + to
            );
        }
        double actual = schedule.getMinimalTransferTimes().get(fromId, toId);
        if (Double.compare(actual, seconds) != 0) {
            throw new IllegalStateException(
                    "Transfer time differs for " + from + " -> " + to
                            + ": expected " + seconds + ", got " + actual
            );
        }
        return 1;
    }

    private static void requireLine(TransitSchedule schedule, String lineId) {
        findLineByGtfsRouteId(schedule, lineId, true);
    }

    private static void requireLineDepartures(
            TransitSchedule schedule,
            String lineId,
            long expected
    ) {
        long actual = lineDepartureCount(schedule, lineId);
        if (actual != expected) {
            throw new IllegalStateException(
                    lineId + " has " + actual + " departures; expected " + expected
            );
        }
    }

    private static void requireLineStop(
            TransitSchedule schedule,
            String lineId,
            String stopId
    ) {
        if (!containsNormalizedLineStop(schedule, lineId, stopId)) {
            throw new IllegalStateException(
                    "Line " + lineId + " does not serve required stop " + stopId
            );
        }
    }

    private static boolean containsNormalizedLineStop(
            TransitSchedule schedule,
            String lineId,
            String stopId
    ) {
        TransitLine line = findLineByGtfsRouteId(schedule, lineId, false);
        if (line == null) {
            return false;
        }
        return line.getRoutes().values().stream()
                .flatMap(route -> route.getStops().stream())
                .map(TransitRouteStop::getStopFacility)
                .map(facility -> normalizedFacilityId(schedule, facility))
                .anyMatch(stopId::equals);
    }

    private static void validateRoadComponent(Network network, RoadReference road) {
        String digest = networkDigest(
                network, road.nodeIds(), road.linkIds(), true, Set.of()
        );
        if (!road.semanticSha256().equals(digest)) {
            throw new IllegalStateException(
                    "Base road-network properties changed while adding the PT pseudonetwork."
            );
        }
    }

    private static void validateRoadComponentForProfile(
            Network network,
            RoadReference road,
            Profile profile,
            List<FastTrackPedestrianZones.Restriction> pedestrianZones
    ) {
        Set<String> restoreCarForDigest = profile.fastTrack()
                ? FastTrackPedestrianZones.linkIds(pedestrianZones)
                : Set.of();
        String digest = networkDigest(
                network, road.nodeIds(), road.linkIds(), true, restoreCarForDigest
        );
        if (!road.semanticSha256().equals(digest)) {
            throw new IllegalStateException(
                    profile.label() + " road component differs beyond the approved "
                            + "Fast Track pedestrian-zone car-mode removals."
            );
        }
        if (profile.fastTrack()) {
            FastTrackPedestrianZones.validateApplied(network, pedestrianZones);
            FastTrackPedestrianZones.validatePerimeterCarConnectivity(network);
            FastTrackPedestrianZones.validateCarNetworkConnected(network);
        }
    }

    private static List<FastTrackPedestrianZones.Restriction>
            pedestrianZoneRestrictions(Profile profile) throws Exception {
        return profile.fastTrack()
                ? FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                )
                : List.of();
    }

    private static void validatePedestrianZoneActivityReferences(
            Profile profile,
            List<FastTrackPedestrianZones.Restriction> pedestrianZones
    ) throws Exception {
        if (!profile.fastTrack()) {
            return;
        }
        long references = FastTrackPedestrianZones.countActivityLinkReferences(
                FastTrackPedestrianZones.FAST_TRACK_POPULATION,
                FastTrackPedestrianZones.linkIds(pedestrianZones)
        );
        if (references != 0) {
            throw new IllegalStateException(
                    references + " Fast Track activities explicitly reference pedestrian-zone "
                            + "links. Resolve those link assignments explicitly; coordinate "
                            + "proximity alone must not change activities."
            );
        }
    }

    private static void validatePseudoLinks(Network network, RoadReference road) {
        long pseudoLinks = 0;
        for (Link link : network.getLinks().values()) {
            if (road.linkIds().contains(link.getId().toString())) {
                continue;
            }
            pseudoLinks++;
            if (link.getAllowedModes().contains(TransportMode.car)
                    || !link.getAllowedModes().equals(Set.of(TransportMode.pt))) {
                throw new IllegalStateException(
                        "PT pseudolink has unsafe modes " + link.getAllowedModes()
                                + ": " + link.getId()
                );
            }
        }
        if (pseudoLinks == 0) {
            throw new IllegalStateException("No PT pseudolinks were created.");
        }
    }

    private static void validateScheduleAndVehicles(Scenario scenario) {
        Network network = scenario.getNetwork();
        TransitSchedule schedule = scenario.getTransitSchedule();
        Set<Id<Vehicle>> referencedVehicles = new HashSet<>();
        for (TransitStopFacility facility : schedule.getFacilities().values()) {
            if (facility.getLinkId() != null
                    && !network.getLinks().containsKey(facility.getLinkId())) {
                throw new IllegalStateException(
                        "Transit stop references a missing network link: " + facility.getId()
                );
            }
        }
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (TransitRouteStop stop : route.getStops()) {
                    TransitStopFacility facility = stop.getStopFacility();
                    if (facility.getLinkId() == null
                            || !network.getLinks().containsKey(facility.getLinkId())) {
                        throw new IllegalStateException(
                                "Served transit stop has no valid network link: "
                                        + facility.getId()
                        );
                    }
                }
                validateNetworkRoute(network, line, route);
                for (Departure departure : route.getDepartures().values()) {
                    Id<Vehicle> vehicleId = departure.getVehicleId();
                    if (vehicleId == null
                            || !scenario.getTransitVehicles().getVehicles()
                                    .containsKey(vehicleId)) {
                        throw new IllegalStateException(
                                "Departure has no valid vehicle: " + line.getId() + " / "
                                        + route.getId() + " / " + departure.getId()
                        );
                    }
                    referencedVehicles.add(vehicleId);
                }
            }
        }
        if (referencedVehicles.size()
                != scenario.getTransitVehicles().getVehicles().size()) {
            throw new IllegalStateException(
                    "Transit vehicle file contains missing or unreferenced vehicles."
            );
        }
        for (Vehicle vehicle : scenario.getTransitVehicles().getVehicles().values()) {
            if (!scenario.getTransitVehicles().getVehicleTypes()
                    .containsKey(vehicle.getType().getId())) {
                throw new IllegalStateException(
                        "Vehicle references a missing type: " + vehicle.getId()
                );
            }
            if (!TransportMode.pt.equals(vehicle.getType().getNetworkMode())) {
                throw new IllegalStateException(
                        "Transit vehicle type does not use network mode pt: "
                                + vehicle.getType().getId()
                );
            }
        }
        transferCount(schedule, true);
    }

    private static void validateNetworkRoute(
            Network network,
            TransitLine line,
            TransitRoute transitRoute
    ) {
        NetworkRoute route = transitRoute.getRoute();
        if (route == null) {
            throw new IllegalStateException(
                    "Transit route has no network route: " + line.getId() + " / "
                            + transitRoute.getId()
            );
        }
        List<Id<Link>> ids = new ArrayList<>();
        ids.add(route.getStartLinkId());
        ids.addAll(route.getLinkIds());
        ids.add(route.getEndLinkId());
        for (Id<Link> id : ids) {
            Link link = network.getLinks().get(id);
            if (link == null) {
                throw new IllegalStateException(
                        "Transit network route references missing link " + id
                );
            }
            if (link.getAllowedModes().contains(TransportMode.car)
                    || !link.getAllowedModes().contains(TransportMode.pt)) {
                throw new IllegalStateException(
                        "Transit route uses a non-PT or car-enabled link " + id
                );
            }
        }
    }

    private static long transferCount(TransitSchedule schedule, boolean validate) {
        long count = 0;
        var iterator = schedule.getMinimalTransferTimes().iterator();
        while (iterator.hasNext()) {
            iterator.next();
            count++;
            if (validate) {
                if (!schedule.getFacilities().containsKey(iterator.getFromStopId())
                        || !schedule.getFacilities().containsKey(iterator.getToStopId())
                        || !Double.isFinite(iterator.getSeconds())
                        || iterator.getSeconds() < 0) {
                    throw new IllegalStateException(
                            "Invalid minimal transfer relation: "
                                    + iterator.getFromStopId() + " -> "
                                    + iterator.getToStopId()
                    );
                }
            }
        }
        return count;
    }

    private static void writeScenario(Scenario scenario, OutputFiles files) {
        new NetworkWriter(scenario.getNetwork()).write(files.network().toString());
        new TransitScheduleWriter(scenario.getTransitSchedule())
                .writeFile(files.schedule().toString());
        new MatsimVehicleWriter(scenario.getTransitVehicles())
                .writeFile(files.vehicles().toString());
    }

    private static Scenario loadOutputScenario(OutputFiles files) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem(CRS);
        config.network().setInputFile(files.network().toString());
        config.transit().setUseTransit(true);
        config.transit().setTransitScheduleFile(files.schedule().toString());
        config.transit().setVehiclesFile(files.vehicles().toString());
        return ScenarioUtils.loadScenario(config);
    }

    private static void publish(OutputFiles candidate, OutputFiles output)
            throws IOException {
        move(candidate.network(), output.network());
        move(candidate.schedule(), output.schedule());
        move(candidate.vehicles(), output.vehicles());
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(
                source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
        );
    }

    private static void compareScenarios(
            ConversionResult bau,
            ConversionResult fastTrack
    ) {
        if (!bau.baseNetworkFileSha256().equals(fastTrack.baseNetworkFileSha256())
                || !bau.roadSemanticSha256().equals(fastTrack.roadSemanticSha256())) {
            throw new IllegalStateException(
                    "BAU and Fast Track do not use identical base road networks."
            );
        }
        if (!bau.s8Signature().equals(fastTrack.s8Signature())) {
            throw new IllegalStateException(
                    "The S8 service differs between BAU and Fast Track."
            );
        }
        if (bau.u4Departures() != fastTrack.u4Departures()) {
            throw new IllegalStateException(
                    "The U4 extension changed the number of U4 departures."
            );
        }
    }

    private static String lineSignature(TransitSchedule schedule, String lineId) {
        TransitLine line = findLineByGtfsRouteId(schedule, lineId, true);
        MessageDigest digest = sha256Digest();
        put(digest, line.getId().toString());
        putAttributes(digest, line.getAttributes());
        line.getRoutes().values().stream()
                .sorted(Comparator.comparing(route -> route.getId().toString()))
                .forEach(route -> {
                    put(digest, route.getId().toString());
                    put(digest, route.getTransportMode());
                    putAttributes(digest, route.getAttributes());
                    for (TransitRouteStop stop : route.getStops()) {
                        put(digest, normalizedFacilityId(
                                schedule, stop.getStopFacility()
                        ));
                        put(digest, stop.getArrivalOffset().toString());
                        put(digest, stop.getDepartureOffset().toString());
                        put(digest, Boolean.toString(stop.isAllowBoarding()));
                        put(digest, Boolean.toString(stop.isAllowAlighting()));
                        put(digest, Boolean.toString(stop.isAwaitDepartureTime()));
                    }
                    route.getDepartures().values().stream()
                            .sorted(Comparator.comparing(
                                    departure -> departure.getId().toString()
                            ))
                            .forEach(departure -> {
                                put(digest, departure.getId().toString());
                                put(digest, Double.toHexString(
                                        departure.getDepartureTime()
                                ));
                            });
                });
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static String normalizedFacilityId(
            TransitSchedule schedule,
            TransitStopFacility facility
    ) {
        String id = facility.getId().toString();
        int separator = id.lastIndexOf('.');
        if (separator > 0 && id.substring(separator + 1).chars().allMatch(Character::isDigit)) {
            String candidate = id.substring(0, separator);
            if (schedule.getFacilities().containsKey(
                    Id.create(candidate, TransitStopFacility.class)
            )) {
                return candidate;
            }
        }
        return id;
    }

    private static long lineDepartureCount(TransitSchedule schedule, String lineId) {
        TransitLine line = findLineByGtfsRouteId(schedule, lineId, false);
        if (line == null) {
            return 0;
        }
        return line.getRoutes().values().stream()
                .mapToLong(route -> route.getDepartures().size()).sum();
    }

    /**
     * MATSim's GTFS converter creates a readable MATSim line identifier instead
     * of necessarily preserving {@code route_id} verbatim. Resolve the source
     * route deterministically, while rejecting ambiguous matches.
     */
    private static TransitLine findLineByGtfsRouteId(
            TransitSchedule schedule,
            String gtfsRouteId,
            boolean required
    ) {
        List<TransitLine> matches = schedule.getTransitLines().values().stream()
                .filter(line -> {
                    String id = line.getId().toString();
                    Object shortName = line.getAttributes().getAttribute(
                            "gtfs_route_short_name"
                    );
                    return id.equals(gtfsRouteId)
                            || id.endsWith(gtfsRouteId)
                            || gtfsRouteId.equals(line.getName())
                            || gtfsRouteId.equals(shortName);
                })
                .sorted(Comparator.comparing(line -> line.getId().toString()))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "GTFS route_id " + gtfsRouteId
                            + " maps ambiguously to MATSim lines "
                            + matches.stream().map(line -> line.getId().toString()).toList()
            );
        }
        if (matches.isEmpty()) {
            if (required) {
                throw new IllegalStateException(
                        "Required GTFS route is missing: " + gtfsRouteId
                );
            }
            return null;
        }
        return matches.getFirst();
    }

    private static long departureCount(TransitSchedule schedule) {
        return schedule.getTransitLines().values().stream()
                .flatMap(line -> line.getRoutes().values().stream())
                .mapToLong(route -> route.getDepartures().size()).sum();
    }

    private static long routeCount(TransitSchedule schedule) {
        return schedule.getTransitLines().values().stream()
                .mapToLong(line -> line.getRoutes().size()).sum();
    }

    private static String networkDigest(
            Network network,
            Set<String> nodeIds,
            Set<String> linkIds,
            boolean requireAll,
            Set<String> restoreCarModeForLinks
    ) {
        MessageDigest digest = sha256Digest();
        put(digest, network.getName());
        put(digest, Double.toHexString(network.getCapacityPeriod()));
        put(digest, Double.toHexString(network.getEffectiveCellSize()));
        put(digest, Double.toHexString(network.getEffectiveLaneWidth()));
        putAttributes(digest, network.getAttributes());

        nodeIds.stream().sorted().forEach(id -> {
            Node node = network.getNodes().get(Id.createNodeId(id));
            if (node == null) {
                if (requireAll) {
                    throw new IllegalStateException("Base road node is missing: " + id);
                }
                return;
            }
            put(digest, id);
            put(digest, Double.toHexString(node.getCoord().getX()));
            put(digest, Double.toHexString(node.getCoord().getY()));
            putAttributes(digest, node.getAttributes());
        });
        linkIds.stream().sorted().forEach(id -> {
            Link link = network.getLinks().get(Id.createLinkId(id));
            if (link == null) {
                if (requireAll) {
                    throw new IllegalStateException("Base road link is missing: " + id);
                }
                return;
            }
            put(digest, id);
            put(digest, link.getFromNode().getId().toString());
            put(digest, link.getToNode().getId().toString());
            put(digest, Double.toHexString(link.getLength()));
            put(digest, Double.toHexString(link.getFreespeed()));
            put(digest, Double.toHexString(link.getCapacity()));
            put(digest, Double.toHexString(link.getNumberOfLanes()));
            Set<String> normalizedModes = new TreeSet<>(link.getAllowedModes());
            if (restoreCarModeForLinks.contains(id)) {
                normalizedModes.add(TransportMode.car);
            }
            normalizedModes.forEach(mode -> put(digest, mode));
            putAttributes(digest, link.getAttributes());
        });
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void putAttributes(MessageDigest digest, Attributes attributes) {
        attributes.getAsMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    put(digest, entry.getKey());
                    Object value = entry.getValue();
                    put(digest, value == null ? "null" : value.getClass().getName());
                    put(digest, String.valueOf(value));
                });
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is not available.", error);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = sha256Digest();
        try (DigestInputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(file)), digest
        )) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void requireRegularFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Required input is missing: " + file);
        }
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

    private static void printResults(Iterable<ConversionResult> results) {
        System.out.println("MATSim GTFS-2037 scenario conversion completed.");
        System.out.println("Scenario | nodes | links | PT stops | lines | routes | "
                + "departures | vehicles | transfers");
        for (ConversionResult result : results) {
            Counts counts = result.counts();
            System.out.printf(
                    Locale.ROOT,
                    "%s | %d | %d | %d | %d | %d | %d | %d | %d%n",
                    result.profile().label(), counts.networkNodes(), counts.networkLinks(),
                    counts.transitStops(), counts.transitLines(), counts.transitRoutes(),
                    counts.departures(), counts.transitVehicles(), counts.transfers()
            );
            System.out.println("  Base network: " + result.baseNetworkFile());
            System.out.println("  Network: " + result.output().network());
            System.out.println("  Schedule: " + result.output().schedule());
            System.out.println("  Vehicles: " + result.output().vehicles());
            System.out.println("  Published: " + result.published());
            System.out.println("  Base road-network file SHA-256: "
                    + result.baseNetworkFileSha256());
            System.out.println("  Road semantic SHA-256: " + result.roadSemanticSha256());
            System.out.println("  Network SHA-256: " + result.networkSha256());
            System.out.println("  Schedule SHA-256: " + result.scheduleSha256());
            System.out.println("  Vehicles SHA-256: " + result.vehiclesSha256());
        }
        System.out.println("Validation: PASS");
    }

    private record Profile(
            String label,
            String key,
            Path configFile,
            Path gtfsFile,
            Path outputDirectory,
            long expectedGtfsStops,
            long expectedLines,
            long expectedDepartures,
            long expectedTransfers,
            boolean fastTrack
    ) {
    }

    private record OutputFiles(Path network, Path schedule, Path vehicles) {
    }

    private record RoadReference(
            Set<String> nodeIds,
            Set<String> linkIds,
            String sourceFileSha256,
            String semanticSha256,
            Path sourceFile
    ) {
        static RoadReference from(
                Network network,
                String sourceFileSha256,
                Path sourceFile
        ) {
            Set<String> nodes = new TreeSet<>();
            network.getNodes().keySet().forEach(id -> nodes.add(id.toString()));
            Set<String> links = new TreeSet<>();
            network.getLinks().keySet().forEach(id -> links.add(id.toString()));
            return new RoadReference(
                    Set.copyOf(nodes), Set.copyOf(links), sourceFileSha256,
                    networkDigest(network, nodes, links, true, Set.of()), sourceFile
            );
        }
    }

    private record Counts(
            long networkNodes,
            long networkLinks,
            long transitStops,
            long transitLines,
            long transitRoutes,
            long departures,
            long transitVehicles,
            long transfers
    ) {
        static Counts from(Scenario scenario) {
            TransitSchedule schedule = scenario.getTransitSchedule();
            return new Counts(
                    scenario.getNetwork().getNodes().size(),
                    scenario.getNetwork().getLinks().size(),
                    schedule.getFacilities().size(),
                    schedule.getTransitLines().size(),
                    routeCount(schedule), departureCount(schedule),
                    scenario.getTransitVehicles().getVehicles().size(),
                    transferCount(schedule, true)
            );
        }
    }

    private record ConversionResult(
            Profile profile,
            Path baseNetworkFile,
            String baseNetworkFileSha256,
            String roadSemanticSha256,
            String s8Signature,
            long u4Departures,
            Counts baseCounts,
            Counts counts,
            OutputFiles output,
            String networkSha256,
            String scheduleSha256,
            String vehiclesSha256,
            boolean published
    ) {
    }
}
