package org.eqasim.core.scenario.cutter.transit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Resolves the names of hub stations to the stop facilities of a transit schedule. A hub is an anchor of
 * {@link TransitScheduleCutter}: routes are kept up to it even where it lies beyond the extent.
 */
public final class TransitHubStops {
	private final static Logger log = LogManager.getLogger(TransitHubStops.class);

	private TransitHubStops() {
	}

	/**
	 * Reads hub stop names from a UTF-8 text file with one exact stop name per line. Names are trimmed, blank
	 * lines are dropped. A file is used rather than a command-line list so that umlauts survive the Windows code
	 * page.
	 */
	public static List<String> readNames(Path file) throws IOException {
		List<String> names = new ArrayList<>();

		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String name = line.trim();

			if (!name.isEmpty()) {
				names.add(name);
			}
		}

		return names;
	}

	/**
	 * Resolves every name to all stop facilities with exactly that name (case-sensitive, a facility without
	 * a name never matches). Fails with the list of all names that match no facility. Warns for every hub
	 * facility inside the extent (there it is an anchor anyway, so a hub there has no effect) and logs, per
	 * name, the matched facility ids and coordinates.
	 */
	public static Set<Id<TransitStopFacility>> resolve(TransitSchedule schedule, List<String> names,
			ScenarioExtent extent) {
		Set<Id<TransitStopFacility>> hubStopIds = new LinkedHashSet<>();
		List<String> unmatched = new ArrayList<>();

		for (String name : names) {
			List<TransitStopFacility> matches = schedule.getFacilities().values().stream()
					.filter(facility -> name.equals(facility.getName())) //
					.sorted((a, b) -> a.getId().toString().compareTo(b.getId().toString())) //
					.collect(Collectors.toList());

			if (matches.isEmpty()) {
				unmatched.add(name);
				continue;
			}

			log.info(String.format(Locale.ROOT, "[TransitHubStops] hub '%s': %d stop facilities: %s", name,
					matches.size(), matches.stream().map(f -> String.format(Locale.ROOT, "%s (%.1f, %.1f)",
							f.getId(), f.getCoord().getX(), f.getCoord().getY())).collect(Collectors.joining(", "))));

			for (TransitStopFacility facility : matches) {
				hubStopIds.add(facility.getId());

				if (extent.isInside(facility.getCoord())) {
					log.warn(String.format(Locale.ROOT,
							"[TransitHubStops] hub '%s' facility %s lies inside the extent; it is an anchor there anyway",
							name, facility.getId()));
				}
			}
		}

		if (!unmatched.isEmpty()) {
			throw new IllegalArgumentException(
					"Hub stop names without a stop facility of exactly that name: " + String.join("; ", unmatched));
		}

		return hubStopIds;
	}
}
