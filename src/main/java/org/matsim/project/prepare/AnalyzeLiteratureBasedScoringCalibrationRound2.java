package org.matsim.project.prepare;

/** Recovery-only entry point for an already completed parameterized round. */
public final class AnalyzeLiteratureBasedScoringCalibrationRound2 {
    private AnalyzeLiteratureBasedScoringCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        summarizeExistingOutput(ValidateLiteratureBasedScoringCalibrationRound2Config
                .specification(args));
    }

    static void summarizeExistingOutput() throws Exception {
        summarizeExistingOutput(ValidateLiteratureBasedScoringCalibrationRound2Config.ROUND_2);
    }

    static void summarizeExistingOutput(
            ValidateLiteratureBasedScoringCalibrationRound2Config.CalibrationSpecification
                    specification) throws Exception {
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(specification, false);
        AnalyzeLiteratureBasedScoringCalibrationRound1.summarizeExistingOutput(
                ValidateLiteratureBasedScoringCalibrationRound2Config
                        .definition(specification), config);
    }
}
