package org.matsim.project.prepare;

/** Thin server entry point for the shared Fast Track territorial cost-input analysis. */
public final class AnalyzeFastTrack2040TerritorialCostInputs {
    private AnalyzeFastTrack2040TerritorialCostInputs() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeFastTrack2040TerritorialCostInputs accepts no arguments");
        AnalyzeProduction2040TerritorialCostInputs.analyze("FAST_TRACK");
    }
}
