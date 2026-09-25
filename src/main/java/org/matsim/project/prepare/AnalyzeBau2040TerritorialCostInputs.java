package org.matsim.project.prepare;

/** Thin server entry point for the shared BAU territorial cost-input analysis. */
public final class AnalyzeBau2040TerritorialCostInputs {
    private AnalyzeBau2040TerritorialCostInputs() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeBau2040TerritorialCostInputs accepts no arguments");
        AnalyzeProduction2040TerritorialCostInputs.analyze("BAU");
    }
}
