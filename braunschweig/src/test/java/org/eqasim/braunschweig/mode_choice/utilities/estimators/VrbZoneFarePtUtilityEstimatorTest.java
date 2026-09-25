package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eqasim.braunschweig.fares.zonal.FareOutcomeCounter;
import org.eqasim.braunschweig.fares.zonal.FareQuote;
import org.eqasim.braunschweig.fares.zonal.FareQuoteSource;
import org.eqasim.braunschweig.fares.zonal.VrbFareModelTest;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPtPredictor;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultRoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Day-ticket cap correction of a PT utility estimated at its own single fare (ADR-0133 D9). Fixture
 * prices from VrbFareModelTest.JSON: city single 360 / day 720, ps3 single 770 / day 1540. Every
 * routed trip k rides line "L<k>", and the quote source answers per line, so each trip in a prefix
 * carries its own quote.
 */
public class VrbZoneFarePtUtilityEstimatorTest {
	private static final int LINES = 5;
	private static final FareQuote CITY = new FareQuote(360, FareQuote.VRB_SINGLE_ADULT, "city");
	private static final FareQuote PS3 = new FareQuote(770, FareQuote.VRB_SINGLE_ADULT, "ps3");
	private static final FareQuote EXTERNAL = new FareQuote(370, FareQuote.EXTERNAL_LOCAL_FLAT, null);

	private TransitSchedule schedule;
	private DiscreteModeChoiceTrip trip;
	private FareOutcomeCounter counter;

	@Before
	public void setUp() {
		schedule = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();
		TransitStopFacility from = stop(factory, "s0", 0);
		TransitStopFacility to = stop(factory, "s1", 3000);
		for (int index = 0; index < LINES; index++) {
			List<TransitRouteStop> stops = List.of(factory.createTransitRouteStop(from, 0, 0),
					factory.createTransitRouteStop(to, 600, 600));
			TransitRoute route = factory.createTransitRoute(Id.create("L" + index + "_r", TransitRoute.class), null, stops,
					"bus");
			TransitLine line = factory.createTransitLine(Id.create("L" + index, TransitLine.class));
			line.addRoute(route);
			schedule.addTransitLine(line);
		}
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(3000, 0));
		trip = new DiscreteModeChoiceTrip(origin, destination, "pt", List.of(), 0, 0, 0, new AttributesImpl());
		counter = new FareOutcomeCounter();
	}

	private TransitStopFacility stop(TransitScheduleFactory factory, String id, double x) {
		TransitStopFacility facility = factory.createTransitStopFacility(Id.create(id, TransitStopFacility.class),
				new Coord(x, 0), false);
		facility.setLinkId(Id.createLinkId("link_" + id));
		schedule.addStopFacility(facility);
		return facility;
	}

	private static List<PlanElement> route(int line) {
		Leg leg = PopulationUtils.createLeg("pt");
		leg.setDepartureTime(28_800);
		leg.setTravelTime(900);
		DefaultTransitPassengerRoute route = new DefaultTransitPassengerRoute(Id.createLinkId("link_s0"),
				Id.createLinkId("link_s1"), Id.create("s0", TransitStopFacility.class), Id.create("s1", TransitStopFacility.class),
				Id.create("L" + line, TransitLine.class), Id.create("L" + line + "_r", TransitRoute.class));
		route.setBoardingTime(29_100);
		leg.setRoute(route);
		return List.of(leg);
	}

	private static List<TripCandidate> earlierPtTrips(int count) {
		List<TripCandidate> prefix = new ArrayList<>();
		for (int line = 0; line < count; line++) {
			prefix.add(new DefaultRoutedTripCandidate(0, "pt", route(line), 900));
		}
		return prefix;
	}

	/** Quote source answering by the line of the (single) PT leg: quote k for line "L<k>". */
	private static FareQuoteSource source(FareQuote... byLine) {
		FareQuoteSource fares = mock(FareQuoteSource.class);
		org.mockito.stubbing.Answer<FareQuote> byLeg = invocation -> {
			List<?> elements = invocation.getArgument(1);
			TransitPassengerRoute route = (TransitPassengerRoute) ((Leg) elements.get(0)).getRoute();
			return byLine[Integer.parseInt(route.getLineId().toString().substring(1))];
		};
		when(fares.quote(any(), any())).thenAnswer(byLeg);
		when(fares.quoteWithoutCounting(any(), any())).thenAnswer(byLeg);
		return fares;
	}

	private VrbZoneFarePtUtilityEstimator vrb(FareQuoteSource fares) throws Exception {
		CostModel singleFare = (person, trip, elements) -> fares.quote(person, elements).cents() / 100.0;
		return new VrbZoneFarePtUtilityEstimator(BraunschweigModeParameters.buildDefault(),
				new BraunschweigPtPredictor(schedule), new BraunschweigPersonPredictor(), singleFare, fares,
				VrbFareModelTest.model(), counter);
	}

	/** The plain Braunschweig PT estimator at a fixed fare: the utility the cap correction must reproduce. */
	private BraunschweigPtUtilityEstimator atFare(double fareEur) {
		return new BraunschweigPtUtilityEstimator(BraunschweigModeParameters.buildDefault(),
				new BraunschweigPtPredictor(schedule), new BraunschweigPersonPredictor(), (person, trip, elements) -> fareEur);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
	}

	@Test
	public void capCorrectionTurnsTheUtilityAtTheSingleIntoTheUtilityAtTheMarginalFare() throws Exception {
		VrbZoneFarePtUtilityEstimator estimator = vrb(source(CITY, CITY, CITY, CITY));
		Person person = person();
		// Three city singles already reach the city day ticket (10.80 > 7.20): the fourth trip adds nothing.
		double atSingle = estimator.estimateUtility(person, trip, route(3));
		double correction = estimator.utilityAdjustment(person, trip, route(3), earlierPtTrips(3));
		assertTrue("a cheaper trip must gain utility", correction > 0.0);
		assertEquals(atFare(0.0).estimateUtility(person, trip, route(3)), atSingle + correction, 1e-9);
		assertEquals(atFare(3.60).estimateUtility(person, trip, route(3)), atSingle, 1e-9);
	}

	@Test
	public void capCorrectionIsZeroWhenTheCapDoesNotBind() throws Exception {
		VrbZoneFarePtUtilityEstimator estimator = vrb(source(CITY, CITY, CITY, PS3));
		// city day ticket 7.20 + ps3 single 7.70 is the cheapest day, so the ps3 trip still pays its full single.
		assertEquals(0.0, estimator.utilityAdjustment(person(), trip, route(3), earlierPtTrips(3)), 0.0);
		assertEquals(Map.of(), counter.snapshotAndReset());
	}

	@Test
	public void capCorrectionPricesWithoutCountingAndCountsOnlyTheCapLabel() throws Exception {
		FareQuoteSource fares = source(CITY, CITY, CITY);
		vrb(fares).utilityAdjustment(person(), trip, route(2), earlierPtTrips(2));
		// The cached estimate already counted the trip's own quote; the correction must not count it again.
		verify(fares, never()).quote(any(), any());
		verify(fares, times(3)).quoteWithoutCounting(any(), any());
		assertEquals(Map.of(FareQuote.DAY_TICKET_CAP_APPLIED, 1L), counter.snapshotAndReset());
	}

	@Test
	public void marginalCashIsCappedAtTheCityDayTicket() throws Exception {
		VrbZoneFarePtUtilityEstimator estimator = vrb(source(CITY, CITY, CITY));
		// 1st trip 360; 2nd 360 (sum 720 = city day ticket); 3rd adds nothing.
		assertEquals(360, estimator.marginalCents(person(), CITY, earlierPtTrips(0)));
		assertEquals(360, estimator.marginalCents(person(), CITY, earlierPtTrips(1)));
		assertEquals(0, estimator.marginalCents(person(), CITY, earlierPtTrips(2)));
	}

	@Test
	public void onlyRoutedPtCandidatesOfThePrefixEnterTheCap() throws Exception {
		VrbZoneFarePtUtilityEstimator estimator = vrb(source(CITY, CITY));
		TripCandidate carBetween = new DefaultTripCandidate(0, "car", 100);
		List<TripCandidate> prefix = new ArrayList<>(earlierPtTrips(1));
		prefix.add(carBetween);
		assertEquals(360, estimator.marginalCents(person(), CITY, prefix));
		// the same trip after a car trip only: no pt trip before, so no leakage from branches not in the prefix
		assertEquals(360, estimator.marginalCents(person(), CITY, List.of(carBetween)));
	}

	@Test
	public void externalAndFlatTripsAreNeverCappedNorPartOfTheCap() throws Exception {
		VrbZoneFarePtUtilityEstimator external = vrb(source(CITY, CITY, EXTERNAL));
		assertEquals(0.0, external.utilityAdjustment(person(), trip, route(2), earlierPtTrips(2)), 0.0);
		VrbZoneFarePtUtilityEstimator afterExternal = vrb(source(EXTERNAL, EXTERNAL, CITY));
		assertEquals(360, afterExternal.marginalCents(person(), CITY, earlierPtTrips(2)));
	}
}
