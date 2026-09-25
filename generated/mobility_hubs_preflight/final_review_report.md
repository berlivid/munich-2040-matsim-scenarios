# Final review of Fast Track mobility-hub candidates

## Scope and evidence

This short, read-only quality review covers only ranks 1–20 from the existing preflight. It does not alter the LHM workbook, the MATSim schedule, either preflight CSV, or any model input. No simulation, MATSim project build, Maven test, or commit was performed.

- **LHM-derived fields:** mobility-point ID, name, address, modal flags, car-sharing spaces, and transformed location coordinates are carried forward from the existing preflight CSVs. They describe the municipal source data and are not rewritten here.
- **MATSim observations:** StopArea membership, facility IDs, serving TransitLines and TransitRoutes, route modes, and explicit `minimalTransferTimes` were read from `scenarios/munich_fast_track_2040/input_transit/transitSchedule.xml.gz` (SHA-256 `D032A77481947C189EB69E486A132CF9348B4DD8A89384BDCFE8019C19C6A3B6`).
- **Model assumptions:** each LHM point is assigned to exactly one coherent interchange node. Complete StopAreas are used once a node is confirmed. Multiple StopAreas are combined only for ZOB/Hackerbrücke, the Ostbahnhof complex, and Scheidplatz/Scheidplatz Süd. The four-large/four-medium/four-small classes remain an analytical modelling convention, not an LHM classification.

## Node-based method

The earlier 150 m search was used only to identify nearby candidates. This review removes unrelated neighbouring StopAreas and then expands a confirmed StopArea to all of its facilities, preventing a platform just outside 150 m from disappearing from an otherwise confirmed station. Eligibility is evaluated against the nearest served facility of the assigned node (maximum 150 m) and at least two distinct MATSim TransitLines. Counts use unique TransitLine IDs and unique line/TransitRoute pairs. Potential directed line transfers are `L × (L − 1)`; they are structural possibilities, not timetable-feasibility claims.

## Proposed final twelve

| Rank | Size | LHM point | Assigned MATSim node | Distance (m) | Lines | Routes | Modes | Existing transfer relations |
|---:|---|---|---|---:|---:|---:|---|---:|
|1|large|Ostbahnhof/Belfortstraße|Ostbahnhof station complex|23.1|57|522|bus, rail, subway, tram|129|
|2|large|ZOB|ZOB / Hackerbrücke station complex|88.3|52|427|bus, rail, tram|37|
|3|large|Moosach Bahnhof|Moosach station complex|28.1|20|141|bus, rail, subway, tram|144|
|4|large|Münchner Freiheit|Münchner Freiheit|24.1|18|151|bus, subway, tram|81|
|5|medium|Rosenheimer Platz|Rosenheimer Platz|34.6|18|317|bus, rail, tram|25|
|6|medium|Baaderstraße|Isartor (proxy for Baaderstraße)|93.8|18|315|bus, rail, tram|25|
|7|medium|Giesinger Bahnhof|Giesing station complex|23.1|17|186|bus, rail, subway, tram|196|
|8|medium|Scheidplatz|Scheidplatz / Scheidplatz Süd station complex|2.7|14|87|bus, subway, tram|104|
|9|small|Hohenzollernplatz|Hohenzollernplatz|41.1|14|74|bus, subway, tram|16|
|10|small|Grillparzerstraße|Grillparzerstraße|30.7|14|85|bus, tram|36|
|11|small|Rotkreuzplatz|Rotkreuzplatz|13.6|13|83|bus, subway, tram|64|
|12|small|Galeriestraße|Odeonsplatz (proxy for Galeriestraße)|110.0|13|118|bus, subway|36|

The deterministic re-ranking changes the first selection as follows: Ostbahnhof becomes rank 1 after its complete station complex is counted; Moosach becomes `large`; Rosenheimer Platz becomes `medium`; Rindermarkt falls out of the final twelve; and Galeriestraße enters at rank 12 as an explicit Odeonsplatz proxy.

## Targeted quality findings

### Rindermarkt

Marienplatz (StopArea 106059), Viktualienmarkt (106495), St.-Jakobs-Platz (106554), and Marienplatz (Rindermarkt) (106640) are separate nodes. Only StopArea 106640 is assigned to the LHM Rindermarkt point. It has 2 lines, 18 routes, mode `bus`, and remains formally eligible. Its corrected review rank is 20, so it is replaced in the final twelve by Galeriestraße/Odeonsplatz.

### Hohenzollernplatz

The full schedule contains U2 facilities 106586 and 106587 in StopArea 106583. Their nearest distance to the LHM point is 167.7 m, just outside the initial platform-level 150 m filter; therefore the first preflight omitted `subway`. The same StopArea also has closer served tram/bus facilities (nearest 41.1 m). Complete-node counting yields 14 lines, 74 routes, and modes `bus;subway;tram`. The case is resolved, not unresolved.

### Baaderstraße

The assigned MATSim hub is explicitly **Isartor**, StopArea 106063. The nearest served Isartor facility is 93.8 m from the LHM Baaderstraße point. This is a transparent spatial proxy, not evidence that MATSim contains a separate Baaderstraße interchange.

### Modal differences visible in the schedule

- **Ostbahnhof:** LHM says `tram=Nein`, while MATSim explicitly serves Ostbahnhof facilities 106082/106083 with tram lines 2272017_0 and 2272026_0; the full station complex also contains forecast tram line 21. This is a source-versus-2040-schedule difference, not a spatial matching error.
- **Rosenheimer Platz:** LHM says `bus=Nein`, while MATSim bus line 2255086_3 serves facilities 106073 and 106074 in StopArea 106069.
- **Trudering:** U2 facilities 107998 and 107999 belong to StopArea 107993 but are approximately 174.7 m from the LHM point. They were outside the initial platform-level radius; complete StopArea counting restores `subway`, producing 12 lines and 167 routes.

These statements are schedule observations. They do not establish whether an LHM flag describes present-day infrastructure, the municipal data-collection date, or the 2040 model state.

## Explicit minimal transfer times

Only already existing relations whose two endpoints lie inside a confirmed final hub are listed in `hub_stop_pair_review.csv`. No relation is created and no value is changed. Counts include both same-stop and directed cross-stop relations.

| Rank | Hub | Existing relations | Same-stop | Cross-stop | Values |
|---:|---|---:|---:|---:|---|
|1|Ostbahnhof station complex|129|15|114|0s×2;180s×127|
|2|ZOB / Hackerbrücke station complex|37|7|30|180s×1;300s×36|
|3|Moosach station complex|144|12|132|180s×144|
|4|Münchner Freiheit|81|9|72|180s×81|
|5|Rosenheimer Platz|25|5|20|300s×25|
|6|Isartor (proxy for Baaderstraße)|25|5|20|300s×25|
|7|Giesing station complex|196|14|182|180s×196|
|8|Scheidplatz / Scheidplatz Süd station complex|104|12|92|180s×104|
|9|Hohenzollernplatz|16|4|12|180s×16|
|10|Grillparzerstraße|36|6|30|180s×36|
|11|Rotkreuzplatz|64|8|56|180s×64|
|12|Odeonsplatz (proxy for Galeriestraße)|36|6|30|180s×36|

## Review of all ranks 1–20

| Review rank | Initial rank | LHM point | Assigned node | Lines | Modes | Status |
|---:|---:|---|---|---:|---:|---|
|1|2|Ostbahnhof/Belfortstraße|Ostbahnhof station complex|57|4|selected|
|2|1|ZOB|ZOB / Hackerbrücke station complex|52|3|selected|
|3|7|Moosach Bahnhof|Moosach station complex|20|4|selected|
|4|3|Münchner Freiheit|Münchner Freiheit|18|3|selected|
|5|4|Rosenheimer Platz|Rosenheimer Platz|18|3|selected|
|6|5|Baaderstraße|Isartor (proxy for Baaderstraße)|18|3|selected|
|7|6|Giesinger Bahnhof|Giesing station complex|17|4|selected|
|8|8|Scheidplatz|Scheidplatz / Scheidplatz Süd station complex|14|3|selected|
|9|12|Hohenzollernplatz|Hohenzollernplatz|14|3|selected|
|10|9|Grillparzerstraße|Grillparzerstraße|14|2|selected|
|11|11|Rotkreuzplatz|Rotkreuzplatz|13|3|selected|
|12|13|Galeriestraße|Odeonsplatz (proxy for Galeriestraße)|13|2|selected|
|13|18|Trudering Bahnhof Süd|Trudering station complex|12|3|not selected|
|14|16|Kidlerplatz|Am Harras (proxy for Kidlerplatz)|11|1|not selected|
|15|17|Messestadt West|Messestadt West|10|2|not selected|
|16|19|Fürstenried West|Fürstenried West|10|2|not selected|
|17|14|Arabellapark|Arabellapark|9|2|not selected|
|18|20|Kolumbusplatz|Kolumbusplatz|7|2|not selected|
|19|15|Pasinger Marienplatz|Pasinger Marienplatz|4|1|not selected|
|20|10|Rindermarkt|Marienplatz (Rindermarkt)|2|1|not selected|

## Remaining decisions

1. Confirm the model proxy that assigns the LHM Galeriestraße point to Odeonsplatz rather than to the separate Von-der-Tann-Straße surface stop.
2. If candidates below rank 20 are reconsidered later, clarify whether the LHM Kidlerplatz point is intended to represent Am Harras; its assigned MATSim node is bus-only despite `u_bahn=Ja`.
3. Decide in a later implementation step which subset of the existing same-stop and cross-stop transfer relations should receive large, medium, or small hub-specific values. This review does not recommend numerical transfer times.
