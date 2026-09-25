package org.matsim.project.prepare;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

/** Strict read-only validator for a server-produced iteration-zero smoke output. */
public final class ValidateMatsim2040ProductionSmokeOutput {
    private static final Set<String> ALLOWED_EVENT_MODES = Set.of(
            "car", "pt", "walk", "bike", "transit_walk", "access_walk", "egress_walk");

    private ValidateMatsim2040ProductionSmokeOutput() { }

    public static void main(String[] args) throws Exception {
        Production2040Contract.require(args.length == 1,
                "Usage: ValidateMatsim2040ProductionSmokeOutput BAU|FAST_TRACK");
        var contract = Production2040Contract.loadAndValidate();
        validate(Production2040RunSupport.scenario(args[0]),
                Production2040Contract.protectedInputSnapshot(contract));
    }

    static SmokeValidation validate(Production2040RunSupport.RunDefinition definition,
            Map<Path, String> protectedBefore) throws Exception {
        Path output = definition.smokeOutput();
        Production2040Contract.require(Files.isDirectory(output),
                "Missing smoke output: " + Production2040Contract.projectPath(output));
        String runId = definition.smokeRunId();
        Path log = required(output.resolve(runId + ".logfile.log"), "smoke log");
        Production2040Contract.require(Files.readString(log, StandardCharsets.UTF_8)
                        .contains("shutdown completed."),
                "Smoke log contains no normal MATSim shutdown evidence");
        Production2040Contract.require(Files.isDirectory(output.resolve("ITERS/it.0")),
                "Smoke output lacks completed iteration 0");
        Production2040Contract.require(!Files.exists(output.resolve("ITERS/it.1")),
                "Smoke output unexpectedly contains iteration 1");

        Path outputConfig = required(output.resolve(runId + ".output_config.xml"),
                "smoke output config");
        Config expected = Production2040RunSupport.smokeConfig(definition);
        Config actual = ConfigUtils.loadConfig(outputConfig.toString());
        validateOutputConfig(expected, actual, runId);

        // Load the protected scenario inputs from the production-config context.
        // The output config is compared semantically above but its own directory
        // must never become an alternative base path for relative model inputs.
        Scenario input = ScenarioUtils.loadScenario(expected);
        Path plans = required(output.resolve(runId + ".output_plans.xml.gz"),
                "smoke output plans");
        Set<String> persons = new HashSet<>();
        StreamingPopulationReader populationReader = new StreamingPopulationReader(input);
        populationReader.addAlgorithm(person -> persons.add(person.getId().toString()));
        populationReader.readFile(plans.toString());
        Production2040Contract.require(persons.equals(Production2040RunSupport.SMOKE_PERSON_IDS),
                "Smoke output plans contain unexpected or missing persons: " + persons);

        SmokeEvents events = new SmokeEvents(input, definition.fastTrack());
        var manager = EventsUtils.createEventsManager();
        manager.addHandler(events);
        new MatsimEventsReader(manager).readFile(required(output.resolve(
                runId + ".output_events.xml.gz"), "smoke events").toString());
        events.validate();

        var contract = Production2040Contract.loadAndValidate();
        Production2040Contract.require(protectedBefore.equals(
                        Production2040Contract.protectedInputSnapshot(contract)),
                "Smoke execution or validation changed a protected input");
        SmokeValidation result = events.result();
        System.out.printf("2040 PRODUCTION SMOKE OUTPUT VALIDATION PASS scenario=%s "
                        + "departures=%d arrivals=%d pt_boarded=true pt_alighted=true "
                        + "car_link_events=%d stuck=0%n",
                definition.argument(), result.departures(), result.arrivals(),
                result.carLinkEvents());
        System.out.println("No Controller or QSim was started by this validator.");
        return result;
    }

    static void validateOutputConfig(Config expected, Config actual, String runId) {
        var configDifferences = Production2040PostRunConfigComparison
                .semanticConfigDifferences(expected, actual);
        Production2040Contract.require(configDifferences.isEmpty(),
                "Smoke output config differs from the approved in-memory derivation:\n- "
                        + String.join("\n- ", configDifferences));
        Production2040Contract.require(runId.equals(actual.controller().getRunId()),
                "Smoke output belongs to the wrong scenario/run ID");
    }

    private static Path required(Path file, String label) {
        Production2040Contract.require(Files.isRegularFile(file),
                "Missing " + label + ": " + Production2040Contract.projectPath(file));
        return file;
    }

    private static final class SmokeEvents implements PersonDepartureEventHandler,
            PersonArrivalEventHandler, PersonEntersVehicleEventHandler,
            PersonLeavesVehicleEventHandler, LinkEnterEventHandler, PersonStuckEventHandler {
        private final Scenario scenario;
        private final boolean fastTrack;
        private final Map<String, Set<String>> departures = new HashMap<>();
        private final Set<String> arrivals = new HashSet<>();
        private final Set<String> ptEnteredVehicles = new HashSet<>();
        private final Set<String> ptLeftVehicles = new HashSet<>();
        private final Set<String> stuck = new HashSet<>();
        private long carLinkEvents;

        private SmokeEvents(Scenario scenario, boolean fastTrack) {
            this.scenario = scenario;
            this.fastTrack = fastTrack;
        }

        @Override public void handleEvent(PersonDepartureEvent event) {
            String person = event.getPersonId().toString();
            if (!Production2040RunSupport.SMOKE_PERSON_IDS.contains(person)) return;
            Production2040Contract.require(ALLOWED_EVENT_MODES.contains(event.getLegMode()),
                    "Unexpected smoke departure mode " + event.getLegMode());
            departures.computeIfAbsent(person, ignored -> new HashSet<>()).add(event.getLegMode());
        }

        @Override public void handleEvent(PersonArrivalEvent event) {
            String person = event.getPersonId().toString();
            if (Production2040RunSupport.SMOKE_PERSON_IDS.contains(person)) arrivals.add(person);
        }

        @Override public void handleEvent(PersonEntersVehicleEvent event) {
            if (!"smoke-pt".equals(event.getPersonId().toString())) return;
            Production2040Contract.require(scenario.getTransitVehicles().getVehicles()
                            .containsKey(event.getVehicleId()),
                    "PT smoke person entered a missing transit vehicle " + event.getVehicleId());
            ptEnteredVehicles.add(event.getVehicleId().toString());
        }

        @Override public void handleEvent(PersonLeavesVehicleEvent event) {
            if (!"smoke-pt".equals(event.getPersonId().toString())) return;
            Production2040Contract.require(scenario.getTransitVehicles().getVehicles()
                            .containsKey(event.getVehicleId()),
                    "PT smoke person left a missing transit vehicle " + event.getVehicleId());
            ptLeftVehicles.add(event.getVehicleId().toString());
        }

        @Override public void handleEvent(LinkEnterEvent event) {
            String vehicle = event.getVehicleId().toString();
            if (!"smoke-car".equals(vehicle)) return;
            Production2040Contract.require(scenario.getNetwork().getLinks()
                            .containsKey(event.getLinkId()),
                    "Smoke car used an unknown link " + event.getLinkId());
            if (fastTrack) Production2040Contract.require(!FastTrackPedestrianZones
                            .readOnlyRestrictedLinkIds().contains(event.getLinkId().toString()),
                    "Fast Track smoke car used a pedestrian-zone link " + event.getLinkId());
            carLinkEvents++;
        }

        @Override public void handleEvent(PersonStuckEvent event) {
            String person = event.getPersonId().toString();
            if (Production2040RunSupport.SMOKE_PERSON_IDS.contains(person)) stuck.add(person);
        }

        void validate() {
            Production2040Contract.require(departures.keySet().equals(
                            Production2040RunSupport.SMOKE_PERSON_IDS),
                    "One or more smoke persons produced no departure: " + departures.keySet());
            Production2040Contract.require(arrivals.equals(Production2040RunSupport.SMOKE_PERSON_IDS),
                    "One or more smoke persons produced no arrival: " + arrivals);
            Production2040Contract.require(departures.get("smoke-car").contains("car")
                            && departures.get("smoke-pt").contains("pt")
                            && departures.get("smoke-walk").contains("walk")
                            && departures.get("smoke-bike").contains("bike"),
                    "Smoke main-mode departures do not match the fixture: " + departures);
            Production2040Contract.require(!ptEnteredVehicles.isEmpty()
                            && !ptLeftVehicles.isEmpty()
                            && !java.util.Collections.disjoint(ptEnteredVehicles, ptLeftVehicles),
                    "PT smoke person did not board and alight a transit vehicle");
            Production2040Contract.require(carLinkEvents > 0,
                    "Smoke car produced no regular LinkEnter event");
            Production2040Contract.require(stuck.isEmpty(),
                    "Smoke person became stuck: " + stuck);
        }

        SmokeValidation result() {
            return new SmokeValidation(departures.values().stream().mapToLong(Set::size).sum(),
                    arrivals.size(), carLinkEvents, Set.copyOf(ptEnteredVehicles));
        }
    }

    record SmokeValidation(long departures, long arrivals, long carLinkEvents,
                           Set<String> ptVehicles) { }
}
