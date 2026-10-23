package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.AccessGroupRecord;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Outcome;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Parameters;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Result;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.SiteDecision;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityOption;
import org.matsim.facilities.FacilitiesUtils;

/**
 * Pins the large-site access rule of ADR-0165 on a small layout: a site at (0, 0) on a 600 veh/h side street, lot A
 * 100 m north (300 spaces; a 600 veh/h lane 5 m away, a 1000 veh/h street 200 m away), lot B 200 m south (200
 * spaces; only a 600 veh/h street next to it), lot C 2000 m east (beyond the 1500 m radius), and a 1000 veh/h
 * secondary road 400 m west. Sample size 1 %, so 10 activities stand for 1000.
 */
public class LargeSiteAccessAssignmentTest {
	private static final Coord SITE = new Coord(0.0, 0.0);

	private static Parameters parameters() {
		return new Parameters(0.01, 1000.0, 1000.0, 1000.0, 0.5, 1500.0, 300.0, 0.5, List.of("uni_loc_"),
				List.of("outside_", "EXT", "home_"));
	}

	/** Network, facilities, population and lots of one test. */
	private static final class Layout {
		final Network network = NetworkUtils.createNetwork();
		final ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		final Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		final List<ParkingLot> lots = new ArrayList<>();

		Layout() {
			LargeFacilityAccessLinkSelectorTest.addLink(network, "side_street", -100.0, 10.0, 100.0, 10.0, 600.0, "tertiary");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "lot_a_lane", -50.0, 105.0, 50.0, 105.0, 600.0, "service");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "lot_a_street", -100.0, 300.0, 100.0, 300.0, 1000.0,
					"secondary");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "lot_b_street", -100.0, -210.0, 100.0, -210.0, 600.0,
					"residential");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "lot_c_street", 1950.0, 10.0, 2050.0, 10.0, 1000.0,
					"secondary");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "west_secondary", -400.0, -100.0, -400.0, 100.0, 1000.0,
					"secondary");
			LargeFacilityAccessLinkSelectorTest.addLink(network, "home_link", 5000.0, 0.0, 5100.0, 0.0, 600.0, "residential");
			lots.add(new ParkingLot("way/a", new Coord(0.0, 100.0), 300));
			lots.add(new ParkingLot("way/b", new Coord(0.0, -200.0), 200));
			lots.add(new ParkingLot("way/c", new Coord(2000.0, 0.0), 5000));
		}

		ActivityFacility facility(String id, Coord coord, String linkId, String optionType) {
			ActivityFacility facility = facilities.getFactory().createActivityFacility(Id.create(id, ActivityFacility.class),
					coord, Id.createLinkId(linkId));
			ActivityOption option = facilities.getFactory().createActivityOption(optionType);
			option.setCapacity(25.0);
			facility.addActivityOption(option);
			facility.getAttributes().putAttribute("vrbTariffZone", "zone_" + id);
			facilities.addActivityFacility(facility);
			return facility;
		}

		/** A person home -> one activity per entry of {@code stops} (facility id, type) -> home, by car with routes. */
		Person person(String personId, String... stops) {
			PopulationFactory factory = population.getFactory();
			Person person = factory.createPerson(Id.createPersonId(personId));
			person.addPlan(plan(personId, stops));
			population.addPerson(person);
			return person;
		}

		Plan plan(String personId, String... stops) {
			PopulationFactory factory = population.getFactory();
			String homeId = "home_" + personId;
			if (!facilities.getFacilities().containsKey(Id.create(homeId, ActivityFacility.class))) {
				facility(homeId, new Coord(5050.0, 5.0), "home_link", "home");
			}
			Plan plan = factory.createPlan();
			plan.addActivity(activity(homeId, "home"));
			for (int index = 0; index < stops.length; index += 2) {
				plan.addLeg(leg());
				plan.addActivity(activity(stops[index], stops[index + 1]));
			}
			plan.addLeg(leg());
			plan.addActivity(activity(homeId, "home"));
			return plan;
		}

		private Activity activity(String facilityId, String type) {
			ActivityFacility facility = facilities.getFacilities().get(Id.create(facilityId, ActivityFacility.class));
			Activity activity = population.getFactory().createActivityFromActivityFacilityId(type, facility.getId());
			activity.setLinkId(facility.getLinkId());
			activity.setCoord(facility.getCoord());
			return activity;
		}

		private Leg leg() {
			Leg leg = population.getFactory().createLeg(TransportMode.car);
			leg.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("home_link"), Id.createLinkId("side_street")));
			return leg;
		}

		/** A work site at SITE: {@code workPersons} persons at {@code work_a} plus {@code incommuters} ic_work facilities. */
		void workSite(int workPersons, int incommuters) {
			facility("work_a", SITE, "side_street", "work");
			for (int index = 0; index < workPersons; index++) {
				person("w" + index, "work_a", "work");
			}
			for (int index = 0; index < incommuters; index++) {
				facility("ic_work_" + index, SITE, "side_street", "work");
				person("ic" + index, "ic_work_" + index, "work");
			}
		}

		Result run() {
			return new LargeSiteAccessAssignment(network, parameters(), lots).run(facilities, population);
		}

		String linkOf(String facilityId) {
			return facilities.getFacilities().get(Id.create(facilityId, ActivityFacility.class)).getLinkId().toString();
		}

		/** Link of the first activity of a person's selected plan at a facility whose id starts with {@code prefix}. */
		Activity siteActivity(String personId, String prefix) {
			for (PlanElement element : population.getPersons().get(Id.createPersonId(personId)).getSelectedPlan()
					.getPlanElements()) {
				if (element instanceof Activity activity && activity.getFacilityId().toString().startsWith(prefix)) {
					return activity;
				}
			}
			throw new AssertionError("no activity at " + prefix + " for " + personId);
		}
	}

	private static SiteDecision onlySite(Result result) {
		assertEquals(1, result.sites().size());
		return result.sites().get(0);
	}

	@Test
	public void incommuterFacilitiesAtTheSameCoordinatePoolIntoOneSite() {
		Layout layout = new Layout();
		layout.workSite(6, 5); // no facility alone reaches 1000, the site does: 11 activities = 1100 at 100 %
		SiteDecision site = onlySite(layout.run());
		assertEquals(6, site.facilities());
		assertEquals(11, site.activitiesInSample());
		assertEquals(1100.0, site.activities100Percent(), 1e-9);
		assertTrue(site.inScope());
		// 0.5 x 1100 = 550 spaces wanted, lots A and B within 1500 m hold 500.
		assertEquals(Outcome.DISTRIBUTED_PARTIAL, site.outcome());
	}

	@Test
	public void workSiteIsDistributedOverTheNearestLotsByTheirSpaces() {
		Layout layout = new Layout();
		layout.workSite(6, 4); // 1000 at 100 %: lots A and B (300 + 200 = 500 spaces) cover 0.5 x 1000
		Result result = layout.run();
		SiteDecision site = onlySite(result);
		assertEquals(Outcome.DISTRIBUTED, site.outcome());
		assertEquals(2, site.lotsUsed());
		assertEquals(2, site.accessGroups());
		assertEquals(500, site.spacesUsed());
		assertEquals(1.0, site.coverageRatio(), 1e-9);
		assertEquals((300.0 * 100.0 + 200.0 * 200.0) / 500.0, site.spacesWeightedDistanceMeters(), 1e-9);
		assertEquals(200.0, site.maximumLotDistanceMeters(), 1e-9);

		AccessGroupRecord groupA = result.groups().get(0);
		AccessGroupRecord groupB = result.groups().get(1);
		// Lot A takes the 1000 veh/h street 200 m away over the 600 veh/h lane 5 m away; lot B has only a 600 veh/h street.
		assertEquals("lot_a_street", groupA.linkId().toString());
		assertEquals("lot_b_street", groupB.linkId().toString());
		assertEquals(List.of(300, 200), List.of(groupA.spaces(), groupB.spaces()));
		assertEquals(List.of(6, 4), List.of(groupA.persons(), groupB.persons()));
		assertEquals(1, result.lotsOnCapacityRule());
		assertEquals(1, result.lotsOnNearestRule());

		int onA = 0;
		for (Person person : layout.population.getPersons().values()) {
			if (person.getId().toString().startsWith("w") || person.getId().toString().startsWith("ic")) {
				String link = layout.siteActivity(person.getId().toString(), person.getId().toString().startsWith("w")
						? "work_a" : "ic_work_").getLinkId().toString();
				assertTrue(link, link.equals("lot_a_street") || link.equals("lot_b_street"));
				onA += link.equals("lot_a_street") ? 1 : 0;
			}
		}
		assertEquals(6, onA);
	}

	@Test
	public void partialCoverageUsesAllLotsInReach() {
		Layout layout = new Layout();
		layout.workSite(12, 8); // 2000 at 100 % needs 1000 spaces; A and B give 500, C lies beyond 1500 m
		SiteDecision site = onlySite(layout.run());
		assertEquals(Outcome.DISTRIBUTED_PARTIAL, site.outcome());
		assertEquals(2, site.lotsUsed());
		assertEquals(0.5, site.coverageRatio(), 1e-9);
	}

	@Test
	public void aLotWithoutAnEligibleLinkWithin300mIsSkipped() {
		Layout layout = new Layout();
		layout.lots.add(new ParkingLot("way/isolated", new Coord(0.0, -1400.0), 900));
		layout.workSite(12, 8);
		Result result = layout.run();
		assertEquals(1, result.lotsSkippedNoLink());
		assertEquals(2, onlySite(result).lotsUsed());
	}

	@Test
	public void lotsSharingAnAccessLinkFormOneGroup() {
		Layout layout = new Layout();
		layout.lots.add(new ParkingLot("way/a2", new Coord(40.0, 110.0), 100));
		layout.workSite(6, 4); // needs 500: A (300), A2 (100, also on lot_a_street), B (200)
		Result result = layout.run();
		SiteDecision site = onlySite(result);
		assertEquals(3, site.lotsUsed());
		assertEquals(2, site.accessGroups());
		assertEquals(400, result.groups().get(0).spaces());
		assertEquals(List.of("way/a", "way/a2"), result.groups().get(0).lotIds());
	}

	@Test
	public void aMultiPersonFacilityIsSplitIntoSubFacilitiesAtTheSamePoint() {
		Layout layout = new Layout();
		layout.workSite(10, 0); // groups 6 (lot A) and 4 (lot B): work_a keeps its id for lot A's group
		Result result = layout.run();
		assertEquals("lot_a_street", layout.linkOf("work_a"));
		ActivityFacility sub = layout.facilities.getFacilities()
				.get(Id.create("work_a__access_2", ActivityFacility.class));
		assertNotNull(sub);
		assertEquals("lot_b_street", sub.getLinkId().toString());
		assertEquals(SITE, sub.getCoord());
		assertEquals(Set.of("work"), sub.getActivityOptions().keySet());
		assertEquals(25.0, sub.getActivityOptions().get("work").getCapacity(), 0.0);
		assertEquals("zone_work_a", sub.getAttributes().getAttribute("vrbTariffZone"));
		assertEquals("work_a", sub.getAttributes().getAttribute(LargeSiteAccessAssignment.PARENT_ATTRIBUTE));
		assertEquals(1, result.subFacilitiesCreated());
		// Each group lists the facilities whose activities use its link.
		assertEquals(List.of("work_a"), result.groups().get(0).facilityIds());
		assertEquals(List.of("work_a__access_2"), result.groups().get(1).facilityIds());

		int onSub = 0;
		for (int index = 0; index < 10; index++) {
			Activity activity = layout.siteActivity("w" + index, "work_a");
			boolean subFacility = activity.getFacilityId().toString().equals("work_a__access_2");
			assertEquals(subFacility ? "lot_b_street" : "lot_a_street", activity.getLinkId().toString());
			onSub += subFacility ? 1 : 0;
		}
		assertEquals(4, onSub);
		// The legs next to a relinked activity lost their route; the population is routed afterwards.
		Plan plan = layout.population.getPersons().get(Id.createPersonId("w0")).getSelectedPlan();
		assertNull(((Leg) plan.getPlanElements().get(1)).getRoute());
		assertNull(((Leg) plan.getPlanElements().get(3)).getRoute());
	}

	@Test
	public void singlePersonFacilitiesAreRelinkedWithoutSubFacilities() {
		Layout layout = new Layout();
		layout.workSite(0, 10); // ten incommuter workplaces at one point
		Result result = layout.run();
		assertEquals(0, result.subFacilitiesCreated());
		int onA = 0;
		for (int index = 0; index < 10; index++) {
			Activity activity = layout.siteActivity("ic" + index, "ic_work_");
			assertEquals("ic_work_" + index, activity.getFacilityId().toString());
			assertEquals(layout.linkOf("ic_work_" + index), activity.getLinkId().toString());
			onA += activity.getLinkId().toString().equals("lot_a_street") ? 1 : 0;
		}
		assertEquals(6, onA);
	}

	@Test
	public void aPersonWithActivitiesAtTwoFacilitiesOfOneSiteGetsOneGroup() {
		Layout layout = new Layout();
		layout.facility("sec_b_canteen", SITE, "side_street", "leisure");
		layout.workSite(6, 4);
		layout.person("both", "work_a", "work", "sec_b_canteen", "leisure");
		layout.run();
		assertEquals(layout.siteActivity("both", "work_a").getLinkId(),
				layout.siteActivity("both", "sec_b_canteen").getLinkId());
	}

	@Test
	public void anOutOfScopeSiteGetsOneHighCapacityLinkForAllItsFacilities() {
		Layout layout = new Layout();
		layout.facility("sec_b_mall", SITE, "side_street", "shop");
		layout.facility("sec_b_kiosk", SITE, "lot_a_lane", "shop"); // same point, a different nearest link
		for (int index = 0; index < 8; index++) {
			layout.person("s" + index, "sec_b_mall", "shop");
		}
		for (int index = 0; index < 3; index++) {
			layout.person("k" + index, "sec_b_kiosk", "shop");
		}
		SiteDecision site = onlySite(layout.run());
		assertTrue(!site.inScope());
		assertEquals(Outcome.MOVED, site.outcome());
		assertEquals("side_street", site.oldLinkId().toString()); // the link of the facility with most activities
		// The closest link of >= 1000 veh/h within 1000 m: lot_a_street 300 m north (west_secondary is 400 m away).
		assertEquals("lot_a_street", site.newLinkId().toString());
		assertEquals("lot_a_street", layout.linkOf("sec_b_mall"));
		assertEquals("lot_a_street", layout.linkOf("sec_b_kiosk"));
		assertEquals("lot_a_street", layout.siteActivity("k0", "sec_b_kiosk").getLinkId().toString());
	}

	@Test
	public void anInScopeSiteWithoutLotsFallsBackToTheSingleLinkRuleAndIsCounted() {
		Layout layout = new Layout();
		layout.lots.clear();
		layout.lots.add(new ParkingLot("way/c", new Coord(2000.0, 0.0), 5000));
		layout.workSite(6, 4);
		Result result = layout.run();
		SiteDecision site = onlySite(result);
		assertEquals(Outcome.MOVED, site.outcome());
		assertEquals(0, site.lotsUsed());
		assertEquals(1, result.inScopeWithoutLot());
	}

	@Test
	public void excludedPrefixesAreNeverSites() {
		Layout layout = new Layout();
		layout.facility("outside_1", SITE, "side_street", "work");
		layout.facility("EXT03241011", new Coord(10.0, 0.0), "side_street", "leisure");
		for (int index = 0; index < 12; index++) {
			layout.person("o" + index, "outside_1", "work", "EXT03241011", "leisure");
		}
		Result result = layout.run();
		assertEquals(List.of(), result.sites());
		assertEquals(2, result.excludedLargeFacilities());
		assertEquals("side_street", layout.linkOf("outside_1"));
	}

	@Test
	public void sizeCountsSelectedPlansButRelinkingCoversAllPlans() {
		Layout small = new Layout();
		small.workSite(5, 4); // 9 selected activities = 900 < 1000
		Person other = small.person("unselected_only");
		other.addPlan(small.plan("unselected_only", "work_a", "work"));
		assertEquals(List.of(), small.run().sites());

		Layout large = new Layout();
		large.workSite(6, 4);
		Person extra = large.person("unselected_only");
		Plan unselected = large.plan("unselected_only", "work_a", "work");
		extra.addPlan(unselected);
		SiteDecision site = onlySite(large.run());
		assertEquals(10, site.activitiesInSample());
		Activity relinked = (Activity) unselected.getPlanElements().get(2);
		assertTrue(relinked.getLinkId().toString(), relinked.getLinkId().toString().startsWith("lot_"));
	}

	@Test
	public void theUniversityPrefixPutsAnEducationSiteInScope() {
		Layout layout = new Layout();
		layout.facility("uni_loc_15", SITE, "side_street", "education");
		for (int index = 0; index < 10; index++) {
			layout.person("u" + index, "uni_loc_15", "education");
		}
		SiteDecision site = onlySite(layout.run());
		assertTrue(site.inScope());
		assertEquals(Outcome.DISTRIBUTED, site.outcome());
	}

	@Test
	public void outOfScopeSitesOnAdequateOrIsolatedLinksAreKept() {
		Layout adequate = new Layout();
		adequate.facility("sec_b_mall", new Coord(-390.0, 0.0), "west_secondary", "shop");
		for (int index = 0; index < 10; index++) {
			adequate.person("s" + index, "sec_b_mall", "shop");
		}
		assertEquals(Outcome.ADEQUATE, onlySite(adequate.run()).outcome());

		Layout isolated = new Layout();
		isolated.facility("sec_b_far", new Coord(0.0, 4000.0), "side_street", "shop");
		for (int index = 0; index < 10; index++) {
			isolated.person("s" + index, "sec_b_far", "shop");
		}
		SiteDecision site = onlySite(isolated.run());
		assertEquals(Outcome.FALLBACK, site.outcome());
		assertEquals("side_street", isolated.linkOf("sec_b_far"));
	}
}
