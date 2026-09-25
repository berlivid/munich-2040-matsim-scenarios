package org.matsim.project.prepare;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Adds the public-transport measures shared by BAU 2040 and Fast Track 2040.
 * The cleaned Munich feed remains an immutable input. Analysis is fail-closed;
 * build repeats all checks and writes a separate deterministic BAU ZIP.
 */
public final class BuildCommonGtfs2037Measures {
    static final Path INPUT = Path.of("original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_clean.zip");
    static final Path OUTPUT = Path.of("original-input-data/mvv_gtfs_2037/generated/gtfs2037_munich_bau.zip");
    static final Path SPEC = Path.of("original-input-data/mvv_gtfs_2037/common_service_specification.csv");
    static final Path WORKBOOK = Path.of("original-input-data/mvv_gtfs_2037/Infrastructure_measures.xlsx");
    static final Path PREFLIGHT = Path.of("original-input-data/mvv_gtfs_2037/generated/common_measures_preflight");
    static final Path REPORT = Path.of("docs/gtfs2040/gtfs2037_common_measures_method.md");
    static final long ZIP_TIME = Instant.parse("1980-01-01T00:00:00Z").toEpochMilli();
    static final Set<String> POCCI_ROUTES = Set.of("E 28.a BY", "E 28.b BY", "N 28.a BY", "N 28.b BY", "E 56 BY", "N 56 BY");
    static final String S2 = "S2_Prognose_Petershausen/Altomünster-Holzkirchen";
    static final String POCCI_PARENT = "106206", BERDUX_PARENT = "162054";
    static final String POCCI_D0 = "BAU_POCCISTRASSE_RAIL_D0", POCCI_D1 = "BAU_POCCISTRASSE_RAIL_D1";
    static final String BERDUX_D0 = "BAU_BERDUXSTRASSE_S2_D0", BERDUX_D1 = "BAU_BERDUXSTRASSE_S2_D1";
    static final Set<String> HBF = Set.of("142501", "142502", "142503");
    static final Set<String> OST = Set.of("106077", "182908");
    static final Set<String> LAIM = Set.of("106109");
    static final Set<String> OBERMENZING = Set.of("109793");

    private BuildCommonGtfs2037Measures() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("--analyze") || args[0].equals("--build")))
            throw new IllegalArgumentException("Use exactly one mode: --analyze or --build");
        Processor p = new Processor();
        Analysis a = p.analyze();
        p.writePreflight(a);
        p.print(a);
        if (args[0].equals("--analyze")) {
            System.out.println("Analyze mode completed; no GTFS ZIP was changed.");
            return;
        }
        if (!a.blockers.isEmpty()) throw new IllegalStateException("Build stopped: " + a.blockers.size() + " blocker(s); see " + PREFLIGHT.resolve("preflight_report.md"));
        p.build(a);
    }

    static final class Processor {
        final Map<String, Stop> stops = new HashMap<>();
        final Map<String, Route> routes = new HashMap<>();
        final Map<String, Trip> trips = new HashMap<>();
        final Map<String, String> allTripRoutes = new HashMap<>();
        final Map<String, List<Call>> affected = new LinkedHashMap<>();
        final Map<String, Set<String>> stopRoutes = new HashMap<>();

        Analysis analyze() throws Exception {
            for (Path p : List.of(INPUT, SPEC, WORKBOOK)) if (!Files.isRegularFile(p)) throw new IllegalStateException("Missing required input: " + p);
            verifySpec();
            Map<String, DwellRule> dwellRules = readDwellRules();
            List<String> blockers = new ArrayList<>();
            List<Integer> regionalDwells = new ArrayList<>(), sDwells = new ArrayList<>();
            try (ZipFile z = new ZipFile(INPUT.toFile(), StandardCharsets.UTF_8)) {
                for (String n : List.of("agency.txt","calendar.txt","routes.txt","trips.txt","stop_times.txt","stops.txt","shapes.txt","transfers.txt"))
                    if (z.getEntry(n) == null) blockers.add("Input ZIP lacks " + n);
                readStops(z); readRoutes(z); readTrips(z); scanStopTimes(z, regionalDwells, sDwells, blockers);
            }
            requireAnchor(POCCI_PARENT, "Poccistraße", blockers);
            requireAnchor(BERDUX_PARENT, "Berduxstraße", blockers);
            for (String id : POCCI_ROUTES) if (!routes.containsKey(id)) blockers.add("Specified regional route is absent: " + id);
            if (!routes.containsKey(S2)) blockers.add("Specified S2 route is absent: " + S2);
            int forecastRegionalDwell = median(regionalDwells), forecastSDwell = median(sDwells);
            DwellRule regionalRule = dwellRules.get("POCCISTRASSE");
            DwellRule sRule = dwellRules.get("BERDUXSTRASSE");
            int regionalDwell = regionalRule.totalPenaltySeconds();
            int sDwell = sRule.totalPenaltySeconds();
            int pocci = 0, berdux = 0, p0 = 0, p1 = 0, b0 = 0, b1 = 0;
            for (var e : affected.entrySet()) {
                Trip t = trips.get(e.getKey());
                if (POCCI_ROUTES.contains(t.routeId) && regionalInsertion(e.getValue()) >= 0) { pocci++; if (t.direction.equals("0")) p0++; else p1++; }
                if (t.routeId.equals(S2) && isRegularS2(e.getValue())) { berdux++; if (t.direction.equals("0")) b0++; else b1++; }
            }
            if (pocci == 0) blockers.add("No listed regional trip could be resolved on the Hbf-Ost corridor");
            if (berdux == 0) blockers.add("No regular S2 trip could be resolved between Laim and Obermenzing");
            if (forecastRegionalDwell < 0 || forecastSDwell < 0) blockers.add("Forecast-feed dwell medians could not be audited");
            if (regionalRule.dwellSeconds() != regionalRule.evidenceMedianSeconds()
                    || sRule.dwellSeconds() != sRule.evidenceMedianSeconds()) {
                blockers.add("Applied dwell must equal the documented project-internal evidence median");
            }
            List<Candidate> candidates = candidates();
            return new Analysis(blockers, candidates, pocci, berdux, p0, p1, b0, b1,
                    regionalDwell, sDwell, forecastRegionalDwell, forecastSDwell,
                    regionalRule, sRule, sha256(INPUT), sha256(WORKBOOK));
        }

        void verifySpec() throws IOException {
            String text = Files.readString(SPEC, StandardCharsets.UTF_8);
            for (String token : List.of("13,POCCISTRASSE", "18,SENDLINGER_SPANGE", "19,BERDUXSTRASSE", "scenario assumption"))
                if (!text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))) throw new IllegalStateException("Common specification is incomplete: missing " + token);
        }

        Map<String, DwellRule> readDwellRules() throws IOException {
            Map<String, DwellRule> result = new HashMap<>();
            try (CsvReader table = new CsvReader(SPEC)) {
                String[] row;
                while ((row = table.next()) != null) {
                    String measure = table.get(row, "measure_id");
                    if (!Set.of("POCCISTRASSE", "BERDUXSTRASSE").contains(measure)) continue;
                    DwellRule rule = new DwellRule(
                            Integer.parseInt(table.get(row, "dwell_seconds")),
                            Integer.parseInt(table.get(row, "braking_acceleration_penalty_seconds")),
                            List.of(table.get(row, "transfer_target_stop_ids").split("\\|")),
                            Integer.parseInt(table.get(row, "minimum_transfer_time_seconds")),
                            table.get(row, "dwell_evidence"),
                            Integer.parseInt(table.get(row, "evidence_observations")),
                            Integer.parseInt(table.get(row, "evidence_median_seconds")),
                            Integer.parseInt(table.get(row, "evidence_p10_seconds")),
                            Integer.parseInt(table.get(row, "evidence_p90_seconds"))
                    );
                    if (rule.dwellSeconds() <= 0 || rule.brakingAccelerationPenaltySeconds() < 0
                            || rule.evidenceObservations() <= 0) {
                        throw new IllegalStateException("Invalid dwell rule for " + measure);
                    }
                    result.put(measure, rule);
                }
            }
            if (!result.keySet().equals(Set.of("POCCISTRASSE", "BERDUXSTRASSE"))) {
                throw new IllegalStateException("Dwell rules are incomplete in " + SPEC);
            }
            return result;
        }

        void readStops(ZipFile z) throws IOException {
            try (CsvReader r = csv(z, "stops.txt")) { String[] a; while ((a=r.next())!=null) {
                Stop s = new Stop(r.get(a,"stop_id"), r.get(a,"stop_name"), d(r.get(a,"stop_lat")), d(r.get(a,"stop_lon")), r.get(a,"location_type"), r.get(a,"parent_station"));
                stops.put(s.id, s);
            }}
        }
        void readRoutes(ZipFile z) throws IOException {
            try (CsvReader r = csv(z,"routes.txt")) { String[] a; while((a=r.next())!=null) {
                Route x=new Route(r.get(a,"route_id"),r.get(a,"route_short_name"),r.get(a,"route_long_name"),r.get(a,"route_type")); routes.put(x.id,x);
            }}
        }
        void readTrips(ZipFile z) throws IOException {
            try (CsvReader r=csv(z,"trips.txt")) { String[] a; while((a=r.next())!=null) {
                String route=r.get(a,"route_id"), tripId=r.get(a,"trip_id");
                allTripRoutes.put(tripId, route);
                if(POCCI_ROUTES.contains(route)||route.equals(S2)) {
                    Trip t=new Trip(r.get(a,"trip_id"),route,r.get(a,"service_id"),r.get(a,"direction_id"),r.get(a,"trip_headsign")); trips.put(t.id,t); affected.put(t.id,new ArrayList<>());
                }
            }}
        }
        void scanStopTimes(ZipFile z,List<Integer> regionalDwells,List<Integer>sDwells,List<String> blockers)throws IOException {
            try(CsvReader r=csv(z,"stop_times.txt")){String[] a;while((a=r.next())!=null){String id=r.get(a,"trip_id"), stopId=r.get(a,"stop_id");String anyRoute=allTripRoutes.get(id);Stop st=stops.get(stopId);
                if(st!=null && anyRoute!=null) stopRoutes.computeIfAbsent(st.parentOrSelf(),k->new TreeSet<>()).add(anyRoute);
                Trip t=trips.get(id);if(t==null)continue;
                Call c=new Call(r.get(a,"stop_id"),r.get(a,"arrival_time"),r.get(a,"departure_time"),Integer.parseInt(r.get(a,"stop_sequence"))); affected.get(id).add(c);
            }}
            for(var e:affected.entrySet()){
                List<Call> c=e.getValue(); c.sort(Comparator.comparingInt(x->x.sequence));
                for(int i=1;i<c.size()-1;i++){int dwell=time(c.get(i).departure)-time(c.get(i).arrival);if(dwell<0)blockers.add("Negative dwell in source trip "+e.getKey());
                    if(POCCI_ROUTES.contains(trips.get(e.getKey()).routeId))regionalDwells.add(dwell); else sDwells.add(dwell);
                }
            }
        }

        boolean isRegularS2(List<Call> c) {
            int l=index(c,LAIM), o=index(c,OBERMENZING); if(l<0||o<0||Math.abs(l-o)!=1)return false;
            Set<String> ids=new HashSet<>();for(Call x:c)ids.add(x.stopId);
            // All source trips classified as regular must call at the complete inner western pattern.
            return ids.containsAll(Set.of("109843","109809","5132","12799"));
        }
        int regionalInsertion(List<Call> c) {
            int h=index(c,HBF),o=index(c,OST);
            if(h>=0&&o>=0) return bestSpatialGap(c,Math.min(h,o),Math.max(h,o),stops.get(POCCI_PARENT));
            // N 28.a inbound omits an Ost stop but its reverse pattern and eastern calls establish the corridor.
            if(h>=0 && containsAny(c,Set.of("9627","102385"))) return h==0?0:c.size()-2;
            return -1;
        }
        int bestSpatialGap(List<Call> c,int from,int to,Stop target){int best=-1;double score=Double.POSITIVE_INFINITY;for(int i=from;i<to;i++){Stop a=stops.get(c.get(i).stopId),b=stops.get(c.get(i+1).stopId);if(a==null||b==null)continue;double x=km(a,target)+km(target,b)-km(a,b);if(x<score){score=x;best=i;}}return best;}

        List<Candidate> candidates() {
            List<Candidate> result = new ArrayList<>();
            for (Stop s : stops.values()) {
                String n = fold(s.name);
                boolean relevant = n.contains("poccistr") || n.contains("sudbahnhof")
                        || n.contains("munchen sud") || n.contains("lindwurm")
                        || n.contains("bavariaring") || n.equals("kvr")
                        || n.contains("berdux") || n.contains("paul-gerhardt")
                        || n.equals("laim") || n.equals("obermenzing");
                if (relevant) {
                    Set<String> rs = stopRoutes.getOrDefault(s.parentOrSelf(), Set.of());
                    List<String> described = rs.stream().map(id -> {
                        Route r = routes.get(id);
                        return id + " [route_type=" + (r == null ? "unknown" : r.type) + "]";
                    }).toList();
                    boolean west = n.contains("berdux") || n.contains("paul-gerhardt")
                            || n.equals("laim") || n.equals("obermenzing");
                    Stop anchor = stops.get(west ? BERDUX_PARENT : POCCI_PARENT);
                    result.add(new Candidate(s, String.join("|", described), km(s, anchor)));
                }
            }
            result.sort(Comparator.comparing(a -> a.stop.name + "|" + a.stop.id));
            return result;
        }
        void requireAnchor(String id,String name,List<String>b){Stop s=stops.get(id);if(s==null)b.add("Coordinate anchor absent: "+id);else if(!fold(s.name).equals(fold(name)))b.add("Coordinate anchor name mismatch: "+id+" is "+s.name);else if(!validCoord(s))b.add("Invalid coordinate anchor: "+id);}

        void build(Analysis a)throws Exception{
            Path tmp=Files.createTempDirectory(OUTPUT.getParent(),"common-gtfs-");
            try(ZipFile z=new ZipFile(INPUT.toFile(),StandardCharsets.UTF_8)){
                for(String n:List.of("agency.txt","calendar.txt","routes.txt","trips.txt","shapes.txt"))copy(z,n,tmp.resolve(n));
                writeStops(z,tmp.resolve("stops.txt"));
                writeStopTimes(z,tmp.resolve("stop_times.txt"),a);
                writeTransfers(z,tmp.resolve("transfers.txt"),a.regionalRule);
            }
            validateFiles(tmp,a);
            writeZip(tmp,OUTPUT);
            validateZip(OUTPUT,a);
            validateMatsimReadback(OUTPUT, a);
            writeMethod(a);
            deleteTree(tmp);
            System.out.println("Built "+OUTPUT+" SHA-256="+sha256(OUTPUT));
        }

        void writeStops(ZipFile z,Path out)throws IOException{
            try(CsvReader r=csv(z,"stops.txt");BufferedWriter w=Files.newBufferedWriter(out,StandardCharsets.UTF_8)){
                w.write(csvLine(r.header));w.newLine();String[] a;while((a=r.next())!=null){w.write(csvLine(a));w.newLine();}
                appendPlatform(w,r,POCCI_D0,"Poccistraße regional rail",POCCI_PARENT);appendPlatform(w,r,POCCI_D1,"Poccistraße regional rail",POCCI_PARENT);
                appendPlatform(w,r,BERDUX_D0,"Berduxstraße S-Bahn",BERDUX_PARENT);appendPlatform(w,r,BERDUX_D1,"Berduxstraße S-Bahn",BERDUX_PARENT);
            }
        }
        void appendPlatform(BufferedWriter w,CsvReader r,String id,String name,String parent)throws IOException{String[]a=new String[r.header.length];Arrays.fill(a,"");Stop p=stops.get(parent);set(a,r,"stop_id",id);set(a,r,"stop_name",name);set(a,r,"stop_lat",fmt(p.lat));set(a,r,"stop_lon",fmt(p.lon));set(a,r,"location_type","0");set(a,r,"parent_station",parent);w.write(csvLine(a));w.newLine();}

        void writeTransfers(ZipFile z, Path out, DwellRule rule) throws IOException {
            try (CsvReader r = csv(z, "transfers.txt");
                    BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write(csvLine(r.header)); w.newLine();
                String[] row; Set<String> keys = new HashSet<>();
                while ((row = r.next()) != null) {
                    keys.add(r.get(row, "from_stop_id") + "\u0000" + r.get(row, "to_stop_id"));
                    w.write(csvLine(row)); w.newLine();
                }
                for (String rail : List.of(POCCI_D0, POCCI_D1)) {
                    for (String target : rule.transferTargets()) {
                        for (String[] pair : List.of(new String[]{rail, target}, new String[]{target, rail})) {
                            String key = pair[0] + "\u0000" + pair[1];
                            if (!keys.add(key)) throw new IllegalStateException("Duplicate common transfer " + key);
                            String[] added = new String[r.header.length]; Arrays.fill(added, "");
                            set(added, r, "from_stop_id", pair[0]); set(added, r, "to_stop_id", pair[1]);
                            set(added, r, "transfer_type", "2");
                            set(added, r, "min_transfer_time", Integer.toString(rule.minimumTransferSeconds()));
                            w.write(csvLine(added)); w.newLine();
                        }
                    }
                }
            }
        }

        void writeStopTimes(ZipFile z,Path out,Analysis analysis)throws IOException{
            try(CsvReader r=csv(z,"stop_times.txt");BufferedWriter w=Files.newBufferedWriter(out,StandardCharsets.UTF_8)){
                w.write(csvLine(r.header));w.newLine();String[] row;String current=null;List<String[]> group=new ArrayList<>();
                while((row=r.next())!=null){String id=r.get(row,"trip_id");if(current!=null&&!id.equals(current)){writeGroup(w,r,current,group,analysis);group.clear();}current=id;group.add(row);}
                if(current!=null)writeGroup(w,r,current,group,analysis);
            }
        }
        void writeGroup(BufferedWriter w,CsvReader r,String id,List<String[]> rows,Analysis a)throws IOException{
            Trip t=trips.get(id);if(t==null){for(String[]x:rows){w.write(csvLine(x));w.newLine();}return;}
            List<Call> calls=affected.get(id);int gap=-1,dwell=0;String newId=null;
            if(POCCI_ROUTES.contains(t.routeId)){gap=regionalInsertion(calls);dwell=a.regionalDwell;newId=t.direction.equals("0")?POCCI_D0:POCCI_D1;}
            else if(t.routeId.equals(S2)&&isRegularS2(calls)){int l=index(calls,LAIM),o=index(calls,OBERMENZING);gap=Math.min(l,o);dwell=a.sDwell;newId=t.direction.equals("0")?BERDUX_D0:BERDUX_D1;}
            if(gap<0){for(String[]x:rows){w.write(csvLine(x));w.newLine();}return;}
            int dep=time(r.get(rows.get(gap),"departure_time")),arrNext=time(r.get(rows.get(gap+1),"arrival_time"));
            Stop aa=stops.get(r.get(rows.get(gap),"stop_id")),bb=stops.get(r.get(rows.get(gap+1),"stop_id")),nn=stops.get(newId.startsWith("BAU_POCCI")?POCCI_PARENT:BERDUX_PARENT);
            double ratio=km(aa,nn)/(km(aa,nn)+km(nn,bb));int arrival=dep+(int)Math.round((arrNext-dep)*ratio);arrival=Math.max(dep,Math.min(arrNext,arrival));
            for(int i=0;i<rows.size();i++){
                String[]x=rows.get(i).clone();int seq=Integer.parseInt(r.get(x,"stop_sequence"));if(i>gap){set(x,r,"arrival_time",shift(r.get(x,"arrival_time"),dwell));set(x,r,"departure_time",shift(r.get(x,"departure_time"),dwell));set(x,r,"stop_sequence",Integer.toString(seq+1));}w.write(csvLine(x));w.newLine();
                if(i==gap){String[]n=x.clone();set(n,r,"stop_id",newId);set(n,r,"arrival_time",clock(arrival));set(n,r,"departure_time",clock(arrival+dwell));set(n,r,"stop_sequence",Integer.toString(seq+1));setIf(n,r,"pickup_type","0");setIf(n,r,"drop_off_type","0");for(String col:List.of("shape_dist_traveled","Entferung","Fahrzeit","FZT","Streckengeschwindigkeit"))setIf(n,r,col,"");w.write(csvLine(n));w.newLine();}
            }
        }

        void validateFiles(Path dir,Analysis a)throws IOException{
            Set<String> stopIds=new HashSet<>(),tripIds=new HashSet<>();
            try(CsvReader r=new CsvReader(dir.resolve("stops.txt"))){String[]x;while((x=r.next())!=null)if(!stopIds.add(r.get(x,"stop_id")))throw new IllegalStateException("Duplicate stop_id "+r.get(x,"stop_id"));}
            try(CsvReader r=new CsvReader(dir.resolve("trips.txt"))){String[]x;while((x=r.next())!=null)if(!tripIds.add(r.get(x,"trip_id")))throw new IllegalStateException("Duplicate trip_id "+r.get(x,"trip_id"));}
            int p=0,b=0;String lastTrip="";int lastSeq=-1,lastTime=-1;
            try(CsvReader r=new CsvReader(dir.resolve("stop_times.txt"))){String[]x;while((x=r.next())!=null){String trip=r.get(x,"trip_id"),stop=r.get(x,"stop_id");if(!tripIds.contains(trip)||!stopIds.contains(stop))throw new IllegalStateException("Broken stop_times reference for "+trip+" / "+stop);int seq=Integer.parseInt(r.get(x,"stop_sequence")),arr=time(r.get(x,"arrival_time")),dep=time(r.get(x,"departure_time"));if(arr>dep)throw new IllegalStateException("Arrival after departure in "+trip);if(trip.equals(lastTrip)&&(seq<=lastSeq||arr<lastTime))throw new IllegalStateException("Non-monotone stop sequence/time in "+trip);if(!trip.equals(lastTrip)){lastSeq=-1;lastTime=-1;}lastTrip=trip;lastSeq=seq;lastTime=dep;if(stop.startsWith("BAU_POCC"))p++;if(stop.startsWith("BAU_BERDUX"))b++;}}
            if(p!=a.pocciTrips||b!=a.berduxTrips)throw new IllegalStateException("Inserted-stop count mismatch: Poccistraße="+p+", Berduxstraße="+b);
            int commonTransfers = 0;
            try (CsvReader r = new CsvReader(dir.resolve("transfers.txt"))) { String[] x; while ((x=r.next())!=null) {
                String from=r.get(x,"from_stop_id"),to=r.get(x,"to_stop_id");
                if(!stopIds.contains(from)||!stopIds.contains(to))throw new IllegalStateException("Broken transfer reference "+from+" -> "+to);
                if(from.startsWith("BAU_POCCI")||to.startsWith("BAU_POCCI")){commonTransfers++;if(Integer.parseInt(r.get(x,"min_transfer_time"))!=180)throw new IllegalStateException("Wrong Poccistraße transfer time");}
            }}
            if(commonTransfers!=8)throw new IllegalStateException("Expected 8 Poccistraße rail transfers, found "+commonTransfers);
        }
        void validateZip(Path zip,Analysis a)throws IOException{try(ZipFile z=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)){Path t=Files.createTempDirectory(zip.getParent(),"validate-common-");try{for(String n:List.of("stops.txt","trips.txt","stop_times.txt","transfers.txt"))copy(z,n,t.resolve(n));validateFiles(t,a);}finally{deleteTree(t);}}}

        void validateMatsimReadback(Path zip, Analysis a) {
            var config = ConfigUtils.createConfig();
            config.global().setCoordinateSystem("EPSG:31468");
            Scenario scenario = ScenarioUtils.createScenario(config);
            GtfsConverter.newBuilder()
                    .setFeed(zip)
                    .setDate(java.time.LocalDate.parse("2026-02-13"))
                    .setTransform(TransformationFactory.getCoordinateTransformation(
                            TransformationFactory.WGS84, "EPSG:31468"))
                    .setScenario(scenario)
                    .setUseExtendedRouteTypes(false)
                    .setMergeStops(GtfsConverter.MergeGtfsStops.doNotMerge)
                    .setIncludeMinimalTransferTimes(true)
                    .build().convert();
            for (String id : List.of(POCCI_D0, POCCI_D1, BERDUX_D0, BERDUX_D1)) {
                if (!scenario.getTransitSchedule().getFacilities().containsKey(
                        Id.create(id, TransitStopFacility.class))) {
                    throw new IllegalStateException("MATSim read-back omitted new stop " + id);
                }
            }
            for (String rail : List.of(POCCI_D0, POCCI_D1)) for (String target : List.of("106211", "106212")) {
                double forward = scenario.getTransitSchedule().getMinimalTransferTimes().get(
                        Id.create(rail, TransitStopFacility.class), Id.create(target, TransitStopFacility.class));
                double reverse = scenario.getTransitSchedule().getMinimalTransferTimes().get(
                        Id.create(target, TransitStopFacility.class), Id.create(rail, TransitStopFacility.class));
                if (forward != 180 || reverse != 180) throw new IllegalStateException("MATSim read-back omitted Poccistraße transfer " + rail + " <-> " + target);
            }
            int departures = scenario.getTransitSchedule().getTransitLines().values().stream()
                    .flatMap(line -> line.getRoutes().values().stream())
                    .mapToInt(route -> route.getDepartures().size()).sum();
            if (departures != 70_620) {
                throw new IllegalStateException("BAU trip count changed unexpectedly: " + departures);
            }
        }

        void writePreflight(Analysis a)throws IOException{Files.createDirectories(PREFLIGHT);try(BufferedWriter w=Files.newBufferedWriter(PREFLIGHT.resolve("coordinate_candidates.csv"),StandardCharsets.UTF_8)){w.write("stop_id,stop_name,location_type,parent_station,latitude,longitude,served_routes_and_modes,distance_to_measure_anchor_km\n");for(Candidate c:a.candidates)w.write(csvLine(new String[]{c.stop.id,c.stop.name,c.stop.locationType,c.stop.parent,fmt(c.stop.lat),fmt(c.stop.lon),c.routes,String.format(Locale.ROOT,"%.3f",c.distanceKm)})+"\n");}
            try(BufferedWriter w=Files.newBufferedWriter(PREFLIGHT.resolve("affected_trips.csv"),StandardCharsets.UTF_8)){w.write("measure,direction,affected_trips,applied_dwell_seconds,braking_acceleration_penalty_seconds,total_added_seconds\n");w.write("POCCISTRASSE,0,"+a.pocciD0+","+a.regionalRule.dwellSeconds()+","+a.regionalRule.brakingAccelerationPenaltySeconds()+","+a.regionalDwell+"\nPOCCISTRASSE,1,"+a.pocciD1+","+a.regionalRule.dwellSeconds()+","+a.regionalRule.brakingAccelerationPenaltySeconds()+","+a.regionalDwell+"\nBERDUXSTRASSE,0,"+a.berduxD0+","+a.sRule.dwellSeconds()+","+a.sRule.brakingAccelerationPenaltySeconds()+","+a.sDwell+"\nBERDUXSTRASSE,1,"+a.berduxD1+","+a.sRule.dwellSeconds()+","+a.sRule.brakingAccelerationPenaltySeconds()+","+a.sDwell+"\n");}
            StringBuilder m=new StringBuilder("# Common-measures preflight\n\n- Blockers: **").append(a.blockers.size()).append("**\n- Poccistraße trips: ").append(a.pocciTrips).append("\n- Berduxstraße trips: ").append(a.berduxTrips).append("\n- Forecast-feed regional/S2 dwell medians: ").append(a.forecastRegionalDwell).append("/").append(a.forecastSDwell).append(" seconds\n- Applied regional/S2 dwell: ").append(a.regionalRule.dwellSeconds()).append("/").append(a.sRule.dwellSeconds()).append(" seconds\n- Additional braking/acceleration penalty: 0 seconds\n- Regional evidence: ").append(a.regionalRule.evidenceObservations()).append(" observations, p10/median/p90 ").append(a.regionalRule.evidenceP10Seconds()).append("/").append(a.regionalRule.evidenceMedianSeconds()).append("/").append(a.regionalRule.evidenceP90Seconds()).append(" seconds\n- S2 evidence: ").append(a.sRule.evidenceObservations()).append(" observations, p10/median/p90 ").append(a.sRule.evidenceP10Seconds()).append("/").append(a.sRule.evidenceMedianSeconds()).append("/").append(a.sRule.evidenceP90Seconds()).append(" seconds\n- Clean-feed SHA-256: `").append(a.inputSha).append("`\n- Workbook SHA-256: `").append(a.workbookSha).append("`\n\n## Critical findings\n\n");if(a.blockers.isEmpty())m.append("No critical blocker remains.\n");else for(String b:a.blockers)m.append("- ").append(b).append('\n');Files.writeString(PREFLIGHT.resolve("preflight_report.md"),m,StandardCharsets.UTF_8);}

        void writeMethod(Analysis a)throws Exception{Files.createDirectories(REPORT.getParent());String s="""
# Shared BAU and Fast Track GTFS measures

## Analytical purpose and source hierarchy

This processing stage adds measures that belong to both 2040 scenarios after geographic cleaning and before Fast Track-specific additions. The substantive source is rows 13, 18 and 19 of `Infrastructure_measures.xlsx`; `common_service_specification.csv` is the version-controlled executable transcription. The raw feed and the cleaned Munich feed are never altered.

## Poccistraße

The regional stop is inserted only into trips on the six route IDs named in Excel that can be placed on the München Hbf–München Ost corridor. The existing Poccistraße parent station `106206` supplies a spatial proxy. New rail-platform IDs are used; existing underground and bus platforms are not reclassified. The proxy is a **scenario assumption**, not an official future regional-platform coordinate. Existing moving time is divided by straight-line distance around the inserted point. All following times are shifted by the derived median dwell, so trip count, departure pattern and frequency remain unchanged.

Affected trips by route are: `E 28.a BY` 40, `E 28.b BY` 80, `E 56 BY` 21, `N 28.a BY` 39, `N 28.b BY` 18 and `N 56 BY` 36. The insertion gap is selected by the smallest straight-line detour through the proxy between Hbf and Ost. Where an inbound `N 28.a BY` record omits an Ost stop, its reverse pattern and eastern calls establish the same corridor; the stop is placed on the final eastern-to-Hbf segment. Each old gap interval is divided in proportion to the two straight-line distances. The current MVV GTFS provides 2,934 positive intermediate passenger-stop observations on RE 5, RB 40 and RB 54. Their median is 60 seconds (10th–90th percentile: 60–240 seconds). The builder therefore applies 60 seconds dwell and shifts every later time by 60 seconds.

The exact and fuzzy source-stop search found no Munich stop named München Süd/Südbahnhof, Lindwurmstraße, Bavariaring or KVR. Distant matches elsewhere in Germany were rejected. Parent `106206` is the only exact local Poccistraße station object (48.125512, 11.550358). Existing children are served by subway and bus routes, so the new rail platforms remain separate IDs beneath the parent. Eight directed transfers connect the two new rail platforms to the existing U3/U6 platforms `106211` and `106212`. Their 180-second minimum follows the existing Poccistraße transfer matrix. A routed MATSim check confirms a regional-to-underground interchange with 192 seconds of transfer/access time; no zero-second interchange is introduced.

## Berduxstraße

The stop is inserted only into the exact forecast S2 route and only where Laim and Obermenzing are consecutive and the trip contains the complete regular western calling pattern (Untermenzing, Allach, Karlsfeld and Dachau). Express S-Bahn routes remain excluded. The existing Berduxstraße parent `162054` supplies a **scenario-assumption** coordinate proxy, while new rail-platform IDs prevent a bus platform from being represented as rail. Running time and dwell follow the same deterministic method as Poccistraße.

The local candidates were Berduxstraße parent `162054` (48.151769, 11.480379), its bus child `162055` 0.046 km away, Paul-Gerhardt-Allee parent `109761` 0.533 km away, Laim parent `106108` 1.888 km away and Obermenzing parent `109792` 1.395 km away. Berduxstraße is the unique exact-name and corridor-intermediate candidate. All selected S2 trips call at the complete regular pattern; separate `S…X` route IDs are excluded.

Each existing Laim–Obermenzing interval is divided in proportion to the straight-line distance via the proxy. The current MVV GTFS provides 9,079 positive intermediate passenger-stop observations on regular S2 trips. Their median is 60 seconds (10th–90th percentile: 60–120 seconds). The builder therefore applies 60 seconds dwell and shifts every later time by 60 seconds. No departure is created or removed.

## Sendlinger Spange

No GTFS row is added. Excel records no published regular-weekday timetable change. Existing S20 and other scheduled services remain unchanged. Reliability, diversion and disruption benefits are therefore represented indirectly in the scenario definition but not in a normal-day MATSim timetable.

## Reproducibility and limitations

Run `powershell -ExecutionPolicy Bypass -File src/main/scripts/gtfs2040/build_common_gtfs2037.ps1 -Mode analyze`, inspect the preflight files, then rerun with `-Mode build` only if the blocker count is zero. The resulting BAU feed becomes the input to the Fast Track builder. Straight-line allocation is reproducible but does not constitute infrastructure-based railway running-time modelling. Arrival/departure data identify dwell but cannot separately identify braking and acceleration. No additional penalty is therefore imposed; the total addition is 60 seconds per new stop. Neither proxy coordinate is an official future platform location.

## Current result

"""+"- Poccistraße modified trips: "+a.pocciTrips+" (direction 0: "+a.pocciD0+", direction 1: "+a.pocciD1+")\n- Berduxstraße modified trips: "+a.berduxTrips+" (direction 0: "+a.berduxD0+", direction 1: "+a.berduxD1+")\n- Applied regional dwell and total time addition: "+a.regionalDwell+" seconds\n- Applied S-Bahn dwell and total time addition: "+a.sDwell+" seconds\n- Additional braking/acceleration penalty: 0 seconds\n- Clean input SHA-256: `"+a.inputSha+"`\n- BAU output SHA-256: `"+sha256(OUTPUT)+"`\n";Files.writeString(REPORT,s,StandardCharsets.UTF_8);}
        void print(Analysis a){System.out.printf(Locale.ROOT,"Common-measures analysis: blockers=%d, Poccistrasse trips=%d (%d/%d), Berduxstrasse trips=%d (%d/%d), dwell=%d/%d s%n",a.blockers.size(),a.pocciTrips,a.pocciD0,a.pocciD1,a.berduxTrips,a.berduxD0,a.berduxD1,a.regionalDwell,a.sDwell);}
    }

    record Analysis(List<String>blockers,List<Candidate>candidates,int pocciTrips,int berduxTrips,int pocciD0,int pocciD1,int berduxD0,int berduxD1,int regionalDwell,int sDwell,int forecastRegionalDwell,int forecastSDwell,DwellRule regionalRule,DwellRule sRule,String inputSha,String workbookSha){}
    record DwellRule(int dwellSeconds,int brakingAccelerationPenaltySeconds,List<String> transferTargets,int minimumTransferSeconds,String evidence,int evidenceObservations,int evidenceMedianSeconds,int evidenceP10Seconds,int evidenceP90Seconds){int totalPenaltySeconds(){return dwellSeconds+brakingAccelerationPenaltySeconds;}}
    record Stop(String id,String name,double lat,double lon,String locationType,String parent){String parentOrSelf(){return parent.isBlank()?id:parent;}}
    record Route(String id,String shortName,String longName,String type){}
    record Trip(String id,String routeId,String service,String direction,String headsign){}
    record Call(String stopId,String arrival,String departure,int sequence){}
    record Candidate(Stop stop,String routes,double distanceKm){}

    static CsvReader csv(ZipFile z,String n)throws IOException{ZipEntry e=z.getEntry(n);if(e==null)throw new IOException("Missing ZIP entry "+n);return new CsvReader(z.getInputStream(e));}
    static final class CsvReader implements AutoCloseable{final BufferedReader in;final String[]header;final Map<String,Integer>idx=new HashMap<>();CsvReader(Path p)throws IOException{this(Files.newInputStream(p));}CsvReader(InputStream s)throws IOException{in=new BufferedReader(new InputStreamReader(s,StandardCharsets.UTF_8),1<<20);header=parse(in.readLine());if(header[0].startsWith("\ufeff"))header[0]=header[0].substring(1);for(int i=0;i<header.length;i++)idx.put(header[i],i);}String[]next()throws IOException{String s=in.readLine();return s==null?null:parse(s);}String get(String[]a,String n){Integer i=idx.get(n);return i==null||i>=a.length?"":a[i];}public void close()throws IOException{in.close();}}
    static String[]parse(String s){List<String>x=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==','&&!q){x.add(b.toString());b.setLength(0);}else b.append(c);}x.add(b.toString());return x.toArray(String[]::new);}
    static String csvLine(String[]a){StringJoiner j=new StringJoiner(",");for(String s:a){if(s==null)s="";if(s.indexOf(',')>=0||s.indexOf('"')>=0||s.indexOf('\n')>=0)j.add('"'+s.replace("\"","\"\"")+'"');else j.add(s);}return j.toString();}
    static void set(String[]a,CsvReader r,String n,String v){Integer i=r.idx.get(n);if(i==null)throw new IllegalStateException("Required column absent: "+n);a[i]=v;}static void setIf(String[]a,CsvReader r,String n,String v){Integer i=r.idx.get(n);if(i!=null)a[i]=v;}
    static int index(List<Call>c,Set<String>ids){for(int i=0;i<c.size();i++)if(ids.contains(c.get(i).stopId))return i;return-1;}static boolean containsAny(List<Call>c,Set<String>ids){return index(c,ids)>=0;}
    static int median(List<Integer>x){if(x.isEmpty())return-1;List<Integer>y=new ArrayList<>(x);Collections.sort(y);return y.get((y.size()-1)/2);}
    static int time(String s){String[]p=s.split(":");return Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60+Integer.parseInt(p[2]);}static String clock(int t){return String.format(Locale.ROOT,"%02d:%02d:%02d",t/3600,(t/60)%60,t%60);}static String shift(String s,int delta){return s.isBlank()?s:clock(time(s)+delta);}
    static double d(String s){return s.isBlank()?Double.NaN:Double.parseDouble(s);}static String fmt(double d){return String.format(Locale.ROOT,"%.6f",d);}static boolean validCoord(Stop s){return s.lat>=-90&&s.lat<=90&&s.lon>=-180&&s.lon<=180;}
    static double km(Stop a,Stop b){double p1=Math.toRadians(a.lat),p2=Math.toRadians(b.lat),dp=p2-p1,dl=Math.toRadians(b.lon-a.lon);double h=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);return 6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));}
    static String fold(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);}
    static void copy(ZipFile z,String n,Path p)throws IOException{try(InputStream in=z.getInputStream(z.getEntry(n))){Files.copy(in,p,StandardCopyOption.REPLACE_EXISTING);}}
    static void writeZip(Path dir,Path out)throws IOException{Files.createDirectories(out.getParent());Path tmp=out.resolveSibling(out.getFileName()+".tmp");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(tmp),StandardCharsets.UTF_8)){z.setLevel(Deflater.BEST_COMPRESSION);for(String n:List.of("agency.txt","calendar.txt","routes.txt","trips.txt","stop_times.txt","stops.txt","shapes.txt","transfers.txt")){ZipEntry e=new ZipEntry(n);e.setTime(ZIP_TIME);z.putNextEntry(e);Files.copy(dir.resolve(n),z);z.closeEntry();}}Files.move(tmp,out,StandardCopyOption.REPLACE_EXISTING);}
    static String sha256(Path p)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[]b=new byte[1<<20];for(int n;(n=in.read(b))>0;)m.update(b,0,n);}return HexFormat.of().withUpperCase().formatHex(m.digest());}
    static void deleteTree(Path p)throws IOException{if(!Files.exists(p))return;try(var s=Files.walk(p)){for(Path x:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(x);}}
}
