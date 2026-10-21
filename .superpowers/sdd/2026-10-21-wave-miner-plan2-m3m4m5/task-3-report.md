# Task 3 Report: 任务模型扩展（engine_id/language/diarize）+ SchemaMigrator + 创建参数校验

**Status: COMPLETE**
**Commit: `aed2486` — `feat: 任务引擎/语言/说话人分离字段与创建校验`**

## What was done (per brief steps)

### Step 1 — Failing test first
- Added `engineFieldsRoundTrip` to `TaskRepositoryTest` (before implementation).
- Verified RED: compile failure (`找不到符号 getEngineId/isDiarize/getLanguage`) as expected.

### Step 2 — SchemaMigrator + entity/repository/context/runner
- **Created** `backend/src/main/java/com/waveminer/waveminer/pipeline/SchemaMigrator.java` — exact code from brief: `@Component @Order(1) implements ApplicationRunner`, `PRAGMA table_info(task)`, idempotent `ALTER TABLE task ADD COLUMN` for `engine_id TEXT` / `language TEXT` / `diarize INTEGER`.
- **`Task.java`**: added `engineId`/`language`/`diarize` fields + `getEngineId/setEngineId`, `getLanguage/setLanguage`, `isDiarize/setDiarize` (matches existing getter/setter style).
- **`TaskRepository.java`**: `insert` now writes 13 columns incl. `engine_id,language,diarize` (`t.isDiarize() ? 1 : 0`); `mapTask` reads them back (`rs.getInt("diarize") != 0`).
- **`TaskContext.java`**: added `engineId/language/diarize` final fields; original 5-arg constructor delegates to new 8-arg constructor (null → engineId=null, blank → language="auto"); read-only accessors `engineId()/language()/diarize()`.
- **`TaskRunner.java`**: `execute` builds `TaskContext` with the 8-arg constructor (appends `task.getEngineId(), task.getLanguage(), task.isDiarize()`). TaskRunner constructor unchanged (per instructions — Task 7 scope).

### Step 3 — PipelineService/TaskController/TaskViews + tests
- **`PipelineService.java`**:
  - `CreateTaskCommand` → 6-arg record `(url, file, stages, language, engineId, diarize)`.
  - Constructor now `(TaskRepository, ArtifactStore, EngineRegistry, AppProperties, Runner)` — registry in position 4 as specified.
  - `create()` sets engineId (blank → `minimax`, else trimmed), language (blank → `auto`, else trimmed), diarize.
  - `validate()` appends TRANSCRIBE-gated checks at the end: unknown engine → `IllegalArgumentException("未知转写引擎: x")` (caught from `EngineException`); non-`auto` language not in `engine.info().languages()` → `"该引擎不支持的语言: x"`.
- **`TaskController.java`**: `create` adds `@RequestParam(required=false) language/engineId` and `@RequestParam(defaultValue="false") String diarize` → `Boolean.parseBoolean`. `toView` passes the 3 new fields.
- **`TaskViews.java`**: `TaskView` gains `String engineId, String language, boolean diarize` immediately after `errorMessage`.
- **`PipelineServiceTest`**: DDL extended with the 3 columns; service built with Mockito-mocked `TranscriptionEngine` (`fake-engine`, languages `["zh"]`) wrapped in mocked `ObjectProvider` (`orderedStream()`) feeding a real `EngineRegistry`; `url()/upload()` helpers and `urlAndFileTogetherRejected` use the 6-arg command; added `unknownEngineRejected` + `unsupportedLanguageRejected` (exact code from brief).
- **`TaskRunnerTest`**: DDL extended with the 3 columns only; existing 5-arg `TaskContext` usages untouched.
- **`TaskRepositoryTest`**: `engineFieldsRoundTrip` added (Step 1 test, now green).

## Verification

- Targeted: `./mvnw -q test -Dtest='TaskRepositoryTest,PipelineServiceTest,TaskRunnerTest'` → exit 0.
- Full suite: `cd backend && ./mvnw -q test` → exit 0, **79 tests, 0 failures, 0 errors, 0 skipped** across 14 test classes (surefire reports: WaveMinerApplicationTests 1, SettingApiTest 3, TaskApiTest 12, EngineRegistryTest 3, FfmpegServiceTest 8, ProcessRunnerTest 5, ToolHealthCheckerTest 3, PipelineServiceTest 12, TaskRepositoryTest 7, TaskRunnerTest 8, AudioExtractStageTest 5, DownloadStageTest 6, ArtifactStoreTest 4, SettingServiceTest 2).

### Step 5 — Manual smoke on the real legacy DB
- Backup of `data/wave-miner.db` taken to temp before the run.
- `./mvnw -q spring-boot:run -Dspring-boot.run.jvmArguments="-Dapp.data-dir=../data"` (deviation noted below), then
  `curl -s -X POST /api/tasks -F url=https://example.com/a.mp4 -F stages=DOWNLOAD`.
- Response contained exactly `"engineId":"minimax","language":"auto","diarize":false` as required.
- `PRAGMA table_info(task)` on the legacy DB afterwards shows `engine_id`, `language`, `diarize` — **SchemaMigrator really executed ALTER on the old DB**.
- Startup log contains 0 matches for SQL exceptions.
- Cleanup: smoke task row + task_stage rows deleted from the DB and its task dir removed (legacy DB retains only the intended schema migration); app process stopped; port 8080 free.

### Step 6 — Commit
- `git add backend && git commit -m "feat: 任务引擎/语言/说话人分离字段与创建校验"` → `aed2486`, 11 files changed, 149 insertions, 13 deletions. Working tree clean.

## Deviations / notes

1. **Smoke data-dir override**: the brief's smoke command (`cd backend && ./mvnw spring-boot:run`) resolves `app.data-dir=./data` → `backend/data`, which did not exist; the legacy DB lives at repo-root `data/`. To satisfy the constraint "SchemaMigrator 会实际执行旧库 ALTER", the smoke was run with `-Dapp.data-dir=../data`. Only the data dir differs; the request and assertions are exactly the brief's.
2. **`schema.sql` intentionally NOT modified** (brief's file list excludes it). This is safe by design: `SchemaMigrator` adds the columns on every startup where they're missing — covering both legacy DBs and fresh DBs created by the old `schema.sql`. Verified in practice: fresh-test DBs (Spring Boot tests) and the legacy DB both end up with the columns before any insert can happen (ApplicationRunner runs after DataSource init, before any request).
3. **`@Order(1)` placement**: `StartupRecovery` is the only other `ApplicationRunner` (no explicit order = lowest precedence), so migrations always run before stale-task recovery. No conflicts.
4. **Smoke side effects cleaned**: the POST inserted a task row into the legacy DB; that row (and stage rows/dir) were removed post-verification so the user's data is unchanged apart from the schema migration itself. Backup remains at `/var/folders/cs/qnxf9q7s0r90c2rvgzhn1z1r0000gn/T/opencode/wave-miner.db.bak`.
5. **No engine beans exist yet in main code** (`TranscriptionEngine` has no implementations as of this task), so TRANSCRIBE-stage validation in the full Spring context would reject everything with "未知转写引擎: minimax" — expected until Task 4+ lands the minimax engine; no current test creates TRANSCRIBE tasks via the API.

## Concerns
- None blocking. Item 5 above is the only forward-looking note.
