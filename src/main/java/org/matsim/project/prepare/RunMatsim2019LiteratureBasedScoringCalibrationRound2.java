package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Thin server-only runner for parameterized literature-based calibration rounds. */
public final class RunMatsim2019LiteratureBasedScoringCalibrationRound2 {
    private RunMatsim2019LiteratureBasedScoringCalibrationRound2() { }

    public static void main(String[] args) throws Exception {
        var specification = ValidateLiteratureBasedScoringCalibrationRound2Config
                .specification(args);
        var config = ValidateLiteratureBasedScoringCalibrationRound2Config
                .loadAndValidate(specification, true);
        var scenario = ScenarioUtils.loadScenario(config);
        var observer = new AnalyzeLiteratureBasedScoringCalibrationRound1(
                scenario, new MunichTripBoundaryFilter(MunichMunicipalBoundary.loadDefault()),
                ValidateLiteratureBasedScoringCalibrationRound2Config.definition(specification));
        var controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.addControlerListener(observer);
        controler.getEvents().addHandler(observer);
        controler.run();
        AnalyzeLiteratureBasedScoringCalibrationRound2
                .summarizeExistingOutput(specification);
    }
}
