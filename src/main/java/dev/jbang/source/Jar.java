package dev.jbang.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.annotation.Nonnull;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import dev.jbang.dependencies.DependencyResolver;
import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.dependencies.MavenCoordinate;
import dev.jbang.source.builders.BaseBuilder;
import dev.jbang.source.generators.JarCmdGenerator;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.Util;

/**
 * A Jar represents a Source (something runnable) in the form of a JAR file.
 * It's a reference to an already existing JAR file, either as a solitary file
 * on the user's file system or accessible via a URL. But it can also be a Maven
 * GAV (group:artifact:version) reference resolving to a JAR file in the Maven
 * cache (~/.m2/repository).
 *
 * NB: The Jar contains/returns no other information than that which can be
 * extracted from the JAR file itself. So all Jars that refer to the same JAR
 * file will contain/return the exact same information.
 */
public class Jar implements Code {
	private final ResourceRef resourceRef;
	private final Path jarFile;

	// Values read from MANIFEST
	private String classPath;
	private String mainClass;
	private List<String> javaRuntimeOptions;
	private String gav;
	private int buildJdk;

	// Cached values
	private Project project;

	private Jar(ResourceRef resourceRef, Path jar) {
		this.resourceRef = resourceRef;
		this.jarFile = jar;
		this.javaRuntimeOptions = Collections.emptyList();
		if (Files.exists(jar)) {
			try (JarFile jf = new JarFile(jar.toFile())) {
				Attributes attrs = jf.getManifest().getMainAttributes();
				mainClass = attrs.getValue(Attributes.Name.MAIN_CLASS);

				Optional<JarEntry> pom = jf.stream().filter(e -> e.getName().endsWith("/pom.xml")).findFirst();
				if (pom.isPresent()) {
					try (InputStream is = jf.getInputStream(pom.get())) {
						MavenXpp3Reader reader = new MavenXpp3Reader();
						Model model = reader.read(is);
						// GAVS of the form "group:xxxx:999-SNAPSHOT" are skipped
						if (!MavenCoordinate.DUMMY_GROUP.equals(model.getGroupId())
								|| !MavenCoordinate.DEFAULT_VERSION.equals(model.getVersion())) {
							gav = model.getGroupId() + ":" + model.getArtifactId();
							// The version "999-SNAPSHOT" is ignored
							if (!MavenCoordinate.DEFAULT_VERSION.equals(model.getVersion())) {
								gav += ":" + model.getVersion();
							}
						}
					} catch (XmlPullParserException e) {
						Util.verboseMsg("Unable to read the JAR's pom.xml file", e);
					}
				}

				String val = attrs.getValue(BaseBuilder.ATTR_JBANG_JAVA_OPTIONS);
				if (val != null) {
					javaRuntimeOptions = Code.quotedStringToList(val);
				}

				String ver = attrs.getValue(BaseBuilder.ATTR_BUILD_JDK);
				if (ver != null) {
					buildJdk = JavaUtil.parseJavaVersion(ver);
				}

				classPath = attrs.getValue(Attributes.Name.CLASS_PATH);
			} catch (IOException e) {
				Util.warnMsg("Problem reading manifest from " + getResourceRef().getFile());
			}
		}
	}

	@Override
	public ResourceRef getResourceRef() {
		return resourceRef;
	}

	@Override
	public Path getJarFile() {
		return jarFile;
	}

	@Override
	public Jar asJar() {
		return this;
	}

	@Override
	public Project asProject() {
		if (project == null) {
			project = new Project(getResourceRef());
			project.setDescription(getDescription().orElse(null));
			project.setGav(getGav().orElse(null));
			project.setMainClass(getMainClass());
			project.addRuntimeOptions(getRuntimeOptions());
			project.setJavaVersion(getJavaVersion());
			// TODO deduplicate with code from updateDependencyResolver()
			if (resourceRef.getOriginalResource() != null
					&& DependencyUtil.looksLikeAGav(resourceRef.getOriginalResource())) {
				project.getMainSourceSet().addDependency(resourceRef.getOriginalResource());
			} else if (classPath != null) {
				project.getMainSourceSet().addClassPaths(Arrays.asList(classPath.split(" ")));
			}
		}
		return project;
	}

	/**
	 * Determines if the associated jar is up-to-date, returns false if it needs to
	 * be rebuilt
	 */
	public boolean isUpToDate() {
		return jarFile != null && Files.exists(jarFile)
				&& updateDependencyResolver(new DependencyResolver()).resolve().isValid();
	}

	@Override
	public DependencyResolver updateDependencyResolver(DependencyResolver resolver) {
		if (resourceRef.getOriginalResource() != null
				&& DependencyUtil.looksLikeAGav(resourceRef.getOriginalResource())) {
			resolver.addDependency(resourceRef.getOriginalResource());
		} else if (classPath != null) {
			resolver.addClassPaths(Arrays.asList(classPath.split(" ")));
		}
		return resolver;
	}

	@Override
	public String getJavaVersion() {
		return buildJdk > 0 ? buildJdk + "+" : null;
	}

	@Nonnull
	@Override
	public Optional<String> getGav() {
		return Optional.ofNullable(gav);
	}

	/**
	 * Returns the actual Java version that was used to build this Jar. Will return
	 * 0 if the information is not available (for example because the Jar hasn't
	 * been built yet).
	 *
	 * @return The Java version that was used to build this code or 0
	 */
	public int getBuildJdk() {
		return buildJdk;
	}

	@Override
	public String getMainClass() {
		return mainClass;
	}

	@Nonnull
	@Override
	public List<String> getRuntimeOptions() {
		return javaRuntimeOptions;
	}

	@Nonnull
	@Override
	public Builder builder() {
		return () -> this;
	}

	@Nonnull
	@Override
	public CmdGenerator cmdGenerator(RunContext ctx) {
		return new JarCmdGenerator(this, ctx);
	}

	public static Jar prepareJar(ResourceRef resourceRef) {
		return new Jar(resourceRef, resourceRef.getFile());
	}

	public static Jar prepareJar(ResourceRef resourceRef, Path jarFile) {
		return new Jar(resourceRef, jarFile);
	}

	public static Jar prepareJar(Project prj) {
		Jar jsrc = new Jar(prj.getResourceRef(), prj.getJarFile());
		jsrc.project = prj;
		if (!Files.exists(prj.getJarFile())) {
			jsrc.classPath = prj.resolveClassPath().getManifestPath();
			jsrc.mainClass = prj.getMainClass();
			jsrc.javaRuntimeOptions = prj.getRuntimeOptions();
			jsrc.gav = prj.getGav().orElse(null);
			jsrc.buildJdk = JavaUtil.javaVersion(prj.getJavaVersion());
		}
		return jsrc;
	}
}
