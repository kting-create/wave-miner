import { describe, expect, it } from 'vitest'
import { clampSeekMs, findActiveSegment, formatTimecode } from './timeline'
import type { TranscriptSegment } from '../api/types'

const segments: TranscriptSegment[] = [
  { startMs: 0, endMs: 1660, text: 'a', speaker: 1 },
  { startMs: 2000, endMs: 6100, text: 'b', speaker: 2 },
  { startMs: 65000, endMs: 70000, text: 'c', speaker: null },
]

describe('formatTimecode', () => {
  it('formats mm:ss', () => {
    expect(formatTimecode(0)).toBe('00:00')
    expect(formatTimecode(65_000)).toBe('01:05')
    expect(formatTimecode(3_723_000)).toBe('1:02:03')
  })
})

describe('findActiveSegment', () => {
  it('finds segment containing time', () => {
    expect(findActiveSegment(segments, 1000)).toBe(0)
    expect(findActiveSegment(segments, 2100)).toBe(1)
    expect(findActiveSegment(segments, 65_500)).toBe(2)
  })
  it('returns -1 in gaps and out of range', () => {
    expect(findActiveSegment(segments, 1800)).toBe(-1)
    expect(findActiveSegment(segments, -1)).toBe(-1)
    expect(findActiveSegment(segments, 999_999)).toBe(-1)
  })
})

describe('clampSeekMs', () => {
  it('clamps to zero', () => {
    expect(clampSeekMs(segments[0])).toBe(0)
    expect(clampSeekMs({ startMs: -5, endMs: 0, text: '' })).toBe(0)
  })
})
