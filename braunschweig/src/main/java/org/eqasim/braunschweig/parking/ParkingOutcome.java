package org.eqasim.braunschweig.parking;

/**
 * The rule that decided the parking cost of one car stay (design "parking cost zones", section 3.2; eqasim-bs issue
 * #436). Every zero-cost path has its own value, so counting outcomes shows which rule priced the car stays: a broken
 * primary path, such as an empty zone join or missing activity attributes, then appears as an implausible NO_ZONE or
 * free share instead of silently lowering car costs.
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
	/** The zone exempts its residents and the person is a resident of this zone. */
	RESIDENT_FREE,
	/** The stay overlaps the fee window of none of the days it touches. */
	OUTSIDE_FEE_HOURS,
	/**
	 * The stay overlaps a fee window but costs 0 ct: it is within the zone's free-stay threshold, or the metered price is
	 * 0 (disc parking in a resident zone, whose hourly rate is 0).
	 */
	FREE_WITHIN_LIMIT,
	/** Started billing units at the hourly rate, with the zone's first-period block and daily cap where it has them. */
	PAID_METERED,
	/** The chargeable stay exceeds the zone's maximum stay: the zone's long-stay product is paid (assumption M1). */
	PAID_LONG_STAY,
	/** Campus zone, purpose work or education: the member day product. */
	PAID_CAMPUS_MEMBER,
	/** Campus zone, any other purpose: the guest day product. */
	PAID_CAMPUS_GUEST
}
