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

@Extension
public class ScriptAction implements RootAction {
    @Override public String getIconFileName() { return "notepad.png"; } // 아이콘 변경 제안
    @Override public String getDisplayName()    { return "E2E Test Scripts"; }
    @Override public String getUrlName()        { return "e2e-test-scripts"; }

    private File getDir() throws IOException {
        File d = new File(Jenkins.get().getRootDir(), "scripts"); // 이 디렉토리 사용은 여전히 관리자 주의 필요
        if (!d.exists() && (!d.mkdirs() && !d.exists())) {
            throw new IOException("Failed to create script storage directory: " + d.getAbsolutePath());
        }
        return d;
    }

    public List<ScriptEntry> getScripts() throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

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
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (script == null) return new ScriptModel();
        File f = new File(getDir(), script);
        return f.exists() ? load(f) : new ScriptModel();
    }

    @RequirePOST
    public void doSave(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        
        Jenkins.get().checkPermission(Jenkins.ADMINISTER); // Admin permission check
        
        req.setCharacterEncoding(StandardCharsets.UTF_8.name()); // Prevent broken characters for parameters

        String json = req.getParameter("jsonData");
        ScriptModel model = ScriptModel.fromJson(json); // Assuming ScriptModel.fromJson handles potential errors

        String fileName = sanitize(model.getTitle()) + ".json";
        Path target = getDir().toPath().resolve(fileName);

        // Write JSON with UTF-8, disable ASCII escape for non-ASCII characters
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);

        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, model);
        }

        rsp.sendRedirect2(Jenkins.get().getRootUrl() + getUrlName()); // Redirect to list after save
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
}
