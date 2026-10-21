export type StageType = 'DOWNLOAD' | 'AUDIO_EXTRACT' | 'TRANSCRIBE'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type StageStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED'

export interface StageView {
  type: StageType
  status: StageStatus
  detail: string | null
  artifactName: string | null
}

export interface ArtifactView {
  name: string
  sizeBytes: number
}

export interface TaskView {
  id: string
  title: string
  sourceType: 'URL' | 'UPLOAD'
  sourceRef: string | null
  status: TaskStatus
  errorMessage: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
  stages: StageView[]
  artifacts: ArtifactView[]
}

export interface TaskPage {
  items: TaskView[]
  total: number
  page: number
  size: number
}

export interface ToolHealth {
  name: string
  ok: boolean
  version: string
}
