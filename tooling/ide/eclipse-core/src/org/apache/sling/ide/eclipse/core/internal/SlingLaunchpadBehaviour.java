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
package org.apache.sling.ide.eclipse.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.apache.sling.ide.artifacts.EmbeddedArtifactLocator;
import org.apache.sling.ide.eclipse.core.ISlingLaunchpadServer;
import org.apache.sling.ide.eclipse.core.ServerUtil;
import org.apache.sling.ide.eclipse.core.debug.PluginLogger;
import org.apache.sling.ide.osgi.OsgiClient;
import org.apache.sling.ide.osgi.OsgiClientException;
import org.apache.sling.ide.serialization.SerializationException;
import org.apache.sling.ide.transport.Command;
import org.apache.sling.ide.transport.Repository;
import org.apache.sling.ide.transport.RepositoryInfo;
import org.apache.sling.ide.transport.ResourceProxy;
import org.apache.sling.ide.transport.Result;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.model.IModuleResource;
import org.eclipse.wst.server.core.model.IModuleResourceDelta;
import org.eclipse.wst.server.core.model.ServerBehaviourDelegate;
import org.osgi.framework.Version;

public class SlingLaunchpadBehaviour extends ServerBehaviourDelegate {

    private ResourceChangeCommandFactory commandFactory;
	private ILaunch launch;
	private JVMDebuggerConnection debuggerConnection;
	
    @Override
    public void stop(boolean force) {
    	if (debuggerConnection!=null) {
    		debuggerConnection.stop(force);
    	}
        setServerState(IServer.STATE_STOPPED);
        try {
            ServerUtil.stopRepository(getServer(), new NullProgressMonitor());
        } catch (CoreException e) {
            Activator.getDefault().getPluginLogger().warn("Failed to stop repository", e);
        }
    }

    public void start(IProgressMonitor monitor) throws CoreException {

        boolean success = false;
        Result<ResourceProxy> result = null;

        if (getServer().getMode().equals(ILaunchManager.DEBUG_MODE)) {
            debuggerConnection = new JVMDebuggerConnection();
            success = debuggerConnection.connectInDebugMode(launch, getServer(), monitor);

        } else {

            Repository repository;
            try {
                repository = ServerUtil.getRepository(getServer(), monitor);
            } catch (CoreException e) {
                setServerState(IServer.STATE_STOPPED);
                throw e;
            }
            Command<ResourceProxy> command = repository.newListChildrenNodeCommand("/");
            result = command.execute();
            success = result.isSuccess();
            
            RepositoryInfo repositoryInfo;
            try {
                repositoryInfo = ServerUtil.getRepositoryInfo(getServer(), monitor);
                OsgiClient client = Activator.getDefault().getOsgiClientFactory().createOsgiClient(repositoryInfo);
                Version bundleVersion = client.getBundleVersion(EmbeddedArtifactLocator.SUPPORT_BUNDLE_SYMBOLIC_NAME);
                
                ISlingLaunchpadServer launchpadServer = (ISlingLaunchpadServer) getServer().loadAdapter(SlingLaunchpadServer.class,
                        monitor);
                launchpadServer.setBundleVersion(EmbeddedArtifactLocator.SUPPORT_BUNDLE_SYMBOLIC_NAME, bundleVersion,
                        monitor);
                
            } catch (URISyntaxException e) {
                Activator.getDefault().getPluginLogger()
                        .warn("Failed retrieving information about the installation support bundle", e);
            } catch (OsgiClientException e) {
                Activator.getDefault().getPluginLogger()
                        .warn("Failed retrieving information about the installation support bundle", e);
            }
        }

        if (success) {
            setServerState(IServer.STATE_STARTED);
        } else {
            setServerState(IServer.STATE_STOPPED);
            String message = "Unable to connect to Sling Lanchpad. Please make sure a Launchpad instance is running ";
            if (result != null) {
                message += " (" + result.toString() + ")";
            }
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, message));
        }
    }

    // TODO refine signature
    public void setupLaunch(ILaunch launch, String launchMode, IProgressMonitor monitor) throws CoreException {
        // TODO check that ports are free

    	this.launch = launch;
        setServerRestartState(false);
        setServerState(IServer.STATE_STARTING);
        setMode(launchMode);
    }

    @Override
    protected void publishModule(int kind, int deltaKind, IModule[] module, IProgressMonitor monitor)
            throws CoreException {

        PluginLogger logger = Activator.getDefault().getPluginLogger();
        
        if (commandFactory == null) {
            commandFactory = new ResourceChangeCommandFactory(Activator.getDefault().getSerializationManager());
        }

        logger.trace(traceOperation(kind, deltaKind, module));

        if ((kind == IServer.PUBLISH_AUTO || kind == IServer.PUBLISH_INCREMENTAL)
                && deltaKind == ServerBehaviourDelegate.NO_CHANGE) {
            logger.trace("Ignoring request to publish the module when no resources have changed; most likely another module has changed");
            return;
        }
        
        if (kind == IServer.PUBLISH_FULL && deltaKind == ServerBehaviourDelegate.REMOVED) {
            logger.trace("Ignoring request to unpublish all of the module resources");
            return;
        }

        if (ProjectHelper.isBundleProject(module[0].getProject())) {
            String serverMode = getServer().getMode();
            if (!serverMode.equals(ILaunchManager.DEBUG_MODE)) {
                // in debug mode, we rely on the hotcode replacement feature of eclipse/jvm
                // otherwise, for run and profile modes we explicitly publish the bundle module
                // TODO: make this configurable as part of the server config
                publishBundleModule(module, monitor);
                BundleStateHelper.resetBundleState(getServer(), module[0].getProject());
            }
        } else if (ProjectHelper.isContentProject(module[0].getProject())) {

            try {
                publishContentModule(kind, deltaKind, module, monitor);
                BundleStateHelper.resetBundleState(getServer(), module[0].getProject());
            } catch (SerializationException e) {
                throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Serialization error for "
                        + traceOperation(kind, deltaKind, module).toString(), e));
            } catch (IOException e) {
                throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "IO error for "
                        + traceOperation(kind, deltaKind, module).toString(), e));
            }
        }
    }

    private String traceOperation(int kind, int deltaKind, IModule[] module) {
        StringBuilder trace = new StringBuilder();
        trace.append("SlingLaunchpadBehaviour.publishModule(");

        switch (kind) {
            case IServer.PUBLISH_CLEAN:
                trace.append("PUBLISH_CLEAN, ");
                break;
            case IServer.PUBLISH_INCREMENTAL:
                trace.append("PUBLISH_INCREMENTAL, ");
                break;
            case IServer.PUBLISH_AUTO:
                trace.append("PUBLISH_AUTO, ");
                break;
            case IServer.PUBLISH_FULL:
                trace.append("PUBLISH_FULL, ");
                break;
            default:
                trace.append("UNKNOWN - ").append(kind).append(", ");
        }

        switch (deltaKind) {
            case ServerBehaviourDelegate.ADDED:
                trace.append("ADDED, ");
                break;
            case ServerBehaviourDelegate.CHANGED:
                trace.append("CHANGED, ");
                break;
            case ServerBehaviourDelegate.NO_CHANGE:
                trace.append("NO_CHANGE, ");
                break;
            case ServerBehaviourDelegate.REMOVED:
                trace.append("REMOVED, ");
                break;
            default:
                trace.append("UNKONWN - ").append(deltaKind).append(", ");
                break;
        }

        trace.append(Arrays.toString(module)).append(")");

        return trace.toString();
    }

	private void publishBundleModule(IModule[] module, IProgressMonitor monitor) throws CoreException {
		final IProject project = module[0].getProject();
        boolean installLocally = getServer().getAttribute(ISlingLaunchpadServer.PROP_INSTALL_LOCALLY, true);
		monitor.beginTask("deploying via local install", 5);

        try {
            OsgiClient osgiClient = Activator.getDefault().getOsgiClientFactory()
                    .createOsgiClient(ServerUtil.getRepositoryInfo(getServer(), monitor));

            Version supportBundleVersion = osgiClient
                    .getBundleVersion(EmbeddedArtifactLocator.SUPPORT_BUNDLE_SYMBOLIC_NAME);
            monitor.worked(1);
            if (supportBundleVersion == null) {
                throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID,
                        "The support bundle was not found, please install it via the server properties page"));
            }

            IJavaProject javaProject = ProjectHelper.asJavaProject(project);

            IFolder outputFolder = (IFolder) project.getWorkspace().getRoot().findMember(javaProject.getOutputLocation());
            IPath outputLocation = outputFolder.getLocation();
            monitor.worked(1);

            if ( installLocally ) {
                osgiClient.installLocalBundle(outputLocation.toOSString());
                monitor.worked(3);
            } else {

                JarBuilder builder = new JarBuilder();
                InputStream bundle = builder.buildJar(outputFolder);
                monitor.worked(1);
                
                osgiClient.installLocalBundle(bundle, outputFolder.getLocation().toOSString());
                monitor.worked(2);
            }

            setModulePublishState(module, IServer.PUBLISH_STATE_NONE);

        } catch (URISyntaxException e1) {
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, e1.getMessage(), e1));
        } catch (OsgiClientException e1) {
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Failed installing bundle : "
                    + e1.getMessage(), e1));
        } finally {
            monitor.done();
        }
	}

    private void publishContentModule(int kind, int deltaKind, IModule[] module, IProgressMonitor monitor)
            throws CoreException, SerializationException, IOException {

        PluginLogger logger = Activator.getDefault().getPluginLogger();

		Repository repository = ServerUtil.getRepository(getServer(), monitor);
        
        // TODO it would be more efficient to have a module -> filter mapping
        // it would be simpler to implement this in SlingContentModuleAdapter, but
        // the behaviour for resources being filtered out is deletion, and that
        // would be an incorrect ( or at least suprising ) behaviour at development time

        switch (deltaKind) {
            case ServerBehaviourDelegate.CHANGED:
                for (IModuleResourceDelta resourceDelta : getPublishedResourceDelta(module)) {

                    StringBuilder deltaTrace = new StringBuilder();
                    deltaTrace.append("- processing delta kind ");

                    switch (resourceDelta.getKind()) {
                        case IModuleResourceDelta.ADDED:
                            deltaTrace.append("ADDED ");
                            break;
                        case IModuleResourceDelta.CHANGED:
                            deltaTrace.append("CHANGED ");
                            break;
                        case IModuleResourceDelta.NO_CHANGE:
                            deltaTrace.append("NO_CHANGE ");
                            break;
                        case IModuleResourceDelta.REMOVED:
                            deltaTrace.append("REMOVED ");
                            break;
                        default:
                            deltaTrace.append("UNKNOWN - ").append(resourceDelta.getKind());
                    }

                    deltaTrace.append("for resource ").append(resourceDelta.getModuleResource());

                    logger.trace(deltaTrace.toString());

                    switch (resourceDelta.getKind()) {
                        case IModuleResourceDelta.ADDED:
                        case IModuleResourceDelta.CHANGED:
                        case IModuleResourceDelta.NO_CHANGE: // TODO is this needed?
                            execute(addFileCommand(repository, resourceDelta.getModuleResource()));
                            break;
                        case IModuleResourceDelta.REMOVED:
                            execute(removeFileCommand(repository, resourceDelta.getModuleResource()));
                            break;
                    }
                }
                break;

            case ServerBehaviourDelegate.ADDED:
            case ServerBehaviourDelegate.NO_CHANGE: // TODO is this correct ?
                for (IModuleResource resource : getResources(module)) {
                    execute(addFileCommand(repository, resource));
                }
                break;
            case ServerBehaviourDelegate.REMOVED:
                IModuleResource[] moduleResources2 = getResources(module);
                for (IModuleResource resource : moduleResources2) {
                    execute(removeFileCommand(repository, resource));
                }
                break;
        }


        // set state to published
        super.publishModule(kind, deltaKind, module, monitor);
        setModulePublishState(module, IServer.PUBLISH_STATE_NONE);
//        setServerPublishState(IServer.PUBLISH_STATE_NONE);
	}

    private void execute(Command<?> command) throws CoreException {
        if (command == null) {
            return;
        }
        Result<?> result = command.execute();

        if (!result.isSuccess()) {
            // TODO - proper error logging
            throw new CoreException(new Status(Status.ERROR, Activator.PLUGIN_ID, "Failed publishing "
                    + result.toString()));
        }

    }

    private Command<?> addFileCommand(Repository repository, IModuleResource resource) throws CoreException,
            SerializationException, IOException {

        IResource res = getResource(resource);

        if (res == null) {
            return null;
        }

        return commandFactory.newCommandForAddedOrUpdated(repository, res);
    }

    private IResource getResource(IModuleResource resource) {

        IResource file = (IFile) resource.getAdapter(IFile.class);
        if (file == null) {
            file = (IFolder) resource.getAdapter(IFolder.class);
        }

        if (file == null) {
            // Usually happens on server startup, it seems to be safe to ignore for now
            Activator.getDefault().getPluginLogger()
                    .trace("Got null '{0}' and '{1}' for {2}", IFile.class.getSimpleName(),
                            IFolder.class.getSimpleName(), resource);
            return null;
        }

        return file;
    }

    private Command<?> removeFileCommand(Repository repository, IModuleResource resource)
            throws SerializationException, IOException, CoreException {
    	
        IResource deletedResource = getResource(resource);

        if (deletedResource == null) {
            return null;
        }

        return commandFactory.newCommandForRemovedResources(repository, deletedResource);
    }
}