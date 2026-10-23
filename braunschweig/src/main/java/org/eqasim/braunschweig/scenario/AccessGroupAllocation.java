package org.eqasim.braunschweig.scenario;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

/**
 * Splits the persons of a large site over its access groups in proportion to the groups' parking spaces (issue #465,
 * ADR-0165).
 *
 * <p>
 * The counts per group follow the largest-remainder method: group g gets floor(n w_g / W) persons, and the
 * remaining persons go one each to the groups with the largest remainders (ties: the lower group index), computed
 * in integer arithmetic. The persons are ordered by the CRC32 of {@code siteKey + "|" + personId} (ties: the person
 * id) and cut into consecutive blocks of those counts. The result is therefore exact in proportion, deterministic
 * without a random-number generator, independent of the order of the input list, and different between sites. No
 * side effects.
 * </p>
 */
public final class AccessGroupAllocation {
	private AccessGroupAllocation() {
	}

	/**
	 * @param siteKey key of the site (mixes the order between sites)
	 * @param persons persons with an activity at the site, each once
	 * @param weights non-negative weight (spaces) per group, at least one positive
	 * @return group index (0 .. weights.length - 1) per person
	 */
	public static Map<Id<Person>, Integer> allocate(String siteKey, List<Id<Person>> persons, int[] weights) {
		long total = 0;
		for (int weight : weights) {
			if (weight < 0) {
				throw new IllegalArgumentException("access-group weights must be non-negative, got " + weight);
			}
			total += weight;
		}
		if (total <= 0) {
			throw new IllegalArgumentException("access-group weights must contain a positive value");
		}
		int count = persons.size();
		int[] counts = new int[weights.length];
		long[] remainders = new long[weights.length];
		int assigned = 0;
		for (int group = 0; group < weights.length; group++) {
			long numerator = (long) count * weights[group];
			counts[group] = (int) (numerator / total);
			remainders[group] = numerator % total;
			assigned += counts[group];
		}
		List<Integer> byRemainder = new ArrayList<>();
		for (int group = 0; group < weights.length; group++) {
			byRemainder.add(group);
		}
		byRemainder.sort(Comparator.comparingLong((Integer group) -> -remainders[group]).thenComparingInt(group -> group));
		for (int index = 0; index < count - assigned; index++) {
			counts[byRemainder.get(index)]++;
		}

		List<Id<Person>> ordered = new ArrayList<>(persons);
		ordered.sort(Comparator.comparingLong((Id<Person> person) -> orderKey(siteKey, person))
				.thenComparing(Id::toString));
		Map<Id<Person>, Integer> allocation = new HashMap<>();
		int position = 0;
		for (int group = 0; group < weights.length; group++) {
			for (int member = 0; member < counts[group]; member++) {
				allocation.put(ordered.get(position++), group);
			}
		}
		return allocation;
	}

	private static long orderKey(String siteKey, Id<Person> person) {
		CRC32 crc = new CRC32();
		crc.update((siteKey + "|" + person).getBytes(StandardCharsets.UTF_8));
		return crc.getValue();
	}
}
