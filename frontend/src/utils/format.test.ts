import { describe, expect, it } from 'vitest'
import { formatBytes, formatTime } from './format'

describe('formatBytes', () => {
  it('formats bytes', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
  it('formats invalid', () => {
    expect(formatBytes(-1)).toBe('-')
    expect(formatBytes(NaN)).toBe('-')
  })
})

describe('formatTime', () => {
  it('returns dash for null', () => {
    expect(formatTime(null)).toBe('-')
  })
  it('parses iso', () => {
    expect(formatTime('2026-10-21T00:00:00Z')).not.toBe('-')
  })
})
