<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { normalizeReferences, sessionPreview, sessionTitle } from '../chatHistory'
import { formatSessionTime } from '../sessionTime'
import { formatDuration, referenceTitle, referenceTypeLabel } from '../presentation'
import MarkdownContent from './MarkdownContent.vue'

const props = defineProps({
  selectedVideo: { type: Object, default: null }, sessions: { type: Array, default: () => [] }, messages: { type: Array, default: () => [] },
  chatView: { type: String, default: 'chat' }, sessionListLoading: { type: Boolean, default: false }, sessionListError: { type: String, default: '' },
  sessionDetailLoading: { type: Boolean, default: false }, activeSessionId: { type: Number, default: null }, question: { type: String, default: '' },
  answerScope: { type: String, default: 'KNOWLEDGE_EXTENDED' }, loadingChat: { type: Boolean, default: false },
  knowledgeBases: { type: Array, default: () => [] }, selectedKnowledgeBaseIds: { type: Array, default: () => [] },
  knowledgeLoading: { type: Boolean, default: false }, historyScrollTop: { type: Number, default: 0 }
})
const emit = defineEmits([
  'show-history', 'new-session', 'return-chat', 'back-history', 'open-session', 'reload-sessions', 'history-scroll', 'update:question',
  'update:answer-scope', 'update:selected-knowledge-base-ids', 'refresh-knowledge', 'send', 'open-reference',
  'submit-feedback', 'delete-feedback'
])

const historyList = ref(null)
const scopeOpen = ref(false)
const feedbackOpen = ref(false)
const feedbackTarget = ref(null)
const feedbackReasons = ref([])
const feedbackDetail = ref('')
const feedbackOptions = [
  ['SEMANTIC_DRIFT', '偏离查询语义'],
  ['KNOWLEDGE_GROUNDING_ERROR', '未精确依据知识库内容'],
  ['MISSING_KEY_POINTS', '遗漏关键信息'],
  ['IRRELEVANT_REFERENCE', '参考来源不相关'],
  ['ANSWER_INCOMPLETE', '回答不完整'],
  ['OTHER', '其他']
]
const questionModel = computed({ get: () => props.question, set: (value) => emit('update:question', value) })
const answerScopeModel = computed({ get: () => props.answerScope, set: (value) => emit('update:answer-scope', value) })
const knowledgeModel = computed({ get: () => props.selectedKnowledgeBaseIds, set: (value) => emit('update:selected-knowledge-base-ids', value) })
const canSend = computed(() => Boolean(props.selectedVideo?.id) && Boolean(props.question.trim()) && !props.loadingChat && !props.sessionDetailLoading)
const scopeLabel = computed(() => {
  if (props.activeSessionId) return `范围已锁定 · ${props.selectedKnowledgeBaseIds.length + 1} 个知识库`
  return props.selectedKnowledgeBaseIds.length ? `当前视频 + ${props.selectedKnowledgeBaseIds.length} 个文档库` : '仅当前视频'
})

watch(() => props.chatView, async (view) => {
  if (view !== 'history') return
  await nextTick()
  if (historyList.value) historyList.value.scrollTop = props.historyScrollTop
})

watch(() => props.activeSessionId, (sessionId) => {
  if (sessionId) scopeOpen.value = false
})

function openSession(session) {
  emit('open-session', { id: session.id, scrollTop: historyList.value?.scrollTop || 0 })
}

function onComposerKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (canSend.value) emit('send')
}

function canFeedback(message) {
  return message?.role === 'ASSISTANT' && Boolean(message.id) && Boolean(message.generationId)
    && !message.streaming && !message.failed
}

function toggleUp(message) {
  if (message.feedback?.rating === 'UP') return emit('delete-feedback', message)
  emit('submit-feedback', { message, rating: 'UP', reasonCodes: [], detail: '' })
}

function openDown(message) {
  feedbackTarget.value = message
  feedbackReasons.value = message.feedback?.rating === 'DOWN' ? [...(message.feedback.reasonCodes || [])] : []
  feedbackDetail.value = message.feedback?.rating === 'DOWN' ? (message.feedback.detail || '') : ''
  feedbackOpen.value = true
}

function submitDown() {
  if (!feedbackReasons.value.length || !feedbackTarget.value) return
  emit('submit-feedback', {
    message: feedbackTarget.value,
    rating: 'DOWN',
    reasonCodes: feedbackReasons.value,
    detail: feedbackDetail.value.trim()
  })
  feedbackOpen.value = false
}

function clearDown() {
  if (feedbackTarget.value?.feedback?.rating === 'DOWN') emit('delete-feedback', feedbackTarget.value)
  feedbackOpen.value = false
}
</script>

<template>
  <aside class="assistant-workspace" aria-label="智能助手">
    <header class="assistant-heading">
      <h2>{{ chatView === 'history' ? '历史会话' : '智能助手' }}</h2>
      <div>
        <button v-if="chatView !== 'history'" class="text-action" type="button" :disabled="!selectedVideo?.id" @click="$emit('show-history')">
          <svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 1 0 2.3-5.7L4 8.6M4 4v4.6h4.6M12 8v4l3 2"/></svg>历史会话
        </button>
        <button class="primary-small" type="button" :disabled="!selectedVideo?.id || sessionDetailLoading" @click="$emit('new-session')">
          <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>新建对话
        </button>
      </div>
    </header>

    <section v-if="chatView === 'history'" class="history-panel">
      <button class="back-action" type="button" @click="$emit('return-chat')"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>返回智能助手</button>
      <div class="history-context"><strong>{{ selectedVideo?.originalFilename || '尚未选择视频' }}</strong><span>选择会话后可继续之前的问答。</span></div>
      <div v-if="sessionListLoading" class="assistant-state">正在加载历史会话…</div>
      <div v-else-if="sessionListError" class="assistant-state"><p>{{ sessionListError }}</p><button type="button" @click="$emit('reload-sessions')">重新加载</button></div>
      <div v-else-if="!sessions.length" class="assistant-state"><strong>暂无历史会话</strong><p>开始一次新对话后，会话会显示在这里。</p></div>
      <div v-else ref="historyList" class="history-list" @scroll="$emit('history-scroll', $event.target.scrollTop)">
        <button v-for="session in sessions" :key="session.id" class="history-item" :class="{ active: activeSessionId === session.id }" type="button" @click="openSession(session)">
          <span><strong>{{ sessionTitle(session) }}</strong><small>{{ sessionPreview(session) }}</small></span><time>{{ formatSessionTime(session.updatedTime) }}</time>
        </button>
      </div>
    </section>

    <template v-else>
      <section class="conversation-panel">
        <button v-if="activeSessionId" class="back-action conversation-back" type="button" @click="$emit('back-history')"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg>返回历史会话</button>
        <div v-if="sessionDetailLoading" class="assistant-state">正在加载会话…</div>
        <div v-else-if="!messages.length" class="assistant-empty">
          <span><svg viewBox="0 0 24 24"><path d="M5 5h14v11H9l-4 3zM8 9h8m-8 3h5"/></svg></span>
          <strong>针对当前视频开始提问</strong>
          <p>回答会结合已选择的知识库，并保留可追溯的参考来源。</p>
        </div>
        <div v-else class="messages" aria-live="polite">
          <article v-for="(message, index) in messages" :key="index" class="message" :class="message.role?.toLowerCase()">
            <div class="message-meta"><span>{{ message.role === 'USER' ? '你' : 'VM' }}</span><strong>{{ message.role === 'USER' ? '你' : 'VideoMind' }}</strong></div>
            <p v-if="message.workflowStatus && !message.content" class="workflow-status"><i></i>{{ message.workflowStatus }}</p>
            <p v-if="message.role === 'USER'" class="user-message-content">{{ message.content }}</p>
            <MarkdownContent v-else :content="message.content" />
            <p v-if="message.failed" class="message-error">本次回答未完整结束，可重新发送问题。</p>
            <div v-if="normalizeReferences(message).length" class="references">
              <strong class="reference-heading">参考来源</strong>
              <button v-for="(reference, referenceIndex) in normalizeReferences(message)" :key="`${reference.id || reference.title}-${referenceIndex}`" type="button" @click="$emit('open-reference', reference)">
                <span class="reference-time">{{ Number.isFinite(reference.startSeconds) ? formatDuration(reference.startSeconds) : referenceTypeLabel(reference) }}</span>
                <span><strong>{{ referenceTitle(reference) }}</strong><small>{{ reference.chunkText || reference.domain || referenceTypeLabel(reference) }}</small></span>
                <svg viewBox="0 0 24 24"><path d="M14 5h5v5m0-5-8 8M19 13v6H5V5h6"/></svg>
              </button>
            </div>
            <div v-if="canFeedback(message)" class="message-feedback" aria-label="回答反馈">
              <button type="button" :class="{ active: message.feedback?.rating === 'UP' }"
                :disabled="message.feedbackSubmitting" aria-label="赞" title="赞" @click="toggleUp(message)">
                <svg viewBox="0 0 24 24"><path d="M7 10v10H4V10h3Zm3 10h7.2a2 2 0 0 0 1.9-1.4l1.6-5A2 2 0 0 0 18.8 11H15l.6-3.1A2.4 2.4 0 0 0 13.2 5L10 10v10Z"/></svg>
              </button>
              <button type="button" :class="{ active: message.feedback?.rating === 'DOWN' }"
                :disabled="message.feedbackSubmitting" aria-label="踩" title="踩" @click="openDown(message)">
                <svg viewBox="0 0 24 24"><path d="M7 14V4H4v10h3Zm3-10h7.2a2 2 0 0 1 1.9 1.4l1.6 5a2 2 0 0 1-1.9 2.6H15l.6 3.1a2.4 2.4 0 0 1-2.4 2.9L10 14V4Z"/></svg>
              </button>
              <span v-if="message.feedback">已记录反馈</span>
            </div>
          </article>
        </div>
      </section>

      <footer class="assistant-composer">
        <div class="composer-settings">
          <div class="scope-select">
            <button
              type="button"
              :class="{ active: scopeOpen, locked: activeSessionId }"
              :disabled="Boolean(activeSessionId) || !selectedVideo?.id"
              @click="scopeOpen = !scopeOpen"
            >
              <span>会话知识库范围</span><strong>{{ scopeLabel }}</strong>
              <svg v-if="activeSessionId" viewBox="0 0 24 24"><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
              <svg v-else viewBox="0 0 24 24"><path d="m7 9 5 5 5-5"/></svg>
            </button>
            <div v-if="scopeOpen && !activeSessionId" class="scope-menu">
              <div class="scope-menu-heading"><div><strong>新对话的知识库范围</strong><small>当前视频始终包含在内</small></div><div><button type="button" @click="$emit('refresh-knowledge')">刷新</button></div></div>
              <div class="scope-fixed"><span class="scope-check">✓</span><span><strong>当前视频</strong><small>{{ selectedVideo?.originalFilename }}</small></span></div>
              <el-checkbox-group v-model="knowledgeModel">
                <div v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" class="knowledge-option">
                  <el-checkbox :value="Number(knowledgeBase.id)" :disabled="!knowledgeBase.selectable">
                    {{ knowledgeBase.name }} · {{ knowledgeBase.status }}
                  </el-checkbox>
                </div>
              </el-checkbox-group>
              <p v-if="!knowledgeBases.length">尚无文档知识库。</p>
              <p class="scope-help">选择完成后，发送第一条问题时会创建并锁定会话。</p>
            </div>
          </div>
          <div class="answer-scope-select"><span>回答范围</span><el-radio-group v-model="answerScopeModel" size="small"><el-radio-button value="KNOWLEDGE_ONLY">仅知识库</el-radio-button><el-radio-button value="KNOWLEDGE_EXTENDED">知识库扩展</el-radio-button></el-radio-group></div>
        </div>
        <div class="composer-box" :class="{ focused: question }">
          <el-input v-model="questionModel" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" resize="none" :placeholder="selectedVideo ? `针对《${selectedVideo.originalFilename}》提问…` : '请先选择视频'" :disabled="!selectedVideo?.id || sessionDetailLoading" @keydown="onComposerKeydown" />
          <div class="composer-footer"><small>Enter 发送 · Shift + Enter 换行</small><button class="send-button" type="button" :disabled="!canSend" @click="$emit('send')"><svg viewBox="0 0 24 24"><path d="m21 3-7.5 18-3-7.5L3 10.5 21 3Zm-10.5 10.5L15 9"/></svg>{{ loadingChat ? '生成中' : '发送' }}</button></div>
        </div>
      </footer>
    </template>
    <el-dialog v-model="feedbackOpen" title="这次回答哪里需要改进？" width="420px" append-to-body>
      <el-checkbox-group v-model="feedbackReasons" class="feedback-reason-list">
        <el-checkbox v-for="option in feedbackOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</el-checkbox>
      </el-checkbox-group>
      <el-input v-model="feedbackDetail" type="textarea" :rows="3" maxlength="500" show-word-limit
        placeholder="可选：补充具体原因" />
      <template #footer>
        <button v-if="feedbackTarget?.feedback?.rating === 'DOWN'" class="feedback-clear" type="button" @click="clearDown">取消反馈</button>
        <el-button @click="feedbackOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!feedbackReasons.length" @click="submitDown">提交反馈</el-button>
      </template>
    </el-dialog>
  </aside>
</template>
