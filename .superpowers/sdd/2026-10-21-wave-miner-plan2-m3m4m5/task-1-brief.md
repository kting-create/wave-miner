### Task 1: 设置存储 app_setting + GET/PUT /api/settings（脱敏）

**Files:**
- Modify: `backend/src/main/resources/schema.sql`、`backend/src/main/java/com/waveminer/waveminer/config/WebConfig.java`（CORS 加 PUT）
- Create: `backend/src/main/java/com/waveminer/waveminer/store/SettingService.java`、`api/SettingController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/store/SettingServiceTest.java`、`api/SettingApiTest.java`

**Interfaces:**
- Produces:
  - 表 `app_setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)`（schema.sql，幂等）
  - `SettingService`：`String get(String key)`（null=无）、`void set(String key, String value)`（blank→删除）、`Map<String,String> getAll()`；已知键 `minimax.apiKey`、`aliyun.apiKey`
  - `GET /api/settings` → `[{"key":"minimax.apiKey","value":"sk****abcd","configured":true}]`（含环境变量兜底；record `SettingView(String key, String value, boolean configured)`）
  - `PUT /api/settings` body `{"key":"...","value":"..."}` → 脱敏后的 SettingView；未知 key → 400（中文）
  - 脱敏规则：长度>10 保留前 2 后 4，中间 `****`；否则 `****`
  - 后续任务依赖的解析顺序：设置表值（非空）→ 环境变量 `WAVEMINER_MINIMAX_API_KEY` / `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`）→ null

- [ ] **Step 1: schema.sql 追加**

```sql
CREATE TABLE IF NOT EXISTS app_setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

- [ ] **Step 2: 写 SettingService 测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/store/SettingServiceTest.java`：

```java
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
```

- [ ] **Step 3: 实现 SettingService**

`store/SettingService.java`：

```java
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
                rs -> out.put(rs.getString(1), rs.getString(2)));
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
```

- [ ] **Step 4: 写 SettingApiTest（先失败）**

`backend/src/test/java/com/waveminer/waveminer/api/SettingApiTest.java`：

```java
package com.waveminer.waveminer.api;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettingApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void putThenGetMasksValue() throws Exception {
        mvc.perform(put("/api/settings")
                        .contentType("application/json")
                        .content("{\"key\":\"minimax.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("minimax.apiKey"))
                .andExpect(jsonPath("$.value").value("sk****abcd"))
                .andExpect(jsonPath("$.configured").value(true));

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].value").value("sk****abcd"))
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].configured").value(true));
    }

    @Test
    void blankValueClearsKey() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void unknownKeyRejected() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"hacker.key\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未知的设置项")));
    }
}
```

- [ ] **Step 5: 实现 SettingController**

`api/SettingController.java`：

```java
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
```

- [ ] **Step 6: WebConfig CORS 允许 PUT**

`config/WebConfig.java` 中 `allowedMethods("GET", "POST", "DELETE")` 改为 `allowedMethods("GET", "POST", "PUT", "DELETE")`。

- [ ] **Step 7: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend && git commit -m "feat: 设置存储与脱敏的设置 API"
```

---

