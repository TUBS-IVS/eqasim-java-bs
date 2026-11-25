package org.eqasim.braunschweig.mode_choice.constraints;

import java.util.Collection;
import java.util.List;

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
 * <li>A tour with a trip in a border tour: a candidate is valid exactly when, for every trip, the candidate mode
 * equals the initial mode wherever the initial mode or the candidate mode is a restricted (vehicle) mode. Vehicle legs
 * keep their mode, no other trip takes a vehicle mode, the remaining modes are free.</li>
 * </ul>
 *
 * <p>
 * A border tour is a home-based tour of the plan, cut after every trip that ends at an activity of type {@code home},
 * with at least one trip whose origin or destination activity is of type {@code outside}. Trips before the first home
 * activity form the first such tour.
 *
 * <p>
 * ASSUMPTION (spec section 5.2): keeping vehicle legs fixed gives up only tours that could drop the vehicle
 * altogether (park-and-ride-like tours); a tour that leaves the area by car keeps its car legs anyway. The initial
 * mode chain is always valid in a border tour, also when the chain itself is inconsistent, because the mode choice
 * drops a person whose initial chain is rejected (fallback behaviour EXCEPTION).
 *
 * <p>
 * "Initial mode" is {@link DiscreteModeChoiceTrip#getInitialMode()}, the mode of the trip in the plan being
 * replanned. The constraint has no fallback path and logs nothing: it is evaluated for every candidate of every tour.
 */
public class BorderTourVehicleConstraint implements TourConstraint {
	static final String HOME_ACTIVITY_TYPE = "home";
	static final String OUTSIDE_ACTIVITY_TYPE = "outside";

	private final Collection<String> restrictedModes;
	private final boolean[] borderTourFlags;
	private final TourConstraint delegate;

	BorderTourVehicleConstraint(Collection<String> restrictedModes, boolean[] borderTourFlags,
			TourConstraint delegate) {
		this.restrictedModes = restrictedModes;
		this.borderTourFlags = borderTourFlags;
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
			String initialMode = tour.get(index).getInitialMode();
			String mode = modes.get(index);

			if (restrictedModes.contains(initialMode) || restrictedModes.contains(mode)) {
				if (!mode.equals(initialMode)) {
					return false;
				}
			}
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

	@Override
	public boolean validateAfterEstimation(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
			List<TourCandidate> previousCandidates) {
		return true;
	}

	/**
	 * Builds the constraint per plan: the border tour flags from all trips of the plan and the stock
	 * {@link VehicleTourConstraint} at the home location as the delegate.
	 */
	public static class Factory implements TourConstraintFactory {
		private final Collection<String> restrictedModes;
		private final HomeFinder homeFinder;

		public Factory(Collection<String> restrictedModes, HomeFinder homeFinder) {
			this.restrictedModes = restrictedModes;
			this.homeFinder = homeFinder;
		}

		@Override
		public TourConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			return new BorderTourVehicleConstraint(restrictedModes, borderTourFlags(planTrips),
					new VehicleTourConstraint(restrictedModes, homeFinder.getHomeLocationId(planTrips)));
		}
	}
}
