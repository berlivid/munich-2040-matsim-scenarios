package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.Map;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Shared server-only BAU/Fast-Track production runner. */
public final class RunMatsim2040Production {
    private RunMatsim2040Production() { }

    public static void main(String[] args) throws Exception {
        Production2040Contract.require(args.length == 1,
                "Usage: RunMatsim2040Production BAU|FAST_TRACK");
        var definition = Production2040RunSupport.scenario(args[0]);
        ValidateMatsim2040ProductionInput.validate(definition);
        var contract = Production2040Contract.loadAndValidate();
        Map<Path, String> protectedBefore = Production2040Contract.protectedInputSnapshot(contract);
        Production2040RunSupport.requireProductionRunnerOutputAbsent(definition);
        Production2040RunSupport.validateBothSmokeOutputs(protectedBefore,
                ValidateMatsim2040ProductionSmokeOutput::validate);

        Scenario scenario = ScenarioUtils.loadScenario(
                Production2040RunSupport.productionConfig(definition));
        Production2040Contract.require(scenario.getConfig().controller().getFirstIteration() == 0
                        && scenario.getConfig().controller().getLastIteration() == 60,
                "Production runner requires the unchanged iterations 0-60 config");
        Controler controler = new Controler(scenario);
        Production2040RunSupport.installProductionModules(controler, definition);
        try {
            controler.run();
        } catch (RuntimeException | Error failure) {
            System.err.println("SIMULATION_FAILED scenario=" + definition.argument()
                    + " output_preserved="
                    + Production2040Contract.projectPath(definition.productionOutput()));
            throw failure;
        }

        Production2040Contract.require(protectedBefore.equals(
                        Production2040Contract.protectedInputSnapshot(contract)),
                "Production simulation changed a protected input");
        try {
            AnalyzeProduction2040Output.analyze(
                    Production2040AnalysisSpec.scenario(definition.argument()));
            ValidateProduction2040AnalysisOutput.validatePublished(
                    Production2040AnalysisSpec.scenario(definition.argument()));
        } catch (Exception | Error analysisFailure) {
            System.err.println("ANALYSIS_FAILED_AFTER_NORMAL_SIMULATION scenario="
                    + definition.argument() + " output_preserved="
                    + Production2040Contract.projectPath(definition.productionOutput()));
            System.err.println("Run AnalyzeExistingMatsim2040ProductionOutput "
                    + definition.argument() + " after resolving the analysis issue; do not rerun QSim.");
            throw analysisFailure;
        }
        System.out.println("2040 PRODUCTION RUN AND ANALYSIS PASS scenario="
                + definition.argument());
    }
}
