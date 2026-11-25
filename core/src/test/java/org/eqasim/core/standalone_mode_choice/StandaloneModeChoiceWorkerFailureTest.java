package org.eqasim.core.standalone_mode_choice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eqasim.core.misc.ParallelProgress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.inject.Provider;

/**
 * A worker exception of the standalone mode choice must end the run: the exception reaches the caller and no
 * non-daemon thread (the {@link org.eqasim.core.misc.ParallelProgress} thread in particular) is left alive to keep
 * the JVM running. On eqasim-bs#678 a NullPointerException in a worker left the progress thread alive, so the JVM
 * never exited and the pipeline's hang watchdog never fired.
 */
public class StandaloneModeChoiceWorkerFailureTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	/** A model that fails for every person, like the freight NullPointerException of eqasim-bs#678. */
	private static final class FailingModel implements DiscreteModeChoiceModel {
		@Override
		public List<TripCandidate> chooseModes(Person person, List<DiscreteModeChoiceTrip> trips, Random random) {
			throw new NullPointerException("worker failure of " + person.getId());
		}
	}

	/**
	 * A model that interrupts its own thread and then fails: without a thread pool the worker runs on the caller's
	 * thread, so ParallelProgress.close() in the performer's finally block is interrupted while joining.
	 */
	private static final class InterruptingFailingModel implements DiscreteModeChoiceModel {
		@Override
		public List<TripCandidate> chooseModes(Person person, List<DiscreteModeChoiceTrip> trips, Random random) {
			Thread.currentThread().interrupt();
			throw new NullPointerException("worker failure of " + person.getId());
		}
	}

	/**
	 * The first choice of the whole run fails at once; every other choice takes {@link #SLOW_CHOICE_MS} unless its
	 * thread is interrupted, so a run that waits for all chunks before reporting the failure is easy to tell apart.
	 */
	private static final long SLOW_CHOICE_MS = 1000;

	private static Provider<DiscreteModeChoiceModel> firstFailsOthersSlowModel() {
		AtomicBoolean failed = new AtomicBoolean(false);
		return () -> (person, trips, random) -> {
			if (failed.compareAndSet(false, true)) {
				throw new NullPointerException("worker failure of " + person.getId());
			}
			try {
				Thread.sleep(SLOW_CHOICE_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			List<TripCandidate> candidates = new ArrayList<>();
			for (int index = 0; index < trips.size(); index++) {
				candidates.add(new DefaultTripCandidate(0.0, TransportMode.car, 0.0));
			}
			return candidates;
		};
	}

	private static Scenario scenario() {
		return scenario(4);
	}

	private static Scenario scenario(int persons) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Population population = scenario.getPopulation();
		PopulationFactory factory = population.getFactory();
		for (int index = 0; index < persons; index++) {
			Person person = factory.createPerson(Id.createPersonId("person" + index));
			Plan plan = factory.createPlan();
			Activity home = factory.createActivityFromCoord("home", new Coord(0.0, 0.0));
			home.setEndTime(8.0 * 3600.0);
			plan.addActivity(home);
			plan.addLeg(factory.createLeg(TransportMode.car));
			plan.addActivity(factory.createActivityFromCoord("work", new Coord(5000.0, 0.0)));
			person.addPlan(plan);
			population.addPerson(person);
		}
		return scenario;
	}

	private void perform(int threads) throws Exception {
		perform(threads, FailingModel::new);
	}

	private void perform(int threads, Provider<DiscreteModeChoiceModel> model) throws Exception {
		perform(threads, model, scenario());
	}

	private void perform(int threads, Provider<DiscreteModeChoiceModel> model, Scenario scenario) throws Exception {
		StandaloneModeChoiceConfigGroup configGroup = new StandaloneModeChoiceConfigGroup();
		configGroup.setChunkSize(1);
		String output = new File(temporaryFolder.getRoot(), "output_" + threads).getAbsolutePath();
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(output, null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		new StandaloneModeChoicePerformer(model, configGroup, scenario.getPopulation(), threads, 1L,
				hierarchy, scenario).run();
	}

	/**
	 * Runs {@link #perform(int)} in a non-daemon thread, like the JVM's main thread of the standalone runner, and
	 * rethrows its failure. A thread inherits the daemon flag of its creator, and JUnit's timeout runs the test method
	 * in a daemon thread, so a progress thread started directly from the test would be a daemon and hide the defect.
	 */
	private void performInNonDaemonThread(int threads) throws Throwable {
		performInNonDaemonThread(() -> perform(threads));
	}

	private interface Run {
		void run() throws Exception;
	}

	private void performInNonDaemonThread(Run run) throws Throwable {
		Throwable[] failure = new Throwable[1];
		Thread runner = new Thread(() -> {
			try {
				run.run();
			} catch (Throwable error) {
				failure[0] = error;
			}
		}, "standalone-mode-choice-test-main");
		runner.setDaemon(false);
		runner.start();
		runner.join();
		if (failure[0] != null) {
			throw failure[0];
		}
	}

	/**
	 * Returns the non-daemon threads started since {@code before} that are still alive after a short grace period
	 * (an executor's worker can still be leaving its run method when the executor reports termination).
	 */
	private static List<String> leftoverNonDaemonThreads(Set<Thread> before) throws InterruptedException {
		List<String> leftover = new ArrayList<>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (before.contains(thread) || thread.isDaemon()) {
				continue;
			}
			thread.join(2000);
			if (thread.isAlive()) {
				leftover.add(thread.getName());
			}
		}
		return leftover;
	}

	/**
	 * Returns the live threads started since {@code before} that still execute {@code ParallelProgress} code, daemon or
	 * not: the performer must close its progress on the failure path too.
	 */
	private static List<String> leftoverProgressThreads(Set<Thread> before) {
		List<String> leftover = new ArrayList<>();
		for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
			if (before.contains(entry.getKey()) || !entry.getKey().isAlive()) {
				continue;
			}
			if (Arrays.stream(entry.getValue())
					.anyMatch(frame -> frame.getClassName().equals(ParallelProgress.class.getName()))) {
				leftover.add(entry.getKey().getName());
			}
		}
		return leftover;
	}

	@Test(timeout = 60000)
	public void aWorkerFailureWithoutThreadPoolPropagatesAndLeavesNoThreadAlive() throws Exception {
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		NullPointerException error = assertThrows(NullPointerException.class, () -> performInNonDaemonThread(0));
		assertTrue(error.getMessage().startsWith("worker failure of"));
		assertEquals(List.of(), leftoverProgressThreads(before));
		assertEquals(List.of(), leftoverNonDaemonThreads(before));
	}

	@Test(timeout = 60000)
	public void aWorkerFailureInTheThreadPoolPropagatesAndLeavesNoThreadAlive() throws Exception {
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		ExecutionException error = assertThrows(ExecutionException.class, () -> performInNonDaemonThread(2));
		assertTrue(error.getCause() instanceof NullPointerException);
		assertEquals(List.of(), leftoverProgressThreads(before));
		assertEquals(List.of(), leftoverNonDaemonThreads(before));
	}

	@Test(timeout = 60000)
	public void anInterruptionWhileClosingTheProgressKeepsTheWorkerFailure() throws Throwable {
		Throwable[] failure = new Throwable[1];
		Thread runner = new Thread(() -> {
			try {
				perform(0, InterruptingFailingModel::new);
			} catch (Throwable error) {
				failure[0] = error;
			}
		}, "standalone-mode-choice-test-main");
		runner.setDaemon(false);
		runner.start();
		runner.join();

		assertTrue(String.valueOf(failure[0]), failure[0] instanceof NullPointerException);
		assertEquals(1, failure[0].getSuppressed().length);
		// the interruption is not lost: it travels with the worker's failure as a suppressed exception
		assertTrue(failure[0].getSuppressed()[0] instanceof InterruptedException);
	}

	@Test(timeout = 60000)
	public void aWorkerFailureInTheThreadPoolFailsBeforeTheRemainingChunksRun() throws Throwable {
		// 20 chunks of one person on 2 threads: waiting for all of them would take about 19 slow choices / 2 threads
		// = 9.5 s; failing fast takes at most the one slow choice already running on the other thread
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		long start = System.nanoTime();
		ExecutionException error = assertThrows(ExecutionException.class,
				() -> performInNonDaemonThread(() -> perform(2, firstFailsOthersSlowModel(), scenario(20))));
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof NullPointerException);
		assertTrue("failed after " + elapsedMs + " ms", elapsedMs < 4 * SLOW_CHOICE_MS);
		assertEquals(List.of(), leftoverProgressThreads(before));
		assertEquals(List.of(), leftoverNonDaemonThreads(before));
	}
}
