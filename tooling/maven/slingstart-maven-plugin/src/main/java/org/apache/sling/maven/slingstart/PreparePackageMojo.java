/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.maven.slingstart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.IOUtils;
import org.apache.felix.cm.file.ConfigurationHandler;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.sling.provisioning.model.ArtifactGroup;
import org.apache.sling.provisioning.model.Configuration;
import org.apache.sling.provisioning.model.Feature;
import org.apache.sling.provisioning.model.Model;
import org.apache.sling.provisioning.model.ModelConstants;
import org.apache.sling.provisioning.model.RunMode;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Prepare the sling start applications.
 */
@Mojo(
        name = "prepare-package",
        defaultPhase = LifecyclePhase.PROCESS_SOURCES,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true
    )
public class PreparePackageMojo extends AbstractSlingStartMojo {

    private static final String BASE_DESTINATION = "resources";

    private static final String BOOT_DIRECTORY = "bundles";

    private static final String ARTIFACTS_DIRECTORY = "install";

    private static final String CONFIG_DIRECTORY = "config";

    private static final String BOOTSTRAP_FILE = "sling_bootstrap.txt";

    private static final String PROPERTIES_FILE = "sling_install.properties";

    /**
     * To look up Archiver/UnArchiver implementations
     */
    @Component
    private ArchiverManager archiverManager;

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     */
    @Component
    private ArtifactResolver resolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Model model = ModelUtils.getEffectiveModel(this.project);

        this.prepareGlobal(model);
        this.prepareStandaloneApp(model);
        this.prepareWebapp(model);
    }

    protected File getStandaloneOutputDirectory() {
        return new File(this.project.getBuild().getOutputDirectory());
    }

    /**
     * Prepare the global map for the artifacts.
     */
    private void prepareGlobal(final Model model) throws MojoExecutionException {
        final Map<String, File> globalContentsMap = new HashMap<String, File>();
        this.buildContentsMap(model, (String)null, globalContentsMap);

        this.project.setContextValue(BuildConstants.CONTEXT_GLOBAL, globalContentsMap);
    }

    /**
     * Prepare the standalone application.
     */
    private void prepareStandaloneApp(final Model model) throws MojoExecutionException {
        final Map<String, File> contentsMap = new HashMap<String, File>();
        this.project.setContextValue(BuildConstants.CONTEXT_STANDALONE, contentsMap);

        // unpack base artifact and create settings
        final File outputDir = getStandaloneOutputDirectory();
        unpackBaseArtifact(model, outputDir, ModelConstants.RUN_MODE_STANDALONE);
        this.buildSettings(model, ModelConstants.RUN_MODE_STANDALONE, outputDir);
        this.buildBootstrapFile(model, ModelConstants.RUN_MODE_STANDALONE, outputDir);

        this.buildContentsMap(model, ModelConstants.RUN_MODE_STANDALONE, contentsMap);
    }

    /**
     * Prepare the web application.
     */
    private void prepareWebapp(final Model model) throws MojoExecutionException {
        if ( this.createWebapp ) {
            final Map<String, File> contentsMap = new HashMap<String, File>();
            this.project.setContextValue(BuildConstants.CONTEXT_WEBAPP, contentsMap);

            // unpack base artifact and create settings
            final File outputDir = new File(this.project.getBuild().getDirectory(), BuildConstants.WEBAPP_OUTDIR);
            final File webappDir = new File(outputDir, "WEB-INF");
            unpackBaseArtifact(model, outputDir, ModelConstants.RUN_MODE_WEBAPP);

            // check for web.xml
            final Feature webappF = model.getFeature(ModelConstants.FEATURE_LAUNCHPAD);
            if ( webappF != null ) {
                final RunMode webappRM = webappF.getRunMode(null);
                if ( webappRM != null ) {
                    final Configuration webConfig = webappRM.getConfiguration(ModelConstants.CFG_LAUNCHPAD_WEB_XML);
                    if ( webConfig != null ) {
                        final File webXML = new File(webappDir, "web.xml");
                        try {
                            FileUtils.fileWrite(webXML, webConfig.getProperties().get(ModelConstants.CFG_LAUNCHPAD_WEB_XML).toString());
                        } catch (final IOException e) {
                            throw new MojoExecutionException("Unable to write configuration to " + webXML, e);
                        }
                    }
                }
            }
            this.buildSettings(model, ModelConstants.RUN_MODE_WEBAPP, webappDir);
            this.buildBootstrapFile(model, ModelConstants.RUN_MODE_WEBAPP, webappDir);

            this.buildContentsMap(model, ModelConstants.RUN_MODE_WEBAPP, contentsMap);
        }
    }

    /**
     * Build a list of all artifacts.
     */
    private void buildContentsMap(final Model model, final String packageRunMode, final Map<String, File> contentsMap)
    throws MojoExecutionException {
        if ( packageRunMode == null ) {
            // add base jar
            final Artifact artifact = getBaseArtifact(model, null, BuildConstants.TYPE_JAR);
            contentsMap.put(BASE_DESTINATION + "/"+ artifact.getArtifactId() + "." + artifact.getArtifactHandler().getExtension(), artifact.getFile());
        }
        for(final Feature feature : model.getFeatures()) {
            if ( feature.isSpecial() && !feature.getName().equals(ModelConstants.FEATURE_BOOT)) {
                continue;
            }
            for(final RunMode runMode : feature.getRunModes()) {
                if ( packageRunMode == null ) {
                    if ( runMode.isSpecial() ) {
                        continue;
                    }
                    this.buildContentsMap(model, runMode, contentsMap, feature.getName().equals(ModelConstants.FEATURE_BOOT));
                } else {
                    if ( runMode.isRunMode(packageRunMode) ) {
                        this.buildContentsMap(model, runMode, contentsMap, feature.getName().equals(ModelConstants.FEATURE_BOOT));
                    }
                }
            }
        }
    }

    /**
     * Build a list of all artifacts from this run mode
     */
    private void buildContentsMap(final Model model, final RunMode runMode, final Map<String, File> contentsMap, final boolean isBoot)
    throws MojoExecutionException{
        for(final ArtifactGroup group : runMode.getArtifactGroups()) {
            for(final org.apache.sling.provisioning.model.Artifact a : group) {
                final Artifact artifact = ModelUtils.getArtifact(this.project, this.mavenSession, this.artifactHandlerManager, this.resolver, a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getType(), a.getClassifier());
                final File artifactFile = artifact.getFile();
                contentsMap.put(getPathForArtifact(group.getStartLevel(), artifactFile.getName(), runMode, isBoot), artifactFile);
            }
        }

        final File rootConfDir = new File(this.getTmpDir(), "global-config");
        boolean hasConfig = false;
        for(final Configuration config : runMode.getConfigurations()) {
            // skip special configurations
            if ( config.isSpecial() ) {
                continue;
            }
            final String configPath = getPathForConfiguration(config, runMode);
            final File configFile = new File(rootConfDir, configPath);
            getLog().debug(String.format("Creating configuration at %s", configFile.getPath()));
            configFile.getParentFile().mkdirs();
            try {
                final FileOutputStream os = new FileOutputStream(configFile);
                try {
                    ConfigurationHandler.write(os, config.getProperties());
                } finally {
                    os.close();
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to write configuration to " + configFile, e);
            }
            hasConfig = true;
        }
        if ( hasConfig ) {
            contentsMap.put(BASE_DESTINATION, rootConfDir);
        }
    }

    /**
     * Build the settings for the given packaging run mode
     */
    private void buildSettings(final Model model, final String packageRunMode, final File outputDir)
    throws MojoExecutionException {
        final Properties settings = new Properties();
        final Feature launchpadFeature = model.getFeature(ModelConstants.FEATURE_LAUNCHPAD);
        if ( launchpadFeature != null ) {
            final RunMode launchpadRunMode = launchpadFeature.getRunMode(null);
            if ( launchpadRunMode != null ) {
                for(final Map.Entry<String, String> entry : launchpadRunMode.getSettings()) {
                    settings.put(entry.getKey(), entry.getValue());
                }
            }
        }
        final Feature bootFeature = model.getFeature(ModelConstants.FEATURE_BOOT);
        if ( bootFeature != null ) {
            final RunMode bootRunMode = bootFeature.getRunMode(null);
            if ( bootRunMode != null ) {
                for(final Map.Entry<String, String> entry : bootRunMode.getSettings()) {
                    settings.put(entry.getKey(), entry.getValue());
                }
            }
        }
        for(final Feature f : model.getFeatures()) {
            final RunMode packageRM = f.getRunMode(new String[] {packageRunMode});
            if ( packageRM != null ) {
                for(final Map.Entry<String, String> entry : packageRM.getSettings()) {
                    settings.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if ( settings.size() > 0 ) {
            final File settingsFile = new File(outputDir, PROPERTIES_FILE);
            getLog().debug(String.format("Creating settings at %s", settingsFile.getPath()));
            FileWriter writer = null;
            try {
                writer = new FileWriter(settingsFile);
                settings.store(writer, null);
            } catch ( final IOException ioe ) {
                throw new MojoExecutionException("Unable to write properties file.", ioe);
            } finally {
                IOUtils.closeQuietly(writer);
            }
        }
    }

    /**
     * Build the bootstrap file for the given packaging run mode
     */
    private void buildBootstrapFile(final Model model, final String packageRunMode, final File outputDir)
    throws MojoExecutionException {
        final StringBuilder sb = new StringBuilder();

        final Feature launchpadFeature = model.getFeature(ModelConstants.FEATURE_LAUNCHPAD);
        if ( launchpadFeature != null ) {
            final RunMode launchpadRunMode = launchpadFeature.getRunMode(null);
            if ( launchpadRunMode != null ) {
                final Configuration c = launchpadRunMode.getConfiguration(ModelConstants.CFG_LAUNCHPAD_BOOTSTRAP);
                if ( c != null ) {
                    sb.append(c.getProperties().get(c.getPid()));
                    sb.append('\n');
                }
            }
            final RunMode packageRM = launchpadFeature.getRunMode(new String[] {packageRunMode});
            if ( packageRM != null ) {
                final Configuration c = packageRM.getConfiguration(ModelConstants.CFG_LAUNCHPAD_BOOTSTRAP);
                if ( c != null ) {
                    sb.append(c.getProperties().get(c.getPid()));
                    sb.append('\n');
                }
            }
        }

        if ( sb.length() > 0 ) {
            final File file = new File(outputDir, BOOTSTRAP_FILE);
            getLog().debug(String.format("Creating bootstrap file at %s", file.getPath()));
            try {
                FileUtils.fileWrite(file, sb.toString());
            } catch ( final IOException ioe ) {
                throw new MojoExecutionException("Unable to write bootstrap file.", ioe);
            }
        }
    }

    /**
     * Return the base artifact
     */
    private Artifact getBaseArtifact(final Model model, final String classifier, final String type) throws MojoExecutionException {
        final ModelUtils.SearchResult result = ModelUtils.findBaseArtifact(model);
        if ( result.errorMessage != null ) {
            throw new MojoExecutionException(result.errorMessage);
        }
        final org.apache.sling.provisioning.model.Artifact baseArtifact = result.artifact;

        final Artifact a = ModelUtils.getArtifact(this.project,  this.mavenSession, this.artifactHandlerManager, this.resolver,
                baseArtifact.getGroupId(),
                baseArtifact.getArtifactId(),
                baseArtifact.getVersion(),
                type,
                classifier);
        if (a == null) {
            throw new MojoExecutionException(
                    String.format("Project doesn't have a base dependency of groupId %s and artifactId %s",
                            baseArtifact.getGroupId(), baseArtifact.getArtifactId()));
        }
        return a;
    }

    /**
     * Unpack the base artifact
     */
    private void unpackBaseArtifact(final Model model, final File outputDirectory, final String packageRunMode)
     throws MojoExecutionException {
        final String classifier;
        final String type;
        if ( ModelConstants.RUN_MODE_STANDALONE.equals(packageRunMode) ) {
            classifier = BuildConstants.CLASSIFIER_APP;
            type = BuildConstants.TYPE_JAR;
        } else {
            classifier = BuildConstants.CLASSIFIER_WEBAPP;
            type = BuildConstants.TYPE_WAR;
        }
        final Artifact artifact = this.getBaseArtifact(model, classifier, type);
        unpack(artifact.getFile(), outputDirectory);
    }

    /**
     * Unpack a file
     */
    private void unpack(final File source, final File destination)
    throws MojoExecutionException {
        getLog().debug("Unpacking " + source.getPath() + " to\n  " + destination.getPath());
        try {
            destination.mkdirs();

            final UnArchiver unArchiver = archiverManager.getUnArchiver(source);

            unArchiver.setSourceFile(source);
            unArchiver.setDestDirectory(destination);

            unArchiver.extract();
        } catch (final NoSuchArchiverException e) {
            throw new MojoExecutionException("Unable to find archiver for " + source.getPath(), e);
        } catch (final ArchiverException e) {
            throw new MojoExecutionException("Unable to unpack " + source.getPath(), e);
        }
    }

    /**
     * Get the relative path for an artifact.
     */
    private String getPathForArtifact(final int startLevel, final String artifactName, final RunMode rm, final boolean isBoot) {
        final Set<String> runModesList = new TreeSet<String>();
        if (rm.getNames() != null ) {
            for(final String mode : rm.getNames()) {
                runModesList.add(mode);
            }
        }
        final String runModeExt;
        if ( runModesList.size() == 0 || rm.isSpecial() ) {
            runModeExt = "";
        } else {
            final StringBuilder sb = new StringBuilder();
            for(final String n : runModesList ) {
                sb.append('.');
                sb.append(n);
            }
            runModeExt = sb.toString();
        }

        if ( isBoot ) {
            return String.format("%s/%s/1/%s", BASE_DESTINATION, BOOT_DIRECTORY,
                    artifactName);
        }
        return String.format("%s/%s%s/%s/%s", BASE_DESTINATION, ARTIFACTS_DIRECTORY,
                runModeExt,
                (startLevel == -1 ? 1 : startLevel),
                artifactName);
    }

    /**
     * Get the relative path for a configuration
     */
    private String getPathForConfiguration(final Configuration config, final RunMode rm) {
        final Set<String> runModesList = new TreeSet<String>();
        if (rm.getNames() != null ) {
            for(final String mode : rm.getNames()) {
                runModesList.add(mode);
            }
        }
        final String runModeExt;
        if ( runModesList.size() == 0 || rm.isSpecial() ) {
            runModeExt = "";
        } else {
            final StringBuilder sb = new StringBuilder();
            boolean first = true;
            for(final String n : runModesList ) {
                if ( first ) {
                    sb.append('/');
                    first = false;
                } else {
                    sb.append('.');
                }
                sb.append(n);
            }
            runModeExt = sb.toString();
        }

        final String mainName = (config.getFactoryPid() != null ? config.getFactoryPid() : config.getPid());
        final String alias = (config.getFactoryPid() != null ? "-" + config.getPid() : "");
        return String.format("%s/%s%s/%s%s.config", BASE_DESTINATION, CONFIG_DIRECTORY,
                runModeExt,
                mainName,
                alias);
    }
}
