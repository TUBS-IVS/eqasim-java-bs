package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPersonVariables;
import org.eqasim.braunschweig.parking.ParkingConfigGroup;
import org.eqasim.braunschweig.parking.ParkingSearchTime;
import org.eqasim.braunschweig.parking.ParkingTariffs;
import org.eqasim.braunschweig.parking.ZoneParkingCarCostModel;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.CarPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Parking search time in the Braunschweig car utility (lever 4 of the zone-based parking costs, eqasim-bs issue #436):
 * {@code betaTravelTime_u_min * searchTimeFactor * search_time_min} of the destination's parking zone is added to the car
 * utility. With the default factor 0, and without the parking module, the utility is exactly the one of version 1. The
 * fixture tariff model gives zone fx_bs_ia_v2 5 min, fx_wob_v2 2 min, fx_campus_v2 an explicit 0 min and fx_bs_ia no
 * value; the car trip of every case lasts 20 min, costs 2.00 EUR and is 5 km long.
 */
public class BraunschweigCarUtilityEstimatorSearchTimeTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final double UTILITY_TOLERANCE = 1e-12;

	private static BraunschweigModeParameters parameters;
	private static ParkingTariffs tariffs;

	@BeforeClass
	public static void setUp() throws Exception {
		parameters = BraunschweigModeParameters.buildDefault();
		URL resource = BraunschweigCarUtilityEstimatorSearchTimeTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		tariffs = ParkingTariffs.read(Path.of(resource.toURI()));
	}

	/** One shared predictor: the term is not part of the cached car variables, so it needs no cache of its own. */
	private final CostModel cost = (person, trip, elements) -> 2.0;
	private final CarPredictor predictor = new CarPredictor(parameters, cost);
	private final BraunschweigPersonPredictor personPredictor = new BraunschweigPersonPredictor();

	private static ParkingSearchTime searchTime(String searchTimeFactor) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath("parking_tariffs_fixture.json");
		parking.addParam("searchTimeFactor", searchTimeFactor);
		return new ParkingSearchTime(tariffs, parking);
	}

	private BraunschweigCarUtilityEstimator estimator(ParkingSearchTime searchTime) {
		return new BraunschweigCarUtilityEstimator(parameters, predictor, personPredictor, searchTime);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("driver"));
	}

	private static Activity destination(String zoneId) {
		Activity destination = PopulationUtils.createActivityFromCoord("shop", new Coord(5000, 0));
		if (zoneId != null) {
			destination.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId);
		}
		return destination;
	}

	private static DiscreteModeChoiceTrip trip(Activity destination) {
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		return new DiscreteModeChoiceTrip(origin, destination, TransportMode.car, List.of(), 0, 0, 0, new AttributesImpl());
	}

	/** The routed car trip: one car leg of 20 min. */
	private static List<PlanElement> elements() {
		Leg leg = PopulationUtils.createLeg(TransportMode.car);
		leg.setTravelTime(1200.0);
		return List.of(leg);
	}

	/** The utility of version 1, from the terms of the estimator that exist without the search time term. */
	private double versionOneUtility(Person person, DiscreteModeChoiceTrip trip, List<PlanElement> elements) {
		BraunschweigCarUtilityEstimator estimator = estimator(ParkingSearchTime.none());
		CarVariables variables = predictor.predictVariables(person, trip, elements);
		BraunschweigPersonVariables personVariables = personPredictor.predictVariables(person, trip, elements);
		return parameters.car.alpha_u + parameters.car.betaTravelTime_u_min * variables.travelTime_min
				+ estimator.estimateAccessEgressTimeUtility(variables)
				+ estimator.estimateMonetaryCostUtility(variables, personVariables);
	}

	/**
	 * Factor 0 (the default) and the absent module leave the utility exactly as in version 1, for a destination with a
	 * search time as much as for one without.
	 */
	@Test
	public void withTheDefaultFactorOrWithoutTheModuleTheUtilityIsTheOneOfVersionOne() {
		Person person = person();
		List<PlanElement> elements = elements();
		DiscreteModeChoiceTrip inZone = trip(destination("fx_bs_ia_v2"));
		double versionOne = versionOneUtility(person, inZone, elements);

		double withoutModule = estimator(ParkingSearchTime.none()).estimateUtility(person, inZone, elements);
		assertEquals(versionOne, withoutModule, UTILITY_TOLERANCE);
		double factorZero = estimator(searchTime("0.0")).estimateUtility(person, inZone, elements);
		assertEquals(withoutModule, factorZero, 0.0);
	}

	/** The term is betaTravelTime_u_min * factor * minutes: -0.042431 * 5 for factor 1 and a 5-minute zone. */
	@Test
	public void theUtilityDropsByTheTravelTimeBetaTimesTheFactorTimesTheSearchMinutes() {
		Person person = person();
		List<PlanElement> elements = elements();
		DiscreteModeChoiceTrip trip = trip(destination("fx_bs_ia_v2"));
		double withoutTerm = estimator(ParkingSearchTime.none()).estimateUtility(person, trip, elements);
		double beta = parameters.car.betaTravelTime_u_min;
		assertTrue(beta < 0.0);

		double factorOne = estimator(searchTime("1.0")).estimateUtility(person, trip, elements);
		assertEquals(beta * 5.0, factorOne - withoutTerm, UTILITY_TOLERANCE);
		assertTrue(factorOne < withoutTerm);

		double factorTwoAndAHalf = estimator(searchTime("2.5")).estimateUtility(person, trip, elements);
		assertEquals(beta * 2.5 * 5.0, factorTwoAndAHalf - withoutTerm, UTILITY_TOLERANCE);

		double largestFactor = estimator(searchTime("10")).estimateUtility(person, trip, elements);
		assertEquals(beta * 10.0 * 5.0, largestFactor - withoutTerm, UTILITY_TOLERANCE);

		// Another zone, another column value: 2 minutes in fx_wob_v2.
		DiscreteModeChoiceTrip toWolfsburg = trip(destination("fx_wob_v2"));
		double wolfsburgWithoutTerm = estimator(ParkingSearchTime.none()).estimateUtility(person, toWolfsburg, elements);
		double wolfsburg = estimator(searchTime("1.0")).estimateUtility(person, toWolfsburg, elements);
		assertEquals(beta * 2.0, wolfsburg - wolfsburgWithoutTerm, UTILITY_TOLERANCE);
	}

	/** A destination outside every zone, in a zone without the column and in a zone with an explicit 0 gets no term. */
	@Test
	public void aDestinationWithoutSearchMinutesIsUnchangedAtAnyFactor() {
		Person person = person();
		List<PlanElement> elements = elements();
		for (String zoneId : new String[] { null, "fx_bs_ia", "fx_campus_v2" }) {
			DiscreteModeChoiceTrip trip = trip(destination(zoneId));
			double withoutTerm = estimator(ParkingSearchTime.none()).estimateUtility(person, trip, elements);
			assertEquals(String.valueOf(zoneId), withoutTerm, estimator(searchTime("10")).estimateUtility(person, trip, elements),
					0.0);
		}
	}

	/**
	 * The term sits in the estimator, not in the cached car variables: one predictor serves estimators of different
	 * factors on the same trip object, and each gets its own term.
	 */
	@Test
	public void theTermIsNotPartOfTheCachedCarVariables() {
		Person person = person();
		List<PlanElement> elements = elements();
		DiscreteModeChoiceTrip trip = trip(destination("fx_bs_ia_v2"));
		BraunschweigCarUtilityEstimator off = estimator(searchTime("0.0"));
		BraunschweigCarUtilityEstimator on = estimator(searchTime("1.0"));
		double firstOff = off.estimateUtility(person, trip, elements);
		double firstOn = on.estimateUtility(person, trip, elements);
		// Repeated calls on the same trip hit the predictor cache and still differ by the same term.
		assertEquals(firstOff, off.estimateUtility(person, trip, elements), 0.0);
		assertEquals(firstOn, on.estimateUtility(person, trip, elements), 0.0);
		assertEquals(parameters.car.betaTravelTime_u_min * 5.0, firstOn - firstOff, UTILITY_TOLERANCE);
	}

	/** The core keeps the global search penalty at 0, so the zone column is the only source of parking search time. */
	@Test
	public void theGlobalParkingSearchPenaltyStaysZero() {
		assertEquals(0.0, parameters.car.constantParkingSearchPenalty_min, 0.0);
	}
}
