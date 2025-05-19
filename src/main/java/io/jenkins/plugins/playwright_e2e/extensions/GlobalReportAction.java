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
import java.util.Base64;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class GlobalReportAction implements RootAction {
    @Override public String getIconFileName() { return "clipboard.png"; }
    @Override public String getDisplayName()    { return "MCP Reports"; }
    @Override public String getUrlName()        { return "mcp-reports"; }

    /**
     * Scan build folders in the 'results' directory.
     * Use the number after the last '_' as the build number.
     */
    public List<BuildEntry> getBuilds() {
        File rootDir = new File(Jenkins.get().getRootDir(), "results");
        if (!rootDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] dirs = rootDir.listFiles(File::isDirectory);
        if (dirs == null) {
            return Collections.emptyList();
        }
        List<BuildEntry> list = new ArrayList<>();
        for (File dir : dirs) {
            String name = dir.getName();
            String numPart = name;
            int idx = name.lastIndexOf('_');
            if (idx >= 0 && idx < name.length() - 1) {
                numPart = name.substring(idx + 1);
            }
            int buildNum;
            try {
                buildNum = Integer.parseInt(numPart);
            } catch (NumberFormatException e) {
                buildNum = -1;
            }
            // Check result.json
            File scenarioDir = new File(dir, numPart);
            File jsonFile = new File(scenarioDir, "result.json");
            Date when;
            if (jsonFile.exists()) {
                when = new Date(jsonFile.lastModified());
            } else {
                when = new Date(dir.lastModified());
            }
            list.add(new BuildEntry(name, buildNum, "Build " + numPart, when));
        }
        list.sort(Comparator.comparingInt(BuildEntry::getNumber).reversed());
        return list;
    }

    /**
     * Read JSON for a specific build directory and scenario.
     */
    public ReportDetail getReportDetail(@QueryParameter String build,
                                        @QueryParameter String scenario) throws IOException {
        String base = Jenkins.get().getRootDir().getAbsolutePath();
        String path = String.join(File.separator, base, "results", build, scenario, "result.json");
        if (!Files.exists(Paths.get(path))) {
            return null;
        }
        String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        JSONObject obj = JSONObject.fromObject(content);
        ReportDetail d = new ReportDetail();
        d.setTitle(obj.optString("title"));
        d.setStatus(obj.optBoolean("status"));
        d.setDuration(obj.optDouble("duration"));
        d.setFeedback(obj.optString("feedback"));
        d.setFail(obj.optString("fail", null));
        d.setScreenshots(new ArrayList<>());
        obj.optJSONArray("screenshots").forEach(o -> d.getScreenshots().add(o.toString()));
        return d;
    }

    /**
     * List of scenarios per build.
     */
    public List<String> getScenarios(@QueryParameter String build) {
        File dir = new File(Jenkins.get().getRootDir(), "results" + File.separator + build);
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (File sub : subs) {
            list.add(sub.getName());
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Return screenshot as base64 data URI.
     */
    public String getScreenshotDataUri(@QueryParameter String build,
                                       @QueryParameter String scenario,
                                       @QueryParameter String file) throws IOException {
        String base = Jenkins.get().getRootDir().getAbsolutePath();
        String imgPath = String.join(File.separator, base, "results", build, scenario, "screenshots", file);
        if (!Files.exists(Paths.get(imgPath))) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(Paths.get(imgPath));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Stapler endpoint that serves the report.html file directly.
     * URL: /jenkins/mcp-reports/report?build=<buildFolderName>
     */
    public HttpResponse doReport(@QueryParameter String build) throws IOException {
        File html = new File(
                Jenkins.get().getRootDir(),
                "results" + File.separator + build + File.separator + "report.html"
        );
        // ── Debugging Log ─────────────────────────────
        System.out.println("[GlobalReportAction] Looking for report at: "
                + html.getAbsolutePath()
                + " (exists=" + html.exists() + ", readable=" + html.canRead() + ")");
        // ─────────────────────────────────────────────
        if (!html.exists()) {
            return HttpResponses.error(404, "report.html not found for build: " + build);
        }
        return HttpResponses.staticResource(html);
    }

    /**
     * Serves screenshot images.
     * URL: /mcp-reports/screenshot?build={build}&scenario={scenario}&file={file}
     */
    public HttpResponse doScreenshot(
            @QueryParameter("build") String build,
            @QueryParameter("scenario") String scenario,
            @QueryParameter("file") String fileName
    ) throws IOException {
        File img = new File(
                Jenkins.get().getRootDir(),
                "results/" + build + "/" + scenario + "/screenshots/" + fileName
        );
        if (!img.isFile()) {
            return HttpResponses.error(404, "Screenshot not found");
        }
        return HttpResponses.staticResource(img);
    }
}
