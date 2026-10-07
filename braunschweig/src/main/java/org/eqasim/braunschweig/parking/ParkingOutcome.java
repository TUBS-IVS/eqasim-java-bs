package org.eqasim.braunschweig.parking;

/**
 * The rule that decided the parking cost of one car stay (design "parking cost zones", section 3.2; eqasim-bs issue
 * #436). Every zero-cost path has its own value, so counting outcomes shows which rule priced the car stays: a broken
 * primary path, such as an empty zone join or missing activity attributes, then appears as an implausible NO_ZONE or
 * free share instead of silently lowering car costs. With the garage and the commuter product of schema 2 the paid
 * outcomes name the product that won the product minimum: PAID_METERED and PAID_LONG_STAY the street product,
 * PAID_GARAGE the garage, PAID_COMMUTER the commuter product.
 */
public enum ParkingOutcome {
	/**
	 * The activity lies in no parking zone, so parking is free (assumption Z1). Decided by the car cost model from the
	 * missing zone attribute; ParkingCostCalculator never returns it.
	 */
	NO_ZONE,
	/** The activity purpose is home: the car stands at the person's home and pays nothing. */
	HOME,
	/** The activity carries parkingFree = true: the employer or institution provides free parking. */
	EMPLOYER_FREE,
	/**
	 * The person is exempt by residence: the zone exempts its residents and the person is a resident of this zone (rule
	 * R1), or the stay lies in the resident parking district of the person's home in a zone that honours resident permits
	 * (rule R2, spec Amendment C3, ASSUMPTION R2-a). One outcome for both rules.
	 */
	RESIDENT_FREE,
	/** The stay overlaps the fee window of none of the days it touches. */
	OUTSIDE_FEE_HOURS,
	/**
	 * The stay overlaps a fee window but costs 0 ct: it is within the zone's free-stay threshold, the metered price is 0
	 * (disc parking in a resident zone, whose hourly rate is 0), or, with the garage product of schema 2, the garage is the
	 * cheapest product the driver can use and costs 0 ct, for instance a stay that lies in the street fee window but outside
	 * the garage's own fee window (golden case V18).
	 */
	FREE_WITHIN_LIMIT,
	/** Started billing units at the hourly rate, with the zone's first-period block and daily cap where it has them. */
	PAID_METERED,
	/** The chargeable stay exceeds the zone's maximum stay: the zone's long-stay product is paid (assumption M1). */
	PAID_LONG_STAY,
	/** Campus zone, purpose work or education: the member day product. */
	PAID_CAMPUS_MEMBER,
	/** Campus zone, any other purpose: the guest day product. */
	PAID_CAMPUS_GUEST,
	/**
	 * Schema 2 (parking cost zones v2): the garage product was the cheapest the driver can use and costs more than 0 ct
	 * (assumption P1). A garage stay of 0 ct, for instance outside the garage's own fee window, is FREE_WITHIN_LIMIT.
	 * Appended at the end of the declaration, so the order of the version 1 outcomes and of the outcome report is unchanged.
	 */
	PAID_GARAGE,
	/**
	 * Schema 2: the commuter product (commuter_day_cents per working day) was the cheapest product, or the cheapest for a
	 * campus member (assumptions P1, P2; spec amendment A4). Offered to the purposes work and education only.
	 */
	PAID_COMMUTER,
	/**
	 * Schema 3 (spec Amendment E, ruling R-4d-5): the stay was priced as the probability-weighted mean over the street option
	 * and at least one garage option within the maximum distance, or over the garage options alone where the street product
	 * is unavailable ({@link GarageParkingCostCalculator}). The price is the expected cost rounded half up to the cent once.
	 * Appended at the end, at the position of the Python reference's OUTCOMES, so the declaration order of every earlier
	 * outcome (part of the report contract) is unchanged.
	 */
	PAID_EXPECTED
}
