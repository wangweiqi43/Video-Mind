import test from 'node:test'
import assert from 'node:assert/strict'
import { selectableKnowledgeBases, selectedDocumentScope } from '../src/knowledgeScope.js'

test('only user knowledge bases are optional and only READY bases are selectable', () => {
  const values = selectableKnowledgeBases([
    { id: 1, type: 'VIDEO', videoId: 7, status: 'READY' },
    { id: 2, type: 'USER', status: 'READY' },
    { id: 3, type: 'USER', status: 'PROCESSING' }
  ], 7)
  assert.deepEqual(values.map((item) => [item.id, item.selectable]), [[2, true], [3, false]])
})

test('restores only document knowledge bases from fixed session scope', () => {
  const scope = selectedDocumentScope({ knowledgeBaseIds: [1, 2, 99] }, [
    { id: 1, type: 'VIDEO', status: 'READY' },
    { id: 2, type: 'USER', status: 'READY' }
  ])
  assert.deepEqual(scope, [2])
})
