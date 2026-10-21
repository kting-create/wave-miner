package com.waveminer.waveminer.api;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettingApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
        // 非池化连接用完即释放 SQLite 文件句柄，Windows 上 @TempDir 才能删除库文件
        r.add("spring.datasource.type", () -> "org.springframework.jdbc.datasource.DriverManagerDataSource");
    }

    @Autowired MockMvc mvc;

    @Test
    void putThenGetMasksValue() throws Exception {
        mvc.perform(put("/api/settings")
                        .contentType("application/json")
                        .content("{\"key\":\"minimax.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("minimax.apiKey"))
                .andExpect(jsonPath("$.value").value("sk****abcd"))
                .andExpect(jsonPath("$.configured").value(true));

        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].value").value("sk****abcd"))
                .andExpect(jsonPath("$[?(@.key=='minimax.apiKey')].configured").value(true));
    }

    @Test
    void blankValueClearsKey() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"sk-1234567890abcd\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"aliyun.apiKey\",\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void unknownKeyRejected() throws Exception {
        mvc.perform(put("/api/settings").contentType("application/json")
                        .content("{\"key\":\"hacker.key\",\"value\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未知的设置项")));
    }
}
