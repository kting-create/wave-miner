package com.waveminer.waveminer;

import com.waveminer.waveminer.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class WaveMinerApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(WaveMinerApplication.class)
                .initializers(ctx -> {
                    String dir = ctx.getEnvironment().getProperty("app.data-dir", "./data");
                    try {
                        Files.createDirectories(Path.of(dir));
                    } catch (IOException e) {
                        throw new UncheckedIOException("无法创建数据目录: " + dir, e);
                    }
                })
                .run(args);
    }
}
