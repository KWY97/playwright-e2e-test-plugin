package io.jenkins.extensions;

import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.extensions.dto.ScriptEntry;
import io.jenkins.extensions.dto.ScriptModel;
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
    @Override public String getIconFileName() { return "clipboard.png"; }
    @Override public String getDisplayName()    { return "Scripts"; }
    @Override public String getUrlName()        { return "scripts"; }

    private File getDir() throws IOException {
        File d = new File(Jenkins.get().getRootDir(), "scripts");
        if (!d.exists() && (!d.mkdirs() && !d.exists())) {
            throw new IOException("스크립트 저장 디렉터리를 생성하지 못했습니다: " + d.getAbsolutePath());
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
        // 한글 파라미터 깨짐 방지
        req.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // JSON 파라미터 읽기
        String json = req.getParameter("jsonData");
        ScriptModel model = ScriptModel.fromJson(json);

        // 파일명 생성
        String fileName = sanitize(model.getTitle()) + ".json";
        Path target = getDir().toPath().resolve(fileName);

        // UTF-8로 JSON 쓰기, 한글 유니코드 이스케이프 비활성화
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);

        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, model);
        }

        // 저장 후 목록으로 리다이렉트
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
}
