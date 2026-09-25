package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertEquals;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPredictorUtils;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;

/**
 * The long-distance routing surcharge valued like the Braunschweig PT utility values cost for this person
 * and trip: the flat price divided by the value of time beta_time / (beta_cost x distance interaction x
 * income interaction). Default parameters: in-vehicle time -0.025501 u/min, cost -0.310998 u/EUR, distance
 * exponent -0.257501 at 4.4 km, income exponent -0.8169 at 3200 EUR.
 */
public class ModeChoiceValueOfTimeTest {
	private static final double REFERENCE_SECONDS = 2190 / 100.0 / (0.025501 / 60.0 / 0.310998);

	private static Person person(Double income) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		if (income != null) {
			person.getAttributes().putAttribute(BraunschweigPredictorUtils.HOUSEHOLD_INCOME_ATTRIBUTE, income);
		}
		return person;
	}

	private static ModeChoiceValueOfTime valuation() {
		return new ModeChoiceValueOfTime(2190, BraunschweigModeParameters.buildDefault());
	}

	@Test
	public void referencePersonAtTheReferenceDistanceGetsTheReferenceSurcharge() {
		assertEquals(REFERENCE_SECONDS, valuation().surchargeSeconds(person(3200.0), 4.4), 1e-6);
		assertEquals(REFERENCE_SECONDS, valuation().referenceSurchargeSeconds(), 1e-6);
	}

	@Test
	public void longerTripsAndHigherIncomesGetASmallerSurcharge() {
		assertEquals(REFERENCE_SECONDS * Math.pow(60.0 / 4.4, -0.257501), valuation().surchargeSeconds(person(3200.0), 60.0),
				1e-6);
		assertEquals(REFERENCE_SECONDS * Math.pow(2.0, -0.8169), valuation().surchargeSeconds(person(6400.0), 4.4), 1e-6);
		assertEquals(REFERENCE_SECONDS * Math.pow(30.0 / 4.4, -0.257501) * Math.pow(6400.0 / 3200.0, -0.8169),
				valuation().surchargeSeconds(person(6400.0), 30.0), 1e-6);
	}

	@Test
	public void missingIncomeIsValuedAtTheReferenceIncomeLikeThePtEstimator() {
		assertEquals(REFERENCE_SECONDS * Math.pow(60.0 / 4.4, -0.257501), valuation().surchargeSeconds(person(null), 60.0),
				1e-6);
	}
}
