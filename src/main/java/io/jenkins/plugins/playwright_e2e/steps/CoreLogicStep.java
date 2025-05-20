package io.jenkins.plugins.playwright_e2e.steps;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.AncestorInPath;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;

import java.util.Collections;
import java.util.Set;

public class CoreLogicStep extends Step {
    private final String scriptPath; // Changed from 'input'
    /** ID of the File Credential where the .env file is stored */
    private String envFileCredentialsId;
    /** Scripting language to execute (python | typescript) */
    private String language;

    @DataBoundConstructor
    public CoreLogicStep(String scriptPath) { // Changed from 'input'
        this.scriptPath = scriptPath; // Changed from 'input'
        this.language = "python"; // Default value
    }

    public String getScriptPath() { // Changed from 'getInput'
        return scriptPath; // Changed from 'input'
    }

    public String getEnvFileCredentialsId() {
        return envFileCredentialsId;
    }

    @DataBoundSetter
    public void setEnvFileCredentialsId(String envFileCredentialsId) {
        this.envFileCredentialsId = envFileCredentialsId;
    }

    public String getLanguage() {
        return language;
    }

    @DataBoundSetter
    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new CoreLogicStepExecution(this, context);
    }

    @Extension
    @Symbol("playwrightE2ETest")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "playwrightE2ETest";
        }

        @Override
        public String getDisplayName() {
            return "Run Playwright E2E Test";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, FilePath.class, Launcher.class);
        }

    }
}
