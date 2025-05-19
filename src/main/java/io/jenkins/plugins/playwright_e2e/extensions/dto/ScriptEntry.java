package io.jenkins.plugins.playwright_e2e.extensions.dto;

import java.util.Date;

public class ScriptEntry {
    private final String title;
    private final String fileName;
    private final Date modified;

    public ScriptEntry(String title, String fileName, Date modified) {
        this.title = title;
        this.fileName = fileName;
        // 방어적 복사
        this.modified = modified != null ? new Date(modified.getTime()) : null;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public Date getModified() {
        return modified != null ? new Date(modified.getTime()) : null;
    }
}
