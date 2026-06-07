package org.eqasim.braunschweig.mode_choice.utilities.predictors;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;

public class BraunschweigPredictorUtils {
	private static final Logger logger = LogManager.getLogger(BraunschweigPredictorUtils.class);

	// Numeric monthly household income attribute written by the Python synthesis
	// (matsim/scenario/population.py: "householdIncomeEur", java.lang.Double).
	static public final String HOUSEHOLD_INCOME_ATTRIBUTE = "householdIncomeEur";

	// Sentinel returned when the attribute is absent. The estimator resolves this
	// to the reference income (neutral income-interaction term of 1.0) so a person
	// without a synthesised income is not implicitly treated as zero-income.
	static public final double HOUSEHOLD_INCOME_MISSING = Double.NaN;

	// No-silent-fallback instrumentation (project MANDATORY rule): count how often
	// the primary attribute is present vs. missing and log the rate periodically so
	// a broken attribute write (e.g. ~100% missing) is surfaced loudly.
	private static final AtomicLong incomePrimaryCount = new AtomicLong();
	private static final AtomicLong incomeMissingCount = new AtomicLong();
	private static final long INCOME_LOG_INTERVAL = 1_000_000L;

	static public boolean hasSubscription(Person person) {
		Boolean hasSubscription = (Boolean) person.getAttributes().getAttribute("hasPtSubscription");
		return hasSubscription != null && hasSubscription;
	}

	/**
	 * Reads the numeric monthly household income (EUR) written by the Python
	 * synthesis. Residents carry it as a {@code java.lang.Double}; cross-cordon
	 * incommuters may not (their population is written by a separate path), so a
	 * missing attribute returns {@link #HOUSEHOLD_INCOME_MISSING} (NaN) which the
	 * estimator maps to the reference income. The primary-vs-missing rate is logged.
	 *
	 * @param person the MATSim person
	 * @return monthly household income in EUR, or {@link #HOUSEHOLD_INCOME_MISSING}
	 */
	static public double getHouseholdIncome(Person person) {
		Object value = person.getAttributes().getAttribute(HOUSEHOLD_INCOME_ATTRIBUTE);

		long primary;
		long missing;
		if (value instanceof Number) {
			primary = incomePrimaryCount.incrementAndGet();
			missing = incomeMissingCount.get();
		} else {
			primary = incomePrimaryCount.get();
			missing = incomeMissingCount.incrementAndGet();
		}

		long total = primary + missing;
		if (total % INCOME_LOG_INTERVAL == 0L) {
			double missingRate = (double) missing / total;
			logger.info("householdIncomeEur attribute: primary {}/{} ({}%), missing {} ({}%)", primary, total,
					String.format("%.1f", 100.0 * primary / total), missing,
					String.format("%.1f", 100.0 * missingRate));
			if (missingRate > 0.5) {
				logger.warn("householdIncomeEur missing for {}% of predictions -- the numeric income attribute may "
						+ "not be written (check Python config write_income_eur).", String.format("%.1f", 100.0 * missingRate));
			}
		}

		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		return HOUSEHOLD_INCOME_MISSING;
	}

	static public boolean isOutside(Person person) {
		Boolean isOutside = (Boolean) person.getAttributes().getAttribute("outside");
		return isOutside != null && isOutside;
	}

	static public boolean hasDrivingLicense(Person person) {
		return !"no".equals(PersonUtils.getLicense(person));
	}

	static public boolean hasCarAvailability(Person person) {
		return !"none".equals((String) person.getAttributes().getAttribute("carAvailability"));
	}

	static public boolean hasBicycleAvailability(Person person) {
		return !"none".equals((String) person.getAttributes().getAttribute("bicycleAvailability"));
	}
	
	static public boolean isParisResident(Person person) {
		Boolean isResident = (Boolean) person.getAttributes().getAttribute("isParis");
		return isResident != null && isResident;
	}
}
