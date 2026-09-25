package org.matsim.project.prepare;

/** Thin server entry point for the shared Fast Track PT cost-allocation analysis. */
public final class AnalyzeFastTrack2040PtCostAllocation {
    private AnalyzeFastTrack2040PtCostAllocation() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeFastTrack2040PtCostAllocation accepts no arguments");
        AnalyzeProduction2040PtCostAllocation.analyze("FAST_TRACK");
    }
}
