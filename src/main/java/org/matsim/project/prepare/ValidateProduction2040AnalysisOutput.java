package org.matsim.project.prepare;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/** Strict read-only gate for a completed 2040 production output. */
public final class ValidateProduction2040AnalysisOutput {
    private ValidateProduction2040AnalysisOutput() { }

    public static void main(String[] args) throws Exception {
        Production2040AnalysisSpec.require(args.length == 1,
                "Usage: ValidateProduction2040AnalysisOutput BAU|FAST_TRACK");
        var definition = Production2040AnalysisSpec.scenario(args[0]);
        ValidatedOutput output = validatePublished(definition);
        System.out.println("2040 PUBLISHED PRODUCTION ANALYSIS VALIDATION PASS");
        System.out.println("  scenario=" + definition.scenarioId());
        System.out.println("  output=" + Production2040Contract.projectPath(output.output()));
        System.out.println("No Controller or QSim was started by the validator.");
    }

    static ValidatedOutput validatePublished(
            Production2040AnalysisSpec.ScenarioDefinition definition) throws Exception {
        ValidatedOutput output = validate(definition, false);
        Production2040AnalysisSpec.require(Files.isDirectory(definition.analysisDirectory()),
                "Missing published production analysis");
        Map<String, String> reports = new LinkedHashMap<>();
        try (var files = Files.list(definition.analysisDirectory())) {
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
        validateReportBundle(definition, reports);
        String quality = reports.get("analysis_quality_checks.csv");
        Production2040AnalysisSpec.require(quality != null
                        && !quality.contains(",FAIL,") && !quality.contains(",WARN,"),
                "Published analysis contains a non-PASS quality check");
        return output;
    }

    static ValidatedOutput validate(Production2040AnalysisSpec.ScenarioDefinition definition,
            boolean requireAnalysisAbsent) throws Exception {
        ValidateProduction2040Configs.validateFiles(Production2040Contract.BAU.configPath(),
                Production2040Contract.FAST_TRACK.configPath(), false);
        var contract = Production2040Contract.loadAndValidate();
        Production2040Contract.requireHash(MunichMunicipalBoundary.DEFAULT_FILE,
                Production2040Contract.HashMethod.CANONICAL_UTF8_LF_SHA256,
                Production2040AnalysisSpec.BOUNDARY_HASH);

        Path output = definition.outputDirectory();
        Production2040AnalysisSpec.require(Files.isDirectory(output),
                "Missing production output directory: " + Production2040Contract.projectPath(output));
        if (requireAnalysisAbsent) Production2040AnalysisSpec.require(
                !Files.exists(definition.analysisDirectory()),
                "Published analysis already exists and will not be overwritten");
        Production2040AnalysisSpec.require(Files.isDirectory(definition.runtimeDirectory()),
                "Missing compact runtime analysis from the shared listener");

        String runId = definition.contract().runId();
        Path log = required(output.resolve(runId + ".logfile.log"), "normal-shutdown log");
        String logText = Files.readString(log, StandardCharsets.UTF_8);
        Production2040AnalysisSpec.require(logText.contains("shutdown completed."),
                "MATSim log contains no normal-shutdown evidence");

        Path outputConfig = required(output.resolve(runId + ".output_config.xml"), "output config");
        Config expected = ConfigUtils.loadConfig(definition.contract().configPath().toString());
        Config actual = ConfigUtils.loadConfig(outputConfig.toString());
        validateOutputConfig(expected, actual, runId);

        Path iterations = required(definition.runtimeDirectory().resolve(
                "iteration_mode_shares.csv"), "iteration history");
        List<Production2040AnalysisSpec.IterationSnapshot> rows = readIterations(
                iterations, definition.scenarioId());
        Map<String, Production2040AnalysisSpec.LateStatistic> late =
                Production2040AnalysisSpec.lateStatistics(rows);
        for (var entry : late.entrySet()) {
            Production2040AnalysisSpec.require(Math.abs(entry.getValue()
                            .linearTrendPpPerIteration())
                            < Production2040AnalysisSpec.MAX_ABS_LATE_TREND_PP,
                    "Late modal-share trend is unstable for " + entry.getKey());
            Production2040AnalysisSpec.require(entry.getValue().rangePercentagePoints()
                            <= Production2040AnalysisSpec.MAX_LATE_RANGE_PP,
                    "Late modal-share range is unstable for " + entry.getKey());
        }
        Path stuck = required(definition.runtimeDirectory().resolve(
                "stuck_events_by_iteration_and_mode.csv"), "stuck-event history");
        Map<Integer, StuckTotal> stuckTotals = validateStuckRows(stuck,
                definition.scenarioId());
        for (int iteration = Production2040AnalysisSpec.LATE_FIRST;
                iteration <= Production2040AnalysisSpec.LATE_LAST; iteration++) {
            long denominator = rows.get(iteration).bothInsideTrips();
            double incidence = Production2040AnalysisSpec.percent(
                    stuckTotals.get(iteration).uniqueRelevantPersons(), denominator);
            Production2040AnalysisSpec.require(incidence
                            <= Production2040AnalysisSpec.MAX_STUCK_INCIDENCE_PERCENT,
                    "Late stuck-person incidence exceeds the common quality limit in iteration "
                            + iteration + ": " + incidence + "%");
        }

        Path trips = required(output.resolve(runId + ".output_trips.csv.gz"), "final trips");
        Path plans = required(output.resolve(runId + ".output_plans.xml.gz"), "final plans");
        Path events = required(output.resolve(runId + ".output_events.xml.gz"), "final events");
        Path network = required(output.resolve(runId + ".output_network.xml.gz"), "output network");
        Path schedule = required(output.resolve(runId + ".output_transitSchedule.xml.gz"),
                "output transit schedule");
        Path vehicles = required(output.resolve(runId + ".output_transitVehicles.xml.gz"),
                "output transit vehicles");

        Map<Path, String> protectedSnapshot = Production2040Contract.protectedInputSnapshot(contract);
        return new ValidatedOutput(output, outputConfig, trips, plans, events, network,
                schedule, vehicles, iterations, stuck, rows, stuckTotals,
                protectedSnapshot);
    }

    static void validateOutputConfig(Config expected, Config actual, String runId) {
        List<String> differences = Production2040PostRunConfigComparison
                .semanticConfigDifferences(expected, actual);
        Production2040AnalysisSpec.require(differences.isEmpty(),
                "Production output config differs from the approved config:\n- "
                        + String.join("\n- ", differences));
        Production2040AnalysisSpec.require(runId.equals(actual.controller().getRunId()),
                "Output run ID does not match scenario");
    }

    static void validateReportBundle(
            Production2040AnalysisSpec.ScenarioDefinition definition,
            Map<String, String> reports) {
        Production2040AnalysisSpec.require(reports.keySet().equals(
                        Production2040AnalysisSpec.OUTPUT_FILES),
                "Analysis report set is incomplete or contains unknown files");
        String markdown = reports.get("analysis_report.md");
        Production2040AnalysisSpec.require(markdown != null && markdown.lines().findFirst()
                        .orElse("").equals(Production2040AnalysisSpec.reportHeading(definition)),
                "Published analysis report belongs to another scenario");
        for (var entry : reports.entrySet()) {
            Production2040AnalysisSpec.require(entry.getValue() != null
                            && !entry.getValue().isBlank(),
                    "Empty analysis report " + entry.getKey());
            if (!entry.getKey().endsWith(".csv")) continue;
            String[] lines = entry.getValue().split("\\R");
            Production2040AnalysisSpec.require(lines.length >= 2,
                    "CSV has no data rows: " + entry.getKey());
            List<String> header = AnalyzeLiteratureBasedScoringDiagnosticOutput
                    .parseCsvLine(lines[0], ',');
            Production2040AnalysisSpec.require(new java.util.HashSet<>(header).size()
                            == header.size(),
                    "Duplicate CSV header in " + entry.getKey());
            int scenarioColumn = header.indexOf("scenario_id");
            int sampleColumn = header.indexOf("sample_factor");
            int unitColumn = header.indexOf("unit");
            Production2040AnalysisSpec.require(scenarioColumn >= 0 && sampleColumn >= 0
                            && unitColumn >= 0,
                    "CSV lacks scenario_id, sample_factor or unit: " + entry.getKey());
            for (int row = 1; row < lines.length; row++) {
                if (lines[row].isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .parseCsvLine(lines[row], ',');
                Production2040AnalysisSpec.require(fields.size() == header.size(),
                        "Inconsistent column count in " + entry.getKey() + " row " + (row + 1));
                Production2040AnalysisSpec.require(definition.scenarioId().equals(
                                fields.get(scenarioColumn)),
                        "CSV row belongs to another scenario in " + entry.getKey());
                Production2040AnalysisSpec.require(!fields.get(sampleColumn).isBlank()
                                && !fields.get(unitColumn).isBlank(),
                        "CSV row lacks sample factor or unit in " + entry.getKey());
            }
        }
    }

    static List<Production2040AnalysisSpec.IterationSnapshot> readIterations(Path file,
            String expectedScenario) throws IOException {
        List<Production2040AnalysisSpec.IterationSnapshot> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            Production2040AnalysisSpec.require(header != null && header.equals(
                    "scenario_id,sample_factor,unit,iteration,both_inside_main_trips,car_sample_trips,car_share_percent,pt_sample_trips,pt_share_percent,bike_sample_trips,bike_share_percent,walk_sample_trips,walk_share_percent,unexpected_mode_sample_trips,unexpected_modes,definition"),
                    "Unexpected iteration-history header");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .parseCsvLine(line, ',');
                Production2040AnalysisSpec.require(fields.size() == 16,
                        "Malformed iteration-history row");
                Production2040AnalysisSpec.require(expectedScenario.equals(fields.get(0)),
                        "Iteration history belongs to another scenario");
                Production2040AnalysisSpec.require("0.05".equals(fields.get(1)),
                        "Wrong runtime sample factor");
                Map<String, Long> modes = new LinkedHashMap<>();
                modes.put("car", Long.parseLong(fields.get(5)));
                modes.put("pt", Long.parseLong(fields.get(7)));
                modes.put("bike", Long.parseLong(fields.get(9)));
                modes.put("walk", Long.parseLong(fields.get(11)));
                long denominator = Long.parseLong(fields.get(4));
                long unexpected = Long.parseLong(fields.get(13));
                Production2040AnalysisSpec.require(modes.values().stream()
                                .mapToLong(Long::longValue).sum() + unexpected == denominator,
                        "Iteration mode counts do not reconcile");
                Map<String, Long> unexpectedModes = unexpected == 0 ? Map.of()
                        : Map.of("reported_unexpected", unexpected);
                result.add(new Production2040AnalysisSpec.IterationSnapshot(
                        Integer.parseInt(fields.get(3)), denominator, Map.copyOf(modes),
                        unexpectedModes));
            }
        }
        Production2040AnalysisSpec.require(result.size() == 61,
                "Iteration history must contain exactly 61 rows");
        for (int index = 0; index <= 60; index++) {
            Production2040AnalysisSpec.require(result.get(index).iteration() == index,
                    "Missing or reordered iteration " + index);
            Production2040AnalysisSpec.require(result.get(index).unexpectedCount() == 0,
                    "Unexpected main mode in iteration " + index);
            Production2040AnalysisSpec.require(result.get(index).bothInsideTrips() > 0,
                    "Iteration has no BOTH_INSIDE main trips: " + index);
        }
        return List.copyOf(result);
    }

    private static Map<Integer, StuckTotal> validateStuckRows(Path file,
            String scenarioId) throws IOException {
        Map<Integer, StuckTotal> allRows = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            Production2040AnalysisSpec.require(header != null && header.startsWith(
                    "scenario_id,sample_factor,unit,iteration,mode,"),
                    "Unexpected stuck-event-history header");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = AnalyzeLiteratureBasedScoringDiagnosticOutput
                        .parseCsvLine(line, ',');
                Production2040AnalysisSpec.require(fields.size() == 10,
                        "Malformed stuck-event-history row");
                Production2040AnalysisSpec.require(scenarioId.equals(fields.get(0)),
                        "Stuck history belongs to another scenario");
                int iteration = Integer.parseInt(fields.get(3));
                if ("ALL".equals(fields.get(4))) {
                    StuckTotal total = new StuckTotal(Long.parseLong(fields.get(5)),
                            Long.parseLong(fields.get(6)), Long.parseLong(fields.get(7)),
                            Long.parseLong(fields.get(8)));
                    Production2040AnalysisSpec.require(allRows.put(iteration, total) == null,
                            "Duplicate ALL stuck row for iteration " + iteration);
                }
            }
        }
        Production2040AnalysisSpec.require(allRows.size() == 61,
                "Stuck history lacks one or more iterations");
        for (int iteration = 0; iteration <= 60; iteration++) {
            Production2040AnalysisSpec.require(allRows.containsKey(iteration),
                    "Stuck history must contain one ALL row for iteration " + iteration);
        }
        return Map.copyOf(allRows);
    }

    private static Path required(Path file, String label) {
        Production2040AnalysisSpec.require(Files.isRegularFile(file),
                "Missing " + label + ": " + Production2040Contract.projectPath(file));
        return file;
    }

    record ValidatedOutput(Path output, Path config, Path trips, Path plans,
                           Path events, Path network, Path schedule, Path vehicles,
                           Path iterations, Path stuck,
                           List<Production2040AnalysisSpec.IterationSnapshot> iterationRows,
                           Map<Integer, StuckTotal> stuckTotals,
                           Map<Path, String> protectedInputSnapshot) { }

    record StuckTotal(long events, long relevantEvents, long uniquePersons,
                      long uniqueRelevantPersons) { }
}
