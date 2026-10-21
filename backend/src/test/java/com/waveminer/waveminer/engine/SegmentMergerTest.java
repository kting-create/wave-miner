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
