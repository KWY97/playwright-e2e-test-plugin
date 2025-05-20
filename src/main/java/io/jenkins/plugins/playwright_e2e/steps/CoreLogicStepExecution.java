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
import java.util.HashMap; // Added for environment map
import java.util.Map; // Added for environment map
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

        // Q4: Change result storage location to JENKINS_HOME/results for GlobalReportAction
        File jenkinsResultsDir = new File(Jenkins.get().getRootDir(), "results");
        // Ensure the main results directory exists
        if (!jenkinsResultsDir.exists() && !jenkinsResultsDir.mkdirs()) {
            listener.getLogger().println("▶ WARNING: Failed to create Jenkins global results directory: " + jenkinsResultsDir.getAbsolutePath());
            // Potentially throw an error here if this directory is critical
        }
        // The python script will create a subdirectory inside this based on job name and build number.
        // So, we pass jenkinsResultsDir as the base output directory to the script.
        File resultsDir = jenkinsResultsDir; // This will be passed as --output_dir to main_logic.py

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

        Map<String, String> envVars = new HashMap<>();
        if (step.getEnvFileCredentialsId() != null && !step.getEnvFileCredentialsId().isEmpty()) {
            FileCredentials envCred = CredentialsProvider.findCredentialById(
                    step.getEnvFileCredentialsId(), FileCredentials.class, run, Collections.emptyList()
            );
            if (envCred == null) {
                listener.error("❌ Could not find Secret File credential for credentialsId='" + step.getEnvFileCredentialsId() + "'.");
                return; // Or throw an exception
            }
            try (InputStream is = envCred.getContent();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    int P_ = line.indexOf('=');
                    if (P_ > 0) {
                        String key = line.substring(0, P_).trim();
                        String value = line.substring(P_ + 1).trim();
                        // Remove surrounding quotes if any (optional, depends on .env format)
                        if (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        envVars.put(key, value);
                    }
                }
                listener.getLogger().println("✅ Loaded .env content into environment variables.");
            } catch (IOException e) {
                listener.error("❌ Failed to read .env credential: " + e.getMessage());
                return; // Or throw
            }
        }
        // Inject JOB_NAME, it might be overwritten if present in .env but that's fine.
        envVars.put("JOB_NAME", run.getParent().getFullName());


        try {
            extractResources("python", pythonDir, listener);
            cleanDosLineEndings(pythonDir, listener, launcher);
            changeMode(pythonDir.child("setup.sh"), listener, 0755); // Keep this call
            int setupExit = executeShell(pythonDir, listener, launcher, "bash setup.sh");
            if (setupExit != 0) {
                listener.error("❌ setup.sh execution failed (exit=" + setupExit + ")");
                return;
            }

            listener.getLogger().println("▶ Executing Python test: main_logic.py (activate venv)");
            String buildNumber = String.valueOf(run.getNumber());
            String jobName = run.getParent().getFullName(); // Get the full job name
            String activateScript = new File(pythonDir.getRemote(), ".venv/bin/activate").getAbsolutePath();
            String cmd = String.join(" && ",
                    String.format("source %s", activateScript),
                    String.format("python main_logic.py --file '%s' --build %s --output_dir '%s'",
                            scenarioFilePath.getRemote(), buildNumber, resultsDir.getAbsolutePath() // scenarioFile.getAbsolutePath() -> scenarioFilePath.getRemote()
                    )
            );
            // Pass JOB_NAME to the python script environment
            Launcher.ProcStarter procStarter = launcher.launch()
                .cmds("bash", "-c", cmd)
                .pwd(pythonDir)
                .stdout(listener)
                .stderr(listener.getLogger())
                .envs(envVars) // Inject all loaded environment variables
                .quiet(true);

            int testExit = procStarter.join();
            listener.getLogger().println("▶ Test finished (exit=" + testExit + ")");

            String result = testExit == 0 ? "SUCCESS" : "FAIL";
            run.addAction(new BuildReportAction(step.getInput(), result));
            run.save();
        } finally {
            // No .env file to delete from workspace anymore
            listener.getLogger().println("▶ Python script execution finished.");
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

        Map<String, String> envVars = new HashMap<>();
        if (step.getEnvFileCredentialsId() != null && !step.getEnvFileCredentialsId().isEmpty()) {
            FileCredentials envCred = CredentialsProvider.findCredentialById(
                    step.getEnvFileCredentialsId(), FileCredentials.class, getContext().get(Run.class), Collections.emptyList()
            );
            if (envCred == null) {
                listener.error("❌ Could not find Secret File credential for credentialsId='" + step.getEnvFileCredentialsId() + "'.");
                return;
            }
            try (InputStream is = envCred.getContent();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    int P_ = line.indexOf('=');
                    if (P_ > 0) {
                        String key = line.substring(0, P_).trim();
                        String value = line.substring(P_ + 1).trim();
                        if (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        envVars.put(key, value);
                    }
                }
                listener.getLogger().println("✅ Loaded .env content into environment variables for TypeScript.");
            } catch (IOException e) {
                listener.error("❌ Failed to read .env credential for TypeScript: " + e.getMessage());
                return;
            }
        }
        // JOB_NAME might be useful for TS scripts too, though not explicitly used in current python script's folder naming
        envVars.put("JOB_NAME", getContext().get(Run.class).getParent().getFullName());


        try {
            // Extract TS resources
            extractResources("typescript", tsDir, listener);

            // Install dependencies (including Playwright and playwright-core)
            listener.getLogger().println("▶ Starting npm install"); // Already translated
            int installExit = executeShell(tsDir, listener, launcher, "npm install", envVars); // Pass envVars
            if (installExit != 0) {
                listener.error("❌ npm install failed (exit=" + installExit + ")");
                return;
            }
            listener.getLogger().println("▶ Starting additional playwright-core installation");
            int coreExit = executeShell(tsDir, listener, launcher, "npm install playwright-core", envVars); // Pass envVars
            if (coreExit != 0) {
                listener.error("❌ playwright-core installation failed (exit=" + coreExit + ")");
                return;
            }

            // Get build number (using Jenkins Run object)
            Run<?, ?> run = getContext().get(Run.class);
            String buildNumber = (run != null) ? Integer.toString(run.getNumber()) : "";

            // Execute TS script: index.ts + --build flag
            listener.getLogger().println("▶ Executing TS script: index.ts (passing scenario file and build number)"); // Already translated
            String cmd = String.format(
                    "npx ts-node index.ts '%s' --build %s",
                    scenarioFilePath.getRemote(),
                    buildNumber
            );
            int tsExit = executeShell(tsDir, listener, launcher, cmd, envVars); // Pass envVars
            listener.getLogger().println("▶ TS execution finished (exit=" + tsExit + ")");
        } finally {
            // No .env file to delete from workspace anymore
            listener.getLogger().println("▶ TypeScript script execution finished.");
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

    // Overload executeShell to accept envVars
    private int executeShell(FilePath dir, TaskListener listener, Launcher launcher, String command, Map<String,String> envVars) throws IOException, InterruptedException {
        Launcher.ProcStarter procStarter = launcher.launch()
                .cmds("bash", "-c", command)
                .pwd(dir)
                .stdout(listener)
                .stderr(listener.getLogger())
                .envs(envVars) // Use the passed environment variables
                .quiet(true);
        return procStarter.join();
    }

    // Original executeShell for calls that don't need specific .env content (like cleanDosLineEndings)
    private int executeShell(FilePath dir, TaskListener listener, Launcher launcher, String command) throws IOException, InterruptedException {
        return executeShell(dir, listener, launcher, command, Collections.emptyMap()); // Call overloaded version with empty env
    }
}
