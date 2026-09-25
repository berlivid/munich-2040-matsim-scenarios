package org.matsim.project.prepare;

/** Thin server entry point for the shared Fast Track accounting-scope analysis. */
public final class AnalyzeFastTrack2040AccountingScopes {
    private AnalyzeFastTrack2040AccountingScopes() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 0,
                "AnalyzeFastTrack2040AccountingScopes accepts no arguments");
        AnalyzeProduction2040AccountingScopes.analyze("FAST_TRACK");
    }
}
