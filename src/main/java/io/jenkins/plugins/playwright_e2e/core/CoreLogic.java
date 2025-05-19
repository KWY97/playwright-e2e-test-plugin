package io.jenkins.plugins.playwright_e2e.core;

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
     * classpath:/python/test-core-logic.py 리소스를
     * 임시 파일로 추출하고 경로를 반환합니다.
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
     * 입력값과 빌드번호를 받아 Python 스크립트를 실행하고,
     * 스크립트 내에서 결과 파일을 생성한 뒤,
     * stdout으로 처리 결과를 반환합니다.
     */
    public String process(String input, int buildNumber) {
        String result = "";
        try {
            // 1) 스크립트를 임시 파일로 추출
            Path script = extractScript();

            // 2) Python 실행 (UTF-8 입출력 강제 + Jenkins 환경 주입)
            ProcessBuilder pb = new ProcessBuilder("python", script.toString(), input);
            Map<String, String> env = pb.environment();
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("PYTHONUTF8", "1");
            env.put("JENKINS_HOME", Jenkins.get().getRootDir().getAbsolutePath());
            env.put("BUILD_NUMBER", String.valueOf(buildNumber));

            Process proc = pb.start();

            // 3) 표준출력에서 한 줄 읽기 (UTF-8 디코딩)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), Charset.forName("UTF-8")))) {
                String line = reader.readLine();
                result = (line != null ? line : "");
            }

            // 4) 에러 로그 출력
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), Charset.forName("UTF-8")))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = err.readLine()) != null) {
                        sb.append(l).append("\n");
                    }
                    log.error("Python 에러:\n{}", sb.toString());
                }
            }

            log.info("Python 결과: {}", result);
        } catch (IOException | InterruptedException e) {
            log.error("Python 스크립트 실행 중 오류", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }
}
