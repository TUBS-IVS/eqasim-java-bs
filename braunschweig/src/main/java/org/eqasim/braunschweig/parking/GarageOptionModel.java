package org.eqasim.braunschweig.parking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The garage options of a destination (spec Amendment E2 of parking cost zones v2, eqasim-bs issue #436): the priced
 * garages of the tariff model, the decay length lambda ({@code garage_decay_m}, ASSUMPTION G1) and the maximum distance
 * D_max ({@code garage_max_distance_m}, ASSUMPTION G2), with a grid index so that the options of one destination are found
 * without scanning every garage of the region. Immutable and thread-safe: mode choice prices car alternatives in parallel
 * replanning threads and shares one instance (built once by {@link ParkingTariffs}).
 *
 * <p>The model is <em>active</em> when lambda is above 0 and the model lists at least one garage. Lambda 0 switches the
 * garage options off (the sensitivity arm of spec E6): {@link #isActive()} is false and no option code runs. A model with
 * lambda above 0 and no garage is refused ({@link ParkingTariffs} fails at read time), never silently inactive.
 *
 * <p>Distances are straight lines in the network CRS (EPSG:25832) computed as {@code Math.sqrt(dx * dx + dy * dy)}, never
 * {@code Math.hypot}, exactly as the Python reference {@code garage_options_in_range}, so both implementations compute the
 * same double and agree on the inclusive D_max test. Index: every garage is binned into a square cell of side 2 * D_max by
 * its own coordinates; the options of a destination lie in the cells that the square of half side D_max around it touches
 * (at most 2 x 2 cells, looked up by binary search in a sorted array of primitive cell keys, so a lookup allocates nothing
 * but its result), which are scanned and filtered by the exact distance. The result is ordered by ascending garage id, the
 * order of the sums of the expected cost.
 */
public final class GarageOptionModel {
	/** One garage option of a destination: the garage and its straight-line distance in metres. */
	public record Option(GarageTariff garage, double distanceMeters) {
	}

	private static final Option[] NO_OPTIONS = new Option[0];

	private final List<GarageTariff> garages;
	private final double decayMeters;
	private final double maximumDistanceMeters;
	/** Margin in metres added around the search square, far above the rounding of a UTM coordinate and far below any tariff. */
	private static final double SEARCH_MARGIN_M = 1e-6;

	private final double cellSizeMeters;
	private final long[] cellKeys;
	private final int[][] garageOrdinalsByCell;

	/**
	 * @param garages the priced garages; copied and ordered by ascending id
	 * @param decayMeters decay length lambda in metres, finite and at least 0 (0: garage options off)
	 * @param maximumDistanceMeters D_max in metres, finite and above 0 (inclusive)
	 * @throws IllegalArgumentException for an invalid parameter, a duplicate garage id, or lambda above 0 without a garage
	 */
	public GarageOptionModel(List<GarageTariff> garages, double decayMeters, double maximumDistanceMeters) {
		Objects.requireNonNull(garages, "garages");
		if (!Double.isFinite(decayMeters) || decayMeters < 0.0) {
			throw new IllegalArgumentException("garage_decay_m must be a finite number of metres >= 0 (0 switches the garage"
					+ " options off), got " + decayMeters);
		}
		if (!Double.isFinite(maximumDistanceMeters) || maximumDistanceMeters <= 0.0) {
			throw new IllegalArgumentException("garage_max_distance_m must be a finite number of metres > 0, got "
					+ maximumDistanceMeters);
		}
		List<GarageTariff> ordered = new ArrayList<>(garages);
		ordered.sort(Comparator.comparing(GarageTariff::garageId));
		Set<String> ids = new HashSet<>();
		for (GarageTariff garage : ordered) {
			if (!ids.add(garage.garageId())) {
				throw new IllegalArgumentException("duplicate garage_id " + garage.garageId() + " among the garages");
			}
		}
		if (decayMeters > 0.0 && ordered.isEmpty()) {
			throw new IllegalArgumentException("garage_decay_m is " + decayMeters + " (the garage options are on) but the"
					+ " model lists no priced garage: set garage_decay_m to 0 or give the garage dataset");
		}
		this.garages = List.copyOf(ordered);
		this.decayMeters = decayMeters;
		this.maximumDistanceMeters = maximumDistanceMeters;
		this.cellSizeMeters = 2.0 * maximumDistanceMeters;
		TreeMap<Long, List<Integer>> byCell = new TreeMap<>();
		for (int ordinal = 0; ordinal < this.garages.size(); ordinal++) {
			GarageTariff garage = this.garages.get(ordinal);
			byCell.computeIfAbsent(cellKey(cellIndex(garage.x_m()), cellIndex(garage.y_m())), key -> new ArrayList<>())
					.add(ordinal);
		}
		this.cellKeys = new long[byCell.size()];
		this.garageOrdinalsByCell = new int[byCell.size()][];
		int position = 0;
		for (Map.Entry<Long, List<Integer>> cell : byCell.entrySet()) {
			cellKeys[position] = cell.getKey();
			garageOrdinalsByCell[position] = cell.getValue().stream().mapToInt(Integer::intValue).toArray();
			position++;
		}
	}

	/** Whether garage options act: lambda above 0 (and then at least one garage, which the constructor guarantees). */
	public boolean isActive() {
		return decayMeters > 0.0;
	}

	/** The priced garages, ordered by ascending id; unmodifiable. */
	public List<GarageTariff> garages() {
		return garages;
	}

	/** The decay length lambda in metres (ASSUMPTION G1); 0 means the garage options are off. */
	public double decayMeters() {
		return decayMeters;
	}

	/** The maximum straight-line distance D_max in metres, inclusive (ASSUMPTION G2). */
	public double maximumDistanceMeters() {
		return maximumDistanceMeters;
	}

	/**
	 * The garage options of the destination {@code (x_m, y_m)} in EPSG:25832: every garage within D_max (inclusive) with
	 * its distance, ordered by ascending garage id; an empty array (shared, do not modify) when none is in range.
	 *
	 * @throws IllegalArgumentException for a non-finite coordinate
	 */
	public Option[] optionsWithin(double x_m, double y_m) {
		if (!Double.isFinite(x_m) || !Double.isFinite(y_m)) {
			throw new IllegalArgumentException("the destination coordinates must be finite metres (EPSG:25832), got x_m=" + x_m
					+ ", y_m=" + y_m);
		}
		long firstCellX = cellIndex(x_m - maximumDistanceMeters - SEARCH_MARGIN_M);
		long lastCellX = cellIndex(x_m + maximumDistanceMeters + SEARCH_MARGIN_M);
		long firstCellY = cellIndex(y_m - maximumDistanceMeters - SEARCH_MARGIN_M);
		long lastCellY = cellIndex(y_m + maximumDistanceMeters + SEARCH_MARGIN_M);
		Option[] found = NO_OPTIONS;
		int[] foundOrdinals = null;
		int count = 0;
		for (long cellX = firstCellX; cellX <= lastCellX; cellX++) {
			for (long cellY = firstCellY; cellY <= lastCellY; cellY++) {
				int cell = Arrays.binarySearch(cellKeys, cellKey(cellX, cellY));
				if (cell < 0) {
					continue;
				}
				int[] ordinals = garageOrdinalsByCell[cell];
				for (int ordinal : ordinals) {
					GarageTariff garage = garages.get(ordinal);
					double deltaX = garage.x_m() - x_m;
					double deltaY = garage.y_m() - y_m;
					double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
					if (distance <= maximumDistanceMeters) {
						if (count == 0) {
							found = new Option[4];
							foundOrdinals = new int[4];
						} else if (count == found.length) {
							found = Arrays.copyOf(found, count * 2);
							foundOrdinals = Arrays.copyOf(foundOrdinals, count * 2);
						}
						found[count] = new Option(garage, distance);
						foundOrdinals[count] = ordinal;
						count++;
					}
				}
			}
		}
		if (count <= 1) {
			return count == 0 ? NO_OPTIONS : new Option[] { found[0] };
		}
		// The cells are scanned in cell order, so restore the ascending garage id (= ordinal) order by insertion sort
		// (a destination has a handful of options at most).
		for (int position = 1; position < count; position++) {
			Option option = found[position];
			int ordinal = foundOrdinals[position];
			int target = position - 1;
			while (target >= 0 && foundOrdinals[target] > ordinal) {
				found[target + 1] = found[target];
				foundOrdinals[target + 1] = foundOrdinals[target];
				target--;
			}
			found[target + 1] = option;
			foundOrdinals[target + 1] = ordinal;
		}
		return Arrays.copyOf(found, count);
	}

	private long cellIndex(double coordinate_m) {
		return (long) Math.floor(coordinate_m / cellSizeMeters);
	}

	private static long cellKey(long cellX, long cellY) {
		return (cellX << 32) ^ (cellY & 0xFFFFFFFFL);
	}
}
