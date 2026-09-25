package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

/** Deterministically derives both 2040 production configs from the frozen Round-5 config. */
public final class BuildProduction2040Configs {
    private BuildProduction2040Configs() { }

    public static void main(String[] args) {
        Production2040Contract.require(args.length == 0, "This generator does not accept arguments");
        build(Production2040Contract.BAU.configPath(), Production2040Contract.FAST_TRACK.configPath());
    }

    static void build(Path bauTarget, Path fastTrackTarget) {
        Production2040Contract.ContractData contract = Production2040Contract.loadAndValidate();
        Map<Path, String> protectedBefore = Production2040Contract.protectedInputSnapshot(contract);
        Path bauCandidate = null;
        Path fastCandidate = null;
        try {
            bauCandidate = writeCandidate(buildConfig(Production2040Contract.BAU), bauTarget);
            fastCandidate = writeCandidate(buildConfig(Production2040Contract.FAST_TRACK), fastTrackTarget);
            ValidateProduction2040Configs.validateFiles(bauCandidate, fastCandidate, true);
            publish(bauCandidate, bauTarget);
            bauCandidate = null;
            publish(fastCandidate, fastTrackTarget);
            fastCandidate = null;
            ValidateProduction2040Configs.validateFiles(bauTarget, fastTrackTarget, true);
            Production2040Contract.require(protectedBefore.equals(Production2040Contract.protectedInputSnapshot(contract)),
                    "A protected input changed during config generation");
            System.out.println("2040 PRODUCTION CONFIG BUILD PASS");
            System.out.println("  BAU: " + Production2040Contract.projectPath(bauTarget));
            System.out.println("  Fast Track: " + Production2040Contract.projectPath(fastTrackTarget));
        } finally {
            deleteCandidate(bauCandidate);
            deleteCandidate(fastCandidate);
        }
    }

    static Config buildConfig(Production2040Contract.ScenarioSpec specification) {
        Config config = ConfigUtils.loadConfig(Production2040Contract.ROUND_5_CONFIG.toString());
        config.controller().setRunId(specification.runId());
        config.controller().setOutputDirectory(specification.outputDirectory());
        config.network().setInputFile(relativeInput(specification, specification.networkPath()));
        config.plans().setInputFile(relativeInput(specification, specification.populationPath()));
        config.transit().setTransitScheduleFile(relativeInput(specification, specification.schedulePath()));
        config.transit().setVehiclesFile(relativeInput(specification, specification.vehiclesPath()));
        config.checkConsistency();
        return config;
    }

    private static String relativeInput(Production2040Contract.ScenarioSpec specification, String projectPath) {
        Path configDirectory = specification.configPath().getParent();
        Path input = Production2040Contract.path(projectPath);
        return configDirectory.relativize(input).toString().replace('\\', '/');
    }

    private static Path writeCandidate(Config config, Path target) {
        try {
            Production2040Contract.require(Files.isDirectory(target.toAbsolutePath().normalize().getParent()),
                    "Config directory does not exist: " + target.getParent());
            Path candidate = Files.createTempFile(target.getParent(), ".production-2040-config-", ".tmp");
            new ConfigWriter(config).write(candidate.toString());
            return candidate;
        } catch (IOException error) {
            throw new IllegalStateException("Could not create config candidate for " + target, error);
        }
    }

    private static void publish(Path candidate, Path target) {
        try {
            if (Files.isRegularFile(target) && Files.mismatch(candidate, target) == -1L) {
                Files.delete(candidate);
                return;
            }
            try {
                Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not publish " + target, error);
        }
    }

    private static void deleteCandidate(Path candidate) {
        if (candidate == null) return;
        try {
            Files.deleteIfExists(candidate);
        } catch (IOException error) {
            throw new IllegalStateException("Could not remove temporary config " + candidate, error);
        }
    }
}
