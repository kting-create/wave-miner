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
