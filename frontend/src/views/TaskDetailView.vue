<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { TaskView, Transcript, TranscriptSegment } from '../api/types'
import { clampSeekMs, findActiveSegment, formatTimecode } from '../utils/timeline'
import { STAGE_LABELS, STATUS_LABELS, STATUS_TAG_TYPES, formatBytes, formatTime } from '../utils/format'

const route = useRoute()
const id = route.params.id as string
const task = ref<TaskView | null>(null)
const transcript = ref<Transcript | null>(null)
const activeIndex = ref(-1)
const rawOpen = ref(false)
const rawName = ref('')
const rawContent = ref('')
const mediaRef = ref<HTMLMediaElement | null>(null)
const segmentListRef = ref<HTMLElement | null>(null)
let es: EventSource | null = null
let pollTimer: number | undefined
let debounceTimer: number | undefined

const isRunning = computed(() =>
  task.value?.status === 'RUNNING' || task.value?.status === 'PENDING')

const transcriptArtifact = computed(() =>
  task.value?.artifacts.find((a) => a.name === 'transcript.json') ?? null)

const mediaArtifact = computed(() => {
  if (!task.value) return null
  return task.value.artifacts.find((a) => a.name === 'audio.m4a')
    ?? task.value.artifacts.find((a) => /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|flac|ogg)$/i.test(a.name))
    ?? null
})

const playableArtifact = computed(() => {
  if (!task.value) return null
  return task.value.artifacts.find((a) => /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|flac|ogg)$/i.test(a.name)) ?? null
})

const playableUrl = computed(() => {
  if (!playableArtifact.value) return ''
  return api.artifactUrl(id, playableArtifact.value.name)
})

async function load() {
  try {
    task.value = await api.getTask(id)
    await loadTranscript()
  } catch (e) {
    ElMessage.error((e as Error).message)
    stopPolling()
  }
}

async function retry() {
  try {
    await api.retryTask(id)
    await load()
    // 终态时后端已关闭 SSE，任务复活后需重建订阅，否则进度卡静止
    if (!es) startSse()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancel() {
  try {
    await api.cancelTask(id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

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

function handleSseData(data: string) {
  try {
    const e = JSON.parse(data)
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

function startSse() {
  es = new EventSource(api.sseUrl(id))
  es.addEventListener('progress', (ev) => handleSseData((ev as MessageEvent).data))
  es.onmessage = (ev) => handleSseData(ev.data)
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

watch(activeIndex, (idx) => {
  const list = segmentListRef.value
  if (idx < 0 || !list) return
  const el = list.children[idx] as HTMLElement | undefined
  el?.scrollIntoView({ block: 'nearest' })
})

onMounted(() => {
  load().then(loadTranscript)
  startSse()
})

onBeforeUnmount(() => {
  es?.close()
  stopPolling()
  window.clearTimeout(debounceTimer)
})
</script>

<template>
  <div v-if="task">
    <el-page-header :content="task.title" @back="$router.push('/tasks')" style="margin-bottom: 16px" />

    <el-descriptions :column="3" border>
      <el-descriptions-item label="状态">
        <el-tag :type="(STATUS_TAG_TYPES[task.status] as any) || 'info'">{{ STATUS_LABELS[task.status] }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="来源">{{ task.sourceType === 'URL' ? '链接' : '上传' }}</el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ formatTime(task.createdAt) }}</el-descriptions-item>
      <el-descriptions-item label="完成时间">{{ formatTime(task.finishedAt) }}</el-descriptions-item>
      <el-descriptions-item label="来源地址" :span="2">
        <span style="word-break: break-all">{{ task.sourceRef }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-if="task.errorMessage"
      type="error"
      :title="task.errorMessage"
      show-icon
      :closable="false"
      style="margin-top: 12px"
    />

    <el-card style="margin-top: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>执行进度</span>
          <el-space>
            <el-button size="small" :disabled="task.status !== 'FAILED' && task.status !== 'CANCELLED'" @click="retry">重试</el-button>
            <el-button size="small" :disabled="!isRunning" @click="cancel">取消</el-button>
          </el-space>
        </div>
      </template>
      <el-steps direction="vertical">
        <el-step
          v-for="s in task.stages"
          :key="s.type"
          :title="STAGE_LABELS[s.type]"
          :status="s.status === 'DONE' ? 'success' : s.status === 'FAILED' ? 'error' : s.status === 'RUNNING' ? 'process' : s.status === 'SKIPPED' ? 'wait' : 'wait'"
          :description="(s.detail ?? '') + (s.artifactName ? `（产物: ${s.artifactName}）` : '')"
        />
      </el-steps>
    </el-card>

    <el-card v-if="playableArtifact" style="margin-top: 16px">
      <template #header>在线预览 · {{ playableArtifact.name }}（{{ formatBytes(playableArtifact.sizeBytes) }}）</template>
      <video
        v-if="/\.(mp4|mkv|webm|mov|m4v)$/i.test(playableArtifact.name)"
        :src="playableUrl"
        controls
        style="width: 100%; max-height: 480px"
      />
      <audio v-else :src="playableUrl" controls style="width: 100%" />
    </el-card>

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
        <div ref="segmentListRef" style="width: 380px; max-height: 420px; overflow-y: auto">
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

    <el-card style="margin-top: 16px">
      <template #header>产物下载</template>
      <el-empty v-if="task.artifacts.length === 0" description="暂无产物" />
      <el-table v-else :data="task.artifacts">
        <el-table-column prop="name" label="文件" min-width="240" />
        <el-table-column label="大小" width="120">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" type="primary">
              <a :href="api.artifactUrl(id, row.name)" :download="row.name" style="color: inherit; text-decoration: none">下载</a>
            </el-button>
            <el-button v-if="row.name.startsWith('engine_raw/')" size="small" @click="showRaw(row.name)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="rawOpen" :title="rawName" width="720px">
      <pre style="max-height: 60vh; overflow: auto; white-space: pre-wrap; word-break: break-all">{{ rawContent }}</pre>
    </el-dialog>
  </div>
</template>
