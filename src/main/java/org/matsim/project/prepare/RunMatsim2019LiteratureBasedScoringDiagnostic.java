package org.matsim.project.prepare;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Thin server-only entry point for the literature-based ten-iteration diagnostic. */
public final class RunMatsim2019LiteratureBasedScoringDiagnostic {
    private RunMatsim2019LiteratureBasedScoringDiagnostic() { }

    public static void main(String[] args) throws Exception {
        ValidateLiteratureBasedScoringDiagnosticConfig.require(args.length == 0,
                "The diagnostic runner accepts no arguments");
        var config = ValidateLiteratureBasedScoringDiagnosticConfig.loadAndValidate();
        var scenario = ScenarioUtils.loadScenario(config);
        var controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.run();
    }
}
