package com.waveminer.waveminer.store;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SettingServiceTest {

    @TempDir
    Path tmp;

    private SettingService service;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("s.db")));
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        service = new SettingService(jdbc);
    }

    @Test
    void setGetAndOverwrite() {
        assertThat(service.get("minimax.apiKey")).isNull();
        service.set("minimax.apiKey", "sk-1234567890abcd");
        assertThat(service.get("minimax.apiKey")).isEqualTo("sk-1234567890abcd");
        service.set("minimax.apiKey", "sk-ffff");
        assertThat(service.get("minimax.apiKey")).isEqualTo("sk-ffff");
        assertThat(service.getAll()).containsEntry("minimax.apiKey", "sk-ffff");
    }

    @Test
    void blankValueDeletes() {
        service.set("aliyun.apiKey", "sk-1");
        service.set("aliyun.apiKey", "  ");
        assertThat(service.get("aliyun.apiKey")).isNull();
        assertThat(service.getAll()).isEmpty();
    }
}
