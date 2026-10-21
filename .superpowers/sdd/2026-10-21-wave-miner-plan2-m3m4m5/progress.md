# SDD ledger — plan: docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md

## Rulings (preflight)

- Ruling: 直接在 main 分支执行（不建 worktree）— 项目惯例（计划 1 全部任务直接提交 main）+ 需求方明确指令"逐任务执行（每任务 TDD+审查+提交）"；git 历史即记录。代价：无隔离回滚点，靠每任务提交粒度弥补。
- Ruling: Task 13 真机验收需真实 API key，到达该任务时向需求方索取或使用环境变量；不提前猜测。

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
