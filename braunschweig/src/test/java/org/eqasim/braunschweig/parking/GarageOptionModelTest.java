package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;

import org.eqasim.braunschweig.parking.GarageOptionModel.Option;
import org.junit.Test;

/**
 * The garage options of a destination (spec Amendment E2): inclusive maximum distance, ascending garage id, the exact
 * distance of the Python reference, and an index that returns exactly what a scan of every garage returns.
 */
public class GarageOptionModelTest {
	private static final double MAXIMUM_DISTANCE_M = 1000.0;

	private static GarageTariff garage(String id, double x_m, double y_m) {
		return new GarageTariff(id, x_m, y_m, OptionalLong.of(200), OptionalInt.of(60), OptionalInt.of(0),
				OptionalInt.of(86400), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.empty(), List.of(), List.of(), OptionalLong.empty());
	}

	/** A scan of every garage as braunschweig.parking.cost.garage_options_in_range does it. */
	private static List<Option> scan(List<GarageTariff> garages, double x_m, double y_m, double maximumDistanceMeters) {
		List<GarageTariff> ordered = new ArrayList<>(garages);
		ordered.sort((first, second) -> first.garageId().compareTo(second.garageId()));
		List<Option> options = new ArrayList<>();
		for (GarageTariff garage : ordered) {
			double dx = garage.x_m() - x_m;
			double dy = garage.y_m() - y_m;
			double distance = Math.sqrt(dx * dx + dy * dy);
			if (distance <= maximumDistanceMeters) {
				options.add(new Option(garage, distance));
			}
		}
		return options;
	}

	@Test
	public void aGarageAtExactlyTheMaximumDistanceIsAnOptionAndOneMeterMoreIsNot() {
		GarageOptionModel model = new GarageOptionModel(
				List.of(garage("in", 580000 + 1000.0, 5750000.0), garage("out", 580000 + 1001.0, 5750000.0)), 400.0, 400.0,
				MAXIMUM_DISTANCE_M);
		Option[] options = model.optionsWithin(580000.0, 5750000.0);
		assertEquals(1, options.length);
		assertEquals("in", options[0].garage().garageId());
		assertEquals(1000.0, options[0].distanceMeters(), 0.0);
	}

	@Test
	public void theDistanceIsThePythagoreanSquareRootOfTheSquaredDifferences() {
		GarageOptionModel model = new GarageOptionModel(List.of(garage("g", 580300.0, 5750400.0)), 400.0, 400.0,
				MAXIMUM_DISTANCE_M);
		assertEquals(Math.sqrt(300.0 * 300.0 + 400.0 * 400.0), model.optionsWithin(580000.0, 5750000.0)[0].distanceMeters(),
				0.0);
		assertEquals(500.0, model.optionsWithin(580000.0, 5750000.0)[0].distanceMeters(), 0.0);
	}

	/**
	 * Four garages in four different cells (cell side 2 * D_max = 2000 m, boundaries at x = 582000 and y = 5750000), around a
	 * destination on the corner of the four cells. Cell order (x, then y) is g2, g4, g3, g1 and differs from the id order
	 * g1, g2, g3, g4, so the merge of the cells by garage id is exercised; the input is listed in descending id order.
	 */
	@Test
	public void theOptionsAreOrderedByAscendingGarageIdWhateverTheCellsAndTheInputOrder() {
		List<GarageTariff> garages = List.of(garage("g4", 581700.0, 5750400.0), garage("g3", 582300.0, 5749800.0),
				garage("g2", 581600.0, 5749700.0), garage("g1", 582400.0, 5750300.0));
		GarageOptionModel model = new GarageOptionModel(garages, 400.0, 400.0, MAXIMUM_DISTANCE_M);
		Option[] options = model.optionsWithin(582000.0, 5750000.0);
		List<String> ids = new ArrayList<>();
		for (Option option : options) {
			ids.add(option.garage().garageId());
		}
		assertEquals(List.of("g1", "g2", "g3", "g4"), ids);
		assertEquals(List.of("g1", "g2", "g3", "g4"), model.garages().stream().map(GarageTariff::garageId).toList());
		assertEquals(scan(garages, 582000.0, 5750000.0, MAXIMUM_DISTANCE_M), List.of(options));
	}

	/** Negative coordinates (cells with negative indices, garages on both sides of zero) are indexed like positive ones. */
	@Test
	public void negativeCoordinatesAreIndexedLikeThePositiveOnes() {
		List<GarageTariff> garages = List.of(garage("n1", -100.0, 100.0), garage("n2", 100.0, -100.0),
				garage("n3", -1900.0, -1900.0), garage("n4", -2100.0, -2100.0), garage("n5", 2500.0, 2500.0));
		GarageOptionModel model = new GarageOptionModel(garages, 400.0, 400.0, MAXIMUM_DISTANCE_M);
		for (double[] destination : new double[][] { { 0.0, 0.0 }, { -2000.0, -2000.0 }, { -1000.0, -1000.0 },
				{ 1800.0, 1800.0 }, { -2000.5, -1999.5 } }) {
			assertEquals("destination " + destination[0] + ", " + destination[1],
					scan(garages, destination[0], destination[1], MAXIMUM_DISTANCE_M),
					List.of(model.optionsWithin(destination[0], destination[1])));
		}
		assertEquals(List.of("n1", "n2"), java.util.Arrays.stream(model.optionsWithin(0.0, 0.0))
				.map(option -> option.garage().garageId()).toList());
		assertEquals(List.of("n3", "n4"), java.util.Arrays.stream(model.optionsWithin(-2000.0, -2000.0))
				.map(option -> option.garage().garageId()).toList());
	}

	/** A huge finite coordinate must neither loop over overflowing cell indices nor be silently priced: it is refused fast. */
	@Test(timeout = 10_000)
	public void aHugeCoordinateIsRefusedInsteadOfOverflowingTheCellIndex() {
		GarageOptionModel model = new GarageOptionModel(List.of(garage("g", 580000.0, 5750000.0)), 400.0, 400.0,
				MAXIMUM_DISTANCE_M);
		for (double huge : new double[] { 1e30, -1e30, Double.MAX_VALUE, 1e19 }) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> model.optionsWithin(huge, 0.0));
			assertTrue(error.getMessage(), error.getMessage().contains("EPSG:25832"));
			assertThrows(IllegalArgumentException.class, () -> model.optionsWithin(0.0, huge));
		}
		// A garage at an implausible coordinate is refused when the model is built.
		assertThrows(IllegalArgumentException.class,
				() -> new GarageOptionModel(List.of(garage("far", 1e30, 0.0)), 400.0, 400.0, MAXIMUM_DISTANCE_M));
		// A plausible coordinate is still answered.
		assertEquals(1, model.optionsWithin(580000.0, 5750000.0).length);
	}

	@Test
	public void noGarageInRangeIsTheSharedEmptyArray() {
		GarageOptionModel model = new GarageOptionModel(List.of(garage("g", 600000.0, 5750000.0)), 400.0, 400.0,
				MAXIMUM_DISTANCE_M);
		assertEquals(0, model.optionsWithin(580000.0, 5750000.0).length);
		assertSame(model.optionsWithin(580000.0, 5750000.0), model.optionsWithin(0.0, 0.0));
	}

	@Test
	public void theGridIndexReturnsExactlyWhatAScanOfEveryGarageReturns() {
		Random random = new Random(436L);
		List<GarageTariff> garages = new ArrayList<>();
		for (int index = 0; index < 60; index++) {
			// A 6 km x 6 km region, so that destinations have between 0 and about 10 garages in range, with a few negative
			// and cell-boundary coordinates.
			garages.add(garage(String.format("g%03d", index), 579000.0 + random.nextInt(6000), 5748000.0 + random.nextInt(6000)));
		}
		garages.add(garage("g_boundary", 580000.0, 5750000.0));
		for (double maximumDistance : new double[] { 250.0, 1000.0, 2500.0 }) {
			GarageOptionModel model = new GarageOptionModel(garages, 400.0, 400.0, maximumDistance);
			for (int query = 0; query < 2000; query++) {
				double x_m = 578500.0 + random.nextDouble() * 7000.0;
				double y_m = 5747500.0 + random.nextDouble() * 7000.0;
				List<Option> expected = scan(garages, x_m, y_m, maximumDistance);
				assertEquals("destination " + x_m + ", " + y_m, expected, List.of(model.optionsWithin(x_m, y_m)));
			}
			// Destinations exactly on a cell boundary and exactly on a garage.
			assertEquals(scan(garages, 580000.0, 5750000.0, maximumDistance), List.of(model.optionsWithin(580000.0, 5750000.0)));
			assertEquals(scan(garages, 0.0, 0.0, maximumDistance), List.of(model.optionsWithin(0.0, 0.0)));
			assertEquals(scan(garages, -1500.0, -2500.0, maximumDistance), List.of(model.optionsWithin(-1500.0, -2500.0)));
		}
	}

	@Test
	public void aDecayOfZeroIsInactiveAndAPositiveDecayIsActive() {
		List<GarageTariff> garages = List.of(garage("g", 580000.0, 5750000.0));
		assertFalse(new GarageOptionModel(garages, 0.0, 0.0, MAXIMUM_DISTANCE_M).isActive());
		assertTrue(new GarageOptionModel(garages, 0.01, 0.01, MAXIMUM_DISTANCE_M).isActive());
		assertFalse(new GarageOptionModel(List.of(), 0.0, 0.0, MAXIMUM_DISTANCE_M).isActive());
	}

	/** Spec Amendment H1: the model is active when either decay is above 0; both 0 switches it off. */
	@Test
	public void theModelIsActiveWhenEitherDecayIsAboveZero() {
		List<GarageTariff> garages = List.of(garage("g", 580000.0, 5750000.0));
		GarageOptionModel commuterOnly = new GarageOptionModel(garages, 0.0, 250.0, MAXIMUM_DISTANCE_M);
		assertTrue(commuterOnly.isActive());
		assertEquals(0.0, commuterOnly.decayMeters(), 0.0);
		assertEquals(250.0, commuterOnly.commuteDecayMeters(), 0.0);
		GarageOptionModel visitorOnly = new GarageOptionModel(garages, 400.0, 0.0, MAXIMUM_DISTANCE_M);
		assertTrue(visitorOnly.isActive());
		assertEquals(400.0, visitorOnly.decayMeters(), 0.0);
		assertEquals(0.0, visitorOnly.commuteDecayMeters(), 0.0);
		assertFalse(new GarageOptionModel(garages, 0.0, 0.0, MAXIMUM_DISTANCE_M).isActive());
	}

	/** A decay above 0 for either purpose group without a priced garage is refused, whichever one it is. */
	@Test
	public void eitherDecayAboveZeroWithoutAGarageIsRefusedAndNamesBothDecays() {
		for (double[] decays : new double[][] { { 0.0, 250.0 }, { 400.0, 0.0 }, { 400.0, 250.0 } }) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> new GarageOptionModel(List.of(), decays[0], decays[1], MAXIMUM_DISTANCE_M));
			String message = error.getMessage();
			assertTrue(message, message.contains("garage_decay_m is " + decays[0]));
			assertTrue(message, message.contains("garage_decay_commute_m is " + decays[1]));
			assertTrue(message, message.contains("lists no priced garage"));
		}
	}

	@Test
	public void rejectsAnInvalidCommuteDecay() {
		List<GarageTariff> garages = List.of(garage("g", 580000.0, 5750000.0));
		for (double commuteDecay : new double[] { -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
			IllegalArgumentException error = assertThrows("commute decay " + commuteDecay, IllegalArgumentException.class,
					() -> new GarageOptionModel(garages, 400.0, commuteDecay, MAXIMUM_DISTANCE_M));
			assertTrue(error.getMessage(),
					error.getMessage().contains("garage_decay_commute_m must be a finite number of metres >= 0"));
		}
	}

	@Test
	public void rejectsInvalidParametersDuplicatesAndOptionsWithoutGarages() {
		List<GarageTariff> garages = List.of(garage("g", 580000.0, 5750000.0));
		assertThrows(IllegalArgumentException.class, () -> new GarageOptionModel(garages, -1.0, 400.0, MAXIMUM_DISTANCE_M));
		assertThrows(IllegalArgumentException.class, () -> new GarageOptionModel(garages, Double.NaN, 400.0, MAXIMUM_DISTANCE_M));
		assertThrows(IllegalArgumentException.class, () -> new GarageOptionModel(garages, 400.0, 400.0, 0.0));
		assertThrows(IllegalArgumentException.class, () -> new GarageOptionModel(garages, 400.0, 400.0, Double.POSITIVE_INFINITY));
		IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
				() -> new GarageOptionModel(List.of(garage("g", 1.0, 1.0), garage("g", 2.0, 2.0)), 400.0, 400.0,
						MAXIMUM_DISTANCE_M));
		assertTrue(duplicate.getMessage(), duplicate.getMessage().contains("duplicate garage_id g"));
		IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
				() -> new GarageOptionModel(List.of(), 400.0, 400.0, MAXIMUM_DISTANCE_M));
		assertTrue(empty.getMessage(), empty.getMessage().contains("lists no priced garage"));
		GarageOptionModel model = new GarageOptionModel(garages, 400.0, 400.0, MAXIMUM_DISTANCE_M);
		assertThrows(IllegalArgumentException.class, () -> model.optionsWithin(Double.NaN, 5750000.0));
		assertThrows(IllegalArgumentException.class, () -> model.optionsWithin(580000.0, Double.POSITIVE_INFINITY));
		assertThrows(UnsupportedOperationException.class, () -> model.garages().clear());
	}
}
