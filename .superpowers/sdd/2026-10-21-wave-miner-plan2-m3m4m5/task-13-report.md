# T13 验收缺陷修复 #1：拦截器 NPE

日期：2026-10-21
提交：`6b380dd` `fix: 缺少访问令牌时返回 401 而非 500（拦截器空指针）`

## 缺陷

真机验收发现：请求既无 `X-Access-Token` header 也无 `?token=` 参数时，`AccessTokenInterceptor.preHandle` 中 `given` 为 null，`given.getBytes(...)` 抛 NPE，经全局兜底转为 500 INTERNAL。预期为 401 + JSON `{"code":"UNAUTHORIZED","message":"缺少或错误的访问令牌"}`。

## 修复（TDD 先红后绿）

1. **RED**：新增 `backend/src/test/java/com/waveminer/waveminer/api/AccessTokenInterceptorTest.java`（纯单元测试，`MockHttpServletRequest`/`MockHttpServletResponse` + 真实 `AppProperties`），5 个用例：
   - 两者都缺失 → false + 401（修复前 NPE 红灯，实锤 `AccessTokenInterceptor.java:25`）
   - header 正确 → true
   - header 错误 → false + 401
   - header 缺失但 `?token=` 正确 → true
   - accessToken 空串（未启用鉴权）→ true
2. **GREEN**：`AccessTokenInterceptor.java` 在恒时比较前补 `if (given == null) given = "";`，缺失按空串处理落入 401 分支；`MessageDigest.isEqual` 恒时比较与 401 响应体不变。

## 验证

- 单测：`./mvnw -q test -Dtest=AccessTokenInterceptorTest` → 5/5 绿
- 全量：`./mvnw -q test` → 22 个测试类、124 tests、0 failures、0 errors（原 119 + 新增 5）

## 改动范围

- `backend/src/main/java/com/waveminer/waveminer/api/AccessTokenInterceptor.java`（+1 行兜底）
- `backend/src/test/java/com/waveminer/waveminer/api/AccessTokenInterceptorTest.java`（新增）

# T13 验收缺陷修复 #2：产物多段路径 404

日期：2026-10-21
提交：`ddbf9a9` `fix: 产物下载支持含子目录的名称（engine_raw/ 多段路径）`

## 缺陷

真机验收发现：`TaskController.java:153` 的 `@GetMapping("/{id}/artifacts/{name:.+}")` 在 Spring Boot 4 默认 PathPatternParser 下，`{name:.+}` 只匹配单段路径，`GET /api/tasks/{id}/artifacts/engine_raw/minimax.json`（T8 引擎原始响应存档，含 `/` 的多段产物名）落到静态资源处理器 → 404「接口不存在」。单段产物（audio.m4a 等）正常。计划原文假设 `/{name:.+}` 能跨段，在该 Spring 版本不成立——计划缺陷。

## 修复（TDD 先红后绿）

1. **RED**：`TaskApiTest` 新增 `artifactWithMultiSegmentNameDownloads`：mock FfmpegService 建 UPLOAD 任务（stages=AUDIO_EXTRACT）走完管线，`store.taskDir(id)/engine_raw/probe.json` 写入 `{"raw":true}`，断言 `GET /api/tasks/{id}/artifacts/engine_raw/probe.json` 200 且 `$.raw=true`。修复前实测 404「接口不存在」，与真机一致（TaskApiTest.java:218）。
2. **GREEN**：路由改为 PathPattern 捕获剩余语法 `@GetMapping("/{id}/artifacts/{*name}")`；`{*name}` 捕获值自带前导 `/`（如 `/engine_raw/probe.json`），方法内先剥掉再走原有校验链（isAllowedArtifactName → resolve/normalize 越界检查 → Range 逻辑全部不变，安全模型未改动）。
3. **回归**：同用例内断言单段产物 `audio.m4a` 仍 200 且带 `Accept-Ranges: bytes`。

## 验证

- 单测：`./mvnw -q test -Dtest=TaskApiTest` → 13/13 绿
- 全量：`./mvnw -q test` → EXIT=0、125 tests、0 failures、0 errors（原 124 + 新增 1）

## 改动范围

- `backend/src/main/java/com/waveminer/waveminer/api/TaskController.java`（路由改 `{*name}` + 剥前导 `/`）
- `backend/src/test/java/com/waveminer/waveminer/api/TaskApiTest.java`（+1 用例，含多段与单段回归断言）

# T13 验收缺口补齐 #3：设置页

日期：2026-10-21
提交：`fd2a8cf` `feat: 设置页（API Key 查看/更新/清除）`

## 缺口

真机验收发现：T1 后端设置 API（GET/PUT `/api/settings`）与 T10 前端 client 方法（`api.getSettings`/`api.putSetting`）均已就绪，但计划中没有任何任务负责创建设置页 UI；而计划 Task 13 验收明确要求"在设置页填真实 key"。计划任务划分缺口，本次补齐。

## 实现

- 新建 `frontend/src/views/SettingsView.vue`，风格对齐 NewTaskView（el-card 包裹、中文文案）：
  - onMounted 调 `api.getSettings()` 渲染后端返回的 key 列表（不写死 key 列表）
  - 每行：key 的中文标签映射（`minimax.apiKey` → "MiniMax 转写 API Key"，`aliyun.apiKey` → "阿里云百炼 API Key"，未知 key 显示原 key）+ 原始 key 灰字 + configured 状态 el-tag（已配置/未配置）+ `el-input show-password`（已配置时 placeholder 显示掩码值并提示"留空表示不修改"）
  - "保存"：仅非空输入调 `api.putSetting(key, value)`，成功 `ElMessage.success("已保存")` 并刷新列表清空输入；空输入 warning 不发请求；失败 `ElMessage.error(err.message)`
  - "清除"：`api.putSetting(key, "")`（空串即清除），成功提示"已清除"，未配置时禁用
  - 顶部 el-alert 说明：key 存储在本机 SQLite 仅掩码显示；也可用环境变量 WAVEMINER_MINIMAX_API_KEY / WAVEMINER_ALIYUN_API_KEY（兼容 DASHSCOPE_API_KEY）
- `frontend/src/router.ts`：新增路由 `{ path: '/settings', component: SettingsView }`
- `frontend/src/App.vue`：el-menu 加 `<el-menu-item index="/settings">设置</el-menu-item>`

## 验证

- `cd frontend; npm run test` → 3 个测试文件、11/11 用例全绿（无新增组件测试，项目无组件测试设施）
- `npm run build`（vue-tsc -b && vite build）→ 通过（chunk >500kB 警告为既有现象，与本次改动无关）

## 改动范围

- `frontend/src/views/SettingsView.vue`（新增）
- `frontend/src/router.ts`（+2 行）
- `frontend/src/App.vue`（+1 行）

# T13 验收缺陷修复 #4：阿里云上传凭证信封

日期：2026-10-21
提交：`6a67848` `fix: 阿里云上传凭证解析顶层 data 字段（对齐真实响应信封）`

## 缺陷

真机验收发现：阿里云百炼引擎转写报错「获取上传凭证失败：响应缺少 output.data」，而原始响应存档为 200 + 完整凭证。`AliyunFiletransEngine.java:244` 的 `parsePolicy` 按 `root.path("output").path("data")` 取凭证——计划 T8 把 `GET /uploads?action=getPolicy` 的响应信封误当成转写任务接口的 `{output:{...}}` 信封；真实 DashScope 返回**顶层 `data`**（`{"data":{policy/signature/upload_dir/upload_host/...},"request_id":...}`），字段名全部一致，仅信封路径错。提交（`output.task_id`）与轮询（`output.task_status`）的 `output` 信封正确，未改动。

## 修复（TDD 先红后绿）

1. **RED**：更新 `AliyunFiletransEngineTest`：
   - `POLICY_JSON` 常量由 `{"output":{"data":{...}}}` 改为真实信封 `{"data":{...},"request_id":"r1"}`（合成字段值不变，`hermeticPolicy` 的 WireMock 指向 `wire.baseUrl()` 不变）
   - 原 `policyErrorFails` 拆分：`codeErrorFails`（错误码分支，断言含「上传凭证」，保留覆盖）+ `missingDataFails`（`{"request_id":"r"}` 缺顶层 data，断言含「响应缺少 data」）
   - 新增回归 `parsesRealDevicePolicyIgnoringExtraFields`：与真机完全同构的响应（凭证在顶层 data，含 `expire_in_seconds`/`max_file_size_mb`/`capacity_limit_mb` 多余字段），断言 7 个凭证字段全部正常解析——多余字段不得干扰
   - 修复前实测：6/9 红，全部失败于「获取上传凭证失败：响应缺少 output.data」，与真机报错同因
2. **GREEN**：`parsePolicy` 第 244 行改为 `root.path("data")`，缺字段错误消息改为「获取上传凭证失败：响应缺少 data」。

## 验证

- 单测：`./mvnw -q test -Dtest=AliyunFiletransEngineTest` → 先红（6 失败/3 错误）后绿 9/9
- 全量：`./mvnw -q test` → 127 tests、0 failures、0 errors（基线 125 + 新增 2：codeErrorFails 拆分 +1、真机回归用例 +1）

## 改动范围

- `backend/src/main/java/com/waveminer/waveminer/engine/AliyunFiletransEngine.java`（parsePolicy 信封路径 + 错误消息）
- `backend/src/test/java/com/waveminer/waveminer/engine/AliyunFiletransEngineTest.java`（POLICY_JSON 真实信封 + 用例拆分/新增）

# T13 验收缺陷修复 #5：file_urls 缺 bucket

日期：2026-10-21
提交：`2d1234e` `fix: 阿里云提交 file_urls 补全 OSS bucket 段（对齐 dashscope-file-mgr）`

## 缺陷

真机验收发现：上传 OSS 成功后提交转写任务被拒——任务 FAILED，报错「阿里云转写任务失败（FAILED）: REQUEST_INVALID_FILE_URL_VALUE」，原始响应已存档。根因：`AliyunFiletransEngine.java:101` 提交时 `submitRequest(key, "oss://" + objectKey, ...)`，而 objectKey = `uploadDir + "/" + filename`（形如 `dashscope-instant/<workspace>/<date>/<uuid>/file.mp3`），拼出的 URL `oss://dashscope-instant/...` 把 upload_dir 首段当成了 bucket——但 upload_dir 是 bucket 内的对象前缀，不是 bucket 名。真机 getPolicy 响应实锤：`upload_host = https://dashscope-file-mgr.oss-cn-beijing.aliyuncs.com`，policy 解码后 conditions 含 `{"bucket":"dashscope-file-mgr"}`，即上传目标 bucket 是 `dashscope-file-mgr`（与 upload_host 首个 DNS 标签一致），正确 file_url 形态为 `oss://dashscope-file-mgr/<objectKey>`。

## 修复（TDD 先红后绿）

1. **RED**：更新 `AliyunFiletransEngineTest`：
   - 全流程用例 `fullFlowUploadSubmitPollDownload` 的 submit body 期望由 `oss://asr/filetrans/2026-10-21/abc/a.m4a` 改为 `oss://127/asr/filetrans/2026-10-21/abc/a.m4a`——hermetic upload_host 由 `wire.baseUrl()+"/upload"`（`localhost` 无点）改为 `http://127.0.0.1:<port>/upload`（WireMock 默认绑回环仍可拦截，host 带点可按首段推导 bucket，首段即 `127`）
   - 修复前实测：`fullFlowUploadSubmitPollDownload` 红，实际 body 为 `oss://asr/...`（缺 bucket 段），与真机 REQUEST_INVALID_FILE_URL_VALUE 同因
2. **GREEN**：`AliyunFiletransEngine` 第 101 行改为 `submitRequest(key, "oss://" + ossBucketOf(policy.uploadHost()) + "/" + objectKey, ...)`；新增包内静态 `ossBucketOf(uploadHost)`：`URI` 取 host，按第一个 `.` 分割取首段为 bucket；host 为空或无点时抛 `EngineException("上传凭证的 upload_host 异常: " + uploadHost, null)`（2 参构造，rawResponse 为 null）
3. 新增两条钉住推导规则的用例：`bucketDerivedFromUploadHostFirstDnsLabel`（真机 host `https://dashscope-file-mgr.oss-cn-beijing.aliyuncs.com` → `dashscope-file-mgr`）、`uploadHostWithoutDotFails`（`http://localhost:9000/upload` → 报「上传凭证的 upload_host 异常」）

## 验证

- 单测：`./mvnw -q test -Dtest=AliyunFiletransEngineTest` → 先红（1 失败：file_urls 缺 bucket）后绿 11/11
- 全量：`./mvnw -q test` → 129 tests、0 failures、0 errors（基线 127 + 新增 2）

## 改动范围

- `backend/src/main/java/com/waveminer/waveminer/engine/AliyunFiletransEngine.java`（file_urls 补 bucket 段 + ossBucketOf 推导/校验）
- `backend/src/test/java/com/waveminer/waveminer/engine/AliyunFiletransEngineTest.java`（file_urls 期望 + hermetic host 改 127.0.0.1 + 新增 2 用例）

# T13 验收缺陷修复 #6：OssResourceResolve 头

日期：2026-10-21
提交：`c242ab5` `fix: 阿里云提交补 X-DashScope-OssResourceResolve 头并回退 oss:// 无 bucket 形态`

## 缺陷

真机实锤：#5 的 bucket 前缀修复不解决问题——阿里云 filetrans 提交仍被拒 `REQUEST_INVALID_FILE_URL_VALUE`。官方语义证据（工作良好的同类开源实现 maw/asr/qwen.py，其注释与官方文档一致）：`oss://` URL **不带 bucket 前缀**，形如 `f"oss://{key}"`；filetrans 接口需请求头 **`X-DashScope-OssResourceResolve: enable`** 才能解析该格式（DashScope 将其解析为临时上传存储里的对象）。#5 从 upload_host 推导 bucket 的方向错误，且计划 T8 漏掉了该请求头。

## 修复（TDD 先红后绿）

1. **RED**：更新 `AliyunFiletransEngineTest`：
   - 全流程用例 `fullFlowUploadSubmitPollDownload` 新增断言 `submitEvent.getRequest().getHeader("X-DashScope-OssResourceResolve")` 为 `"enable"`（修复前为 null → 红）
   - file_urls 断言改回无 bucket 形态：`"file_urls":["oss://asr/filetrans/2026-10-21/abc/a.m4a"]`（不带 bucket 前缀；#5 的 bucket 推导断言并入该全流程断言）
   - 删除 #5 引入的两条 bucket 推导用例（`bucketDerivedFromUploadHostFirstDnsLabel`、`uploadHostWithoutDotFails`，随 `ossBucketOf` 一并移除）；`hermeticPolicy` 注释去掉 bucket 推导说明
   - 修复前实测：`fullFlowUploadSubmitPollDownload` 红，失败于 `expected: "enable" but was: null`（缺请求头）
2. **GREEN**：`AliyunFiletransEngine`：
   - transcribe() 提交改回 `submitRequest(key, "oss://" + objectKey, options)`（objectKey = uploadDir + "/" + 文件名，即 OSS Post Object 的 `key` 表单值）
   - submitRequest() 提交请求加 `.header("X-DashScope-OssResourceResolve", "enable")`
   - 删除 #5 引入的 `ossBucketOf(uploadHost)` 推导方法及其异常——不再需要

## 验证

- 单测：`./mvnw -q test -Dtest=AliyunFiletransEngineTest` → 先红（1 失败：缺 OssResourceResolve 头）后绿（0 失败 0 错误）
- 全量：`./mvnw test` → 127 tests、0 failures、0 errors（基线 129 − 删除 2 条 bucket 推导用例，全绿为过）

## 改动范围

- `backend/src/main/java/com/waveminer/waveminer/engine/AliyunFiletransEngine.java`（补 OssResourceResolve 头 + file_urls 回退无 bucket 形态 + 删 ossBucketOf）
- `backend/src/test/java/com/waveminer/waveminer/engine/AliyunFiletransEngineTest.java`（新增头断言 + file_urls 无 bucket 断言 + 删 2 条 bucket 用例）


