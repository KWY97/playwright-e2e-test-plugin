package io.jenkins.extensions.dto;

import java.util.Date;

public class BuildEntry {
    private final String dirName;
    private final int    number;
    private final String displayName;
    private final Date when;

    public BuildEntry(String dirName, int number, String displayName, Date when) {
        this.dirName = dirName;
        this.number = number;
        this.displayName = displayName;
        this.when = when;
    }

    public String getDirName() { return dirName; }
    public int getNumber() { return number; }
    public String getDisplayName() { return displayName; }
    public Date getWhen() { return when; }
}
