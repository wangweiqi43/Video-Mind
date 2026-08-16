import assert from 'node:assert/strict'
import test from 'node:test'
import { decodeChatDelta, decodeWorkflowEvent } from '../src/chatStream.js'
import { failAssistantMessage, normalizeHistoryMessages, sessionPreview, sessionTitle } from '../src/chatHistory.js'
import { normalizeMarkdownForRendering, renderSafeMarkdown } from '../src/safeMarkdown.js'

test('decodes JSON-wrapped whitespace deltas and keeps legacy text events', () => {
  assert.equal(decodeChatDelta('{"delta":" "}'), ' ')
  assert.equal(decodeChatDelta('{"delta":"\\n"}'), '\n')
  assert.equal(decodeChatDelta('{"delta":"  leading"}'), '  leading')
  assert.equal(decodeChatDelta('legacy delta'), 'legacy delta')
})

test('accepts only the public four-field workflow event contract', () => {
  assert.deepEqual(decodeWorkflowEvent(JSON.stringify({
    phase: 'TOOL',
    stepId: 's1',
    status: 'COMPLETED',
    message: '工具调用完成',
    tool: 'VIDEO_TIMELINE_RETRIEVAL',
    evidenceIds: ['ev-1']
  })), {
    phase: 'TOOL',
    stepId: 's1',
    status: 'COMPLETED',
    message: '工具调用完成'
  })
  assert.equal(decodeWorkflowEvent('{"phase":"TOOL"}'), null)
  assert.equal(decodeWorkflowEvent('not-json'), null)
})

test('renders a completed Markdown answer without collapsing its text', () => {
  const html = renderSafeMarkdown([
    'Intercultural Communication',
    '',
    '### 一、核心定义',
    '',
    '- **跨文化交流的定义**'
  ].join('\n'))

  assert.match(html, /Intercultural Communication/)
  assert.match(html, /<h3>一、核心定义<\/h3>/)
  assert.match(html, /<li><strong>跨文化交流的定义<\/strong><\/li>/)
})

test('repairs common model Markdown spacing outside fenced code blocks', () => {
  const source = [
    '###核心复习要求',
    '',
    '-**黑色小标题**：必须掌握。',
    '',
    '```markdown',
    '###代码示例不能改写',
    '-**代码内容**',
    '```'
  ].join('\n')
  const normalized = normalizeMarkdownForRendering(source)
  const html = renderSafeMarkdown(source)

  assert.match(normalized, /^### 核心复习要求/m)
  assert.match(normalized, /^- \*\*黑色小标题\*\*/m)
  assert.match(normalized, /^###代码示例不能改写/m)
  assert.match(normalized, /^-\*\*代码内容\*\*/m)
  assert.match(html, /<h3>核心复习要求<\/h3>/)
  assert.match(html, /<li><strong>黑色小标题<\/strong>：必须掌握。<\/li>/)
})

test('disables raw HTML and secures rendered links', () => {
  const html = renderSafeMarkdown('<script>alert(1)</script>\n\n[资料](https://example.com)')

  assert.doesNotMatch(html, /<script>/)
  assert.match(html, /&lt;script&gt;/)
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="noopener noreferrer"/)
})

test('restores local knowledge history with evidence as completed content', () => {
  const messages = normalizeHistoryMessages([{
    role: 'assistant',
    content: '### 核心结论',
    referencesJson: JSON.stringify([
      { sourceType: 'DOCUMENT', title: '用户文档' },
      { sourceType: 'VIDEO_TIMELINE', title: '视频时间轴' },
      { sourceType: 'TRANSCRIPT_SOURCE', title: '视频转录原文' }
    ])
  }])

  assert.equal(messages[0].role, 'ASSISTANT')
  assert.equal(messages[0].streaming, false)
  assert.equal(messages[0].failed, false)
  assert.equal(messages[0].references.length, 3)
  assert.match(renderSafeMarkdown(messages[0].content), /<h3>核心结论<\/h3>/)
})

test('restores the persisted PEC generation and answer feedback', () => {
  const messages = normalizeHistoryMessages([{
    id: 31,
    generationId: 61,
    role: 'ASSISTANT',
    content: '已验证回答',
    feedback: {
      messageId: 31,
      rating: 'DOWN',
      reasonCodes: ['IRRELEVANT_REFERENCE'],
      detail: '引用与问题无关'
    }
  }])

  assert.equal(messages[0].id, 31)
  assert.equal(messages[0].generationId, 61)
  assert.equal(messages[0].feedback.rating, 'DOWN')
  assert.deepEqual(messages[0].feedback.reasonCodes, ['IRRELEVANT_REFERENCE'])
  assert.equal(messages[0].feedbackSubmitting, false)
})

test('uses safe fallbacks for incomplete local session metadata', () => {
  assert.equal(sessionTitle({ title: ' 旧会话 ' }), '旧会话')
  assert.equal(sessionTitle({}), '新会话')
  assert.equal(sessionPreview({ lastMessagePreview: ' 最新回答 ' }), '最新回答')
  assert.equal(sessionPreview({}), '点击查看并继续对话')
})

test('recovers a streaming answer into an explicit retryable failure state', () => {
  const message = { content: '', streaming: true, failed: false, workflowStatus: '正在检索' }
  failAssistantMessage(message)
  assert.deepEqual(message, {
    content: '回答未完成，请稍后重试。',
    streaming: false,
    failed: true,
    workflowStatus: ''
  })
})
