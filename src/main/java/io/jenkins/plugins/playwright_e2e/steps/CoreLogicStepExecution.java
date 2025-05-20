package io.jenkins.plugins.playwright_e2e.steps;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import io.jenkins.plugins.playwright_e2e.actions.BuildReportAction;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import jenkins.model.Jenkins;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class CoreLogicStepExecution extends SynchronousNonBlockingStepExecution<Void> {
    private static final long serialVersionUID = 1L;
    private final transient CoreLogicStep step;

    protected CoreLogicStepExecution(CoreLogicStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    protected Void run() throws Exception {
        FilePath workspace = getContext().get(FilePath.class);
        if (workspace == null) {
            throw new IllegalStateException("Could not get workspace");
        }
        TaskListener listener = getContext().get(TaskListener.class);
        Run<?, ?> run = getContext().get(Run.class);
        Launcher launcher = workspace.createLauncher(listener);

        // Determine scenario filename (Python: .json, TS: .txt)
        String scenarioNameInput = step.getInput();

        // Q2: Prevent Path Traversal - Validate filename
        if (scenarioNameInput.contains("/") || scenarioNameInput.contains("\\") || scenarioNameInput.contains("..")) {
            throw new IllegalArgumentException("Invalid scenario filename. Cannot contain path characters: " + scenarioNameInput);
        }

        String scenarioName = scenarioNameInput;
        String lang = step.getLanguage() != null ? step.getLanguage() : "python";
        if ("typescript".equalsIgnoreCase(lang)) {
            if (!scenarioName.endsWith(".txt")) scenarioName += ".txt";
        } else {
            if (!scenarioName.endsWith(".json")) scenarioName += ".json";
        }

        File jenkinsHome = Jenkins.get().getRootDir();
        File scriptsDir = new File(jenkinsHome, "scripts");
        File scenarioFile = new File(scriptsDir, scenarioName);

        // Q2: Prevent Path Traversal - Normalize and validate path
        try {
            String canonicalScriptsDir = scriptsDir.getCanonicalPath();
            String canonicalScenarioFile = scenarioFile.getCanonicalPath();
            // Ensure scenarioFile is within scriptsDir and also check for symlink issues by ensuring it's a direct child.
            // A simple startsWith check might be vulnerable if scriptsDir is a symlink itself or scenarioName contains tricky sequences.
            // However, for a basic check, startsWith is a first step. More robust validation might involve checking parent directories.
            if (!canonicalScenarioFile.startsWith(canonicalScriptsDir + File.separator) || !scenarioFile.getParentFile().getCanonicalPath().equals(canonicalScriptsDir)) {
                 throw new IllegalArgumentException("Invalid scenario file path. Attempt to access outside allowed directory: " + scenarioFile.getPath());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Error validating scenario file path: " + scenarioFile.getPath(), e);
        }

        if (!scenarioFile.exists()) {
            throw new IllegalArgumentException("Scenario file does not exist: " + scenarioFile);
        }
        listener.getLogger().println("▶ Loading scenario: " + scenarioName);

        // Q4: Change result storage location to build-specific artifact directory
        File artifactsDir = run.getArtifactsDir();
        File resultsDir = new File(artifactsDir, "playwright-e2e-results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            // If resultsDir creation fails, it might be better to throw an exception or log a severe error.
            // Here, only a warning is printed, but stopping execution might be safer in a real scenario.
            listener.getLogger().println("▶ WARNING: Failed to create results directory in build artifacts: " + resultsDir.getAbsolutePath());
            // throw new IOException("Failed to create results directory in build artifacts: " + resultsDir.getAbsolutePath());
        }

        // Q1 & Q3 Resolved: Read script content and copy to a temporary file in the workspace
        String scenarioContent = new String(Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8);
        String tempScenarioFileName = "temp_scenario_" + System.currentTimeMillis() + ("typescript".equalsIgnoreCase(lang) ? ".txt" : ".json");
        FilePath tempScenarioFilePath = workspace.child(tempScenarioFileName);
        
        try {
            tempScenarioFilePath.write(scenarioContent, StandardCharsets.UTF_8.name());
            listener.getLogger().println("▶ Copied scenario file to workspace: " + tempScenarioFilePath.getRemote());

            // Language-specific execution branch (now using tempScenarioFilePath instead of scenarioFile)
            if ("typescript".equalsIgnoreCase(lang)) {
                runTypeScriptBranch(workspace, listener, launcher, tempScenarioFilePath);
            } else {
                runPythonBranch(workspace, listener, launcher, tempScenarioFilePath, run, resultsDir);
            }
        } finally {
            if (tempScenarioFilePath.exists()) {
                tempScenarioFilePath.delete();
                listener.getLogger().println("▶ Deleted temporary scenario file: " + tempScenarioFilePath.getRemote());
            }
        }
        return null;
    }

    private void runPythonBranch(FilePath workspace, TaskListener listener, Launcher launcher, FilePath scenarioFilePath, Run<?,?> run, File resultsDir) throws Exception { // File scenarioFile -> FilePath scenarioFilePath
        FilePath pythonDir = workspace.child("resources/python");
        pythonDir.mkdirs();

        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(), FileCredentials.class, run, Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ Could not find Secret File credential for credentialsId='" + step.getEnvFileCredentialsId() + "'.");
            return;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = pythonDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ Copied .env file to " + envFile.getRemote());

        try {
            extractResources("python", pythonDir, listener);
            cleanDosLineEndings(pythonDir, listener, launcher); // Keep this call
            changeMode(pythonDir.child("setup.sh"), listener, 0755); // Keep this call
            int setupExit = executeShell(pythonDir, listener, launcher, "bash setup.sh");
            if (setupExit != 0) {
                listener.error("❌ setup.sh execution failed (exit=" + setupExit + ")");
                return;
            }

            listener.getLogger().println("▶ Executing Python test: main_logic.py (activate venv)");
            String buildNumber = String.valueOf(run.getNumber());
            String activateScript = new File(pythonDir.getRemote(), ".venv/bin/activate").getAbsolutePath();
            String cmd = String.join(" && ",
                    String.format("source %s", activateScript),
                    String.format("python main_logic.py --file '%s' --build %s --output_dir '%s'",
                            scenarioFilePath.getRemote(), buildNumber, resultsDir.getAbsolutePath() // scenarioFile.getAbsolutePath() -> scenarioFilePath.getRemote()
                    )
            );
            int testExit = executeShell(pythonDir, listener, launcher, cmd);
            listener.getLogger().println("▶ Test finished (exit=" + testExit + ")");

            String result = testExit == 0 ? "SUCCESS" : "FAIL";
            run.addAction(new BuildReportAction(step.getInput(), result));
            run.save();
        } finally {
            listener.getLogger().println("▶ Deleting .env file"); // This was already translated
            pythonDir.child(".env").delete();
        }
    }

    private void runTypeScriptBranch(
            FilePath workspace,
            TaskListener listener,
            Launcher launcher,
            FilePath scenarioFilePath // File scenarioFile -> FilePath scenarioFilePath
    ) throws Exception {
        FilePath tsDir = workspace.child("resources/typescript");
        tsDir.mkdirs();

        // Copy .env
        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(), FileCredentials.class, getContext().get(Run.class), Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ Could not find Secret File credential for credentialsId='" + step.getEnvFileCredentialsId() + "'."); // Already translated
            return;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = tsDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ Copied .env file to " + envFile.getRemote()); // Already translated

        try {
            // Extract TS resources
            extractResources("typescript", tsDir, listener); // Keep this call

            // Install dependencies (including Playwright and playwright-core)
            listener.getLogger().println("▶ Starting npm install"); // Already translated
            int installExit = executeShell(tsDir, listener, launcher, "npm install");
            if (installExit != 0) {
                listener.error("❌ npm install failed (exit=" + installExit + ")"); // Already translated
                return;
            }
            listener.getLogger().println("▶ Starting additional playwright-core installation"); // Already translated
            int coreExit = executeShell(tsDir, listener, launcher, "npm install playwright-core");
            if (coreExit != 0) {
                listener.error("❌ playwright-core installation failed (exit=" + coreExit + ")"); // Already translated
                return;
            }

            // Get build number (using Jenkins Run object)
            Run<?, ?> run = getContext().get(Run.class);
            String buildNumber = (run != null) ? Integer.toString(run.getNumber()) : "";

            // Execute TS script: index.ts + --build flag
            listener.getLogger().println("▶ Executing TS script: index.ts (passing scenario file and build number)"); // Already translated
            String cmd = String.format(
                    "npx ts-node index.ts '%s' --build %s",
                    scenarioFilePath.getRemote(), // scenarioFile.getAbsolutePath() -> scenarioFilePath.getRemote()
                    buildNumber
            );
            int tsExit = executeShell(tsDir, listener, launcher, cmd);
            listener.getLogger().println("▶ TS execution finished (exit=" + tsExit + ")"); // Already translated
        } finally {
            // Clean up .env
            listener.getLogger().println("▶ Deleting .env file"); // Already translated
            tsDir.child(".env").delete();
        }
    }

    private void extractResources(String resourcePath, FilePath targetDir, TaskListener listener) throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        URL dirURL = cl.getResource(resourcePath);
        if (dirURL == null) throw new IOException("Resource path not found: " + resourcePath); // Already translated
        if ("file".equals(dirURL.getProtocol())) {
            try {
                Path src = Paths.get(dirURL.toURI());
                Files.walk(src).forEach(path -> {
                    try {
                        Path rel = src.relativize(path);
                        File dest = new File(targetDir.getRemote(), rel.toString());
                        if (Files.isDirectory(path)) {
                            dest.mkdirs();
                        } else {
                            dest.getParentFile().mkdirs();
                            Files.copy(path, dest.toPath());
                        }
                    } catch (Exception e) {
                        listener.getLogger().println("Error copying resource: " + e.getMessage()); // Already translated
                    }
                });
            } catch (URISyntaxException e) {
                throw new IOException("Failed to convert resource path URI", e); // Already translated
            }
        } else if ("jar".equals(dirURL.getProtocol())) {
            String jarPath = dirURL.getPath().substring(5, dirURL.getPath().indexOf("!"));
            try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(resourcePath + "/")) continue;
                    File out = new File(targetDir.getRemote(), name.substring(resourcePath.length() + 1));
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        out.getParentFile().mkdirs();
                        try (InputStream in = jar.getInputStream(entry);
                             OutputStream os = new FileOutputStream(out)) {
                            IOUtils.copy(in, os);
                        }
                    }
                }
            }
        } else {
            throw new IOException("Unsupported protocol: " + dirURL.getProtocol()); // Already translated
        }
    }

    private void cleanDosLineEndings(FilePath dir, TaskListener listener, Launcher launcher) throws IOException, InterruptedException {
        listener.getLogger().println("▶ Starting CRLF removal"); // Already translated
        executeShell(dir, listener, launcher, "find . -type f -name '*.sh' -exec sed -i 's/\r$//' {} +");
        listener.getLogger().println("▶ CRLF removal complete"); // Already translated
    }

    private void changeMode(FilePath file, TaskListener listener, int mode) throws IOException, InterruptedException {
        listener.getLogger().println("▶ chmod " + Integer.toOctalString(mode) + " " + file.getRemote()); // Already translated
        file.chmod(mode);
    }

    private int executeShell(FilePath dir, TaskListener listener, Launcher launcher, String command) throws IOException, InterruptedException {
        Launcher.ProcStarter procStarter = launcher.launch()
                .cmds("bash", "-c", command)
                .pwd(dir)
                .stdout(listener)
                .stderr(listener.getLogger())
                .quiet(true); // Do not print commands to the console

        return procStarter.join();
    }
}
