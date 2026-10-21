# Task 10 实现报告：前端扩展（一）类型/客户端/时间轴工具 + 新建任务转写卡

## 状态
DONE（commit `bbbb6cc` — feat: 新建任务转写引擎/语言配置与时间轴工具）

## 做了什么

### Step 1-2（TDD）：timeline 工具
- 先写 `frontend/src/utils/timeline.test.ts`（brief 原样测试代码），运行 `npm run test` 确认 FAIL（`Cannot find module './timeline'`）。
- 实现 `frontend/src/utils/timeline.ts`：
  - `formatTimecode(ms)`：非法/负值返回 `00:00`，`mm:ss`，≥1h 时 `h:mm:ss`（与后端 TranscriptFormat.timecode 一致）。
  - `findActiveSegment(segments, timeMs)`：线性扫描命中 `[startMs, endMs)` 返回下标，否则 -1。
  - `clampSeekMs(seg)`：`Math.max(0, seg.startMs)`。
- 重跑测试 PASS。

### Step 3：types.ts / client.ts 扩展
- `types.ts`：
  - `TaskView` 在 `errorMessage` 之后插入 `engineId: string | null`、`language: string | null`、`diarize: boolean`。
  - 新增 `EngineInfo`、`TranscriptSegment`、`Transcript`、`SettingView` 四个接口（字段与 brief 一致）。
- `client.ts`：`api` 对象追加 `getEngines()`、`getSettings()`、`putSetting(key, value)`（PUT JSON body `{key, value}`）、`sseUrl(taskId)`（`/api/tasks/{id}/events` + `?token=`，无 token 时省略参数）；import 补充 `EngineInfo, SettingView`。

### Step 4：NewTaskView 转写卡
- `<script setup>`：补 `computed` 导入；新增 `engines/engineId('minimax')/language('auto')/diarize` 状态、`hasTranscribe/currentEngine/languageOptions` 计算属性、`LANG_LABELS` 中文语言映射表、`loadEngines()`（失败静默，提交时后端仍校验）、`engineId` watch（切换引擎后语言不在新列表则回退 `auto`）、`onMounted(loadEngines)`。
- 模板：勾选组追加 `TRANSCRIBE`（转写（输入：音频/视频））；勾选组与提交按钮之间插入"转写设置"卡片（`v-if="hasTranscribe"`）：引擎下拉（label `displayName（≤X 分钟 / YMB）`）、未配置 API Key 时 el-alert 中文警告、语言下拉（自动检测 + engine.languages）、`supportsDiarization` 时显示"说话人分离"开关。
- `submit()`：`hasTranscribe` 时向 FormData 追加 `engineId/language/diarize`（diarize 转 String）。

### Step 5-6：验证与提交
- 见下方测试结果；仅 `git add frontend` 提交，commit message 用 brief 指定文案。

## 测试结果
- `npm run test`（vitest run）：**2 个文件 / 8 个用例全部通过**（timeline 4 + format 4；TDD 红灯阶段已确认 FAIL → 实现后转绿）。
- `npm run build`（`vue-tsc -b && vite build`）：**通过，无类型错误**。构建产物正常输出；仅出现原有的 >500kB chunk 警告（本次改动前已存在，非错误）。

## 偏离清单
1. **`putSetting` 实现以 brief 为准，与交接上下文描述不一致**：交接说明称后端契约是 `PUT /api/settings/{key}` + body `{"value": "..."}`，但 brief 代码是 `PUT /api/settings` + body `{key, value}`。已核对后端 `backend/.../api/SettingController.java`（`@PutMapping` 无路径参数，`PutRequest(key, value)`），brief 代码与实际后端一致，故照 brief 实现，无需改动。交接上下文该条为过时描述。
2. `onMounted(loadEngines)` 以独立 hook 形式追加（文件原有 `onMounted(loadTools)`），Vue 支持多个 onMounted，行为一致。
3. 模板部分为使缩进对齐现有嵌套结构，卡片内元素整体多一层缩进；内容与 brief 逐字一致。

## 自查发现
- `git status`/`git diff HEAD` 复核：frontend 5 个文件（client.ts、types.ts、NewTaskView.vue、timeline.ts、timeline.test.ts）全部已入库；工作区仅剩 `.superpowers/` 下的 progress.md 修改与未跟踪的 brief/report 文件（编排器工作区文件，不属于本任务提交范围，brief 指定仅 `git add frontend`）。
- 后端零改动；未新增任何依赖；无子代理；文案全中文。
- `sseUrl` 本任务仅交付（T11 才接 SSE），`getSettings`/`putSetting` 供设置页（后续任务）使用，均已在 client.ts 备好。

## Fix round 1（审查补遗：artifactUrl 按 `/` 分段编码）

### 问题
审查发现 `client.ts` 的 `artifactUrl(taskId, name)` 对整个 name 单次 `encodeURIComponent`：name 含 `/`（如 `engine_raw/minimax.json`）时会编码为 `%2F`，Tomcat 默认拒绝含 `%2F` 的路径（计划 Ruling 9 / 交接文档 §2 要求按 `/` 分段编码）。brief 步骤漏列了此项。

### 修复（TDD）
1. **RED**：新建 `frontend/src/api/client.test.ts`（vitest，风格对齐 `format.test.ts`），3 个用例：
   - `engine_raw/minimax.json` → `/api/tasks/t1/artifacts/engine_raw/minimax.json`（`/` 保留、各段编码）；
   - `engine_raw/bad name!.json` → `/api/tasks/t1/artifacts/engine_raw/bad%20name!.json`（空格编码、`/` 不编码）；
   - `setToken('abc')` 后追加 `?token=abc`（localStorage 在测试内用 `vi.stubGlobal` 以 Map 后端 stub，vitest 默认 node 环境无 localStorage；`afterEach` 清空 store 保证隔离）。
   - 运行 `npm run test`：3 个用例全部 FAIL，失败原因符合预期（收到 `%2F`）。
2. **GREEN**：`artifactUrl` 改为 `name.split('/').map(encodeURIComponent).join('/')`；token 逻辑不变。重跑测试全绿。
3. 验证：`npm run test` **3 文件 / 11 用例全部通过**；`npm run build` 通过（仅原有的 >500kB chunk 警告）。

### 提交
- commit `813d6cb`（fix: artifactUrl 按 / 分段编码避免 Tomcat 拒绝 %2F），仅含 `frontend/src/api/client.ts`（+2/-1）与新增 `frontend/src/api/client.test.ts`；`.superpowers/` 编排器文件未入库；未派发子代理。
