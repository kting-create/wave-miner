package com.waveminer.waveminer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.waveminer.waveminer.media.FfmpegService;
import com.waveminer.waveminer.media.YtDlpService;
import com.waveminer.waveminer.pipeline.TaskEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TaskSseApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
        // 非池化连接用完即释放 SQLite 文件句柄，Windows 上 @TempDir 才能删除库文件
        r.add("spring.datasource.type", () -> "org.springframework.jdbc.datasource.DriverManagerDataSource");
    }

    @Autowired MockMvc mvc;
    @Autowired TaskEventPublisher publisher;
    @Autowired TaskSseRegistry registry;

    @MockitoBean FfmpegService ffmpeg;
    @MockitoBean YtDlpService ytDlp;

    @Test
    void unknownTaskReturns404() throws Exception {
        mvc.perform(get("/api/tasks/no-such-id/events")).andExpect(status().isNotFound());
    }

    @Test
    void subscribesAndReceivesEventsUntilTerminal() throws Exception {
        Mockito.doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.writeString(out, "audio");
            return null;
        }).when(ffmpeg).extractAudio(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.when(ffmpeg.hasAudioStream(Mockito.any())).thenReturn(true);

        MvcResult created = mvc.perform(multipart("/api/tasks")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1, 2, 3, 4}))
                        .param("stages", "AUDIO_EXTRACT"))
                .andExpect(status().isOk()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        // 等后台管线走完，避免其事件与手动发布的事件竞争终态完成时机
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                mvc.perform(get("/api/tasks/" + id))
                        .andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.status").value("COMPLETED")));

        MvcResult sse = mvc.perform(get("/api/tasks/" + id + "/events")
                        .accept("text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        publisher.publish(TaskEventPublisher.TaskEvent.progress(id, "TRANSCRIBE", "进度你好"));
        publisher.publish(TaskEventPublisher.TaskEvent.task(id, "COMPLETED"));

        // Spring 7 移除了 isAsyncComplete()：终态 complete() 走 DeferredResult.dispatch()，
        // 等待信号是 dispatch latch（asyncDispatch 内部经 getAsyncResult() 检查）
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                sse.getAsyncResult(50);
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        });
        assertThat(registry.emitterCount(id)).isZero();
        // 响应 Content-Type 无 charset（SSE 规范强制 UTF-8），getContentAsString() 会按 ISO-8859-1 误读中文
        byte[] raw = mvc.perform(asyncDispatch(sse)).andReturn().getResponse().getContentAsByteArray();
        String body = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:progress");
        assertThat(body).contains("进度你好");
        assertThat(body).contains("\"type\":\"connected\"");
        assertThat(body).contains("\"type\":\"task\"");
    }
}
