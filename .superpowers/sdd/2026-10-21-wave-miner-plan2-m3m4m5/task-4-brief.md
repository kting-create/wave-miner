### Task 4: 转写产物格式器 TranscriptFormat + SegmentMerger

**Files:**
- Create: `backend/src/main/java/com/waveminer/waveminer/engine/TranscriptFormat.java`、`engine/SegmentMerger.java`
- Test: `backend/src/test/java/com/waveminer/waveminer/engine/TranscriptFormatTest.java`、`engine/SegmentMergerTest.java`

**Interfaces:**
- Produces:
  - `TranscriptFormat`（静态工具）：
    - `String toJson(String engineId, String language, long durationMs, List<Segment> segments)` → `{"segments":[{startMs,endMs,text,speaker?}],"engine","language","durationMs"}`（speaker 为 null 时省略该键）
    - `String toSrt(List<Segment> segments)` → 标准 SRT（序号从 1，`00:00:00,100 --> 00:00:01,660`，毫秒 3 位，段间空行）
    - `String toTxt(List<Segment> segments)` → 每段一行 `[mm:ss] text`
    - `static String timecode(long ms)` → `mm:ss`（≥1 小时 `h:mm:ss`）
  - `SegmentMerger`：`static List<Segment> merge(List<Segment> segments, long gapMs)` —— 按 startMs 排序；`next.startMs - cur.endMs < gapMs` 且非说话人冲突（仅两者非空且不等）→ 合并（start=较早，end=较晚，text 直接拼接，speaker 取有值方）

- [ ] **Step 1: 写测试（先失败）**

`engine/TranscriptFormatTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptFormatTest {

    private final List<Segment> segments = List.of(
            new Segment(0, 1660, "嘎嘎会，可以，这把稳了。", 1),
            new Segment(2000, 6100, "Let me check the question.", 2),
            new Segment(65000, 70000, "无说话人", null));

    @Test
    void jsonStructure() {
        String json = TranscriptFormat.toJson("minimax", "zh", 71000, segments);
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("engine").asText()).isEqualTo("minimax");
        assertThat(root.get("language").asText()).isEqualTo("zh");
        assertThat(root.get("durationMs").asLong()).isEqualTo(71000);
        assertThat(root.get("segments").size()).isEqualTo(3);
        assertThat(root.get("segments").get(0).get("speaker").asInt()).isEqualTo(1);
        assertThat(root.get("segments").get(2).get("speaker").isNull()).isTrue();
    }

    @Test
    void srtFormat() {
        String srt = TranscriptFormat.toSrt(segments.subList(0, 2));
        assertThat(srt).startsWith("1\n00:00:00,000 --> 00:00:01,660\n嘎嘎会，可以，这把稳了。\n\n")
                .contains("2\n00:00:02,000 --> 00:00:06,100\nLet me check the question.\n");
    }

    @Test
    void txtFormatUsesMinutePrefix() {
        String txt = TranscriptFormat.toTxt(segments);
        assertThat(txt).startsWith("[00:00] 嘎嘎会，可以，这把稳了。\n")
                .contains("[00:02] Let me check the question.\n")
                .contains("[01:05] 无说话人");
    }

    @Test
    void timecodeHours() {
        assertThat(TranscriptFormat.timecode(65_000)).isEqualTo("01:05");
        assertThat(TranscriptFormat.timecode(3_723_000)).isEqualTo("1:02:03");
    }
}
```

`engine/SegmentMergerTest.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentMergerTest {

    @Test
    void mergesGapBelowThreshold() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "你好", 1),
                new Segment(1500, 2000, "呀。", 1),
                new Segment(6000, 7000, "下一句", 1)), 800);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).startMs()).isEqualTo(0);
        assertThat(merged.get(0).endMs()).isEqualTo(2000);
        assertThat(merged.get(0).text()).isEqualTo("你好呀。");
        assertThat(merged.get(0).speaker()).isEqualTo(1);
    }

    @Test
    void speakerConflictPreventsMerge() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "甲", 1),
                new Segment(1200, 2000, "乙", 2)), 800);
        assertThat(merged).hasSize(2);
    }

    @Test
    void nullSpeakerMergesFreely() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "a", null),
                new Segment(1300, 2000, "b", 3)), 800);
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).speaker()).isEqualTo(3);
    }

    @Test
    void gapAtOrAboveThresholdNotMerged() {
        List<Segment> merged = SegmentMerger.merge(List.of(
                new Segment(0, 1000, "a", null),
                new Segment(1800, 2000, "b", null)), 800);
        assertThat(merged).hasSize(2);
    }
}
```

Run: `cd backend && ./mvnw -q test`
Expected: FAIL，编译错误

- [ ] **Step 2: 实现 TranscriptFormat**

`engine/TranscriptFormat.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class TranscriptFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TranscriptFormat() {}

    public static String toJson(String engineId, String language, long durationMs, List<Segment> segments) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("engine", engineId);
        root.put("language", language);
        root.put("durationMs", durationMs);
        ArrayNode arr = root.putArray("segments");
        for (Segment s : segments) {
            ObjectNode n = arr.addObject();
            n.put("startMs", s.startMs());
            n.put("endMs", s.endMs());
            n.put("text", s.text());
            if (s.speaker() != null) n.put("speaker", s.speaker());
        }
        return MAPPER.writeValueAsString(root);
    }

    public static String toSrt(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            sb.append(i + 1).append('\n')
                    .append(srtTime(s.startMs())).append(" --> ").append(srtTime(s.endMs())).append('\n')
                    .append(s.text()).append('\n').append('\n');
        }
        return sb.toString();
    }

    public static String toTxt(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append('[').append(timecode(s.startMs())).append("] ").append(s.text()).append('\n');
        }
        return sb.toString();
    }

    public static String timecode(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return "%d:%02d:%02d".formatted(h, m, s);
        return "%02d:%02d".formatted(m, s);
    }

    private static String srtTime(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1000;
        long milli = ms % 1000;
        return "%02d:%02d:%02d,%03d".formatted(h, m, s, milli);
    }
}
```

- [ ] **Step 3: 实现 SegmentMerger**

`engine/SegmentMerger.java`：

```java
package com.waveminer.waveminer.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SegmentMerger {

    private SegmentMerger() {}

    public static List<Segment> merge(List<Segment> segments, long gapMs) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::startMs));
        List<Segment> out = new ArrayList<>();
        for (Segment cur : sorted) {
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }
            Segment prev = out.get(out.size() - 1);
            boolean speakerConflict = cur.speaker() != null && prev.speaker() != null
                    && !cur.speaker().equals(prev.speaker());
            if (cur.startMs() - prev.endMs() < gapMs && !speakerConflict) {
                Integer speaker = prev.speaker() != null ? prev.speaker() : cur.speaker();
                out.set(out.size() - 1, new Segment(prev.startMs(), Math.max(prev.endMs(), cur.endMs()),
                        prev.text() + cur.text(), speaker));
            } else {
                out.add(cur);
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 全量测试通过**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend && git commit -m "feat: 转写产物格式器与分段归并"
```

---

