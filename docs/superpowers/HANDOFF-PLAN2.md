# wave-miner 会话交接（计划 2 执行至 T8 中断，待续 T9-T13）

> 用途：计划 2 在"任务 8 完成并审查通过"后中断，换机器续作。本文档 + 计划 2 文档（含计划全文）即可无损续作，无需考古 git 历史。
> 最后更新：2026-10-21（T1-T8 完成并逐任务审查通过；T9-T13 未开始）

## 0. 工作约定（需求方明确的规则，必须遵守）

1. **以官方文档为准**：所有 API 设计/请求参数必须先查官方文档再实现；计划 2 涉及的 API 已全部核对完毕（见计划文档"官方 API 核对结论"章节），实现时以该章节为准。
2. **细节不用反复确认**：自行裁定并记录 Ruling；只有不可逆/有副作用/每条路都是猜才停下来问。
3. **稳定、能用的质量标准**：个人项目，优先稳定运行；错误信息一律中文。
4. **跨平台**：新代码/测试双平台可用（Windows 验证/Linux 部署）；测试禁止仅依赖 Unix 命令；外部进程走 `ProcessRunner`。
5. JDK 21 + 虚拟线程；不使用 Lombok。
6. **执行方式**：用 superpowers:subagent-driven-development 逐任务执行（每任务 TDD + 独立审查 + conventional commit，一次提交一个可运行状态）。

## 1. 当前进度（计划 2 共 13 任务）

**已完成 T1-T8（每任务经独立审查，全部 Approved）**，全量后端测试 **117 个全部通过**（`cd backend && ./mvnw -q test`）。前端未动（T10/T11 未开始）。

| 任务 | 内容 | 提交 |
|---|---|---|
| 计划文档 | 计划 2 全文（含官方 API 核对结论、14 条 Rulings、13 任务详细步骤与代码） | `4a65762` |
| T1 | 设置存储 app_setting + GET/PUT /api/settings（脱敏、env 兜底、CORS+PUT） | `daa7e76` |
| T2 | 转写引擎 SPI 类型 + EngineRegistry + GET /api/engines | `306cacf` |
| T3 | 任务模型扩展（engine_id/language/diarize 列 + SchemaMigrator 迁移 + 创建校验） | `aed2486` |
| T4 | TranscriptFormat（json/srt/txt）+ SegmentMerger（<800ms 合并） | `c484639` |
| T5 | AppProperties 转写配置 + FfmpegService.detectSilences/cutAudio + AudioSegmenter | `b53db0e` |
| T6 | EngineHttpClient（超时/重试）+ MultipartBuilder + MiniMax 引擎（WireMock 全流程） | `941dfe6` |
| T7 | TaskEventPublisher + TaskRunner 事件埋点 + TranscribeStage + 产物白名单/MIME | `dce5bc0` |
| T8 | 阿里云百炼 filetrans 引擎（上传凭证→OSS→提交→轮询→结果归一化） | `c3eef9e` + fix `6558d67` |

**T8 唯一一次修复**：`downloadResult` 非 200 路径补 raw body + "原始响应已存档到 engine_raw/"（复审 ADDRESSED、无新破坏）。

**重要提醒**：T6 实现者报告里写的 "114 tests" 是误计；T7 完成时实测 110、T8 完成时 117。以 Maven 汇总行为准。

## 2. 剩余工作（T9-T13，以计划文档任务全文为准）

计划文档：`docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md`（已提交，每个任务都有完整步骤、测试代码、实现代码与 Interfaces 块）。

- **T9 SSE 进度接口**：`api/TaskSseRegistry`（订阅/广播/终态 complete/15s 心跳）+ `api/TaskSseController`（`GET /api/tasks/{id}/events`，404 校验）。计划 Task 9。注意：MockMvc SSE 测试若 asyncComplete 等待不稳，计划里有备选方案（registry 提供 `emitterCount` 做单元断言）。
- **T10 前端一**：`frontend/src/api/types.ts`（EngineInfo/Transcript/TranscriptSegment/SettingView，TaskView +engineId/language/diarize）、`client.ts`（getEngines/getSettings/putSetting/sseUrl + artifactUrl 按 `/` 分段编码）、`utils/timeline.ts`（formatTimecode/findActiveSegment/clampSeekMs，先写 vitest）、`NewTaskView.vue` 转写卡（引擎/语言/说话人分离，configured=false 警告）。计划 Task 10。
- **T11 前端二**：`TaskDetailView.vue` 时间轴对照（媒体播放器 timeupdate ↔ 分段高亮双向联动、点击段落 seek）+ SSE 订阅（EventSource + `?token=`，终态关闭，onerror 回退 2s 轮询）+ engine_raw 查看器 dialog。计划 Task 11。
- **T12 文档与打包**：README 转写章节（计划里已给全文）+ `npm run build` + `./mvnw -q package -DskipTests` + jar 冒烟（/api/engines、/api/settings）。计划 Task 12。
- **T13 全量验收**：全量测试 + **真机验收（需要真实 MiniMax 中国站 / 阿里云百炼 API key，见 §5）** + 修复 + 更新本 HANDOFF 为完成态。计划 Task 13 有 11 项手工验收清单。

## 3. 新机器续作指引（零上下文）

```bash
git clone <repo-url> && cd wave-miner
# 验证基线：
cd backend && ./mvnw -q test        # 应 117 全绿
cd ../frontend && npm install && npm run test && npm run build   # 应全绿
```

然后继续执行计划 2 的 T9-T13。推荐两种方式：

**方式 A（本机装有 opencode + superpowers 技能包，推荐）**：把本文档 §6 的"续作提示词"整段发给新会话。SDD 技能脚本位于
`~/.cache/opencode/packages/superpowers@git+https:/github.com/obra/superpowers.git/node_modules/superpowers/skills/subagent-driven-development/scripts/`，
用 `task-brief <计划文件> N` 生成任务简报、`review-package <计划文件> BASE HEAD` 生成审查包、`sdd-workspace <计划文件>` 定位工作区。

**方式 B（无 superpowers 脚本）**：按计划文档逐任务手工执行，保持同等纪律：
- 提取任务全文：`awk '/^### Task 9:/,/^### Task 10:/' docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md > /tmp/task-9-brief.md`（编号替换）
- 每任务 TDD：先跑测试看 FAIL → 实现 → 全量 `./mvnw -q test` → `git add <改动> && git commit -m "<计划里指定的 commit message>"`
- 每任务完成后生成审查包（手工等价）：
  `BASE=$(git rev-parse HEAD~1); git log --oneline $BASE..HEAD > /tmp/review.diff && git diff --stat $BASE..HEAD >> /tmp/review.diff && git diff -U10 $BASE..HEAD >> /tmp/review.diff`
  再让另一个无上下文的会话/代理对照计划任务原文审查该 diff（spec 合规 + 质量，两裁定都给出）。
- 各任务 commit message 计划里已写死，照抄。

**执行顺序必须 T9→T10→T11→T12→T13**（T11 依赖 T9 的 SSE；T13 验收依赖全部）。

## 4. 已做 Rulings（复制自执行 ledger，.superpowers/ 不入库故转录于此）

1. 直接在 main 分支执行（不建 worktree）— 项目惯例（计划 1 同样）+ 需求方明确指令；每任务提交弥补粒度。
2. 计划文档 Ruling 1-14（见计划文档"Rulings"章节），核心：MiniMax 中国站 baseUrl=`https://api.minimax.cn`（修正交接文档的 minimaxi.com）；阿里云轮询用 POST；API key 解析序 设置表→env→无；speaker 归一化 Integer；engine_raw 前端 URL 按 `/` 分段编码避免 Tomcat 拒 %2F；SSE 终态服务端 complete；WireMock 3.13.1。
3. （T4）brief 测试断言 speaker isNull() 与 Interfaces"省略该键"矛盾 — 以契约为准，断言改 `has("speaker").isFalse()`。
4. （T5）parseSilenceEnd 取首个空白前 token、detectSilencesCommand 阈值 trimNum — brief 测试为权威，按测试修正实现。
5. （T6）重试测试用 WireMock scenario 桩替代双普通桩 — WireMock 3.x last-stub-wins 使 brief 版本测不到重试；引擎代码零改动。
6. （T7）TranscribeStage 5 参构造（计划 Interfaces 块中的 ArtifactStore 参数是计划勘误，已按勘误执行）；registry.get 在 resolveInput 之前（brief 自身测试要求）。
7. （T8）full-flow 测试用 `hermeticPolicy(wire)` 把 upload_host 改写为 WireMock 本地 — brief 原文指向真实 OSS 主机会造成测试外呼。

## 5. 真机验收（T13）所需

- **真实 API key**：MiniMax 中国站（platform.minimax.cn 申请，baseUrl api.minimax.cn）与 阿里云百炼（DASHSCOPE_API_KEY）。两种注入方式：设置页填入（存 SQLite）或环境变量 `WAVEMINER_MINIMAX_API_KEY` / `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`）。
- 本机已装 ffmpeg 9.0.2 / yt-dlp（brew）；Windows/Linux 注意事项见 README 与计划 1 文档。
- 验收清单见计划 Task 13（11 项：设置脱敏、engines 列表、短音频转写、中英混合+说话人分离、直链 mp4 全流程、阿里云双引擎、>500s 切分、未配 key 报错、SSE 实时、令牌+`?token=`、错误 key 的 engine_raw 存档）。
- 验收中若发现缺陷：按 TDD 小步修复各自提交（`fix: ...`）。

## 6. 遗留 Minor（不阻塞，T13 或后续顺手清）

- T1: SettingApiTest @TempDir 共库跨用例（现用键不相交，加用例需注意）；PUT 坏 JSON → 500 而非 400（HttpMessageNotReadableException 未映射）。
- T2: EngineRegistry.get 对 null id() 会 NPE；EngineController 引擎列表构造期冻结。
- T3: SchemaMigrator 无专属单测；engineId/language 归一化在 create()/validate() 重复；语言匹配大小写敏感。
- T4: SegmentMerger 不接受 null 输入（内部调用方）。
- T5: cutPoints maxSec<=0 死循环（实际值 500/43200 安全）；detectSilences 无取消路径。
- T6: speakerOf 超长数字串 NFE（被 parseVerboseJson catch 包住）；errorMessage 对 null body 渲染 "null"。
- T7: transcribeInChunks 的 `catch(RuntimeException){throw e;}` 为空操作（注释意图）。
- T8: EngineHttpClient 重试耗尽异常缺 engine_raw 存档提示且仅 500 字符摘要；downloadResult 空 URL 无存档提示；上传/提交失败缺阶段上下文前缀。
- 计划 1 时代的遗留清单（见旧 HANDOFF git 历史或计划 1 文档）仍然有效，未处理。

## 7. 实战注意事项（本会话踩过的坑，新会话直接避让）

1. Spring Boot 4 测试注解换了包：`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`（不是 boot.test.autoconfigure.web.servlet）。
2. JSON 是 Jackson 3：`tools.jackson.databind.ObjectMapper/JsonNode/node.ObjectNode`；writeValueAsString/readTree 不抛受检异常。
3. WireMock 3.x 同优先级 last-stub-wins：同一 URL 要先后返回不同响应必须用 scenario（`Scenario.STARTED` 起始态）；重试类测试用 scenario 才真实覆盖。
4. WireMock 桩的 URL 必须全部指向 `wire.baseUrl()`（brief 里真实 OSS host 的桩要改写，勿外呼）。
5. surefire 计数以 Maven 汇总行为准；报告里手写数字曾误计（114 vs 实际）。
6. Spring `ObjectProvider.orderedStream()` 每次调用返回新流；mock 时用 `thenAnswer(inv -> Stream.of(...))` 而非 `thenReturn(stream)`（后者二次消费抛异常）。
7. javac 对 `rs -> out.put(...)` 这类既可 value 又可 void 的 lambda 在 ResultSetExtractor/RowCallbackHandler 间有二义性——写成块体。
8. 旧开发库升级靠 `SchemaMigrator`（PRAGMA 检查 + ALTER），已实测兼容。
9. Mockito 桩 FfmpegService 的 extractAudio 用 3 参版本（2 参是 default 方法，mock 不转发）。
10. 冒烟实测旧库：`./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.data-dir=../data`（brief 里 `./mvnw spring-boot:run &` 默认会建 backend/data 新库）。

## 8. 续作提示词（复制给新会话）

```
继续 wave-miner 项目（计划 2 后半段）。先读以下文档建立完整上下文：
1. docs/superpowers/HANDOFF-PLAN2.md（中断交接：T1-T8 已完成，T9-T13 待做，含 Rulings/遗留/实战注意）
2. docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md（计划 2 全文，Task 9-13 的步骤与代码就在其中）

任务：从 Task 9 开始按 superpowers:subagent-driven-development 逐任务执行到 Task 13（每任务 TDD + 独立审查 + 提交，commit message 用计划里写死的）。遵守 HANDOFF §0 约定与 §7 实战注意事项。T13 真机验收需要真实 MiniMax/阿里云 API key（注入方式见 HANDOFF §5），验收后更新 HANDOFF 为完成态。
```
