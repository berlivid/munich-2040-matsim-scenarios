package org.matsim.project.prepare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned contract reader and hash policy for the paired 2040 production configs. */
final class Production2040Contract {
    static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    static final Path ROUND_5_CONFIG = path("scenarios/munich_calibration_2019/config_literature_based_scoring_calibration_round_5.xml");
    static final Path SHARED_PARAMETERS = path("docs/methodology/production_2040_shared_parameters.csv");
    static final Path INPUT_MANIFEST = path("docs/methodology/production_2040_input_manifest.csv");
    static final Path ALLOWED_DIFFERENCES = path("docs/methodology/production_2040_allowed_differences.csv");
    static final Path SCENARIO_CONTRACT = path("docs/methodology/production_2040_scenario_contract.md");
    static final String ROUND_5_SHA256 = "F15CAF50F3FAAF6C13EACB874405C3ABA65BB12419D11918D5CB21D48F4EA25A";
    static final String ROUND_5_SOURCE = "scenarios/munich_calibration_2019/config_literature_based_scoring_calibration_round_5.xml";

    static final ScenarioSpec BAU = new ScenarioSpec(
            "BAU", path("scenarios/munich_bau_2040/config_bau_2040_mode_choice.xml"),
            "munich-bau-2040-mode-choice", "scenarios/munich_bau_2040/output/production-mode-choice",
            "scenarios/munich_bau_2040/population_2040.xml",
            "scenarios/munich_bau_2040/input_transit/network-with-pt.xml.gz",
            "scenarios/munich_bau_2040/input_transit/transitSchedule.xml.gz",
            "scenarios/munich_bau_2040/input_transit/transitVehicles.xml.gz");
    static final ScenarioSpec FAST_TRACK = new ScenarioSpec(
            "Fast Track", path("scenarios/munich_fast_track_2040/config_fast_track_2040_mode_choice.xml"),
            "munich-fast-track-2040-mode-choice", "scenarios/munich_fast_track_2040/output/production-mode-choice",
            "scenarios/munich_fast_track_2040/population_2040_fast_track.xml",
            "scenarios/munich_fast_track_2040/input_transit/network-with-pt.xml.gz",
            "scenarios/munich_fast_track_2040/input_transit/transitSchedule.xml.gz",
            "scenarios/munich_fast_track_2040/input_transit/transitVehicles.xml.gz");

    private static final Set<String> EXPECTED_ALLOWED = Set.of(
            "run_id", "output_directory", "population_file", "population_olympic_village",
            "population_media_village", "combined_network_file", "pedestrian_zone_links",
            "transit_schedule_file", "fast_track_u9", "fast_track_u4_extension",
            "fast_track_nordring", "mobility_hub_transfers", "transit_vehicle_file");
    private static final Set<String> EXPECTED_NOT_ALLOWED = Set.of(
            "scoring_and_asc", "mode_choice", "strategies", "random_seed", "iterations",
            "innovation_switch", "qsim_horizon", "sample_and_capacity", "coordinate_system",
            "analysis_scope", "scaling", "unlisted_difference");
    private static final Set<String> EXPECTED_CANONICAL_MANIFEST_TEXT_PATHS = Set.of(
            "scenarios/munich_bau_2040/config_bau.xml",
            "scenarios/munich_fast_track_2040/config_fast_track.xml",
            "original-input-data/munich-demography/munich_boundary.json",
            "original-input-data/mvv_gtfs_2037/common_service_specification.csv",
            "original-input-data/mvv_gtfs_2037/fast_track_service_specification.csv",
            "original-input-data/mvv_gtfs_2037/fast_track_pedestrian_zone_links.csv",
            "original-input-data/fast_track_2040_sources/mobility_hubs/approved_mobility_hubs.csv");
    private static final Map<String, String> EXPECTED_NON_CONFIG_PARAMETERS = Map.ofEntries(
            Map.entry("analysis.population", "complete regional five-percent population"),
            Map.entry("analysis.scope", "BOTH_INSIDE"),
            Map.entry("analysis.boundary_rule", "covers"),
            Map.entry("analysis.trip_definition", "MATSim TripStructureUtils with official stage-activity handling"),
            Map.entry("analysis.main_mode", "MATSim standard analysis main-mode identifier"),
            Map.entry("analysis.modes", "car,pt,bike,walk"),
            Map.entry("analysis.sample_factor", "0.05"),
            Map.entry("analysis.expansion_factor", "20.0"),
            Map.entry("analysis.share_scaling", "none"),
            Map.entry("analysis.reporting", "sample and expanded"),
            Map.entry("target.trip.car", "34.0"),
            Map.entry("target.trip.pt", "24.0"),
            Map.entry("target.trip.bike", "18.0"),
            Map.entry("target.trip.walk", "24.0"));

    private Production2040Contract() { }

    static ContractData loadAndValidate() {
        try {
            require(Files.isRegularFile(SCENARIO_CONTRACT), "Missing production scenario contract");
            requireRound5ConfigHash(ROUND_5_CONFIG);

            List<Map<String, String>> sharedRows = readCsv(SHARED_PARAMETERS);
            require(sharedRows.size() == 149, "Expected 149 shared parameters but found " + sharedRows.size());
            Map<String, SharedParameter> shared = new LinkedHashMap<>();
            for (Map<String, String> row : sharedRows) {
                SharedParameter parameter = new SharedParameter(row.get("parameter_key"), row.get("required_value"),
                        row.get("authoritative_source"), row.get("production_rule"));
                require(parameter.key() != null && !parameter.key().isBlank(), "Blank shared-parameter key");
                require(shared.put(parameter.key(), parameter) == null, "Duplicate shared parameter " + parameter.key());
            }
            requireValue(shared, "calibration.asc.car", "-0.35175057259662179");
            requireValue(shared, "calibration.asc.pt", "0.16187543976517921");
            requireValue(shared, "calibration.asc.bike", "-1.2617442557140233");
            requireValue(shared, "calibration.asc.walk", "0.0");
            for (var entry : EXPECTED_NON_CONFIG_PARAMETERS.entrySet()) requireValue(shared, entry.getKey(), entry.getValue());

            List<Map<String, String>> manifestRows = readCsv(INPUT_MANIFEST);
            require(manifestRows.size() == 13, "Expected 13 manifest entries but found " + manifestRows.size());
            Map<String, ManifestEntry> manifest = new LinkedHashMap<>();
            Set<String> canonicalTextPaths = new HashSet<>();
            for (Map<String, String> row : manifestRows) {
                ManifestEntry entry = new ManifestEntry(row.get("artifact_id"), row.get("bau_path"),
                        row.get("fast_track_path"), HashMethod.valueOf(row.get("hash_method")),
                        row.get("bau_sha256"), row.get("fast_track_sha256"));
                require(manifest.put(entry.id(), entry) == null, "Duplicate manifest artifact " + entry.id());
                verifyManifestSide(entry, false);
                verifyManifestSide(entry, true);
                addCanonicalManifestPath(canonicalTextPaths, entry, entry.bauPath());
                addCanonicalManifestPath(canonicalTextPaths, entry, entry.fastTrackPath());
            }
            require(canonicalTextPaths.equals(EXPECTED_CANONICAL_MANIFEST_TEXT_PATHS),
                    "Canonical manifest text-input set differs from the frozen contract");
            requireManifestPath(manifest, "scenario_population", BAU.populationPath(), FAST_TRACK.populationPath());
            requireManifestPath(manifest, "combined_network", BAU.networkPath(), FAST_TRACK.networkPath());
            requireManifestPath(manifest, "transit_schedule", BAU.schedulePath(), FAST_TRACK.schedulePath());
            requireManifestPath(manifest, "transit_vehicles", BAU.vehiclesPath(), FAST_TRACK.vehiclesPath());

            List<Map<String, String>> allowRows = readCsv(ALLOWED_DIFFERENCES);
            Set<String> allowed = new HashSet<>();
            Set<String> notAllowed = new HashSet<>();
            for (Map<String, String> row : allowRows) {
                String id = row.get("difference_id");
                String status = row.get("status");
                require("ALLOWED_DIFFERENCE".equals(status) || "NOT_ALLOWED".equals(status)
                                || "UNRESOLVED".equals(status),
                        "Unknown allowlist status for " + id + ": " + status);
                Set<String> target = "ALLOWED_DIFFERENCE".equals(status) ? allowed : notAllowed;
                require(target.add(id), "Duplicate allowlist difference " + id);
            }
            require(allowed.equals(EXPECTED_ALLOWED), "Allowed-difference set differs from the frozen contract");
            require(notAllowed.equals(EXPECTED_NOT_ALLOWED), "Prohibited-difference set differs from the frozen contract");
            return new ContractData(Map.copyOf(shared), Map.copyOf(manifest), Set.copyOf(allowed));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the production contract", error);
        }
    }

    static Map<Path, String> protectedInputSnapshot(ContractData data) {
        Map<Path, HashMethod> methods = new LinkedHashMap<>();
        for (ManifestEntry entry : data.manifest().values()) {
            addSnapshotPath(methods, entry.bauPath(), entry.hashMethod());
            addSnapshotPath(methods, entry.fastTrackPath(), entry.hashMethod());
        }
        methods.put(ROUND_5_CONFIG, HashMethod.CANONICAL_UTF8_LF_SHA256);
        Map<Path, String> result = new LinkedHashMap<>();
        for (var entry : methods.entrySet()) result.put(entry.getKey(), sha256(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    static String sha256(Path file, HashMethod method) {
        require(Files.isRegularFile(file), "Missing protected file " + projectPath(file));
        MessageDigest digest = sha256Digest();
        try {
            if (method == HashMethod.CANONICAL_UTF8_LF_SHA256) {
                byte[] bytes = Files.readAllBytes(file);
                String text = decodeUtf8Strict(bytes).replace("\r\n", "\n").replace('\r', '\n');
                digest.update(text.getBytes(StandardCharsets.UTF_8));
            } else {
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[1 << 20];
                    for (int count; (count = input.read(buffer)) >= 0; ) if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (IOException error) {
            throw new IllegalStateException("Could not hash " + projectPath(file), error);
        }
    }

    static boolean valuesEqual(String expected, String actual) {
        if (expected.equals(actual)) return true;
        try {
            return Double.doubleToLongBits(Double.parseDouble(expected))
                    == Double.doubleToLongBits(Double.parseDouble(actual));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static void requireHash(Path file, HashMethod method, String expected) {
        require(expected.equals(sha256(file, method)), "Hash mismatch for " + projectPath(file));
    }

    static void requireRound5ConfigHash(Path file) {
        requireHash(file, HashMethod.CANONICAL_UTF8_LF_SHA256, ROUND_5_SHA256);
    }

    static Map<String, String> expectedNonConfigParameters() {
        return EXPECTED_NON_CONFIG_PARAMETERS;
    }

    static Set<String> expectedCanonicalManifestTextPaths() {
        return EXPECTED_CANONICAL_MANIFEST_TEXT_PATHS;
    }

    static String projectPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(ROOT) ? ROOT.relativize(normalized).toString().replace('\\', '/') : normalized.toString();
    }

    static Path path(String projectRelative) {
        Path value = Path.of(projectRelative);
        require(!value.isAbsolute(), "Contract paths must be project-relative: " + projectRelative);
        Path resolved = ROOT.resolve(value).normalize();
        require(resolved.startsWith(ROOT), "Contract path escapes the project: " + projectRelative);
        return resolved;
    }

    private static void requireValue(Map<String, SharedParameter> shared, String key, String value) {
        SharedParameter actual = shared.get(key);
        require(actual != null && value.equals(actual.value()), "Unexpected contract value for " + key);
    }

    private static void requireManifestPath(Map<String, ManifestEntry> manifest, String id, String bau, String fast) {
        ManifestEntry entry = manifest.get(id);
        require(entry != null && bau.equals(entry.bauPath()) && fast.equals(entry.fastTrackPath()),
                "Manifest scenario path mismatch for " + id);
    }

    private static void verifyManifestSide(ManifestEntry entry, boolean fastTrack) {
        String value = fastTrack ? entry.fastTrackPath() : entry.bauPath();
        String expected = fastTrack ? entry.fastTrackSha256() : entry.bauSha256();
        if ("NOT_APPLICABLE".equals(value)) {
            require("NOT_APPLICABLE".equals(expected), "Hash must be NOT_APPLICABLE for " + entry.id());
            return;
        }
        Path file = path(value);
        require(expected.equals(sha256(file, entry.hashMethod())), "Hash mismatch for " + value);
    }

    private static void addSnapshotPath(Map<Path, HashMethod> target, String value, HashMethod method) {
        if ("NOT_APPLICABLE".equals(value)) return;
        Path file = path(value);
        HashMethod old = target.put(file, method);
        require(old == null || old == method, "Conflicting hash methods for " + value);
    }

    private static void addCanonicalManifestPath(Set<String> target, ManifestEntry entry,
            String value) {
        if ("NOT_APPLICABLE".equals(value)) return;
        boolean expectedCanonical = EXPECTED_CANONICAL_MANIFEST_TEXT_PATHS.contains(value);
        require((entry.hashMethod() == HashMethod.CANONICAL_UTF8_LF_SHA256)
                        == expectedCanonical,
                "Wrong hash method for manifest input " + value);
        if (expectedCanonical) target.add(value);
    }

    private static List<Map<String, String>> readCsv(Path file) throws IOException {
        List<List<String>> records = parseCsv(Files.readString(file, StandardCharsets.UTF_8));
        require(!records.isEmpty(), "Empty CSV " + projectPath(file));
        List<String> header = records.getFirst();
        require(new HashSet<>(header).size() == header.size(), "Duplicate CSV header in " + projectPath(file));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> record = records.get(index);
            if (record.size() == 1 && record.getFirst().isEmpty()) continue;
            require(record.size() == header.size(), "Malformed CSV row " + (index + 1) + " in " + projectPath(file));
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) row.put(header.get(column), record.get(column));
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static List<List<String>> parseCsv(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < input.length() && input.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else quoted = false;
                } else field.append(character);
            } else if (character == '"') {
                require(field.isEmpty(), "Quote inside an unquoted CSV field");
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') index++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else field.append(character);
        }
        require(!quoted, "Unclosed quoted CSV field");
        if (!row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static String decodeUtf8Strict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalStateException("Canonical text input is not valid UTF-8", error);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    enum HashMethod { RAW_SHA256, CANONICAL_UTF8_LF_SHA256 }

    record ScenarioSpec(String label, Path configPath, String runId, String outputDirectory,
                        String populationPath, String networkPath, String schedulePath, String vehiclesPath) { }

    record SharedParameter(String key, String value, String authoritativeSource, String productionRule) { }

    record ManifestEntry(String id, String bauPath, String fastTrackPath, HashMethod hashMethod,
                         String bauSha256, String fastTrackSha256) { }

    record ContractData(Map<String, SharedParameter> shared, Map<String, ManifestEntry> manifest,
                        Set<String> allowedDifferences) { }
}
