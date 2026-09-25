package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

class LiteratureBasedScoringCalibrationRound4Test {

    @Test
    void reproducesFullPrecisionWalkNormalizedConstants() throws Exception {
        var late = ValidateLiteratureBasedScoringCalibrationRound2Config
                .readLateMeans(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_3_LATE,
                        "51-60");
        var derived = ValidateLiteratureBasedScoringCalibrationRound2Config
                .readDerivedAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_4_DERIVATION);
        ValidateLiteratureBasedScoringCalibrationRound2Config.validateDerivation(
                ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4_DERIVATION,
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_3_EXPECTED_ASCS, late, derived);
        var calculated = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_3_EXPECTED_ASCS,
                        late, 0.5);
        assertEquals(-0.27979614837234024, calculated.get("car"), 1e-15);
        assertEquals(0.22971538337764302, calculated.get("pt"), 1e-15);
        assertEquals(-1.1684385773353396, calculated.get("bike"), 1e-15);
        assertEquals(0L, Double.doubleToLongBits(calculated.get("walk")));
        for (String mode : calculated.keySet()) {
            assertEquals(calculated.get(mode), derived.get(mode), 1e-14);
        }
    }

    @Test
    void permitsOnlyRunIdentityAndFourAscsFromRound3() {
        var round3 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_3_CONFIG.toString());
        var round4 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_4_CONFIG.toString());
        String originalPopulation = round3.plans().getInputFile();
        ValidateLiteratureBasedScoringCalibrationRound2Config
                .validateOnlyApprovedDifferences(round3, round4, false);
        assertEquals(originalPopulation, round4.plans().getInputFile());
        assertFalse(round4.plans().getInputFile().toLowerCase().contains("round-"));

        round3 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_3_CONFIG.toString());
        round4 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_4_CONFIG.toString());
        round4.qsim().setEndTime(47 * 3600.0);
        var base = round3;
        var changed = round4;
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .validateOnlyApprovedDifferences(base, changed, false));
    }

    @Test
    void validatesFinalRoundSettingsAndOriginalPopulation() throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4,
                        false);
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(60, config.controller().getLastIteration());
        assertEquals(51, definition.lateFirst());
        assertEquals(60, definition.lateLast());
        assertEquals(0L, Double.doubleToLongBits(
                config.scoring().getModes().get("walk").getConstant()));
        assertTrue(definition.finalRound());
        assertEquals(ConfigUtils.loadConfig(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_3_CONFIG.toString()).plans().getInputFile(),
                config.plans().getInputFile());
    }

    @Test
    void appliesFinalRound4DecisionLabels() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4);
        assertEquals("ACCEPT",
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
    void createsDerivationAndAssessmentButNoAutomaticRecommendation() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4);
        assertEquals(List.of(
                        "round_4_constant_derivation.csv",
                        "round_4_late_iteration_statistics.csv",
                        "round_4_final_mode_summary.csv",
                        "round_4_active_mode_distance_summary.csv",
                        "round_4_final_calibration_assessment.csv",
                        "round_4_report.md"),
                AnalyzeLiteratureBasedScoringCalibrationRound1
                        .summaryFileNames(definition));
        assertTrue(definition.activeDistanceBaseline().endsWith(
                "round_3_active_mode_distance_summary.csv"));
    }

    @Test
    void selectsBetterStableCandidateByPredefinedCriteria() {
        var round3 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(3, Map.of(), 15.0, true,
                        "CALIBRATION_TARGET_NOT_REACHED");
        var worseRound4 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(4, Map.of(), 17.0, true,
                        "CALIBRATION_TARGET_NOT_REACHED");
        assertEquals(3, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round3, worseRound4)).round());

        var acceptedRound4 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(4, Map.of(), 6.0, true,
                        "ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION");
        assertEquals(4, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round3, acceptedRound4)).round());
    }

    @Test
    void protectsRound4Output(@TempDir Path temp) throws Exception {
        Path existing = Files.createDirectory(temp.resolve("existing-round-4-output"));
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .requireOutputAbsent(existing));
    }
}
