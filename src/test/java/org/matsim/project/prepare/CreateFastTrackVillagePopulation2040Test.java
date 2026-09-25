package org.matsim.project.prepare;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateFastTrackVillagePopulation2040Test {

	@Test
	void nearestLinkUsesOnlyCarEnabledLinksAndExactSegmentDistance() {
		Network network = NetworkUtils.createNetwork();
		Node n1 = node(network, "n1", 0, 0);
		Node n2 = node(network, "n2", 100, 0);
		Node n3 = node(network, "n3", 0, 20);
		Node n4 = node(network, "n4", 100, 20);
		link(network, "pt-near", n1, n2, Set.of(TransportMode.pt));
		link(network, "car-farther", n3, n4, Set.of(TransportMode.car));

		List<CreateFastTrackVillagePopulation2040.LinkCandidate> result =
				CreateFastTrackVillagePopulation2040.nearestCarLinks(
						network, new Coord(50, 5), 3
				);

		assertAll(
				() -> assertEquals(1, result.size()),
				() -> assertEquals("car-farther", result.getFirst().linkId().toString()),
				() -> assertEquals(15.0, result.getFirst().distanceMeters(), 1e-9)
		);
	}

	@Test
	void relocationIsDeterministicDisjointAndMovesEveryRepeatedActivity() {
		List<CreateFastTrackVillagePopulation2040.Site> sites = List.of(
				new CreateFastTrackVillagePopulation2040.Site(
						"Olympic Village", 48.153739, 11.658821,
						new Coord(10, 20), Id.createLinkId("olympic-link"), 10, 525, 175
				),
				new CreateFastTrackVillagePopulation2040.Site(
						"Media Village", 48.142233, 11.657852,
						new Coord(30, 40), Id.createLinkId("media-link"), 20, 175, 58
				)
		);
		Population first = population(1_000);
		Population second = population(1_000);

		var firstResult = CreateFastTrackVillagePopulation2040.relocatePopulation(
				first, sites, CreateFastTrackVillagePopulation2040.RANDOM_SEED
		);
		var secondResult = CreateFastTrackVillagePopulation2040.relocatePopulation(
				second, sites, CreateFastTrackVillagePopulation2040.RANDOM_SEED
		);

		assertAll(
				() -> assertEquals(700, firstResult.homeAssignments().size()),
				() -> assertEquals(233, firstResult.workAssignments().size()),
				() -> assertEquals(
						firstResult.homeAssignments().keySet(),
						secondResult.homeAssignments().keySet()
				),
				() -> assertEquals(
						firstResult.workAssignments().keySet(),
						secondResult.workAssignments().keySet()
				),
				() -> assertTrue(java.util.Collections.disjoint(
						firstResult.homeAssignments().keySet(),
						firstResult.workAssignments().keySet()
				)),
				() -> assertEquals(Map.of(
						"Olympic Village", 1_050,
						"Media Village", 350
				), firstResult.homeActivities()),
				() -> assertEquals(Map.of(
						"Olympic Village", 350,
						"Media Village", 116
				), firstResult.workActivities())
		);

		assertAssignments(first, firstResult.homeAssignments(), "home");
		assertAssignments(first, firstResult.workAssignments(), "work");
	}

	private static void assertAssignments(
			Population population,
			Map<Id<Person>, CreateFastTrackVillagePopulation2040.Site> assignments,
			String type
	) {
		for (Map.Entry<Id<Person>, CreateFastTrackVillagePopulation2040.Site> entry
				: assignments.entrySet()) {
			Person person = population.getPersons().get(entry.getKey());
			int found = 0;
			for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
				if (element instanceof Activity activity
						&& type.equals(activity.getType())) {
					found++;
					assertEquals(entry.getValue().coordinate(), activity.getCoord());
					assertEquals(entry.getValue().carLinkId(), activity.getLinkId());
				}
			}
			assertEquals(2, found);
		}
	}

	private static Population population(int persons) {
		Population population = PopulationUtils.createPopulation(
				org.matsim.core.config.ConfigUtils.createConfig()
		);
		for (int index = 0; index < persons; index++) {
			Person person = population.getFactory().createPerson(
					Id.createPersonId("person-" + index)
			);
			Plan selected = population.getFactory().createPlan();
			selected.addActivity(activity(population, "home", index, 0));
			selected.addLeg(population.getFactory().createLeg(TransportMode.car));
			selected.addActivity(activity(population, "work", index, 10));
			selected.addLeg(population.getFactory().createLeg(TransportMode.car));
			selected.addActivity(activity(population, "work", index, 20));
			selected.addLeg(population.getFactory().createLeg(TransportMode.car));
			selected.addActivity(activity(population, "home", index, 30));
			person.addPlan(selected);
			person.setSelectedPlan(selected);
			population.addPerson(person);
		}
		return population;
	}

	private static Activity activity(
			Population population,
			String type,
			double x,
			double y
	) {
		return population.getFactory().createActivityFromCoord(type, new Coord(x, y));
	}

	private static Node node(Network network, String id, double x, double y) {
		Node node = network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y));
		network.addNode(node);
		return node;
	}

	private static Link link(
			Network network,
			String id,
			Node from,
			Node to,
			Set<String> modes
	) {
		Link link = network.getFactory().createLink(Id.createLinkId(id), from, to);
		link.setAllowedModes(modes);
		network.addLink(link);
		return link;
	}
}
