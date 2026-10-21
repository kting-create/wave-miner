# SDD ledger — plan: docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md

## Rulings (preflight)

- Ruling: 直接在 main 分支执行（不建 worktree）— 项目惯例（计划 1 全部任务直接提交 main）+ 需求方明确指令"逐任务执行（每任务 TDD+审查+提交）"；git 历史即记录。代价：无隔离回滚点，靠每任务提交粒度弥补。
- Ruling: Task 13 真机验收需真实 API key，到达该任务时向需求方索取或使用环境变量；不提前猜测。
- Ruling: 续作基线修复（Windows 机器）— 4 个 @SpringBootTest 类加 spring.datasource.type=DriverManagerDataSource（非池化连接释放 SQLite 句柄，@TempDir 才能删库文件）；FfmpegServiceTest 路径断言改 Path.toString() 平台中立 — 依据 HANDOFF §0-4 跨平台约定 — 代价：DriverManagerDataSource 无池化，测试并发写靠 sqlite busy_timeout 兜底（实测 117 全绿）。commit 56ac533

## Preflight conflict scan

| 任务对 | 共享点 | 结果 |
|---|---|---|
| 1→2 | SettingService.get | T2 SettingsEngineKeyProvider 消费 T1 接口，签名一致 |
| 2→3 | EngineRegistry.get/info().languages | T3 校验用，签名一致 |
| 3→7 | TaskContext 8 参构造 / TaskRunner | T3 定型，T7 仅追加构造参数 events，无冲突 |
| 3→10 | TaskView +engineId/language/diarize | 后端 T3 先行，前端 T10 同步类型，顺序正确 |
| 4→7 | TranscriptFormat/SegmentMerger | T7 消费 T4 静态方法，签名一致 |
| 5→6/7/8 | AppProperties 新字段 | T6 用 setMinimaxBaseUrl/engineHttpTimeout；T8 用 setAliyunBaseUrl/setTranscribePollIntervalSeconds；T5 定义齐全 |
| 5→7 | AudioSegmenter.split(Path,Path,long,BooleanSupplier) | T7 stub 与之匹配 |
| 6→7/8 | EngineHttpClient.send / MultipartBuilder | T8 消费，签名一致 |
| 7→9 | TaskEventPublisher.TaskEvent 字段 | T9 读 taskId/type/stage/status，一致 |
| 9→11 | SSE 契约（type=task 终态→complete） | 前后端行为对应 |
| 12→13 | README/验收 | 无冲突 |

任务内自查：T5 cutPoints 语义与测试匹配；T7 偏移断言 492000 已修正；T8 WireMock stub 已简化；T2 EngineController 用 ObjectProvider 防空列表注入失败。无任务间矛盾。

## Progress

Task 1: complete (commits 4a65762..daa7e76, review clean)
Task 1: minor (deferred): SettingApiTest @TempDir 共库跨用例状态（现在不相交、未来加用例需注意）
Task 1: minor (deferred): PUT 缺失/坏 JSON body → 500 而非 400（HttpMessageNotReadableException 未映射）
Task 1: minor (deferred): put() 返回的 configured 仅查 DB 不含 env 兜底（与 list() 不对称，brief 原文如此）
Task 2: complete (commits daa7e76..306cacf, review clean)
Task 2: minor (deferred): EngineRegistry.get 对 null id() 会 NPE（契约：引擎 id 非 null）
Task 2: minor (deferred): EngineController 引擎列表构造期冻结（单例引擎场景可接受）
Task 3: complete (commits 306cacf..aed2486, review clean)
Task 3: minor (deferred): SchemaMigrator 无专属单测（旧库补列仅冒烟验证）
Task 3: minor (deferred): engineId/language 归一化在 create() 与 validate() 重复（brief 原样）
Task 3: minor (deferred): 语言匹配大小写敏感（"ZH"→400）
Task 4: complete (commits aed2486..c484639, review clean)
Task 4: Ruling: brief 测试断言 speaker isNull() 与 Interfaces 块"省略该键"矛盾 — 以契约+实现为准，测试断言改为 has("speaker").isFalse() — 代价：若下游期待显式 null 键需调整（无此需求）
Task 4: minor (deferred): SegmentMerger 不接受 null 输入（内部调用方，信息性）
Task 5: complete (commits c484639..b53db0e, review clean)
Task 5: Ruling: parseSilenceEnd 取首个空白前 token、detectSilencesCommand 对阈值用 trimNum — brief 测试为权威（TDD），两处实现代码与自身测试矛盾，按测试修正 — 代价：无（测试即契约）
Task 5: minor (deferred): cutPoints maxSec<=0 会死循环（实际 maxSec 来自 EngineInfo 500/43200，安全）
Task 5: minor (deferred): detectSilences 无取消路径（brief 签名如此，本地 ffmpeg 快速执行）
Task 6: complete (commits b53db0e..941dfe6, review clean)
Task 6: Ruling: 重试测试用 WireMock scenario 桩替代双普通桩 — WireMock 3.x last-stub-wins 使 brief 版本测不到重试，scenario 版真实走 429→200 — 引擎代码零改动
Task 6: minor (deferred): speakerOf 超长数字串 NumberFormatException（不现实输入，包 parseVerboseJson catch）
Task 6: minor (deferred): errorMessage 对 null body 渲染 "null"（外观问题）
Task 7: complete (commits 941dfe6..dce5bc0, review clean)
Task 7: Ruling: registry.get 在 resolveInput 之前（brief 自身 unknownEngineFails 用例要求）；TranscribeStage 5 参构造（plan勘误：无 ArtifactStore 参数）
Task 7: minor (deferred): transcribeInChunks 的 catch(RuntimeException){throw e;} 为空操作（注释意图）
Task 6 报告的"114 tests"为误计，T7 时实测 110 全绿（未删任何测试）
Task 8: fix round 1/5 (1 addressed, 0 open — downloadResult 非200路径补 raw body+存档提示; commits c3eef9e..6558d67)
Task 8: complete (commits dce5bc0..6558d67, review clean)
Task 8: minor (deferred): EngineHttpClient 重试耗尽异常缺 engine_raw 存档提示且仅带 500 字符摘要（共享层，同缺口类）
Task 8: minor (deferred): downloadResult 空 URL 路径无存档提示（无响应体可存档，可辩护）
Task 8: minor (deferred): 上传/提交失败缺阶段上下文（"请求转写服务失败"无 上传/提交 前缀）
Task 9: complete (commits 56ac533..9319ec6, review clean)
Task 9: minor (deferred): 心跳发送失败仅 remove 未 complete（brief 正文与手稿自相矛盾，实现跟随手稿；timeout=0L 影响有限）
Task 9: minor (deferred): emitters.isEmpty 检查与 remove 间并发窗口，subscribe 后新 emitter 可能随整个 list 被移出（brief 参考代码固有，窗口极窄）
Task 9: minor (deferred): 实现报告误引 brief 的 findById（实际 brief 写的就是 find，代码正确，仅报告笔误）
Task 10: fix round 1/5 (1 addressed, 0 open — artifactUrl 按 / 分段编码+client.test.ts 3 用例; commits bbbb6cc..813d6cb)
Task 10: complete (commits 9319ec6..813d6cb, review clean)
Task 10: minor (deferred): 切换到不支持说话人分离的引擎时 diarize 不重置，隐藏开关仍随表单提交（brief 自身设计，后端也不校验 diarize×引擎组合）
Task 10: Ruling: brief 步骤漏列"artifactUrl 按 / 分段编码"（计划 Ruling 9 + HANDOFF §2 明确归 T10）— 按 fix round 补齐 — 代价：无
Task 11: fix round 1/5 (1 addressed, 0 open — 重试后重建 SSE 订阅; commits 8135679..2e55b18)
Task 11: complete (commits 813d6cb..2e55b18, review clean)
Task 11: Ruling: brief 的 es.onmessage 与后端命名事件 event:progress 矛盾（onmessage 收不到命名事件，SSE 会整体失效）— 以 T9 后端契约为准，改 addEventListener('progress') + 保留 onmessage 兜底 — 代价：无
Task 11: minor (deferred): 挂载时转写重复拉取一次（brief 两处如此规定，幂等无害）
Task 11: minor (deferred): 终态帧到达时防抖定时器在途会多触发一次冗余 load()（幂等无害）
Task 11: minor (deferred): 已终态任务页面保留空闲 SSE 长连接（brief 设计，本地单机可接受）
Task 11: minor (deferred): 组件卸载前 retry 在飞的极窄窗口可能留下无人清理的 ES（低危，需组件测试设施才能覆盖）
Task 12: complete (commits 2e55b18..85fef0b, review clean)
Task 12: minor (deferred): README:3 简介仍写"转写引擎在后续版本提供"，与新增转写章节矛盾（T13 顺手修）
Task 12: Ruling: commit message 用"docs: 转写功能与配置使用说明"与计划措辞"转写引擎配置与使用说明"语义等价，不为措辞改写历史 — 代价：无

## T13 真机验收（Windows 机器，jar + data/ + token）

- Ruling: 验收环境 ffmpeg 用 winget Gyan 9.0.2（--app.ffmpeg-path 显式指定）— 原有第三方路径 nightly 构建二进制异常（任何输出文件 Permission denied，ffprobe 读取正常），不排查根因直接换官方构建 — 代价：依赖本机 winget 包。
- Ruling: 计划缺口——验收步骤要求"在设置页填 key"但 13 个任务无任何任务建设置页 UI — 补建 SettingsView.vue（getSettings/putSetting 已有，最小实现），commit fd2a8cf，审查 Approved — 代价：无。
- Task 13: fix round #1 (6b380dd) 缺少访问令牌 → 401 而非 500（拦截器 given=null NPE，验收项 10 实锤；+5 单测）
- Task 13: fix round #2 (ddbf9a9) 产物下载支持多段路径（{name:.+} 在 PathPatternParser 不跨 `/`，engine_raw 下载 404 实锤；{*name} 捕获；+1 用例）
- Task 13: fix round #4 (6a67848) 阿里云上传凭证解析顶层 data 字段（计划把 getPolicy 响应信封误当 {output:...}；真机原始响应存档实锤；+2 用例）
- Task 13: fix round #5 (2d1234e) 阿里云 file_urls 补全 OSS bucket 段（oss://dashscope-file-mgr/...，REQUEST_INVALID_FILE_URL_VALUE 实锤；+2 用例）
- 验收已过项：1 设置脱敏 ✅；2 engines 列表 ✅；8 未配 key 报错（中文，TRANSCRIBE FAILED）✅；10 令牌（无 token 401 / header 通过 / ?token= SSE 通过）✅；11 错误 key engine_raw 存档 ✅（401 原始响应存档+下载）；SSE connected+心跳 ✅
- Task 13: fix round #6 (c242ab5) 阿里云提交补 X-DashScope-OssResourceResolve: enable 头并回退 oss:// 无 bucket 形态（官方语义：oss://<key> 不带 bucket，靠该头解析临时存储；fix #5 的 bucket 推导为此撤销）— 真机复验：ASCII 与中文文件名任务均 COMPLETED，transcript.json/srt/txt 产物正确，diarize=true 的 segments 带 speaker 字段
- 真机验收进度：项1 ✅ 脱敏；项2 ✅ engines；项3 ✅（阿里云真转写+产物三格式）；项4 ✅ diarize=true 全链路（segments.speaker 落库；单歌手音频仅 speaker=0 属预期，纯中英混合素材未单独构造）；项6 ✅ 阿里云引擎；项8 ✅ 未配 key 中文报错；项9 ✅ SSE connected/progress/stage/终态 complete 实时推送；项10 ✅ 401/header/?token=；项11 ✅ 错误 key engine_raw 存档+下载
- 待做：项3/7 的 MiniMax 部分（需 MiniMax key，>500s 切分靠 minimax 500s 限额）、项5 直链 mp4 全流程（已装 yt-dlp 后可跑）
- 验收环境注意：shell 工具会杀本地子进程树（Start-Process 分离仍可能被回收），服务器用 Start-Process 启动后以 Get-NetTCPConnection 定位真实监听 pid 再管理；PowerShell 5.1 无 -Form，multipart 用 curl.exe；PS 变量名大小写不敏感，循环里勿用同名大小写变体。
