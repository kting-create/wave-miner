### Task 11: 前端扩展（二）：任务详情时间轴对照 + SSE 订阅 + engine_raw 查看器

**Files:**
- Modify: `frontend/src/views/TaskDetailView.vue`、`frontend/src/api/client.ts`（artifactUrl 分段编码）
- Test: 无新增组件测试（`vue-tsc && vite build` + vitest 回归）

**Interfaces:**
- Consumes: Task 9 SSE（`event: progress`，data JSON `{taskId,type,stage,status,message}`；`type=="task"` 为终态，服务端随后 complete）、Task 10 类型/工具、`transcript.json`/`engine_raw/*` 产物下载接口（`{name:.+}`）
- Produces:
  - `client.ts` 的 `artifactUrl` 改为按 `/` 分段编码：`name.split('/').map(encodeURIComponent).join('/')`（其余逻辑不变）
  - 详情页新增"时间轴对照"卡片（任务有 `transcript.json` 产物时显示）：
    - 加载 `api.artifactUrl(id, 'transcript.json')` → `resp.json()` → `Transcript`
    - 左侧 `<audio>`/`<video>`（优先 `audio.m4a`，其次现有 playableArtifact 逻辑），`@timeupdate` → `activeIndex = findActiveSegment(segments, t*1000)`，当前段高亮且滚动到可视区
    - 右侧分段列表：`[formatTimecode(startMs) - formatTimecode(endMs)]` + 说话人标签（`speaker!=null` → `说话人 {speaker+1}`）+ 文本；点击段 → `media.currentTime = clampSeekMs(seg)/1000` 并播放
  - SSE：`onMounted` 建 `new EventSource(api.sseUrl(id))`；`onmessage`（默认消息）→ 解析 JSON：`type=="task"` 终态 → `load()` 后 `es.close()` 并停止；否则防抖 300ms `load()`；`onerror` → `es.close()` 并启用 2s 轮询兜底（任务终态后自动停止轮询）
  - engine_raw 查看器：产物列表中 `engine_raw/` 开头的文件提供"查看"按钮 → dialog 内 `<pre>` 显示 JSON 文本（`artifactUrl` 拉取）

- [ ] **Step 1: client.ts artifactUrl 分段编码**

```ts
  artifactUrl(taskId: string, name: string): string {
    const t = getToken()
    const encoded = name.split('/').map(encodeURIComponent).join('/')
    return `/api/tasks/${taskId}/artifacts/${encoded}${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
```

- [ ] **Step 2: TaskDetailView 增加 SSE（替换轮询主逻辑）**

`<script setup>` 关键改动（保留现有 load/retry/cancel）：

```ts
import type { TaskView, Transcript, TranscriptSegment } from '../api/types'
import { clampSeekMs, findActiveSegment, formatTimecode } from '../utils/timeline'

const transcript = ref<Transcript | null>(null)
const activeIndex = ref(-1)
const rawOpen = ref(false)
const rawName = ref('')
const rawContent = ref('')
const mediaRef = ref<HTMLMediaElement | null>(null)
let es: EventSource | null = null
let pollTimer: number | undefined
let debounceTimer: number | undefined

const transcriptArtifact = computed(() =>
  task.value?.artifacts.find((a) => a.name === 'transcript.json') ?? null)
const mediaArtifact = computed(() => {
  if (!task.value) return null
  return task.value.artifacts.find((a) => a.name === 'audio.m4a')
    ?? task.value.artifacts.find((a) => /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|flac|ogg)$/i.test(a.name))
    ?? null
})
const rawArtifacts = computed(() =>
  task.value?.artifacts.filter((a) => a.name.startsWith('engine_raw/')) ?? [])

async function loadTranscript() {
  if (!transcriptArtifact.value) { transcript.value = null; return }
  try {
    const resp = await fetch(api.artifactUrl(id, 'transcript.json'))
    transcript.value = resp.ok ? await resp.json() : null
  } catch { transcript.value = null }
}

function onTimeUpdate() {
  const media = mediaRef.value
  if (!media || !transcript.value) return
  activeIndex.value = findActiveSegment(transcript.value.segments, media.currentTime * 1000)
}

function seekTo(seg: TranscriptSegment) {
  const media = mediaRef.value
  if (!media) return
  media.currentTime = clampSeekMs(seg) / 1000
  media.play().catch(() => { /* 自动播放被拦截时可忽略 */ })
}

function speakerLabel(s: number | null | undefined): string {
  return s == null ? '' : `说话人 ${s + 1}`
}

async function showRaw(name: string) {
  rawName.value = name
  rawOpen.value = true
  try {
    const resp = await fetch(api.artifactUrl(id, name))
    rawContent.value = resp.ok ? JSON.stringify(await resp.json(), null, 2) : '加载失败'
  } catch (e) {
    rawContent.value = '加载失败: ' + (e as Error).message
  }
}

function startSse() {
  es = new EventSource(api.sseUrl(id))
  es.onmessage = (ev) => {
    try {
      const e = JSON.parse(ev.data)
      if (e.type === 'task') {
        es?.close()
        es = null
        load()
        return
      }
      window.clearTimeout(debounceTimer)
      debounceTimer = window.setTimeout(load, 300)
    } catch { /* 忽略非 JSON 帧 */ }
  }
  es.onerror = () => {
    es?.close()
    es = null
    startPolling() // SSE 断开 → 轮询兜底
  }
}

function startPolling() {
  if (pollTimer !== undefined) return
  pollTimer = window.setInterval(() => {
    load().then(() => {
      if (task.value && task.value.status !== 'RUNNING' && task.value.status !== 'PENDING') {
        stopPolling()
      }
    })
  }, 2000)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

onMounted(() => {
  load().then(loadTranscript)
  startSse()
})

onBeforeUnmount(() => {
  es?.close()
  stopPolling()
  window.clearTimeout(debounceTimer)
})
```

`load()` 改为同时刷新转写：成功路径末尾 `await loadTranscript()`；模板中进度卡沿用；原 `setInterval(load, 2000)` 轮询初始化删除（由 SSE/兜底接管）。

- [ ] **Step 3: 模板增加时间轴对照与 engine_raw 查看器**

产物卡片之前插入：

```html
<el-card v-if="transcript" style="margin-top: 16px">
  <template #header>
    时间轴对照 · 引擎 {{ transcript.engine }} · 语言 {{ transcript.language }}
  </template>
  <div style="display: flex; gap: 16px; align-items: flex-start">
    <div style="flex: 1; min-width: 0">
      <video
        v-if="mediaArtifact && /\.(mp4|mkv|webm|mov|m4v)$/i.test(mediaArtifact.name)"
        ref="mediaRef"
        :src="api.artifactUrl(id, mediaArtifact.name)"
        controls
        @timeupdate="onTimeUpdate"
        style="width: 100%; max-height: 420px"
      />
      <audio
        v-else-if="mediaArtifact"
        ref="mediaRef"
        :src="api.artifactUrl(id, mediaArtifact.name)"
        controls
        @timeupdate="onTimeUpdate"
        style="width: 100%"
      />
    </div>
    <div style="width: 380px; max-height: 420px; overflow-y: auto">
      <div
        v-for="(seg, i) in transcript.segments"
        :key="i"
        @click="seekTo(seg)"
        :style="{
          padding: '6px 10px', cursor: 'pointer', borderRadius: '6px',
          background: i === activeIndex ? 'var(--el-color-primary-light-9)' : 'transparent',
          borderLeft: i === activeIndex ? '3px solid var(--el-color-primary)' : '3px solid transparent',
        }"
      >
        <div style="font-size: 12px; color: #909399">
          [{{ formatTimecode(seg.startMs) }} - {{ formatTimecode(seg.endMs) }}]
          <el-tag v-if="seg.speaker != null" size="small" style="margin-left: 6px">
            {{ speakerLabel(seg.speaker) }}
          </el-tag>
        </div>
        <div>{{ seg.text }}</div>
      </div>
    </div>
  </div>
</el-card>
```

产物表格"操作"列追加（`engine_raw/` 文件显示"查看"）：

```html
<el-button v-if="row.name.startsWith('engine_raw/')" size="small" @click="showRaw(row.name)">查看</el-button>
```

页面底部追加对话框：

```html
<el-dialog v-model="rawOpen" :title="rawName" width="720px">
  <pre style="max-height: 60vh; overflow: auto; white-space: pre-wrap; word-break: break-all">{{ rawContent }}</pre>
</el-dialog>
```

- [ ] **Step 4: 构建验证**

Run: `cd frontend && npm run test && npm run build`
Expected: vitest 全过；`vue-tsc && vite build` 干净

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat: 任务详情时间轴对照、SSE 进度与引擎原始响应查看"
```

---
