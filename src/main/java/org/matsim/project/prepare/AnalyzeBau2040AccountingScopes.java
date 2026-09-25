package org.matsim.project.prepare;

/** Thin server entry point for the shared BAU accounting-scope analysis. */
public final class AnalyzeBau2040AccountingScopes {
    private AnalyzeBau2040AccountingScopes() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeBau2040AccountingScopes accepts no arguments");
        AnalyzeProduction2040AccountingScopes.analyze("BAU");
    }
}
