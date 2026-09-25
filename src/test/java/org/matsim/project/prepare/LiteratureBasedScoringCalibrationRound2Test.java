package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class LiteratureBasedScoringCalibrationRound2Test {

    @Test
    void derivesExactRound2AscsFromRound1LateMeans() {
        var actual = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound1Config.EXPECTED_ASCS,
                        ValidateLiteratureBasedScoringCalibrationRound2Config.EXPECTED_LATE_MEANS,
                        0.5);
        assertEquals(0.258598439, actual.get("car"), 1e-9);
        assertEquals(0.611403971, actual.get("pt"), 1e-9);
        assertEquals(-0.348664107, actual.get("bike"), 1e-9);
        assertEquals(0.0, actual.get("walk"), 0.0);
    }

    @Test
    void validatesOnlyApprovedDifferencesAndOriginalPopulation() throws Exception {
        var round1 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound1Config.CONFIG.toString());
        var round2 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config.CONFIG.toString());
        String originalPlans = round1.plans().getInputFile();
        ValidateLiteratureBasedScoringCalibrationRound2Config
                .validateOnlyApprovedDifferences(round1, round2);
        assertEquals(originalPlans, round2.plans().getInputFile());
        assertFalse(round2.plans().getInputFile().toLowerCase().contains("round-1"));

        round1 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound1Config.CONFIG.toString());
        round2 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config.CONFIG.toString());
        round2.scoring().setMarginalUtilityOfMoney(2.0);
        var base = round1;
        var changed = round2;
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .validateOnlyApprovedDifferences(base, changed));
    }

    @Test
    void completeConfigHasSixtyIterationsAndInnovationSwitch() throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(false);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(60, config.controller().getLastIteration());
        assertEquals(48, (int) Math.floor(config.controller().getLastIteration()
                * config.replanning().getFractionOfIterationsToDisableInnovation()));
        assertEquals(0.0, config.scoring().getModes().get("walk").getConstant(), 0.0);
        assertEquals(ValidateLiteratureBasedScoringCalibrationRound1Config
                        .loadAndValidate(false).plans().getInputFile(),
                config.plans().getInputFile());
    }

    @Test
    void usesLateWindowFiftyOneToSixty() {
        List<AnalyzeLiteratureBasedScoringCalibrationRound1.IterationSnapshot> rows =
                new ArrayList<>();
        for (int iteration = 51; iteration <= 60; iteration++) {
            rows.add(snapshot(iteration, 34, 24, 18, 24));
        }
        var late = AnalyzeLiteratureBasedScoringCalibrationRound1
                .lateStatistics(rows, 51, 60);
        assertEquals(34.0, late.get("car").mean(), 1e-12);
        assertEquals(0.0, late.get("car").trend(), 1e-12);
        assertEquals(0.0, late.get("walk").targetDifference(), 1e-12);
    }

    @Test
    void lateStuckDecisionIgnoresLargeEarlyCounts() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config.definition();
        List<AnalyzeLiteratureBasedScoringCalibrationRound1.StuckRow> rows =
                new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            long events = iteration < 51 ? 10_000 : 2;
            long affected = iteration < 51 ? 5_000 : 1;
            rows.add(new AnalyzeLiteratureBasedScoringCalibrationRound1.StuckRow(
                    iteration, "ALL", events, affected, events, affected));
        }
        var assessment = AnalyzeLiteratureBasedScoringCalibrationRound1
                .stuckAssessment(rows, definition);
        assertTrue(assessment.cumulativeEvents() > 500_000);
        assertTrue(assessment.acceptable(),
                "Early stuck events must not trigger the Round-2 structural decision");
        assertEquals(2, assessment.finalEvents());
    }

    @Test
    void returnsAllThreeDecisionClassifications() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config.definition();
        assertEquals("ACCEPT_CALIBRATION",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, true, false, true, false, definition));
        assertEquals("ONE_FINAL_ASC_UPDATE_REQUIRED",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, false, false, true, false, definition));
        assertEquals("STRUCTURAL_REVIEW_REQUIRED",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        false, false, false, true, false, definition));
    }

    @Test
    void protectsEveryIterationDenominatorAndOutput(@TempDir Path temp)
            throws Exception {
        Path history = temp.resolve("iterations.csv");
        StringBuilder csv = new StringBuilder("iteration,both_inside_trips,car_count,car_share_percent,pt_count,pt_share_percent,bike_count,bike_share_percent,walk_count,walk_share_percent,unexpected_mode_count,unexpected_modes\n");
        for (int iteration = 0; iteration <= 60; iteration++) {
            long denominator = iteration == 55 ? 160_602 : 160_603;
            csv.append(iteration).append(',').append(denominator)
                    .append(",48881,30,26844,17,35014,22,49864,31,0,{}\n");
        }
        Files.writeString(history, csv, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                AnalyzeLiteratureBasedScoringCalibrationRound1
                        .readIterations(history, 60));

        Path existing = Files.createDirectory(temp.resolve("existing-output"));
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
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
