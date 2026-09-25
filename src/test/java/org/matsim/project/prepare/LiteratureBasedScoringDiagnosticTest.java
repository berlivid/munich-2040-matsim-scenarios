package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

class LiteratureBasedScoringDiagnosticTest {

    @Test
    void configContainsExactLiteratureBasedStartingVector() throws Exception {
        var config = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate(false);
        assertEquals(6.0, config.scoring().getPerforming_utils_hr(), 1e-12);
        assertEquals(1.0, config.scoring().getMarginalUtilityOfMoney(), 1e-12);
        assertEquals(-6.0, config.scoring().getMarginalUtlOfWaitingPt_utils_hr(), 1e-12);
        assertEquals(-1.0, config.scoring().getUtilityOfLineSwitch(), 1e-12);

        for (String mode : ValidateLiteratureBasedScoringDiagnosticConfig.CHOICE_MODES) {
            var params = config.scoring().getModes().get(mode);
            assertEquals(0.0, params.getConstant(), 0.0);
            assertEquals(0.0, params.getMarginalUtilityOfTraveling(), 0.0);
            assertEquals(0.0, params.getMarginalUtilityOfDistance(), 0.0);
            assertEquals(0.0, params.getDailyUtilityConstant(), 0.0);
            assertEquals(0.0, params.getDailyMonetaryConstant(), 0.0);
            assertEquals("car".equals(mode) ? -0.00020 : 0.0,
                    params.getMonetaryDistanceRate(), 0.0);
        }
        assertEquals(1.333333333, config.routing().getTeleportedModeParams()
                .get("walk").getTeleportedModeSpeed(), 1e-12);
        assertEquals(3.805555556, config.routing().getTeleportedModeParams()
                .get("bike").getTeleportedModeSpeed(), 1e-12);
        assertEquals(1.3, config.routing().getTeleportedModeParams()
                .get("walk").getBeelineDistanceFactor(), 1e-12);
        assertEquals(1.3, config.routing().getTeleportedModeParams()
                .get("bike").getBeelineDistanceFactor(), 1e-12);
    }

    @Test
    void walkIsPermanentZeroReferenceAndChoiceSetIsClosed() {
        var config = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.toString());
        var basis = ConfigUtils.loadConfig(
                ValidateLiteratureBasedScoringDiagnosticConfig.INPUT_VALIDATION_CONFIG
                        .toString());
        assertEquals(List.of("car", "pt", "walk", "bike"),
                Arrays.asList(config.subtourModeChoice().getModes()));
        assertEquals(Set.of("car", "bike"),
                Set.of(config.subtourModeChoice().getChainBasedModes()));
        assertFalse(config.subtourModeChoice().considerCarAvailability());
        config.scoring().getModes().get("walk").setConstant(0.01);
        assertThrows(IllegalStateException.class,
                () -> ValidateLiteratureBasedScoringDiagnosticConfig
                        .validateScoring(config, basis));
    }

    @Test
    void runControlAndStrategiesAreExact() throws Exception {
        var config = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate(false);
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(10, config.controller().getLastIteration());
        assertEquals(48 * 3600.0, config.qsim().getEndTime().seconds(), 1e-12);
        assertEquals(4711, config.global().getRandomSeed());
        assertEquals(0.05, config.qsim().getFlowCapFactor(), 1e-12);
        assertEquals(0.05, config.qsim().getStorageCapFactor(), 1e-12);
        assertEquals(1.0, config.scoring().getBrainExpBeta(), 1e-12);
        assertEquals(Map.of("ChangeExpBeta", 0.8, "ReRoute", 0.1,
                        "SubtourModeChoice", 0.1),
                ValidateLiteratureBasedScoringDiagnosticConfig.strategyMap(config));
        assertEquals(ValidateLiteratureBasedScoringDiagnosticConfig.OUTPUT.normalize(),
                Path.of(config.controller().getOutputDirectory()).normalize());
    }

    @Test
    void outputProtectionRejectsExistingDirectory(@TempDir Path temp) throws Exception {
        Path existing = Files.createDirectories(temp.resolve("existing-output"));
        assertThrows(IllegalStateException.class,
                () -> ValidateLiteratureBasedScoringDiagnosticConfig
                        .requireOutputAbsent(existing));
    }

    @Test
    void protectedInputHashesAndTechnicalScenarioRemainUnchanged() throws Exception {
        for (var input : ValidateLiteratureBasedScoringDiagnosticConfig
                .PROTECTED_INPUT_SHA256.entrySet()) {
            Path file = ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG.getParent()
                    .resolve(input.getKey()).normalize();
            assertEquals(input.getValue(),
                    ValidateLiteratureBasedScoringDiagnosticConfig.sha256(file));
        }
        ValidateLiteratureBasedScoringDiagnosticConfig.validateProtectedWorkspace();
        assertTrue(Files.isRegularFile(Path.of(
                "scenarios/munich_calibration_2019/config_input_validation.xml")));
    }

    @Test
    void diagnosticContainsNoAutomaticAscUpdateOrEmbeddedTargets() throws Exception {
        String config = Files.readString(
                ValidateLiteratureBasedScoringDiagnosticConfig.CONFIG);
        String runner = Files.readString(Path.of("src/main/java/org/matsim/project/prepare/"
                + "RunMatsim2019LiteratureBasedScoringDiagnostic.java"));
        assertFalse(config.contains("calibrationTarget"));
        assertFalse(runner.contains("Math.log"));
        assertFalse(runner.contains("setConstant"));
        assertTrue(runner.contains("SwissRailRaptorModule"));
    }

    @Test
    void intellijConfigurationsAreSyntactic() throws Exception {
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        for (String name : List.of(
                "05 Validate Literature-Based 2019 Scoring Diagnostic.run.xml",
                "06 Run Literature-Based 2019 Scoring Diagnostic.run.xml",
                "07 Analyze Literature-Based 2019 Scoring Diagnostic.run.xml")) {
            assertEquals("component", builder.parse(Path.of(".run", name).toFile())
                    .getDocumentElement().getTagName());
        }
    }
}
