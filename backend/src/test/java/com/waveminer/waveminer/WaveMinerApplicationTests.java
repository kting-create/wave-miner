package com.waveminer.waveminer;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class WaveMinerApplicationTests {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", () -> tmp.toString());
        // 非池化连接用完即释放 SQLite 文件句柄，Windows 上 @TempDir 才能删除库文件
        r.add("spring.datasource.type", () -> "org.springframework.jdbc.datasource.DriverManagerDataSource");
    }

    @Test
    void contextLoads() {}
}
