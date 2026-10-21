<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { TaskView } from '../api/types'
import { STAGE_LABELS, STATUS_LABELS, STATUS_TAG_TYPES, formatTime } from '../utils/format'

const router = useRouter()
const items = ref<TaskView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const p = await api.listTasks(null, page.value - 1, size)
    items.value = p.items
    total.value = p.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

async function retry(t: TaskView) {
  try {
    await api.retryTask(t.id)
    ElMessage.success('已重新排队')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function cancel(t: TaskView) {
  try {
    await api.cancelTask(t.id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(t: TaskView) {
  await ElMessageBox.confirm('删除任务及其所有产物文件？', '确认', { type: 'warning' })
  try {
    await api.deleteTask(t.id)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div>
    <el-table
      :data="items"
      v-loading="loading"
      @row-click="(r: TaskView) => router.push(`/tasks/${r.id}`)"
      style="cursor: pointer"
    >
      <el-table-column prop="title" label="任务" min-width="220" show-overflow-tooltip />
      <el-table-column label="功能" width="200">
        <template #default="{ row }">
          <el-tag v-for="s in row.stages" :key="s.type" size="small" style="margin-right: 4px">
            {{ STAGE_LABELS[s.type] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(STATUS_TAG_TYPES[row.status] as any) || 'info'">{{ STATUS_LABELS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click.stop="retry(row)" :disabled="row.status !== 'FAILED' && row.status !== 'CANCELLED'">重试</el-button>
          <el-button size="small" @click.stop="cancel(row)" :disabled="row.status !== 'RUNNING' && row.status !== 'PENDING'">取消</el-button>
          <el-button size="small" type="danger" @click.stop="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="size"
      style="margin-top: 16px; justify-content: flex-end"
      @current-change="load"
    />
  </div>
</template>
