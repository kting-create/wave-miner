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

仅改后端时可跳过前端构建与拷贝：

```bash
cd backend && ./mvnw -q package -P '!frontend'
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

## 转写（M3+M4）

任务创建时勾选“转写”，选择引擎与语言，任务完成后详情页提供时间轴对照与 `transcript.json/.srt/.txt` 三种产物下载。

### 引擎与 API Key

| 引擎 | 引擎 ID | 限制 | Key 配置 |
|---|---|---|---|
| MiniMax 语音识别 | `minimax` | ≤500 秒/50MB（超长自动静音切分） | 设置页 `minimax.apiKey` 或环境变量 `WAVEMINER_MINIMAX_API_KEY` |
| 阿里云百炼录音文件转写 | `aliyun-filetrans` | ≤12 小时/2GB | 设置页 `aliyun.apiKey` 或环境变量 `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`） |

- MiniMax baseUrl 默认中国站 `https://api.minimax.cn`，可用 `APP_MINIMAX_BASE_URL` 覆盖（国际站 `https://api.minimax.io`）。
- 阿里云 baseUrl 默认 `https://dashscope.aliyuncs.com/api/v1`，可用 `APP_ALIYUN_BASE_URL` 指向业务空间专属域名；默认模型 `qwen-audio-3.0-asr-flash-filetrans`（`APP_ALIYUN_MODEL` 可换 `paraformer-v2` 等）。
- 本地文件转写（阿里云）走 DashScope 上传凭证 + `oss://` 临时 URL，凭证限流 100 QPS。

### 相关配置（application.yml / 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.engine-http-timeout-seconds` | 600 | 引擎单次 HTTP 请求超时 |
| `app.engine-retry-attempts` | 3 | 429/5xx/网络错误重试次数 |
| `app.transcribe-poll-interval-seconds` | 5 | 阿里云任务轮询间隔 |
| `app.transcribe-timeout-seconds` | 7200 | 转写任务整体超时 |
| `app.silence-threshold-db` / `app.silence-min-duration-sec` | -35 / 0.6 | 静音切分阈值 |
| `app.merge-gap-ms` | 800 | 跨分片段落合并间隙 |

### 进度推送

任务详情页使用 SSE（`GET /api/tasks/{id}/events`），断开自动回退轮询。访问令牌启用时 EventSource 走 `?token=`。

## 测试

```bash
cd backend && ./mvnw test      # 后端全部单元/集成测试（不依赖真实 ffmpeg/yt-dlp，跨平台可跑）
cd frontend && npm run test    # 前端 vitest
```

## Windows 上运行/开发

- JDK 21、Node.js 20+ 安装后，用 `mvnw.cmd` 替代 `./mvnw`（`mvnw.cmd test`、`mvnw.cmd package`）。
- 安装工具（任选其一）：
  - `winget install Gyan.FFmpeg yt-dlp.yt-dlp`（装完确认 `ffmpeg -version`、`yt-dlp --version` 在新开的终端可用）
  - 或手动下载 ffmpeg（gyan.dev 构建）与 `yt-dlp.exe`，放到固定目录
- 若工具不在 PATH，用环境变量指定完整路径（注意反斜杠或用引号包路径）：

```bat
set APP_FFMPEG_PATH=C:\tools\ffmpeg\bin\ffmpeg.exe
set APP_FFPROBE_PATH=C:\tools\ffmpeg\bin\ffprobe.exe
set APP_YT_DLP_PATH=C:\tools\yt-dlp.exe
java -jar backend\target\wave-miner-0.0.1-SNAPSHOT.jar
```

- 产物、SQLite 建议放本地 NTFS 盘（网络盘/OneDrive 同步目录会导致文件锁与数据库损坏风险）。
- 任务删除采用协作式取消：Windows 上若外部进程刚被杀，个别文件可能短暂被占用导致删除报错，重试即可。

## Linux 服务器部署

- 依赖：`sudo apt install ffmpeg`（或 `dnf install ffmpeg yt-dlp`）、`sudo curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && sudo chmod a+rx /usr/local/bin/yt-dlp`；需要 JDK 21（`apt install openjdk-21-jre`）。
- 打包好的 jar 是自包含的（内嵌前端），服务器无需 Node.js；无界面纯后台运行即可，媒体播放发生在用户浏览器里。
- **局域网/远程访问必须两件事一起做**：绑定地址放开 + 访问令牌（默认只绑 127.0.0.1）：

```bash
SERVER_ADDRESS=0.0.0.0 APP_ACCESS_TOKEN=改成你的长随机串 java -jar wave-miner-0.0.1-SNAPSHOT.jar
```

- systemd 服务示例 `/etc/systemd/system/wave-miner.service`：

```ini
[Unit]
Description=wave-miner
After=network.target

[Service]
User=waveminer
WorkingDirectory=/opt/wave-miner
Environment=APP_DATA_DIR=/opt/wave-miner/data
Environment=APP_ACCESS_TOKEN=改成你的长随机串
Environment=SERVER_ADDRESS=0.0.0.0
ExecStart=/usr/bin/java -jar /opt/wave-miner/wave-miner.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

- 传输建议走 HTTPS 反向代理（nginx/caddy）后再暴露公网；直接暴露请务必设置强令牌。
- 平台链接解析依赖 yt-dlp 对目标站点的支持，服务器需能访问对应站点；更新 yt-dlp 即可跟进站点变化（`yt-dlp -U`）。

## 多机分工说明

本工具全部组件（Java/Nginx/SQLite/ffmpeg/yt-dlp）在 Windows 与 Linux 上都受支持，没有必须留在 Windows 的组件。若个别视频站点解析在 Linux 服务器网络环境不可用（区域限制），可以把下载任务放在 Windows 机器的实例上跑，两台机器共用同一套 jar 包与配置方式，仅环境变量不同。
