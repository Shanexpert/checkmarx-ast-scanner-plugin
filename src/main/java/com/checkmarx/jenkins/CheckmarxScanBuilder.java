package com.checkmarx.jenkins;

import com.checkmarx.ast.CxAuth;
import com.checkmarx.ast.CxScanConfig;
import com.checkmarx.jenkins.credentials.CheckmarxApiToken;
import com.checkmarx.jenkins.model.ScanConfig;
import com.checkmarx.jenkins.tools.CheckmarxInstallation;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.*;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import lombok.NonNull;
import lombok.SneakyThrows;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.cloudbees.plugins.credentials.CredentialsProvider.findCredentialById;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import static hudson.Util.fixEmptyAndTrim;
import static java.util.stream.Collectors.joining;

public class CheckmarxScanBuilder extends Builder implements SimpleBuildStep {

    public static final String GIT_BRANCH = "GIT_BRANCH";
    public static final String CVS_BRANCH = "CVS_BRANCH";
    public static final String SVN_REVISION = "SVN_REVISION";

    CxLoggerAdapter log;
    @Nullable
    private String serverUrl;
    private boolean useAuthenticationUrl;
    private String baseAuthUrl;
    private String tenantName;
    private String projectName;
    private String teamName;
    private String credentialsId;
    private String checkmarxInstallation;
    private String additionalOptions;
    private String zipFileFilters;
    private boolean useFileFiltersFromJobConfig;
    private boolean useOwnServerCredentials;

    @DataBoundConstructor
    public CheckmarxScanBuilder(boolean useOwnServerCredentials,
                                String serverUrl,
                                boolean useAuthenticationUrl,
                                String baseAuthUrl,
                                String tenantName,
                                String projectName,
                                String teamName,
                                String credentialsId,
                                String zipFileFilters,
                                boolean useFileFiltersFromJobConfig,
                                String additionalOptions
    ) {
        this.useOwnServerCredentials = useOwnServerCredentials;
        this.serverUrl = serverUrl;
        this.useAuthenticationUrl = useAuthenticationUrl;
        this.baseAuthUrl = baseAuthUrl;
        this.tenantName = tenantName;
        this.projectName = projectName;
        this.teamName = teamName;
        this.credentialsId = credentialsId;
        this.useFileFiltersFromJobConfig = useFileFiltersFromJobConfig;
        this.additionalOptions = additionalOptions;
        this.zipFileFilters = zipFileFilters;
    }

    public CheckmarxScanBuilder() {

    }

    public boolean getUseOwnServerCredentials() {
        return useOwnServerCredentials;
    }

    @DataBoundSetter
    public void setUseOwnServerCredentials(boolean useOwnServerCredentials) {
        this.useOwnServerCredentials = useOwnServerCredentials;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(@Nullable String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getTenantName() {
        return tenantName;
    }

    @DataBoundSetter
    public void setTenantName(@Nullable String tenantName) {
        this.tenantName = tenantName;
    }

    public String getProjectName() {
        return projectName;
    }

    @DataBoundSetter
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getTeamName() {
        return teamName;
    }

    @DataBoundSetter
    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public boolean getUseFileFiltersFromJobConfig() {
        return useFileFiltersFromJobConfig;
    }

    @DataBoundSetter
    public void setUseFileFiltersFromJobConfig(boolean useFileFiltersFromJobConfig) {
        this.useFileFiltersFromJobConfig = useFileFiltersFromJobConfig;
    }

    public String getZipFileFilters() {
        return zipFileFilters;
    }

    @DataBoundSetter
    public void setZipFileFilters(@Nullable String zipFileFilters) {
        this.zipFileFilters = zipFileFilters;
    }

    public String getAdditionalOptions() {
        return additionalOptions;
    }

    @DataBoundSetter
    public void setAdditionalOptions(@Nullable String additionalOptions) {
        this.additionalOptions = additionalOptions;
    }

    public String getCheckmarxInstallation() {
        return checkmarxInstallation;
    }

    @DataBoundSetter
    public void setCheckmarxInstallation(String checkmarxInstallation) {
        this.checkmarxInstallation = checkmarxInstallation;
    }

    public boolean isUseAuthenticationUrl() {
        return useAuthenticationUrl;
    }

    @DataBoundSetter
    public void setUseAuthenticationUrl(boolean useAuthenticationUrl) {
        this.useAuthenticationUrl = useAuthenticationUrl;
    }

    public String getBaseAuthUrl() {
        return baseAuthUrl;
    }

    @DataBoundSetter
    public void setBaseAuthUrl(String baseAuthUrl) {
        this.baseAuthUrl = baseAuthUrl;
    }

    @SneakyThrows
    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, EnvVars envVars, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        final CheckmarxScanBuilderDescriptor descriptor = getDescriptor();
        log = new CxLoggerAdapter(listener.getLogger());

        ScanConfig scanConfig;
        try {
            scanConfig = resolveConfiguration(run, workspace, descriptor, envVars, log);
        } catch (Exception e) {
            log.info(e.getMessage());
            run.setResult(Result.FAILURE);
            return;
        }

        printConfiguration(scanConfig, log);

        if (!getUseOwnServerCredentials()) checkmarxInstallation = descriptor.getCheckmarxInstallation();
        //// Check for required version of CLI
        CheckmarxInstallation installation = PluginUtils.findCheckmarxInstallation(checkmarxInstallation);
        if (installation == null) {
            log.info("Checkmarx installation named '" + checkmarxInstallation + "' was not found. Please configure the build properly and retry.");
            run.setResult(Result.FAILURE);
            return;
        }

        // install if necessary
        Computer computer = workspace.toComputer();
        Node node = computer != null ? computer.getNode() : null;
        if (node == null) {
            log.info("Not running on a build node.");
            run.setResult(Result.FAILURE);
            return;
        }

        installation = installation.forNode(node, listener);
        installation = installation.forEnvironment(envVars);
        String checkmarxCliExecutable = installation.getCheckmarxExecutable(launcher);

        if (checkmarxCliExecutable == null) {
            log.info("Can't retrieve the Checkmarx executable.");
            run.setResult(Result.FAILURE);
            return;
        }
        log.info("This is the executable: " + checkmarxCliExecutable);

        // Check if the configured token is valid.
        CheckmarxApiToken checkmarxToken = scanConfig.getCheckmarxToken();
        if (checkmarxToken == null) {
            log.error("Checkmarx API token with ID '" + credentialsId + "' was not found. Please configure the build properly and retry.");
            run.setResult(Result.FAILURE);
            return;
        }

        if (run.getActions(CheckmarxScanResultsAction.class).isEmpty()) {
            run.addAction(new CheckmarxScanResultsAction(run));
        }

        //----------Integration with the wrapper------------
        final boolean result = PluginUtils.submitScanDetailsToWrapper(scanConfig, checkmarxCliExecutable, this.log);
        if (result) {
            PluginUtils.generateHTMLReport(workspace);

            ArtifactArchiver artifactArchiver = new ArtifactArchiver(workspace.getName() + "_" + PluginUtils.CHECKMARX_AST_RESULTS_HTML);
            artifactArchiver.perform(run, workspace, envVars, launcher, listener);

            run.setResult(Result.SUCCESS);
        } else {
            run.setResult(Result.FAILURE);
        }
        return;
    }

    private String getDefaultBranchName(EnvVars envVars) {
        if (!StringUtils.isEmpty(envVars.get(GIT_BRANCH))) return envVars.get(GIT_BRANCH).replaceAll("^([^/]+)/", "");
        if (!StringUtils.isEmpty(envVars.get(CVS_BRANCH))) return envVars.get(CVS_BRANCH);
        if (!StringUtils.isEmpty(envVars.get(SVN_REVISION))) return envVars.get(SVN_REVISION);

        return "";
    }

    private void printConfiguration(ScanConfig scanConfig, CxLoggerAdapter log) {
        log.info("----**** Checkmarx Scan Configuration ****----");
        log.info("Checkmarx Server Url: " + scanConfig.getServerUrl());
        if (StringUtils.isNotEmpty(scanConfig.getBaseAuthUrl())) {
            log.info("Checkmarx Auth Server Url: " + scanConfig.getBaseAuthUrl());
        }
        log.info("Tenant Name: " + Optional.ofNullable(scanConfig.getTenantName()).orElse(""));
        log.info("Project Name: " + Optional.ofNullable(scanConfig.getProjectName()).orElse(""));
        log.info("Team Name: " + Optional.ofNullable(scanConfig.getTeamName()).orElse(""));
        log.info("Using Job Specific File filters: " + getUseFileFiltersFromJobConfig());

        if (getUseFileFiltersFromJobConfig()) {
            log.info("Using File Filters: " + scanConfig.getZipFileFilters());
        }

        log.info("Default branch name: " + Optional.ofNullable(scanConfig.getBranchName()).orElse(""));

        log.info("Additional Options: " + Optional.ofNullable(scanConfig.getAdditionalOptions()).orElse(""));

    }

    private ScanConfig resolveConfiguration(Run<?, ?> run, FilePath workspace, CheckmarxScanBuilderDescriptor descriptor, EnvVars envVars, CxLoggerAdapter log) throws Exception {
        ScanConfig scanConfig = new ScanConfig();

        if (fixEmptyAndTrim(getProjectName()) == null)
            throw new Exception("Please provide a valid project name.");
        if (!getUseOwnServerCredentials() && fixEmptyAndTrim(descriptor.getServerUrl()) == null)
            throw new Exception("Please setup the server url in the global settings.");
        if (!getUseOwnServerCredentials() && fixEmptyAndTrim(descriptor.getCredentialsId()) == null)
            throw new Exception("Please setup the credential in the global settings");

        scanConfig.setProjectName(getProjectName());

        if (fixEmptyAndTrim(getTeamName()) != null) {
            scanConfig.setTeamName(getTeamName());
        }

        if (descriptor.getUseAuthenticationUrl()) {
            scanConfig.setBaseAuthUrl(descriptor.getBaseAuthUrl());
        }

        if (this.getUseOwnServerCredentials()) {
            scanConfig.setServerUrl(getServerUrl());
            scanConfig.setTenantName(fixEmptyAndTrim(getTenantName()));
            if (this.isUseAuthenticationUrl()) {
                scanConfig.setBaseAuthUrl(this.getBaseAuthUrl());
            }
            scanConfig.setCheckmarxToken(getCheckmarxTokenCredential(run, getCredentialsId()));

        } else {
            scanConfig.setServerUrl(descriptor.getServerUrl());
            scanConfig.setTenantName(fixEmptyAndTrim(descriptor.getTenantName()));
            scanConfig.setCheckmarxToken(getCheckmarxTokenCredential(run, descriptor.getCredentialsId()));
        }

        if (getUseFileFiltersFromJobConfig()) {
            scanConfig.setZipFileFilters(cleanFilters(this.getZipFileFilters()));
        } else {
            scanConfig.setZipFileFilters(cleanFilters(descriptor.getZipFileFilters()));
        }

        String defaultBranchName = getDefaultBranchName(envVars);
        scanConfig.setBranchName(defaultBranchName);

        if (fixEmptyAndTrim(getAdditionalOptions()) != null) {
            scanConfig.setAdditionalOptions(getAdditionalOptions());
        }

        File file = new File(workspace.getRemote());
        String sourceDir = file.getAbsolutePath();
        scanConfig.setSourceDirectory(sourceDir);

        return scanConfig;
    }

    private CheckmarxApiToken getCheckmarxTokenCredential(Run<?, ?> run, String credentialsId) {
        return findCredentialById(credentialsId, CheckmarxApiToken.class, run);
    }

    private String cleanFilters(String filter) {
        String cleanFilter = fixEmptyAndTrim(filter);
        if (cleanFilter == null) return "";

        return cleanFilter.replaceAll("\\s+", "");
    }

    @Override
    public CheckmarxScanBuilderDescriptor getDescriptor() {
        return (CheckmarxScanBuilderDescriptor) super.getDescriptor();
    }

    @Symbol("checkmarxASTScanner")
    @Extension
    public static class CheckmarxScanBuilderDescriptor extends BuildStepDescriptor<Builder> {

        private static final Logger LOG = LoggerFactory.getLogger(CheckmarxScanBuilderDescriptor.class.getName());
        private static final int authValid = 0;

        @Nullable
        private String serverUrl;
        private String tenantName;
        private String baseAuthUrl;
        private boolean useAuthenticationUrl;
        private String checkmarxInstallation;
        private String credentialsId;
        @Nullable
        private String zipFileFilters;

        @CopyOnWrite
        private volatile CheckmarxInstallation[] installations = new CheckmarxInstallation[0];

        public CheckmarxScanBuilderDescriptor() {
            load();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Execute Checkmarx AST Scan";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @SuppressFBWarnings("EI_EXPOSE_REP")
        public CheckmarxInstallation[] getInstallations() {
            return this.installations;
        }

        public void setInstallations(final CheckmarxInstallation... installations) {
            this.installations = installations;
            this.save();
        }

        @Nullable
        public String getServerUrl() {
            return serverUrl;
        }

        public void setServerUrl(@Nullable String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public String getBaseAuthUrl() {
            return baseAuthUrl;
        }

        public void setBaseAuthUrl(@Nullable String baseAuthUrl) {
            this.baseAuthUrl = baseAuthUrl;
        }

        public boolean getUseAuthenticationUrl() {
            return this.useAuthenticationUrl;
        }

        public void setUseAuthenticationUrl(final boolean useAuthenticationUrl) {
            this.useAuthenticationUrl = useAuthenticationUrl;
        }

        public String getTenantName() {
            return tenantName;
        }

        public void setTenantName(@Nullable String tenantName) {
            this.tenantName = tenantName;
        }

        public String getCredentialsId() {
            return credentialsId;
        }

        public void setCredentialsId(String credentialsId) {
            this.credentialsId = credentialsId;
        }

        @Nullable
        public String getZipFileFilters() {
            return this.zipFileFilters;
        }

        public void setZipFileFilters(@Nullable final String zipFileFilters) {
            this.zipFileFilters = zipFileFilters;
        }

        public String getCheckmarxInstallation() {
            return checkmarxInstallation;
        }

        public void setCheckmarxInstallation(String checkmarxInstallation) {
            this.checkmarxInstallation = checkmarxInstallation;
        }


        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            JSONObject pluginData = formData.getJSONObject("checkmarx");
            req.bindJSON(this, pluginData);
            save();
            return false;
        }

        public boolean hasInstallationsAvailable() {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Available Checkmarx installations: {}",
                        Arrays.stream(this.installations).map(CheckmarxInstallation::getName).collect(joining(",", "[", "]")));
            }

            return this.installations.length > 0;
        }

        public FormValidation doCheckServerUrl(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Server Url cannot be empty");
            }
            return FormValidation.ok();
        }

        public FormValidation doTestConnection(@QueryParameter final String serverUrl,
                                               @QueryParameter final boolean useAuthenticationUrl,
                                               @QueryParameter final String baseAuthUrl,
                                               @QueryParameter final String tenantName,
                                               @QueryParameter final String credentialsId,
                                               @QueryParameter final String checkmarxInstallation,
                                               @AncestorInPath Item item,
                                               @AncestorInPath final Job job) {
            try {
                if (job == null) {
                    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                } else {
                    job.checkPermission(Item.CONFIGURE);
                }

                String cxInstallationPath = getCheckmarxInstallationPath(checkmarxInstallation);
                CheckmarxApiToken checkmarxApiToken = getCheckmarxApiToken(credentialsId);

                CxScanConfig config = new CxScanConfig();
                config.setBaseUri(serverUrl);
                config.setTenant(tenantName);
                config.setApiKey(checkmarxApiToken.getToken().getPlainText());
                config.setPathToExecutable(cxInstallationPath);

                if (useAuthenticationUrl) {
                    config.setBaseAuthUri(baseAuthUrl);
                }

                CxAuth cxAuth = new CxAuth(config, LOG);
                Integer valid = cxAuth.cxAuthValidate();

                return valid != null && valid.intValue() == authValid ? FormValidation.ok("Success") : FormValidation.ok("Failed");
            } catch (final Exception e) {
                return FormValidation.ok("Error: " + e.getMessage());
            }
        }

        private String getCheckmarxInstallationPath(String checkmarxInstallation) throws Exception {
            if (StringUtils.isEmpty(checkmarxInstallation)) throw new Exception("Checkmarx installation not provided");

            TaskListener taskListener = () -> System.out;
            Launcher launcher = Jenkins.get().createLauncher(taskListener);
            Computer computer = Arrays.stream(Jenkins.get().getComputers()).findFirst().orElseThrow(() -> new Exception("Error getting runner"));
            Node node = Optional.ofNullable(computer.getNode()).orElseThrow(() -> new Exception("Error getting runner"));

            CheckmarxInstallation cxInstallation = PluginUtils
                    .findCheckmarxInstallation(checkmarxInstallation)
                    .forNode(node, taskListener);

            return cxInstallation.getCheckmarxExecutable(launcher);
        }

        private CheckmarxApiToken getCheckmarxApiToken(String credentialsId) throws Exception {
            CheckmarxApiToken checkmarxApiToken =
                    CredentialsMatchers.firstOrNull(
                            lookupCredentials(CheckmarxApiToken.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList()),
                            withId(credentialsId));

            return Optional.ofNullable(checkmarxApiToken).orElseThrow(() -> new Exception("Error getting credentials"));
        }

        public FormValidation doCheckProjectName(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Project Name cannot be empty");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                        && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            return result.includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, CheckmarxApiToken.class)
                    .includeCurrentValue(credentialsId);

        }

        public FormValidation doCheckCredentialsId(@AncestorInPath Item item,
                                                   @QueryParameter String value
        ) {
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return FormValidation.ok();
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return FormValidation.ok();
                }
            }

            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Checkmarx API token is required.");
            }

            if (null == CredentialsMatchers.firstOrNull(lookupCredentials(CheckmarxApiToken.class, Jenkins.get(), ACL.SYSTEM, Collections.emptyList()),
                    anyOf(withId(value), CredentialsMatchers.instanceOf(CheckmarxApiToken.class)))) {
                return FormValidation.error("Cannot find currently selected Checkmarx API token.");
            }
            return FormValidation.ok();
        }

        public String getCredentialsDescription() {
            if (this.getServerUrl() == null || this.getServerUrl().isEmpty()) {
                return "not set";
            }

            return "Server URL: " + this.getServerUrl();

        }
    }

}
