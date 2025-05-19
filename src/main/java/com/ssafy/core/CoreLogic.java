package com.ssafy.core;

import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class CoreLogic {
    private static final Logger log = LoggerFactory.getLogger(CoreLogic.class);

    /**
     * Extracts the classpath:/python/test-core-logic.py resource
     * to a temporary file and returns its path.
     */
    private Path extractScript() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream("python/test-core-logic.py")) {
            if (in == null) {
                throw new IOException("python/test-core-logic.py not found in classpath");
            }
            Path tmp = Files.createTempFile("test-core-logic-", ".py");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /**
     * Executes a Python script with the given input and build number,
     * generates a result file within the script, and
     * returns the processing result via stdout.
     */
    public String process(String input, int buildNumber) {
        String result = "";
        try {
            // 1) Extract the script to a temporary file
            Path script = extractScript();

            // 2) Execute Python (force UTF-8 I/O + inject Jenkins environment)
            ProcessBuilder pb = new ProcessBuilder("python", script.toString(), input);
            Map<String, String> env = pb.environment();
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONUTF8", "1");
            env.put("JENKINS_HOME", Jenkins.get().getRootDir().getAbsolutePath());
            env.put("BUILD_NUMBER", String.valueOf(buildNumber));

            Process proc = pb.start();

            // 3) Read one line from stdout (UTF-8 decoding)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), Charset.forName("UTF-8")))) {
                String line = reader.readLine();
                result = (line != null ? line : "");
            }

            // 4) Print error log
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), Charset.forName("UTF-8")))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = err.readLine()) != null) {
                        sb.append(l).append("\n");
                    }
                    log.error("Python error:\n{}", sb.toString());
                }
            }

            log.info("Python result: {}", result);
        } catch (IOException | InterruptedException e) {
            log.error("Error during Python script execution", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }
}
