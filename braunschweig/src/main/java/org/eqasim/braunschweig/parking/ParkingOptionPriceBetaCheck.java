package org.eqasim.braunschweig.parking;

import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Startup check of the money coefficient of the parking option weights against the mode choice (spec v3 A2, ASSUMPTION G1-d;
 * eqasim-bs issue #436). The option weights {@code exp(-distance / decay - beta * cost_cents / 100)} use ONE money coefficient
 * for the choice between parking options: the magnitude of the car cost coefficient {@code betaCost_u_MU} of the mode choice
 * ({@code BraunschweigModeParameters}), without the income or distance elasticity of the person's whole utility. The Python
 * preparation writes it into the tariff model as {@code option_price_beta_u_per_eur}; this check refuses a model whose value
 * differs from {@code Math.abs(betaCost_u_MU)} by more than {@value #TOLERANCE}, so that the coefficient of the garage choice
 * and the coefficient of the mode choice cannot drift apart silently. The run stops with an IllegalStateException that names
 * both values and both ways out (write the tariff model again with the coefficient of the mode choice, or change the mode
 * parameter); a coefficient of 0 in a schema-5 model (the price-independent weights of v2) is refused like any other value that
 * differs from the mode choice.
 *
 * <p>The mode choice value is read from the {@link ModeParameters} that BraunschweigModeChoiceModule binds: the singleton the car
 * predictor reads, after the mode parameters file and the {@code --mode-choice-parameter} command-line options have been applied,
 * so the check sees the value the utilities use. It is read at the startup event, because the overrides are applied by the provider
 * of that singleton. A tariff model of schema 4 or older carries no coefficient (its weights carry no price) and skips the check
 * with one info line.
 *
 * <p>This class exists only in the jar that implements the v3 mechanics of the tariff model schema 5, so the Python pre-run check
 * of the jar names it as the marker of a jar that prices a schema-5 model correctly.
 *
 * <p>Bound by ParkingModule as a controller listener, so it exists only when the braunschweigParking module is enabled. Side
 * effects: the info line and the exception; the tariff model and the parameters are only read.
 */
@Singleton
public final class ParkingOptionPriceBetaCheck implements StartupListener {
	private static final Logger LOG = LogManager.getLogger(ParkingOptionPriceBetaCheck.class);

	/** Largest accepted difference between the coefficient of the tariff model and the magnitude of the mode choice one. */
	static final double TOLERANCE = 1e-9;

	private final ParkingTariffs tariffs;
	private final ModeParameters modeParameters;
	private final String tariffsPath;

	/**
	 * @param tariffs the tariff model of the run: its option_price_beta_u_per_eur is the coefficient of the option weights
	 * @param parking the enabled module: its tariffsPath names the model in the message
	 * @param modeParameters the mode parameters of the run with every override applied: betaCost_u_MU is the coefficient of the
	 *        mode choice
	 */
	@Inject
	public ParkingOptionPriceBetaCheck(ParkingTariffs tariffs, ParkingConfigGroup parking, ModeParameters modeParameters) {
		this.tariffs = Objects.requireNonNull(tariffs, "tariffs");
		this.modeParameters = Objects.requireNonNull(modeParameters, "modeParameters");
		this.tariffsPath = parking.getTariffsPath();
	}

	/** Fails when the coefficient of the tariff model differs from the mode choice one; see the class comment. */
	@Override
	public void notifyStartup(StartupEvent event) {
		if (tariffs.schemaVersion() < ParkingTariffs.V3_MECHANICS_SCHEMA_VERSION) {
			LOG.info("[parking] option price coefficient check skipped: the tariff model " + tariffsPath + " has schema "
					+ tariffs.schemaVersion() + " and carries no option_price_beta_u_per_eur; its option weights carry no price"
					+ " (the weights of v2)");
			return;
		}
		double modeChoiceBeta = Math.abs(modeParameters.betaCost_u_MU);
		double modelBeta = tariffs.optionPriceBetaPerEuro();
		// Written so that NaN, which compares false with everything, also fails.
		if (!(Math.abs(modelBeta - modeChoiceBeta) <= TOLERANCE)) {
			throw new IllegalStateException("option_price_beta_u_per_eur of the parking tariff model " + tariffsPath + " ("
					+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath) is " + modelBeta + " but the magnitude of the mode choice"
					+ " cost coefficient betaCost_u_MU is " + modeChoiceBeta + " (difference above " + TOLERANCE + "): the"
					+ " choice between parking options uses ONE money coefficient, the one of the mode choice (ASSUMPTION"
					+ " G1-d); write the tariff model again with parking_option_price_beta_u_per_eur " + modeChoiceBeta
					+ ", or change betaCost_u_MU of the mode parameters to " + (-modelBeta));
		}
		LOG.info("[parking] option price coefficient " + modelBeta + " per EUR equals the magnitude of the mode choice cost"
				+ " coefficient betaCost_u_MU (" + modeChoiceBeta + ", tolerance " + TOLERANCE + ")");
	}
}
