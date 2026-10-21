<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import type { SettingView } from '../api/types'

const settings = ref<SettingView[]>([])
const inputs = ref<Record<string, string>>({})
const saving = ref<Record<string, boolean>>({})

const KEY_LABELS: Record<string, string> = {
  'minimax.apiKey': 'MiniMax 转写 API Key',
  'aliyun.apiKey': '阿里云百炼 API Key',
}

function label(key: string): string {
  return KEY_LABELS[key] ?? key
}

async function load() {
  try {
    settings.value = await api.getSettings()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function save(s: SettingView) {
  const value = (inputs.value[s.key] ?? '').trim()
  if (!value) {
    ElMessage.warning('请输入新的 Key 值')
    return
  }
  saving.value[s.key] = true
  try {
    await api.putSetting(s.key, value)
    ElMessage.success('已保存')
    inputs.value[s.key] = ''
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value[s.key] = false
  }
}

async function clear(s: SettingView) {
  saving.value[s.key] = true
  try {
    await api.putSetting(s.key, '')
    ElMessage.success('已清除')
    inputs.value[s.key] = ''
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value[s.key] = false
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>设置</template>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="API Key 存储在本机 SQLite 数据库，界面仅显示掩码；也可通过环境变量 WAVEMINER_MINIMAX_API_KEY / WAVEMINER_ALIYUN_API_KEY（兼容 DASHSCOPE_API_KEY）配置。"
      style="margin-bottom: 16px"
    />
    <div v-for="s in settings" :key="s.key" style="margin-bottom: 20px">
      <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px">
        <span style="font-weight: 600">{{ label(s.key) }}</span>
        <span style="color: #909399; font-size: 13px">{{ s.key }}</span>
        <el-tag :type="s.configured ? 'success' : 'info'" size="small">
          {{ s.configured ? '已配置' : '未配置' }}
        </el-tag>
      </div>
      <div style="display: flex; align-items: center; gap: 8px">
        <el-input
          v-model="inputs[s.key]"
          show-password
          clearable
          :placeholder="s.configured ? `已配置（${s.value}），留空表示不修改` : '请输入 API Key'"
          style="width: 420px"
        />
        <el-button type="primary" :loading="!!saving[s.key]" @click="save(s)">保存</el-button>
        <el-button :disabled="!s.configured" :loading="!!saving[s.key]" @click="clear(s)">清除</el-button>
      </div>
    </div>
    <el-empty v-if="settings.length === 0" description="暂无可配置项" />
  </el-card>
</template>
