package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Fail-closed, read-only validation of literature-based calibration Round 1. */
public final class ValidateLiteratureBasedScoringCalibrationRound1Config {
    public static final Path CONFIG = Path.of("scenarios/munich_calibration_2019/"
            + "config_literature_based_scoring_calibration_round_1.xml");
    public static final Path OUTPUT = Path.of("scenarios/munich_calibration_2019/output/"
            + "literature-based-scoring-calibration-round-1");
    public static final String RUN_ID =
            "munich-calibration-2019-literature-based-scoring-calibration-round-1";
    public static final Path DIAGNOSTIC_SUMMARY =
            ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT.resolve(
                    "analysis/literature_based_scoring_final_mode_summary.csv");
    public static final Path DISTANCE_AUDIT =
            ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT.resolve(
                    "analysis/distance-audit/distance_audit_report.md");
    public static final Map<String, Double> TARGETS = ordered(
            34.0, 24.0, 18.0, 24.0);
    public static final Map<String, Double> EXPECTED_DIAGNOSTIC_SHARES = ordered(
            30.435919628, 16.714507201, 21.801585275, 31.047987896);
    public static final Map<String, Double> EXPECTED_ASCS = Map.of(
            "car", 0.368217221,
            "pt", 0.619256967,
            "bike", 0.065869246,
            "walk", 0.0);
    public static final int EXPECTED_BOTH_INSIDE = 160_603;
    public static final int LAST_ITERATION = 40;
    public static final int INNOVATION_LAST_ACTIVE_ITERATION = 32;
    private static final double EPSILON = 1e-9;

    private ValidateLiteratureBasedScoringCalibrationRound1Config() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "The Round-1 validator accepts no arguments");
        Config config = loadAndValidate(true);
        System.out.printf(Locale.ROOT,
                "LITERATURE-BASED SCORING CALIBRATION ROUND-1 VALIDATION PASS%n"
                        + "config=%s%noutput=%s%niterations=0..%d innovation_active_through=%d%n"
                        + "ASCs=%s walk_reference=0 BOTH_INSIDE=%d%n"
                        + "Protected inputs are byte-identical. No Controller or QSim was started.%n",
                CONFIG, OUTPUT, config.controller().getLastIteration(),
                INNOVATION_LAST_ACTIVE_ITERATION, EXPECTED_ASCS, EXPECTED_BOTH_INSIDE);
    }

    public static Config loadAndValidate() throws Exception {
        return loadAndValidate(true);
    }

    static Config loadAndValidate(boolean requireOutputAbsent) throws Exception {
        require(Files.isRegularFile(CONFIG), "Missing Round-1 config: " + CONFIG);
        Evidence evidence = readEvidence(DIAGNOSTIC_SUMMARY);
        require(evidence.bothInsideTrips() == EXPECTED_BOTH_INSIDE,
                "Diagnostic BOTH_INSIDE total changed: " + evidence.bothInsideTrips());
        require(evidence.shares().equals(EXPECTED_DIAGNOSTIC_SHARES),
                "Diagnostic modal shares differ from the approved evidence: " + evidence.shares());
        require(Files.isRegularFile(DISTANCE_AUDIT),
                "Missing completed distance audit: " + DISTANCE_AUDIT);
        require(close(TARGETS.values().stream().mapToDouble(Double::doubleValue).sum(), 100.0),
                "Trip-share targets must sum to 100%");

        Map<String, Double> calculated = calculateAscs(evidence.shares(), TARGETS);
        for (String mode : EXPECTED_ASCS.keySet()) {
            require(close(calculated.get(mode), EXPECTED_ASCS.get(mode)),
                    "Calculated " + mode + " ASC differs: calculated=" + calculated.get(mode)
                            + ", expected=" + EXPECTED_ASCS.get(mode));
        }

        Config diagnostic = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate(false);
        Config actual = ConfigUtils.loadConfig(CONFIG.toString());
        validateRunControl(actual, requireOutputAbsent);
        validateAscs(actual);
        validateOnlyApprovedDifferences(diagnostic, actual);
        ValidateLiteratureBasedScoringDiagnosticConfig.validateProtectedWorkspace();
        return actual;
    }

    static Map<String, Double> calculateAscs(Map<String, Double> observed,
            Map<String, Double> targets) {
        require(observed.keySet().containsAll(List.of("car", "pt", "bike", "walk")),
                "Observed shares must contain car, pt, bike and walk");
        require(targets.keySet().containsAll(List.of("car", "pt", "bike", "walk")),
                "Targets must contain car, pt, bike and walk");
        double walkObserved = observed.get("walk");
        double walkTarget = targets.get("walk");
        require(walkObserved > 0 && walkTarget > 0,
                "Walk reference shares must be positive");
        Map<String, Double> result = new LinkedHashMap<>();
        for (String mode : List.of("car", "pt", "bike")) {
            require(observed.get(mode) > 0 && targets.get(mode) > 0,
                    mode + " shares must be positive");
            double raw = Math.log((targets.get(mode) / observed.get(mode))
                    / (walkTarget / walkObserved));
            result.put(mode, roundNine(raw));
        }
        result.put("walk", 0.0);
        return Map.copyOf(result);
    }

    static Map<String, Double> recommendNextAscs(Map<String, Double> current,
            Map<String, Double> lateMeans, double damping) {
        require(damping > 0 && damping <= 1, "Damping must be in (0,1]");
        Map<String, Double> result = new LinkedHashMap<>();
        double walkRatio = TARGETS.get("walk") / lateMeans.get("walk");
        for (String mode : List.of("car", "pt", "bike")) {
            double update = damping * Math.log((TARGETS.get(mode) / lateMeans.get(mode))
                    / walkRatio);
            result.put(mode, current.get(mode) + update);
        }
        result.put("walk", 0.0);
        return Map.copyOf(result);
    }

    static void validateRunControl(Config config, boolean requireOutputAbsent) {
        require(config.controller().getFirstIteration() == 0
                        && config.controller().getLastIteration() == LAST_ITERATION,
                "Round 1 must run iterations 0..40");
        require(RUN_ID.equals(config.controller().getRunId()), "Unexpected Round-1 run ID");
        require(OUTPUT.normalize().equals(Path.of(config.controller().getOutputDirectory()).normalize()),
                "Unexpected Round-1 output directory");
        require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Round-1 output must use failIfDirectoryExists");
        require(config.global().getRandomSeed() == 4711, "Random seed must remain 4711");
        require(config.qsim().getEndTime().isDefined()
                        && close(config.qsim().getEndTime().seconds(), 48 * 3600.0),
                "QSim end time must remain 48:00:00");
        require(close(config.qsim().getFlowCapFactor(), 0.05)
                        && close(config.qsim().getStorageCapFactor(), 0.05),
                "Capacity factors must remain 0.05/0.05");
        require(close(config.replanning().getFractionOfIterationsToDisableInnovation(), 0.8),
                "Innovation-disable fraction must remain 0.8");
        require((int) Math.floor(LAST_ITERATION
                        * config.replanning().getFractionOfIterationsToDisableInnovation())
                        == INNOVATION_LAST_ACTIVE_ITERATION,
                "Innovation must be active through iteration 32");
        if (requireOutputAbsent) {
            ValidateLiteratureBasedScoringDiagnosticConfig.requireOutputAbsent(OUTPUT);
        }
    }

    static void requireOutputAbsent(Path output) {
        ValidateLiteratureBasedScoringDiagnosticConfig.requireOutputAbsent(output);
    }

    static void validateAscs(Config config) {
        for (var expected : EXPECTED_ASCS.entrySet()) {
            var params = config.scoring().getModes().get(expected.getKey());
            require(params != null, "Missing mode scoring for " + expected.getKey());
            require(close(params.getConstant(), expected.getValue()),
                    "Unexpected " + expected.getKey() + " ASC: " + params.getConstant());
        }
        require(config.scoring().getModes().get("walk").getConstant() == 0.0,
                "Walk must remain the exact zero reference");
    }

    static void validateOnlyApprovedDifferences(Config diagnostic, Config round) {
        String roundRunId = round.controller().getRunId();
        String roundOutput = round.controller().getOutputDirectory();
        int roundLastIteration = round.controller().getLastIteration();
        Map<String, Double> roundAscs = new LinkedHashMap<>();
        for (String mode : List.of("car", "pt", "bike")) {
            roundAscs.put(mode, round.scoring().getModes().get(mode).getConstant());
        }
        round.controller().setRunId(diagnostic.controller().getRunId());
        round.controller().setOutputDirectory(diagnostic.controller().getOutputDirectory());
        round.controller().setLastIteration(diagnostic.controller().getLastIteration());
        for (String mode : List.of("car", "pt", "bike")) {
            round.scoring().getModes().get(mode).setConstant(
                    diagnostic.scoring().getModes().get(mode).getConstant());
        }
        List<String> differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(diagnostic, round);
        round.controller().setRunId(roundRunId);
        round.controller().setOutputDirectory(roundOutput);
        round.controller().setLastIteration(roundLastIteration);
        roundAscs.forEach((mode, value) ->
                round.scoring().getModes().get(mode).setConstant(value));
        require(differences.isEmpty(), "Round 1 contains unapproved semantic differences:\n- "
                + String.join("\n- ", differences));
    }

    static Evidence readEvidence(Path file) throws Exception {
        require(Files.isRegularFile(file), "Missing diagnostic mode summary: " + file);
        Map<String, Double> shares = new LinkedHashMap<>();
        long trips = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            require(header != null && header.startsWith("mode,plan_trip_count,trip_share_percent"),
                    "Unexpected diagnostic mode-summary header");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (!List.of("car", "pt", "bike", "walk").contains(fields[0])) continue;
                trips += Long.parseLong(fields[1]);
                shares.put(fields[0], Double.parseDouble(fields[2]));
            }
        }
        return new Evidence(trips, Map.copyOf(shares));
    }

    private static Map<String, Double> ordered(double car, double pt, double bike, double walk) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("car", car);
        result.put("pt", pt);
        result.put("bike", bike);
        result.put("walk", walk);
        return Map.copyOf(result);
    }

    private static double roundNine(double value) {
        return Math.round(value * 1_000_000_000.0) / 1_000_000_000.0;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Evidence(long bothInsideTrips, Map<String, Double> shares) { }
}
