package org.matsim.project.prepare;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class CreateMunichPopulation2040 {

	private static final Path BASE_POPULATION = Path.of(
			"scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml"
	);
	private static final Path MUNICIPAL_BOUNDARY = Path.of(
			"original-input-data/munich-demography/munich_boundary.json"
	);
	private static final Path POPULATION_PROJECTION = Path.of(
			"original-input-data/munich-demography/population_projection_2023_2040.csv"
	);

	private static final Path BAU_OUTPUT = Path.of(
			"scenarios/munich_bau_2040/population_2040.xml"
	);
	private static final Path FAST_TRACK_OUTPUT = Path.of(
			"scenarios/munich_fast_track_2040/population_2040.xml"
	);

	// A fixed seed makes the generated population identical on every run.
	private static final long RANDOM_SEED = 2040L;

	public static void main(String[] args) throws IOException {
		requireRegularFile(BASE_POPULATION, "Base population");
		requireRegularFile(MUNICIPAL_BOUNDARY, "Municipal boundary");
		requireRegularFile(POPULATION_PROJECTION, "Population projection");

		ProjectionTotals projection = readProjectionTotals(POPULATION_PROJECTION);
		double growthFactor = (double) projection.population2040()
				/ projection.population2023();

		System.out.println("Reading base population: " + BASE_POPULATION.toAbsolutePath());
		Population population = PopulationUtils.readPopulation(BASE_POPULATION.toString());

		Geometry boundaryGeometry =
				AnalyzeMunichPopulation.readGeoJsonGeometry(MUNICIPAL_BOUNDARY);
		if (boundaryGeometry.isEmpty() || !boundaryGeometry.isValid()) {
			throw new IllegalArgumentException(
					"The Munich municipal boundary is empty or invalid."
			);
		}
		PreparedGeometry munichBoundary =
				PreparedGeometryFactory.prepare(boundaryGeometry);

		List<Person> munichResidents = findMunichResidents(population, munichBoundary);
		if (munichResidents.isEmpty()) {
			throw new IllegalStateException(
					"No Munich residents were found. Check the boundary and coordinate system."
			);
		}

		int targetMunichResidents = Math.toIntExact(
				Math.round(munichResidents.size() * growthFactor)
		);
		int additionalResidents = targetMunichResidents - munichResidents.size();
		if (additionalResidents < 0) {
			throw new IllegalStateException(
					"The projection decreases the population. "
							+ "This generator currently supports growth only."
			);
		}

		int originalPopulationSize = population.getPersons().size();
		addClonedResidents(
				population,
				munichResidents,
				additionalResidents,
				new Random(RANDOM_SEED)
		);

		Files.createDirectories(BAU_OUTPUT.getParent());
		Files.createDirectories(FAST_TRACK_OUTPUT.getParent());

		System.out.println("Writing BAU population: " + BAU_OUTPUT.toAbsolutePath());
		PopulationUtils.writePopulation(population, BAU_OUTPUT.toString());

		// Both 2040 infrastructure scenarios deliberately use the exact same demand.
		System.out.println(
				"Copying identical population to: " + FAST_TRACK_OUTPUT.toAbsolutePath()
		);
		Files.copy(BAU_OUTPUT, FAST_TRACK_OUTPUT, StandardCopyOption.REPLACE_EXISTING);

		printSummary(
				projection,
				growthFactor,
				originalPopulationSize,
				munichResidents.size(),
				targetMunichResidents,
				additionalResidents,
				population.getPersons().size()
		);
	}

	private static List<Person> findMunichResidents(
			Population population,
			PreparedGeometry munichBoundary
	) {
		List<Person> residents = new ArrayList<>();

		for (Person person : population.getPersons().values()) {
			Activity homeActivity = AnalyzeMunichPopulation.findHomeActivity(person);
			if (homeActivity == null || homeActivity.getCoord() == null) {
				continue;
			}

			Coordinate homeCoordinate = new Coordinate(
					homeActivity.getCoord().getX(),
					homeActivity.getCoord().getY()
			);
			if (AnalyzeMunichPopulation.isInsideMunich(
					munichBoundary,
					homeCoordinate
			)) {
				residents.add(person);
			}
		}
		return residents;
	}

	private static void addClonedResidents(
			Population population,
			List<Person> donors,
			int numberOfClones,
			Random random
	) {
		PopulationFactory factory = population.getFactory();

		for (int cloneNumber = 1; cloneNumber <= numberOfClones; cloneNumber++) {
			Person donor = donors.get(random.nextInt(donors.size()));
			String cloneId = String.format(
					Locale.ROOT,
					"munich2040_%05d_from_%s",
					cloneNumber,
					donor.getId()
			);

			if (population.getPersons().containsKey(Id.createPersonId(cloneId))) {
				throw new IllegalStateException("Generated duplicate person ID: " + cloneId);
			}

			Person clone = copyPerson(donor, cloneId, factory);
			population.addPerson(clone);
		}
	}

	private static Person copyPerson(
			Person source,
			String cloneId,
			PopulationFactory factory
	) {
		Person clone = factory.createPerson(Id.createPersonId(cloneId));
		AttributesUtils.copyTo(source.getAttributes(), clone.getAttributes());

		for (Plan sourcePlan : source.getPlans()) {
			Plan clonedPlan = factory.createPlan();
			PopulationUtils.copyFromTo(sourcePlan, clonedPlan);
			clone.addPlan(clonedPlan);

			if (sourcePlan == source.getSelectedPlan()) {
				clone.setSelectedPlan(clonedPlan);
			}
		}

		return clone;
	}

	private static ProjectionTotals readProjectionTotals(Path csvFile) throws IOException {
		long summed2023 = 0;
		long summed2040 = 0;
		Long explicitTotal2023 = null;
		Long explicitTotal2040 = null;

		try (BufferedReader reader = Files.newBufferedReader(
				csvFile,
				StandardCharsets.UTF_8
		)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				String[] fields = line.split(";", -1);
				if (fields.length < 3) {
					throw new IllegalArgumentException(
							"Invalid projection CSV row: " + line
					);
				}

				String ageGroup = fields[0].trim();
				if (ageGroup.isEmpty() || "age_group".equalsIgnoreCase(ageGroup)) {
					continue;
				}

				long population2023 = parsePopulationValue(fields[1], line);
				long population2040 = parsePopulationValue(fields[2], line);

				if ("total".equalsIgnoreCase(ageGroup)) {
					explicitTotal2023 = population2023;
					explicitTotal2040 = population2040;
				} else {
					summed2023 += population2023;
					summed2040 += population2040;
				}
			}
		}

		long population2023 =
				explicitTotal2023 != null ? explicitTotal2023 : summed2023;
		long population2040 =
				explicitTotal2040 != null ? explicitTotal2040 : summed2040;
		if (population2023 <= 0 || population2040 <= 0) {
			throw new IllegalArgumentException(
					"Projection totals must both be greater than zero."
			);
		}
		return new ProjectionTotals(population2023, population2040);
	}

	private static long parsePopulationValue(String rawValue, String completeRow) {
		String normalized = rawValue.trim()
				.replace(".", "")
				.replace(" ", "");
		try {
			return Long.parseLong(normalized);
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(
					"Invalid population value in CSV row: " + completeRow,
					exception
			);
		}
	}

	private static void requireRegularFile(Path path, String description) {
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException(
					description + " file does not exist: " + path.toAbsolutePath()
			);
		}
	}

	private static void printSummary(
			ProjectionTotals projection,
			double growthFactor,
			int originalPopulationSize,
			int originalMunichResidents,
			int targetMunichResidents,
			int additionalResidents,
			int finalPopulationSize
	) {
		System.out.println();
		System.out.println("Munich population 2040 created");
		System.out.printf(
				"Official population 2023:    %,d%n",
				projection.population2023()
		);
		System.out.printf(
				"Official population 2040:    %,d%n",
				projection.population2040()
		);
		System.out.printf("Growth factor:               %.6f%n", growthFactor);
		System.out.printf("Original MATSim persons:     %,d%n", originalPopulationSize);
		System.out.printf(
				"Original Munich residents:   %,d%n",
				originalMunichResidents
		);
		System.out.printf("Target Munich residents:     %,d%n", targetMunichResidents);
		System.out.printf("Added cloned residents:      %,d%n", additionalResidents);
		System.out.printf("Final MATSim persons:        %,d%n", finalPopulationSize);
		System.out.println("Random seed:                 " + RANDOM_SEED);
	}

	private record ProjectionTotals(long population2023, long population2040) {
	}
}
