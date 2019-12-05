/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.java;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.scala.FlinkILoop;
import org.apache.flink.configuration.ConfigUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.util.JarUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Special version of {@link org.apache.flink.api.java.ExecutionEnvironment} that has a reference
 * to a {@link org.apache.flink.api.scala.FlinkILoop}. When execute is called this will
 * use the reference of the ILoop to write the compiled classes of the current session to
 * a Jar file and submit these with the program.
 */
@Internal
public class ScalaShellEnvironment extends ExecutionEnvironment {

	/** The jar files that need to be attached to each job. */
	private final List<URL> jarFiles;

	/** reference to Scala Shell, for access to virtual directory. */
	private final FlinkILoop flinkILoop;

	public ScalaShellEnvironment(
			final Configuration configuration,
			final FlinkILoop flinkILoop,
			final String... jarFiles) {

		super(validateAndGetConfiguration(configuration));
		this.flinkILoop = checkNotNull(flinkILoop);
		this.jarFiles = checkNotNull(getJarFiles(jarFiles));
	}

	private static Configuration validateAndGetConfiguration(final Configuration configuration) {
		if (!ExecutionEnvironment.areExplicitEnvironmentsAllowed()) {
			throw new InvalidProgramException(
					"The RemoteEnvironment cannot be instantiated when running in a pre-defined context " +
							"(such as Command Line Client, Scala Shell, or TestEnvironment)");
		}
		return checkNotNull(configuration);
	}

	private static List<URL> getJarFiles(final String[] jars) {
		return jars == null
				? Collections.emptyList()
				: Arrays.stream(jars).map(jarPath -> {
			try {
				final URL fileURL = new File(jarPath).getAbsoluteFile().toURI().toURL();
				JarUtils.checkJarFile(fileURL);
				return fileURL;
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException("JAR file path invalid", e);
			} catch (IOException e) {
				throw new RuntimeException("Problem with jar file " + jarPath, e);
			}
		}).collect(Collectors.toList());
	}

	@Override
	public JobExecutionResult execute(String jobName) throws Exception {
		final Configuration configuration = getConfiguration();
		checkState(configuration.getBoolean(DeploymentOptions.ATTACHED), "Only ATTACHED mode is supported by the scala shell.");

		final List<URL> updatedJarFiles = getUpdatedJarFiles();
		ConfigUtils.encodeCollectionToConfig(configuration, PipelineOptions.JARS, updatedJarFiles, URL::toString);

		return super.execute(jobName);
	}

	private List<URL> getUpdatedJarFiles() throws MalformedURLException {
		final URL jarUrl = flinkILoop.writeFilesToDisk().getAbsoluteFile().toURI().toURL();
		final List<URL> allJarFiles = new ArrayList<>(jarFiles);
		allJarFiles.add(jarUrl);
		return allJarFiles;
	}

	public static void disableAllContextAndOtherEnvironments() {
		initializeContextEnvironment(() -> {
			throw new UnsupportedOperationException("Execution Environment is already defined for this shell.");
		});
	}

	public static void resetContextEnvironments() {
		ExecutionEnvironment.resetContextEnvironment();
	}
}
