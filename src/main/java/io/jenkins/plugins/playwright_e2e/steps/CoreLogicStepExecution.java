package io.jenkins.plugins.playwright_e2e.steps;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.FilePath;
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
        // 1) Prepare workspace and Python directory
        FilePath workspace = getContext().get(FilePath.class);
        if (workspace == null) {
            throw new IllegalStateException("Could not get workspace");
        }
        FilePath pythonDir = workspace.child("resources/python");
        pythonDir.mkdirs();

        TaskListener listener = getContext().get(TaskListener.class);
        Run<?, ?> run = getContext().get(Run.class);

        // 2) Retrieve Secret File Credentials and copy .env
        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(),
                FileCredentials.class,
                run,
                Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ Could not find Secret File credential with ID '" + step.getEnvFileCredentialsId() + "'.");
            return null;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = pythonDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ Copied .env file to " + envFile.getRemote());

        try {
            // 3) Extract bundled Python scripts
            listener.getLogger().println("▶ Starting extraction of bundled Python scripts...");
            extractResources("python", pythonDir, listener);
            listener.getLogger().println("▶ Script extraction complete.");

            // 3.5) Remove CRLF from setup.sh (alternative to dos2unix)
            listener.getLogger().println("▶ Starting CRLF removal from setup.sh...");
            ProcessBuilder pbClean = new ProcessBuilder(
                    "sed", "-i", "s/\\r$//", "setup.sh"
            );
            pbClean.directory(new File(pythonDir.getRemote()));
            pbClean.redirectErrorStream(true);
            try {
                Process procClean = pbClean.start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(procClean.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        listener.getLogger().println(line);
                    }
                }
                int exitCode = procClean.waitFor();
                if (exitCode != 0) {
                    listener.error("❌ Failed to remove CRLF from setup.sh (exit=" + exitCode + ")");
                } else {
                    listener.getLogger().println("✅ CRLF removal from setup.sh complete.");
                }
            } catch (IOException | InterruptedException e) {
                listener.error("❌ Exception during CRLF removal from setup.sh: " + e.getMessage());
            }

            // 4) Load scenario file (from JENKINS_HOME/scripts)
            String scenarioName = step.getInput();
            if (!scenarioName.endsWith(".json")) scenarioName += ".json";
            File jenkinsHome = Jenkins.get().getRootDir();
            File scriptsDir = new File(jenkinsHome, "scripts"); // Admin attention needed for this directory
            File scenarioFile = new File(scriptsDir, scenarioName);

            // Prevent Path Traversal: Check if the requested file is within scriptsDir
            if (!scenarioFile.getCanonicalPath().startsWith(scriptsDir.getCanonicalPath() + File.separator)) {
                listener.error("❌ Invalid scenario file path (Path Traversal attempt detected): " + scenarioName);
                throw new IllegalArgumentException("Invalid scenario file path: " + scenarioName);
            }

            if (!scenarioFile.exists()) {
                throw new IllegalArgumentException("Scenario file not found: " + scenarioFile);
            }
            listener.getLogger().println("▶ Loading scenario: " + scenarioName);
            String scenarioContent = new String(
                    Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8
            );
            listener.getLogger().println(scenarioContent);

            // 5) Change permissions for setup.sh
            FilePath setupSh = pythonDir.child("setup.sh");
            listener.getLogger().println("▶ chmod 0755 for setup.sh ...");
            setupSh.chmod(0755);

            // 6) Execute setup script
            listener.getLogger().println("▶ Executing setup script...");
            ProcessBuilder pbSetup = new ProcessBuilder("bash", "setup.sh")
                    .directory(new File(pythonDir.getRemote()))
                    .redirectErrorStream(true);
            Process setupProc = pbSetup.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(setupProc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    listener.getLogger().println(line);
                }
            }
            if (setupProc.waitFor() != 0) {
                throw new IllegalStateException("Setup script execution failed");
            }
            listener.getLogger().println("▶ Setup complete.");

            // 7) Execute Python test
            listener.getLogger().println("▶ Executing Python test: main_logic.py (activate venv)");
            String buildNumber = String.valueOf(run.getNumber());

            FilePath baseWorkspaceResultsDir = workspace.child("playwright-e2e-results");
            baseWorkspaceResultsDir.mkdirs();
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            String uniqueRunId = timestamp + "_build_" + buildNumber;
            FilePath currentRunResultsDir = baseWorkspaceResultsDir.child(uniqueRunId);
            currentRunResultsDir.mkdirs();
            String currentRunResultsDirRemotePath = currentRunResultsDir.getRemote();

            listener.getLogger().println("▶ Results directory: " + currentRunResultsDirRemotePath);

            listener.getLogger().println(
                    String.format("▶ Arguments: --file %s --build %s --output_dir %s",
                            scenarioFile.getAbsolutePath(), buildNumber, currentRunResultsDirRemotePath
                    )
            );
            String activateScript = new File(pythonDir.getRemote(), ".venv/bin/activate").getAbsolutePath();
            String command = String.join(" && ",
                    String.format("source %s", activateScript),
                    String.format("python main_logic.py --file '%s' --build %s --output_dir '%s'",
                            scenarioFile.getAbsolutePath(), buildNumber, currentRunResultsDirRemotePath
                    )
            );
            ProcessBuilder pbRun = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(pythonDir.getRemote()))
                    .redirectErrorStream(true);
            Process runProc = pbRun.start();
            try (BufferedReader rdr = new BufferedReader(
                    new InputStreamReader(runProc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = rdr.readLine()) != null) {
                    listener.getLogger().println(line);
                }
            }
            int runExit = runProc.waitFor();
            listener.getLogger().println("▶ Test execution finished (exit=" + runExit + ")");
            String result = runExit == 0 ? "SUCCESS" : "FAIL";
            run.addAction(new BuildReportAction(scenarioName, result, uniqueRunId)); // Pass uniqueRunId
            run.save();

        } finally {
            // 8) Always delete .env file
            listener.getLogger().println("▶ Deleting .env file: " + envFile.getRemote());
            if (!envFile.delete()) {
                listener.getLogger().println("⚠️ Failed to delete .env file.");
            }
        }

        return null;
    }

    private void extractResources(String resourcePath, FilePath targetDir, TaskListener listener) throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        URL dirURL = cl.getResource(resourcePath);
        if (dirURL == null) throw new IOException("Resource path not found: " + resourcePath);
        if ("file".equals(dirURL.getProtocol())) {
            try {
                Path src = Paths.get(dirURL.toURI());
                Files.walk(src).forEach(path -> {
                    try {
                        Path rel = src.relativize(path);
                        File dest = new File(targetDir.getRemote(), rel.toString());
                        if (Files.isDirectory(path)) {
                            if (!dest.mkdirs() && !dest.isDirectory()) {
                                listener.getLogger().println("Failed to create directory: " + dest);
                            }
                        } else {
                            File parent = dest.getParentFile();
                            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                listener.getLogger().println("Failed to create parent directory: " + parent);
                            }
                            Files.copy(path, dest.toPath()); // Consider StandardCopyOption.REPLACE_EXISTING
                        }
                    } catch (Exception e) {
                        listener.getLogger().println("Error copying resource: " + e.getMessage());
                    }
                });
            } catch (URISyntaxException e) {
                throw new IOException("Failed to convert resource path URI", e);
            }
        } else if ("jar".equals(dirURL.getProtocol())) {
            String jarPath = dirURL.getPath().substring(5, dirURL.getPath().indexOf("!"));
            try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(resourcePath + "/")) continue;
                    String rel = name.substring(resourcePath.length() + 1);
                    File out = new File(targetDir.getRemote(), rel);
                    if (entry.isDirectory()) {
                        if (!out.mkdirs() && !out.isDirectory()) {
                            listener.getLogger().println("Failed to create directory: " + out);
                        }
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            listener.getLogger().println("Failed to create parent directory: " + parent);
                        }
                        try (InputStream in = jar.getInputStream(entry);
                             OutputStream os = new FileOutputStream(out)) {
                            IOUtils.copy(in, os);
                        }
                    }
                }
            }
        } else {
            throw new IOException("Unsupported protocol: " + dirURL.getProtocol());
        }
    }
}
