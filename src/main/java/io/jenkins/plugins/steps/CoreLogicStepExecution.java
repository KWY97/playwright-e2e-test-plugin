package io.jenkins.plugins.steps;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import io.jenkins.actions.BuildReportAction;
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
        // 1) 워크스페이스 및 Python 디렉터리 준비
        FilePath workspace = getContext().get(FilePath.class);
        if (workspace == null) {
            throw new IllegalStateException("워크스페이스를 가져올 수 없습니다");
        }
        FilePath pythonDir = workspace.child("resources/python");
        pythonDir.mkdirs();

        TaskListener listener = getContext().get(TaskListener.class);
        Run<?, ?> run = getContext().get(Run.class);

        // 2) Secret File Credentials 조회 및 .env 복사
        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(),
                FileCredentials.class,
                run,
                Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ credentialsId='" + step.getEnvFileCredentialsId() + "'에 해당하는 Secret File 크리덴셜을 찾을 수 없습니다.");
            return null;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = pythonDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ .env 파일을 " + envFile.getRemote() + "에 복사했습니다.");

        try {
            // 3) 번들된 Python 스크립트 추출
            listener.getLogger().println("▶ 번들된 Python 스크립트 추출 시작");
            extractResources("python", pythonDir, listener);
            listener.getLogger().println("▶ 스크립트 추출 완료");

            // 4) 시나리오 파일 로드 (JENKINS_HOME/scripts)
            String scenarioName = step.getInput();
            if (!scenarioName.endsWith(".json")) scenarioName += ".json";
            File jenkinsHome = Jenkins.get().getRootDir();
            File scriptsDir = new File(jenkinsHome, "scripts");
            File scenarioFile = new File(scriptsDir, scenarioName);
            if (!scenarioFile.exists()) {
                throw new IllegalArgumentException("시나리오 파일이 없습니다: " + scenarioFile);
            }
            listener.getLogger().println("▶ 시나리오 로드: " + scenarioName);
            String scenarioContent = new String(
                    Files.readAllBytes(scenarioFile.toPath()), StandardCharsets.UTF_8
            );
            listener.getLogger().println(scenarioContent);

            // 5) setup.sh 권한 변경
            FilePath setupSh = pythonDir.child("setup.sh");
            listener.getLogger().println("▶ setup.sh chmod 0755 …");
            setupSh.chmod(0755);

            // 6) setup 스크립트 실행
            listener.getLogger().println("▶ setup 스크립트 실행...");
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
                throw new IllegalStateException("setup 스크립트 실행 실패");
            }
            listener.getLogger().println("▶ setup 완료");

            // 7) Python 테스트 실행
            listener.getLogger().println("▶ Python 테스트 실행: main_logic.py (activate venv)");
            String buildNumber = String.valueOf(run.getNumber());
            File resultsDir = new File(Jenkins.get().getRootDir(), "results");
            if (!resultsDir.exists() && !resultsDir.mkdirs()) {
                listener.getLogger().println("▶ WARNING: results 디렉터리 생성 실패: " + resultsDir);
            }
            listener.getLogger().println(
                    String.format("▶ 인자: --file %s --build %s --output_dir %s",
                            scenarioFile.getAbsolutePath(), buildNumber, resultsDir.getAbsolutePath()
                    )
            );
            String activateScript = new File(pythonDir.getRemote(), ".venv/bin/activate").getAbsolutePath();
            String command = String.join(" && ",
                    String.format("source %s", activateScript),
                    String.format("python main_logic.py --file '%s' --build %s --output_dir '%s'",
                            scenarioFile.getAbsolutePath(), buildNumber, resultsDir.getAbsolutePath()
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
            listener.getLogger().println("▶ 테스트 종료 (exit=" + runExit + ")");
            String result = runExit == 0 ? "SUCCESS" : "FAIL";
            run.addAction(new BuildReportAction(scenarioName, result));
            run.save();

        } finally {
            // 8) 항상 .env 파일 삭제
            listener.getLogger().println("▶ .env 파일 삭제: " + envFile.getRemote());
            if (!envFile.delete()) {
                listener.getLogger().println("⚠️ .env 파일 삭제에 실패했습니다.");
            }
        }

        return null;
    }

    private void extractResources(String resourcePath, FilePath targetDir, TaskListener listener) throws IOException {
        ClassLoader cl = getClass().getClassLoader();
        URL dirURL = cl.getResource(resourcePath);
        if (dirURL == null) throw new IOException("리소스 경로를 찾을 수 없습니다: " + resourcePath);
        if ("file".equals(dirURL.getProtocol())) {
            try {
                Path src = Paths.get(dirURL.toURI());
                Files.walk(src).forEach(path -> {
                    try {
                        Path rel = src.relativize(path);
                        File dest = new File(targetDir.getRemote(), rel.toString());
                        if (Files.isDirectory(path)) {
                            if (!dest.mkdirs() && !dest.isDirectory()) {
                                listener.getLogger().println("디렉터리 생성 실패: " + dest);
                            }
                        } else {
                            File parent = dest.getParentFile();
                            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                listener.getLogger().println("디렉터리 생성 실패: " + parent);
                            }
                            Files.copy(path, dest.toPath());
                        }
                    } catch (Exception e) {
                        listener.getLogger().println("리소스 복사 오류: " + e.getMessage());
                    }
                });
            } catch (URISyntaxException e) {
                throw new IOException("리소스 경로 URI 변환 실패", e);
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
                            listener.getLogger().println("디렉터리 생성 실패: " + out);
                        }
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            listener.getLogger().println("디렉터리 생성 실패: " + parent);
                        }
                        try (InputStream in = jar.getInputStream(entry);
                             OutputStream os = new FileOutputStream(out)) {
                            IOUtils.copy(in, os);
                        }
                    }
                }
            }
        } else {
            throw new IOException("지원하지 않는 프로토콜: " + dirURL.getProtocol());
        }
    }
}
