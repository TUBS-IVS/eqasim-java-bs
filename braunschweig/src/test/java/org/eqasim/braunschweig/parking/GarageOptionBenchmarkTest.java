package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

/**
 * Cost of the garage options per priced stay (spec Amendment E, ruling R-4e-3): a synthetic population of stays in one street
 * zone priced (a) by the schema-2 calculator, which is what every stay cost before the garage options, (b) by the garage
 * calculator with the options off (decay 0), and (c) by the garage calculator with 48 synthetic garages (a quarter tiered, a
 * quarter banded, half single window) and the grid index, for destinations spread over the region ("regional": most have no
 * garage in range) and for destinations that all lie next to garages ("dense": the worst case, several options each).
 *
 * <p>What is NOT measured: the thread-safe counters ({@link GarageOptionCounters} LongAdders, passed as null in the timed
 * passes) and the {@link ParkingOutcomeCounter} record of the cost model, both of which the real cost model adds per stay, and
 * the rest of the cost model (time tracker, attribute reads). The measured increase per stay is therefore a floor, not the
 * full cost of the options in a run; the 1 % scenario measurement of Task 5 is the figure for that.
 *
 * <p>The numbers are measurements of the machine they run on, never a pinned bound: the test always checks that the three
 * ways agree where they must (the options-off price equals the schema-2 price) and that the priced sums are reproducible, and
 * it measures and writes {@code target/parking-benchmark/garage_options_benchmark.txt} only when started with
 * {@code -Dparking.benchmark=true}, because a timing is neither deterministic nor cheap.
 */
public class GarageOptionBenchmarkTest {
	private static final Logger LOG = LogManager.getLogger(GarageOptionBenchmarkTest.class);

	private static final long RANDOM_SEED = 436L;
	private static final int GARAGES = 48;
	private static final int STAYS = 4096;
	private static final int SMOKE_ROUNDS = 2;
	private static final int WARM_UP_ROUNDS = 400;
	private static final int MEASURED_ROUNDS = 1500;
	private static final int REPEATS = 5;
	private static final double REGION_MIN_X_M = 600_000.0;
	private static final double REGION_MIN_Y_M = 5_790_000.0;
	private static final double REGION_SIZE_M = 12_000.0;

	private record Stay(double arrival_s, double departure_s, String purpose, double x_m, double y_m) {
	}

	private static GarageTariff singleWindow(String id, double x_m, double y_m, int ratePerHourCents) {
		return new GarageTariff(id, x_m, y_m, OptionalLong.of(ratePerHourCents), OptionalInt.of(30), OptionalInt.of(0),
				OptionalInt.of(86400), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.of(1200), List.of(), List.of(), OptionalLong.of(6300));
	}

	private static GarageTariff tiered(String id, double x_m, double y_m) {
		return new GarageTariff(id, x_m, y_m, OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.of(900), List.of(new GarageTariff.Tier(28800, 64800, 30, 60),
						new GarageTariff.Tier(64800, 28800, 30, 20)),
				List.of(), OptionalLong.empty());
	}

	private static GarageTariff banded(String id, double x_m, double y_m) {
		return new GarageTariff(id, x_m, y_m, OptionalLong.empty(), OptionalInt.empty(), OptionalInt.of(0),
				OptionalInt.of(86400), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.of(1500), List.of(),
				List.of(new GarageTariff.Band(0, OptionalInt.of(20), GarageTariff.BandKind.FREE, 0, OptionalInt.empty()),
						new GarageTariff.Band(20, OptionalInt.of(120), GarageTariff.BandKind.TOTAL, 100, OptionalInt.empty()),
						new GarageTariff.Band(120, OptionalInt.empty(), GarageTariff.BandKind.INCREMENT, 50,
								OptionalInt.of(60))),
				OptionalLong.empty());
	}

	private static List<GarageTariff> garages(Random random) {
		List<GarageTariff> garages = new ArrayList<>();
		for (int index = 0; index < GARAGES; index++) {
			String id = String.format("bench_g%02d", index);
			double x_m = REGION_MIN_X_M + random.nextDouble() * REGION_SIZE_M;
			double y_m = REGION_MIN_Y_M + random.nextDouble() * REGION_SIZE_M;
			garages.add(switch (index % 4) {
			case 0 -> tiered(id, x_m, y_m);
			case 1 -> banded(id, x_m, y_m);
			default -> singleWindow(id, x_m, y_m, 150 + 10 * (index % 7));
			});
		}
		return garages;
	}

	private static List<Stay> stays(Random random, List<GarageTariff> garages, boolean dense) {
		String[] purposes = { "work", "shop", "leisure", "education", "other" };
		List<Stay> stays = new ArrayList<>();
		for (int index = 0; index < STAYS; index++) {
			double x_m;
			double y_m;
			if (dense) {
				GarageTariff garage = garages.get(random.nextInt(garages.size()));
				x_m = garage.x_m() + (random.nextDouble() - 0.5) * 400.0;
				y_m = garage.y_m() + (random.nextDouble() - 0.5) * 400.0;
			} else {
				x_m = REGION_MIN_X_M + random.nextDouble() * REGION_SIZE_M;
				y_m = REGION_MIN_Y_M + random.nextDouble() * REGION_SIZE_M;
			}
			double arrival_s = 34_200.0 + random.nextInt(8 * 3600);
			double departure_s = arrival_s + 900 + random.nextInt(9 * 3600);
			stays.add(new Stay(arrival_s, departure_s, purposes[random.nextInt(purposes.length)], x_m, y_m));
		}
		return stays;
	}

	private static ZoneTariff streetZone() throws Exception {
		return ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
	}

	/** Total cents of one pass over the stays by the schema-2 calculator. */
	private static long schemaTwoPass(ZoneTariff zone, List<Stay> stays) {
		long sum = 0;
		for (Stay stay : stays) {
			sum += ParkingCostCalculator.cents(zone, stay.arrival_s(), stay.departure_s(), stay.purpose(), false, false, false)
					.cents();
		}
		return sum;
	}

	/** Total cents of one pass over the stays by the garage calculator, the options found through the grid index. */
	private static long garagePass(ZoneTariff zone, List<Stay> stays, GarageOptionModel model, GarageOptionCounters counters) {
		long sum = 0;
		for (Stay stay : stays) {
			sum += GarageParkingCostCalculator.cents(zone, stay.arrival_s(), stay.departure_s(), stay.purpose(), false, false,
					false, model.decayMeters(), () -> model.optionsWithin(stay.x_m(), stay.y_m()), counters).cents();
		}
		return sum;
	}

	/** The best (lowest) nanoseconds per stay over {@link #REPEATS} timed runs of {@code rounds} passes. */
	private static double nanosecondsPerStay(java.util.function.LongSupplier pass, int rounds) {
		double best = Double.MAX_VALUE;
		long sink = 0;
		for (int repeat = 0; repeat < REPEATS; repeat++) {
			long start = System.nanoTime();
			for (int round = 0; round < rounds; round++) {
				sink += pass.getAsLong();
			}
			best = Math.min(best, (System.nanoTime() - start) / ((double) rounds * STAYS));
		}
		assertTrue(sink >= 0);
		return best;
	}

	/** Total number of options found by the index for every stay of one pass. */
	private static long lookupPass(List<Stay> stays, GarageOptionModel model) {
		long found = 0;
		for (Stay stay : stays) {
			found += model.optionsWithin(stay.x_m(), stay.y_m()).length;
		}
		return found;
	}

	@Test
	public void theOptionsOffPriceEqualsTheSchemaTwoPriceAndThePricedSumsAreReproducible() throws Exception {
		Random random = new Random(RANDOM_SEED);
		List<GarageTariff> garages = garages(random);
		List<Stay> regional = stays(random, garages, false);
		List<Stay> dense = stays(random, garages, true);
		ZoneTariff zone = streetZone();
		GarageOptionModel off = new GarageOptionModel(garages, 0.0, 1000.0);
		GarageOptionModel on = new GarageOptionModel(garages, 400.0, 1000.0);

		assertEquals(schemaTwoPass(zone, regional), garagePass(zone, regional, off, null));
		assertEquals(schemaTwoPass(zone, dense), garagePass(zone, dense, off, null));
		GarageOptionCounters counters = new GarageOptionCounters();
		long regionalSum = garagePass(zone, regional, on, counters);
		long denseSum = garagePass(zone, dense, on, counters);
		assertEquals(regionalSum, garagePass(zone, regional, on, null));
		assertEquals(denseSum, garagePass(zone, dense, on, null));
		// The primary path is taken: the dense stays have garages in range and are priced as an expectation.
		GarageOptionCounters.Snapshot rates = counters.snapshot();
		assertTrue(rates.toString(), rates.withGaragesInRange() > STAYS / 2);
		assertTrue(rates.toString(), rates.expected() > STAYS / 2);
		assertTrue(rates.toString(), rates.optionEvaluations() > rates.expected());
		LOG.info("[parking-benchmark] smoke: {}", rates.summary());

		if (Boolean.getBoolean("parking.benchmark")) {
			measure(zone, garages, regional, dense, off, on);
		} else {
			// A short smoke run so that the passes themselves stay covered.
			for (int round = 0; round < SMOKE_ROUNDS; round++) {
				garagePass(zone, dense, on, null);
			}
		}
	}

	private static void measure(ZoneTariff zone, List<GarageTariff> garages, List<Stay> regional, List<Stay> dense,
			GarageOptionModel off, GarageOptionModel on) throws Exception {
		long warm = 0;
		for (int round = 0; round < WARM_UP_ROUNDS; round++) {
			warm += schemaTwoPass(zone, regional) + schemaTwoPass(zone, dense) + garagePass(zone, regional, off, null)
					+ garagePass(zone, regional, on, null) + garagePass(zone, dense, on, null);
		}
		assertTrue(warm >= 0);
		StringBuilder report = new StringBuilder();
		report.append("Garage option benchmark (GarageOptionBenchmarkTest, spec Amendment E, R-4e-3)\n");
		report.append("java.version=").append(System.getProperty("java.version")).append(" cores=")
				.append(Runtime.getRuntime().availableProcessors()).append(" stays_per_pass=").append(STAYS)
				.append(" rounds=").append(MEASURED_ROUNDS).append(" repeats=").append(REPEATS).append(" (best of repeats)\n");
		report.append("EXCLUDED from the timed passes: the LongAdder counters and the ParkingOutcomeCounter record of the cost"
				+ " model and the rest of the cost model, so the increase per stay below is a floor\n");
		report.append("garages=").append(garages.size()).append(" region_m=").append(REGION_SIZE_M).append(" max_distance_m=")
				.append(on.maximumDistanceMeters()).append(" decay_m=").append(on.decayMeters()).append('\n');
		for (boolean denseDestinations : new boolean[] { false, true }) {
			List<Stay> stays = denseDestinations ? dense : regional;
			double before = nanosecondsPerStay(() -> schemaTwoPass(zone, stays), MEASURED_ROUNDS);
			double optionsOff = nanosecondsPerStay(() -> garagePass(zone, stays, off, null), MEASURED_ROUNDS);
			double after = nanosecondsPerStay(() -> garagePass(zone, stays, on, null), MEASURED_ROUNDS);
			double lookup = nanosecondsPerStay(() -> lookupPass(stays, on), MEASURED_ROUNDS);
			GarageOptionCounters counters = new GarageOptionCounters();
			garagePass(zone, stays, on, counters);
			GarageOptionCounters.Snapshot rates = counters.snapshot();
			report.append(String.format(java.util.Locale.ROOT,
					"%s destinations: schema-2 calculator %.1f ns/stay, garage calculator options off %.1f ns/stay, options on"
							+ " %.1f ns/stay (%.1f ns more than before, of which the index lookup alone %.1f ns); garages in"
							+ " range %d/%d stays, %.2f garage options priced per stay%n",
					denseDestinations ? "dense" : "regional", before, optionsOff, after, after - before, lookup,
					rates.withGaragesInRange(), (long) STAYS, rates.optionEvaluations() / (double) STAYS));
		}
		Path file = Path.of("target", "parking-benchmark", "garage_options_benchmark.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, report.toString(), StandardCharsets.UTF_8);
		LOG.info("[parking-benchmark]\n{}", report);
	}
}
