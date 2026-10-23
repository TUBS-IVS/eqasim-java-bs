package org.eqasim.core.scenario.routing;

import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripRouterFactoryBuilderWithDefaults;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Verifies the routed day-end shift (eqasim-bs ADR-0160): a plan whose last arrival by its ROUTED travel times
 * is later than the latest allowed arrival moves earlier as a whole and is routed again. Person 1125404 of the 1 %
 * rerun of the #465 fixes ended at 29:00 by its survey times, but its routed 132 km car trip home arrived at
 * 29.6 h and, with the per-link time steps of the QSim, after the end of the simulated day.
 */
public class DayEndShiftTest {
	private final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
	private final PopulationFactory factory = scenario.getPopulation().getFactory();

	private Plan plan() {
		Person person = factory.createPerson(Id.createPersonId("p"));
		Plan plan = factory.createPlan();
		person.addPlan(plan);
		return plan;
	}

	private Activity activity(Plan plan, String type, double x, double endTime) {
		Activity activity = factory.createActivityFromCoord(type, new Coord(x, 0.0));
		if (!Double.isNaN(endTime)) {
			activity.setEndTime(endTime);
		}
		plan.addActivity(activity);
		return activity;
	}

	private Leg leg(Plan plan, double travelTime) {
		Leg leg = factory.createLeg(TransportMode.walk);
		leg.setTravelTime(travelTime);
		plan.addLeg(leg);
		return leg;
	}

	@Test
	public void plannedLastArrivalReplaysEndTimesDurationsAndTravelTimes() {
		Plan plan = plan();
		activity(plan, "home", 0.0, 8.0 * 3600.0);
		leg(plan, 1800.0);
		// ends at its end time although the agent arrives earlier
		activity(plan, "work", 1.0, 17.0 * 3600.0);
		leg(plan, 600.0);
		// a duration-only stop: ends 0 s after the arrival
		Activity interaction = activity(plan, "car interaction", 2.0, Double.NaN);
		interaction.setMaximumDuration(0.0);
		leg(plan, 3600.0);
		// an activity that ends before the agent arrives ends at the arrival
		activity(plan, "shop", 3.0, 17.5 * 3600.0);
		leg(plan, 900.0);
		activity(plan, "home", 0.0, Double.NaN);

		Assert.assertEquals(17.0 * 3600.0 + 600.0 + 3600.0 + 900.0, DayEndShift.plannedLastArrival(plan), 0.0);
	}

	@Test
	public void lateDayMovesEarlierAsAWholeAndIsRoutedAgain() {
		DayEndShift shift = new DayEndShift(29.0 * 3600.0);

		Plan plan = plan();
		activity(plan, "home", 0.0, 15.0 * 3600.0);
		Activity outside = factory.createActivityFromCoord("outside", new Coord(1.0, 0.0));
		outside.setStartTime(15.75 * 3600.0);
		outside.setEndTime(28.25 * 3600.0 + 23.0);
		leg(plan, 3600.0);
		plan.addActivity(outside);
		leg(plan, 4837.0);
		activity(plan, "home", 0.0, Double.NaN);

		int[] reroutes = { 0 };
		double applied = shift.apply(plan, () -> {
			reroutes[0]++;
		});

		double excess = Math.ceil(28.25 * 3600.0 + 23.0 + 4837.0 - 29.0 * 3600.0);
		Assert.assertEquals(excess, applied, 0.0);
		Assert.assertEquals(1, reroutes[0]);
		Assert.assertEquals(15.0 * 3600.0 - excess, ((Activity) plan.getPlanElements().get(0)).getEndTime().seconds(),
				0.0);
		// every time moves by the same whole number of seconds: durations are kept
		Assert.assertEquals(15.75 * 3600.0 - excess, outside.getStartTime().seconds(), 0.0);
		Assert.assertEquals(28.25 * 3600.0 + 23.0 - excess, outside.getEndTime().seconds(), 0.0);
		Assert.assertTrue(DayEndShift.plannedLastArrival(plan) <= 29.0 * 3600.0);
		Assert.assertEquals(1, shift.getLatePlans());
		Assert.assertEquals(1, shift.getShiftedPlans());
		Assert.assertEquals(0, shift.getUnresolvedPlans());
	}

	@Test
	public void dayEndingInTimeIsLeftAlone() {
		DayEndShift shift = new DayEndShift(29.0 * 3600.0);
		Plan plan = plan();
		activity(plan, "home", 0.0, 8.0 * 3600.0);
		leg(plan, 3600.0);
		activity(plan, "work", 1.0, Double.NaN);

		Assert.assertEquals(0.0, shift.apply(plan, () -> Assert.fail("must not reroute")), 0.0);
		Assert.assertEquals(8.0 * 3600.0, ((Activity) plan.getPlanElements().get(0)).getEndTime().seconds(), 0.0);
		Assert.assertEquals(1, shift.getPlans());
		Assert.assertEquals(0, shift.getLatePlans());
	}

	@Test
	public void dayThatCannotMoveFarEnoughIsCountedUnresolved() {
		DayEndShift shift = new DayEndShift(29.0 * 3600.0);
		Plan plan = plan();
		// the first departure at 01:00 leaves room for one hour only
		activity(plan, "home", 0.0, 1.0 * 3600.0);
		leg(plan, 30.0 * 3600.0);
		activity(plan, "work", 1.0, Double.NaN);

		Assert.assertEquals(3600.0, shift.apply(plan, () -> {
		}), 0.0);
		Assert.assertEquals(0.0, ((Activity) plan.getPlanElements().get(0)).getEndTime().seconds(), 0.0);
		Assert.assertEquals(1, shift.getUnresolvedPlans());
	}

	@Test
	public void routerShiftsAndReroutesARoutedLatePlan() {
		TripRouter tripRouter = new TripRouterFactoryBuilderWithDefaults().build(scenario).get();
		PlanRouter router = new PlanRouter(scenario.getActivityFacilities(), tripRouter);
		DayEndShift shift = new DayEndShift(29.0 * 3600.0);

		Plan plan = plan();
		activity(plan, "home", 0.0, 27.0 * 3600.0);
		plan.addLeg(factory.createLeg(TransportMode.walk));
		activity(plan, "work", 10000.0, Double.NaN);

		router.run(plan, true, Set.of());
		double routedArrival = DayEndShift.plannedLastArrival(plan);
		Assert.assertTrue("fixture: the routed walk must end after 29 h", routedArrival > 29.0 * 3600.0);

		shift.apply(plan, () -> router.run(plan, true, Set.of()));

		Leg leg = TripStructureUtils.getLegs(plan).get(0);
		Assert.assertEquals(27.0 * 3600.0 - Math.ceil(routedArrival - 29.0 * 3600.0), leg.getDepartureTime().seconds(),
				0.0);
		Assert.assertTrue(DayEndShift.plannedLastArrival(plan) <= 29.0 * 3600.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void negativeLatestArrivalIsRejected() {
		new DayEndShift(-1.0);
	}
}
