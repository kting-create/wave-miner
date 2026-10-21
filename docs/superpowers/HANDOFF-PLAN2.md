# wave-miner 会话交接（给下一个会话的完整状态）

> 用途：新会话接续开发。本文档 + 规格书 + 计划 1 文档构成完整上下文，新会话无需考古 git 历史。
> 最后更新：2026-10-21（M1+M2 完成并验收）

## 0. 新会话工作约定（需求方明确的规则，必须遵守）

1. **以官方文档为准**：所有 API 设计/请求参数必须先查官方文档再实现，不得凭记忆编造；实现前未核对的必须在规格/计划里标注"实现期核对"。
2. **细节不用反复确认**：需求方已授权"做一个好的产品给我"。决策自行做并在文档中记录裁定（Ruling）即可；只有不可逆/有副作用/每条路都是猜的情况才停下来问。
3. **稳定、能用的质量标准**：个人项目，优先稳定运行与运行安全；错误信息中文。
4. **跨平台要求**（新增）：后续要转到 **Windows 验证**，之后部署到 **Linux 服务器**。新代码/测试必须双平台可用；详见规格 §14 与 README 的 Windows/Linux 章节。
5. JDK 21 + 虚拟线程；不使用 Lombok。

## 1. 项目现状

- 仓库：`/Users/kting/code/wave-miner`，分支 `main`，全部提交作者已统一为 `kting <2551416075@qq.com>`（2026-10-21 filter-branch 重写过一次；此后新增提交自动用该身份）。
- 已交付 **M1+M2（计划 1，12 任务全部完成、每任务经审查）**：
  - 后端：Spring Boot 4.1.1 + Java 21（虚拟线程）+ SQLite。任务流水线（DOWNLOAD / AUDIO_EXTRACT 两阶段已实现；TRANSCRIBE 是计划 2）、`TaskRunner`（虚拟线程+Semaphore 并发限流、in-flight 守卫、retry 从首个非 DONE stage 续跑、协作式取消会杀外部进程树）、`ProcessRunner`（并发排空输出、超时/取消杀进程树）、`ArtifactStore`（产物存储+路径安全）、REST API（任务 CRUD / 产物 Range 下载 / SSE 尚未做 / `X-Access-Token` 或 `?token=` 鉴权 / 统一中文异常）、工具健康检查（按工具选 `-version`/`--version`）。
  - 前端：Vue 3 + Vite + TS + Element Plus。新建任务（URL/上传切换+功能勾选）、任务列表、任务详情（产物预览/下载）。轮询刷新（SSE 是计划 2）。
  - 测试：后端 68 全过；前端 vitest 4 全过 + `vue-tsc && vite build` 干净。
  - 真机验收通过：真实 ffmpeg 9.0.2 + yt-dlp 2026.08.19（brew 已装）；上传→提取音频、直链→下载+提取（23MB/46.6s）、B站下载、kill -9 重启恢复、401/200 令牌、Range 206。
- 打包：`java -jar backend/target/wave-miner-0.0.1-SNAPSHOT.jar` 自包含前端；`-P '!frontend'` 跳过前端构建。

## 2. 关键文档（先读这三个）

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-10-21-wave-miner-design.md` | 设计规格（已确认）。**§5 转写引擎层、§9 前端、§13 实现期核对清单、§14 跨平台** 是计划 2 的输入 |
| `docs/superpowers/plans/2026-10-21-wave-miner-plan1-m1m2.md` | 计划 1（已执行完，接口定义可当参考书用：TaskRepository/ArtifactStore/ProcessRunner/引擎 SPI 等 Interfaces 块） |
| `README.md` | 运行/配置/Windows/Linux 部署 |

## 3. 计划 2 的范围（下一步要做的）

**M3+M4+M5：转写引擎层 + TRANSCRIBE stage + SSE + 时间轴对照 UI**

1. **TranscriptionEngine SPI**（规格 §5.1）：`id()/info()/transcribe(mediaFile, options, progress)`；统一句级输出 `[{startMs,endMs,text,speaker?}]`；引擎原始响应存 `data/tasks/{id}/engine_raw/`。
2. **MiniMax 引擎**（已核实官方 OpenAPI，规格 §5.2）：`POST {baseUrl}/v1/speech_to_text`，Bearer，multipart，`model=asr-1.0`，header `language`(BCP-47，空=混合)，`response_format=verbose_json`，`timestamp_level=sentence`；限制 ≤500s/≤50MB；国际站 `https://api.minimax.io`，中国站 `https://api.minimaxi.com`（baseUrl 可配，默认中国站——中国站字段细节实现期以 platform.minimax.cn 文档核对）。文档：https://platform.minimax.io/docs/api-reference/speech-to-text
3. **阿里云百炼引擎**（已核实官方 REST，规格 §5.3）：默认模型 `qwen-audio-3.0-asr-flash-filetrans`（≤12h/2GB，说话人分离/热词/prompt），备选 `paraformer-v2`。流程：DashScope 文件上传凭证拿临时公网 URL → `POST /api/v1/services/audio/asr/transcription`（`X-DashScope-Async: enable`）→ 轮询 `POST /api/v1/tasks/{task_id}` → 下载 `transcription_url` JSON → 归一化 `transcripts[].sentences[]`（begin/end ms、words、speaker_id）。**本地文件必须先走上传凭证接口**（API 只收公网 URL）；上传凭证与 filetrans 请求字段实现期以官方文档+官方示例库核对：https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api 、 https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide 、 https://github.com/aliyun/alibabacloud-bailian-speech-demo （有 Java 示例）
4. **超长音频分片**（规格 §5.5，MiniMax 必需）：ffmpeg `silencedetect` 静音切分 → 分片转写 → 时间戳偏移归并（<800ms 间隙合并）。
5. **三种产物**：`transcript.json`（segments 数组）、`transcript.srt`、`transcript.txt`（[mm:ss] 前缀）；允许的名字需在 `ArtifactStore.isAllowedArtifactName` 加白名单（transcript.* / engine_raw/**），同步检查 `TaskController` Range 下载的 MIME 映射。
6. **前端**：新建任务页加 TRANSCRIBE 勾选卡（引擎选择数据源 `GET /api/engines` 需新实现）+ 语言选择；详情页时间轴对照（媒体播放器 currentTime ↔ 分段高亮 双向联动）；SSE 进度（`GET /api/tasks/{id}/events`）替代轮询（保留轮询兜底）。
7. **设置存储**：API key 存 SQLite（`app_setting` 表）或环境变量；`GET/PUT /api/settings` 返回脱敏。引擎选择/语言持久化为任务字段。

**计划 3（之后）**：M6——OpenAI 兼容模板引擎（探索更高质引擎：SiliconFlow SenseVoice / Groq Whisper 等）、设置页完善、小米 MiMo（未查到公开 ASR API，保持插槽）、访问令牌管理页。

## 4. 平台注意事项（写代码时就考虑）

- 新测试禁止仅依赖 Unix 命令（参考 `ProcessRunnerTest` 的 cmd/sh 分支写法）。
- 外部工具调用都走 `ProcessRunner`（支持取消信号 3 参重载）；新工具（如 silencedetect）同样封装在 `media/`。
- ffmpeg 9 注意：`--version` 已失效用 `-version`（健康检查已处理）； silencedetect/切分参数双平台一致，无坑。
- Windows 文件锁：删除在途任务产物可能短暂失败，前端已有重试路径。
- 转写 API 调用从服务器发起，注意超时与重试（指数退避）；引擎错误信息透传"原始响应已存档到 engine_raw/"。

## 5. 新会话启动提示词（复制给新会话）

```
继续 wave-miner 项目（/Users/kting/code/wave-miner）。先读以下三份文档建立完整上下文，再开始工作：
1. docs/superpowers/HANDOFF-PLAN2.md（会话交接：现状/约定/计划 2 范围/平台注意事项）
2. docs/superpowers/specs/2026-10-21-wave-miner-design.md（设计规格，重点 §5/§9/§13/§14）
3. docs/superpowers/plans/2026-10-21-wave-miner-plan1-m1m2.md（计划 1，已执行完毕，Interfaces 块作为接口参考）

任务：按交接文档 §3 的范围，用 superpowers:writing-plans 编写"计划 2（M3+M4+M5：转写引擎 + SSE + 时间轴对照）"的实现计划（保存到 docs/superpowers/plans/），API 细节必须先核对交接文档列出的官方文档 URL（尤其是阿里云上传凭证接口与 MiniMax 中国站字段）。计划完成自查后，用 superpowers:subagent-driven-development 逐任务执行（每任务 TDD + 审查 + 提交）。遵守交接文档 §0 的工作约定：官方文档为准、细节自行裁定并记录 Ruling、错误信息中文、双平台（Windows 验证/Linux 部署）、JDK21 虚拟线程、不用 Lombok。完成后跑全量测试 + 真机验收（用真实 API key），最后更新 HANDOFF 文档状态。
```

## 6. 遗留 Minor 清单（不阻塞，计划 2/3 顺手可清）

- backend: insert+insertStages 无事务；catch(Exception) 不捕 Error；created_at TEXT 同秒混合精度排序；directDownload 每次新建 HttpClient；health 串行最坏 45s；ByteArrayOutputStream 无上限；Range 后缀形式非 RFC；删除在途任务竞态；在途 retry→409/416/401 无专项测试锁定。
- frontend: ElMessageBox.confirm 取消 unhandled rejection；详情页瞬时错误永久停轮询；route.params.id 非响应式；onFileChange(any)。
- 杂项: README 硬编码 jar 版本号；pom initializr 空占位节点。
