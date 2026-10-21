package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.YtDlpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
        r.add("app.max-concurrent-tasks", () -> "1");
    }

    @Autowired MockMvc mvc;
    @Autowired com.waveminer.waveminer.store.ArtifactStore store;
    @Autowired com.waveminer.waveminer.config.AppProperties props;

    @MockitoBean FfmpegService ffmpeg;
    @MockitoBean YtDlpService ytDlp;

    private static MockMultipartFile mp4() {
        return new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
    }

    private String waitCompleted(String id) throws Exception {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(get("/api/tasks/" + id))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        return id;
    }

    private String createTask(MvcResult created) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void createUploadTaskRunsToCompletion() throws Exception {
        Mockito.doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.writeString(out, "audio");
            return null;
        }).when(ffmpeg).extractAudio(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.when(ffmpeg.hasAudioStream(Mockito.any())).thenReturn(true);

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .file(mp4())
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sourceType").value("UPLOAD"))
                .andReturn();
        String id = createTask(created);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(get("/api/tasks/" + id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPLETED"))
                        .andExpect(jsonPath("$.stages[0].status").value("DONE"))
                        .andExpect(jsonPath("$.stages[0].artifactName").value("audio.m4a"))
                        .andExpect(jsonPath("$.artifacts.length()").value(2)));
    }

    @Test
    void createUrlTaskWithMockedYtDlp() throws Exception {
        Mockito.doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(ytDlp).download(Mockito.anyString(), Mockito.any(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .param("url", "https://example.com/watch/1")
                        .param("stages", "DOWNLOAD"))
                .andExpect(status().isOk())
                .andReturn();
        waitCompleted(createTask(created));
    }

    @Test
    void validationErrorReturns400WithChineseMessage() throws Exception {
        mvc.perform(multipart("/api/tasks")
                        .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1}))
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("文件类型")));
    }

    @Test
    void detailNotFound() throws Exception {
        // 合法 UUID 格式但任务不存在 → 404；非法格式 → 400（见 malformedIdRejectedWith400）
        mvc.perform(get("/api/tasks/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedIdRejectedWith400() throws Exception {
        mvc.perform(get("/api/tasks/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("非法的任务 ID"));
        mvc.perform(get("/api/tasks/nope/artifacts/source.mp4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidArtifactNameReturns400() throws Exception {
        Mockito.doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.writeString(dir.resolve("source.mp4"), "v");
            return null;
        }).when(ytDlp).download(Mockito.anyString(), Mockito.any(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .param("url", "https://example.com/bad-name")
                        .param("stages", "DOWNLOAD"))
                .andExpect(status().isOk()).andReturn();
        String id = waitCompleted(createTask(created));

        mvc.perform(get("/api/tasks/" + id + "/artifacts/foo.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("非法的产物名称"));
    }

    @Test
    void artifactForUnknownTaskReturns404WithoutCreatingDir() throws Exception {
        String unknown = "00000000-0000-0000-0000-000000000000";
        mvc.perform(get("/api/tasks/" + unknown + "/artifacts/source.mp4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("任务不存在"));
        // 404 不得留下孤儿空目录（taskDir 惰性建目录，故用数据目录根推算路径断言）
        Path root = Path.of(props.getDataDir()).toAbsolutePath().normalize();
        assertThat(Files.notExists(root.resolve(unknown))).isTrue();
    }

    @Test
    void unknownApiPathReturns404() throws Exception {
        mvc.perform(get("/api/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("接口不存在"));
    }

    @Test
    void artifactDownloadSupportsRange() throws Exception {
        Mockito.doAnswer(inv -> {
            Path dir = inv.getArgument(1);
            Files.write(dir.resolve("source.mp4"), new byte[1000]);
            return null;
        }).when(ytDlp).download(Mockito.anyString(), Mockito.any(), Mockito.any());

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .param("url", "https://example.com/x.mp4")
                        .param("stages", "DOWNLOAD"))
                .andExpect(status().isOk()).andReturn();
        String id = waitCompleted(createTask(created));

        mvc.perform(get("/api/tasks/" + id + "/artifacts/source.mp4"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"));

        mvc.perform(get("/api/tasks/" + id + "/artifacts/source.mp4").header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-99/1000"));
    }

    @Test
    void artifactTraversalRejected() throws Exception {
        mvc.perform(get("/api/tasks/whatever/artifacts/..%2F..%2Fsecret.txt"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listAndDelete() throws Exception {
        Mockito.when(ffmpeg.hasAudioStream(Mockito.any())).thenReturn(true);
        mvc.perform(multipart("/api/tasks").file(mp4()).param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk());
        String body = mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.items[0].id");
        assertThat(id).isNotBlank();
        waitCompleted(id);
        Path dir = store.taskDir(id);
        mvc.perform(delete("/api/tasks/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isNotFound());
        assertThat(Files.notExists(dir)).isTrue();
    }

    @Test
    void retryOnlyForFailedOrCancelled() throws Exception {
        Mockito.when(ffmpeg.hasAudioStream(Mockito.any())).thenReturn(true);
        mvc.perform(multipart("/api/tasks").file(mp4()).param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk());
        String body = mvc.perform(get("/api/tasks")).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.items[0].id");

        // 任务可能已在跑或已完成；两者都不允许 retry → 409
        mvc.perform(post("/api/tasks/" + id + "/retry"))
                .andExpect(status().isConflict());
    }
}
