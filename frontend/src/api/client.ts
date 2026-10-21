import type { EngineInfo, SettingView, TaskPage, TaskView, ToolHealth } from './types'

const TOKEN_KEY = 'wave-miner-token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(t: string) {
  localStorage.setItem(TOKEN_KEY, t)
}

function headers(): Record<string, string> {
  const h: Record<string, string> = {}
  const t = getToken()
  if (t) h['X-Access-Token'] = t
  return h
}

async function parse<T>(resp: Response): Promise<T> {
  if (!resp.ok) {
    let message = `HTTP ${resp.status}`
    try {
      const body = await resp.json()
      if (body?.message) message = body.message
    } catch { /* 非 JSON 错误体 */ }
    throw new Error(message)
  }
  return resp.json() as Promise<T>
}

export const api = {
  createTask(form: FormData): Promise<TaskView> {
    return fetch('/api/tasks', { method: 'POST', headers: headers(), body: form }).then((r) => parse<TaskView>(r))
  },
  listTasks(status: string | null, page: number, size: number): Promise<TaskPage> {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
    if (status) q.set('status', status)
    return fetch(`/api/tasks?${q}`, { headers: headers() }).then((r) => parse<TaskPage>(r))
  },
  getTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}`, { headers: headers() }).then((r) => parse<TaskView>(r))
  },
  retryTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}/retry`, { method: 'POST', headers: headers() }).then((r) => parse<TaskView>(r))
  },
  cancelTask(id: string): Promise<TaskView> {
    return fetch(`/api/tasks/${id}/cancel`, { method: 'POST', headers: headers() }).then((r) => parse<TaskView>(r))
  },
  deleteTask(id: string): Promise<void> {
    return fetch(`/api/tasks/${id}`, { method: 'DELETE', headers: headers() }).then((r) => parse<void>(r))
  },
  toolsHealth(): Promise<ToolHealth[]> {
    return fetch('/api/tools/health', { headers: headers() }).then((r) => parse<ToolHealth[]>(r))
  },
  artifactUrl(taskId: string, name: string): string {
    const t = getToken()
    return `/api/tasks/${taskId}/artifacts/${encodeURIComponent(name)}${t ? `?token=${encodeURIComponent(t)}` : ''}`
  },
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
}
