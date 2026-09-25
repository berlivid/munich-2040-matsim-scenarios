package org.matsim.project.prepare;

/** Thin server entry point for the shared BAU PT cost-allocation analysis. */
public final class AnalyzeBau2040PtCostAllocation {
    private AnalyzeBau2040PtCostAllocation() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeBau2040PtCostAllocation accepts no arguments");
        AnalyzeProduction2040PtCostAllocation.analyze("BAU");
    }
}
