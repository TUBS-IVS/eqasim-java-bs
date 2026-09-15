package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

public class RunAdaptPassengerAvailabilityConfigTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void capabilityEntrypointProducesLegacyAdaptationConfiguration() throws Exception {
		Path inputPath = temporaryFolder.getRoot().toPath().resolve("input.xml");
		Path legacyOutputPath = temporaryFolder.getRoot().toPath().resolve("legacy-output.xml");
		Path capabilityOutputPath = temporaryFolder.getRoot().toPath().resolve("capability-output.xml");
		new ConfigWriter(ConfigUtils.createConfig()).write(inputPath.toString());

		RunAdaptConfig.main(arguments(inputPath, legacyOutputPath));
		RunAdaptPassengerAvailabilityConfig.main(arguments(inputPath, capabilityOutputPath));

		assertArrayEquals(Files.readAllBytes(legacyOutputPath), Files.readAllBytes(capabilityOutputPath));
		Config adaptedConfig = ConfigUtils.loadConfig(capabilityOutputPath.toString(),
				new DiscreteModeChoiceConfigGroup());
		DiscreteModeChoiceConfigGroup dmcConfig = (DiscreteModeChoiceConfigGroup) adaptedConfig.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		assertFalse(dmcConfig.getTripConstraints().contains(EqasimModeChoiceModule.PASSENGER_CONSTRAINT_NAME));
	}

	private String[] arguments(Path inputPath, Path outputPath) {
		return new String[] {
				"--input-path", inputPath.toString(),
				"--output-path", outputPath.toString(),
				"--prefix", "scenario-"
		};
	}
}
