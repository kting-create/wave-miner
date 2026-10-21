package com.waveminer.waveminer.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingService {

    private final JdbcTemplate jdbc;

    public SettingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String get(String key) {
        List<String> rows = jdbc.query("SELECT value FROM app_setting WHERE key = ?",
                (rs, i) -> rs.getString(1), key);
        return rows.isEmpty() || rows.get(0).isBlank() ? null : rows.get(0);
    }

    public Map<String, String> getAll() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM app_setting",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    @Transactional
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
            return;
        }
        jdbc.update("INSERT INTO app_setting(key,value) VALUES(?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value.trim());
    }
}
