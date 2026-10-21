package com.waveminer.waveminer.media;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioSegmenterTest {

    private static FfmpegService.Silence sil(double s, double e) {
        return new FfmpegService.Silence(s, e);
    }

    @Test
    void shortAudioNeedsNoCut() {
        assertThat(AudioSegmenter.cutPoints(120, List.of(), 500)).isEmpty();
    }

    @Test
    void cutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(490, 494)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 900.0);
    }

    @Test
    void multipleCutsAtSilenceMidpoints() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(sil(490, 494), sil(980, 984)), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 492.0);
        assertThat(cuts.get(1)).containsExactly(492.0, 982.0);
        assertThat(cuts.get(2)).containsExactly(982.0, 1200.0);
    }

    @Test
    void noSilenceForcesHardCut() {
        List<double[]> cuts = AudioSegmenter.cutPoints(1200, List.of(), 500);
        assertThat(cuts).hasSize(3);
        assertThat(cuts.get(0)).containsExactly(0.0, 500.0);
        assertThat(cuts.get(1)).containsExactly(500.0, 1000.0);
        assertThat(cuts.get(2)).containsExactly(1000.0, 1200.0);
    }

    @Test
    void silenceNearWindowEndPreferred() {
        List<double[]> cuts = AudioSegmenter.cutPoints(900, List.of(sil(298, 302), sil(478, 482)), 500);
        assertThat(cuts).hasSize(2);
        assertThat(cuts.get(0)).containsExactly(0.0, 480.0);
        assertThat(cuts.get(1)).containsExactly(480.0, 900.0);
    }

    @Test
    void parseSilenceLines() {
        assertThat(FfmpegService.parseSilenceStart("[silencedetect @ 0x0] silence_start: 12.3456")).isEqualTo(12.3456);
        assertThat(FfmpegService.parseSilenceEnd(
                "[silencedetect @ 0x0] silence_end: 13.5 | silence_duration: 1.154")).isEqualTo(13.5);
    }
}
