import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, setToken } from './client'

const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
})

afterEach(() => {
  store.clear()
})

describe('api.artifactUrl', () => {
  it('keeps slash segments and encodes each segment', () => {
    expect(api.artifactUrl('t1', 'engine_raw/minimax.json')).toBe(
      '/api/tasks/t1/artifacts/engine_raw/minimax.json',
    )
  })
  it('encodes special characters per segment without encoding slashes', () => {
    expect(api.artifactUrl('t1', 'engine_raw/bad name!.json')).toBe(
      '/api/tasks/t1/artifacts/engine_raw/bad%20name!.json',
    )
  })
  it('appends token query when token is set', () => {
    setToken('abc')
    expect(api.artifactUrl('t1', 'engine_raw/x.json')).toBe(
      '/api/tasks/t1/artifacts/engine_raw/x.json?token=abc',
    )
  })
})
