<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { api, getToken, setToken } from '../api/client'
import type { EngineInfo, ToolHealth } from '../api/types'

const router = useRouter()
const sourceMode = ref<'url' | 'upload'>('url')
const url = ref('')
const file = ref<File | null>(null)
const stages = ref<string[]>(['DOWNLOAD'])
const submitting = ref(false)
const tools = ref<ToolHealth[]>([])
const tokenInput = ref(getToken())

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

// 切换输入模式时清理不适用的勾选
watch(sourceMode, (mode) => {
  if (mode === 'upload') {
    stages.value = stages.value.filter((s) => s !== 'DOWNLOAD')
  } else if (stages.value.length === 0) {
    stages.value = ['DOWNLOAD']
  }
})

function onFileChange(f: any) {
  file.value = f.raw as File
}

function onFileRemove() {
  file.value = null
}

function validate(): string | null {
  if (sourceMode.value === 'url') {
    if (!url.value.trim()) return '请输入视频链接'
    if (!stages.value.includes('DOWNLOAD')) return '使用链接时必须勾选"下载"'
  } else {
    if (!file.value) return '请选择要上传的文件'
    if (stages.value.includes('DOWNLOAD')) return '上传文件时不能勾选"下载"'
  }
  if (stages.value.length === 0) return '请至少选择一个功能'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const form = new FormData()
  if (sourceMode.value === 'url') {
    form.set('url', url.value.trim())
  } else {
    form.set('file', file.value!)
  }
  form.set('stages', stages.value.join(','))
  if (hasTranscribe.value) {
    form.set('engineId', engineId.value)
    form.set('language', language.value)
    form.set('diarize', String(diarize.value))
  }
  submitting.value = true
  try {
    const t = await api.createTask(form)
    ElMessage.success('任务已创建')
    router.push(`/tasks/${t.id}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

async function saveToken() {
  setToken(tokenInput.value.trim())
  ElMessage.success('令牌已保存')
  await loadTools()
}

async function loadTools() {
  try {
    tools.value = await api.toolsHealth()
  } catch {
    tools.value = []
  }
}

onMounted(loadTools)
onMounted(loadEngines)
</script>

<template>
  <div>
    <el-card>
      <template #header>新建任务</template>
      <el-alert
        v-if="tools.length > 0 && tools.some((t) => !t.ok)"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
        :title="'外部工具不可用：' + tools.filter((t) => !t.ok).map((t) => t.name).join('、') + '，请先安装'"
      />
      <el-radio-group v-model="sourceMode" style="margin-bottom: 16px">
        <el-radio-button value="url">视频链接</el-radio-button>
        <el-radio-button value="upload">上传文件</el-radio-button>
      </el-radio-group>

      <el-input
        v-if="sourceMode === 'url'"
        v-model="url"
        placeholder="粘贴视频页面链接或文件直链 https://..."
        clearable
        style="margin-bottom: 16px"
      />
      <el-upload
        v-else
        :auto-upload="false"
        :limit="1"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
        drag
        style="margin-bottom: 16px"
      >
        <el-icon size="40"><UploadFilled /></el-icon>
        <div>拖拽视频/音频文件到这里，或点击选择</div>
      </el-upload>

      <div style="margin-bottom: 8px; color: #909399; font-size: 13px">选择要执行的功能（可组合）：</div>
      <el-checkbox-group v-model="stages" style="margin-bottom: 16px">
        <el-checkbox value="DOWNLOAD" :disabled="sourceMode === 'upload'">下载视频（输入：链接）</el-checkbox>
        <el-checkbox value="AUDIO_EXTRACT">提取音频（输入：视频）</el-checkbox>
        <el-checkbox value="TRANSCRIBE">转写（输入：音频/视频）</el-checkbox>
      </el-checkbox-group>

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

      <el-button type="primary" :loading="submitting" @click="submit">创建任务</el-button>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>访问令牌（服务端启用时需要）</template>
      <el-space>
        <el-input v-model="tokenInput" placeholder="X-Access-Token（可选）" style="width: 320px" show-password />
        <el-button @click="saveToken">保存</el-button>
      </el-space>
      <div v-if="tools.length" style="margin-top: 12px; color: #909399; font-size: 13px">
        外部工具：
        <el-tag v-for="t in tools" :key="t.name" :type="t.ok ? 'success' : 'danger'" style="margin-right: 8px">
          {{ t.name }} {{ t.ok ? 'OK' : '不可用' }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>
