package org.eqasim.simulation.transit.departure;

import java.util.Arrays;

import org.eqasim.core.components.transit.departure.DefaultDepartureFinder;
import org.eqasim.core.components.transit.departure.DepartureFinder.NoDepartureFoundException;
import org.eqasim.core.components.transit.departure.TransitBoardingFinder;
import org.eqasim.core.components.transit.departure.TransitBoardingFinder.Boarding;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Verifies the boarding choice of the schedule-based transit engine (eqasim-bs#465): a passenger who reaches the
 * stop one second after the planned departure (the QSim teleports a zero-length transfer walk in one time step)
 * still boards it, and a passenger whose planned route has no departure left boards another route of the same line.
 * With the stop-pair fallback (ADR-0161 of eqasim-bs) a passenger who missed the planned departure boards the vehicle
 * of any line that reaches the egress stop first.
 */
public class TestTransitBoardingFinder {
	private final TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
	private final TransitStopFacility stopA = factory.createTransitStopFacility(Id.create("A", TransitStopFacility.class),
			new Coord(0.0, 0.0), false);
	private final TransitStopFacility stopB = factory.createTransitStopFacility(Id.create("B", TransitStopFacility.class),
			new Coord(1000.0, 0.0), false);
	private final TransitStopFacility stopC = factory.createTransitStopFacility(Id.create("C", TransitStopFacility.class),
			new Coord(2000.0, 0.0), false);

	/** A route A -> B with the given in-vehicle time, one departure per given time. */
	private TransitRoute createRoute(String id, TransitStopFacility from, TransitStopFacility to, double inVehicleTime,
			double... departureTimes) {
		TransitRoute route = factory.createTransitRoute(Id.create(id, TransitRoute.class), null,
				Arrays.asList(factory.createTransitRouteStop(from, 0.0, 0.0),
						factory.createTransitRouteStop(to, inVehicleTime, inVehicleTime)),
				"bus");
		for (double departureTime : departureTimes) {
			route.addDeparture(factory.createDeparture(Id.create(id + "_" + departureTime, Departure.class),
					departureTime));
		}
		return route;
	}

	private TransitLine createLine(TransitRoute... routes) {
		TransitLine line = factory.createTransitLine(Id.create("line", TransitLine.class));
		for (TransitRoute route : routes) {
			line.addRoute(route);
		}
		return line;
	}

	@Test
	public void passengerOneSecondLateForAZeroDurationTransferBoardsThePlannedDeparture()
			throws NoDepartureFoundException {
		// The only departure of the planned route leaves at 08:00; the passenger arrives at 08:00:01
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		TransitLine line = createLine(planned);

		Boarding boarding = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true).findBoarding(line,
				planned, stopA.getId(), stopB.getId(), 8.0 * 3600.0 + 1.0);

		Assert.assertSame(planned, boarding.route);
		Assert.assertTrue(boarding.onPlannedRoute);
		Assert.assertEquals(8.0 * 3600.0, boarding.vehicleDepartureTime, 0.0);
		Assert.assertEquals(600.0, boarding.inVehicleTime, 0.0);

		// Without the tolerance the passenger misses it: the behaviour that left agents stuck
		try {
			new TransitBoardingFinder(new DefaultDepartureFinder(), 0.0, false).findBoarding(line, planned,
					stopA.getId(), stopB.getId(), 8.0 * 3600.0 + 1.0);
			Assert.fail("expected no departure");
		} catch (NoDepartureFoundException expected) {
		}
	}

	@Test
	public void toleranceDoesNotReachFurtherBack() {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		try {
			new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, false).findBoarding(createLine(planned),
					planned, stopA.getId(), stopB.getId(), 8.0 * 3600.0 + 2.0);
			Assert.fail("expected no departure");
		} catch (NoDepartureFoundException expected) {
		}
	}

	@Test
	public void latePassengerBoardsTheNextDepartureOfThePlannedRouteWhenThereIsOne()
			throws NoDepartureFoundException {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0, 9.0 * 3600.0);
		TransitRoute other = createRoute("other", stopA, stopB, 900.0, 8.5 * 3600.0);

		Boarding boarding = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true)
				.findBoarding(createLine(planned, other), planned, stopA.getId(), stopB.getId(), 8.1 * 3600.0);

		Assert.assertSame(planned, boarding.route);
		Assert.assertEquals(9.0 * 3600.0, boarding.vehicleDepartureTime, 0.0);
	}

	@Test
	public void latePassengerWithoutAPlannedDepartureLeftBoardsAnotherRouteOfTheLine()
			throws NoDepartureFoundException {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		// Serves B before A: wrong direction, never boarded
		TransitRoute reverse = createRoute("reverse", stopB, stopA, 600.0, 8.2 * 3600.0);
		// Does not serve B at all
		TransitRoute elsewhere = createRoute("elsewhere", stopA, stopC, 600.0, 8.2 * 3600.0);
		TransitRoute later = createRoute("later", stopA, stopB, 900.0, 8.5 * 3600.0, 10.0 * 3600.0);
		TransitRoute earlier = createRoute("earlier", stopA, stopB, 700.0, 8.4 * 3600.0);

		Boarding boarding = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true).findBoarding(
				createLine(planned, reverse, elsewhere, later, earlier), planned, stopA.getId(), stopB.getId(),
				8.1 * 3600.0);

		Assert.assertSame(earlier, boarding.route);
		Assert.assertFalse(boarding.onPlannedRoute);
		Assert.assertEquals(8.4 * 3600.0, boarding.vehicleDepartureTime, 0.0);
		// In-vehicle time of the boarded route, from its stop offsets
		Assert.assertEquals(700.0, boarding.inVehicleTime, 0.0);
	}

	@Test
	public void latePassengerIsStuckWhenTheLineFallbackIsOffOrNoRouteOfTheLineServesTheTrip() {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		TransitRoute later = createRoute("later", stopA, stopB, 900.0, 8.5 * 3600.0);
		TransitRoute reverse = createRoute("reverse", stopB, stopA, 600.0, 9.0 * 3600.0);

		for (TransitBoardingFinder finder : Arrays.asList(
				new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, false),
				new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true))) {
			TransitLine line = finder.isLineFallbackEnabled() ? createLine(planned, reverse) : createLine(planned, later);
			try {
				finder.findBoarding(line, planned, stopA.getId(), stopB.getId(), 8.1 * 3600.0);
				Assert.fail("expected no departure");
			} catch (NoDepartureFoundException expected) {
			}
		}
	}

	private TransitLine createLine(String id, TransitRoute... routes) {
		TransitLine line = factory.createTransitLine(Id.create(id, TransitLine.class));
		for (TransitRoute route : routes) {
			line.addRoute(route);
		}
		return line;
	}

	private TransitSchedule createSchedule(TransitLine... lines) {
		TransitSchedule schedule = factory.createTransitSchedule();
		for (TransitStopFacility stop : Arrays.asList(stopA, stopB, stopC)) {
			schedule.addStopFacility(stop);
		}
		for (TransitLine line : lines) {
			schedule.addTransitLine(line);
		}
		return schedule;
	}

	private TransitBoardingFinder stopPairFinder(TransitSchedule schedule) {
		return new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true, schedule, true);
	}

	@Test
	public void passengerWhoMissedTheLastVehicleOfTheLineBoardsAnotherLineServingTheStopPair()
			throws NoDepartureFoundException {
		// Person 693334 of the 1 % rerun of the #465 fixes: the planned route's last departure was 18:19, the
		// planned line's next one is 06:17 of the next day (30.28 h, after the end of the simulated day), and the
		// evening line serving the same two stops leaves at 19:09; the passenger reaches the stop at 19:05.
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 18.32 * 3600.0);
		TransitRoute nextDay = createRoute("nextDay", stopA, stopB, 600.0, 30.28 * 3600.0);
		TransitLine dayLine = createLine("day", planned, nextDay);
		TransitRoute evening = createRoute("evening", stopA, stopB, 700.0, 19.15 * 3600.0);
		TransitLine eveningLine = createLine("evening", evening);

		Boarding boarding = stopPairFinder(createSchedule(dayLine, eveningLine)).findBoarding(dayLine, planned,
				stopA.getId(), stopB.getId(), 19.09 * 3600.0, 16.57 * 3600.0);

		Assert.assertSame(evening, boarding.route);
		Assert.assertFalse(boarding.onPlannedRoute);
		Assert.assertFalse(boarding.onPlannedLine);
		Assert.assertEquals(19.15 * 3600.0, boarding.vehicleDepartureTime, 1e-6);
		Assert.assertEquals(700.0, boarding.inVehicleTime, 0.0);

		// The same-line fallback alone takes the next-day departure: the agent waits beyond the end of the day
		Boarding lineOnly = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true).findBoarding(dayLine,
				planned, stopA.getId(), stopB.getId(), 19.09 * 3600.0);
		Assert.assertSame(nextDay, lineOnly.route);
	}

	@Test
	public void passengerWhoMissedThePlannedDepartureTakesTheEarliestArrivalAtTheEgressStop()
			throws NoDepartureFoundException {
		// The planned route still runs, but only in an hour; another line leaves in 10 min and arrives first.
		// A slow line leaving even earlier arrives last and is not taken.
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0, 9.0 * 3600.0);
		TransitLine line = createLine("line", planned);
		TransitRoute other = createRoute("other", stopA, stopB, 600.0, 8.25 * 3600.0);
		TransitRoute slow = createRoute("slow", stopA, stopB, 4000.0, 8.2 * 3600.0);

		Boarding boarding = stopPairFinder(createSchedule(line, createLine("other", other), createLine("slow", slow)))
				.findBoarding(line, planned, stopA.getId(), stopB.getId(), 8.1 * 3600.0, 8.0 * 3600.0);

		Assert.assertSame(other, boarding.route);
	}

	@Test
	public void passengerOnTimeKeepsThePlannedDepartureEvenWhenAnotherLineArrivesEarlier()
			throws NoDepartureFoundException {
		TransitRoute planned = createRoute("planned", stopA, stopB, 900.0, 8.0 * 3600.0);
		TransitLine line = createLine("line", planned);
		TransitRoute faster = createRoute("faster", stopA, stopB, 300.0, 8.0 * 3600.0);

		Boarding boarding = stopPairFinder(createSchedule(line, createLine("faster", faster))).findBoarding(line,
				planned, stopA.getId(), stopB.getId(), 7.95 * 3600.0, 8.0 * 3600.0);

		Assert.assertSame(planned, boarding.route);
		Assert.assertTrue(boarding.onPlannedRoute);
		Assert.assertTrue(boarding.onPlannedLine);
	}

	@Test
	public void stopPairFallbackPrefersThePlannedRouteAndLineOnEqualArrival() throws NoDepartureFoundException {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0, 9.0 * 3600.0);
		TransitRoute sameLine = createRoute("a_sameLine", stopA, stopB, 600.0, 10.0 * 3600.0);
		TransitLine line = createLine("z_line", planned, sameLine);
		TransitRoute otherLine = createRoute("a_other", stopA, stopB, 600.0, 9.0 * 3600.0);

		Boarding boarding = stopPairFinder(createSchedule(line, createLine("a_line", otherLine))).findBoarding(line,
				planned, stopA.getId(), stopB.getId(), 8.5 * 3600.0, 8.0 * 3600.0);

		Assert.assertSame(planned, boarding.route);
	}

	@Test
	public void stopPairFallbackIgnoresRoutesInTheWrongDirectionAndIsStuckWithoutAnyVehicle() {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		TransitLine line = createLine("line", planned);
		TransitRoute reverse = createRoute("reverse", stopB, stopA, 600.0, 9.0 * 3600.0);
		TransitRoute elsewhere = createRoute("elsewhere", stopA, stopC, 600.0, 9.0 * 3600.0);

		try {
			stopPairFinder(createSchedule(line, createLine("reverse", reverse), createLine("elsewhere", elsewhere)))
					.findBoarding(line, planned, stopA.getId(), stopB.getId(), 8.5 * 3600.0, 8.0 * 3600.0);
			Assert.fail("expected no departure");
		} catch (NoDepartureFoundException expected) {
		}
	}

	@Test
	public void stopPairFallbackKeepsOtherRoutesOfThePlannedLineOutWhenTheLineFallbackIsOff()
			throws NoDepartureFoundException {
		TransitRoute planned = createRoute("planned", stopA, stopB, 600.0, 8.0 * 3600.0);
		TransitRoute sameLine = createRoute("sameLine", stopA, stopB, 600.0, 8.2 * 3600.0);
		TransitLine line = createLine("line", planned, sameLine);
		TransitRoute otherLine = createRoute("otherLine", stopA, stopB, 600.0, 8.4 * 3600.0);
		TransitSchedule schedule = createSchedule(line, createLine("other", otherLine));

		Boarding withLineFallback = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true, schedule, true)
				.findBoarding(line, planned, stopA.getId(), stopB.getId(), 8.1 * 3600.0, 8.0 * 3600.0);
		Assert.assertSame(sameLine, withLineFallback.route);

		Boarding withoutLineFallback = new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, false, schedule,
				true).findBoarding(line, planned, stopA.getId(), stopB.getId(), 8.1 * 3600.0, 8.0 * 3600.0);
		Assert.assertSame(otherLine, withoutLineFallback.route);
	}

	@Test(expected = IllegalArgumentException.class)
	public void stopPairFallbackNeedsTheSchedule() {
		new TransitBoardingFinder(new DefaultDepartureFinder(), 1.0, true, null, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void negativeToleranceIsRejected() {
		new TransitBoardingFinder(new DefaultDepartureFinder(), -1.0, true);
	}
}
