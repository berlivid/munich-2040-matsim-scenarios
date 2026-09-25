# Proposed mode-choice configuration changes (documentation preview only)

## Current effective configuration

No XML file was changed for this preview. MATSim 2025.0 readback of the base, BAU and Fast Track configs gives the same behavioural settings:

| Item | Current effective value |
|---|---|
| Active strategies | `ChangeExpBeta` 0.9; `ReRoute` 0.4 |
| Mode-choice strategy | none |
| Effective plan memory | 4 (a preceding duplicate legacy module says 5, then is overwritten) |
| Innovation shut-off fraction | 0.8 |
| Iterations | 0–0 |
| Random seed | 4711 (MATSim default, not explicit in XML) |
| Network modes | `car` |
| Teleported modes | `bike`, `walk`, `non_network_walk`, `ride`; a fallback `pt` parameter also exists |
| PT in 2040 configs | enabled; `SwissRailRaptorModule` is installed by the project runners |
| Mode scoring sets | `car`, `pt`, `walk`, `bike`, `ride`, `other` |
| Mode constants | 0.0 for all six modes |
| Travel-time utility | -6.0 utils/hour for all six modes |
| Distance and monetary rates | 0.0 for all six modes |
| Car availability data | absent from all 324,043 base persons |

The production XML uses deprecated aliases (`controler`, `strategy`, `planCalcScore`) and contains two `strategy` modules. They load successfully but emit warnings. A calibration-only config should use canonical module names and a single `replanning` module.

## Proposed calibration-only delta

Create a separate configuration only after confirming the reference-year inputs. If provenance confirms 2019, the preferred path is `scenarios/munich_base_2019/config_mode_choice_calibration.xml`; do not rename or move current inputs solely to satisfy that label. The conceptual MATSim 2025.0 delta is:

```xml
<module name="global">
    <param name="randomSeed" value="4711"/>
</module>

<module name="controller">
    <param name="firstIteration" value="0"/>
    <param name="lastIteration" value="100"/>
    <!-- New, non-production output directory required. -->
</module>

<module name="replanning">
    <param name="fractionOfIterationsToDisableInnovation" value="0.8"/>
    <param name="maxAgentPlanMemorySize" value="4"/>
    <param name="planSelectorForRemoval" value="WorstPlanSelector"/>
    <parameterset type="strategysettings">
        <param name="strategyName" value="ChangeExpBeta"/>
        <param name="weight" value="0.8"/>
    </parameterset>
    <parameterset type="strategysettings">
        <param name="strategyName" value="ReRoute"/>
        <param name="weight" value="0.1"/>
    </parameterset>
    <parameterset type="strategysettings">
        <param name="strategyName" value="SubtourModeChoice"/>
        <param name="weight" value="0.1"/>
    </parameterset>
</module>

<module name="subtourModeChoice">
    <param name="behavior" value="fromSpecifiedModesToSpecifiedModes"/>
    <param name="modes" value="car,pt,walk,bike"/>
    <param name="chainBasedModes" value="car,bike"/>
    <param name="probaForRandomSingleTripMode" value="0.0"/>
    <param name="coordDistance" value="0.0"/>
    <!-- Set only after the availability decision described below. -->
    <param name="considerCarAvailability" value="false"/>
</module>
```

The calibration config must also point to a year-consistent network, population, PT schedule and vehicles. The current `config_base.xml` has transit disabled, while its optional transit bundle is from 2026; neither is a complete 2019 or 2023 calibration input.

## Scoring changes

Reuse the current activity parameters and all current time, distance, waiting, money and routing coefficients initially. Change only constants in the existing `scoringParameters/modeParams` sets:

| Mode | Initial treatment | Empirical input still needed |
|---|---|---|
| `car` | fix constant at 0 as reference | car share, ownership/licence distribution, parking/cost scope |
| `pt` | calibrate constant | compatible observed PT share and year-consistent PT supply |
| `walk` | calibrate constant | compatible observed walk share and trip definition |
| `bike` | calibrate constant | compatible observed bike share; acknowledge teleported routing |
| `ride` | exclude initially | target share and consistent population coding |
| `other` | exclude | explicit behavioural definition if ever required |

No numeric calibrated constants are proposed because the repository contains no empirical modal-split targets.

## Availability decision

The preferred later change is to create documented person-level licence and car-availability attributes and set `considerCarAvailability=true`. If the thesis instead chooses a minimal aggregate calibration with `false`, car is available as a choice without an individual constraint; that decision must be explicit and tested as a sensitivity. No availability attributes should be invented inside the config.

## Parameters retained initially

- QSim flow and storage factors: 0.05.
- Random seed: 4711, explicitly frozen in the calibration config.
- `networkModes=car`.
- Bike speed 4.1667 m/s, walk speed 0.8333 m/s and beeline factor 1.3.
- SwissRailRaptor defaults and existing transit transfer data.
- Performing utility 6 utils/hour, travelling utility -6 utils/hour, PT waiting utility -6 utils/hour, line-switch utility -1.
- Zero distance and monetary rates until an empirical cost specification exists.

## Inputs and decisions required before activation

1. Confirm the source year and calibration provenance of `munich-v1.0-5pct.plans.xml` and `studyNetworkDense.xml`.
2. Supply a year-consistent PT schedule and vehicles for the selected calibration year.
3. Supply observed, definition-compatible modal shares plus trip distance/time targets.
4. Decide the calibration geography and confirm the proposed Munich-resident filter.
5. Decide whether to enrich licence/car availability or accept `considerCarAvailability=false` as a documented limitation.
6. Choose the iteration budget and late-iteration averaging window after one diagnostic run.

## Production files affected later

Only after the calibration succeeds should the common settings be copied identically into:

- `scenarios/munich_bau_2040/config_bau.xml`
- `scenarios/munich_fast_track_2040/config_fast_track.xml`

The scenario population, network, transit schedule and vehicles would not be changed by the config-only activation. If car-availability enrichment is approved, that is a separate population-preparation change and must be applied consistently before the 2040 population builders. BAU and Fast Track must never receive separately calibrated constants.
