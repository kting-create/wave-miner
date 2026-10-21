export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatTime(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  return d.toLocaleString()
}

export const STAGE_LABELS: Record<string, string> = {
  DOWNLOAD: '下载',
  AUDIO_EXTRACT: '提取音频',
  TRANSCRIBE: '转写',
}

export const STATUS_LABELS: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  DONE: '完成',
  SKIPPED: '跳过',
}

export const STATUS_TAG_TYPES: Record<string, string> = {
  PENDING: 'info',
  RUNNING: 'primary',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
  DONE: 'success',
  SKIPPED: 'info',
}
