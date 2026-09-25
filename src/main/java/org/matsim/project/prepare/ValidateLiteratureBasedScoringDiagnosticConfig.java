package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.pt.config.TransitConfigGroup;

/** Read-only, fail-closed validation of the literature-based scoring diagnostic. */
public final class ValidateLiteratureBasedScoringDiagnosticConfig {
    public static final Path CONFIG = Path.of("scenarios/munich_calibration_2019/"
            + "config_literature_based_scoring_diagnostic.xml");
    public static final Path INPUT_VALIDATION_CONFIG = Path.of(
            "scenarios/munich_calibration_2019/config_input_validation.xml");
    public static final Path OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "literature-based-scoring-diagnostic");
    public static final String RUN_ID =
            "munich-calibration-2019-literature-based-scoring-diagnostic";
    public static final List<String> CHOICE_MODES = List.of("car", "pt", "walk", "bike");
    public static final Set<String> CHAIN_BASED_MODES = Set.of("car", "bike");
    public static final Map<String, Double> STRATEGY_WEIGHTS = Map.of(
            "ChangeExpBeta", 0.8,
            "ReRoute", 0.1,
            "SubtourModeChoice", 0.1);
    public static final Map<String, String> PROTECTED_INPUT_SHA256 = Map.of(
            "../munich_base_2023/munich-v1.0-5pct.plans.xml",
            "DCC920DBD6158D898C7C774F77A5E9BE35DD7E7135150EE66FAA1FC9D34139CB",
            "input_transit/network-with-pt.xml.gz",
            "68FD7ABA2AD7DC459AC4B21672C426E0E4560994449BFE968278F0804CD6D86F",
            "input_transit/transitSchedule.xml.gz",
            "5F247C33D43C31A24CE8ABC8DCB1731A30E58257CA37BC16AE5AA1C1269CF55E",
            "input_transit/transitVehicles.xml.gz",
            "EA3EC382AB1A5A003B9F5C17EA1846BB4F4ECD89BD3BFC360D2C9ED321B16EE1");
    private static final double EPSILON = 1e-12;

    private ValidateLiteratureBasedScoringDiagnosticConfig() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The read-only validator accepts no arguments");
        Config config = loadAndValidate();
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING DIAGNOSTIC CONFIG VALIDATION PASS%n"
                        + "config=%s%noutput=%s%niterations=%d..%d qsimEnd=48:00:00%n"
                        + "choiceModes=%s chainBasedModes=%s strategies=%s%n"
                        + "ASCs=0/0/0/0 walkReference=0 carCost=0.20_EUR_per_km%n"
                        + "walkSpeed=1.333333333_m_per_s bikeSpeed=3.805555556_m_per_s%n"
                        + "Protected inputs are byte-identical. No Controller or QSim was started.%n",
                CONFIG, OUTPUT, config.controller().getFirstIteration(),
                config.controller().getLastIteration(), CHOICE_MODES,
                CHAIN_BASED_MODES, strategyMap(config));
    }

    public static Config loadAndValidate() throws Exception {
        return loadAndValidate(true);
    }

    /**
     * Validates the versioned diagnostic configuration. The analyzer uses the
     * {@code false} variant after a protected server run already exists; every
     * content and workspace check remains active.
     */
    static Config loadAndValidate(boolean requireOutputAbsent) throws Exception {
        require(Files.isRegularFile(CONFIG), "Missing diagnostic config: " + CONFIG);
        require(Files.isRegularFile(INPUT_VALIDATION_CONFIG),
                "Missing validated synthetic-2019 input config: " + INPUT_VALIDATION_CONFIG);
        String xml = Files.readString(CONFIG);
        require(moduleCount(xml, "replanning") == 1,
                "Exactly one replanning module is required");
        require(moduleCount(xml, "strategy") == 0,
                "Legacy strategy modules are forbidden");
        require(!Pattern.compile("<(?:param|module)\\b[^>]*\\bname\\s*=\\s*"
                        + "[\\\"'][^\\\"']*target[^\\\"']*[\\\"']",
                Pattern.CASE_INSENSITIVE).matcher(xml).find(),
                "Calibration targets must not be embedded in the diagnostic config");

        Config config = ConfigUtils.loadConfig(CONFIG.toString());
        Config technicalBasis = ConfigUtils.loadConfig(INPUT_VALIDATION_CONFIG.toString());
        validateInputs(config, technicalBasis, xml);
        validateTransit(config);
        validateStrategies(config);
        validateModeChoice(config);
        validateScoring(config, technicalBasis);
        validateRouting(config);
        validateRunControl(config, requireOutputAbsent);
        validateProtectedWorkspace();
        return config;
    }

    static void validateInputs(Config config, Config basis, String xml) throws Exception {
        require("EPSG:31468".equals(config.global().getCoordinateSystem()),
                "Coordinate system must remain EPSG:31468");
        require(same(config.network().getInputFile(), basis.network().getInputFile()),
                "Network path differs from the validated 2019 basis");
        require(same(config.plans().getInputFile(), basis.plans().getInputFile()),
                "Population path differs from the validated 2019 basis");
        require(same(config.transit().getTransitScheduleFile(),
                        basis.transit().getTransitScheduleFile()),
                "Transit schedule path differs from the validated 2019 basis");
        require(same(config.transit().getVehiclesFile(), basis.transit().getVehiclesFile()),
                "Transit vehicle path differs from the validated 2019 basis");
        String lower = xml.toLowerCase(Locale.ROOT);
        require(!lower.contains("munich_bau_2040")
                        && !lower.contains("munich_fast_track_2040"),
                "The diagnostic must not reference BAU or Fast Track");
        for (var protectedInput : PROTECTED_INPUT_SHA256.entrySet()) {
            Path file = CONFIG.getParent().resolve(protectedInput.getKey()).normalize();
            require(Files.isRegularFile(file), "Missing protected input: " + file);
            require(protectedInput.getValue().equals(sha256(file)),
                    "Protected input hash changed: " + file);
        }
    }

    static void validateTransit(Config config) {
        require(config.transit().isUseTransit() && config.transit().isUsingTransitInMobsim(),
                "Public transport must remain simulated");
        require(config.transit().getRoutingAlgorithmType()
                        == TransitConfigGroup.TransitRoutingAlgorithmType.SwissRailRaptor,
                "SwissRailRaptor must remain configured");
    }

    static void validateStrategies(Config config) {
        require(config.replanning().getMaxAgentPlanMemorySize() == 4,
                "maxAgentPlanMemorySize must remain 4");
        require("WorstPlanSelector".equals(config.replanning().getPlanSelectorForRemoval()),
                "Unexpected plan-removal selector");
        require(close(config.replanning().getFractionOfIterationsToDisableInnovation(), 0.8),
                "Innovation-disable fraction must remain 0.8");
        require(config.replanning().getStrategySettings().size() == 3,
                "Exactly three strategies are required");
        Map<String, Double> actual = strategyMap(config);
        require(actual.equals(STRATEGY_WEIGHTS),
                "Unexpected strategy names or weights: " + actual);
        require(close(config.scoring().getBrainExpBeta(), 1.0),
                "BrainExpBeta must be 1.0");
    }

    static void validateModeChoice(Config config) {
        require(Arrays.asList(config.subtourModeChoice().getModes()).equals(CHOICE_MODES),
                "Choice modes must be exactly car,pt,walk,bike in that order");
        require(Set.of(config.subtourModeChoice().getChainBasedModes())
                        .equals(CHAIN_BASED_MODES),
                "Chain-based modes must be exactly car and bike");
        require(!Arrays.asList(config.subtourModeChoice().getModes()).contains("ride")
                        && !Arrays.asList(config.subtourModeChoice().getModes()).contains("other"),
                "ride and other must not be choice alternatives");
        require(!config.subtourModeChoice().considerCarAvailability(),
                "considerCarAvailability must remain false; no attributes may be invented");
        require("fromSpecifiedModesToSpecifiedModes".equals(
                        config.subtourModeChoice().getBehavior().toString()),
                "Unexpected SubtourModeChoice behavior");
    }

    static void validateScoring(Config config, Config basis) {
        require(close(config.scoring().getPerforming_utils_hr(), 6.0),
                "marginalUtilityOfPerforming must be 6.0 utils/hour");
        require(close(config.scoring().getMarginalUtilityOfMoney(), 1.0),
                "marginalUtilityOfMoney must be 1.0");
        require(close(config.scoring().getMarginalUtlOfWaitingPt_utils_hr(), -6.0),
                "PT waiting utility must be -6.0 utils/hour");
        require(close(config.scoring().getUtilityOfLineSwitch(), -1.0),
                "utilityOfLineSwitch must be -1.0");
        require(close(config.scoring().getEarlyDeparture_utils_hr(),
                        basis.scoring().getEarlyDeparture_utils_hr())
                        && close(config.scoring().getLateArrival_utils_hr(),
                        basis.scoring().getLateArrival_utils_hr())
                        && close(config.scoring().getMarginalUtlOfWaiting_utils_hr(),
                        basis.scoring().getMarginalUtlOfWaiting_utils_hr()),
                "Inherited early-departure, late-arrival or general waiting utility changed");

        for (String activityType : basis.scoring().getActivityTypes()) {
            var expected = basis.scoring().getActivityParams(activityType);
            var actual = config.scoring().getActivityParams(activityType);
            require(actual != null, "Missing inherited activity scoring for " + activityType);
            require(actual.isScoringThisActivityAtAll()
                            == expected.isScoringThisActivityAtAll(),
                    "Activity scoring flag changed for " + activityType);
            require(optionalSeconds(actual.getTypicalDuration())
                            == optionalSeconds(expected.getTypicalDuration()),
                    "Typical duration changed for " + activityType);
        }

        for (String mode : CHOICE_MODES) {
            var params = config.scoring().getModes().get(mode);
            require(params != null, "Missing scoring parameters for " + mode);
            require(close(params.getConstant(), 0.0),
                    mode + " ASC must start at zero");
            require(close(params.getMarginalUtilityOfTraveling(), 0.0),
                    mode + " direct travel-time utility must be zero");
            require(close(params.getMarginalUtilityOfDistance(), 0.0),
                    mode + " direct distance utility must be zero");
            require(close(params.getDailyUtilityConstant(), 0.0)
                            && close(params.getDailyMonetaryConstant(), 0.0),
                    mode + " daily constants must be zero");
            double expectedRate = "car".equals(mode) ? -0.00020 : 0.0;
            require(close(params.getMonetaryDistanceRate(), expectedRate),
                    mode + " monetary distance rate is incorrect");
        }
        require(config.scoring().getModes().get("walk").getConstant() == 0.0,
                "Walk is the permanent reference and its ASC must be exactly zero");
    }

    static void validateRouting(Config config) {
        var walk = config.routing().getTeleportedModeParams().get("walk");
        var bike = config.routing().getTeleportedModeParams().get("bike");
        var nonNetworkWalk = config.routing().getTeleportedModeParams()
                .get("non_network_walk");
        require(walk != null && bike != null && nonNetworkWalk != null,
                "Missing walk, non-network-walk or bike routing parameters");
        require(close(walk.getTeleportedModeSpeed(), 1.333333333),
                "Walk speed must be 1.333333333 m/s");
        require(close(bike.getTeleportedModeSpeed(), 3.805555556),
                "Bike speed must be 3.805555556 m/s");
        require(close(nonNetworkWalk.getTeleportedModeSpeed(), 1.333333333),
                "PT access/egress walk speed must match the walk speed");
        require(close(walk.getBeelineDistanceFactor(), 1.3)
                        && close(nonNetworkWalk.getBeelineDistanceFactor(), 1.3)
                        && close(bike.getBeelineDistanceFactor(), 1.3),
                "Walk, non-network-walk and bike beeline factors must be 1.3");
        require(Set.copyOf(config.routing().getNetworkModes()).equals(Set.of("car")),
                "Only car may remain a routed network mode");
    }

    static void validateRunControl(Config config, boolean requireOutputAbsent)
            throws IOException {
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == 10,
                "The short diagnostic must run iterations 0..10");
        require(config.global().getRandomSeed() == 4711,
                "Random seed must remain 4711");
        require(close(config.qsim().getFlowCapFactor(), 0.05)
                        && close(config.qsim().getStorageCapFactor(), 0.05),
                "Both capacity factors must remain 0.05");
        require(config.qsim().getEndTime().isDefined()
                        && close(config.qsim().getEndTime().seconds(), 48 * 3600.0),
                "QSim end time must be 48:00:00");
        require(RUN_ID.equals(config.controller().getRunId()),
                "Unexpected diagnostic run ID");
        require(normalize(OUTPUT).equals(normalize(
                        Path.of(config.controller().getOutputDirectory()))),
                "Unexpected diagnostic output directory");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Output must use failIfDirectoryExists");
        if (requireOutputAbsent) requireOutputAbsent(OUTPUT);
    }

    static void validateProtectedWorkspace() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "status", "--porcelain=v1",
                "--untracked-files=all", "--", "scenarios/munich_bau_2040",
                "scenarios/munich_fast_track_2040").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        require(exitCode == 0, "Git could not verify BAU/Fast Track protection");
        require(output.isBlank(), "BAU or Fast Track has local changes: " + output.trim());
    }

    static void requireOutputAbsent(Path output) {
        require(!Files.exists(output),
                "Protected diagnostic output already exists: " + output);
    }

    static Map<String, Double> strategyMap(Config config) {
        return config.replanning().getStrategySettings().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ReplanningConfigGroup.StrategySettings::getStrategyName,
                        ReplanningConfigGroup.StrategySettings::getWeight));
    }

    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(file)), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static long optionalSeconds(org.matsim.core.utils.misc.OptionalTime time) {
        return time.isDefined() ? Math.round(time.seconds()) : Long.MIN_VALUE;
    }

    private static int moduleCount(String xml, String name) {
        Pattern pattern = Pattern.compile("<module\\s+name\\s*=\\s*[\\\"']"
                + Pattern.quote(name) + "[\\\"']", Pattern.CASE_INSENSITIVE);
        return (int) pattern.matcher(xml).results().count();
    }

    private static boolean same(String first, String second) {
        return first.replace('\\', '/').equalsIgnoreCase(second.replace('\\', '/'));
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < EPSILON;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
