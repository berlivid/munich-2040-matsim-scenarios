package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict in-memory gate applied before atomic accounting-scope publication. */
final class ValidateProduction2040AccountingScopes {
    static final Set<String> OUTPUT_FILES = Set.of(
            "accounting_scope_definition.csv",
            "final_modal_split_by_scope.csv",
            "final_pkm_by_scope_and_mode.csv",
            "final_private_car_fkm_by_scope.csv",
            "final_active_mode_distance_by_scope.csv",
            "final_territorial_pt_fkm_by_route_mode.csv",
            "resident_cohort_summary.csv",
            "accounting_scope_quality_checks.csv",
            "accounting_scope_report.md");

    private ValidateProduction2040AccountingScopes() { }

    /** Validates an already published accounting-scope package without writing it. */
    static Map<String, String> validatePublished(
            Production2040AnalysisSpec.ScenarioDefinition definition) throws IOException {
        var destination = definition.analysisDirectory().resolve(
                AnalyzeProduction2040AccountingScopes.SUBDIRECTORY);
        Production2040AnalysisSpec.require(Files.isDirectory(destination),
                "Missing published accounting-scope analysis: " + destination);
        Map<String, String> reports = new LinkedHashMap<>();
        try (var files = Files.list(destination)) {
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
                "Accounting-scope report set is partial or contains unknown files");
        String expectedHeading = "# " + definition.scenarioId().replace('_', ' ')
                + " accounting scopes";
        String markdown = reports.get("accounting_scope_report.md");
        Production2040AnalysisSpec.require(markdown != null && markdown.lines().findFirst()
                        .orElse("").equals(expectedHeading),
                "Accounting-scope report belongs to another scenario");
        for (var entry : reports.entrySet()) {
            Production2040AnalysisSpec.require(entry.getValue() != null
                            && !entry.getValue().isBlank(),
                    "Empty accounting-scope report " + entry.getKey());
            if (!entry.getKey().endsWith(".csv")) continue;
            String[] lines = entry.getValue().split("\\R");
            Production2040AnalysisSpec.require(lines.length >= 2,
                    "Accounting CSV has no rows: " + entry.getKey());
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                    lines[0], ',');
            Production2040AnalysisSpec.require(header.size() == Set.copyOf(header).size(),
                    "Duplicate accounting CSV header: " + entry.getKey());
            int scenarioColumn = header.indexOf("scenario_id");
            Production2040AnalysisSpec.require(scenarioColumn >= 0,
                    "Accounting CSV lacks scenario_id: " + entry.getKey());
            for (int row = 1; row < lines.length; row++) {
                if (lines[row].isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput.parseCsvLine(
                        lines[row], ',');
                Production2040AnalysisSpec.require(fields.size() == header.size(),
                        "Inconsistent accounting CSV row in " + entry.getKey());
                Production2040AnalysisSpec.require(definition.scenarioId().equals(
                                fields.get(scenarioColumn)),
                        "Accounting CSV row belongs to another scenario: " + entry.getKey());
            }
        }
        String quality = reports.get("accounting_scope_quality_checks.csv");
        Production2040AnalysisSpec.require(!quality.contains(",FAIL,")
                        && !quality.contains(",WARN,"),
                "Accounting-scope package contains a failed quality check");
        Production2040AnalysisSpec.require(reports.get(
                        "final_territorial_pt_fkm_by_route_mode.csv")
                        .contains(",NOT_APPLICABLE,"),
                "Territorial PT service must not contain factor-20 values");
        Production2040AnalysisSpec.require(reports.get(
                        "final_territorial_pt_fkm_by_route_mode.csv")
                        .contains("point_anchored_pseudolink_used_link_count")
                        && reports.get("final_territorial_pt_fkm_by_route_mode.csv")
                        .contains("point_anchored_pseudolink_territorial_service_km"),
                "Territorial PT service report lacks point-anchored pseudolink diagnostics");
        Production2040AnalysisSpec.require(quality.contains("point_anchored_pt_pseudolinks_used")
                        && quality.contains("point_anchored_pt_pseudolink_territorial_service_km")
                        && quality.contains("zero_model_length_pt_links_used"),
                "Accounting quality report lacks PT pseudolink diagnostics");
        Production2040AnalysisSpec.require(reports.get(
                        "final_active_mode_distance_by_scope.csv")
                        .contains(",walk,walk_person_km,")
                        && reports.get("final_active_mode_distance_by_scope.csv")
                        .contains(",NOT_APPLICABLE,"),
                "Walk distance must remain person-kilometres, not vehicle-kilometres");
    }
}
