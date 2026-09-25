package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Shared, default-deny scenario and execution definitions for all 2040 runners. */
final class Production2040RunSupport {
    static final Set<String> SMOKE_PERSON_IDS = Set.of(
            "smoke-car", "smoke-pt", "smoke-walk", "smoke-bike");

    private Production2040RunSupport() { }

    static RunDefinition scenario(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "BAU" -> definition(Production2040Contract.BAU, "BAU", "BAU_2040",
                    "munich-bau-2040-production-smoke-r5",
                    "scenarios/munich_bau_2040/output/smoke-production-r5", false);
            case "FAST_TRACK" -> definition(Production2040Contract.FAST_TRACK, "FAST_TRACK",
                    "FAST_TRACK_2040", "munich-fast-track-2040-production-smoke-r5",
                    "scenarios/munich_fast_track_2040/output/smoke-production-r5", true);
            default -> throw new IllegalArgumentException(
                    "Scenario must be BAU or FAST_TRACK, not: " + value);
        };
    }

    private static RunDefinition definition(Production2040Contract.ScenarioSpec contract,
            String argument, String analysisScenarioId, String smokeRunId,
            String smokeOutput, boolean fastTrack) {
        return new RunDefinition(argument, analysisScenarioId, contract, smokeRunId,
                Production2040Contract.path(smokeOutput), fastTrack);
    }

    static Config productionConfig(RunDefinition definition) {
        return ConfigUtils.loadConfig(definition.contract().configPath().toString());
    }

    /**
     * Derives the iteration-zero integration config in memory. Only identity,
     * protected output, iteration count, and replacement of the full population
     * by four in-memory smoke agents may differ from production.
     */
    static Config smokeConfig(RunDefinition definition) {
        Config config = productionConfig(definition);
        config.controller().setRunId(definition.smokeRunId());
        config.controller().setOutputDirectory(projectRelative(definition.smokeOutput()));
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        config.plans().setInputFile(null);
        validateSmokeOverrides(definition, config);
        return config;
    }

    static void validateSmokeOverrides(RunDefinition definition, Config smoke) {
        Config production = productionConfig(definition);
        Config expected = productionConfig(definition);
        expected.controller().setRunId(definition.smokeRunId());
        expected.controller().setOutputDirectory(projectRelative(definition.smokeOutput()));
        expected.controller().setFirstIteration(0);
        expected.controller().setLastIteration(0);
        expected.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists);
        expected.plans().setInputFile(null);
        var differences = AnalyzeLiteratureBasedScoringDiagnosticOutput
                .semanticConfigDifferences(expected, smoke);
        Production2040Contract.require(differences.isEmpty(),
                "Smoke config contains a non-allowlisted override:\n- "
                        + String.join("\n- ", differences));
        Production2040Contract.require(production.controller().getLastIteration() == 60,
                "Production config must retain iterations 0-60");
        Production2040Contract.require(smoke.controller().getLastIteration() == 0,
                "Smoke config must end after iteration 0");
        Production2040Contract.require(smoke.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                "Smoke output protection must fail if the directory exists");
    }

    static RunnerArchitecture productionArchitecture() {
        return new RunnerArchitecture(1, 1, 0, 60, true, true);
    }

    static RunnerArchitecture smokeArchitecture() {
        return new RunnerArchitecture(1, 0, 0, 0, true, false);
    }

    static void installProductionModules(Controler controler, RunDefinition definition)
            throws Exception {
        RunnerArchitecture architecture = productionArchitecture();
        for (int index = 0; index < architecture.swissRailRaptorInstallations(); index++)
            controler.addOverridingModule(new SwissRailRaptorModule());
        for (int index = 0; index < architecture.analysisListenerInstallations(); index++)
            Production2040AnalysisListener.install(controler, definition.argument());
    }

    static void installSmokeModules(Controler controler) {
        controler.addOverridingModule(new SwissRailRaptorModule());
    }

    static void requireSmokeRunnerOutputsAbsent(RunDefinition definition) {
        requireSmokeRunnerOutputsAbsent(definition, Files::exists);
    }

    static void requireSmokeRunnerOutputsAbsent(RunDefinition definition,
            Predicate<Path> exists) {
        Production2040Contract.require(!exists.test(definition.smokeOutput()),
                "Protected smoke output already exists: "
                        + Production2040Contract.projectPath(definition.smokeOutput()));
        Production2040Contract.require(!exists.test(definition.productionOutput()),
                "Protected production output already exists: "
                        + Production2040Contract.projectPath(definition.productionOutput()));
    }

    static void requireProductionRunnerOutputAbsent(RunDefinition definition) {
        requireProductionRunnerOutputAbsent(definition, Files::exists);
    }

    static void requireProductionRunnerOutputAbsent(RunDefinition definition,
            Predicate<Path> exists) {
        Production2040Contract.require(!exists.test(definition.productionOutput()),
                "Protected production output already exists: "
                        + Production2040Contract.projectPath(definition.productionOutput()));
    }

    static void validateBothSmokeOutputs(Map<Path, String> protectedBefore,
            SmokeOutputValidation validator) throws Exception {
        validator.validate(scenario("BAU"), protectedBefore);
        validator.validate(scenario("FAST_TRACK"), protectedBefore);
    }

    private static String projectRelative(Path path) {
        return Production2040Contract.projectPath(path);
    }

    record RunDefinition(String argument, String analysisScenarioId,
                         Production2040Contract.ScenarioSpec contract,
                         String smokeRunId, Path smokeOutput, boolean fastTrack) {
        Path productionOutput() {
            return Production2040Contract.path(contract.outputDirectory());
        }
    }

    record RunnerArchitecture(int swissRailRaptorInstallations,
                              int analysisListenerInstallations,
                              int firstIteration, int lastIteration,
                              boolean transitEnabled,
                              boolean postprocessAfterNormalShutdown) { }

    @FunctionalInterface
    interface SmokeOutputValidation {
        void validate(RunDefinition definition, Map<Path, String> protectedBefore)
                throws Exception;
    }
}
