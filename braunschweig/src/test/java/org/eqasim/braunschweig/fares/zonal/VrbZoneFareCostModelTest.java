package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Pricing rules on a synthetic schedule. Stops a, b, c lie in zone 40 and d, f in zone 70 (all on
 * one bus line); g and h lie outside every zone; e lies in the single-station zone 55 whose pair
 * with 40 is undefined in the fixture matrix. Prices come from VrbFareModelTest.JSON (fixtures,
 * not the real tariff).
 */
public class VrbZoneFareCostModelTest {
	private TransitSchedule schedule;
	private FareOutcomeCounter counter;
	private VrbZoneFareCostModel model;

	@Before
	public void setUp() throws Exception {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		schedule = scenario.getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();
		TransitStopFacility a = stop(factory, "a", 0, "40");
		TransitStopFacility b = stop(factory, "b", 300, "40");
		TransitStopFacility c = stop(factory, "c", 600, "40");
		TransitStopFacility d = stop(factory, "d", 900, "70");
		TransitStopFacility f = stop(factory, "f", 1500, "70");
		TransitStopFacility g = stop(factory, "g", 2500, null);
		TransitStopFacility h = stop(factory, "h", 31500, null);
		TransitStopFacility e = stop(factory, "e", 4000, "55");
		line(factory, "BUS", "bus", a, b, c, d, f, g);
		line(factory, "RAIL", "rail", d, g, h);
		line(factory, "ICE", "rail", c, h);
		line(factory, "NOSCOPE", "bus", a, b);
		line(factory, "TO55", "bus", a, e);
		line(factory, "LOOP", "bus", a, b, c, a, d);
		line(factory, "NOSCOPE_OUT", "bus", a, g);
		counter = new FareOutcomeCounter();
		PtLineScopes scopes = PtLineScopes.of(Map.of(
				"BUS", new PtLineScopes.Scope("regional", "bus"), "RAIL", new PtLineScopes.Scope("regional", "rail"),
				"ICE", new PtLineScopes.Scope("long_distance", "rail"), "TO55", new PtLineScopes.Scope("regional", "bus"),
				"LOOP", new PtLineScopes.Scope("regional", "bus")));
		model = new VrbZoneFareCostModel(schedule, VrbFareModelTest.model(), scopes, counter);
	}

	private TransitStopFacility stop(TransitScheduleFactory factory, String id, double x, String zone) {
		TransitStopFacility facility = factory.createTransitStopFacility(Id.create(id, TransitStopFacility.class),
				new Coord(x, 0), false);
		facility.setLinkId(Id.createLinkId("link_" + id));
		if (zone != null) {
			facility.getAttributes().putAttribute(VrbZoneFareCostModel.ZONE_ATTRIBUTE, zone);
		}
		schedule.addStopFacility(facility);
		return facility;
	}

	private void line(TransitScheduleFactory factory, String id, String mode, TransitStopFacility... stops) {
		List<TransitRouteStop> routeStops = new ArrayList<>();
		for (int index = 0; index < stops.length; index++) {
			routeStops.add(factory.createTransitRouteStop(stops[index], index * 60.0, index * 60.0));
		}
		TransitRoute route = factory.createTransitRoute(Id.create(id + "_r", TransitRoute.class), null, routeStops, mode);
		TransitLine line = factory.createTransitLine(Id.create(id, TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
	}

	private static Person person(String category, Integer age) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p-" + category + "-" + age));
		if (category != null) {
			person.getAttributes().putAttribute(VrbZoneFareCostModel.CATEGORY_ATTRIBUTE, category);
		}
		if (age != null) {
			PersonUtils.setAge(person, age);
		}
		return person;
	}

	private static List<PlanElement> ride(String line, String from, String to) {
		Leg leg = PopulationUtils.createLeg("pt");
		leg.setRoute(new DefaultTransitPassengerRoute(Id.createLinkId("link_" + from), Id.createLinkId("link_" + to),
				Id.create(from, TransitStopFacility.class), Id.create(to, TransitStopFacility.class),
				Id.create(line, TransitLine.class), Id.create(line + "_r", TransitRoute.class)));
		return List.of(leg);
	}

	private static List<PlanElement> rides(List<PlanElement> first, List<PlanElement> second) {
		List<PlanElement> all = new ArrayList<>(first);
		all.add(PopulationUtils.createActivityFromCoord("pt interaction", new Coord(0, 0)));
		all.addAll(second);
		return all;
	}

	private void assertQuote(long cents, String outcome, Person person, List<PlanElement> elements) {
		FareQuote quote = model.quote(person, elements);
		assertEquals(outcome, quote.outcome());
		assertEquals(cents, quote.cents());
		assertEquals(cents / 100.0, model.calculateCost_MU(person, null, elements), 1e-9);
	}

	@Test
	public void vrbSingleTicketsFollowTheMatrixAndTheShortTripRule() {
		assertQuote(200, FareQuote.VRB_SHORT_TRIP, person("single_ticket", 30), ride("BUS", "a", "d"));   // 3 intervals
		assertQuote(560, FareQuote.VRB_SINGLE_ADULT, person("single_ticket", 30), ride("BUS", "a", "f"));  // 40 -> 70 = ps2
		assertQuote(330, FareQuote.VRB_SINGLE_CHILD, person("single_ticket", 10), ride("BUS", "a", "f"));
		assertQuote(0, FareQuote.CHILD_FREE, person("single_ticket", 4), ride("BUS", "a", "f"));
	}

	@Test
	public void flatHoldersPayNothingInsideVrb() {
		assertQuote(0, FareQuote.VRB_FLAT_NATIONAL, person("deutschlandticket", 30), ride("BUS", "a", "f"));
		assertQuote(0, FareQuote.VRB_FLAT_REGIONAL, person("monthly_or_annual_subscription", 30), ride("BUS", "a", "f"));
	}

	@Test
	public void externalRidesUseNationalFlatRailBandsOrTheLocalSingle() {
		assertQuote(370, FareQuote.EXTERNAL_LOCAL_FLAT, person("single_ticket", 30), ride("BUS", "a", "g"));
		assertQuote(0, FareQuote.EXTERNAL_NATIONAL_FLAT, person("deutschlandticket", 30), ride("BUS", "a", "g"));
		assertQuote(370, FareQuote.EXTERNAL_LOCAL_FLAT, person("monthly_or_annual_subscription", 30), ride("BUS", "a", "g"));
		assertQuote(200, FareQuote.EXTERNAL_RAIL_NT, person("single_ticket", 30), ride("RAIL", "d", "g"));        // 1.6 km
		assertQuote(100, FareQuote.EXTERNAL_RAIL_NT, person("single_ticket", 10), ride("RAIL", "d", "g"));
		assertQuote(370, FareQuote.EXTERNAL_RAIL_BEYOND_BANDS, person("single_ticket", 30), ride("RAIL", "d", "h")); // 30.6 km
	}

	@Test
	public void mixedJourneyForVrbFlatHolderIsPricedOnceAsExternalRail() {
		Person holder = person("monthly_or_annual_subscription", 30);
		assertQuote(200, FareQuote.EXTERNAL_RAIL_NT, holder, rides(ride("BUS", "a", "d"), ride("RAIL", "d", "g")));
	}

	@Test
	public void longDistanceLinesFallBackEvenForNationalFlatHolders() {
		assertQuote(370, FareQuote.LONG_DISTANCE_FALLBACK, person("deutschlandticket", 30), ride("ICE", "c", "h"));
	}

	@Test
	public void lineWithoutScopeRowIsCountedAsFallbackNotCrash() {
		assertQuote(370, FareQuote.LINE_SCOPE_MISSING, person("single_ticket", 30), ride("NOSCOPE", "a", "b"));
	}

	@Test
	public void personWithoutTicketCategoryIsCountedAndPricedAsNoEntitlement() {
		assertQuote(560, FareQuote.CATEGORY_MISSING, person(null, 30), ride("BUS", "a", "f"));
		assertQuote(560, FareQuote.CATEGORY_UNKNOWN, person("platinum", 30), ride("BUS", "a", "f"));
	}

	@Test
	public void undefinedMatrixCellIsAFallbackAndWalkOnlyTripsAreFree() {
		assertQuote(370, FareQuote.VRB_PAIR_UNDEFINED_FALLBACK, person("single_ticket", 30), ride("TO55", "a", "e"));
		assertQuote(0, FareQuote.NO_PT_LEG, person("single_ticket", 30), List.of(PopulationUtils.createLeg("walk")));
	}

	@Test
	public void everyQuoteIsCountedExactlyOnce() {
		model.quote(person("single_ticket", 30), ride("BUS", "a", "f"));
		model.quote(person("deutschlandticket", 30), ride("ICE", "c", "h"));
		Map<String, Long> snapshot = counter.snapshotAndReset();
		assertEquals(Long.valueOf(1), snapshot.get(FareQuote.VRB_SINGLE_ADULT));
		assertEquals(Long.valueOf(1), snapshot.get(FareQuote.LONG_DISTANCE_FALLBACK));
		assertEquals(0.5, FareOutcomeCounter.fallbackShare(snapshot), 1e-12);
	}

	@Test
	public void loopRouteIsMeasuredFromTheLastBoardingOccurrenceBeforeAlighting() {
		// LOOP runs a b c a d: boarding at the second "a" rides one interval, a short trip, not four.
		assertQuote(200, FareQuote.VRB_SHORT_TRIP, person("single_ticket", 30), ride("LOOP", "a", "d"));
	}

	@Test
	public void categoryIssueKeepsTheCashFlagOfThePriceItWasGiven() {
		// Priced as no entitlement, so the trip is a VRB single and must enter the day-ticket cap.
		assertTrue(model.quote(person(null, 30), ride("BUS", "a", "f")).vrbCash());
		assertTrue(model.quote(person("platinum", 30), ride("BUS", "a", "f")).vrbCash());
		assertFalse(model.quote(person(null, 30), ride("BUS", "a", "g")).vrbCash());
	}

	@Test
	public void personWithoutAgeFailsLoudlyInsteadOfPayingTheAdultPrice() {
		assertThrows(IllegalStateException.class, () -> model.quote(person("single_ticket", null), ride("BUS", "a", "f")));
	}

	@Test
	public void unzonedStopOnALineWithoutScopeIsTheScopeFallbackNotExternalPricing() {
		assertQuote(370, FareQuote.LINE_SCOPE_MISSING, person("single_ticket", 30), ride("NOSCOPE_OUT", "a", "g"));
	}

	@Test
	public void missingCategoryCountsTowardsTheFallbackShare() {
		model.quote(person(null, 30), ride("BUS", "a", "f"));
		assertEquals(1.0, FareOutcomeCounter.fallbackShare(counter.snapshotAndReset()), 0.0);
	}

	@Test
	public void stopZoneOutsideTheFareModelIsRejectedAtConstruction() throws Exception {
		stop(schedule.getFactory(), "z", 9000, "99");
		assertThrows(IllegalStateException.class, () -> new VrbZoneFareCostModel(schedule, VrbFareModelTest.model(),
				PtLineScopes.of(Map.of()), new FareOutcomeCounter()));
	}
}
