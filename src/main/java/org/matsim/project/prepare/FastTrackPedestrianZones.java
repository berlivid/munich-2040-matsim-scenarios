package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;

/**
 * Applies and validates the approved Fast Track pedestrian-zone car-link
 * restrictions. Links remain in the network; only their allowed modes change.
 */
public final class FastTrackPedestrianZones {

    static final Path SPECIFICATION = Path.of(
            "original-input-data/mvv_gtfs_2037/fast_track_pedestrian_zone_links.csv"
    );
    static final Path FAST_TRACK_NETWORK = Path.of(
            "scenarios/munich_fast_track_2040/input_transit/network-with-pt.xml.gz"
    );
    static final Path FAST_TRACK_POPULATION = Path.of(
            "scenarios/munich_fast_track_2040/population_2040_fast_track.xml"
    );
    private static final Set<String> EXPECTED_LINK_IDS = Set.of(
            "39774", "39775", "85662", "148336", "148337", "148338",
            "148339", "148354", "257668", "425785", "425786", "493302",
            "126449"
    );
    static final String TECHNICAL_BOUNDARY_CONNECTOR = "126449";
    private static final Set<String> ROUTING_PERIMETER_NODES = Set.of(
            "3607043222", "361798", "185840092", "18931608"
    );
    private static final String[] EXPECTED_HEADER = {
            "measure_id", "street_name", "matsim_link_id", "osm_way_id",
            "match_method", "confidence", "expected_modes_before",
            "expected_modes_after", "action"
    };

    private FastTrackPedestrianZones() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("No arguments expected.");
        }
        List<Restriction> restrictions = readSpecification(SPECIFICATION);
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(FAST_TRACK_NETWORK.toString());
        validateApplied(network, restrictions);
        validatePerimeterCarConnectivity(network);
        validateCarNetworkConnected(network);
        long references = countActivityLinkReferences(
                FAST_TRACK_POPULATION, linkIds(restrictions)
        );
        if (references != 0) {
            throw new IllegalStateException(
                    references + " activities explicitly reference restricted links; "
                            + "resolve those link assignments explicitly before use."
            );
        }
        System.out.println("Fast Track pedestrian-zone validation: PASS");
        System.out.println("Restricted links: " + restrictions.size());
        System.out.println("Explicit activity references: " + references);
        System.out.println("Car-routing perimeter nodes: " + ROUTING_PERIMETER_NODES);
    }

    static List<Restriction> readSpecification(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing pedestrian-zone specification: " + path);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !Arrays.equals(lines.getFirst().split(",", -1), EXPECTED_HEADER)) {
            throw new IllegalStateException("Unexpected pedestrian-zone CSV header: " + path);
        }
        List<Restriction> result = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            if (lines.get(row).isBlank()) {
                continue;
            }
            String[] values = lines.get(row).split(",", -1);
            if (values.length != EXPECTED_HEADER.length) {
                throw new IllegalStateException(
                        "Pedestrian-zone CSV row " + (row + 1) + " has "
                                + values.length + " columns; expected "
                                + EXPECTED_HEADER.length
                );
            }
            result.add(new Restriction(
                    values[0], values[1], values[2], values[3], values[4],
                    values[5], parseModes(values[6]), parseModes(values[7]), values[8]
            ));
        }
        validateSpecification(result);
        return List.copyOf(result);
    }

    static void apply(Network network, List<Restriction> restrictions) {
        validateSpecification(restrictions);
        for (Restriction restriction : restrictions) {
            Link link = requireLink(network, restriction.linkId());
            Set<String> actualBefore = Set.copyOf(link.getAllowedModes());
            if (!actualBefore.equals(restriction.expectedModesBefore())) {
                throw new IllegalStateException(
                        "Link " + restriction.linkId() + " has modes " + actualBefore
                                + "; expected " + restriction.expectedModesBefore()
                );
            }
            Set<String> changed = new LinkedHashSet<>(actualBefore);
            if (!changed.remove(TransportMode.car)) {
                throw new IllegalStateException(
                        "Link " + restriction.linkId() + " did not allow car before restriction."
                );
            }
            if (!changed.equals(restriction.expectedModesAfter())) {
                throw new IllegalStateException(
                        "Removing car from link " + restriction.linkId()
                                + " would produce " + changed + "; expected "
                                + restriction.expectedModesAfter()
                );
            }
            link.setAllowedModes(Set.copyOf(changed));
        }
        validateApplied(network, restrictions);
    }

    static void validateApplied(Network network, List<Restriction> restrictions) {
        validateSpecification(restrictions);
        for (Restriction restriction : restrictions) {
            Link link = requireLink(network, restriction.linkId());
            Set<String> actual = Set.copyOf(link.getAllowedModes());
            if (actual.contains(TransportMode.car)
                    || !actual.equals(restriction.expectedModesAfter())) {
                throw new IllegalStateException(
                        "Restricted link " + restriction.linkId() + " has modes " + actual
                                + "; expected " + restriction.expectedModesAfter()
                );
            }
        }
    }

    static void validatePerimeterCarConnectivity(Network network) {
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains(TransportMode.car)) {
                continue;
            }
            outgoing.computeIfAbsent(link.getFromNode().getId().toString(), ignored -> new HashSet<>())
                    .add(link.getToNode().getId().toString());
        }
        for (String origin : ROUTING_PERIMETER_NODES) {
            if (!network.getNodes().containsKey(Id.createNodeId(origin))) {
                throw new IllegalStateException("Routing perimeter node is missing: " + origin);
            }
            Set<String> reached = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            reached.add(origin);
            queue.add(origin);
            while (!queue.isEmpty()) {
                for (String next : outgoing.getOrDefault(queue.removeFirst(), Set.of())) {
                    if (reached.add(next)) {
                        queue.addLast(next);
                    }
                }
            }
            Set<String> missing = new TreeSet<>(ROUTING_PERIMETER_NODES);
            missing.removeAll(reached);
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Car-routing smoke test failed from perimeter node " + origin
                                + "; unreachable perimeter nodes: " + missing
                );
            }
        }
    }

    static void validateCarNetworkConnected(Network network) {
        Set<String> disconnected = carLinksOutsideLargestRoutableComponent(network);
        if (!disconnected.isEmpty()) {
            throw new IllegalStateException(
                    "Car network contains links outside its largest routable component: "
                            + disconnected
            );
        }
    }

    static Set<String> carLinksOutsideLargestRoutableComponent(Network network) {
        Network carNetwork = NetworkUtils.createNetwork();
        Map<Id<Node>, Node> copiedNodes = new HashMap<>();
        for (Link source : network.getLinks().values()) {
            if (!source.getAllowedModes().contains(TransportMode.car)) {
                continue;
            }
            Node from = copiedNodes.computeIfAbsent(source.getFromNode().getId(), id -> {
                Node copy = carNetwork.getFactory().createNode(
                        id, source.getFromNode().getCoord()
                );
                carNetwork.addNode(copy);
                return copy;
            });
            Node to = copiedNodes.computeIfAbsent(source.getToNode().getId(), id -> {
                Node copy = carNetwork.getFactory().createNode(
                        id, source.getToNode().getCoord()
                );
                carNetwork.addNode(copy);
                return copy;
            });
            Link copy = carNetwork.getFactory().createLink(source.getId(), from, to);
            copy.setAllowedModes(Set.of(TransportMode.car));
            carNetwork.addLink(copy);
        }
        MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(carNetwork);
        cleaner.run(Set.of(TransportMode.car));
        Set<String> disconnected = new TreeSet<>();
        cleaner.getRemovedLinkIds().forEach(id -> disconnected.add(id.toString()));
        cleaner.getModifiedLinkIds().forEach(id -> disconnected.add(id.toString()));
        return Set.copyOf(disconnected);
    }

    static long countActivityLinkReferences(Path population, Set<String> linkIds)
            throws Exception {
        if (!Files.isRegularFile(population)) {
            throw new IllegalStateException("Missing Fast Track population: " + population);
        }
        long references = 0;
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try (var input = new BufferedInputStream(Files.newInputStream(population), 1 << 20)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && reader.getLocalName().equals("activity")) {
                    String link = reader.getAttributeValue(null, "link");
                    if (link != null && linkIds.contains(link)) {
                        references++;
                    }
                }
            }
            reader.close();
        }
        return references;
    }

    static Set<String> linkIds(List<Restriction> restrictions) {
        Set<String> ids = new TreeSet<>();
        restrictions.forEach(restriction -> ids.add(restriction.linkId()));
        return Set.copyOf(ids);
    }

    /** Immutable IDs used by read-only event validators; does not read or alter the network. */
    static Set<String> readOnlyRestrictedLinkIds() {
        return EXPECTED_LINK_IDS;
    }

    private static void validateSpecification(List<Restriction> restrictions) {
        Set<String> ids = new TreeSet<>();
        int herzogWilhelm = 0;
        int kreuz = 0;
        int technicalBoundary = 0;
        for (Restriction restriction : restrictions) {
            if (!ids.add(restriction.linkId())) {
                throw new IllegalStateException(
                        "Duplicate pedestrian-zone link in specification: "
                                + restriction.linkId()
                );
            }
            if (!restriction.measureId().equals("XLSX_ROW_06")
                    || !restriction.confidence().equals("high")
                    || !restriction.action().equals("remove_car_fast_track_only")) {
                throw new IllegalStateException(
                        "Unexpected metadata for pedestrian-zone link " + restriction.linkId()
                );
            }
            if (!restriction.expectedModesBefore().contains(TransportMode.car)) {
                throw new IllegalStateException(
                        "Specification does not remove car from link " + restriction.linkId()
                );
            }
            Set<String> expectedAfter = new HashSet<>(restriction.expectedModesBefore());
            expectedAfter.remove(TransportMode.car);
            if (!expectedAfter.equals(restriction.expectedModesAfter())) {
                throw new IllegalStateException(
                        "Mode transition is inconsistent for link " + restriction.linkId()
                );
            }
            if (restriction.streetName().equals("Herzog-Wilhelm-Straße")) {
                if (!restriction.matchMethod().equals("both")) {
                    throw new IllegalStateException(
                            "Unexpected match method for spatial pedestrian-zone link "
                                    + restriction.linkId()
                    );
                }
                herzogWilhelm++;
            } else if (restriction.streetName().equals("Kreuzstraße")) {
                if (!restriction.matchMethod().equals("both")) {
                    throw new IllegalStateException(
                            "Unexpected match method for spatial pedestrian-zone link "
                                    + restriction.linkId()
                    );
                }
                kreuz++;
            } else if (restriction.streetName().equals("technical boundary connector")
                    && restriction.linkId().equals(TECHNICAL_BOUNDARY_CONNECTOR)
                    && restriction.osmWayId().equals("107023516")
                    && restriction.matchMethod().equals("network_topology")) {
                technicalBoundary++;
            } else {
                throw new IllegalStateException(
                        "Unexpected street in pedestrian-zone specification: "
                                + restriction.streetName()
                );
            }
        }
        if (!ids.equals(EXPECTED_LINK_IDS) || herzogWilhelm != 11 || kreuz != 1
                || technicalBoundary != 1) {
            throw new IllegalStateException(
                    "Pedestrian-zone specification must contain the approved 11 + 1 "
                            + "spatial links and one technical boundary connector; "
                            + "found ids=" + ids + ", street counts="
                            + herzogWilhelm + "+" + kreuz + "+" + technicalBoundary
            );
        }
    }

    private static Link requireLink(Network network, String id) {
        Link link = network.getLinks().get(Id.createLinkId(id));
        if (link == null) {
            throw new IllegalStateException("Required pedestrian-zone link is missing: " + id);
        }
        return link;
    }

    private static Set<String> parseModes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> modes = new TreeSet<>();
        for (String mode : value.split("[|;]")) {
            if (!mode.isBlank()) {
                modes.add(mode.trim());
            }
        }
        return Set.copyOf(modes);
    }

    record Restriction(
            String measureId,
            String streetName,
            String linkId,
            String osmWayId,
            String matchMethod,
            String confidence,
            Set<String> expectedModesBefore,
            Set<String> expectedModesAfter,
            String action
    ) {
    }
}
