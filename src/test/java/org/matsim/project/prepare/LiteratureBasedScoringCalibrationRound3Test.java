package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

class LiteratureBasedScoringCalibrationRound3Test {

    @Test
    void derivesExactRound3AscsFromRound2LateMeans() throws Exception {
        var lateMeans = ValidateLiteratureBasedScoringCalibrationRound2Config
                .readLateMeans(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_2_LATE,
                        "51-60");
        var recorded = ValidateLiteratureBasedScoringCalibrationRound2Config
                .readRecommendedAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_2_RECOMMENDATION);
        assertEquals(ValidateLiteratureBasedScoringCalibrationRound2Config
                .ROUND_3_EXPECTED_LATE_MEANS, lateMeans);
        assertEquals(ValidateLiteratureBasedScoringCalibrationRound2Config
                .ROUND_3_EXPECTED_ASCS, recorded);

        var calculated = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.EXPECTED_ASCS,
                        lateMeans, 0.5);
        assertEquals(-0.052867606, calculated.get("car"), 1e-9);
        assertEquals(0.408378132, calculated.get("pt"), 1e-9);
        assertEquals(-0.851722801, calculated.get("bike"), 1e-9);
        assertEquals(0.0, calculated.get("walk"), 0.0);
    }

    @Test
    void permitsOnlyRoundIdentityAndAscDifferences() {
        var round2 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config.CONFIG.toString());
        var round3 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_3_CONFIG.toString());
        String originalPopulation = round2.plans().getInputFile();
        ValidateLiteratureBasedScoringCalibrationRound2Config
                .validateOnlyApprovedDifferences(round2, round3, false);
        assertEquals(originalPopulation, round3.plans().getInputFile());
        assertFalse(round3.plans().getInputFile().toLowerCase().contains("round-"));

        round2 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config.CONFIG.toString());
        round3 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_3_CONFIG.toString());
        round3.controller().setLastIteration(59);
        var base = round2;
        var changed = round3;
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .validateOnlyApprovedDifferences(base, changed, false));
    }

    @Test
    void validatesFinalRoundSettingsAndWalkReference() throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3,
                        false);
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(60, config.controller().getLastIteration());
        assertEquals(48, (int) Math.floor(config.controller().getLastIteration()
                * config.replanning().getFractionOfIterationsToDisableInnovation()));
        assertEquals(51, definition.lateFirst());
        assertEquals(60, definition.lateLast());
        assertEquals(0.0, config.scoring().getModes().get("walk").getConstant(), 0.0);
        assertTrue(definition.finalRound());
    }

    @Test
    void appliesFinalThreeWayDecision() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3);
        assertEquals("ACCEPT_CALIBRATION",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, true, true, false, true, false, definition));
        assertEquals("ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, false, true, false, true, false, definition));
        assertEquals("CALIBRATION_TARGET_NOT_REACHED",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, false, false, false, true, false, definition));
        assertEquals("CALIBRATION_TARGET_NOT_REACHED",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        false, true, true, false, true, false, definition));
    }

    @Test
    void round3CreatesAssessmentButNoAutomaticRecommendation() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3);
        assertEquals(List.of(
                        "round_3_late_iteration_statistics.csv",
                        "round_3_final_mode_summary.csv",
                        "round_3_active_mode_distance_summary.csv",
                        "round_3_final_calibration_assessment.csv",
                        "round_3_report.md"),
                AnalyzeLiteratureBasedScoringCalibrationRound1
                        .summaryFileNames(definition));
    }

    @Test
    void protectsRound3Output(@TempDir Path temp) throws Exception {
        Path existing = Files.createDirectory(temp.resolve("existing-round-3-output"));
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .requireOutputAbsent(existing));
    }
}
