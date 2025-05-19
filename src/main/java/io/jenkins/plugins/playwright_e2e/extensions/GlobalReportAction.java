package io.jenkins.plugins.playwright_e2e.extensions;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.RootAction;
import io.jenkins.plugins.playwright_e2e.extensions.dto.BuildEntry;
import io.jenkins.plugins.playwright_e2e.extensions.dto.ReportDetail;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import hudson.util.HttpResponses;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class GlobalReportAction implements RootAction {
    @Override public String getIconFileName() { return "clipboard.png"; }
    @Override public String getDisplayName()    { return "MCP E2E Test Reports"; }
    @Override public String getUrlName()        { return "mcp-playwright-e2e-reports"; }

    // JENKINS_HOME/results 디렉토리를 직접 사용하는 로직은 제거되었습니다.
    // 각 빌드의 결과는 해당 빌드의 아티팩트를 통해 확인해야 합니다.
    // 이 페이지는 이제 각 빌드로 이동하여 리포트를 확인하도록 안내하는 역할을 합니다.

}
