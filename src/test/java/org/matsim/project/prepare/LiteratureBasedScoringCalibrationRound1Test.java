package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

class LiteratureBasedScoringCalibrationRound1Test {

    @Test
    void derivesExactWalkReferencedLogRatioConstants() {
        var actual = ValidateLiteratureBasedScoringCalibrationRound1Config.calculateAscs(
                ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_DIAGNOSTIC_SHARES,
                ValidateLiteratureBasedScoringCalibrationRound1Config.TARGETS);
        assertEquals(0.368217221, actual.get("car"), 1e-12);
        assertEquals(0.619256967, actual.get("pt"), 1e-12);
        assertEquals(0.065869246, actual.get("bike"), 1e-12);
        assertEquals(0.0, actual.get("walk"), 0.0);
        assertTrue(actual.get("bike") > 0,
                "Bike is below the still more overrepresented walk reference in relative terms");
        assertEquals(100.0, ValidateLiteratureBasedScoringCalibrationRound1Config
                .TARGETS.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
    }

    @Test
    void validatesOnlyApprovedRoundDifferencesAndRejectsStructuralChange()
            throws Exception {
        var diagnostic = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.toString());
        var round = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound1Config.CONFIG.toString());
        ValidateLiteratureBasedScoringCalibrationRound1Config
                .validateOnlyApprovedDifferences(diagnostic, round);

        diagnostic = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.toString());
        round = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound1Config.CONFIG.toString());
        round.routing().getTeleportedModeParams().get("walk").setTeleportedModeSpeed(2.0);
        var changed = round;
        var base = diagnostic;
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound1Config
                        .validateOnlyApprovedDifferences(base, changed));
    }

    @Test
    void completeConfigHasFrozenSettingsAndProtectedOutput() throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound1Config
                .loadAndValidate(false);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(40, config.controller().getLastIteration());
        assertEquals(0.8, config.replanning().getFractionOfIterationsToDisableInnovation(), 0);
        assertEquals(32, (int) Math.floor(config.controller().getLastIteration()
                * config.replanning().getFractionOfIterationsToDisableInnovation()));
        assertEquals(0.0, config.scoring().getModes().get("walk").getConstant(), 0.0);
    }

    @Test
    void calculatesLateStatisticsAndDampedRecommendation() {
        List<AnalyzeLiteratureBasedScoringCalibrationRound1.IterationSnapshot> rows =
                new ArrayList<>();
        for (int iteration = 31; iteration <= 40; iteration++) {
            long car = 30 + (iteration - 31);
            rows.add(snapshot(iteration, car, 25, 20, 100 - car - 45));
        }
        var statistics = AnalyzeLiteratureBasedScoringCalibrationRound1
                .lateStatistics(rows, 31, 40);
        assertEquals(34.5, statistics.get("car").mean(), 1e-12);
        assertEquals(9.0, statistics.get("car").range(), 1e-12);
        assertEquals(1.0, statistics.get("car").trend(), 1e-12);
        assertEquals(0.5, statistics.get("car").targetDifference(), 1e-12);

        Map<String, Double> means = Map.of(
                "car", 34.0, "pt", 20.0, "bike", 18.0, "walk", 28.0);
        var recommendation = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_ASCS,
                        means, 0.5);
        double expectedPt = 0.619256967 + 0.5 * Math.log((24.0 / 20.0) / (24.0 / 28.0));
        assertEquals(expectedPt, recommendation.get("pt"), 1e-12);
        assertEquals(0.0, recommendation.get("walk"), 0.0);
    }

    @Test
    void failsOnChangedBothInsideDenominator(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("iterations.csv");
        StringBuilder csv = new StringBuilder("iteration,both_inside_trips,car_count,car_share_percent,pt_count,pt_share_percent,bike_count,bike_share_percent,walk_count,walk_share_percent,unexpected_mode_count,unexpected_modes\n");
        for (int iteration = 0; iteration <= 40; iteration++) {
            long denominator = iteration == 20 ? 160_602 : 160_603;
            csv.append(iteration).append(',').append(denominator)
                    .append(",48881,30,26844,17,35014,22,49864,31,0,{}\n");
        }
        Files.writeString(history, csv, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> AnalyzeLiteratureBasedScoringCalibrationRound1.readIterations(history));
    }

    @Test
    void refusesAnExistingOutputDirectory(@TempDir Path temp) throws Exception {
        Path existing = Files.createDirectory(temp.resolve("existing"));
        assertThrows(IllegalStateException.class,
                () -> ValidateLiteratureBasedScoringCalibrationRound1Config
                        .requireOutputAbsent(existing));
    }

    private static AnalyzeLiteratureBasedScoringCalibrationRound1.IterationSnapshot
            snapshot(int iteration, long car, long pt, long bike, long walk) {
        Map<String, Long> modes = new LinkedHashMap<>();
        modes.put("car", car);
        modes.put("pt", pt);
        modes.put("bike", bike);
        modes.put("walk", walk);
        return new AnalyzeLiteratureBasedScoringCalibrationRound1.IterationSnapshot(
                iteration, car + pt + bike + walk, Map.copyOf(modes), 0);
    }
}
