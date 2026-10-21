package com.waveminer.waveminer.pipeline;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SQLite 旧库列扩展：CREATE TABLE IF NOT EXISTS 不会补列，这里按需 ALTER。 */
@Component
@Order(1)
public class SchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        List<String> columns = jdbc.query("PRAGMA table_info(task)", (rs, i) -> rs.getString("name"));
        if (columns.isEmpty()) return;
        addColumn(columns, "engine_id", "TEXT");
        addColumn(columns, "language", "TEXT");
        addColumn(columns, "diarize", "INTEGER");
    }

    private void addColumn(List<String> columns, String name, String type) {
        if (!columns.contains(name)) {
            jdbc.execute("ALTER TABLE task ADD COLUMN " + name + " " + type);
        }
    }
}
