package com.waveminer.waveminer.config;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.ProcessRunner;
import com.waveminer.waveminer.media.YtDlpService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RunnerConfig {

    @Bean
    public FfmpegService ffmpegService(ProcessRunner pr, AppProperties props) {
        return new FfmpegService(pr::run, props);
    }

    @Bean
    public YtDlpService ytDlpService(ProcessRunner pr, AppProperties props) {
        return new YtDlpService(pr::run, props);
    }
}
