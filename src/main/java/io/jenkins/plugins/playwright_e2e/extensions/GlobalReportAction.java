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
import org.kohsuke.stapler.verb.GET; // Import @GET annotation

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class GlobalReportAction implements RootAction {
    private static final Logger LOGGER = Logger.getLogger(GlobalReportAction.class.getName());

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
            // Check for report.html to determine the 'when' date
            File reportHtmlFile = new File(dir, "report.html");
            Date when;
            if (reportHtmlFile.exists()) {
                when = new Date(reportHtmlFile.lastModified());
            } else {
                // Fallback to directory's last modified time if report.html is not found
                when = new Date(dir.lastModified());
                LOGGER.log(Level.INFO, "report.html not found in {0}. Using directory modification time for 'when'.", dir.getAbsolutePath());
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
        // 'build' is JOB_NAME_BUILDNUMBER, 'scenario' is the scenario index (e.g., "1", "2")
        String base = Jenkins.get().getRootDir().getAbsolutePath();
        String path = String.join(File.separator, base, "results", build, scenario, "result.json");
        LOGGER.log(Level.INFO, "Attempting to read report detail from: {0}", path);
        if (!Files.exists(Paths.get(path))) {
            LOGGER.log(Level.WARNING, "result.json not found at: {0}", path);
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
        // 'build' parameter is the folder name like "JOB_NAME_BUILDNUMBER"
        File buildDir = new File(Jenkins.get().getRootDir(), "results" + File.separator + build);
        if (!buildDir.isDirectory()) {
            LOGGER.log(Level.WARNING, "Build directory not found or not a directory: {0}", buildDir.getAbsolutePath());
            return Collections.emptyList();
        }
        // List subdirectories that are named with numbers (scenario indexes)
        File[] scenarioDirs = buildDir.listFiles(pathname -> pathname.isDirectory() && pathname.getName().matches("\\d+"));
        if (scenarioDirs == null) {
            LOGGER.log(Level.WARNING, "Could not list scenario directories in: {0}", buildDir.getAbsolutePath());
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (File scenarioDir : scenarioDirs) {
            list.add(scenarioDir.getName());
        }
        // Sort by number to ensure scenarios are listed in order
        list.sort(Comparator.comparingInt(Integer::parseInt));
        return list;
    }

    /**
     * Return screenshot as base64 data URI.
     */
    public String getScreenshotDataUri(@QueryParameter String build,
                                       @QueryParameter String scenario,
                                       @QueryParameter String file) throws IOException {
        // 'build' is JOB_NAME_BUILDNUMBER, 'scenario' is the scenario index (e.g., "1"), 'file' is the image filename (e.g., "1.png")
        String base = Jenkins.get().getRootDir().getAbsolutePath();
        String imgPath = String.join(File.separator, base, "results", build, scenario, "screenshots", file);
        LOGGER.log(Level.INFO, "Attempting to read screenshot from: {0}", imgPath);
        if (!Files.exists(Paths.get(imgPath))) {
            LOGGER.log(Level.WARNING, "Screenshot not found at: {0}", imgPath);
            return "";
        }
        byte[] bytes = Files.readAllBytes(Paths.get(imgPath));
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }
    
    /**
     * Stapler endpoint that serves the report.html file directly.
     * URL: /jenkins/mcp-reports/report?build=<buildFolderName>
     */
    @GET // Explicitly mark as GET handler
    public HttpResponse doReport(@QueryParameter String build) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ); // Permission Check
        File html = new File(
                Jenkins.get().getRootDir(),
                "results" + File.separator + build + File.separator + "report.html"
        );
        // ── Debugging Log ─────────────────────────────
        LOGGER.log(Level.INFO, "[GlobalReportAction] Looking for report at: {0} (exists={1}, readable={2})",
                new Object[]{html.getAbsolutePath(), html.exists(), html.canRead()});
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
    @GET // Explicitly mark as GET handler
    public HttpResponse doScreenshot(
            @QueryParameter("build") String build,
            @QueryParameter("scenario") String scenario,
            @QueryParameter("file") String fileName
    ) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ); // Permission Check
        // Path Traversal Vulnerability Check for fileName
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            LOGGER.log(Level.WARNING, "Invalid characters in screenshot fileName: {0}", fileName);
            return HttpResponses.error(400, "Invalid screenshot file name.");
        }

        // Basic validation for build and scenario parameters (alphanumeric, underscore, hyphen)
        // This is a simple check, more robust validation might be needed depending on expected format.
        String safePattern = "^[a-zA-Z0-9_\\-]+$";
        if (build == null || !build.matches(safePattern)) {
            LOGGER.log(Level.WARNING, "Invalid characters in build parameter: {0}", build);
            return HttpResponses.error(400, "Invalid build parameter.");
        }
        if (scenario == null || !scenario.matches("^\\d+$")) { // Scenario should be a number
            LOGGER.log(Level.WARNING, "Invalid characters in scenario parameter: {0}", scenario);
            return HttpResponses.error(400, "Invalid scenario parameter.");
        }

        File jenkinsRoot = Jenkins.get().getRootDir();
        File expectedScreenshotsDir = new File(jenkinsRoot, "results/" + build + "/" + scenario + "/screenshots");
        File img = new File(expectedScreenshotsDir, fileName);

        // Verify that the canonical path of the image is within the expected screenshots directory
        if (!img.getCanonicalPath().startsWith(expectedScreenshotsDir.getCanonicalPath() + File.separator)) {
            LOGGER.log(Level.SEVERE, "Path traversal attempt detected for screenshot: {0}", img.getPath());
            return HttpResponses.error(403, "Access to the requested screenshot is forbidden.");
        }

        if (!img.isFile()) {
            LOGGER.log(Level.WARNING, "Screenshot not found or not a file: {0}", img.getAbsolutePath());
            return HttpResponses.error(404, "Screenshot not found");
        }
        
        LOGGER.log(Level.INFO, "Serving screenshot: {0}", img.getAbsolutePath());
        return HttpResponses.staticResource(img);
    }
}
