package org.eqasim.core.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * The progress thread only logs: it must never keep the JVM alive. A caller that fails before
 * {@link ParallelProgress#close()} (eqasim-bs#678: a worker exception of the standalone mode choice) would otherwise
 * leave a non-daemon thread running until the reported count is reached, which never happens.
 */
public class ParallelProgressTest {
	/**
	 * The live progress threads of this test: started after {@code before} and named after the test's own unique
	 * description. Other test classes in the reused surefire fork may have left progress threads of their own.
	 */
	private static List<Thread> progressThreads(Set<Thread> before, String description) {
		String name = "ParallelProgress: " + description;
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> !before.contains(thread) && thread.isAlive() && thread.getName().equals(name))
				.collect(Collectors.toList());
	}

	@Test(timeout = 60000)
	public void theProgressThreadIsADaemonEvenWhenCreatedByANonDaemonThread() throws Throwable {
		// A thread inherits the daemon flag of the thread that constructs it. JUnit's timeout thread is a daemon, the
		// JVM main thread of the runners is not, so construct and start the progress from a non-daemon thread.
		String description = "ParallelProgressTest daemon";
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		ParallelProgress[] progress = new ParallelProgress[1];
		Thread creator = new Thread(() -> {
			progress[0] = new ParallelProgress(description, 10);
			progress[0].start();
		}, "parallel-progress-test-main");
		creator.setDaemon(false);
		creator.start();
		creator.join();

		try {
			List<Thread> threads = progressThreads(before, description);
			assertEquals(1, threads.size());
			assertTrue(threads.get(0).isDaemon());
		} finally {
			progress[0].close();
		}
	}

	@Test(timeout = 60000)
	public void closeEndsTheProgressThreadBeforeTheCountIsReached() throws Throwable {
		String description = "ParallelProgressTest close";
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		ParallelProgress progress = new ParallelProgress(description, 10);
		progress.start();
		progress.update(3);
		assertEquals(1, progressThreads(before, description).size());
		progress.close();

		assertEquals(List.of(), progressThreads(before, description));
	}
}
