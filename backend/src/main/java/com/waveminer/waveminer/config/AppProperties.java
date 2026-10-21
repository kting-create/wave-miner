package com.waveminer.waveminer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String dataDir = "./data";
    private int maxConcurrentTasks = 2;
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private String ytDlpPath = "yt-dlp";
    private long processTimeoutSeconds = 1800;
    private String accessToken = "";

    public Duration processTimeout() { return Duration.ofSeconds(processTimeoutSeconds); }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int v) { this.maxConcurrentTasks = v; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String v) { this.ffmpegPath = v; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String v) { this.ffprobePath = v; }
    public String getYtDlpPath() { return ytDlpPath; }
    public void setYtDlpPath(String v) { this.ytDlpPath = v; }
    public long getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(long v) { this.processTimeoutSeconds = v; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String v) { this.accessToken = v; }
}
