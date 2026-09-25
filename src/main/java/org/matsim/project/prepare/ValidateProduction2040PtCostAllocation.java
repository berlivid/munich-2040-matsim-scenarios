package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only validator for an already published PT cost-allocation package. */
public final class ValidateProduction2040PtCostAllocation {
    static final Set<String> OUTPUT_FILES = Set.of(
            "pt_external_cost_inputs.csv",
            "pt_cost_allocation_quality_checks.csv",
            "pt_vehicle_unit_metadata.csv",
            "pt_cost_allocation_report.md");

    private ValidateProduction2040PtCostAllocation() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: ValidateProduction2040PtCostAllocation BAU|FAST_TRACK");
        var definition = Production2040AnalysisSpec.scenario(args[0]);
        validatePublished(definition);
        System.out.println("2040 PT COST-ALLOCATION VALIDATION PASS");
        System.out.println("  scenario=" + definition.scenarioId());
        System.out.println("No Controller or QSim was started by the validator.");
    }

    static Map<String, String> validatePublished(
            Production2040AnalysisSpec.ScenarioDefinition definition) throws Exception {
        ValidateProduction2040AnalysisOutput.validatePublished(definition);
        ValidateProduction2040AccountingScopes.validatePublished(definition);
        var directory = definition.analysisDirectory().resolve(
                AnalyzeProduction2040PtCostAllocation.SUBDIRECTORY);
        Production2040AnalysisSpec.require(Files.isDirectory(directory),
                "Missing published PT cost-allocation package " + directory);
        Map<String, String> reports = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    reports.put(file.getFileName().toString(),
                            Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
        validateBundle(definition, reports);
        return Map.copyOf(reports);
    }

    static void validateBundle(Production2040AnalysisSpec.ScenarioDefinition definition,
            Map<String, String> reports) {
        Production2040AnalysisSpec.require(reports.keySet().equals(OUTPUT_FILES),
                "PT cost-allocation report set is partial or contains unknown files");
        String markdown = reports.get("pt_cost_allocation_report.md");
        String expectedHeading = "# " + definition.scenarioId().replace('_', ' ')
                + " PT cost allocation";
        Production2040AnalysisSpec.require(markdown != null && markdown.lines().findFirst()
                        .orElse("").equals(expectedHeading),
                "PT cost-allocation report belongs to another scenario");
        for (var entry : reports.entrySet()) {
            Production2040AnalysisSpec.require(entry.getValue() != null
                            && !entry.getValue().isBlank(),
                    "Empty PT cost-allocation report " + entry.getKey());
            if (!entry.getKey().endsWith(".csv")) continue;
            String[] lines = entry.getValue().split("\\R");
            Production2040AnalysisSpec.require(lines.length >= 2,
                    "PT cost-allocation CSV has no rows: " + entry.getKey());
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                    lines[0], ',');
            Production2040AnalysisSpec.require(header.size() == Set.copyOf(header).size(),
                    "Duplicate PT cost-allocation CSV header: " + entry.getKey());
            int scenario = header.indexOf("scenario_id");
            Production2040AnalysisSpec.require(scenario >= 0,
                    "PT cost-allocation CSV lacks scenario_id: " + entry.getKey());
            for (int row = 1; row < lines.length; row++) {
                if (lines[row].isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                        lines[row], ',');
                Production2040AnalysisSpec.require(fields.size() == header.size(),
                        "Inconsistent PT cost-allocation CSV row in " + entry.getKey());
                Production2040AnalysisSpec.require(definition.scenarioId().equals(
                                fields.get(scenario)),
                        "PT cost-allocation CSV row belongs to another scenario: "
                                + entry.getKey());
            }
        }
        String inputs = reports.get("pt_external_cost_inputs.csv");
        Production2040AnalysisSpec.require(inputs.contains("demand_expansion_factor")
                        && inputs.contains("supply_expansion_factor")
                        && inputs.contains("average_occupancy_expanded_pkm_per_full_service_fkm")
                        && inputs.contains("allocated_both_inside_fkm_per_day")
                        && inputs.contains("occupancy_status")
                        && inputs.contains("allocation_status"),
                "PT cost-allocation inputs lack scaling, allocation, or zero-activity status");
        String quality = reports.get("pt_cost_allocation_quality_checks.csv");
        Production2040AnalysisSpec.require(!quality.contains(",FAIL,")
                        && !quality.contains(",WARN,"),
                "PT cost-allocation package contains a failed quality check");
        String vehicle = reports.get("pt_vehicle_unit_metadata.csv");
        Production2040AnalysisSpec.require(vehicle.contains("NOT_INFERRED")
                        && vehicle.contains("REQUIRES_EXTERNAL_UNIT_MAPPING"),
                "PT vehicle metadata must not infer a train-carriage or Excel unit conversion");
        Production2040AnalysisSpec.require(markdown.contains("Allocated Fkm may therefore exceed")
                        && markdown.contains("No such conversion has been inferred"),
                "PT cost-allocation report lacks required spatial-transfer or vehicle-unit limitation");
    }
}
