package org.eqasim.core.scenario.routing;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Set;

import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * A worker failure of the population routing must fail the stage with the worker's failure as the cause
 * (eqasim-bs#678), an Error included: before, a worker caught only Exception, so an Error ended up in the unread
 * Future of the executor and the routing finished as if nothing had happened.
 */
public class PopulationRouterWorkerFailureTest {
	private static Population population() {
		Population population = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
		PopulationFactory factory = population.getFactory();
		for (int index = 0; index < 4; index++) {
			Person person = factory.createPerson(Id.createPersonId("person" + index));
			Plan plan = factory.createPlan();
			Activity home = factory.createActivityFromCoord("home", new Coord(0.0, 0.0));
			home.setEndTime(8.0 * 3600.0);
			plan.addActivity(home);
			plan.addLeg(factory.createLeg(TransportMode.car));
			plan.addActivity(factory.createActivityFromCoord("work", new Coord(5000.0, 0.0)));
			person.addPlan(plan);
			population.addPerson(person);
		}
		return population;
	}

	private static PlanRouter failingRouter(Throwable failure) {
		PlanRouter router = mock(PlanRouter.class);
		doThrow(failure).when(router).run(any(), anyBoolean(), any());
		return router;
	}

	private static void route(Throwable failure) throws InterruptedException {
		new PopulationRouter(2, 1, true, Set.of(), () -> failingRouter(failure)).run(population());
	}

	@Test(timeout = 60000)
	public void anExceptionInAWorkerFailsTheRoutingWithItsCause() {
		RuntimeException error = assertThrows(RuntimeException.class,
				() -> route(new IllegalStateException("worker failure")));
		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof IllegalStateException);
	}

	@Test(timeout = 60000)
	public void anErrorInAWorkerFailsTheRoutingWithItsCause() {
		RuntimeException error = assertThrows(RuntimeException.class,
				() -> route(new AssertionError("worker error")));
		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof AssertionError);
	}
}
