package org.eqasim.braunschweig.parking;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Parking cost of one car stay with garage options (spec Amendments E and H of parking cost zones v2 and spec v3 A1, A2 and A5,
 * eqasim-bs issue #436): a pure port of {@code braunschweig.parking.cost.parking_cost_with_garages_detail}, the Python
 * reference, pinned to it by the golden cases (E, F, P and X families). The price of a stay that can reach garages is the
 * probability-weighted mean of the option costs (an expected cost, no random draw, so the car utility stays stable across
 * mode-choice evaluations), rounded half up to the cent once.
 *
 * <p>Order of the rules (R-4d-2: the early rules are decided by {@link ParkingCostCalculator#cents}, called here and not
 * copied):
 * <ol>
 * <li>{@link ParkingCostCalculator#cents} decides first. A stay it prices before any product (home, employer-free, resident,
 * outside the street fee window), a campus stay, a garage decay of 0 for the purpose of the stay and a stay with no garage
 * in range keep that result: same cents, same outcome, probability 0. An electric car within the exemption of its zone (spec
 * v3 A5) is priced there too, EV_EXEMPT, and keeps it also with garages in range (ADR-0142 decision 5: an exempt stay is 0 ct);</li>
 * <li>otherwise the options are the street and every garage within the maximum distance, in this order, the garages by
 * ascending id, weighted by {@code StrictMath.exp(-distance / decay - beta * cost_cents / 100)} (spec v3 A2, ASSUMPTION
 * G1-d): {@code distance} is 0 for the street, {@code decay} the decay length of the purpose of the stay (lambda_c for work
 * and education, lambda for every other purpose, {@link #decayMetersForPurpose}, spec Amendment H1; a purpose whose decay is 0
 * has no garage options), {@code beta} the money coefficient in utility per EUR and {@code cost_cents} the cents the option
 * charges for THIS stay (the street included). With beta 0 the weights are those of v2, bit for bit. The street option costs
 * the cheapest of the EV exemption, the street or long-stay product and the zone's commuter product (the zone-level garage
 * family is superseded, spec E8), or is unavailable above the maximum stay without a long-stay product; a garage option costs
 * {@link GarageOptionPricing#optionCents};</li>
 * <li>spec v3 A1 (ASSUMPTION M3), before the weights: a garage whose {@code max_stay_min} the ELAPSED stay exceeds is no
 * option, and the street is no option above its maximum stay without a long-stay product; the weights are formed over the
 * remaining options only. With the street alone left the stay prices as the street (its own outcome, probability 0); with NO
 * option left it is priced as the street at its metered price, the maximum ignored, outcome NO_FEASIBLE_OPTION;</li>
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
 * server). The exponent is formed as {@code (-distance / decay) - ((beta * cost_cents) / 100)}, the operations and their order
 * the contract. If the total weight is not above 0 (every weight underflowed: a decay far below the distances or prices far
 * above what the exponential resolves, and no street option) the stay fails with an {@link IllegalStateException} that names
 * the zone, the stay and the decays instead of being priced with undefined probabilities.
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

	/** Cents per euro: the price enters the exponent in euro, {@code beta * cost_cents / 100}. */
	private static final double CENTS_PER_EURO = 100.0;

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
	 * @param garageProbability the sum of the garage probabilities, 0 when no garage acted; summed in the order of the
	 *        mixture, like the paid and the free part below, so the three are separate accumulations (a difference of two of
	 *        them can come out at -1e-17 when one part is far below 1e-16, which the report counter would refuse)
	 * @param garageProbabilityPaid the part of {@code garageProbability} that lies on garage options which cost more than 0 ct
	 *        for this stay, accumulated directly, never as a difference
	 * @param garageProbabilityFree the part of {@code garageProbability} that lies on garage options which cost 0 ct for this
	 *        stay (free lots, free grace periods), 0 when no garage acted (spec v3 A4: the paid and the free garage share
	 *        are reported separately)
	 * @param garagesInRange garage options of the stay within the maximum distance, counted for every stay that reached the
	 *        mixture step (a stay decided earlier, on a campus or with the options off has 0, as has a stay without a garage)
	 * @param expectedCents the unrounded expected cost in cents when an expectation was formed, empty otherwise
	 * @param streetFeasible whether the street side was an option of the stay (spec v3 A1): the EV exemption, the street or
	 *        long-stay product or the zone commuter product could serve it; false only where the maximum stay removed all of
	 *        them; true for a stay decided before the options act (early rule, campus)
	 */
	public record Result(long cents, ParkingOutcome outcome, double garageProbability, double garageProbabilityPaid,
			double garageProbabilityFree, int garagesInRange, OptionalDouble expectedCents, boolean streetFeasible) {
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
	 * Whether the stay was decided before any street, garage or commuter product was priced: by an early rule (no zone, home,
	 * employer-free, resident, outside the fee window) or in a campus zone. With such a stay the garage options never act, and
	 * the street side is not asked whether it was an option.
	 */
	static boolean decidedBeforeProducts(ParkingOutcome outcome, ZoneTariff tariff) {
		return EARLY_OUTCOMES.contains(outcome) || tariff.zoneType() == ZoneTariff.ZoneType.CAMPUS;
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
	 * The unnormalised weight of a parking option (spec v3 A2, ASSUMPTION G1-d), as the Python reference
	 * {@code option_weight}: {@code StrictMath.exp(-distanceMeters / decayMeters - optionPriceBetaPerEuro * costCents / 100)}.
	 * The operations and their order are the contract with the Python reference: the exponent is
	 * {@code (-distanceMeters / decayMeters) - ((optionPriceBetaPerEuro * costCents) / 100)}. With a beta of 0 the weight is the
	 * price-independent {@code exp(-distance / decay)} of v2 bit for bit. Pure, no validation of the arguments.
	 *
	 * @param distanceMeters walking distance to the destination in metres, 0 for the street
	 * @param decayMeters decay length in metres, above 0
	 * @param costCents the cents the option charges for this stay, 0 is valid
	 * @param optionPriceBetaPerEuro money coefficient in utility per EUR
	 */
	static double optionWeight(double distanceMeters, double decayMeters, long costCents, double optionPriceBetaPerEuro) {
		return StrictMath.exp(-distanceMeters / decayMeters - optionPriceBetaPerEuro * costCents / CENTS_PER_EURO);
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
	 * @param evExempt the person owns an electric car that the zone exempts from the street fee (spec v3 A5)
	 * @param decayMeters decay length lambda in metres (ASSUMPTION G1) of every purpose but work and education; 0 switches the
	 *        garage options off for those purposes
	 * @param commuteDecayMeters decay length lambda_c in metres (ASSUMPTION G1-c) of work and education; 0 switches the garage
	 *        options off for those purposes; required, never defaulted to lambda
	 * @param optionPriceBetaPerEuro money coefficient of the option weights in utility per EUR (spec v3 A2, ASSUMPTION G1-d),
	 *        finite and at least 0; 0 gives the price-independent weights of v2
	 * @param options the garage options of the destination (looked up only when they can act)
	 * @param counters optional; counts the stays, the garage option rates, the repeated closed schedules, the garage options
	 *        over their maximum stay and the stays without a feasible option
	 * @throws IllegalArgumentException for a negative or non-finite decay, commuter decay or option price coefficient (checked
	 *         for every purpose), a non-finite or negative time or a departure before the arrival
	 * @throws IllegalStateException when the total option weight is not above 0 (all weights underflowed, no street option)
	 * @throws ArithmeticException when a price leaves the long range (an implausible tariff) instead of wrapping around
	 */
	public static Result cents(ZoneTariff tariff, double arrival_s, double departure_s, String purpose, boolean parkingFree,
			boolean residentOfZone, boolean residentOfDistrict, boolean evExempt, double decayMeters,
			double commuteDecayMeters, double optionPriceBetaPerEuro, OptionSource options, GarageOptionCounters counters) {
		Objects.requireNonNull(options, "options");
		ParkingCostCalculator.Result base = ParkingCostCalculator.cents(tariff, arrival_s, departure_s, purpose, parkingFree,
				residentOfZone, residentOfDistrict, evExempt);
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
		if (!Double.isFinite(optionPriceBetaPerEuro) || optionPriceBetaPerEuro < 0.0) {
			throw new IllegalArgumentException("optionPriceBetaPerEuro must be a finite number >= 0 (utility per EUR; 0 gives"
					+ " the price-independent weights of v2), got " + optionPriceBetaPerEuro);
		}
		double decayOfStayMeters = decayMetersForPurpose(purpose, decayMeters, commuteDecayMeters);
		if (decidedBeforeProducts(base.outcome(), tariff)) {
			return new Result(base.cents(), base.outcome(), 0.0, 0.0, 0.0, 0, OptionalDouble.empty(), true);
		}
		// The street side of the stay (spec E8: the zone-level garage family is superseded): the EV exemption, the street or
		// long-stay product and the zone commuter product, whichever is cheapest, a tie going to the first of them, or none
		// of them above the maximum stay.
		long streetChargeable_s = ParkingCostCalculator.chargeableSeconds(arrival_s, departure_s, tariff.feeStart_s(),
				tariff.feeEnd_s());
		ParkingCostCalculator.Result street = cheapestStreetSide(
				ParkingCostCalculator.evExemptStreet(tariff, streetChargeable_s, evExempt),
				ParkingCostCalculator.streetProduct(tariff, streetChargeable_s),
				ParkingCostCalculator.commuterProduct(tariff, purpose));
		boolean streetFeasible = street != null;
		Result unchanged = new Result(base.cents(), base.outcome(), 0.0, 0.0, 0.0, 0, OptionalDouble.empty(),
				streetFeasible);
		if (decayOfStayMeters == 0.0) {
			return unchanged;
		}
		if (counters != null) {
			counters.countEligible();
		}
		GarageOptionModel.Option[] garageOptions = options.options();
		if (garageOptions.length == 0) {
			if (base.outcome() == ParkingOutcome.NO_FEASIBLE_OPTION && counters != null) {
				// No garage in range and no street product either (a street zone with a maximum stay and nothing to sell above
				// it): the stay is priced by the zone calculator as the street and counted here like the other
				// NO_FEASIBLE_OPTION stays, so that the rate is not blind to the zones without any garage.
				counters.countNoFeasibleOption();
			}
			return unchanged;
		}
		if (counters != null) {
			counters.countWithGaragesInRange();
		}
		// Spec v3 A1 (ASSUMPTION M3): a garage whose maximum stay the ELAPSED stay exceeds is no option; it is dropped before the
		// weights are formed, so that no probability mass goes to an option that cannot serve the stay.
		long elapsed_s = ParkingCostCalculator.wholeSeconds(departure_s, "departure_s")
				- ParkingCostCalculator.wholeSeconds(arrival_s, "arrival_s");
		GarageOptionModel.Option[] feasibleOptions = feasibleOptions(garageOptions, elapsed_s);
		if (counters != null) {
			counters.countOptionsInfeasible(garageOptions.length - feasibleOptions.length);
		}
		if (street != null && street.cents() == 0) {
			// Spec E4: nobody pays a garage when the street is free (an EV-exempt stay included, ADR-0142 decision 5).
			if (counters != null) {
				counters.countStreetFree();
			}
			return new Result(0, street.outcome(), 0.0, 0.0, 0.0, garageOptions.length, OptionalDouble.empty(), true);
		}
		if (street == null && counters != null) {
			counters.countStreetUnavailable();
		}
		if (feasibleOptions.length == 0) {
			if (street == null) {
				// No option can serve the stay (ASSUMPTION M3): priced as the street at its metered price for the duration, the
				// maximum ignored, so that the cost stays finite; the outcome and the counter name the case.
				if (counters != null) {
					counters.countNoFeasibleOption();
				}
				return new Result(ParkingCostCalculator.streetMeteredCents(tariff, streetChargeable_s),
						ParkingOutcome.NO_FEASIBLE_OPTION, 0.0, 0.0, 0.0, garageOptions.length, OptionalDouble.empty(), false);
			}
			// The street is the only option left: it is not an expectation over a mixture, so it keeps its own outcome.
			return new Result(street.cents(), street.outcome(), 0.0, 0.0, 0.0, garageOptions.length, OptionalDouble.empty(), true);
		}
		// Spec v3 A2: every option, the street included (distance 0, its own cents), is weighted by its distance AND its price
		// for THIS stay; the street only when it is an option. Beta 0 gives the weights of v2 bit for bit.
		int firstGarage = street != null ? 1 : 0;
		int optionCount = firstGarage + feasibleOptions.length;
		double[] weights = new double[optionCount];
		long[] costs = new long[optionCount];
		if (street != null) {
			weights[0] = optionWeight(0.0, decayOfStayMeters, street.cents(), optionPriceBetaPerEuro);
			costs[0] = street.cents();
		}
		for (int index = 0; index < feasibleOptions.length; index++) {
			GarageOptionModel.Option option = feasibleOptions[index];
			long garageCents = GarageOptionPricing.optionCents(option.garage(), arrival_s, departure_s, purpose, counters);
			costs[firstGarage + index] = garageCents;
			weights[firstGarage + index] = optionWeight(option.distanceMeters(), decayOfStayMeters, garageCents,
					optionPriceBetaPerEuro);
		}
		// R-4d-4: P_i = w_i / sum(w) and the expectation are summed left to right in double precision in the order street,
		// then the garages by ascending id; the Python reference does the same operations in the same order.
		double totalWeight = 0.0;
		for (double weight : weights) {
			totalWeight += weight;
		}
		if (!(totalWeight > 0.0)) {
			throw underflow(tariff, arrival_s, departure_s, purpose, decayMeters, commuteDecayMeters, decayOfStayMeters,
					optionPriceBetaPerEuro, street != null, feasibleOptions);
		}
		double expected = 0.0;
		double garageProbability = 0.0;
		double garageProbabilityPaid = 0.0;
		double garageProbabilityFree = 0.0;
		for (int position = 0; position < optionCount; position++) {
			double probability = weights[position] / totalWeight;
			expected += probability * costs[position];
			if (position >= firstGarage) {
				garageProbability += probability;
				if (costs[position] == 0) {
					garageProbabilityFree += probability;
				} else {
					garageProbabilityPaid += probability;
				}
			}
		}
		if (counters != null) {
			counters.countExpected();
		}
		// Rounded half up to the cent once, at the end.
		return new Result((long) Math.floor(expected + 0.5), ParkingOutcome.PAID_EXPECTED, garageProbability,
				garageProbabilityPaid, garageProbabilityFree, garageOptions.length, OptionalDouble.of(expected), street != null);
	}

	/**
	 * The garage options that admit the elapsed stay (spec v3 A1, ASSUMPTION M3), in the given order; the argument array
	 * itself when every option admits it, so that a model without a garage maximum stay allocates nothing.
	 */
	private static GarageOptionModel.Option[] feasibleOptions(GarageOptionModel.Option[] options, long elapsed_s) {
		int admitted = 0;
		for (GarageOptionModel.Option option : options) {
			if (option.garage().admitsElapsedStay(elapsed_s)) {
				admitted++;
			}
		}
		if (admitted == options.length) {
			return options;
		}
		GarageOptionModel.Option[] feasible = new GarageOptionModel.Option[admitted];
		int position = 0;
		for (GarageOptionModel.Option option : options) {
			if (option.garage().admitsElapsedStay(elapsed_s)) {
				feasible[position++] = option;
			}
		}
		return feasible;
	}

	/**
	 * The cheapest of the street-side products, a tie going to the first of them (the EV exemption, then the street or
	 * long-stay product, then the commuter product); null when none is available.
	 */
	private static ParkingCostCalculator.Result cheapestStreetSide(ParkingCostCalculator.Result... products) {
		ParkingCostCalculator.Result cheapest = null;
		for (ParkingCostCalculator.Result product : products) {
			if (product != null && (cheapest == null || product.cents() < cheapest.cents())) {
				cheapest = product;
			}
		}
		return cheapest;
	}

	/** The failure of a stay whose option weights all underflowed: names the zone, the stay, the decays and the garages. */
	private static IllegalStateException underflow(ZoneTariff tariff, double arrival_s, double departure_s, String purpose,
			double decayMeters, double commuteDecayMeters, double decayOfStayMeters, double optionPriceBetaPerEuro,
			boolean streetOption, GarageOptionModel.Option[] feasibleOptions) {
		StringBuilder distances = new StringBuilder();
		for (GarageOptionModel.Option option : feasibleOptions) {
			distances.append(distances.isEmpty() ? "" : ", ").append(option.garage().garageId()).append(' ')
					.append(option.distanceMeters()).append(" m");
		}
		return new IllegalStateException(String.format(Locale.ROOT,
				"every option weight underflowed to 0 in zone %s for the stay [%.0f, %.0f) s of purpose %s (decay %s m for the"
						+ " purpose, garage_decay_m %s m, garage_decay_commute_m %s m, optionPriceBetaPerEuro %s, street option"
						+ " %s, garage distances %s), so the probabilities of the options are undefined; the decay length of the"
						+ " purpose must be of the order of the garage distances, not far below them",
				tariff.zoneId(), arrival_s, departure_s, purpose, decayOfStayMeters, decayMeters, commuteDecayMeters,
				optionPriceBetaPerEuro, streetOption ? "present" : "absent", distances));
	}
}
