import assert from 'node:assert/strict'
import test from 'node:test'
import { createKnowledgeWithDocument } from '../src/knowledgeCreation.js'

test('creates a document knowledge base before uploading its selected file', async () => {
  const calls = []
  const file = { name: 'requirements.pdf', size: 128 }
  const client = {
    async createKnowledgeBase(name) {
      calls.push(['create', name])
      return { id: 17, name }
    },
    async uploadKnowledgeDocument(id, selectedFile, idempotencyKey) {
      calls.push(['upload', id, selectedFile, idempotencyKey])
      return { taskId: 99 }
    }
  }

  const result = await createKnowledgeWithDocument(client, {
    name: '  项目资料  ', file, idempotencyKey: 'request-key'
  })

  assert.deepEqual(result, { created: { id: 17, name: '项目资料' }, upload: { taskId: 99 } })
  assert.deepEqual(calls, [
    ['create', '项目资料'],
    ['upload', 17, file, 'request-key']
  ])
})
