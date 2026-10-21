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
        assertThat(root.get("segments").get(2).has("speaker")).isFalse();
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
