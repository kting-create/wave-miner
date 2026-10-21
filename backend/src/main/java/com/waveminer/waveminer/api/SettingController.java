package com.waveminer.waveminer.api;

import com.waveminer.waveminer.store.SettingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingController {

    /** 已知设置键 → 环境变量兜底（按序取第一个非空） */
    private static final Map<String, List<String>> KNOWN_KEYS = Map.of(
            "minimax.apiKey", List.of("WAVEMINER_MINIMAX_API_KEY"),
            "aliyun.apiKey", List.of("WAVEMINER_ALIYUN_API_KEY", "DASHSCOPE_API_KEY"));

    public record SettingView(String key, String value, boolean configured) {}
    private record PutRequest(String key, String value) {}

    private final SettingService settings;

    public SettingController(SettingService settings) { this.settings = settings; }

    @GetMapping
    public List<SettingView> list() {
        return KNOWN_KEYS.keySet().stream().sorted().map(k -> {
            String stored = settings.get(k);
            String effective = stored != null ? stored : envValue(k);
            return new SettingView(k, mask(effective), effective != null && !effective.isBlank());
        }).toList();
    }

    @PutMapping
    public SettingView put(@RequestBody PutRequest body) {
        if (body == null || body.key() == null || !KNOWN_KEYS.containsKey(body.key())) {
            throw new IllegalArgumentException("未知的设置项: " + (body == null ? "" : body.key()));
        }
        settings.set(body.key(), body.value() == null ? "" : body.value().trim());
        String effective = settings.get(body.key());
        return new SettingView(body.key(), mask(effective), effective != null);
    }

    private static String envValue(String key) {
        for (String env : KNOWN_KEYS.get(key)) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    static String mask(String v) {
        if (v == null || v.isBlank()) return "";
        if (v.length() <= 10) return "****";
        return v.substring(0, 2) + "****" + v.substring(v.length() - 4);
    }
}
