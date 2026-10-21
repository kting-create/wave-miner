# Task 8 Report: 阿里云百录 filetrans 引擎（上传凭证 + OSS 上传 + 轮询）

**Status:** COMPLETE
**Commit:** `c3eef9e` — `feat: 阿里云百炼录音文件转写引擎（上传凭证+OSS+轮询）`
**Files:**
- Created: `backend/src/main/java/com/waveminer/waveminer/engine/AliyunFiletransEngine.java`
- Modified: `backend/src/main/java/com/waveminer/waveminer/config/EngineConfig.java`（追加 `aliyunFiletransEngine` bean）
- Created: `backend/src/test/java/com/waveminer/waveminer/engine/AliyunFiletransEngineTest.java`

## TDD 过程

1. **Step 1 (RED):** 写解析单测（`parsesUploadPolicy` / `policyErrorFails` / `parsesTranscriptionSentences`）→ 编译失败（类不存在），符合预期。
2. **Step 2 (GREEN):** 实现 `UploadPolicy` record、`parsePolicy`、`parseTranscription`（`transcribe` 暂以 stub 占位满足接口）→ 3/3 PASS。中途补了一个遗漏的 `java.nio.file.Path` import。
3. **Step 3 (RED):** 追加 WireMock 全流程用例（`fullFlowUploadSubmitPollDownload` / `subtaskFailureSurfacesCodeAndMessage` / `missingKeyFailsEarly`）→ 3 失败/错误（stub 抛 `UnsupportedOperationException`），符合预期。
   - 测试文件有一处编译错误：brief 的 `""".formatted(wire.baseUrl())));` 少一个右括号（`stubFor(`/`willReturn(`/`withBody(`/`formatted(` 共 4 层未闭合，仅 3 个 `)`）。已补为 `))))`。
4. **Step 4 (GREEN):** 实现 `transcribe` 主流程（凭证获取 → OSS multipart 上传 → 提交异步任务 → 轮询 → 下载结果 → `parseTranscription`）及 `EngineConfig` bean → 6/6 PASS。
   - 编辑时曾把 Step 2 已有的 parse 方法意外移到类外导致括号失衡，已修复（方法保留在类内，仅删除多余的类闭合括号）。
5. **Step 5:** 全量 `cd backend && ./mvnw -q test` → **Tests run: 116, Failures: 0, Errors: 0, BUILD SUCCESS**。
6. **Step 6:** 按指定提交信息 commit。

## 与 brief 的偏差（已记录）

**全流程用例的 upload_host 改为指向 WireMock（hermetic 修正）。**
brief 的全流程用例直接复用 `POLICY_JSON` 常量（`upload_host` 为真实外网地址 `https://dashscope-file-transfer.oss-cn-beijing.aliyuncs.com`），同时又在 WireMock 上 stub `POST /upload`。实际运行时 OSS 上传请求发往真实外网并收到 HTTP 404（两次全流程用例均因此失败）。修复方式：新增测试辅助方法

```java
private String hermeticPolicy(WireMockServer wire) {
    return POLICY_JSON.replace("https://dashscope-file-transfer.oss-cn-beijing.aliyuncs.com",
            wire.baseUrl() + "/upload");
}
```

两个全流程用例的 getPolicy stub 改为返回 `hermeticPolicy(wire)`。brief 的其余 stub 与断言（`/upload` 事件、multipart 字段名与顺序、`oss://` objectKey、`X-DashScope-Async`、`language_hints`、`diarization_enabled`）全部保持不变。解析单测仍直接用原始 `POLICY_JSON` 断言真实 host 值。

## 实现要点

- `AliyunFiletransEngine implements TranscriptionEngine`：`id()="aliyun-filetrans"`，`info()` 按规格（12h 时长上限、2GB 大小、支持说话人分离、8 种语言）。
- `parsePolicy`：顶层含 `code` → `EngineException("获取上传凭证失败: {code} {message}", json)`；缺 `output.data`/`upload_host` → 报错；否则映射 7 个凭证字段。
- `parseTranscription`：遍历 `transcripts[].sentences[]` → `Segment(begin_time, end_time, text, speaker_id?)`；缺 `transcripts` → 报错。
- `transcribe`：key 缺失早失败 → `GET /uploads?action=getPolicy&model=` → multipart 上传（字段顺序 `OSSAccessKeyId/Signature/policy/key/x-oss-object-acl/x-oss-forbid-overwrite/success_action_status/x-oss-content-type/file`，MIME 按扩展名，未知用 `application/octet-stream`）→ `POST /services/audio/asr/transcription`（`X-DashScope-Async: enable`，ObjectNode 组装 `model/input.file_urls/parameters.channel_id/language_hints/diarization_enabled`）→ 轮询 `POST /tasks/{task_id}`（PENDING/RUNNING 继续，SUCCEEDED 检查 `results[0].subtask_status` 后无鉴权 GET `transcription_url`，其余状态报 `阿里云转写任务失败（{status}）: {code} {message}`，超时报 `转写任务超时（已等待 n 秒）`）→ `TranscribeResult(parseTranscription(json), json)`。
- 每阶段前 `checkCancelled` 抛 `EngineCancelledException("转写已取消")`；引擎错误路径（提交/轮询/子任务失败/下载）消息带「原始响应已存档到 engine_raw/」并携带 raw body。
- 轮询 sleep 使用 `Math.max(0, interval) * 1000L`（brief 提示，兼容测试中 interval=0）。
- Jackson 3（`tools.jackson.databind`），无 Lombok，错误消息中文。

## 验证

- `AliyunFiletransEngineTest`：6/6 PASS（3 解析 + 3 全流程）。
- 全量后端套件：`Tests run: 116, Failures: 0, Errors: 0`，BUILD SUCCESS。

## Fix Round 1（审查 Finding 1 修复）

**Finding 1（Important）**：`downloadResult` 非 200 路径的 `EngineException` 缺少 raw body 与「原始响应已存档到 engine_raw/」存档提示，违反提交/轮询/下载失败路径需携带 `resp.body()` 作为 rawResponse 并附存档提示的约束。

**修复**（AliyunFiletransEngine.java `downloadResult`）：

```java
throw new EngineException("下载转写结果失败（HTTP " + resp.statusCode() + "）。"
        + "原始响应已存档到 engine_raw/", resp.body());
```

**新增测试**：`AliyunFiletransEngineTest.downloadFailureCarriesRawResponseAndArchiveHint` —— 全流程 stub 至 `transcription_url` 下载步骤，stub 返回 HTTP 404（注意：5xx 会被 `EngineHttpClient` 重试层拦截并抛出自身异常，`downloadResult` 非 200 分支仅可由 4xx 触达），断言：

- `EngineException.getMessage()` 同时包含「下载转写结果失败」与「原始响应已存档到 engine_raw/」
- `getRawResponse()` 非 null 且包含 stub 的 raw body（`oss-error`）

**验证命令与结果**：

- `cd backend && ./mvnw -q test -Dtest=AliyunFiletransEngineTest` → 7/7 PASS。
- `cd backend && ./mvnw test` → `Tests run: 117, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
