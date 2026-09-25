package org.matsim.project.prepare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

/** Strict semantic comparison for MATSim-written 2040 output configs only. */
final class Production2040PostRunConfigComparison {
    static final String SWISS_RAIL_RAPTOR_MODULE = "swissRailRaptor";
    private static final Map<String, String> MATSim_2025_DEFAULTS = Map.ofEntries(
            Map.entry("intermodalAccessEgressModeSelection", "CalcLeastCostModePerStop"),
            Map.entry("intermodalLegOnlyHandling", "forbid"),
            Map.entry("scoringParameters", "Default"),
            Map.entry("transferCalculation", "Initial"),
            Map.entry("transferPenaltyBaseCost", "0.0"),
            Map.entry("transferPenaltyCostPerTravelTimeHour", "0.0"),
            Map.entry("transferPenaltyMaxCost", "Infinity"),
            Map.entry("transferPenaltyMinCost", "-Infinity"),
            Map.entry("transferWalkMargin", "5.0"),
            Map.entry("useCapacityConstraints", "false"),
            Map.entry("useIntermodalAccessEgress", "false"),
            Map.entry("useModeMappingForPassengers", "false"),
            Map.entry("useRangeQuery", "false"));

    private Production2040PostRunConfigComparison() { }

    static List<String> semanticConfigDifferences(Config expected, Config actual) {
        List<String> differences = new ArrayList<>(
                AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .semanticConfigDifferences(expected, actual));
        ConfigGroup expectedRaptor = expected.getModules().get(SWISS_RAIL_RAPTOR_MODULE);
        ConfigGroup actualRaptor = actual.getModules().get(SWISS_RAIL_RAPTOR_MODULE);
        if (expectedRaptor == null && isExactMatsim2025RuntimeDefault(actualRaptor)) {
            differences.removeIf(difference -> difference.startsWith(
                    SWISS_RAIL_RAPTOR_MODULE + ":"));
        }
        return differences;
    }

    static Map<String, String> expectedMatsim2025Defaults() {
        return MATSim_2025_DEFAULTS;
    }

    private static boolean isExactMatsim2025RuntimeDefault(ConfigGroup actual) {
        return actual != null && actual.getParams().equals(MATSim_2025_DEFAULTS)
                && actual.getParameterSets().isEmpty();
    }
}
