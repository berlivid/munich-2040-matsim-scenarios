package org.matsim.project.prepare;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;

/** Read-only, prepared City of Munich municipal boundary in the MATSim CRS. */
public final class MunichMunicipalBoundary {
    public static final Path DEFAULT_FILE = Path.of(
            "original-input-data/munich-demography/munich_boundary.json");
    public static final String CRS = "EPSG:31468";
    public static final double PRACTICAL_BOUNDARY_TOLERANCE_METRES = 1.0;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final Path source;
    private final String sha256;
    private final String sourceGeoJsonType;
    private final Geometry geometry;
    private final PreparedGeometry prepared;
    private final IndexedFacetDistance boundaryDistance;

    private MunichMunicipalBoundary(Path source, String sha256, String sourceGeoJsonType,
                                    Geometry geometry) {
        this.source = source;
        this.sha256 = sha256;
        this.sourceGeoJsonType = sourceGeoJsonType;
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
        this.boundaryDistance = new IndexedFacetDistance(geometry.getBoundary());
    }

    public static MunichMunicipalBoundary loadDefault() throws IOException {
        return load(DEFAULT_FILE);
    }

    public static MunichMunicipalBoundary load(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Municipal boundary file is missing: "
                    + source.toAbsolutePath());
        }
        AnalyzeMunichPopulation.ParsedGeoJson parsed = AnalyzeMunichPopulation.readGeoJson(source);
        Geometry geometry = parsed.geometry();
        if (geometry.isEmpty()) {
            throw new IllegalArgumentException("The Munich municipal boundary is empty");
        }
        if (!geometry.isValid()) {
            throw new IllegalArgumentException("The Munich municipal boundary is invalid");
        }
        if (geometry.getDimension() != 2) {
            throw new IllegalArgumentException("The Munich municipal boundary is not polygonal: "
                    + geometry.getGeometryType());
        }
        Envelope envelope = geometry.getEnvelopeInternal();
        if (envelope.getMinX() < 4_000_000 || envelope.getMaxX() > 5_000_000
                || envelope.getMinY() < 5_000_000 || envelope.getMaxY() > 6_000_000) {
            throw new IllegalArgumentException("Boundary coordinate range is incompatible with EPSG:31468: "
                    + envelope);
        }
        return new MunichMunicipalBoundary(source.normalize(), sha256(source),
                parsed.sourceType(), geometry);
    }

    /** Uses covers so that points exactly on the administrative boundary are inside. */
    public boolean covers(Coord coordinate) {
        if (!isFinite(coordinate)) return false;
        Coordinate jts = new Coordinate(coordinate.getX(), coordinate.getY());
        if (!geometry.getEnvelopeInternal().covers(jts)) return false;
        return prepared.covers(GEOMETRY_FACTORY.createPoint(jts));
    }

    public boolean isValidCoordinate(Coord coordinate) {
        return isFinite(coordinate);
    }

    public double distanceToBoundaryMetres(Coord coordinate) {
        if (!isFinite(coordinate)) return Double.NaN;
        return boundaryDistance.distance(GEOMETRY_FACTORY.createPoint(
                new Coordinate(coordinate.getX(), coordinate.getY())));
    }

    public boolean isPracticallyOnBoundary(Coord coordinate) {
        double distance = distanceToBoundaryMetres(coordinate);
        return Double.isFinite(distance)
                && distance <= PRACTICAL_BOUNDARY_TOLERANCE_METRES;
    }

    public Path source() { return source; }
    public String sha256() { return sha256; }
    public String crs() { return CRS; }
    public String sourceGeoJsonType() { return sourceGeoJsonType; }
    public String geometryType() { return geometry.getGeometryType(); }
    public boolean isValid() { return geometry.isValid(); }
    public boolean isEmpty() { return geometry.isEmpty(); }
    public int geometryCount() { return geometry.getNumGeometries(); }
    public Envelope envelope() { return new Envelope(geometry.getEnvelopeInternal()); }

    Geometry geometry() { return geometry; }

    private static boolean isFinite(Coord coordinate) {
        return coordinate != null && Double.isFinite(coordinate.getX())
                && Double.isFinite(coordinate.getY());
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(
                    new BufferedInputStream(Files.newInputStream(file)), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
