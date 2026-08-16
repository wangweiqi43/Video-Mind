import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatDuration,
  formatFileSize,
  parseSummary,
  referenceTypeLabel,
  taskStatusMeta
} from '../src/presentation.js'

test('formats only metadata returned by the video API', () => {
  assert.equal(formatFileSize(1536), '1.5 KB')
  assert.equal(formatFileSize(5 * 1024 * 1024), '5.0 MB')
  assert.equal(formatDuration(65), '01:05')
  assert.equal(formatDuration(3661), '01:01:01')
})

test('maps analysis task states to stable interface tones', () => {
  assert.deepEqual(taskStatusMeta({ taskStatus: 'SUCCESS' }), { label: '解析完成', tone: 'success' })
  assert.deepEqual(
    taskStatusMeta({ taskStatus: 'SUCCESS' }, { transcriptionText: '已有转录', summaryText: null }),
    { label: '摘要未生成', tone: 'warning' }
  )
  assert.deepEqual(
    taskStatusMeta({ taskStatus: 'SUCCESS' }, { summaryText: '摘要内容' }),
    { label: '解析完成', tone: 'success' }
  )
  assert.deepEqual(taskStatusMeta({ status: 'PROCESSING' }), { label: '正在解析', tone: 'running' })
  assert.deepEqual(taskStatusMeta({ status: 'unknown' }), { label: 'UNKNOWN', tone: 'muted' })
})

test('keeps reference kinds and structured summaries explicit', () => {
  assert.equal(referenceTypeLabel({ sourceType: 'DOCUMENT' }), '文档知识库')
  assert.equal(referenceTypeLabel({ url: 'https://example.com' }), '网页来源')
  const sections = parseSummary('### 简洁摘要\n这是摘要。\n### 要点 1：关键结论\n可执行内容。')
  assert.equal(sections[0].type, 'intro')
  assert.equal(sections[1].items[0].title, '关键结论')
})
