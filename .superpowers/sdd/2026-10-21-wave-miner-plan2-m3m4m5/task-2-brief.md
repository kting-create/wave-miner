### Task 2: 转写引擎 SPI 类型 + EngineRegistry + GET /api/engines

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/Segment.java`、`engine/EngineInfo.java`、`engine/TranscribeOptions.java`、`engine/TranscribeResult.java`、`engine/ProgressCallback.java`、`engine/TranscriptionEngine.java`、`engine/EngineException.java`、`engine/EngineCancelledException.java`、`engine/EngineRegistry.java`、`engine/EngineKeyProvider.java`、`engine/SettingsEngineKeyProvider.java`、`api/EngineController.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/EngineRegistryTest.java`

**Interfaces:**
- Consumes: `SettingService`（Task 1）
- Produces（后续所有任务依赖，签名精确如下）:
  - `record Segment(long startMs, long endMs, String text, Integer speaker)`
  - `record EngineInfo(String id, String displayName, long maxDurationSec, long maxSizeBytes, boolean supportsDiarization, List<String> languages)`（languages 为 BCP-47 短码，不含 auto）
  - `record TranscribeOptions(String language, boolean diarize)` + 静态 `TranscribeOptions.of(String language, boolean diarize)`（null/blank→"auto"）
  - `record TranscribeResult(List<Segment> segments, String rawResponse)`
  - `interface ProgressCallback { void update(String message); default boolean cancelled() { return false; } static ProgressCallback noop() { return m -> {}; } }`
  - `interface TranscriptionEngine { String id(); EngineInfo info(); TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress); }`
  - `class EngineException extends RuntimeException`（`String getRawResponse()` 可 null；构造器 `(String)`、`(String, String raw)`、`(String, Throwable cause, String raw)`）
  - `class EngineCancelledException extends EngineException`（`(String msg)`）
  - `EngineRegistry`（@Component）：`EngineRegistry(ObjectProvider<TranscriptionEngine> provider)`、`List<TranscriptionEngine> all()`（`provider.orderedStream().toList()`）、`TranscriptionEngine get(String id)`（未知 → `EngineException("未知转写引擎: " + id)`）
  - `interface EngineKeyProvider { String getKey(String engineId); }`；`SettingsEngineKeyProvider`（@Component）：minimax→`minimax.apiKey`，aliyun-filetrans→`aliyun.apiKey`（环境变量兜底同 Task 1 规则）
  - `GET /api/engines` → `[{id,displayName,maxDurationSec,maxSizeBytes,supportsDiarization,languages,configured}]`（record `EngineView`，configured = key 非空；控制器构造参数用 `ObjectProvider<TranscriptionEngine>` 避免空列表注入失败）

- [ ] **Step 1: 写 EngineRegistry 测试（先失败）**

`backend/src/test/java/com/waveminer/waveminer/engine/EngineRegistryTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineRegistryTest {

    @SuppressWarnings("unchecked")
    private EngineRegistry registry(TranscriptionEngine... engines) {
        ObjectProvider<TranscriptionEngine> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.orderedStream()).thenReturn(List.of(engines).stream());
        return new EngineRegistry(provider);
    }

    private TranscriptionEngine fake(String id) {
        TranscriptionEngine e = Mockito.mock(TranscriptionEngine.class);
        Mockito.when(e.id()).thenReturn(id);
        return e;
    }

    @Test
    void getReturnsEngineById() {
        EngineRegistry r = registry(fake("a"), fake("b"));
        assertThat(r.get("b").id()).isEqualTo("b");
        assertThat(r.all()).hasSize(2);
    }

    @Test
    void unknownIdFailsInChinese() {
        EngineRegistry r = registry(fake("a"));
        assertThatThrownBy(() -> r.get("nope"))
                .isInstanceOf(EngineException.class)
                .hasMessageContaining("未知转写引擎");
    }

    @Test
    void emptyRegistryAllowed() {
        assertThat(registry().all()).isEmpty();
    }
}
```

- [ ] **Step 2: 实现全部类型（record/接口按 Interfaces 块逐字创建）**

`engine/Segment.java`：

```java
package com.waveminer.waveminer.engine;

public record Segment(long startMs, long endMs, String text, Integer speaker) {}
```

`engine/EngineInfo.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;

public record EngineInfo(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                         boolean supportsDiarization, List<String> languages) {}
```

`engine/TranscribeOptions.java`：

```java
package com.waveminer.waveminer.engine;

public record TranscribeOptions(String language, boolean diarize) {
    public static TranscribeOptions of(String language, boolean diarize) {
        return new TranscribeOptions(language == null || language.isBlank() ? "auto" : language, diarize);
    }
}
```

`engine/TranscribeResult.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;

public record TranscribeResult(List<Segment> segments, String rawResponse) {}
```

`engine/ProgressCallback.java`：

```java
package com.waveminer.waveminer.engine;

@FunctionalInterface
public interface ProgressCallback {
    void update(String message);

    default boolean cancelled() { return false; }

    static ProgressCallback noop() { return m -> {}; }
}
```

`engine/TranscriptionEngine.java`：

```java
package com.waveminer.waveminer.engine;

import java.nio.file.Path;

public interface TranscriptionEngine {
    String id();

    EngineInfo info();

    TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress);
}
```

`engine/EngineException.java`：

```java
package com.waveminer.waveminer.engine;

public class EngineException extends RuntimeException {
    private final String rawResponse;

    public EngineException(String message) { this(message, null, null); }

    public EngineException(String message, String rawResponse) { this(message, null, rawResponse); }

    public EngineException(String message, Throwable cause, String rawResponse) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() { return rawResponse; }
}
```

`engine/EngineCancelledException.java`：

```java
package com.waveminer.waveminer.engine;

public class EngineCancelledException extends EngineException {
    public EngineCancelledException(String message) { super(message); }
}
```

`engine/EngineRegistry.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EngineRegistry {

    private final ObjectProvider<TranscriptionEngine> provider;

    public EngineRegistry(ObjectProvider<TranscriptionEngine> provider) { this.provider = provider; }

    public List<TranscriptionEngine> all() {
        return provider.orderedStream().toList();
    }

    public TranscriptionEngine get(String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new EngineException("未知转写引擎: " + id));
    }
}
```

`engine/EngineKeyProvider.java`：

```java
package com.waveminer.waveminer.engine;

public interface EngineKeyProvider {
    /** 无配置返回 null */
    String getKey(String engineId);
}
```

`engine/SettingsEngineKeyProvider.java`：

```java
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
```

- [ ] **Step 3: 实现 EngineController**

`api/EngineController.java`：

```java
package com.waveminer.waveminer.api;

import com.waveminer.waveminer.engine.EngineInfo;
import com.waveminer.waveminer.engine.EngineKeyProvider;
import com.waveminer.waveminer.engine.TranscriptionEngine;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engines")
public class EngineController {

    public record EngineView(String id, String displayName, long maxDurationSec, long maxSizeBytes,
                             boolean supportsDiarization, List<String> languages, boolean configured) {}

    private final List<TranscriptionEngine> engines;
    private final EngineKeyProvider keys;

    public EngineController(ObjectProvider<TranscriptionEngine> engines, EngineKeyProvider keys) {
        this.engines = engines.orderedStream().toList();
        this.keys = keys;
    }

    @GetMapping
    public List<EngineView> list() {
        return engines.stream().map(e -> {
            EngineInfo i = e.info();
            return new EngineView(i.id(), i.displayName(), i.maxDurationSec(), i.maxSizeBytes(),
                    i.supportsDiarization(), i.languages(), keys.getKey(i.id()) != null);
        }).toList();
    }
}
```

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 转写引擎 SPI 与引擎注册表、GET /api/engines"
```

---

