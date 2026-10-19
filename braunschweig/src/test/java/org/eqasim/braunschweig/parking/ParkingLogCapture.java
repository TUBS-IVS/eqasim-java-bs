package org.eqasim.braunschweig.parking;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Test helper: the log events one class writes while an action runs, with their level, so that a test can pin that a
 * problem is logged as a warning (and not as information) and that a run without the problem logs none.
 */
final class ParkingLogCapture {
	/** One captured log event. */
	record Event(Level level, String message) {
	}

	/** An action that may throw a checked exception, for example reading a file. */
	@FunctionalInterface
	interface CheckedAction {
		void run() throws Exception;
	}

	private ParkingLogCapture() {
	}

	/** The events the logger of {@code loggerClass} receives while {@code action} runs, in order. */
	static List<Event> capture(Class<?> loggerClass, Runnable action) {
		try {
			return captureChecked(loggerClass, action::run);
		} catch (RuntimeException | Error error) {
			throw error;
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	/** The events of {@link #capture} for an action that may throw a checked exception, which is passed on. */
	static List<Event> captureChecked(Class<?> loggerClass, CheckedAction action) throws Exception {
		Logger logger = (Logger) LogManager.getLogger(loggerClass);
		CollectingAppender appender = new CollectingAppender();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
		return appender.events;
	}

	/** The messages of the events of one level. */
	static List<String> messages(List<Event> events, Level level) {
		List<String> messages = new ArrayList<>();
		for (Event event : events) {
			if (event.level() == level) {
				messages.add(event.message());
			}
		}
		return messages;
	}

	private static class CollectingAppender extends AbstractAppender {
		private final List<Event> events = new ArrayList<>();

		CollectingAppender() {
			super("parking-log-capture-test", null, PatternLayout.createDefaultLayout(), false, null);
		}

		@Override
		public void append(LogEvent event) {
			events.add(new Event(event.getLevel(), event.getMessage().getFormattedMessage()));
		}
	}
}
