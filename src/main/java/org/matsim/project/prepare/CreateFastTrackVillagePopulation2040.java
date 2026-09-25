package org.matsim.project.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Creates the Fast Track 2040 population with relocated village demand.
 * No persons or plan elements are added or removed.
 */
public final class CreateFastTrackVillagePopulation2040 {

	static final Path SOURCE_POPULATION = Path.of(
			"scenarios/munich_fast_track_2040/population_2040.xml"
	);
	static final Path BAU_POPULATION = Path.of(
			"scenarios/munich_bau_2040/population_2040.xml"
	);
	static final Path FAST_TRACK_NETWORK = Path.of(
			"scenarios/munich_fast_track_2040/input_transit/network-with-pt.xml.gz"
	);
	static final Path OUTPUT_POPULATION = Path.of(
			"scenarios/munich_fast_track_2040/population_2040_fast_track.xml"
	);

	static final String CRS = "EPSG:31468";
	static final long RANDOM_SEED = 20_402_021L;
	static final double MAX_PLAUSIBLE_CAR_LINK_DISTANCE_METERS = 1_000.0;

	private static final List<RawSite> RAW_SITES = List.of(
			new RawSite("Olympic Village", 48.153739, 11.658821, 525, 175),
			new RawSite("Media Village", 48.142233, 11.657852, 175, 58)
	);

	private CreateFastTrackVillagePopulation2040() {
	}

	public static void main(String[] args) throws Exception {
		requireRegularFile(SOURCE_POPULATION, "Fast Track source population");
		requireRegularFile(BAU_POPULATION, "BAU population");
		requireRegularFile(FAST_TRACK_NETWORK, "Fast Track production network");

		String bauBefore = sha256(BAU_POPULATION);
		String sourceBefore = sha256(SOURCE_POPULATION);
		if (!bauBefore.equals(sourceBefore)) {
			throw new IllegalStateException(
					"The Fast Track source population is no longer byte-identical to BAU. "
							+ "Review the intended baseline before rebuilding."
			);
		}

		Network network = NetworkUtils.createNetwork();
		new MatsimNetworkReader(network).readFile(FAST_TRACK_NETWORK.toString());
		List<Site> sites = resolveSites(network);

		System.out.println("Reading source population: " + SOURCE_POPULATION.toAbsolutePath());
		Population population = PopulationUtils.readPopulation(SOURCE_POPULATION.toString());
		int sourcePersons = population.getPersons().size();
		BuildResult result = relocatePopulation(population, sites, RANDOM_SEED);

		Path temporaryOutput = OUTPUT_POPULATION.resolveSibling(
				OUTPUT_POPULATION.getFileName() + ".tmp.xml"
		);
		Files.deleteIfExists(temporaryOutput);
		PopulationUtils.writePopulation(population, temporaryOutput.toString());
		validateWrittenPopulation(temporaryOutput, sourcePersons, result);
		moveReplacing(temporaryOutput, OUTPUT_POPULATION);

		if (!bauBefore.equals(sha256(BAU_POPULATION))) {
			throw new IllegalStateException("The BAU population changed during the build.");
		}
		if (!sourceBefore.equals(sha256(SOURCE_POPULATION))) {
			throw new IllegalStateException("The Fast Track source population changed during the build.");
		}

		printSummary(sourcePersons, sites, result, sourceBefore, sha256(OUTPUT_POPULATION));
	}

	static List<Site> resolveSites(Network network) {
		CoordinateTransformation transformation =
				TransformationFactory.getCoordinateTransformation(
						TransformationFactory.WGS84, CRS
				);
		List<Site> sites = new ArrayList<>();
		for (RawSite raw : RAW_SITES) {
			// MATSim expects WGS84 as x=longitude and y=latitude.
			Coord transformed = transformation.transform(
					new Coord(raw.longitude(), raw.latitude())
			);
			List<LinkCandidate> candidates = nearestCarLinks(network, transformed, 3);
			if (candidates.isEmpty()) {
				throw new IllegalStateException("No car-enabled link exists for " + raw.name());
			}
			LinkCandidate nearest = candidates.getFirst();
			if (nearest.distanceMeters() > MAX_PLAUSIBLE_CAR_LINK_DISTANCE_METERS) {
				throw new IllegalStateException(
						raw.name() + " is " + formatMeters(nearest.distanceMeters())
								+ " from the nearest car link. Candidates: " + candidates
				);
			}
			sites.add(new Site(
					raw.name(), raw.latitude(), raw.longitude(), transformed,
					nearest.linkId(), nearest.distanceMeters(),
					raw.homePersons(), raw.workPersons()
			));
		}
		return List.copyOf(sites);
	}

	static List<LinkCandidate> nearestCarLinks(
			Network network,
			Coord coordinate,
			int limit
	) {
		return network.getLinks().values().stream()
				.filter(link -> link.getAllowedModes().contains(TransportMode.car))
				.map(link -> new LinkCandidate(
						link.getId(),
						CoordUtils.distancePointLinesegment(
								link.getFromNode().getCoord(),
								link.getToNode().getCoord(),
								coordinate
						)
				))
				.sorted(Comparator.comparingDouble(LinkCandidate::distanceMeters)
						.thenComparing(candidate -> candidate.linkId().toString()))
				.limit(limit)
				.toList();
	}

	static BuildResult relocatePopulation(
			Population population,
			List<Site> sites,
			long seed
	) {
		List<Person> persons = new ArrayList<>(population.getPersons().values());
		persons.sort(Comparator.comparing(person -> person.getId().toString()));

		List<Person> homeEligible = new ArrayList<>(persons.stream()
				.filter(person -> hasActivity(person, "home"))
				.toList());
		Collections.shuffle(homeEligible, new Random(seed));
		int requiredHomePersons = sites.stream().mapToInt(Site::homePersons).sum();
		requireCapacity(homeEligible.size(), requiredHomePersons, "home");

		Map<Id<Person>, Site> homeAssignments = assign(
				homeEligible, sites, true
		);
		Set<Id<Person>> homePersonIds = Set.copyOf(homeAssignments.keySet());

		List<Person> workEligible = new ArrayList<>(persons.stream()
				.filter(person -> !homePersonIds.contains(person.getId()))
				.filter(person -> hasActivity(person, "work"))
				.toList());
		Collections.shuffle(workEligible, new Random(seed + 1));
		int requiredWorkPersons = sites.stream().mapToInt(Site::workPersons).sum();
		requireCapacity(workEligible.size(), requiredWorkPersons, "work");
		Map<Id<Person>, Site> workAssignments = assign(
				workEligible, sites, false
		);

		Map<String, Integer> homeActivities = new LinkedHashMap<>();
		Map<String, Integer> workActivities = new LinkedHashMap<>();
		for (Person person : persons) {
			Site homeSite = homeAssignments.get(person.getId());
			if (homeSite != null) {
				homeActivities.merge(
						homeSite.name(), relocateActivities(person, "home", homeSite), Integer::sum
				);
			}
			Site workSite = workAssignments.get(person.getId());
			if (workSite != null) {
				workActivities.merge(
						workSite.name(), relocateActivities(person, "work", workSite), Integer::sum
				);
			}
		}

		return new BuildResult(
				Map.copyOf(homeAssignments), Map.copyOf(workAssignments),
				Map.copyOf(homeActivities), Map.copyOf(workActivities)
		);
	}

	private static Map<Id<Person>, Site> assign(
			List<Person> eligible,
			List<Site> sites,
			boolean home
	) {
		Map<Id<Person>, Site> assignments = new LinkedHashMap<>();
		int index = 0;
		for (Site site : sites) {
			int count = home ? site.homePersons() : site.workPersons();
			for (int selected = 0; selected < count; selected++) {
				Person person = eligible.get(index++);
				assignments.put(person.getId(), site);
			}
		}
		return assignments;
	}

	private static int relocateActivities(Person person, String type, Site site) {
		Plan plan = relevantPlan(person);
		int changed = 0;
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity
					&& type.equalsIgnoreCase(activity.getType())) {
				activity.setCoord(site.coordinate());
				activity.setLinkId(site.carLinkId());
				changed++;
			}
		}
		if (changed == 0) {
			throw new IllegalStateException(
					"Selected person " + person.getId() + " has no " + type + " activity."
			);
		}
		return changed;
	}

	private static boolean hasActivity(Person person, String type) {
		Plan plan = relevantPlan(person);
		if (plan == null) {
			return false;
		}
		return plan.getPlanElements().stream().anyMatch(element ->
				element instanceof Activity activity
						&& type.equalsIgnoreCase(activity.getType())
		);
	}

	private static Plan relevantPlan(Person person) {
		if (person.getSelectedPlan() != null) {
			return person.getSelectedPlan();
		}
		return person.getPlans().isEmpty() ? null : person.getPlans().getFirst();
	}

	private static void validateWrittenPopulation(
			Path populationFile,
			int expectedPersons,
			BuildResult expected
	) {
		Population written = PopulationUtils.readPopulation(populationFile.toString());
		if (written.getPersons().size() != expectedPersons) {
			throw new IllegalStateException(
					"Person count changed from " + expectedPersons + " to "
							+ written.getPersons().size()
			);
		}
		validateAssignments(written, expected.homeAssignments(), "home");
		validateAssignments(written, expected.workAssignments(), "work");
	}

	private static void validateAssignments(
			Population population,
			Map<Id<Person>, Site> assignments,
			String type
	) {
		for (Map.Entry<Id<Person>, Site> entry : assignments.entrySet()) {
			Person person = population.getPersons().get(entry.getKey());
			if (person == null) {
				throw new IllegalStateException("Missing relocated person " + entry.getKey());
			}
			Site site = entry.getValue();
			int occurrences = 0;
			for (PlanElement element : relevantPlan(person).getPlanElements()) {
				if (element instanceof Activity activity
						&& type.equalsIgnoreCase(activity.getType())) {
					occurrences++;
					if (!site.coordinate().equals(activity.getCoord())
							|| !site.carLinkId().equals(activity.getLinkId())) {
						throw new IllegalStateException(
								"Incorrect " + type + " location for person " + person.getId()
						);
					}
				}
			}
			if (occurrences == 0) {
				throw new IllegalStateException(
						"No written " + type + " activity for person " + person.getId()
				);
			}
		}
	}

	private static void printSummary(
			int persons,
			List<Site> sites,
			BuildResult result,
			String sourceHash,
			String outputHash
	) {
		System.out.println();
		System.out.println("Fast Track village population build PASS");
		System.out.println("Random seed: " + RANDOM_SEED);
		System.out.println("Persons before/after: " + persons + "/" + persons);
		for (Site site : sites) {
			long homePersons = result.homeAssignments().values().stream()
					.filter(site::equals).count();
			long workPersons = result.workAssignments().values().stream()
					.filter(site::equals).count();
			System.out.printf(java.util.Locale.ROOT,
					"%s: WGS84 %.6f, %.6f; %s %.3f, %.3f; car link %s (%.1f m); "
							+ "home persons/activities %d/%d; work persons/activities %d/%d%n",
					site.name(), site.latitude(), site.longitude(), CRS,
					site.coordinate().getX(), site.coordinate().getY(),
					site.carLinkId(), site.carLinkDistanceMeters(),
					homePersons, result.homeActivities().getOrDefault(site.name(), 0),
					workPersons, result.workActivities().getOrDefault(site.name(), 0)
			);
		}
		System.out.println("Source SHA-256: " + sourceHash);
		System.out.println("Output SHA-256: " + outputHash);
		System.out.println("Output: " + OUTPUT_POPULATION.toAbsolutePath());
	}

	private static void requireCapacity(int available, int required, String type) {
		if (available < required) {
			throw new IllegalStateException(
					"Only " + available + " eligible " + type + " persons for "
							+ required + " required relocations."
			);
		}
	}

	private static void requireRegularFile(Path path, String description) {
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException(
					description + " does not exist: " + path.toAbsolutePath()
			);
		}
	}

	private static void moveReplacing(Path source, Path destination) throws IOException {
		try {
			Files.move(
					source, destination,
					StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
			);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var input = Files.newInputStream(path)) {
			byte[] buffer = new byte[1 << 20];
			for (int read; (read = input.read(buffer)) > 0; ) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().withUpperCase().formatHex(digest.digest());
	}

	private static String formatMeters(double meters) {
		return String.format(java.util.Locale.ROOT, "%.1f m", meters);
	}

	record RawSite(
			String name,
			double latitude,
			double longitude,
			int homePersons,
			int workPersons
	) {
	}

	record Site(
			String name,
			double latitude,
			double longitude,
			Coord coordinate,
			Id<Link> carLinkId,
			double carLinkDistanceMeters,
			int homePersons,
			int workPersons
	) {
	}

	record LinkCandidate(Id<Link> linkId, double distanceMeters) {
		@Override
		public String toString() {
			return linkId + " (" + formatMeters(distanceMeters) + ")";
		}
	}

	record BuildResult(
			Map<Id<Person>, Site> homeAssignments,
			Map<Id<Person>, Site> workAssignments,
			Map<String, Integer> homeActivities,
			Map<String, Integer> workActivities
	) {
	}
}
