package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;

/**
 * The classpath of this module, whose standalone jar runs the production simulations, holds one copy of the Guice classes
 * without an ASM of their own, and one ASM that reads the class files of this build. The parent pom replaces the default
 * Guice jar (see the comment at the Guice dependency there), and this module excludes the default jar from the MATSim
 * contribs it adds. A contrib added later brings the default jar, or another copy of ASM, back along its own dependency
 * path; the class loader then takes the first copy on the classpath, which Guice uses to read its source lines. The lookup
 * of the source lines itself is tested in the core module (GuiceSourceLinesTest).
 */
public class GuiceClasspathTest {
	@Test
	public void theClasspathHasOneCopyOfGuiceWithoutAnEmbeddedAsm() throws IOException {
		ClassLoader loader = GuiceClasspathTest.class.getClassLoader();

		List<URL> copies = Collections.list(loader.getResources("com/google/inject/Guice.class"));
		assertEquals("copies of the Guice classes on the classpath: " + copies, 1, copies.size());

		assertNull("the Guice on the classpath embeds its own ASM, which cannot read the class files of this build: "
				+ copies, loader.getResource("com/google/inject/internal/asm/$ClassReader.class"));
	}

	@Test
	public void theClasspathHasOneCopyOfTheAsmReader() throws IOException {
		ClassLoader loader = GuiceClasspathTest.class.getClassLoader();

		List<URL> copies = Collections.list(loader.getResources("org/objectweb/asm/ClassReader.class"));
		assertEquals("copies of the ASM class reader on the classpath: " + copies, 1, copies.size());
	}

	/**
	 * The ASM that Guice gets from the class loader reads the class file of this test class, which has the class file
	 * version of this build. The reader throws "Unsupported class file major version" when it is older than the build.
	 */
	@Test
	public void theAsmOnTheClasspathReadsTheClassFilesOfThisBuild() throws IOException {
		try (InputStream stream = GuiceClasspathTest.class.getResourceAsStream("GuiceClasspathTest.class")) {
			new ClassReader(stream);
		} catch (IllegalArgumentException e) {
			fail("the ASM at " + ClassReader.class.getProtectionDomain().getCodeSource().getLocation()
					+ " cannot read the class files of this build: " + e.getMessage());
		}
	}
}
