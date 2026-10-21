### Task 10: 前端扩展（一）：类型/客户端/时间轴工具 + 新建任务转写卡

**Files:**
- Modify: `frontend/src/api/types.ts`、`frontend/src/api/client.ts`、`frontend/src/views/NewTaskView.vue`
- Create: `frontend/src/utils/timeline.ts`
- Test: `frontend/src/utils/timeline.test.ts`

**Interfaces:**
- Consumes: Task 2/3/9 的 HTTP 契约（`/api/engines`、`/api/settings`、`/api/tasks` 新参数、`/api/tasks/{id}/events`）
- Produces:
  - `types.ts` 新增：`interface EngineInfo { id; displayName; maxDurationSec; maxSizeBytes; supportsDiarization; languages: string[]; configured: boolean }`、`interface TranscriptSegment { startMs: number; endMs: number; text: string; speaker?: number | null }`、`interface Transcript { segments: TranscriptSegment[]; engine: string; language: string; durationMs: number }`、`interface SettingView { key: string; value: string; configured: boolean }`；`TaskView` 增加 `engineId: string | null; language: string | null; diarize: boolean`
  - `client.ts` 新增：`getEngines(): Promise<EngineInfo[]>`、`getSettings(): Promise<SettingView[]>`、`putSetting(key, value): Promise<SettingView>`（PUT JSON）、`sseUrl(taskId): string`（`/api/tasks/{id}/events` + `?token=`）
  - `utils/timeline.ts`：
    - `formatTimecode(ms: number): string` → `mm:ss`（≥1h `h:mm:ss`，与后端 TranscriptFormat.timecode 一致）
    - `findActiveSegment(segments: TranscriptSegment[], timeMs: number): number` → 命中 `[startMs,endMs)` 的下标，无则 -1
    - `clampSeekMs(seg: TranscriptSegment): number` → `Math.max(0, seg.startMs)`
  - `NewTaskView.vue`：勾选 `TRANSCRIBE` 时显示：引擎下拉（`getEngines()`，label `displayName +（≤X秒/YMB）`，`configured=false` 时警告"未配置 API Key"）、语言下拉（`自动检测` + engine.languages）、"说话人分离" checkbox（仅 `engine.supportsDiarization` 时显示）；提交表单附 `engineId/language/diarize` 字段

- [ ] **Step 1: 写 timeline 测试（先失败）**

`frontend/src/utils/timeline.test.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { clampSeekMs, findActiveSegment, formatTimecode } from './timeline'
import type { TranscriptSegment } from '../api/types'

const segments: TranscriptSegment[] = [
  { startMs: 0, endMs: 1660, text: 'a', speaker: 1 },
  { startMs: 2000, endMs: 6100, text: 'b', speaker: 2 },
  { startMs: 65000, endMs: 70000, text: 'c', speaker: null },
]

describe('formatTimecode', () => {
  it('formats mm:ss', () => {
    expect(formatTimecode(0)).toBe('00:00')
    expect(formatTimecode(65_000)).toBe('01:05')
    expect(formatTimecode(3_723_000)).toBe('1:02:03')
  })
})

describe('findActiveSegment', () => {
  it('finds segment containing time', () => {
    expect(findActiveSegment(segments, 1000)).toBe(0)
    expect(findActiveSegment(segments, 2100)).toBe(1)
    expect(findActiveSegment(segments, 65_500)).toBe(2)
  })
  it('returns -1 in gaps and out of range', () => {
    expect(findActiveSegment(segments, 1800)).toBe(-1)
    expect(findActiveSegment(segments, -1)).toBe(-1)
    expect(findActiveSegment(segments, 999_999)).toBe(-1)
  })
})

describe('clampSeekMs', () => {
  it('clamps to zero', () => {
    expect(clampSeekMs(segments[0])).toBe(0)
    expect(clampSeekMs({ startMs: -5, endMs: 0, text: '' })).toBe(0)
  })
})
```

Run: `cd frontend && npm run test`
Expected: FAIL（模块不存在）

- [ ] **Step 2: 实现 timeline.ts**

`frontend/src/utils/timeline.ts`：

```ts
import type { TranscriptSegment } from '../api/types'

export function formatTimecode(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '00:00'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function findActiveSegment(segments: TranscriptSegment[], timeMs: number): number {
  for (let i = 0; i < segments.length; i++) {
    if (timeMs >= segments[i].startMs && timeMs < segments[i].endMs) return i
  }
  return -1
}

export function clampSeekMs(seg: TranscriptSegment): number {
  return Math.max(0, seg.startMs)
}
```

Run: `cd frontend && npm run test`
Expected: PASS

- [ ] **Step 3: 扩展 types.ts / client.ts**

`types.ts` 追加（并给 `TaskView` 增加 3 个可选字段以匹配后端）：

```ts
export interface EngineInfo {
  id: string
  displayName: string
  maxDurationSec: number
  maxSizeBytes: number
  supportsDiarization: boolean
  languages: string[]
  configured: boolean
}

export interface TranscriptSegment {
  startMs: number
  endMs: number
  text: string
  speaker?: number | null
}

export interface Transcript {
  segments: TranscriptSegment[]
  engine: string
  language: string
  durationMs: number
}

export interface SettingView {
  key: string
  value: string
  configured: boolean
}
```

`TaskView` 中 `errorMessage: string | null` 之后插入：

```ts
  engineId: string | null
  language: string | null
  diarize: boolean
```

`client.ts` 的 `api` 对象追加：

```ts
  getEngines(): Promise<EngineInfo[]> {
    return fetch('/api/engines', { headers: headers() }).then((r) => parse<EngineInfo[]>(r))
  },
  getSettings(): Promise<SettingView[]> {
    return fetch('/api/settings', { headers: headers() }).then((r) => parse<SettingView[]>(r))
  },
  putSetting(key: string, value: string): Promise<SettingView> {
    return fetch('/api/settings', {
      method: 'PUT',
      headers: { ...headers(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ key, value }),
    }).then((r) => parse<SettingView>(r))
  },
  sseUrl(taskId: string): string {
    const t = getToken()
    return `/api/tasks/${taskId}/events${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
```

（import 行加 `EngineInfo, SettingView` 类型。）

- [ ] **Step 4: NewTaskView 加转写卡**

`NewTaskView.vue` 的 `<script setup>` 增加：

```ts
import { api } from '../api/client'
import type { EngineInfo } from '../api/types'

const engines = ref<EngineInfo[]>([])
const engineId = ref('minimax')
const language = ref('auto')
const diarize = ref(false)
const hasTranscribe = computed(() => stages.value.includes('TRANSCRIBE'))
const currentEngine = computed(() => engines.value.find((e) => e.id === engineId.value) ?? null)
const languageOptions = computed(() => ['auto', ...(currentEngine.value?.languages ?? [])])

const LANG_LABELS: Record<string, string> = {
  auto: '自动检测', zh: '中文', yue: '粤语', en: '英语', ja: '日语', ko: '韩语', th: '泰语',
  vi: '越南语', id: '印尼语', ms: '马来语', fil: '菲律宾语', ar: '阿拉伯语', tr: '土耳其语',
  fr: '法语', de: '德语', es: '西班牙语', it: '意大利语', pt: '葡萄牙语', pl: '波兰语',
  ru: '俄语', uk: '乌克兰语',
}

async function loadEngines() {
  try {
    engines.value = await api.getEngines()
    if (!engines.value.some((e) => e.id === engineId.value) && engines.value.length > 0) {
      engineId.value = engines.value[0].id
    }
  } catch { /* 忽略，提交时后端仍会校验 */ }
}

watch(engineId, () => {
  if (!languageOptions.value.includes(language.value)) language.value = 'auto'
})

onMounted(loadEngines)
```

（`ref/computed/watch/onMounted` 若未导入需补齐。）

勾选组追加 TRANSCRIBE：

```html
<el-checkbox value="TRANSCRIBE">转写（输入：音频/视频）</el-checkbox>
```

勾选组下方、提交按钮之前插入转写配置卡片：

```html
<el-card v-if="hasTranscribe" shadow="never" style="margin-bottom: 16px">
  <template #header>转写设置</template>
  <el-form label-width="110px">
    <el-form-item label="转写引擎">
      <el-select v-model="engineId" style="width: 320px">
        <el-option
          v-for="e in engines"
          :key="e.id"
          :value="e.id"
          :label="`${e.displayName}（≤${Math.round(e.maxDurationSec / 60)} 分钟 / ${Math.round(e.maxSizeBytes / 1024 / 1024)}MB）`"
        />
      </el-select>
      <el-alert
        v-if="currentEngine && !currentEngine.configured"
        type="warning"
        :closable="false"
        show-icon
        title="该引擎尚未配置 API Key，请到设置页填写或配置环境变量"
        style="margin-top: 8px"
      />
    </el-form-item>
    <el-form-item label="语言">
      <el-select v-model="language" style="width: 320px">
        <el-option v-for="l in languageOptions" :key="l" :value="l" :label="LANG_LABELS[l] ?? l" />
      </el-select>
    </el-form-item>
    <el-form-item v-if="currentEngine?.supportsDiarization" label="说话人分离">
      <el-switch v-model="diarize" />
    </el-form-item>
  </el-form>
</el-card>
```

`submit()` 的 FormData 组装处追加：

```ts
  if (hasTranscribe.value) {
    form.set('engineId', engineId.value)
    form.set('language', language.value)
    form.set('diarize', String(diarize.value))
  }
```

- [ ] **Step 5: 构建验证**

Run: `cd frontend && npm run test && npm run build`
Expected: vitest 全过；`vue-tsc && vite build` 干净

- [ ] **Step 6: Commit**

```bash
git add frontend && git commit -m "feat: 新建任务转写引擎/语言配置与时间轴工具"
```

---
