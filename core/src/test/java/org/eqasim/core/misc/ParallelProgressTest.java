package org.eqasim.core.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * The progress thread only logs: it must never keep the JVM alive. A caller that fails before
 * {@link ParallelProgress#close()} (eqasim-bs#678: a worker exception of the standalone mode choice) would otherwise
 * leave a non-daemon thread running until the reported count is reached, which never happens.
 */
public class ParallelProgressTest {
	/** The live threads currently executing {@code ParallelProgress.run}. */
	private static List<Thread> progressThreads() {
		return Thread.getAllStackTraces().entrySet().stream()
				.filter(entry -> entry.getKey().isAlive())
				.filter(entry -> Arrays.stream(entry.getValue())
						.anyMatch(frame -> frame.getClassName().equals(ParallelProgress.class.getName())))
				.map(Map.Entry::getKey).collect(Collectors.toList());
	}

	@Test(timeout = 60000)
	public void theProgressThreadIsADaemonEvenWhenCreatedByANonDaemonThread() throws Throwable {
		// A thread inherits the daemon flag of the thread that constructs it. JUnit's timeout thread is a daemon, the
		// JVM main thread of the runners is not, so construct and start the progress from a non-daemon thread.
		ParallelProgress[] progress = new ParallelProgress[1];
		Thread creator = new Thread(() -> {
			progress[0] = new ParallelProgress("Test progress", 10);
			progress[0].start();
		}, "parallel-progress-test-main");
		creator.setDaemon(false);
		creator.start();
		creator.join();

		try {
			// a just-started thread shows an empty stack until it is scheduled
			List<Thread> threads = progressThreads();
			for (int attempt = 0; attempt < 100 && threads.isEmpty(); attempt++) {
				Thread.sleep(50);
				threads = progressThreads();
			}
			assertEquals(1, threads.size());
			assertTrue(threads.get(0).isDaemon());
		} finally {
			progress[0].close();
		}
	}

	@Test(timeout = 60000)
	public void closeEndsTheProgressThreadBeforeTheCountIsReached() throws Throwable {
		ParallelProgress progress = new ParallelProgress("Test progress", 10);
		progress.start();
		progress.update(3);
		progress.close();

		assertEquals(List.of(), progressThreads());
	}
}
