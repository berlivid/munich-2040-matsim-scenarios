package org.matsim.project.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public class AnalyzeMunichPopulation {

	private static final Path DEFAULT_POPULATION = Path.of(
			"scenarios/munich_base_2023/munich-v1.0-5pct.plans.xml"
	);
	private static final Path DEFAULT_BOUNDARY = Path.of(
			"original-input-data/munich-demography/munich_boundary.json"
	);

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	public static void main(String[] args) throws IOException {
		Path populationFile = args.length >= 1 ? Path.of(args[0]) : DEFAULT_POPULATION;
		Path boundaryFile = args.length >= 2 ? Path.of(args[1]) : DEFAULT_BOUNDARY;

		requireRegularFile(populationFile, "Population");
		requireRegularFile(boundaryFile, "Munich boundary");

		Geometry munichBoundary = readGeoJsonGeometry(boundaryFile);
		if (munichBoundary.isEmpty()) {
			throw new IllegalArgumentException("The Munich boundary contains no geometry.");
		}
		if (!munichBoundary.isValid()) {
			throw new IllegalArgumentException("The Munich boundary geometry is invalid.");
		}

		PreparedGeometry preparedBoundary = PreparedGeometryFactory.prepare(munichBoundary);
		PopulationCounters counters = new PopulationCounters();

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		StreamingPopulationReader reader = new StreamingPopulationReader(scenario);
		reader.addAlgorithm(person -> classifyPerson(person, preparedBoundary, counters));

		System.out.println("Reading population: " + populationFile.toAbsolutePath());
		System.out.println("Reading boundary:   " + boundaryFile.toAbsolutePath());
		System.out.println("Boundary envelope:  " + munichBoundary.getEnvelopeInternal());
		reader.readFile(populationFile.toString());

		printResults(counters);
	}

	private static void classifyPerson(
			Person person,
			PreparedGeometry munichBoundary,
			PopulationCounters counters
	) {
		counters.total.increment();
		classifyNonHomeActivities(person, munichBoundary, counters);

		Activity homeActivity = findHomeActivity(person);
		if (homeActivity == null) {
			counters.withoutHomeActivity.increment();
			return;
		}
		if (homeActivity.getCoord() == null) {
			counters.withoutHomeCoordinate.increment();
			return;
		}

		Coordinate homeCoordinate = new Coordinate(
				homeActivity.getCoord().getX(),
				homeActivity.getCoord().getY()
		);

		// covers() also counts points exactly on the municipal boundary as Munich.
		if (isInsideMunich(munichBoundary, homeCoordinate)) {
			counters.insideMunich.increment();
		} else {
			counters.outsideMunich.increment();
		}

	}

	static Activity findHomeActivity(Person person) {
		Plan selectedPlan = getRelevantPlan(person);
		if (selectedPlan == null) {
			return null;
		}

		for (PlanElement element : selectedPlan.getPlanElements()) {
			if (element instanceof Activity activity
					&& "home".equalsIgnoreCase(activity.getType())) {
				return activity;
			}
		}
		return null;
	}

	private static void classifyNonHomeActivities(
			Person person,
			PreparedGeometry munichBoundary,
			PopulationCounters counters
	) {
		Plan selectedPlan = getRelevantPlan(person);
		if (selectedPlan == null) {
			return;
		}

		ActivityLocations locations = new ActivityLocations();

		for (PlanElement element : selectedPlan.getPlanElements()) {
			if (!(element instanceof Activity activity)
					|| "home".equalsIgnoreCase(activity.getType())
					|| activity.getCoord() == null) {
				continue;
			}

			Coordinate coordinate = new Coordinate(
					activity.getCoord().getX(),
					activity.getCoord().getY()
			);
			boolean insideMunich = isInsideMunich(munichBoundary, coordinate);

			if ("work".equalsIgnoreCase(activity.getType())) {
				locations.workInside |= insideMunich;
				locations.workOutside |= !insideMunich;
			} else if ("education".equalsIgnoreCase(activity.getType())) {
				locations.educationInside |= insideMunich;
				locations.educationOutside |= !insideMunich;
			} else {
				locations.otherInside |= insideMunich;
				locations.otherOutside |= !insideMunich;
			}
		}

		incrementIfTrue(locations.workInside, counters.workInsideMunich);
		incrementIfTrue(locations.workOutside, counters.workOutsideMunich);
		incrementIfTrue(locations.educationInside, counters.educationInsideMunich);
		incrementIfTrue(locations.educationOutside, counters.educationOutsideMunich);
		incrementIfTrue(locations.otherInside, counters.otherInsideMunich);
		incrementIfTrue(locations.otherOutside, counters.otherOutsideMunich);
	}

	private static Plan getRelevantPlan(Person person) {
		Plan selectedPlan = person.getSelectedPlan();
		if (selectedPlan == null && !person.getPlans().isEmpty()) {
			selectedPlan = person.getPlans().getFirst();
		}
		return selectedPlan;
	}

	private static void incrementIfTrue(boolean condition, LongAdder counter) {
		if (condition) {
			counter.increment();
		}
	}

	static boolean isInsideMunich(
			PreparedGeometry munichBoundary,
			Coordinate coordinate
	) {
		if (!munichBoundary.getGeometry().getEnvelopeInternal().contains(coordinate)) {
			return false;
		}
		return munichBoundary.covers(GEOMETRY_FACTORY.createPoint(coordinate));
	}

	static Geometry readGeoJsonGeometry(Path geoJsonFile) throws IOException {
		return readGeoJson(geoJsonFile).geometry();
	}

	static ParsedGeoJson readGeoJson(Path geoJsonFile) throws IOException {
		JsonNode root = new ObjectMapper().readTree(geoJsonFile.toFile());
		return new ParsedGeoJson(requiredText(root, "type"), parseGeometry(root));
	}

	private static Geometry parseGeometry(JsonNode geometryNode) {
		String type = requiredText(geometryNode, "type");

		return switch (type) {
			case "GeometryCollection" -> parseGeometryCollection(geometryNode);
			case "MultiPolygon" -> parseMultiPolygon(geometryNode);
			case "Polygon" -> parsePolygon(geometryNode.get("coordinates"));
			default -> throw new IllegalArgumentException(
					"Unsupported GeoJSON geometry type: " + type
			);
		};
	}

	private static Geometry parseGeometryCollection(JsonNode geometryNode) {
		JsonNode geometriesNode = geometryNode.get("geometries");
		if (geometriesNode == null || !geometriesNode.isArray()) {
			throw new IllegalArgumentException(
					"GeoJSON GeometryCollection has no geometries array."
			);
		}

		List<Geometry> geometries = new ArrayList<>();
		for (JsonNode child : geometriesNode) {
			geometries.add(parseGeometry(child));
		}
		if (geometries.size() == 1) {
			return geometries.getFirst();
		}
		return GEOMETRY_FACTORY.createGeometryCollection(
				geometries.toArray(Geometry[]::new)
		);
	}

	private static Geometry parseMultiPolygon(JsonNode geometryNode) {
		JsonNode polygonsNode = geometryNode.get("coordinates");
		if (polygonsNode == null || !polygonsNode.isArray()) {
			throw new IllegalArgumentException(
					"GeoJSON MultiPolygon has no coordinates array."
			);
		}

		List<Polygon> polygons = new ArrayList<>();
		for (JsonNode polygonNode : polygonsNode) {
			polygons.add(parsePolygon(polygonNode));
		}
		return GEOMETRY_FACTORY.createMultiPolygon(polygons.toArray(Polygon[]::new));
	}

	private static Polygon parsePolygon(JsonNode ringsNode) {
		if (ringsNode == null || !ringsNode.isArray() || ringsNode.isEmpty()) {
			throw new IllegalArgumentException("GeoJSON Polygon contains no rings.");
		}

		LinearRing shell = parseRing(ringsNode.get(0));
		LinearRing[] holes = new LinearRing[ringsNode.size() - 1];
		for (int index = 1; index < ringsNode.size(); index++) {
			holes[index - 1] = parseRing(ringsNode.get(index));
		}
		return GEOMETRY_FACTORY.createPolygon(shell, holes);
	}

	private static LinearRing parseRing(JsonNode ringNode) {
		if (ringNode == null || !ringNode.isArray() || ringNode.size() < 4) {
			throw new IllegalArgumentException(
					"A GeoJSON linear ring needs at least four coordinates."
			);
		}

		Coordinate[] coordinates = new Coordinate[ringNode.size()];
		for (int index = 0; index < ringNode.size(); index++) {
			JsonNode position = ringNode.get(index);
			if (!position.isArray() || position.size() < 2) {
				throw new IllegalArgumentException("Invalid GeoJSON coordinate.");
			}
			coordinates[index] = new Coordinate(
					position.get(0).asDouble(),
					position.get(1).asDouble()
			);
		}
		return GEOMETRY_FACTORY.createLinearRing(coordinates);
	}

	private static String requiredText(JsonNode node, String fieldName) {
		JsonNode value = node.get(fieldName);
		if (value == null || !value.isTextual()) {
			throw new IllegalArgumentException(
					"GeoJSON field '" + fieldName + "' is missing."
			);
		}
		return value.asText();
	}

	private static void requireRegularFile(Path path, String description) {
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException(
					description + " file does not exist: " + path.toAbsolutePath()
			);
		}
	}

	private static void printResults(PopulationCounters counters) {
		long total = counters.total.sum();
		long insideMunich = counters.insideMunich.sum();
		long outsideMunich = counters.outsideMunich.sum();
		long withoutHomeActivity = counters.withoutHomeActivity.sum();
		long withoutHomeCoordinate = counters.withoutHomeCoordinate.sum();
		long workInsideMunich = counters.workInsideMunich.sum();
		long workOutsideMunich = counters.workOutsideMunich.sum();
		long educationInsideMunich = counters.educationInsideMunich.sum();
		long educationOutsideMunich = counters.educationOutsideMunich.sum();
		long otherInsideMunich = counters.otherInsideMunich.sum();
		long otherOutsideMunich = counters.otherOutsideMunich.sum();

		System.out.println();
		System.out.println("Population analysis (5% sample)");
		System.out.printf("Total persons:              %,d%n", total);
		System.out.println();
		System.out.printf("Home inside Munich:         %,d%n", insideMunich);
		System.out.printf("Home outside Munich:        %,d%n", outsideMunich);
		System.out.printf("Without home activity:      %,d%n", withoutHomeActivity);
		System.out.printf("Without home coordinate:    %,d%n", withoutHomeCoordinate);

		long classified = insideMunich + outsideMunich;
		if (classified > 0) {
			double insideShare = 100.0 * insideMunich / classified;
			System.out.printf("Munich share (classified):  %.2f%%%n", insideShare);
		}

		long expectedMunichSample = Math.round(1_488_719 * 0.05);
		System.out.printf("Expected from 2023 data:    %,d%n", expectedMunichSample);
		System.out.printf(
				"Difference to expectation:  %+,d%n",
				insideMunich - expectedMunichSample
		);

		System.out.println();
		System.out.printf("Work inside Munich:         %,d%n", workInsideMunich);
		System.out.printf("Work outside Munich:        %,d%n", workOutsideMunich);
		System.out.printf("Education inside Munich:    %,d%n", educationInsideMunich);
		System.out.printf("Education outside Munich:   %,d%n", educationOutsideMunich);
		System.out.printf("Other inside Munich:        %,d%n", otherInsideMunich);
		System.out.printf("Other outside Munich:       %,d%n", otherOutsideMunich);
	}

	private static final class PopulationCounters {
		private final LongAdder total = new LongAdder();
		private final LongAdder insideMunich = new LongAdder();
		private final LongAdder outsideMunich = new LongAdder();
		private final LongAdder withoutHomeActivity = new LongAdder();
		private final LongAdder withoutHomeCoordinate = new LongAdder();
		private final LongAdder workInsideMunich = new LongAdder();
		private final LongAdder workOutsideMunich = new LongAdder();
		private final LongAdder educationInsideMunich = new LongAdder();
		private final LongAdder educationOutsideMunich = new LongAdder();
		private final LongAdder otherInsideMunich = new LongAdder();
		private final LongAdder otherOutsideMunich = new LongAdder();
	}

	private static final class ActivityLocations {
		private boolean workInside;
		private boolean workOutside;
		private boolean educationInside;
		private boolean educationOutside;
		private boolean otherInside;
		private boolean otherOutside;
	}

	record ParsedGeoJson(String sourceType, Geometry geometry) { }
}
