package com.waveminer.waveminer.engine;

import com.waveminer.waveminer.store.SettingService;
import org.springframework.stereotype.Component;

@Component
public class SettingsEngineKeyProvider implements EngineKeyProvider {

    private final SettingService settings;

    public SettingsEngineKeyProvider(SettingService settings) { this.settings = settings; }

    @Override
    public String getKey(String engineId) {
        return switch (engineId) {
            case "minimax" -> firstNonBlank(settings.get("minimax.apiKey"), env("WAVEMINER_MINIMAX_API_KEY"));
            case "aliyun-filetrans" -> firstNonBlank(settings.get("aliyun.apiKey"),
                    env("WAVEMINER_ALIYUN_API_KEY"), env("DASHSCOPE_API_KEY"));
            default -> null;
        };
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
