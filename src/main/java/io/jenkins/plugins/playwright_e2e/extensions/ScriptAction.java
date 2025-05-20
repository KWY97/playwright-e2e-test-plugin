package io.jenkins.plugins.playwright_e2e.extensions;

import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.playwright_e2e.extensions.dto.ScriptEntry;
import io.jenkins.plugins.playwright_e2e.extensions.dto.ScriptModel;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.ServletException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import hudson.util.HttpResponses;

@Extension
public class ScriptAction implements RootAction {
    private static final Logger LOGGER = Logger.getLogger(ScriptAction.class.getName());

    @Override public String getIconFileName() { return "clipboard.png"; }
    @Override public String getDisplayName()    { return "Scripts"; }
    @Override public String getUrlName()        { return "scripts"; }

    private File getDir() throws IOException {
        File d = new File(Jenkins.get().getRootDir(), "scripts");
        if (!d.exists() && (!d.mkdirs() && !d.exists())) {
            throw new IOException("Failed to create script storage directory: " + d.getAbsolutePath());
        }
        return d;
    }

    public List<ScriptEntry> getScripts() throws IOException {
        File[] files = getDir().listFiles((f)->f.getName().endsWith(".json"));
        if (files == null) return Collections.emptyList();
        List<ScriptEntry> out = new ArrayList<>();
        for (File f: files) {
            ScriptModel m = load(f);
            out.add(new ScriptEntry(m.getTitle(), f.getName(), new Date(f.lastModified())));
        }
        out.sort(Comparator.comparing(ScriptEntry::getModified).reversed());
        return out;
    }

    public ScriptModel getIt(@QueryParameter String script) throws IOException {
        if (script == null) return new ScriptModel();
        File f = new File(getDir(), script);
        return f.exists() ? load(f) : new ScriptModel();
    }

    @RequirePOST
    public void doSave(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        // Prevent garbled Korean parameters
        req.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Read JSON parameter
        String json = req.getParameter("jsonData");
        ScriptModel model = ScriptModel.fromJson(json);

        // Generate filename
        String fileName = sanitize(model.getTitle()) + ".json";
        Path target = getDir().toPath().resolve(fileName);

        // Write JSON in UTF-8, disable Unicode escaping for Korean characters
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);

        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, model);
        }

        // Redirect to list after saving
        rsp.sendRedirect2(Jenkins.get().getRootUrl() + getUrlName());
    }

    private ScriptModel load(File f) {
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = Files.newBufferedReader(
                f.toPath(),
                StandardCharsets.UTF_8)) {
            return mapper.readValue(reader, ScriptModel.class);
        } catch (IOException e) {
            return new ScriptModel();
        }
    }

    private String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @RequirePOST
    public HttpResponse doDeleteScript(@QueryParameter String scriptName) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (scriptName == null || scriptName.isEmpty() || scriptName.contains("..") || scriptName.contains("/") || scriptName.contains("\\")) {
            LOGGER.log(Level.WARNING, "Invalid script name for deletion: {0}", scriptName);
            return HttpResponses.error(400, "Invalid script name.");
        }

        File scriptFile = new File(getDir(), scriptName);
        // Double check it's within the script directory and is a .json file
        if (!scriptFile.exists() || !scriptFile.isFile() || !scriptFile.getName().endsWith(".json")
                || !scriptFile.getCanonicalPath().startsWith(getDir().getCanonicalPath() + File.separator)) {
            LOGGER.log(Level.WARNING, "Script file not found or invalid for deletion: {0}", scriptName);
            return HttpResponses.error(404, "Script not found or invalid.");
        }

        LOGGER.log(Level.INFO, "Attempting to delete script: {0}", scriptFile.getAbsolutePath());
        if (scriptFile.delete()) {
            LOGGER.log(Level.INFO, "Successfully deleted script: {0}", scriptName);
            return HttpResponses.ok();
        } else {
            LOGGER.log(Level.SEVERE, "Failed to delete script: {0}", scriptName);
            return HttpResponses.error(500, "Failed to delete script.");
        }
    }
}
