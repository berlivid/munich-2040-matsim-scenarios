package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared, scenario-neutral definitions for the 2040 production analysis. */
public final class Production2040AnalysisSpec {
    public static final List<String> MAIN_MODES = List.of("car", "pt", "bike", "walk");
    public static final List<String> PT_ROUTE_MODES = List.of("bus", "tram", "subway", "rail");
    public static final Set<String> OUTPUT_FILES = Set.of(
            "iteration_mode_shares.csv", "late_iteration_statistics.csv",
            "final_main_mode_summary.csv", "final_pkm_by_main_mode.csv",
            "final_car_fkm.csv", "final_pt_pkm_by_route_mode.csv",
            "final_pt_fkm_by_route_mode.csv", "stuck_events_by_iteration_and_mode.csv",
            "analysis_quality_checks.csv", "analysis_report.md");
    public static final int FIRST_ITERATION = 0;
    public static final int LAST_ITERATION = 60;
    public static final int LATE_FIRST = 51;
    public static final int LATE_LAST = 60;
    public static final double SAMPLE_FACTOR = 0.05;
    public static final double EXPANSION_FACTOR = 20.0;
    public static final double MIN_MEASUREMENT_COVERAGE_PERCENT = 99.0;
    public static final double MAX_STUCK_INCIDENCE_PERCENT = 0.10;
    public static final double MAX_ABS_LATE_TREND_PP = 0.10;
    public static final double MAX_LATE_RANGE_PP = 2.0;
    public static final String BOUNDARY_HASH =
            "EFBC37F0627F94D95DAB67D1C5A2B9D05507DC9E8C9492A98A35BFF4A4AE2A26";

    private Production2040AnalysisSpec() { }

    public static ScenarioDefinition scenario(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
        return switch (normalized) {
            case "bau", "bau-2040" -> definition(Production2040Contract.BAU, "BAU_2040");
            case "fast-track", "fast-track-2040", "fasttrack" ->
                    definition(Production2040Contract.FAST_TRACK, "FAST_TRACK_2040");
            default -> throw new IllegalArgumentException(
                    "Scenario must be BAU or FAST_TRACK, not: " + value);
        };
    }

    static ScenarioDefinition definition(Production2040Contract.ScenarioSpec source,
            String scenarioId) {
        Path output = Production2040Contract.path(source.outputDirectory());
        return new ScenarioDefinition(scenarioId, source, output,
                output.resolve("analysis-runtime"), output.resolve("analysis"));
    }

    public static String normalizeMainMode(String mode) {
        return mode == null || mode.isBlank() ? "unknown"
                : mode.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizePtRouteMode(String mode) {
        String normalized = normalizeMainMode(mode);
        return switch (normalized) {
            case "u-bahn", "underground", "metro" -> "subway";
            case "s-bahn", "regional_rail", "regionalrail", "train" -> "rail";
            default -> normalized;
        };
    }

    public static double percent(double part, double total) {
        return total == 0 ? 0.0 : part * 100.0 / total;
    }

    public static double expanded(double sampleValue) {
        return sampleValue * EXPANSION_FACTOR;
    }

    static String reportHeading(ScenarioDefinition definition) {
        return "# " + definition.scenarioId().replace('_', ' ')
                + " production analysis";
    }

    public static Map<String, LateStatistic> lateStatistics(List<IterationSnapshot> rows) {
        require(rows.size() == LAST_ITERATION - FIRST_ITERATION + 1,
                "Iteration history must contain exactly iterations 0..60");
        for (int iteration = FIRST_ITERATION; iteration <= LAST_ITERATION; iteration++) {
            require(rows.get(iteration).iteration() == iteration,
                    "Missing or reordered iteration " + iteration);
        }
        java.util.LinkedHashMap<String, LateStatistic> result = new java.util.LinkedHashMap<>();
        for (String mode : MAIN_MODES) {
            List<Point> points = rows.stream()
                    .filter(row -> row.iteration() >= LATE_FIRST && row.iteration() <= LATE_LAST)
                    .map(row -> new Point(row.iteration(), row.share(mode))).toList();
            require(points.size() == LATE_LAST - LATE_FIRST + 1,
                    "Incomplete late window for " + mode);
            double mean = points.stream().mapToDouble(Point::value).average().orElseThrow();
            double min = points.stream().mapToDouble(Point::value).min().orElseThrow();
            double max = points.stream().mapToDouble(Point::value).max().orElseThrow();
            double meanIteration = points.stream().mapToDouble(Point::iteration)
                    .average().orElseThrow();
            double numerator = points.stream().mapToDouble(point ->
                    (point.iteration() - meanIteration) * (point.value() - mean)).sum();
            double denominator = points.stream().mapToDouble(point ->
                    Math.pow(point.iteration() - meanIteration, 2)).sum();
            result.put(mode, new LateStatistic(mean, min, max, max - min,
                    numerator / denominator));
        }
        return Map.copyOf(result);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record ScenarioDefinition(String scenarioId,
                                     Production2040Contract.ScenarioSpec contract,
                                     Path outputDirectory, Path runtimeDirectory,
                                     Path analysisDirectory) { }

    public record IterationSnapshot(int iteration, long bothInsideTrips,
                                    Map<String, Long> modeCounts,
                                    Map<String, Long> unexpectedModes) {
        public long count(String mode) { return modeCounts.getOrDefault(mode, 0L); }
        public double share(String mode) { return percent(count(mode), bothInsideTrips); }
        public long unexpectedCount() {
            return unexpectedModes.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    public record LateStatistic(double meanSharePercent, double minimumSharePercent,
                                double maximumSharePercent, double rangePercentagePoints,
                                double linearTrendPpPerIteration) { }

    public record Point(int iteration, double value) { }
}
