# wave-miner

个人/小团队的音视频内容挖掘工具：视频下载、音频提取、带时间戳的文字转写（转写引擎在后续版本提供）。功能可独立使用，也可组合成流水线。

## 依赖

- JDK 21、Node.js 20+、Maven（或使用自带 mvnw）
- ffmpeg / ffprobe（`brew install ffmpeg`）
- yt-dlp（`brew install yt-dlp`）

## 开发运行

```bash
# 后端（127.0.0.1:8080）
cd backend && ./mvnw spring-boot:run

# 前端（5173，代理 /api 到 8080）
cd frontend && npm install && npm run dev
```

## 打包运行（单 jar 内嵌前端）

```bash
cd frontend && npm install && npm run build
cd ../backend && ./mvnw -q package
java -jar backend/target/wave-miner-0.0.1-SNAPSHOT.jar
# 打开 http://127.0.0.1:8080
```

## 配置（backend/src/main/resources/application.yml 或环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.data-dir` | `./data` | 任务数据目录（SQLite + 产物） |
| `app.max-concurrent-tasks` | `2` | 同时执行的任务数 |
| `app.ffmpeg-path` / `app.ffprobe-path` / `app.yt-dlp-path` | PATH | 外部工具路径 |
| `app.process-timeout-seconds` | `1800` | 外部进程超时 |
| `app.access-token` | 空 | 非空时所有 API 需带 `X-Access-Token` 头（或 `?token=`） |

环境变量映射：`APP_DATA_DIR`、`APP_MAX_CONCURRENT_TASKS`、`APP_FFMPEG_PATH`、`APP_YT_DLP_PATH`、`APP_ACCESS_TOKEN` 等。

## 测试

```bash
cd backend && ./mvnw test      # 后端全部单元/集成测试（不依赖真实 ffmpeg/yt-dlp）
cd frontend && npm run test    # 前端 vitest
```
