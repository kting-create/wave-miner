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
    private String minimaxBaseUrl = "https://api.minimax.cn";
    private String minimaxModel = "asr-1.0";
    private String aliyunBaseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String aliyunModel = "qwen-audio-3.0-asr-flash-filetrans";
    private long engineHttpTimeoutSeconds = 600;
    private int engineRetryAttempts = 3;
    private long transcribePollIntervalSeconds = 5;
    private long transcribeTimeoutSeconds = 7200;
    private double silenceThresholdDb = -35.0;
    private double silenceMinDurationSec = 0.6;
    private long mergeGapMs = 800;

    public Duration processTimeout() { return Duration.ofSeconds(processTimeoutSeconds); }

    public Duration engineHttpTimeout() { return Duration.ofSeconds(engineHttpTimeoutSeconds); }

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
    public String getMinimaxBaseUrl() { return minimaxBaseUrl; }
    public void setMinimaxBaseUrl(String v) { this.minimaxBaseUrl = v; }
    public String getMinimaxModel() { return minimaxModel; }
    public void setMinimaxModel(String v) { this.minimaxModel = v; }
    public String getAliyunBaseUrl() { return aliyunBaseUrl; }
    public void setAliyunBaseUrl(String v) { this.aliyunBaseUrl = v; }
    public String getAliyunModel() { return aliyunModel; }
    public void setAliyunModel(String v) { this.aliyunModel = v; }
    public long getEngineHttpTimeoutSeconds() { return engineHttpTimeoutSeconds; }
    public void setEngineHttpTimeoutSeconds(long v) { this.engineHttpTimeoutSeconds = v; }
    public int getEngineRetryAttempts() { return engineRetryAttempts; }
    public void setEngineRetryAttempts(int v) { this.engineRetryAttempts = v; }
    public long getTranscribePollIntervalSeconds() { return transcribePollIntervalSeconds; }
    public void setTranscribePollIntervalSeconds(long v) { this.transcribePollIntervalSeconds = v; }
    public long getTranscribeTimeoutSeconds() { return transcribeTimeoutSeconds; }
    public void setTranscribeTimeoutSeconds(long v) { this.transcribeTimeoutSeconds = v; }
    public double getSilenceThresholdDb() { return silenceThresholdDb; }
    public void setSilenceThresholdDb(double v) { this.silenceThresholdDb = v; }
    public double getSilenceMinDurationSec() { return silenceMinDurationSec; }
    public void setSilenceMinDurationSec(double v) { this.silenceMinDurationSec = v; }
    public long getMergeGapMs() { return mergeGapMs; }
    public void setMergeGapMs(long v) { this.mergeGapMs = v; }
}
