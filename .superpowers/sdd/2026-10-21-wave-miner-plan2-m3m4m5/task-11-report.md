# Task 11 实现报告：前端二（时间轴联动 + SSE 订阅 + engine_raw 查看器）

状态：DONE_WITH_CONCERNS
提交：`8135679` feat: 任务详情时间轴对照、SSE 进度与引擎原始响应查看

## 做了什么

仅改动 `frontend/src/views/TaskDetailView.vue`（+171/-8），在原详情页上扩展：

1. **SSE 订阅接管进度刷新**（替换原 `setInterval(load, 2000)` 无条件轮询）：
   - `onMounted` 建 `new EventSource(api.sseUrl(id))`。
   - 收到 `type=="task"` 终态帧 → 关闭 ES、`load()` 刷新终态。
   - 其余帧 → 300ms 防抖 `load()`（连带 `loadTranscript()`，转写产物就绪后自动出现）。
   - `onerror` → 关闭 ES 并启动 2s 轮询兜底，轮询发现任务终态后自动停止。
   - `onBeforeUnmount` 清理 ES / 轮询 / 防抖定时器。
   - `load()` 成功路径末尾 `await loadTranscript()`；失败路径由原 `clearInterval(timer)` 改为 `stopPolling()`。
2. **时间轴对照卡片**（存在 `transcript.json` 产物时显示，位于在线预览卡与产物下载卡之间）：
   - 左侧媒体（优先 `audio.m4a`，其次按可播放扩展名匹配），`@timeupdate` → `findActiveSegment` 更新 `activeIndex`。
   - 当前段高亮（背景 + 左边框），并经 `watch(activeIndex)` `scrollIntoView({block:'nearest'})` 滚动到可视区。
   - 右侧分段列表：`[formatTimecode(start) - formatTimecode(end)]` + 说话人标签（`说话人 {speaker+1}`）+ 文本；点击段 → `clampSeekMs(seg)/1000` seek 并播放。
3. **engine_raw 查看器**：产物表 `engine_raw/` 行追加"查看"按钮 → `el-dialog` 内 `<pre>` 展示格式化 JSON（`JSON.stringify(x, null, 2)`），加载失败显示中文错误文案。

## 关键核实：brief 代码与后端契约的冲突（本任务最重要的偏离）

Brief 的 `startSse` 片段只用 `es.onmessage`（仅接收**默认无名字事件**），但 brief 自身 Consumes 段与后端实现（`TaskSseRegistry.java:47,63`，`SseEmitter.event().name("progress")`）都表明后端所有帧（含首帧 connected）都是**命名事件 `event: progress`** —— 只挂 `onmessage` 时回调永远不会触发，SSE 功能完全失效。

处理：新增 `es.addEventListener('progress', ...)` 接收命名帧，同时保留 `es.onmessage`（两者共用提取出的 `handleSseData`），对两种事件风格都健壮。若后续后端改发默认事件，前端无需改动。

## 测试结果

- `cd frontend; npm run test` → **3 个测试文件、11 个用例全部通过**（含 client.test.ts 中 artifactUrl 分段编码的 3 个回归用例；timeline.test.ts 时间轴工具回归）。
- `cd frontend; npm run build`（`vue-tsc -b && vite build`）→ **干净通过**（仅原有的 chunk >500kB 警告，与本任务无关）。
- 组件行为按 brief 无组件测试要求：`vue-tsc` 通过 + 上述回归全绿；未做浏览器端手动验证（本地无运行中的后端/任务数据）。

## 偏离清单（相对 brief）

1. **SSE 监听方式**：如上，增加 `addEventListener('progress')`；brief 片段仅 `onmessage`。依据 brief 自己的 Consumes 契约（`event: progress`）与后端源码，属必要修正。
2. **删除 `rawArtifacts` computed**：brief 脚本片段定义了它，但模板片段从未使用（按钮直接判 `row.name.startsWith('engine_raw/')`）；项目 `noUnusedLocals: true` 下 `vue-tsc` 直接报 TS6133 编译失败，故移除。
3. **分段列表容器加 `ref="segmentListRef"` + `watch(activeIndex)` 滚动逻辑**：brief Produces 要求"当前段高亮且滚动到可视区"，但其模板片段只实现了高亮，补齐滚动。
4. **产物表"操作"列宽 120 → 180**：容纳"下载 + 查看"两个按钮（brief 未说明布局适配）。

## 自查发现（不阻塞，建议后续任务关注）

1. **重试后的进度更新空窗**：终态事件到来后 ES 已按 brief 关闭；用户此时在详情页点"重试"，任务转 RUNNING 但既无 SSE（已关闭）也无轮询兜底（未启动），进度卡会静止到手动刷新页面。旧代码（无条件 2s 轮询）无此问题。brief 明确"保留现有 retry/cancel"故未擅自加逻辑；一行修复（`retry()` 成功后 `if (!es) startSse()`）可解决，建议记入后续任务或评审确认。
2. `onMounted` 的 `load().then(loadTranscript)` 与 `load()` 内部的 `await loadTranscript()` 会使挂载时转写文件重复拉取一次（brief 两处均如此规定，无害，保留 brief 原样）。
3. 查看已终态任务的页面会保持一条空闲 SSE 长连接（靠 15s 心跳保活）；本地单机工具规模下可接受，与 brief 设计一致。

## Step 1 说明

`client.ts` 的 `artifactUrl` 按 `/` 分段编码在前序提交 `813d6cb` 已落地并有 3 个测试覆盖（`engine_raw/minimax.json`、特殊字符分段编码、token 追加），本次零改动，测试回归通过。

---

# Fix round 1

状态：DONE
提交：`2e55b18` fix: 重试后重建 SSE 订阅恢复实时进度

## 审查问题

即自查发现第 1 条（审查确认为 Important）：任务到达终态时后端 `TaskSseRegistry` 完成并关闭 SSE 流（`TaskSseRegistry.java:64-67`），前端 ES 经 `onerror`/终态帧关闭且轮询兜底随终态自停，页面处于"无 ES、无轮询"的静止态。此时点"重试"，任务复活为 PENDING/RUNNING，但没有任何刷新机制，进度卡静止到手动刷新。

## 修复

仅改动 `frontend/src/views/TaskDetailView.vue` 的 `retry()`（+2 行）：

```ts
await api.retryTask(id)
await load()
// 终态时后端已关闭 SSE，任务复活后需重建订阅，否则进度卡静止
if (!es) startSse()
```

- 与原报告提议一致，沿用现有 `startSse`/`stopPolling` 结构，未引入新机制。
- `if (!es)` 防御重复订阅：终态后 `es` 必为 `null`（终态帧路径 `handleSseData` 主动 close，断流路径 `onerror` 置空），仅异常残留时跳过。
- **终态关闭逻辑对新订阅依然生效**：新 ES 复用同一模块级 `es` 变量与 `handleSseData`，再次收到终态 `type=="task"` 帧仍会关闭 ES 并 `load()`；若后端直接断流，`onerror` → 轮询兜底 → 终态自停，闭环不变。
- 重试 API 失败分支不重建订阅（任务仍处终态，无可跟踪内容）。

## 其它"复活"路径核对

- **取消按钮**：`disabled="!isRunning"`（仅 RUNNING/PENDING 可点），取消只会把任务推向 CANCELLED 终态，不可能复活终态任务，无需处理。
- **其余交互**（seekTo/showRaw/下载）均只读。页面唯一复活路径就是重试按钮（仅 FAILED/CANCELLED 可点），已覆盖。

## 测试结果

- `cd frontend; npm run test` → 3 个测试文件、11 个用例全部通过。
- `cd frontend; npm run build`（`vue-tsc -b && vite build`）→ 通过（仅原有 chunk >500kB 警告）。

## 疑虑

无组件级测试覆盖 SSE 生命周期（项目无组件测试设施），本次依赖 vue-tsc + 既有回归 + 后端终态源码推演；建议后续引入组件测试时补一条"终态→重试→恢复订阅"用例。
