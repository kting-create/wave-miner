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
  engineId: string | null
  language: string | null
  diarize: boolean
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
