package com.checkmarx.jenkins.tools;

import com.checkmarx.jenkins.DescriptorImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static java.lang.String.format;

public class CheckmarxInstallation extends ToolInstallation implements EnvironmentSpecific<CheckmarxInstallation>, NodeSpecific<CheckmarxInstallation> {

    @DataBoundConstructor
    public CheckmarxInstallation(@Nonnull String name, @Nonnull String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public CheckmarxInstallation forEnvironment(EnvVars envVars) {
        return new CheckmarxInstallation(getName(), envVars.expand(getHome()), getProperties().toList());
    }

    @Override
    public CheckmarxInstallation forNode(@NonNull Node node, TaskListener taskListener) throws IOException, InterruptedException {
        return new CheckmarxInstallation(getName(), translateFor(node, taskListener), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String root = getHome();
        if (root != null) {
            env.put("PATH+CHECKMARX_HOME", new File(root, "node_modules/.bin").toString());
        }
    }

    public String getCheckmarxExecutable(@Nonnull Launcher launcher) throws IOException, InterruptedException {
        VirtualChannel channel = launcher.getChannel();
        return channel == null ? null : channel.call(new MasterToSlaveCallable<String, IOException>() {
            @Override
            public String call() throws IOException {
                return resolveExecutable("cx", Platform.current());
            }
        });
    }

    private String resolveExecutable(String file, Platform platform) throws IOException {
        final Path nodeModulesBin = getNodeModulesBin();
        if (nodeModulesBin != null) {
            final Path executable = nodeModulesBin.resolve(file);
            if (!executable.toFile().exists()) {
                throw new IOException(format("Could not find executable <%s>", executable));
            }
            return executable.toAbsolutePath().toString();
        } else {
            String root = getHome();
            if (root == null) {
                return null;
            }
            String wrapperFileName = platform.checkmarxWrapperFileName;
            final Path executable = Paths.get(root).resolve(wrapperFileName);
            if (!executable.toFile().exists()) {
                throw new IOException(format("Could not find executable <%s>", wrapperFileName));
            }
            return executable.toAbsolutePath().toString();
        }

    }


    private Path getNodeModulesBin() {
        String root = getHome();
        if (root == null) {
            return null;
        }

        Path nodeModules = Paths.get(root).resolve("node_modules").resolve(".bin");
        if (!nodeModules.toFile().exists()) {
            return null;
        }

        return nodeModules;
    }

    @Extension
    @Symbol("checkmarx")
    public static class CheckmarxInstallationDescriptor extends ToolDescriptor<CheckmarxInstallation> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Checkmarx";
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            return Collections.singletonList(new CheckmarxInstaller(null, null, null));
        }

        public CheckmarxInstallation[] getInstallations() {
            Jenkins instance = Jenkins.get();
            return instance.getDescriptorByType(DescriptorImpl.class).getInstallations();
        }

        public void setInstallations(CheckmarxInstallation... installations) {
            Jenkins instance = Jenkins.get();
            instance.getDescriptorByType(DescriptorImpl.class).setInstallations(installations);
        }
    }
}