package org.matsim.project.prepare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

/** Strict read-only structural validator for the paired 2040 production configs. */
public final class ValidateProduction2040Configs {
    private static final List<Binding> DIRECT_BINDINGS = List.of(
            binding("controller.first_iteration", "controller", "firstIteration"),
            binding("controller.last_iteration", "controller", "lastIteration"),
            binding("controller.mobsim", "controller", "mobsim"),
            binding("controller.overwrite", "controller", "overwriteFiles"),
            binding("controller.routing_algorithm", "controller", "routingAlgorithmType"),
            binding("controller.write_events_interval", "controller", "writeEventsInterval"),
            binding("controller.write_plans_interval", "controller", "writePlansInterval"),
            binding("controller.write_snapshots_interval", "controller", "writeSnapshotsInterval"),
            binding("controller.write_trips_interval", "controller", "writeTripsInterval"),
            binding("controller.create_graphs_interval", "controller", "createGraphsInterval"),
            binding("controller.dump_data_at_end", "controller", "dumpDataAtEnd"),
            binding("global.seed", "global", "randomSeed"),
            binding("global.threads", "global", "numberOfThreads"),
            binding("global.crs", "global", "coordinateSystem"),
            binding("qsim.threads", "qsim", "numberOfThreads"),
            binding("plans.activity_duration_interpretation", "plans", "activityDurationInterpretation"),
            binding("plans.missing_routing_mode", "plans", "handlingOfPlansWithoutRoutingMode"),
            binding("plans.network_route_type", "plans", "networkRouteType"),
            binding("plans.trip_duration_handling", "plans", "tripDurationHandling"),
            binding("qsim.end_time", "qsim", "endTime"),
            binding("qsim.flow_factor", "qsim", "flowCapacityFactor"),
            binding("qsim.storage_factor", "qsim", "storageCapacityFactor"),
            binding("qsim.main_mode", "qsim", "mainMode"),
            binding("qsim.waiting_vehicle_order", "qsim", "insertingWaitingVehiclesBeforeDrivingVehicles"),
            binding("qsim.remove_stuck", "qsim", "removeStuckVehicles"),
            binding("qsim.stuck_time", "qsim", "stuckTime"),
            binding("qsim.missing_vehicle_id", "qsim", "usePersonIdForMissingVehicleId"),
            binding("qsim.vehicle_behavior", "qsim", "vehicleBehavior"),
            binding("qsim.vehicles_source", "qsim", "vehiclesSource"),
            binding("strategy.plan_memory", "replanning", "maxAgentPlanMemorySize"),
            binding("strategy.plan_removal", "replanning", "planSelectorForRemoval"),
            binding("strategy.disable_fraction", "replanning", "fractionOfIterationsToDisableInnovation"),
            binding("choice.behavior", "subtourModeChoice", "behavior"),
            binding("choice.modes", "subtourModeChoice", "modes"),
            binding("choice.chain_modes", "subtourModeChoice", "chainBasedModes"),
            binding("choice.car_availability", "subtourModeChoice", "considerCarAvailability"),
            binding("choice.random_single_trip", "subtourModeChoice", "probaForRandomSingleTripMode"),
            binding("choice.coord_distance", "subtourModeChoice", "coordDistance"),
            binding("routing.network_modes", "routing", "networkModes"),
            binding("routing.access_egress", "routing", "accessEgressType"),
            binding("routing.clear_default_teleported", "routing", "clearDefaultTeleportedModeParams"),
            binding("routing.consistency", "routing", "networkRouteConsistencyCheck"),
            binding("routing.randomness", "routing", "routingRandomness"),
            binding("transit.algorithm", "transit", "routingAlgorithmType"),
            binding("transit.use_transit", "transit", "useTransit"),
            binding("transit.use_in_mobsim", "transit", "usingTransitInMobsim"),
            binding("transit.modes", "transit", "transitModes"),
            binding("transit.transfer", "transitRouter", "additionalTransferTime"),
            binding("transit.direct_walk", "transitRouter", "directWalkFactor"),
            binding("transit.extension_radius", "transitRouter", "extensionRadius"),
            binding("transit.max_walk_connection", "transitRouter", "maxBeelineWalkConnectionDistance"),
            binding("transit.search_radius", "transitRouter", "searchRadius"),
            binding("travel_time.modes", "travelTimeCalculator", "analyzedModes"),
            binding("travel_time.calculate", "travelTimeCalculator", "calculateLinkTravelTimes"),
            binding("travel_time.filter_modes", "travelTimeCalculator", "filterModes"),
            binding("travel_time.max_time", "travelTimeCalculator", "maxTime"),
            binding("travel_time.separate_modes", "travelTimeCalculator", "separateModes"),
            binding("travel_time.aggregator", "travelTimeCalculator", "travelTimeAggregator"),
            binding("travel_time.bin_size", "travelTimeCalculator", "travelTimeBinSize"),
            binding("travel_time.getter", "travelTimeCalculator", "travelTimeGetter"),
            binding("vsp.defaults_checking", "vspExperimental", "vspDefaultsCheckingLevel"),
            binding("scoring.brainExpBeta", "scoring", "brainExpBeta"),
            binding("scoring.learningRate", "scoring", "learningRate"));

    private static final Map<String, Set<String>> ALLOWED_FIELDS = Map.of(
            "controller", Set.of("runId", "outputDirectory"),
            "network", Set.of("inputNetworkFile"),
            "plans", Set.of("inputPlansFile"),
            "transit", Set.of("transitScheduleFile", "vehiclesFile"));

    private ValidateProduction2040Configs() { }

    public static void main(String[] args) {
        Production2040Contract.require(args.length == 0, "This validator does not accept arguments");
        validateFiles(Production2040Contract.BAU.configPath(), Production2040Contract.FAST_TRACK.configPath(), true);
    }

    static void validateFiles(Path bauFile, Path fastTrackFile, boolean requireOutputAbsent) {
        Production2040Contract.ContractData contract = Production2040Contract.loadAndValidate();
        Production2040Contract.require(Files.isRegularFile(bauFile), "Missing BAU production config " + bauFile);
        Production2040Contract.require(Files.isRegularFile(fastTrackFile), "Missing Fast Track production config " + fastTrackFile);
        Map<Path, String> protectedBefore = Production2040Contract.protectedInputSnapshot(contract);
        String bauBefore = Production2040Contract.sha256(bauFile, Production2040Contract.HashMethod.RAW_SHA256);
        String fastBefore = Production2040Contract.sha256(fastTrackFile, Production2040Contract.HashMethod.RAW_SHA256);
        Config bau = ConfigUtils.loadConfig(bauFile.toString());
        Config fastTrack = ConfigUtils.loadConfig(fastTrackFile.toString());
        validateLoadedConfigs(bau, fastTrack, requireOutputAbsent);
        Production2040Contract.require(bauBefore.equals(Production2040Contract.sha256(
                        bauFile, Production2040Contract.HashMethod.RAW_SHA256)),
                "Validator changed the BAU config");
        Production2040Contract.require(fastBefore.equals(Production2040Contract.sha256(
                        fastTrackFile, Production2040Contract.HashMethod.RAW_SHA256)),
                "Validator changed the Fast Track config");
        Production2040Contract.require(protectedBefore.equals(Production2040Contract.protectedInputSnapshot(contract)),
                "Validator changed a protected input");
        System.out.println("2040 PRODUCTION CONFIG VALIDATION PASS");
        System.out.println("  shared_parameters=149 config_backed=135 analysis_and_scaling=14");
        System.out.println("  allowed_differences=" + contract.allowedDifferences().size());
    }

    static void validateLoadedConfigs(Config bau, Config fastTrack, boolean requireOutputAbsent) {
        Production2040Contract.ContractData contract = Production2040Contract.loadAndValidate();
        bau.checkConsistency();
        fastTrack.checkConsistency();
        validateScenario(bau, Production2040Contract.BAU, contract, requireOutputAbsent);
        validateScenario(fastTrack, Production2040Contract.FAST_TRACK, contract, requireOutputAbsent);
        validateSharedParameters(bau, contract);
        validateSharedParameters(fastTrack, contract);

        Config round5 = ConfigUtils.loadConfig(Production2040Contract.ROUND_5_CONFIG.toString());
        String reference = canonicalConfig(round5);
        Production2040Contract.require(reference.equals(canonicalConfig(bau)),
                "BAU differs from Round 5 outside the permitted scenario fields");
        Production2040Contract.require(reference.equals(canonicalConfig(fastTrack)),
                "Fast Track differs from Round 5 outside the permitted scenario fields");
        Production2040Contract.require(canonicalConfig(bau).equals(canonicalConfig(fastTrack)),
                "BAU and Fast Track contain an unknown or prohibited semantic difference");
        Production2040Contract.require(!bau.controller().getRunId().equals(fastTrack.controller().getRunId()),
                "Run IDs must differ");
        Production2040Contract.require(!normalizedPath(bau.controller().getOutputDirectory()).equals(
                        normalizedPath(fastTrack.controller().getOutputDirectory())),
                "Output directories must differ");
    }

    private static void validateScenario(Config config, Production2040Contract.ScenarioSpec specification,
                                         Production2040Contract.ContractData contract, boolean requireOutputAbsent) {
        Production2040Contract.require(specification.runId().equals(config.controller().getRunId()),
                specification.label() + " has the wrong run ID");
        Production2040Contract.require(normalizedPath(specification.outputDirectory()).equals(
                        normalizedPath(config.controller().getOutputDirectory())),
                specification.label() + " has the wrong output directory");
        Production2040Contract.require(config.controller().getOverwriteFileSetting()
                        == OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                specification.label() + " output is not protected by failIfDirectoryExists");
        Production2040Contract.require(!Path.of(config.controller().getOutputDirectory()).isAbsolute(),
                specification.label() + " output directory must be project-relative");
        if (requireOutputAbsent) {
            Production2040Contract.require(!Files.exists(Production2040Contract.path(specification.outputDirectory())),
                    specification.label() + " output directory already exists");
        }

        requireInput(config.plans().getInputFile(), relativeInput(specification, specification.populationPath()),
                specification.label() + " population");
        requireInput(config.network().getInputFile(), relativeInput(specification, specification.networkPath()),
                specification.label() + " network");
        requireInput(config.transit().getTransitScheduleFile(), relativeInput(specification, specification.schedulePath()),
                specification.label() + " schedule");
        requireInput(config.transit().getVehiclesFile(), relativeInput(specification, specification.vehiclesPath()),
                specification.label() + " transit vehicles");
        for (String input : List.of(config.plans().getInputFile(), config.network().getInputFile(),
                config.transit().getTransitScheduleFile(), config.transit().getVehiclesFile())) {
            String normalized = normalizedPath(input).toLowerCase();
            Production2040Contract.require(!normalized.contains("munich_calibration_2019")
                            && !normalized.contains("munich-v1.0-5pct.plans.xml"),
                    specification.label() + " retains a calibration-specific 2019 input path: " + input);
        }
        Production2040Contract.require(config.transit().isUseTransit(), specification.label() + " has useTransit=false");
        Production2040Contract.require(config.transit().isUsingTransitInMobsim(),
                specification.label() + " does not use transit in mobsim");
        Production2040Contract.require(config.qsim().getFlowCapFactor() == 0.05
                        && config.qsim().getStorageCapFactor() == 0.05,
                specification.label() + " does not use the five-percent capacity factors");
        Production2040Contract.require(Set.of(config.subtourModeChoice().getModes()).equals(
                        Set.of("car", "pt", "walk", "bike")),
                specification.label() + " has the wrong mode-choice alternatives");
        Production2040Contract.require(!Set.of(config.subtourModeChoice().getModes()).contains("ride"),
                specification.label() + " offers ride as a choice alternative");
        Production2040Contract.require(config.scoring().getModes().get("walk").getConstant() == 0.0,
                specification.label() + " does not retain walk as the zero-ASC reference");

        verifyScenarioManifest(specification, contract);
    }

    private static void verifyScenarioManifest(Production2040Contract.ScenarioSpec specification,
                                               Production2040Contract.ContractData contract) {
        boolean fast = specification == Production2040Contract.FAST_TRACK;
        for (String id : List.of("scenario_population", "combined_network", "transit_schedule", "transit_vehicles")) {
            Production2040Contract.ManifestEntry entry = contract.manifest().get(id);
            String input = fast ? entry.fastTrackPath() : entry.bauPath();
            String expected = fast ? entry.fastTrackSha256() : entry.bauSha256();
            Production2040Contract.requireHash(Production2040Contract.path(input), entry.hashMethod(), expected);
        }
    }

    private static void validateSharedParameters(Config config, Production2040Contract.ContractData contract) {
        Map<String, String> actual = extractConfigBackedSharedParameters(config);
        Production2040Contract.require(actual.size() == 135,
                "Expected 135 config-backed shared values but extracted " + actual.size());
        Set<String> recognized = new HashSet<>(actual.keySet());
        recognized.addAll(Production2040Contract.expectedNonConfigParameters().keySet());
        Set<String> missing = new HashSet<>(contract.shared().keySet());
        missing.removeAll(recognized);
        Set<String> unexpected = new HashSet<>(recognized);
        unexpected.removeAll(contract.shared().keySet());
        Production2040Contract.require(recognized.equals(contract.shared().keySet()),
                "Shared-parameter key mismatch; missing=" + missing + " unexpected=" + unexpected);
        for (Production2040Contract.SharedParameter parameter : contract.shared().values()) {
            if (!Production2040Contract.ROUND_5_SOURCE.equals(parameter.authoritativeSource())) continue;
            String value = actual.get(parameter.key());
            Production2040Contract.require(value != null
                            && Production2040Contract.valuesEqual(parameter.value(), value),
                    "Shared parameter mismatch for " + parameter.key() + ": expected="
                            + parameter.value() + " actual=" + value);
        }
    }

    static Map<String, String> extractConfigBackedSharedParameters(Config config) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Binding binding : DIRECT_BINDINGS) putUnique(result, binding.key(),
                parameter(config.getModule(binding.module()), binding.parameter()));

        ConfigGroup replanning = config.getModule("replanning");
        for (ConfigGroup strategy : parameterSets(replanning, "strategysettings")) {
            String name = parameter(strategy, "strategyName");
            String key = switch (name) {
                case "ChangeExpBeta" -> "strategy.change_exp_beta";
                case "ReRoute" -> "strategy.reroute";
                case "SubtourModeChoice" -> "strategy.subtour_mode_choice";
                default -> throw new IllegalStateException("Unexpected strategy " + name);
            };
            putUnique(result, key, parameter(strategy, "weight"));
        }

        ConfigGroup scoring = only(parameterSets(config.getModule("scoring"), "scoringParameters"),
                "scoringParameters");
        Map<String, String> scoringKeys = Map.of(
                "earlyDeparture", "scoring.early_departure", "lateArrival", "scoring.late_arrival",
                "marginalUtilityOfMoney", "scoring.money", "performing", "scoring.performing",
                "subpopulation", "scoring.subpopulation", "utilityOfLineSwitch", "scoring.line_switch",
                "waiting", "scoring.waiting", "waitingPt", "scoring.waiting_pt");
        for (var entry : scoringKeys.entrySet()) putUnique(result, entry.getValue(), parameter(scoring, entry.getKey()));
        for (ConfigGroup activity : parameterSets(scoring, "activityParams")) {
            String type = parameter(activity, "activityType");
            if (type.endsWith(" interaction")) putUnique(result,
                    "scoring.activity." + type.replace(' ', '_') + ".enabled",
                    parameter(activity, "scoringThisActivityAtAll"));
            else putUnique(result, "scoring.activity." + type + ".duration",
                    parameter(activity, "typicalDuration"));
        }
        for (ConfigGroup modeParameters : parameterSets(scoring, "modeParams")) {
            String mode = parameter(modeParameters, "mode");
            String constantKey = Set.of("car", "pt", "walk", "bike").contains(mode)
                    ? "calibration.asc." + mode : "scoring.mode." + mode + ".constant";
            putUnique(result, constantKey, parameter(modeParameters, "constant"));
            putUnique(result, "scoring.mode." + mode + ".travel_time",
                    parameter(modeParameters, "marginalUtilityOfTraveling_util_hr"));
            putUnique(result, "scoring.mode." + mode + ".distance",
                    parameter(modeParameters, "marginalUtilityOfDistance_util_m"));
            putUnique(result, "scoring.mode." + mode + ".money_distance",
                    parameter(modeParameters, "monetaryDistanceRate"));
            putUnique(result, "scoring.mode." + mode + ".daily_money",
                    parameter(modeParameters, "dailyMonetaryConstant"));
            putUnique(result, "scoring.mode." + mode + ".daily_utility",
                    parameter(modeParameters, "dailyUtilityConstant"));
        }

        for (ConfigGroup modeParameters : parameterSets(config.getModule("routing"), "teleportedModeParameters")) {
            String mode = parameter(modeParameters, "mode");
            putUnique(result, "routing." + mode + ".beeline", parameter(modeParameters, "beelineDistanceFactor"));
            if (Set.of("ride", "pt").contains(mode)) putUnique(result, "routing." + mode + ".freespeed",
                    parameter(modeParameters, "teleportedModeFreespeedFactor"));
            else putUnique(result, "routing." + mode + ".speed", parameter(modeParameters, "teleportedModeSpeed"));
        }
        return Map.copyOf(result);
    }

    private static String canonicalConfig(Config config) {
        StringBuilder result = new StringBuilder();
        for (var module : config.getModules().entrySet()) {
            result.append(encode(module.getKey())).append(canonicalGroup(module.getValue(),
                    ALLOWED_FIELDS.getOrDefault(module.getKey(), Set.of())));
        }
        return result.toString();
    }

    private static String canonicalGroup(ConfigGroup group, Set<String> ignoredParameters) {
        StringBuilder result = new StringBuilder("{");
        Map<String, String> parameters = new TreeMap<>(group.getParams());
        ignoredParameters.forEach(parameters::remove);
        for (var parameter : parameters.entrySet()) {
            result.append(encode(parameter.getKey())).append(encode(parameter.getValue()));
        }
        for (var type : new TreeMap<>(group.getParameterSets()).entrySet()) {
            List<String> children = new ArrayList<>();
            for (ConfigGroup child : type.getValue()) children.add(canonicalGroup(child, Set.of()));
            children.sort(String::compareTo);
            result.append(encode(type.getKey()));
            for (String child : children) result.append(encode(child));
        }
        return result.append('}').toString();
    }

    private static String relativeInput(Production2040Contract.ScenarioSpec specification, String projectPath) {
        return normalizedPath(specification.configPath().getParent().relativize(
                Production2040Contract.path(projectPath)).toString());
    }

    private static void requireInput(String actual, String expected, String label) {
        Production2040Contract.require(!Path.of(actual).isAbsolute(), label + " path must be project-relative");
        Production2040Contract.require(expected.equals(normalizedPath(actual)),
                label + " path mismatch: expected=" + expected + " actual=" + actual);
    }

    private static String normalizedPath(String value) {
        return Path.of(value.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    private static String parameter(ConfigGroup group, String name) {
        Production2040Contract.require(group != null, "Missing config module");
        String value = group.getParams().get(name);
        Production2040Contract.require(value != null, "Missing config parameter " + group.getName() + "." + name);
        return value;
    }

    private static Collection<? extends ConfigGroup> parameterSets(ConfigGroup group, String type) {
        Production2040Contract.require(group != null, "Missing config group for parameter set " + type);
        return group.getParameterSets(type);
    }

    private static ConfigGroup only(Collection<? extends ConfigGroup> groups, String label) {
        Production2040Contract.require(groups.size() == 1, "Expected one " + label + " set but found " + groups.size());
        return groups.iterator().next();
    }

    private static void putUnique(Map<String, String> target, String key, String value) {
        Production2040Contract.require(target.put(key, value) == null, "Duplicate extracted shared parameter " + key);
    }

    private static String encode(String value) {
        return value.length() + ":" + value;
    }

    private static Binding binding(String key, String module, String parameter) {
        return new Binding(key, module, parameter);
    }

    private record Binding(String key, String module, String parameter) { }
}
