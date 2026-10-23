package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

/**
 * Persons of a site are split over its access groups in exact proportion to the groups' spaces, without a random
 * number generator, and the split must not depend on the order in which the population lists them (ADR-0169).
 */
public class AccessGroupAllocationTest {
	private static List<Id<Person>> persons(int count) {
		List<Id<Person>> persons = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			persons.add(Id.createPersonId("person_" + index));
		}
		return persons;
	}

	private static int[] counts(Map<Id<Person>, Integer> allocation, int groups) {
		int[] counts = new int[groups];
		allocation.values().forEach(group -> counts[group]++);
		return counts;
	}

	@Test
	public void countsFollowTheWeightsExactly() {
		Map<Id<Person>, Integer> allocation = AccessGroupAllocation.allocate("site", persons(10), new int[] { 5, 3, 2 });
		assertEquals(10, allocation.size());
		assertEquals(List.of(5, 3, 2), List.of(counts(allocation, 3)[0], counts(allocation, 3)[1], counts(allocation, 3)[2]));
	}

	@Test
	public void remaindersGoToTheLargestFractionThenTheLowerGroup() {
		// 7 x 1/3 = 2.33 each: floors 2, 2, 2, and the one remaining person goes to group 0.
		int[] counts = counts(AccessGroupAllocation.allocate("site", persons(7), new int[] { 1, 1, 1 }), 3);
		assertEquals(List.of(3, 2, 2), List.of(counts[0], counts[1], counts[2]));
		// 5 x 2/7 = 1.43, 5 x 5/7 = 3.57: floors 1 and 3, the remaining person goes to the larger fraction (group 1).
		counts = counts(AccessGroupAllocation.allocate("site", persons(5), new int[] { 2, 5 }), 2);
		assertEquals(List.of(1, 4), List.of(counts[0], counts[1]));
	}

	@Test
	public void theAllocationDoesNotDependOnTheInputOrder() {
		List<Id<Person>> ordered = persons(50);
		Map<Id<Person>, Integer> reference = AccessGroupAllocation.allocate("work_1", ordered, new int[] { 7, 2, 11 });
		List<Id<Person>> shuffled = new ArrayList<>(ordered);
		Collections.shuffle(shuffled, new Random(4));
		assertEquals(reference, AccessGroupAllocation.allocate("work_1", shuffled, new int[] { 7, 2, 11 }));
		assertEquals(reference, AccessGroupAllocation.allocate("work_1", ordered, new int[] { 7, 2, 11 }));
	}

	@Test
	public void theSiteKeyChangesWhichPersonsShareAGroup() {
		Map<Id<Person>, Integer> first = AccessGroupAllocation.allocate("site_a", persons(40), new int[] { 1, 1 });
		Map<Id<Person>, Integer> second = AccessGroupAllocation.allocate("site_b", persons(40), new int[] { 1, 1 });
		assertTrue("two sites must not give the same persons the same lots", !first.equals(second));
	}

	@Test
	public void aGroupWithoutSpacesGetsNobody() {
		int[] counts = counts(AccessGroupAllocation.allocate("site", persons(9), new int[] { 0, 4 }), 2);
		assertEquals(List.of(0, 9), List.of(counts[0], counts[1]));
	}

	@Test
	public void invalidWeightsAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> AccessGroupAllocation.allocate("site", persons(3), new int[] { 0, 0 }));
		assertThrows(IllegalArgumentException.class,
				() -> AccessGroupAllocation.allocate("site", persons(3), new int[] { 3, -1 }));
		assertEquals(Map.of(), AccessGroupAllocation.allocate("site", persons(0), new int[] { 1 }));
	}
}
