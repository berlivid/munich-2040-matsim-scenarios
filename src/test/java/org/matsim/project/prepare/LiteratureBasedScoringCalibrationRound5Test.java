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

class LiteratureBasedScoringCalibrationRound5Test {

    @Test
    void reproducesConservativeFullPrecisionWalkNormalizedConstants() throws Exception {
        var late = ValidateLiteratureBasedScoringCalibrationRound2Config.readLateMeans(
                ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_4_LATE,
                "51-60");
        var derived = ValidateLiteratureBasedScoringCalibrationRound2Config
                .readDerivedAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_5_DERIVATION, 5);
        ValidateLiteratureBasedScoringCalibrationRound2Config.validateDerivation(
                ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_5_DERIVATION,
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_4_EXPECTED_ASCS,
                late, derived, 0.25, 5);
        var calculated = ValidateLiteratureBasedScoringCalibrationRound1Config
                .recommendNextAscs(
                        ValidateLiteratureBasedScoringCalibrationRound2Config
                                .ROUND_4_EXPECTED_ASCS,
                        late, 0.25);
        assertEquals(-0.35175057259662179, calculated.get("car"), 1e-15);
        assertEquals(0.16187543976517921, calculated.get("pt"), 1e-15);
        assertEquals(-1.2617442557140233, calculated.get("bike"), 1e-15);
        assertEquals(0L, Double.doubleToLongBits(calculated.get("walk")));
        calculated.forEach((mode, value) -> assertEquals(value, derived.get(mode), 1e-14));
    }

    @Test
    void permitsOnlyRunIdentityAndFourAscsFromRound4() {
        var round4 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_4_CONFIG.toString());
        var round5 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_5_CONFIG.toString());
        ValidateLiteratureBasedScoringCalibrationRound2Config
                .validateOnlyApprovedDifferences(round4, round5, false);
        assertEquals(round4.plans().getInputFile(), round5.plans().getInputFile());
        assertFalse(round5.plans().getInputFile().toLowerCase().contains("round-"));

        round4 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_4_CONFIG.toString());
        round5 = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .ROUND_5_CONFIG.toString());
        round5.global().setRandomSeed(4712);
        var base = round4;
        var changed = round5;
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .validateOnlyApprovedDifferences(base, changed, false));
    }

    @Test
    void validatesFinalRoundSettingsOriginalPopulationAndRound4Baseline()
            throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(
                        ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_5,
                        false);
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_5);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(60, config.controller().getLastIteration());
        assertEquals(51, definition.lateFirst());
        assertEquals(60, definition.lateLast());
        assertEquals(0L, Double.doubleToLongBits(
                config.scoring().getModes().get("walk").getConstant()));
        assertTrue(definition.finalRound());
        assertTrue(definition.activeDistanceBaseline().endsWith(
                "round_4_active_mode_distance_summary.csv"));
    }

    @Test
    void createsExactFinalSummarySetAndNoRound6() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_5);
        assertEquals(List.of(
                        "round_5_constant_derivation.csv",
                        "round_5_late_iteration_statistics.csv",
                        "round_5_final_mode_summary.csv",
                        "round_5_active_mode_distance_summary.csv",
                        "round_5_final_calibration_assessment.csv",
                        "round_5_report.md"),
                AnalyzeLiteratureBasedScoringCalibrationRound1.summaryFileNames(definition));
        assertFalse(Files.exists(Path.of("scenarios/munich_calibration_2019/"
                + "config_literature_based_scoring_calibration_round_6.xml")));
    }

    @Test
    void appliesUnchangedDecisionThresholds() {
        var definition = ValidateLiteratureBasedScoringCalibrationRound2Config
                .definition(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_5);
        assertEquals("ACCEPT", AnalyzeLiteratureBasedScoringCalibrationRound1
                .decisionStatus(true, true, true, false, true, false, definition));
        assertEquals("ACCEPT_WITH_REPORTED_RESIDUAL_DEVIATION",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, false, true, false, true, false, definition));
        assertEquals("CALIBRATION_TARGET_NOT_REACHED",
                AnalyzeLiteratureBasedScoringCalibrationRound1.decisionStatus(
                        true, false, false, false, true, false, definition));
    }

    @Test
    void selectsRound5OnlyUnderThePredefinedImprovementRule() {
        var round4 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(4, Map.of(), 10.2, true,
                        "CALIBRATION_TARGET_NOT_REACHED");
        var betterRound5 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(5, Map.of(), 8.0, true,
                        "CALIBRATION_TARGET_NOT_REACHED");
        assertEquals(5, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round4, betterRound5), true, false).round());
        assertEquals(4, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round4, betterRound5), false, false).round());
        assertEquals(4, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round4, betterRound5), true, true).round());

        var worseRound5 = new AnalyzeLiteratureBasedScoringCalibrationRound1
                .CalibrationHistoryRow(5, Map.of(), 11.0, true,
                        "CALIBRATION_TARGET_NOT_REACHED");
        assertEquals(4, AnalyzeLiteratureBasedScoringCalibrationRound1
                .selectFinalCandidate(List.of(round4, worseRound5), true, false).round());
    }

    @Test
    void protectsRound5OutputAndVersionsHeadlessExecution(@TempDir Path temp)
            throws Exception {
        Path existing = Files.createDirectory(temp.resolve("existing-round-5-output"));
        assertThrows(IllegalStateException.class, () ->
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .requireOutputAbsent(existing));
        String round4Run = Files.readString(Path.of(
                ".run/16 Run Literature-Based Scoring Calibration Round 4.run.xml"));
        String round5Run = Files.readString(Path.of(
                ".run/18 Run Literature-Based Scoring Calibration Round 5.run.xml"));
        assertTrue(round4Run.contains("-Djava.awt.headless=true -Xms4g -Xmx16g"));
        assertTrue(round5Run.contains("-Djava.awt.headless=true -Xms4g -Xmx16g"));
    }
}
