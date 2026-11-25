package org.eqasim.braunschweig.mode_choice.constraints;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.components.constraints.VehicleTourConstraint;
import org.matsim.contribs.discrete_mode_choice.components.utils.home_finder.HomeFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;

/**
 * Vehicle rule for border tours (eqasim-bs#678, design "mode choice reach", section 5.2).
 *
 * <p>
 * When the tour finder {@code IsolatedOutsideTrips} cuts a home-based tour that touches an {@code outside} activity
 * into pieces, the mode choice sees only the inside pieces while the trips to and from the border stay fixed. The
 * stock {@link VehicleTourConstraint} cannot judge such a piece: it does not see where the vehicle is during the fixed
 * trips. This constraint therefore decides in two ways:
 * <ul>
 * <li>A tour none of whose trips lies in a border tour: the stock {@link VehicleTourConstraint} decides, unchanged.</li>
 * <li>A tour with a trip in a border tour: a candidate is valid exactly when every vehicle leg keeps its initial mode
 * and no other trip takes a restricted (vehicle) mode. A vehicle leg is a trip whose initial mode is a restricted mode
 * that is available to the person; the remaining modes are free.</li>
 * </ul>
 *
 * <p>
 * A border tour is a home-based tour of the plan, cut after every trip that ends at an activity of type {@code home},
 * with at least one trip whose origin or destination activity is of type {@code outside}. Trips before the first home
 * activity form the first such tour. The activity types are fixed: the rule assumes the tour finder works on the
 * activity types {@code [home, outside]} (the Braunschweig setting of {@code IsolatedOutsideTrips}).
 *
 * <p>
 * ASSUMPTION (spec section 5.2): keeping vehicle legs fixed gives up only tours that could drop the vehicle
 * altogether (park-and-ride-like tours); a tour that leaves the area by car keeps its car legs anyway. When every
 * vehicle leg's mode is available to the person, the initial mode chain is always valid in a border tour, also when
 * the chain itself is inconsistent, because the mode choice drops a person whose initial chain is rejected (fallback
 * behaviour EXCEPTION). Persons with relaxed legs (see below) are the exception; the chain generator never offers
 * their initial chain anyway.
 *
 * <p>
 * Vehicle legs without availability: the mode choice only builds chains from the person's available modes, and
 * {@code BraunschweigModeAvailability} offers {@code car} only with car availability and a licence and
 * {@code bicycle} only with bicycle availability. A diary leg in a vehicle mode the person does not have could never
 * be kept, so every chain would be rejected and the person would fail. Such a leg is therefore treated like a
 * non-vehicle leg of the border tour: free among the non-restricted modes, never a vehicle mode. The factory counts
 * these relaxed legs against all vehicle legs of border tours and logs the rate (no silent fallback).
 *
 * <p>
 * "Initial mode" is {@link DiscreteModeChoiceTrip#getInitialMode()}, the mode of the trip in the plan being
 * replanned.
 */
public class BorderTourVehicleConstraint implements TourConstraint {
	static final String HOME_ACTIVITY_TYPE = "home";
	static final String OUTSIDE_ACTIVITY_TYPE = "outside";

	private final Collection<String> restrictedModes;
	private final boolean[] borderTourFlags;
	private final boolean[] fixedVehicleLegs;
	private final TourConstraint delegate;

	BorderTourVehicleConstraint(Collection<String> restrictedModes, boolean[] borderTourFlags,
			boolean[] fixedVehicleLegs, TourConstraint delegate) {
		this.restrictedModes = restrictedModes;
		this.borderTourFlags = borderTourFlags;
		this.fixedVehicleLegs = fixedVehicleLegs;
		this.delegate = delegate;
	}

	/**
	 * Marks, for every trip of the plan (by position), whether its home-based tour touches an {@code outside}
	 * activity.
	 */
	public static boolean[] borderTourFlags(List<DiscreteModeChoiceTrip> planTrips) {
		boolean[] flags = new boolean[planTrips.size()];

		int tourStart = 0;
		boolean touchesOutside = false;

		for (int index = 0; index < planTrips.size(); index++) {
			DiscreteModeChoiceTrip trip = planTrips.get(index);
			touchesOutside |= isOutside(trip.getOriginActivity().getType())
					|| isOutside(trip.getDestinationActivity().getType());

			boolean tourEnds = HOME_ACTIVITY_TYPE.equals(trip.getDestinationActivity().getType())
					|| index == planTrips.size() - 1;

			if (tourEnds) {
				for (int member = tourStart; member <= index; member++) {
					flags[member] = touchesOutside;
				}

				tourStart = index + 1;
				touchesOutside = false;
			}
		}

		return flags;
	}

	private static boolean isOutside(String activityType) {
		return OUTSIDE_ACTIVITY_TYPE.equals(activityType);
	}

	@Override
	public boolean validateBeforeEstimation(List<DiscreteModeChoiceTrip> tour, List<String> modes,
			List<List<String>> previousModes) {
		if (!isInBorderTour(tour)) {
			return delegate.validateBeforeEstimation(tour, modes, previousModes);
		}

		for (int index = 0; index < tour.size(); index++) {
			DiscreteModeChoiceTrip trip = tour.get(index);
			String mode = modes.get(index);

			if (fixedVehicleLegs[trip.getIndex()]) {
				if (!mode.equals(trip.getInitialMode())) {
					return false; // a vehicle leg changed its mode
				}
			} else if (restrictedModes.contains(mode)) {
				return false; // a new vehicle leg
			}
		}

		return true;
	}

	@Override
	public boolean validateAfterEstimation(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
			List<TourCandidate> previousCandidates) {
		if (!isInBorderTour(tour)) {
			return delegate.validateAfterEstimation(tour, candidate, previousCandidates);
		}

		return true;
	}

	private boolean isInBorderTour(List<DiscreteModeChoiceTrip> tour) {
		for (DiscreteModeChoiceTrip trip : tour) {
			if (borderTourFlags[trip.getIndex()]) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Builds the constraint per plan: the border tour flags and the fixed vehicle legs from all trips of the plan and
	 * the stock {@link VehicleTourConstraint} at the home location as the delegate. Bound as a singleton; the counters
	 * are thread-safe and count per constraint creation (one per replanned plan), not per distinct person.
	 */
	public static class Factory implements TourConstraintFactory {
		private static final Logger logger = LogManager.getLogger(BorderTourVehicleConstraint.class);
		private static final int MAXIMUM_PERSON_WARNINGS = 10;

		private final Collection<String> restrictedModes;
		private final HomeFinder homeFinder;

		private final AtomicLong borderTourVehicleTrips = new AtomicLong();
		private final AtomicLong relaxedVehicleTrips = new AtomicLong();
		private final AtomicInteger warnedPersons = new AtomicInteger();

		public Factory(Collection<String> restrictedModes, HomeFinder homeFinder) {
			this.restrictedModes = restrictedModes;
			this.homeFinder = homeFinder;
		}

		@Override
		public TourConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			boolean[] borderTourFlags = borderTourFlags(planTrips);
			boolean[] fixedVehicleLegs = new boolean[planTrips.size()];

			int vehicleTrips = 0;
			int relaxedTrips = 0;

			for (int index = 0; index < planTrips.size(); index++) {
				String initialMode = planTrips.get(index).getInitialMode();

				if (borderTourFlags[index] && restrictedModes.contains(initialMode)) {
					vehicleTrips++;

					if (availableModes.contains(initialMode)) {
						fixedVehicleLegs[index] = true;
					} else {
						relaxedTrips++;
					}
				}
			}

			record(person, vehicleTrips, relaxedTrips);

			return new BorderTourVehicleConstraint(restrictedModes, borderTourFlags, fixedVehicleLegs,
					new VehicleTourConstraint(restrictedModes, homeFinder.getHomeLocationId(planTrips)));
		}

		private void record(Person person, int vehicleTrips, int relaxedTrips) {
			// Every thread adds its vehicle legs before its relaxed legs (relaxed <= vehicle per call). Reading the
			// vehicle total after the relaxed update therefore covers every relaxed leg counted so far, so the summary
			// never shows more relaxed than vehicle legs under concurrent replanning.
			borderTourVehicleTrips.addAndGet(vehicleTrips);

			if (relaxedTrips == 0) {
				return;
			}

			long previousRelaxed = relaxedVehicleTrips.getAndAdd(relaxedTrips);
			long totalRelaxed = previousRelaxed + relaxedTrips;
			long totalVehicle = borderTourVehicleTrips.get();

			if (warnedPersons.getAndIncrement() < MAXIMUM_PERSON_WARNINGS) {
				logger.warn(String.format(Locale.ROOT,
						"Person %s has %d border-tour vehicle leg(s) in a mode that is not available to them; "
								+ "they are treated as free non-vehicle legs",
						person.getId(), relaxedTrips));
			}

			// Summary whenever the relaxed count reaches (or passes) a power of two.
			if (Long.highestOneBit(totalRelaxed) > previousRelaxed) {
				logger.warn(String.format(Locale.ROOT,
						"border-tour vehicle legs relaxed for missing availability: %d of %d (%.2f %%)", totalRelaxed,
						totalVehicle, 100.0 * totalRelaxed / totalVehicle));
			}
		}

		/** Vehicle legs (initial mode restricted) in border tours seen so far, over all created constraints. */
		public long getBorderTourVehicleTrips() {
			return borderTourVehicleTrips.get();
		}

		/** Of those, the legs treated as free because the vehicle mode is not available to the person. */
		public long getRelaxedVehicleTrips() {
			return relaxedVehicleTrips.get();
		}
	}
}
