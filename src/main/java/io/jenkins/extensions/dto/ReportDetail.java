package io.jenkins.extensions.dto;

import java.util.List;

public class ReportDetail {
    private String title;
    private boolean status;
    private double duration;
    private String feedback;
    private String fail;
    private List<String> screenshots;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public boolean isStatus() { return status; }
    public void setStatus(boolean status) { this.status = status; }
    public double getDuration() { return duration; }
    public void setDuration(double duration) { this.duration = duration; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getFail() { return fail; }
    public void setFail(String fail) { this.fail = fail; }
    public List<String> getScreenshots() { return screenshots; }
    public void setScreenshots(List<String> screenshots) { this.screenshots = screenshots; }
}
