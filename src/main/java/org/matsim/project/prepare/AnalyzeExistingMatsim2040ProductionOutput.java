package org.matsim.project.prepare;

import java.nio.file.Files;

/**
 * Read-only recovery entry point. It never constructs a Controller and accepts
 * only an output for which the shared raw-output gate proves normal completion.
 */
public final class AnalyzeExistingMatsim2040ProductionOutput {
    private AnalyzeExistingMatsim2040ProductionOutput() { }

    public static void main(String[] args) throws Exception {
        Production2040Contract.require(args.length == 1,
                "Usage: AnalyzeExistingMatsim2040ProductionOutput BAU|FAST_TRACK");
        var run = Production2040RunSupport.scenario(args[0]);
        var definition = Production2040AnalysisSpec.scenario(run.argument());
        ValidateProduction2040AnalysisOutput.validate(definition, false);
        if (Files.exists(definition.analysisDirectory())) {
            ValidateProduction2040AnalysisOutput.validatePublished(definition);
            System.out.println("RECOVERY VALIDATION PASS: an existing complete analysis was "
                    + "validated without overwriting it.");
        } else {
            AnalyzeProduction2040Output.analyze(definition);
            ValidateProduction2040AnalysisOutput.validatePublished(definition);
            System.out.println("RECOVERY ANALYSIS PASS: postprocessing was published and validated.");
        }
        System.out.println("No Controller, QSim, routing, or input mutation was performed.");
    }
}
