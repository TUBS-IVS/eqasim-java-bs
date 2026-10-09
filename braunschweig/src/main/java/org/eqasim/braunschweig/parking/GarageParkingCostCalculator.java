package org.eqasim.braunschweig.parking;

import java.util.EnumSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Parking cost of one car stay with garage options (spec Amendments E and H of parking cost zones v2, eqasim-bs issue #436): a
 * pure port of {@code braunschweig.parking.cost.parking_cost_with_garages_detail}, the Python reference, pinned to it by the
 * golden cases E01..E38. The price of a stay that can reach garages is the probability-weighted mean of the option costs (an
 * expected cost, no random draw, so the car utility stays stable across mode-choice evaluations), rounded half up to the cent
 * once.
 *
 * <p>Order of the rules (R-4d-2: the early rules are decided by the schema-2 calculator, called here and not copied):
 * <ol>
 * <li>{@link ParkingCostCalculator#cents} decides first. A stay it prices before any product (home, employer-free, resident,
 * outside the street fee window), a campus stay, a garage decay of 0 for the purpose of the stay and a stay with no garage
 * in range keep that result:
 * same cents, same outcome, probability 0;</li>
 * <li>otherwise the options are the street (weight 1) and every garage within the maximum distance (weight
 * {@code StrictMath.exp(-distance / decay)}, ASSUMPTION G1, spec E3, where {@code decay} is the decay length of the purpose of
 * the stay: lambda_c for work and education, lambda for every other purpose, {@link #decayMetersForPurpose}, spec Amendment
 * H1; a purpose whose decay is 0 has no garage options), in this order, the garages by ascending id. The street
 * option costs the cheaper of the street or long-stay product and the zone's commuter product (the zone-level garage family
 * is superseded, spec E8), or is unavailable above the maximum stay without a long-stay product; a garage option costs
 * {@link GarageOptionPricing#optionCents};</li>
 * <li>a street option that costs 0 means the stay pays 0 (spec E4: nobody pays a garage when the street is free): outcome of
 * the street product, probability 0; an unavailable street renormalises the weights over the garages;</li>
 * <li>otherwise {@code P_i = w_i / sum(w)}, and the price is {@code floor(sum(P_i * cost_i) + 0.5)} cents, outcome
 * PAID_EXPECTED, garage probability the sum of the garage {@code P_i}.</li>
 * </ol>
 *
 * <p>Numerical contract with the Python reference (R-4d-4): the weights, their sum and the expectation are summed left to
 * right in double precision in the order street, then the garages by ascending id; the rounding is applied once, at the end.
 * The golden generator keeps every pinned expectation at least 1e-6 cents from a half cent, so the last-bit difference between
 * the exponential of Java and that of Python's libm cannot flip a rounding. The exponential is {@link StrictMath#exp}
 * (fdlibm), not {@link Math#exp}, which the JVM may replace by a CPU-specific intrinsic: the weights, and with them the
 * expected cost and the garage probability, are then bit-identical on every JVM and CPU (a Windows development box and a Linux
 * server). If every weight underflows to 0 (a decay far below the distances) the stay fails with the decay and the distances
 * instead of being priced with undefined probabilities.
 *
 * <p>The garage options are looked up lazily through an {@link OptionSource}, only for a stay that reaches the mixture, so a
 * stay decided by an early rule, a campus stay and every stay of a model with the garage options off never pay for the lookup.
 *
 * <p>Stateless and free of side effects apart from the optional counters; all methods are static.
 */
public final class GarageParkingCostCalculator {
	/** The outcomes decided before any product is priced; with them, and on a campus, the garages never act. */
	private static final Set<ParkingOutcome> EARLY_OUTCOMES = EnumSet.of(ParkingOutcome.NO_ZONE, ParkingOutcome.HOME,
			ParkingOutcome.EMPLOYER_FREE, ParkingOutcome.RESIDENT_FREE, ParkingOutcome.OUTSIDE_FEE_HOURS);

	/**
	 * Supplies the garage options of the destination of a stay, ordered by ascending garage id, each within the maximum
	 * distance: {@link GarageOptionModel#optionsWithin}. Called at most once per stay and only when the options can act.
	 */
	@FunctionalInterface
	public interface OptionSource {
		GarageOptionModel.Option[] options();
	}

	/**
	 * Price of one stay with garage options.
	 *
	 * @param cents non-negative price in euro cents
	 * @param outcome the rule that decided the price; PAID_EXPECTED for an expectation
	 * @param garageProbability the sum of the garage probabilities, 0 when no garage acted
	 * @param garagesInRange garage options of the stay within the maximum distance, counted for every stay that reached the
	 *        mixture step (a stay decided earlier, on a campus or with the options off has 0, as has a stay without a garage)
	 * @param expectedCents the unrounded expected cost in cents when an expectation was formed, empty otherwise
	 */
	public record Result(long cents, ParkingOutcome outcome, double garageProbability, int garagesInRange,
			OptionalDouble expectedCents) {
		public Result {
			if (cents < 0) {
				throw new IllegalArgumentException("parking cost must be non-negative euro cents, got " + cents);
			}
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(expectedCents, "expectedCents");
		}
	}

	private GarageParkingCostCalculator() {
	}

	/**
	 * The decay length of the garage weights that governs a stay (ASSUMPTION G1-c, spec Amendment H1), as the Python reference
	 * {@code garage_decay_m_for_purpose}: {@code commuteDecayMeters} (lambda_c) for a purpose in
	 * {@link ParkingCostCalculator#COMMUTER_PURPOSES} (work, education), {@code decayMeters} (lambda) for every other
	 * purpose. The single place that decides it. The purpose is the MATSim activity type, matched exactly (case-sensitive).
	 * Pure, no validation (the calculator validates both lengths).
	 */
	public static double decayMetersForPurpose(String purpose, double decayMeters, double commuteDecayMeters) {
		return ParkingCostCalculator.COMMUTER_PURPOSES.contains(purpose) ? commuteDecayMeters : decayMeters;
	}

	/**
	 * Parking cost of one car stay with garage options, as the Python reference {@code parking_cost_with_garages_detail}.
	 *
	 * @param tariff tariff of the zone the activity lies in
	 * @param arrival_s car arrival in simulation seconds
	 * @param departure_s departure the stay is priced to in simulation seconds (terminal rule and minimum stay applied, as for
	 *        {@link ParkingCostCalculator#cents}); every product and every option prices this one interval
	 * @param purpose activity purpose (the MATSim activity type)
	 * @param parkingFree value of the activity attribute parkingFree
	 * @param residentOfZone the person is a resident of this zone (rule R1)
	 * @param residentOfDistrict the activity lies in the resident parking district of the person's home (rule R2)
	 * @param decayMeters decay length lambda in metres (ASSUMPTION G1) of every purpose but work and education; 0 switches the
	 *        garage options off for those purposes
	 * @param commuteDecayMeters decay length lambda_c in metres (ASSUMPTION G1-c) of work and education; 0 switches the garage
	 *        options off for those purposes; required, never defaulted to lambda
	 * @param options the garage options of the destination (looked up only when they can act)
	 * @param counters optional; counts the stays, the garage option rates and the repeated closed schedules
	 * @throws IllegalArgumentException for a negative or non-finite decay or commuter decay (checked for every purpose), a
	 *         non-finite or negative time or a departure before the arrival
	 * @throws ArithmeticException when a price leaves the long range (an implausible tariff) instead of wrapping around
	 */
	public static Result cents(ZoneTariff tariff, double arrival_s, double departure_s, String purpose, boolean parkingFree,
			boolean residentOfZone, boolean residentOfDistrict, double decayMeters, double commuteDecayMeters,
			OptionSource options, GarageOptionCounters counters) {
		Objects.requireNonNull(options, "options");
		ParkingCostCalculator.Result base = ParkingCostCalculator.cents(tariff, arrival_s, departure_s, purpose, parkingFree,
				residentOfZone, residentOfDistrict);
		if (counters != null) {
			counters.countStay();
		}
		if (!Double.isFinite(decayMeters) || decayMeters < 0.0) {
			throw new IllegalArgumentException("decayMeters must be a finite number of metres >= 0 (0 switches the garage"
					+ " options off for every purpose but work and education), got " + decayMeters);
		}
		if (!Double.isFinite(commuteDecayMeters) || commuteDecayMeters < 0.0) {
			throw new IllegalArgumentException("commuteDecayMeters must be a finite number of metres >= 0 (0 switches the garage"
					+ " options off for work and education), got " + commuteDecayMeters);
		}
		double decayOfStayMeters = decayMetersForPurpose(purpose, decayMeters, commuteDecayMeters);
		Result unchanged = new Result(base.cents(), base.outcome(), 0.0, 0, OptionalDouble.empty());
		if (EARLY_OUTCOMES.contains(base.outcome()) || tariff.zoneType() == ZoneTariff.ZoneType.CAMPUS
				|| decayOfStayMeters == 0.0) {
			return unchanged;
		}
		if (counters != null) {
			counters.countEligible();
		}
		GarageOptionModel.Option[] garageOptions = options.options();
		if (garageOptions.length == 0) {
			return unchanged;
		}
		if (counters != null) {
			counters.countWithGaragesInRange();
		}
		// Spec E8: with garage options the zone-level garage family is superseded; the street option is the street or
		// long-stay product or the zone commuter product, whichever is cheaper (a tie keeps the street product), or is
		// unavailable (maximum stay exceeded without a long-stay product).
		long streetChargeable_s = ParkingCostCalculator.chargeableSeconds(arrival_s, departure_s, tariff.feeStart_s(),
				tariff.feeEnd_s());
		ParkingCostCalculator.Result street = cheaperStreetSide(ParkingCostCalculator.streetProduct(tariff, streetChargeable_s),
				ParkingCostCalculator.commuterProduct(tariff, purpose));
		if (street != null && street.cents() == 0) {
			// Spec E4: nobody pays a garage when the street is free.
			if (counters != null) {
				counters.countStreetFree();
			}
			return new Result(0, street.outcome(), 0.0, garageOptions.length, OptionalDouble.empty());
		}
		if (street == null && counters != null) {
			counters.countStreetUnavailable();
		}
		int firstGarage = street != null ? 1 : 0;
		int optionCount = firstGarage + garageOptions.length;
		double[] weights = new double[optionCount];
		long[] costs = new long[optionCount];
		if (street != null) {
			weights[0] = 1.0;
			costs[0] = street.cents();
		}
		for (int index = 0; index < garageOptions.length; index++) {
			GarageOptionModel.Option option = garageOptions[index];
			weights[firstGarage + index] = StrictMath.exp(-option.distanceMeters() / decayOfStayMeters);
			costs[firstGarage + index] = GarageOptionPricing.optionCents(option.garage(), arrival_s, departure_s, purpose,
					counters);
		}
		// R-4d-4: P_i = w_i / sum(w) and the expectation are summed left to right in double precision in the order street,
		// then the garages by ascending id; the Python reference does the same operations in the same order.
		double totalWeight = 0.0;
		for (double weight : weights) {
			totalWeight += weight;
		}
		if (!(totalWeight > 0.0)) {
			StringBuilder distances = new StringBuilder();
			for (GarageOptionModel.Option option : garageOptions) {
				distances.append(distances.isEmpty() ? "" : ", ").append(option.garage().garageId()).append(' ')
						.append(option.distanceMeters()).append(" m");
			}
			throw new IllegalArgumentException("every option weight underflowed to 0 (street option unavailable, decay "
					+ decayOfStayMeters + " m for the purpose " + purpose + ", garage distances " + distances + "), so the"
					+ " garage probabilities are undefined; the decay length of the purpose (garage_decay_m, or"
					+ " garage_decay_commute_m for work and education) must be of the order of the garage distances, not far"
					+ " below them");
		}
		double expected = 0.0;
		double garageProbability = 0.0;
		for (int position = 0; position < optionCount; position++) {
			double probability = weights[position] / totalWeight;
			expected += probability * costs[position];
			if (position >= firstGarage) {
				garageProbability += probability;
			}
		}
		if (counters != null) {
			counters.countExpected();
		}
		// Rounded half up to the cent once, at the end.
		return new Result((long) Math.floor(expected + 0.5), ParkingOutcome.PAID_EXPECTED, garageProbability,
				garageOptions.length, OptionalDouble.of(expected));
	}

	/** The cheaper of the available street-side products; a tie keeps the street product; null when neither is available. */
	private static ParkingCostCalculator.Result cheaperStreetSide(ParkingCostCalculator.Result street,
			ParkingCostCalculator.Result commuter) {
		if (street == null) {
			return commuter;
		}
		return commuter != null && commuter.cents() < street.cents() ? commuter : street;
	}
}
