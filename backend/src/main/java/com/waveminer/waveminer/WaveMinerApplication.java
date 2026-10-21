package com.waveminer.waveminer;

import com.waveminer.waveminer.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class WaveMinerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WaveMinerApplication.class, args);
    }
}
