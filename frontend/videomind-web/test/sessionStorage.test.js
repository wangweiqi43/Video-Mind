import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getVideoSessionId,
  readVideoSessionMap,
  removeVideoSessionId,
  saveVideoSessionId
} from '../src/sessionStorage.js'

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value)
  }
}

test('restores and removes the last session independently for each video', () => {
  const storage = memoryStorage()
  saveVideoSessionId(7, 41, storage)
  saveVideoSessionId(8, 52, storage)
  assert.equal(getVideoSessionId(7, storage), 41)
  removeVideoSessionId(7, storage)
  assert.equal(getVideoSessionId(7, storage), undefined)
  assert.equal(getVideoSessionId(8, storage), 52)
})

test('recovers from malformed local session data', () => {
  const storage = memoryStorage({ 'videomind:video-chat-sessions': '{broken' })
  assert.deepEqual(readVideoSessionMap(storage), {})
})
