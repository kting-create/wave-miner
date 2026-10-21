# Task 7 Report: TaskEventPublisher + TaskRunner 事件埋点 + TranscribeStage + 产物白名单/MIME

**Status:** COMPLETE
**Commit:** dce5bc0 `feat: TRANSCRIBE 阶段、进度事件总线与转写产物白名单`
**Branch:** main

## What was done (TDD order)

1. **RED — ArtifactStoreTest 追加** `transcriptAndEngineRawAllowed`（brief Step 1 原文）：`isAllowedArtifactName("transcript.json")` 等 4 项断言失败。
2. **RED — TranscribeStageTest 新建**（brief Step 2 原文）：编译失败（TaskEventPublisher/TranscribeStage 不存在）。
3. **GREEN 实现：**
   - `pipeline/TaskEventPublisher.java`（新建，@Component）：`record TaskEvent` + 静态工厂 `stage/progress/task`；`Listener` 接口；`addListener/removeListener/publish`（逐个通知，RuntimeException 吞掉记 warn）。与 brief 逐字一致。
   - `stage/TranscribeStage.java`（新建，@Component，type=TRANSCRIBE）：5 参构造 `(registry, ffmpeg, segmenter, events, props)`（未加 ArtifactStore——brief erratum 已确认）；输入解析 AUDIO_EXTRACT → DOWNLOAD → uploadedFile；视频输入先 `ffmpeg.extractAudio(input, dir/audio.m4a, ctx::cancelRequested)` 并重探时长；`durationMs > maxDur*1000` 走分片路径（逐片进度 `转写分片 i/n`、每片前取消检查、raw 存 `engine_raw/{engineId}-chunk-XXX.json`、时间戳偏移 `chunk.startSec()*1000`、成功后 `SegmentMerger.merge(all, props.getMergeGapMs())` 并递归删除 chunks/）；单次路径 raw 存 `engine_raw/{engineId}.json`；`EngineCancelledException` → `TaskCancelledException`；其他 `EngineException` 有 rawResponse 先存档再原样抛出；成功写 transcript.json/srt/txt（TranscriptFormat，language=ctx.language()）→ `ctx.putArtifact(TRANSCRIBE,"transcript.json")` → 进度 `转写完成：N 段`；`callback(ctx)` 转发进度并回传 `ctx.cancelRequested()`。
   - `pipeline/TaskRunner.java`：构造器改为 5 参 `(AppProperties, List<StageHandler>, TaskRepository, ArtifactStore, TaskEventPublisher)`；stage RUNNING/DONE/FAILED（含取消导致的 FAILED）后发布 `stage` 事件；任务终态 COMPLETED/FAILED/CANCELLED（execute 开头短路、循环顶取消、TaskCancelledException 路径）发布 `task` 事件；`fail()` 中 updateStage 后发布 stage FAILED、updateTaskStatus 后发布 task FAILED。
   - `store/ArtifactStore.java`：`isAllowedArtifactName` 追加精确 `transcript.{json,srt,txt}` 与 `engine_raw/<单层 [A-Za-z0-9._-]+ 且无 ..>`（插在斜杠检查**之前**——brief 说"末尾 return false 前追加"，但原有 `name.contains("/")` 早退会先拒绝 `engine_raw/x.json`，故上移，语义与 brief 用例完全一致）；`listArtifacts` 改 `Files.walk(dir,2)` + `relativize().toString().replace('\\','/')` 过滤。
   - `api/TaskController.java`：产物路由改 `@GetMapping("/{id}/artifacts/{name:.+}")`；MIME 表追加 json→application/json、srt→application/x-subrip、txt→text/plain;charset=UTF-8（normalize + startsWith 校验保留）。
   - `TaskRunnerTest`：`publisher` 字段 + setUp 创建并传入构造器；`missingHandlerFailsTask` 的 solo 构造器补第 5 参；既有 8 个用例未动；追加 `publishesStageAndTaskEvents`（brief 原文）。

## Deviations from brief (minimal adaptations)

1. **TranscribeStage.run 中 `registry.get(ctx.engineId())` 提前到 `resolveInput` 之前**。brief 的实现代码是先 resolveInput 再 registry.get，但 brief 自带测试 `unknownEngineFails` 的 ctx 无任何输入（无产物、无上传、无 URL），若先解析输入会抛 `IllegalStateException` 而非 `EngineException("未知转写引擎…")`，测试必然失败。调整后所有 8 个测试与 prose 语义均满足。
2. **TranscribeStageTest.audio() 辅助方法补 `Files.createDirectories(p.getParent())`**。brief 原文在 `cancelRequestedStopsBeforeEngine` 中先调 `audio("t8",…)` 再建目录，`Files.writeString` 因父目录不存在抛 NoSuchFileException。仅改测试辅助方法，测试逻辑保持 brief 原文。
3. **TaskRunner.runTask 的 catch-all（内部错误 → FAILED）补发一条 `task(taskId,"FAILED")` 事件**。brief 只列了 3 处 updateTaskStatus 埋点，但 Interfaces 总则要求"任务终态发布 task 事件"；此路径也是终态，不埋点会导致 SSE 监听方收不到终态。属补充而非冲突。
4. **ArtifactStore.isAllowedArtifactName 新增分支位置上移**（见上），非语义变更。

## Verification

- Step 1 RED：`mvnw -q test -Dtest=ArtifactStoreTest` → 1 failure（预期）。
- Step 2 RED：`mvnw -q test -Dtest=TranscribeStageTest` → 编译失败（预期）。
- Step 7 全量：`cd backend && ./mvnw test` → **BUILD SUCCESS，Tests run: 110, Failures: 0, Errors: 0, Skipped: 0**。
- Commit：`git add backend && git commit -m "feat: TRANSCRIBE 阶段、进度事件总线与转写产物白名单"` → dce5bc0，8 files changed, 501 insertions(+), 7 deletions(-)。

## Notes

- Jackson 3（tools.jackson）仅在既有 TranscriptFormat 内使用，新代码无需额外引入。
- Mockito 对 3 参 `extractAudio(in,out,BooleanSupplier)` 的 stub（`videoInputExtractsAudioFirst`）按原样通过。
- 既有测试 `rejectsTraversalNames`（`sub/transcript.json` 仍拒绝）与 `findsSourceFileAndListsArtifacts`（walk 深度 2 不影响原断言）均保持通过。
