package com.waveminer.waveminer.store;

import com.waveminer.waveminer.config.AppProperties;
import com.waveminer.waveminer.media.MediaTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {

    @TempDir
    Path tmp;

    private ArtifactStore store() {
        AppProperties props = new AppProperties();
        props.setDataDir(tmp.resolve("data").toString());
        return new ArtifactStore(props);
    }

    @Test
    void taskDirCreatedLazily() {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("abc");
    }

    @Test
    void rejectsTraversalNames() {
        ArtifactStore s = store();
        assertThat(s.isAllowedArtifactName("audio.m4a")).isTrue();
        assertThat(s.isAllowedArtifactName("source.mp4")).isTrue();
        assertThat(s.isAllowedArtifactName("source.mkv")).isTrue();
        assertThat(s.isAllowedArtifactName("../t2/source.mp4")).isFalse();
        assertThat(s.isAllowedArtifactName("sub/transcript.json")).isFalse();
        assertThat(s.isAllowedArtifactName("source..mp4")).isFalse();
        assertThat(s.isAllowedArtifactName("evil.exe")).isFalse();
    }

    @Test
    void findsSourceFileAndListsArtifacts() throws Exception {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        Files.writeString(dir.resolve("source.mp4"), "video");
        Files.writeString(dir.resolve("audio.m4a"), "audio");
        Files.writeString(dir.resolve("notes.txt"), "ignored");

        assertThat(ArtifactStore.findSourceFile(dir)).hasValueSatisfying(p ->
                assertThat(p.getFileName().toString()).isEqualTo("source.mp4"));

        List<ArtifactInfo> artifacts = s.listArtifacts("abc");
        assertThat(artifacts).extracting(ArtifactInfo::name)
                .containsExactlyInAnyOrder("source.mp4", "audio.m4a");
        assertThat(artifacts).allSatisfy(a -> assertThat(a.sizeBytes()).isGreaterThan(0));
    }

    @Test
    void transcriptAndEngineRawAllowed() throws Exception {
        ArtifactStore s = store();
        Path dir = s.taskDir("abc");
        Files.createDirectories(dir.resolve("engine_raw"));
        Files.writeString(dir.resolve("transcript.json"), "{}");
        Files.writeString(dir.resolve("transcript.srt"), "");
        Files.writeString(dir.resolve("transcript.txt"), "");
        Files.writeString(dir.resolve("engine_raw").resolve("minimax.json"), "{}");
        Files.writeString(dir.resolve("engine_raw").resolve("bad name!.json"), "{}");

        assertThat(s.isAllowedArtifactName("transcript.json")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.srt")).isTrue();
        assertThat(s.isAllowedArtifactName("transcript.txt")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/minimax.json")).isTrue();
        assertThat(s.isAllowedArtifactName("engine_raw/bad name!.json")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/../../etc")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw/")).isFalse();
        assertThat(s.isAllowedArtifactName("engine_raw")).isFalse();

        List<ArtifactInfo> artifacts = s.listArtifacts("abc");
        assertThat(artifacts).extracting(ArtifactInfo::name).contains(
                "transcript.json", "transcript.srt", "transcript.txt", "engine_raw/minimax.json");
    }

    @Test
    void extensionHelpers() {
        assertThat(MediaTypes.extension("A.MP4")).isEqualTo("mp4");
        assertThat(MediaTypes.extension("noext")).isEmpty();
        assertThat(MediaTypes.isVideo("x.mov")).isTrue();
        assertThat(MediaTypes.isVideo("x.mp3")).isFalse();
        assertThat(MediaTypes.isMedia("x.flac")).isTrue();
        assertThat(MediaTypes.isMedia("x.txt")).isFalse();
    }
}
