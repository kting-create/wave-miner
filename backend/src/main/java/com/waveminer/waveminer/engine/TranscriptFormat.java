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
