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
        FilePath workspace = getContext().get(FilePath.class);
        if (workspace == null) {
            throw new IllegalStateException("워크스페이스를 가져올 수 없습니다");
        }
        TaskListener listener = getContext().get(TaskListener.class);
        Run<?, ?> run = getContext().get(Run.class);

        // 시나리오 파일명 결정 (Python: .json, TS: .txt)
        String scenarioName = step.getInput();
        String lang = step.getLanguage() != null ? step.getLanguage() : "python";
        if ("typescript".equalsIgnoreCase(lang)) {
            if (!scenarioName.endsWith(".txt")) scenarioName += ".txt";
        } else {
            if (!scenarioName.endsWith(".json")) scenarioName += ".json";
        }

        File jenkinsHome = Jenkins.get().getRootDir();
        File scriptsDir = new File(jenkinsHome, "scripts");
        File scenarioFile = new File(scriptsDir, scenarioName);
        if (!scenarioFile.exists()) {
            throw new IllegalArgumentException("시나리오 파일이 없습니다: " + scenarioFile);
        }
        listener.getLogger().println("▶ 시나리오 로드: " + scenarioName);

        File resultsDir = new File(jenkinsHome, "results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            listener.getLogger().println("▶ WARNING: results 디렉터리 생성 실패: " + resultsDir);
        }

        // 언어별 분기 실행
        if ("typescript".equalsIgnoreCase(lang)) {
            runTypeScriptBranch(workspace, listener, scenarioFile);
        } else {
            runPythonBranch(workspace, listener, scenarioFile, run, resultsDir);
        }
        return null;
    }

    private void runPythonBranch(FilePath workspace, TaskListener listener, File scenarioFile, Run<?,?> run, File resultsDir) throws Exception {
        FilePath pythonDir = workspace.child("resources/python");
        pythonDir.mkdirs();

        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(), FileCredentials.class, run, Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ credentialsId='" + step.getEnvFileCredentialsId() + "'에 해당하는 Secret File 크리덴셜을 찾을 수 없습니다.");
            return;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = pythonDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ .env 파일을 " + envFile.getRemote() + "에 복사했습니다.");

        try {
            extractResources("python", pythonDir, listener);
            cleanDosLineEndings(pythonDir, listener);
            changeMode(pythonDir.child("setup.sh"), listener, 0755);
            int setupExit = executeShell(pythonDir, listener, "bash setup.sh");
            if (setupExit != 0) {
                listener.error("❌ setup.sh 실행 실패 (exit=" + setupExit + ")");
                return;
            }

            listener.getLogger().println("▶ Python 테스트 실행: main_logic.py (activate venv)");
            String buildNumber = String.valueOf(run.getNumber());
            String activateScript = new File(pythonDir.getRemote(), ".venv/bin/activate").getAbsolutePath();
            String cmd = String.join(" && ",
                    String.format("source %s", activateScript),
                    String.format("python main_logic.py --file '%s' --build %s --output_dir '%s'",
                            scenarioFile.getAbsolutePath(), buildNumber, resultsDir.getAbsolutePath()
                    )
            );
            int testExit = executeShell(pythonDir, listener, cmd);
            listener.getLogger().println("▶ 테스트 종료 (exit=" + testExit + ")");

            String result = testExit == 0 ? "SUCCESS" : "FAIL";
            run.addAction(new BuildReportAction(step.getInput(), result));
            run.save();
        } finally {
            listener.getLogger().println("▶ .env 파일 삭제");
            pythonDir.child(".env").delete();
        }
    }

    private void runTypeScriptBranch(
            FilePath workspace,
            TaskListener listener,
            File scenarioFile
    ) throws Exception {
        FilePath tsDir = workspace.child("resources/typescript");
        tsDir.mkdirs();

        // .env 복사
        FileCredentials envCred = CredentialsProvider.findCredentialById(
                step.getEnvFileCredentialsId(), FileCredentials.class, getContext().get(Run.class), Collections.emptyList()
        );
        if (envCred == null) {
            listener.error("❌ credentialsId='" + step.getEnvFileCredentialsId() + "'에 해당하는 Secret File 크리덴셜을 찾을 수 없습니다.");
            return;
        }
        byte[] content;
        try (InputStream is = envCred.getContent()) {
            content = IOUtils.toByteArray(is);
        }
        FilePath envFile = tsDir.child(".env");
        envFile.write(new String(content, StandardCharsets.UTF_8), "UTF-8");
        listener.getLogger().println("✅ .env 파일을 " + envFile.getRemote() + "에 복사했습니다.");

        try {
            // TS 리소스 추출
            extractResources("typescript", tsDir, listener);

            // 의존성 설치 (Playwright 및 playwright-core 포함)
            listener.getLogger().println("▶ npm install 시작");
            int installExit = executeShell(tsDir, listener, "npm install");
            if (installExit != 0) {
                listener.error("❌ npm install 실패 (exit=" + installExit + ")");
                return;
            }
            listener.getLogger().println("▶ playwright-core 추가 설치 시작");
            int coreExit = executeShell(tsDir, listener, "npm install playwright-core");
            if (coreExit != 0) {
                listener.error("❌ playwright-core 설치 실패 (exit=" + coreExit + ")");
                return;
            }

            // 빌드 번호 가져오기 (Jenkins Run 객체 사용)
            Run<?, ?> run = getContext().get(Run.class);
            String buildNumber = (run != null) ? Integer.toString(run.getNumber()) : "";

            // TS 스크립트 실행: index.ts + --build 플래그
            listener.getLogger().println("▶ TS 스크립트 실행: index.ts (시나리오 파일과 빌드 번호 전달)");
            String cmd = String.format(
                    "npx ts-node index.ts '%s' --build %s",
                    scenarioFile.getAbsolutePath(),
                    buildNumber
            );
            int tsExit = executeShell(tsDir, listener, cmd);
            listener.getLogger().println("▶ TS 종료 (exit=" + tsExit + ")");
        } finally {
            // .env 정리
            listener.getLogger().println("▶ .env 파일 삭제");
            tsDir.child(".env").delete();
        }
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
                            dest.mkdirs();
                        } else {
                            dest.getParentFile().mkdirs();
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
            throw new IOException("지원하지 않는 프로토콜: " + dirURL.getProtocol());
        }
    }

    private void cleanDosLineEndings(FilePath dir, TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("▶ CRLF 제거 시작");
        executeShell(dir, listener, "find . -type f -name '*.sh' -exec sed -i 's/\r$//' {} +");
        listener.getLogger().println("▶ CRLF 제거 완료");
    }

    private void changeMode(FilePath file, TaskListener listener, int mode) throws IOException, InterruptedException {
        listener.getLogger().println("▶ chmod " + Integer.toOctalString(mode) + " " + file.getRemote());
        file.chmod(mode);
    }

    private int executeShell(FilePath dir, TaskListener listener, String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command)
                .directory(new File(dir.getRemote()))
                .redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                listener.getLogger().println(line);
            }
        }
        proc.waitFor();
        return proc.exitValue();
    }
}
