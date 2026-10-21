<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { TaskView } from '../api/types'
import { STAGE_LABELS, STATUS_LABELS, STATUS_TAG_TYPES, formatBytes, formatTime } from '../utils/format'

const route = useRoute()
const id = route.params.id as string
const task = ref<TaskView | null>(null)
let timer: number | undefined

const isRunning = computed(() =>
  task.value?.status === 'RUNNING' || task.value?.status === 'PENDING')

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
  } catch (e) {
    ElMessage.error((e as Error).message)
    clearInterval(timer)
  }
}

async function retry() {
  try {
    await api.retryTask(id)
    await load()
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

onMounted(() => {
  load()
  timer = window.setInterval(load, 2000)
})
onBeforeUnmount(() => clearInterval(timer))
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

    <el-card style="margin-top: 16px">
      <template #header>产物下载</template>
      <el-empty v-if="task.artifacts.length === 0" description="暂无产物" />
      <el-table v-else :data="task.artifacts">
        <el-table-column prop="name" label="文件" min-width="240" />
        <el-table-column label="大小" width="120">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" type="primary">
              <a :href="api.artifactUrl(id, row.name)" :download="row.name" style="color: inherit; text-decoration: none">下载</a>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
