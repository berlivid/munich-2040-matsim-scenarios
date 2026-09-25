package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Thin server-only runner for literature-based scoring calibration Round 1. */
public final class RunMatsim2019LiteratureBasedScoringCalibrationRound1 {
    private RunMatsim2019LiteratureBasedScoringCalibrationRound1() { }

    public static void main(String[] args) throws Exception {
        ValidateLiteratureBasedScoringCalibrationRound1Config.require(
                args.length == 0, "The Round-1 runner accepts no arguments");
        var config = ValidateLiteratureBasedScoringCalibrationRound1Config.loadAndValidate();
        var scenario = ScenarioUtils.loadScenario(config);
        var observer = new AnalyzeLiteratureBasedScoringCalibrationRound1(
                scenario, new MunichTripBoundaryFilter(MunichMunicipalBoundary.loadDefault()));
        var controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.addControlerListener(observer);
        controler.getEvents().addHandler(observer);
        controler.run();
        AnalyzeLiteratureBasedScoringCalibrationRound1.summarizeExistingOutput();
    }
}
