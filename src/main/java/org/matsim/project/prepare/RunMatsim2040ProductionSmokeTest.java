package org.matsim.project.prepare;

import java.nio.file.Path;
import java.util.Map;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Server-only iteration-zero integration runner with automatic output validation. */
public final class RunMatsim2040ProductionSmokeTest {
    private RunMatsim2040ProductionSmokeTest() { }

    public static void main(String[] args) throws Exception {
        Production2040Contract.require(args.length == 1,
                "Usage: RunMatsim2040ProductionSmokeTest BAU|FAST_TRACK");
        var definition = Production2040RunSupport.scenario(args[0]);
        ValidateMatsim2040ProductionInput.validate(definition);
        var contract = Production2040Contract.loadAndValidate();
        Map<Path, String> protectedBefore = Production2040Contract.protectedInputSnapshot(contract);
        Production2040RunSupport.requireSmokeRunnerOutputsAbsent(definition);

        Scenario scenario = ScenarioUtils.loadScenario(
                Production2040RunSupport.smokeConfig(definition));
        var fixture = Production2040SmokePopulation.install(scenario, definition);
        System.out.printf("SMOKE FIXTURE scenario=%s persons=%s route_mode=%s line=%s "
                        + "route=%s from=%s to=%s departure=%.0f%n",
                definition.argument(), Production2040RunSupport.SMOKE_PERSON_IDS,
                fixture.routeMode(), fixture.lineId(), fixture.routeId(), fixture.fromStopId(),
                fixture.toStopId(), fixture.departureTime());
        Controler controler = new Controler(scenario);
        Production2040RunSupport.installSmokeModules(controler);
        try {
            controler.run();
        } catch (RuntimeException | Error failure) {
            System.err.println("SMOKE_SIMULATION_FAILED scenario=" + definition.argument()
                    + " output=" + Production2040Contract.projectPath(definition.smokeOutput()));
            throw failure;
        }
        ValidateMatsim2040ProductionSmokeOutput.validate(definition, protectedBefore);
        System.out.println("2040 PRODUCTION SMOKE PASS scenario=" + definition.argument());
        System.out.println("This is a technical integration test, not a production run or scientific result.");
    }
}
