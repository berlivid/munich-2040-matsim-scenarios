package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;

/** Shared selected-plan index for the two binding 2040 demand-accounting scopes. */
final class Production2040AccountingScopes {
    enum Scope { BOTH_INSIDE, MUNICH_RESIDENTS }
    enum ResidentStatus { RESIDENT, NON_RESIDENT, UNRESOLVED }

    private Production2040AccountingScopes() { }

    static Index read(Path plansFile, MunichMunicipalBoundary boundary) {
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        Map<Id<Person>, PersonScope> persons = new HashMap<>();
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
        reader.addAlgorithm(person -> Production2040AnalysisSpec.require(
                persons.put(person.getId(), classify(person, boundary, filter)) == null,
                "Duplicate person in final plans: " + person.getId()));
        reader.readFile(plansFile.toString());
        return freeze(persons);
    }

    static Index classify(Iterable<Person> source, MunichMunicipalBoundary boundary) {
        MunichTripBoundaryFilter filter = new MunichTripBoundaryFilter(boundary);
        Map<Id<Person>, PersonScope> persons = new HashMap<>();
        for (Person person : source) {
            Production2040AnalysisSpec.require(
                    persons.put(person.getId(), classify(person, boundary, filter)) == null,
                    "Duplicate person in supplied population: " + person.getId());
        }
        return freeze(persons);
    }

    static PersonScope classify(Person person, MunichMunicipalBoundary boundary,
            MunichTripBoundaryFilter filter) {
        Activity home = AnalyzeMunichPopulation.findHomeActivity(person);
        ResidentStatus residentStatus;
        if (home == null || StageActivityTypeIdentifier.isStageActivity(home.getType())
                || !boundary.isValidCoordinate(home.getCoord())) {
            residentStatus = ResidentStatus.UNRESOLVED;
        } else {
            residentStatus = boundary.covers(home.getCoord())
                    ? ResidentStatus.RESIDENT : ResidentStatus.NON_RESIDENT;
        }
        List<MunichTripBoundaryFilter.ClassifiedTrip> classified = filter.classify(
                person.getSelectedPlan());
        List<TripScope> trips = java.util.stream.IntStream.range(0, classified.size())
                .mapToObj(index -> {
                    var trip = classified.get(index);
                    return new TripScope(new TripKey(person.getId().toString(), index),
                            trip.category(), Production2040AnalysisSpec.normalizeMainMode(
                                    trip.inputMainMode()), residentStatus);
                }).toList();
        return new PersonScope(person.getId(), residentStatus, trips);
    }

    private static Index freeze(Map<Id<Person>, PersonScope> persons) {
        Map<TripKey, TripScope> trips = new LinkedHashMap<>();
        Map<ResidentStatus, Long> personCounts = zeroResidentCounts();
        Map<ResidentStatus, Long> tripCounts = zeroResidentCounts();
        Map<MunichTripBoundaryFilter.SpatialCategory, Long> endpointCounts =
                new EnumMap<>(MunichTripBoundaryFilter.SpatialCategory.class);
        for (var category : MunichTripBoundaryFilter.SpatialCategory.values()) {
            endpointCounts.put(category, 0L);
        }
        for (PersonScope person : persons.values()) {
            personCounts.merge(person.residentStatus(), 1L, Long::sum);
            tripCounts.merge(person.residentStatus(), (long) person.trips().size(), Long::sum);
            for (TripScope trip : person.trips()) {
                Production2040AnalysisSpec.require(trips.put(trip.key(), trip) == null,
                        "Duplicate selected-plan main trip " + trip.key());
                endpointCounts.merge(trip.endpointCategory(), 1L, Long::sum);
            }
        }
        return new Index(Map.copyOf(persons), Map.copyOf(trips), Map.copyOf(personCounts),
                Map.copyOf(tripCounts), Map.copyOf(endpointCounts));
    }

    private static Map<ResidentStatus, Long> zeroResidentCounts() {
        Map<ResidentStatus, Long> result = new EnumMap<>(ResidentStatus.class);
        for (ResidentStatus status : ResidentStatus.values()) result.put(status, 0L);
        return result;
    }

    record TripKey(String personId, int zeroBasedMainTripIndex) { }

    record TripScope(TripKey key,
                     MunichTripBoundaryFilter.SpatialCategory endpointCategory,
                     String mainMode, ResidentStatus residentStatus) {
        boolean included(Scope scope) {
            return switch (scope) {
                case BOTH_INSIDE -> endpointCategory
                        == MunichTripBoundaryFilter.SpatialCategory.BOTH_INSIDE;
                case MUNICH_RESIDENTS -> residentStatus == ResidentStatus.RESIDENT;
            };
        }
    }

    record PersonScope(Id<Person> personId, ResidentStatus residentStatus,
                       List<TripScope> trips) { }

    record Index(Map<Id<Person>, PersonScope> persons,
                 Map<TripKey, TripScope> trips,
                 Map<ResidentStatus, Long> personCounts,
                 Map<ResidentStatus, Long> tripCounts,
                 Map<MunichTripBoundaryFilter.SpatialCategory, Long> endpointTripCounts) {
        TripScope trip(Id<Person> person, Integer zeroBasedIndex) {
            return zeroBasedIndex == null ? null
                    : trips.get(new TripKey(person.toString(), zeroBasedIndex));
        }
    }
}
