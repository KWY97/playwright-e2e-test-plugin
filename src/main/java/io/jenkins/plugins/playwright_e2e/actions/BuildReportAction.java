package io.jenkins.plugins.playwright_e2e.actions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Run;
import org.kohsuke.stapler.DataBoundConstructor;
import jenkins.model.RunAction2;
import java.util.Date;

public class BuildReportAction implements RunAction2 {
    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private transient Run<?,?> run;
    private final String scriptPath;
    private final String status;
    private final long timestamp;
    private final String uniqueRunId; // 예: "20231027-103000_build_1"

    @DataBoundConstructor
    public BuildReportAction(String scriptPath, String status, String uniqueRunId) {
        this.scriptPath = scriptPath;
        this.status     = status;
        this.timestamp  = System.currentTimeMillis();
        this.uniqueRunId = uniqueRunId;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    public String getStatus() {
        return status;
    }

    public Date getTimestamp() {
        return new Date(timestamp);
    }

    public String getUniqueRunId() {
        return uniqueRunId;
    }

    /**
     * Jelly 파일에서 사용할 report.html의 아티팩트 경로를 반환합니다.
     * 예: "playwright-e2e-results/20231027-103000_build_1/report.html"
     */
    public String getReportArtifactPath() {
        if (this.uniqueRunId == null || this.uniqueRunId.isEmpty()) {
            return null; // uniqueRunId가 없으면 경로를 만들 수 없음
        }
        return "playwright-e2e-results/" + this.uniqueRunId + "/report.html";
    }

    @Override
    public String getIconFileName() {
        return "graph.png"; // 빌드 사이드바에 아이콘 표시
    }

    @Override
    public String getDisplayName() {
        return "E2E 테스트 보고서"; // 빌드 사이드바에 표시될 이름
    }

    @Override
    public String getUrlName() {
        return "mcp-report";
    }

    @Override
    public void onAttached(Run<?,?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?,?> r) {
        this.run = r;
    }
}
