package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

class FastTrackPedestrianZonesTest {

    @Test
    void specificationContainsExactlyTheApprovedLinks() throws Exception {
        List<FastTrackPedestrianZones.Restriction> restrictions =
                FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                );

        assertEquals(13, restrictions.size());
        assertEquals(Set.of(
                "39774", "39775", "85662", "148336", "148337", "148338",
                "148339", "148354", "257668", "425785", "425786", "493302",
                "126449"
        ), FastTrackPedestrianZones.linkIds(restrictions));
        assertEquals(11, restrictions.stream()
                .filter(row -> row.streetName().equals("Herzog-Wilhelm-Straße")).count());
        assertEquals(1, restrictions.stream()
                .filter(row -> row.streetName().equals("Kreuzstraße")).count());
        assertEquals(1, restrictions.stream()
                .filter(row -> row.streetName().equals("technical boundary connector"))
                .count());
    }

    @Test
    void applyRemovesOnlyCarFromSpecifiedLinks() throws Exception {
        List<FastTrackPedestrianZones.Restriction> restrictions =
                FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                );
        Network network = NetworkUtils.createNetwork();
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index <= restrictions.size(); index++) {
            nodes.add(node(network, "n" + index, index, 0));
        }
        for (int index = 0; index < restrictions.size(); index++) {
            link(network, restrictions.get(index).linkId(), nodes.get(index),
                    nodes.get(index + 1), Set.of(TransportMode.car));
        }
        Link untouched = link(network, "untouched", nodes.getLast(), nodes.getFirst(),
                Set.of(TransportMode.car, TransportMode.pt));

        FastTrackPedestrianZones.apply(network, restrictions);

        for (FastTrackPedestrianZones.Restriction restriction : restrictions) {
            Link changed = network.getLinks().get(Id.createLinkId(restriction.linkId()));
            assertEquals(Set.of(), changed.getAllowedModes());
        }
        assertEquals(Set.of(TransportMode.car, TransportMode.pt), untouched.getAllowedModes());
    }

    @Test
    void productionNetworkAndPopulationPassFocusedValidation() throws Exception {
        List<FastTrackPedestrianZones.Restriction> restrictions =
                FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                );
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(
                FastTrackPedestrianZones.FAST_TRACK_NETWORK.toString()
        );

        FastTrackPedestrianZones.validateApplied(network, restrictions);
        FastTrackPedestrianZones.validatePerimeterCarConnectivity(network);
        FastTrackPedestrianZones.validateCarNetworkConnected(network);
        assertEquals(0, FastTrackPedestrianZones.countActivityLinkReferences(
                FastTrackPedestrianZones.FAST_TRACK_POPULATION,
                FastTrackPedestrianZones.linkIds(restrictions)
        ));
        for (FastTrackPedestrianZones.Restriction restriction : restrictions) {
            assertFalse(network.getLinks().get(Id.createLinkId(restriction.linkId()))
                    .getAllowedModes().contains(TransportMode.car));
        }
    }

    @Test
    void bauNetworkRetainsCarOnAllSpecifiedLinks() throws Exception {
        List<FastTrackPedestrianZones.Restriction> restrictions =
                FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                );
        Network bau = NetworkUtils.createNetwork();
        new MatsimNetworkReader(bau).readFile(Path.of(
                "scenarios/munich_bau_2040/input_transit/network-with-pt.xml.gz"
        ).toString());

        for (FastTrackPedestrianZones.Restriction restriction : restrictions) {
            assertEquals(restriction.expectedModesBefore(),
                    bau.getLinks().get(Id.createLinkId(restriction.linkId()))
                            .getAllowedModes());
        }
    }

    @Test
    void technicalBoundaryConnectorIsCausedByTheSpatialRestrictions() throws Exception {
        List<FastTrackPedestrianZones.Restriction> restrictions =
                FastTrackPedestrianZones.readSpecification(
                        FastTrackPedestrianZones.SPECIFICATION
                );
        Network bau = NetworkUtils.createNetwork();
        new MatsimNetworkReader(bau).readFile(Path.of(
                "scenarios/munich_bau_2040/input_transit/network-with-pt.xml.gz"
        ).toString());

        Link connector = bau.getLinks().get(Id.createLinkId(
                FastTrackPedestrianZones.TECHNICAL_BOUNDARY_CONNECTOR
        ));
        assertEquals("340075407", connector.getFromNode().getId().toString());
        assertEquals("1097999885", connector.getToNode().getId().toString());
        assertEquals(Set.of(TransportMode.car), connector.getAllowedModes());
        Node boundaryNode = bau.getNodes().get(Id.createNodeId("340075407"));
        assertEquals(Set.of("39775", "85662"), boundaryNode.getInLinks().values()
                .stream()
                .filter(link -> link.getAllowedModes().contains(TransportMode.car))
                .map(link -> link.getId().toString())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("39774", "126449"), boundaryNode.getOutLinks().values()
                .stream()
                .filter(link -> link.getAllowedModes().contains(TransportMode.car))
                .map(link -> link.getId().toString())
                .collect(java.util.stream.Collectors.toSet()));

        for (FastTrackPedestrianZones.Restriction restriction : restrictions) {
            if (!restriction.linkId().equals(
                    FastTrackPedestrianZones.TECHNICAL_BOUNDARY_CONNECTOR)) {
                NetworkUtils.removeAllowedMode(
                        bau.getLinks().get(Id.createLinkId(restriction.linkId())),
                        TransportMode.car
                );
            }
        }
        assertEquals(Set.of(FastTrackPedestrianZones.TECHNICAL_BOUNDARY_CONNECTOR),
                FastTrackPedestrianZones.carLinksOutsideLargestRoutableComponent(bau));
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
