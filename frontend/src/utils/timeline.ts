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
