# wave-miner 设计文档

日期：2026-10-21
状态：已与需求方确认总体方案（单体 Spring Boot + 流水线勾选 + 可插拔转写引擎）

## 1. 目标与范围

wave-miner 是面向个人/小团队的音视频内容挖掘 Web 应用。输入视频链接 / 视频文件 / 音频文件，完成三类功能，每个功能可独立使用、可自由组合：

1. **下载**：视频链接（直链 URL 或平台页面链接）→ 视频文件
2. **音频提取**：视频文件 → 音频文件
3. **转写**：音频/视频 → 带时间戳的句级文字对照（如 `0s-5s: 1111111`，`9s-20s: 2222222`）

非目标（当前版本）：多用户/权限体系、分布式部署、词级时间戳 UI、字幕翻译。

## 2. 技术栈与运行形态

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.x (Maven) | 单体应用，一个 jar 部署 |
| 任务执行 | JDK 21 虚拟线程 | IO 密集（进程调用+HTTP），虚拟线程最契合；用 `Semaphore` 限制并发（默认 2，可配置） |
| 持久化 | SQLite（`sqlite-jdbc`）+ Spring JDBC | 单文件零运维；表 `task`、`task_stage`、`app_setting` |
| 产物存储 | 本地磁盘 `data/tasks/{taskId}/` | 与 SQLite 分离，可整体备份 |
| 前端 | Vue 3 + Vite + Element Plus + Pinia | SPA，构建产物由 Spring Boot 静态托管 |
| 进度推送 | SSE | 单向推送足够，比 WebSocket 简单可靠 |
| 外部工具 | ffmpeg / ffprobe、yt-dlp | ProcessBuilder 调用，路径可配置，启动时健康检查 |
| 安全基线 | 默认绑定 `127.0.0.1`；可选访问令牌 | 个人项目不做用户体系；产物接口做路径穿越防护 |

## 3. 架构

```
┌──────────────────────────────────────────────────────┐
│  Vue 3 SPA                                            │
│  新建任务 / 任务列表 / 任务详情(时间轴对照) / 设置        │
└──────────────────┬───────────────────────────────────┘
                   │ REST + SSE(进度)
┌──────────────────▼───────────────────────────────────┐
│  Spring Boot 3 (Java 21)                              │
│  ├─ api/        TaskController, SettingController     │
│  ├─ pipeline/   PipelineService(编排), TaskExecutor   │
│  ├─ stage/      DownloadStage / AudioExtractStage /   │
│  │              TranscribeStage                       │
│  ├─ engine/     TranscriptionEngine(SPI)              │
│  │              ├─ MinimaxAsrEngine                   │
│  │              ├─ AliyunFiletransEngine              │
│  │              └─ (预留 OpenAI 兼容模板引擎)           │
│  ├─ media/      FfmpegService, YtDlpService           │
│  └─ store/      TaskRepository(SQLite), ArtifactStore │
└──────────────────────────────────────────────────────┘
外部依赖: ffmpeg · yt-dlp · MiniMax API · 阿里云百炼 API
```

## 4. 流水线模型

### 4.1 三个 Stage

| Stage | 输入 | 输出产物 | 实现依赖 |
|---|---|---|---|
| DOWNLOAD | 视频链接（直链或平台页） | `source.<ext>` | yt-dlp / HTTP 下载 |
| AUDIO_EXTRACT | 视频文件 | `audio.m4a` | ffmpeg |
| TRANSCRIBE | 音频或视频文件 | `transcript.json/.srt/.txt` | TranscriptionEngine |

### 4.2 组合矩阵

| 勾选 | 输入 | 行为 |
|---|---|---|
| ① | 链接 | 只下载视频 |
| ② | 上传视频 | 只提音频 |
| ③ | 上传音频/视频 | 只转写 |
| ①②③ | 链接 | 全流程 |
| ①③ | 链接 | 下载后直接转写视频 |
| ①② | 链接 | 下载+提音频，不转写 |
| ②③ | 上传视频 | 提音频后转写 |

**输入解析规则**：前端按勾选组合动态校验——DOWNLOAD 必须有链接；AUDIO_EXTRACT/TRANSCRIBE 的输入自动取上游产物，无上游时要求用户上传对应类型文件（前端按扩展名/探测结果提示）。后端对每条组合再次校验（不信任前端）。

### 4.3 产物目录

```
data/tasks/{taskId}/
├── source.<ext>          下载的视频或上传的原始文件（保留原扩展名）
├── audio.m4a             提取的音频（aac 单声道 16kHz，转写友好且省流量）
├── transcript.json       句级分段：{segments:[{startMs,endMs,text,speaker?}], engine, language, durationMs}
├── transcript.srt        SRT 字幕
├── transcript.txt        纯文本（带 [mm:ss] 前缀的分段文本）
└── engine_raw/           各引擎原始响应存档（转写任务才有，供质量对比）
```

## 5. 转写引擎层（SPI）

### 5.1 接口

```java
public interface TranscriptionEngine {
    String id();                                   // "minimax" / "aliyun-filetrans"
    EngineInfo info();                             // 名称、支持语言、maxDurationSec、maxSizeBytes、是否支持说话人分离
    TranscribeResult transcribe(Path mediaFile, TranscribeOptions options, ProgressCallback progress);
}
// TranscribeOptions: language(auto/zh/en/...)、diarize(boolean)、热词/prompt(预留)
// TranscribeResult:  List<Segment{startMs, endMs, text, Integer speaker}> —— 统一句级
```

所有引擎实现内部完成**归一化**，上层只面对统一格式。新增引擎=新增实现类+配置，零核心改动。

### 5.2 MiniMax 引擎（已核实官方 OpenAPI）

- `POST {baseUrl}/v1/speech_to_text`，`Authorization: Bearer {key}`，`multipart/form-data`
- 字段：`model=asr-1.0`；header `language`（BCP-47：zh/yue/en/ja…，留空=混合语种）；`file`；`response_format=verbose_json`；`timestamp_level=sentence`
- verbose_json 返回：`segments[]{id,start,end,speaker,text}`（秒，浮点）→ 归一化为毫秒句级分段
- 官方限制：时长 ≤500 秒、大小 ≤50MB；格式 wav/aiff/flac/m4a/mp3/aac/opus/ogg
- 国际站 baseUrl `https://api.minimax.io`；中国站 `https://api.minimaxi.com`（baseUrl 可配置，默认中国站）
- 官方文档：https://platform.minimax.io/docs/api-reference/speech-to-text

### 5.3 阿里云百炼引擎（已核实官方 REST 文档）

- 模型：默认 `qwen-audio-3.0-asr-flash-filetrans`（异步文件转写，≤12 小时/2GB，说话人分离、热词、prompt）；备选 `paraformer-v2`
- 流程（DashScope 异步文件转写通用模式）：
  1. **本地文件 → 公网 URL**：调用 DashScope 文件上传凭证接口获取临时上传凭证，上传后得到临时 URL（API 输入不接受二进制流/Base64，必须公网 URL）
  2. **提交任务**：`POST /api/v1/services/audio/asr/transcription`，header `X-DashScope-Async: enable`，body 含 `model`、`input.file_urls`、`parameters`（`language_hints`、`diarization_enabled` 等）
  3. **轮询**：`POST /api/v1/tasks/{task_id}`，直至 `SUCCEEDED/FAILED`
  4. **取结果**：下载 `transcription_url` 指向的 JSON（24h 有效），归一化 `transcripts[].sentences[]{begin_time,end_time(ms),text,speaker_id,words[]}` → 句级分段
- baseUrl 支持工作空间专属域名，可配置；API Key 走设置页，运行时从环境变量或 SQLite 读取，不硬编码
- 官方文档：https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api 、
  https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide 、
  官方示例库 https://github.com/aliyun/alibabacloud-bailian-speech-demo
- 实现前必须以上述官方文档+官方示例库核对上传凭证接口与 filetrans 请求字段（以官方为准）

### 5.4 小米 MiMo 与后续引擎

- 调研结论（2026-10-21）：小米 MiMo 开放平台存在（platform.xiaomimimo.com），但未检索到任何公开 ASR/语音识别 API 文档 → **不内置**
- 引擎层保留两个扩展点：
  1. SPI 实现类（新引擎标准路径）
  2. **OpenAI 兼容模板引擎**：通用实现 `/v1/audio/transcriptions`（verbose_json），可配置 baseUrl/model/key——SiliconFlow（SenseVoice）、Groq（Whisper large v3）等「更高质量」候选都兼容此协议，配置即可接入，无需写代码
- 状态：OpenAI 兼容模板引擎列入 M6（先跑通内置双引擎）

### 5.5 超长音频统一处理

- `EngineInfo.maxDurationSec` 声明上限（MiniMax 500s；阿里云 filetrans 12h）
- 超限时通用分片器接管：ffmpeg `silencedetect` 找静音点 → 在静音处切成 ≤上限的片段 → 逐片转写 → 时间戳整体偏移归并 → 输出统一结果
- 归并规则：片段间 <800ms 间隙且同无说话人冲突时合并为同段；段落文本以句末标点/静音边界对齐

### 5.6 引擎重试与限流

- HTTP 429/5xx：指数退避重试（默认 3 次，可配置）；其他错误码直接失败并记录原始错误
- 引擎原始响应 JSON 存档到任务目录（`engine_raw/`），便于对比各家质量（用户核心诉求：探索更高质量的引擎）

## 6. 任务模型与执行

### 6.1 实体

```
Task: id(uuid), sourceType(URL/UPLOAD), sourceRef, title,
      stages(json), engineId, language, diarize,
      status, errorMessage, createdAt, startedAt, finishedAt
TaskStage: taskId, stageType, status, detail, artifactPath
```

### 6.2 状态机

```
Task:  PENDING → RUNNING → COMPLETED
                     ├→ FAILED   (retry 从首个非 DONE 的 stage 续跑)
                     └→ CANCELLED
Stage: PENDING → RUNNING → DONE / SKIPPED / FAILED
```

### 6.3 执行器

- `TaskExecutor` 接口 + `VirtualThreadTaskExecutor` 默认实现：虚拟线程逐任务串行执行其 stage 链；全局 `Semaphore` 限制同时 RUNNING 的任务数（默认 2）
- 进度回调：stage 级状态变更 + 转写字数/分片进度 → 内存事件总线 → SSE 广播 + SQLite 持久化
- 应用重启恢复：启动扫描，RUNNING/PENDING → FAILED（errorMessage 注明"应用重启中断，可重试"）

## 7. HTTP API

| 方法/路径 | 说明 |
|---|---|
| `POST /api/tasks` | multipart：`url` 或 `file`；`stages`（CSV）；`engineId`；`language`；`diarize`；创建任务并排队 |
| `GET /api/tasks` | 分页列表，`?status=&page=&size=`，按创建时间倒序 |
| `GET /api/tasks/{id}` | 详情（含各 stage 状态与产物清单） |
| `POST /api/tasks/{id}/retry` / `/cancel` | 重试（从失败 stage 续跑）/ 取消（RUNNING 中走协作式中断标记） |
| `DELETE /api/tasks/{id}` | 删除任务记录+产物目录（`?keepFiles=true` 可保留） |
| `GET /api/tasks/{id}/artifacts/{name}` | 产物下载/内联播放；名称白名单校验防路径穿越；支持 HTTP Range（视频/音频拖动播放） |
| `GET /api/tasks/{id}/events` | SSE 进度流 |
| `GET /api/engines` | 可用引擎及能力（供前端下拉与限制提示） |
| `GET/PUT /api/settings` | API keys（返回脱敏）、ffmpeg/yt-dlp 路径、并发数、分片参数 |

约定：错误统一 `{code, message}`；上传大小限制可配（默认 2GB）；全部接口要求 `X-Access-Token`（设置页生成，默认开启，本机使用可关闭）。

## 8. 外部工具封装（media/）

- **FfmpegService**：`extractAudio(video, out)`（`-vn -ac 1 -ar 16000 -c:a aac`）；`probeDuration`（ffprobe json）；`detectSilences`（`silencedetect`，阈值 -35dB/0.6s 可配）；统一 30s 进程超时可配、stderr 摘要入错误信息
- **YtDlpService**：`download(url, dir)`——`--no-playlist -f "bv*+ba/b" -o source.%(ext)s`；失败输出 stderr 关键行；**直链回退**：URL 探测（HEAD Content-Type 为音视频 mime 或扩展名命中）且 yt-dlp 不适用时走 HTTP 下载
- **启动健康检查**：`ffmpeg -version` / `yt-dlp --version`，失败则前端设置页红条提示与安装指引
- 进程调用统一记录命令行与退出码到任务日志（脱敏：不含 API key）

## 9. 前端

| 页面 | 要点 |
|---|---|
| 新建任务 | 输入源 Tab（链接/上传）；stage 勾选卡片（依赖联动）；引擎+语言选择（数据来自 `/api/engines`，显示时长/大小限制）；提交后跳转详情 |
| 任务列表 | 状态徽标、stage 进度条、耗时；操作：查看/重试/取消/删除 |
| 任务详情 | 产物卡片（下载/内联播放）；**时间轴对照视图**：左侧媒体播放器（video/audio 标签，点击段落 `currentTime= startMs/1000` 双向联动高亮当前段），右侧分段列表（时间+文本+说话人）；引擎原始 JSON 查看入口 |
| 设置 | 各引擎 API key、工具路径、并发、访问令牌、数据目录 |

SSE 断线自动重连；上传用分片/流式（axios onUploadProgress 显示进度）。

## 10. 错误处理原则

1. stage 失败即任务 FAILED，已完成产物保留，错误信息=工具 stderr/API 错误摘要
2. 可重试错误（网络/429/5xx）自动重试后仍失败才标 FAILED
3. 磁盘空间预检（任务创建时检查剩余空间 > 上传/预估大小 ×1.5）
4. 所有外部交互有超时；下载/上传中断可整体 retry
5. 用户可见错误一律中文可读，技术细节折叠展示

## 11. 测试策略

- **单元**：引擎归一化（用官方文档中的真实响应样例做 fixture）、超长切分时间戳归并、SRT/TXT 生成、状态机流转、组合校验规则
- **集成**：WireMock 模拟两家 ASR API（含失败/重试/超时路径）；本地小样本 mp4 跑 AUDIO_EXTRACT+TRANSCRIBE 全链（mock 引擎）；SQLite 仓储层
- **验收（手动，真实 API）**：B 站链接全流程、直链 mp4、纯音频文件、>500s 音频走 MiniMax 切分、中英混合转写

## 12. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M1 | 骨架：Spring Boot+SQLite+任务模型+API+前端骨架 | 创建/查询/删除任务，假执行 |
| M2 | media 层：ffmpeg/yt-dlp + DOWNLOAD/AUDIO_EXTRACT stage | 真实链接出视频、出音频 |
| M3 | MiniMax 引擎 + TRANSCRIBE stage + 三种产物 | 真实转写+对照 JSON/SRT/TXT |
| M4 | 阿里云引擎（上传凭证+filetrans） | 双引擎可切换 |
| M5 | SSE 进度 + 任务详情时间轴对照 UI | 播放联动浏览体验完整 |
| M6 | 超长切分、设置页、OpenAI 兼容模板引擎、访问令牌 | >500s 音频、第三方引擎接入 |

## 13. 实现期需以官方文档核对清单

1. DashScope 文件上传凭证接口的确切端点与字段（参考 non-realtime-speech-recognition-user-guide 与官方示例库 `alibabacloud-bailian-speech-demo`）
2. `qwen-audio-3.0-asr-flash-filetrans` 的请求参数名（与 paraformer filetrans 同构但以文档为准）
3. MiniMax 中国站 baseUrl 与 key 体系（platform.minimaxi.com / api.minimaxi.com）
4. yt-dlp 对目标平台（如 B 站）的参数要求
