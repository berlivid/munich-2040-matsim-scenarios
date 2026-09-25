package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/**
 * Scenario-neutral runtime observer for exact iteration-end BOTH_INSIDE shares
 * and compact stuck-event statistics. It is installed only by future runners.
 */
public final class Production2040AnalysisListener
        implements IterationEndsListener, PersonStuckEventHandler {
    private final Production2040AnalysisSpec.ScenarioDefinition definition;
    private final Scenario scenario;
    private final Map<Id<Person>, ScopedTrips> scope;
    private final List<Production2040AnalysisSpec.IterationSnapshot> snapshots =
            new ArrayList<>();
    private final List<StuckIteration> stuckIterations = new ArrayList<>();
    private int currentIteration = -1;
    private MutableStuck currentStuck = new MutableStuck();

    Production2040AnalysisListener(Production2040AnalysisSpec.ScenarioDefinition definition,
            Scenario scenario, MunichTripBoundaryFilter filter) {
        this.definition = java.util.Objects.requireNonNull(definition);
        this.scenario = java.util.Objects.requireNonNull(scenario);
        this.scope = buildScope(scenario, filter);
    }

    /** Installs the shared observer without starting Controller or QSim. */
    public static Production2040AnalysisListener install(Controler controler, String scenarioId)
            throws IOException {
        Production2040AnalysisSpec.ScenarioDefinition definition =
                Production2040AnalysisSpec.scenario(scenarioId);
        Production2040AnalysisListener listener = new Production2040AnalysisListener(
                definition, controler.getScenario(), new MunichTripBoundaryFilter(
                        MunichMunicipalBoundary.loadDefault()));
        controler.addControlerListener(listener);
        controler.getEvents().addHandler(listener);
        return listener;
    }

    @Override
    public void reset(int iteration) {
        Production2040AnalysisSpec.require(iteration >= Production2040AnalysisSpec.FIRST_ITERATION
                        && iteration <= Production2040AnalysisSpec.LAST_ITERATION,
                "Unexpected production iteration " + iteration);
        currentIteration = iteration;
        currentStuck = new MutableStuck();
    }

    @Override
    public void handleEvent(PersonStuckEvent event) {
        Production2040AnalysisSpec.require(currentIteration >= 0,
                "PersonStuckEvent received outside an iteration");
        currentStuck.add(event, scope.containsKey(event.getPersonId()));
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        Production2040AnalysisSpec.require(event.getIteration() == currentIteration,
                "Stuck-event and iteration observers are out of sync");
        var snapshot = snapshot(event.getIteration(), scenario, scope);
        snapshots.add(snapshot);
        stuckIterations.add(currentStuck.freeze(event.getIteration()));
        try {
            Files.createDirectories(definition.runtimeDirectory());
            writeAtomically(definition.runtimeDirectory().resolve("iteration_mode_shares.csv"),
                    iterationCsv(definition.scenarioId(), snapshots));
            writeAtomically(definition.runtimeDirectory().resolve(
                    "stuck_events_by_iteration_and_mode.csv"),
                    stuckCsv(definition.scenarioId(), stuckIterations));
        } catch (IOException error) {
            throw new IllegalStateException("Could not preserve production runtime analysis", error);
        }
    }

    static Map<Id<Person>, ScopedTrips> buildScope(Scenario scenario,
            MunichTripBoundaryFilter filter) {
        Map<Id<Person>, ScopedTrips> result = new HashMap<>();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            var classified = filter.classify(person.getSelectedPlan());
            BitSet selected = new BitSet(classified.size());
            for (int index = 0; index < classified.size(); index++) {
                if (classified.get(index).category()
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE) selected.set(index);
            }
            if (!selected.isEmpty()) result.put(person.getId(),
                    new ScopedTrips(selected, classified.size()));
        }
        return Map.copyOf(result);
    }

    static Production2040AnalysisSpec.IterationSnapshot snapshot(int iteration,
            Scenario scenario, Map<Id<Person>, ScopedTrips> scope) {
        Map<String, Long> modes = new TreeMap<>();
        long denominator = 0;
        for (var entry : scope.entrySet()) {
            Person person = scenario.getPopulation().getPersons().get(entry.getKey());
            Production2040AnalysisSpec.require(person != null && person.getSelectedPlan() != null,
                    "Scoped person or selected plan disappeared: " + entry.getKey());
            var trips = TripStructureUtils.getTrips(person.getSelectedPlan(),
                    StageActivityTypeIdentifier::isStageActivity);
            Production2040AnalysisSpec.require(trips.size() == entry.getValue().mainTripCount(),
                    "Main-trip structure changed for person " + entry.getKey());
            for (int index = entry.getValue().bothInside().nextSetBit(0); index >= 0;
                    index = entry.getValue().bothInside().nextSetBit(index + 1)) {
                modes.merge(MunichTripBoundaryFilter.identifyInputMainMode(trips.get(index)),
                        1L, Long::sum);
                denominator++;
            }
        }
        Map<String, Long> unexpected = new TreeMap<>(modes);
        Production2040AnalysisSpec.MAIN_MODES.forEach(unexpected::remove);
        return new Production2040AnalysisSpec.IterationSnapshot(iteration, denominator,
                Map.copyOf(modes), Map.copyOf(unexpected));
    }

    static String iterationCsv(String scenarioId,
            List<Production2040AnalysisSpec.IterationSnapshot> rows) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,iteration,both_inside_main_trips,car_sample_trips,car_share_percent,pt_sample_trips,pt_share_percent,bike_sample_trips,bike_share_percent,walk_sample_trips,walk_share_percent,unexpected_mode_sample_trips,unexpected_modes,definition\n");
        for (var row : rows) {
            csv.append(scenarioId).append(",0.05,main_trips,").append(row.iteration())
                    .append(',').append(row.bothInsideTrips());
            for (String mode : Production2040AnalysisSpec.MAIN_MODES) {
                csv.append(',').append(row.count(mode)).append(',')
                        .append(number(row.share(mode)));
            }
            csv.append(',').append(row.unexpectedCount()).append(',')
                    .append(quote(new TreeMap<>(row.unexpectedModes()).toString())).append(',')
                    .append(quote("selected-plan MATSim main trips with both main-activity endpoints covered by the Munich boundary; stage activities excluded"))
                    .append('\n');
        }
        return csv.toString();
    }

    static String stuckCsv(String scenarioId, List<StuckIteration> rows) {
        StringBuilder csv = new StringBuilder("scenario_id,sample_factor,unit,iteration,mode,stuck_event_count,relevant_both_inside_person_stuck_events,unique_affected_persons,unique_relevant_both_inside_persons,definition\n");
        for (StuckIteration row : rows) {
            Set<String> modes = new HashSet<>(row.byMode().keySet());
            modes.add("ALL");
            modes.stream().sorted().forEach(mode -> {
                StuckMetric metric = "ALL".equals(mode) ? row.total() : row.byMode().get(mode);
                csv.append(scenarioId).append(",0.05,events,").append(row.iteration())
                        .append(',').append(mode).append(',').append(metric.events())
                        .append(',').append(metric.relevantEvents()).append(',')
                        .append(metric.persons().size()).append(',')
                        .append(metric.relevantPersons().size()).append(',')
                        .append(quote("PersonStuckEvent; relevant persons have at least one BOTH_INSIDE main trip"))
                        .append('\n');
            });
        }
        return csv.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path candidate = Files.createTempFile(target.getParent(), ".runtime-analysis-", ".tmp");
        try {
            Files.writeString(candidate, content, StandardCharsets.UTF_8);
            try {
                Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    record ScopedTrips(BitSet bothInside, int mainTripCount) { }
    record StuckMetric(long events, long relevantEvents, Set<String> persons,
                       Set<String> relevantPersons) { }
    record StuckIteration(int iteration, Map<String, StuckMetric> byMode,
                          StuckMetric total) { }

    private static final class MutableStuck {
        private final Map<String, MutableStuckMetric> modes = new TreeMap<>();
        private final MutableStuckMetric total = new MutableStuckMetric();

        void add(PersonStuckEvent event, boolean relevant) {
            String mode = Production2040AnalysisSpec.normalizeMainMode(event.getLegMode());
            modes.computeIfAbsent(mode, ignored -> new MutableStuckMetric()).add(event, relevant);
            total.add(event, relevant);
        }

        StuckIteration freeze(int iteration) {
            Map<String, StuckMetric> result = new TreeMap<>();
            modes.forEach((mode, metric) -> result.put(mode, metric.freeze()));
            return new StuckIteration(iteration, Map.copyOf(result), total.freeze());
        }
    }

    private static final class MutableStuckMetric {
        private long events;
        private long relevantEvents;
        private final Set<String> persons = new HashSet<>();
        private final Set<String> relevantPersons = new HashSet<>();

        void add(PersonStuckEvent event, boolean relevant) {
            events++;
            String person = event.getPersonId().toString();
            persons.add(person);
            if (relevant) {
                relevantEvents++;
                relevantPersons.add(person);
            }
        }

        StuckMetric freeze() {
            return new StuckMetric(events, relevantEvents, Set.copyOf(persons),
                    Set.copyOf(relevantPersons));
        }
    }
}
