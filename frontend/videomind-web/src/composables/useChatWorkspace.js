import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { failAssistantMessage, normalizeHistoryMessages, normalizeReferences } from '../chatHistory'
import { getVideoSessionId, removeVideoSessionId, saveVideoSessionId } from '../sessionStorage'
import { referenceType } from '../presentation'

export function useChatWorkspace({ client, selectedVideo, knowledge }) {
  const sessions = ref([])
  const messages = ref([])
  const chatView = ref('chat')
  const sessionListLoading = ref(false)
  const sessionListError = ref('')
  const sessionDetailLoading = ref(false)
  const activeSessionId = ref(null)
  const question = ref('')
  const answerScope = ref('KNOWLEDGE_EXTENDED')
  const loadingChat = ref(false)
  const historyScrollTop = ref(0)
  let sessionRequestId = 0
  let detailRequestId = 0
  let activeStream = null

  function resetForVideo() {
    sessionRequestId += 1
    detailRequestId += 1
    activeStream?.cancel?.()
    activeStream = null
    sessions.value = []
    messages.value = []
    activeSessionId.value = null
    chatView.value = 'chat'
    sessionListError.value = ''
    question.value = ''
  }

  async function loadSessions(videoId = selectedVideo.value?.id) {
    if (!videoId) return []
    const requestId = ++sessionRequestId
    sessionListLoading.value = true
    sessionListError.value = ''
    try {
      const result = await client.listSessions(videoId)
      if (requestId === sessionRequestId && selectedVideo.value?.id === videoId) sessions.value = result
      return result
    } catch (error) {
      if (requestId === sessionRequestId && selectedVideo.value?.id === videoId) {
        sessions.value = []
        sessionListError.value = error.message || '历史会话加载失败'
      }
      return []
    } finally {
      if (requestId === sessionRequestId) sessionListLoading.value = false
    }
  }

  async function createSession() {
    const videoId = selectedVideo.value?.id
    if (!videoId || sessionDetailLoading.value) return false
    sessionDetailLoading.value = true
    try {
      const session = await client.createSession(videoId, knowledge.selectedIds.value)
      if (selectedVideo.value?.id !== videoId) return false
      saveVideoSessionId(videoId, session.sessionId)
      await loadSessions(videoId)
      await openSession(session.sessionId, videoId)
      return true
    } catch (error) {
      ElMessage.error(error.message || '新建会话失败')
      return false
    } finally {
      sessionDetailLoading.value = false
    }
  }

  function prepareNewSession() {
    if (loadingChat.value || sessionDetailLoading.value) return
    activeSessionId.value = null
    messages.value = []
    chatView.value = 'chat'
    if (selectedVideo.value?.id) removeVideoSessionId(selectedVideo.value.id)
  }

  async function openSession(sessionId, videoId = selectedVideo.value?.id, fromHistory = false, scrollTop = 0) {
    if (!videoId || (sessionDetailLoading.value && activeSessionId.value === sessionId)) return
    if (fromHistory) historyScrollTop.value = scrollTop
    const requestId = ++detailRequestId
    activeSessionId.value = Number(sessionId)
    chatView.value = 'chat'
    sessionDetailLoading.value = true
    try {
      const result = await client.listMessages(sessionId, videoId)
      if (requestId === detailRequestId && selectedVideo.value?.id === videoId) {
        messages.value = normalizeHistoryMessages(result)
        const session = sessions.value.find((item) => item.id === Number(sessionId))
        if (session) knowledge.restoreSessionScope(session)
        saveVideoSessionId(videoId, sessionId)
      }
    } catch (error) {
      if (requestId === detailRequestId) {
        messages.value = []
        ElMessage.error(error.message || '会话消息加载失败')
      }
    } finally {
      if (requestId === detailRequestId) sessionDetailLoading.value = false
    }
  }

  async function openRestoredSession(videoId) {
    const sessionId = getVideoSessionId(videoId)
    if (!sessionId || !sessions.value.some((session) => session.id === Number(sessionId))) {
      removeVideoSessionId(videoId)
      activeSessionId.value = null
      messages.value = []
      return
    }
    await openSession(Number(sessionId), videoId)
  }

  async function showHistory() {
    if (!selectedVideo.value?.id) return
    chatView.value = 'history'
    await loadSessions(selectedVideo.value.id)
  }

  function returnToChat() {
    chatView.value = 'chat'
  }

  async function backToHistory() {
    chatView.value = 'history'
    await loadSessions()
  }

  async function sendQuestion() {
    const prompt = question.value.trim()
    if (!prompt || loadingChat.value) return
    if (!selectedVideo.value?.id) return ElMessage.warning('请先选择一个视频，再针对该视频内容提问')
    if (!activeSessionId.value && !(await createSession())) return
    loadingChat.value = true
    messages.value.push({ role: 'USER', content: prompt, streaming: false, failed: false })
    question.value = ''
    const assistant = { role: 'ASSISTANT', content: '', references: [], workflowStatus: '', streaming: true, failed: false }
    messages.value.push(assistant)
    try {
      activeStream = client.streamMessage(
        activeSessionId.value,
        selectedVideo.value.id,
        prompt,
        answerScope.value,
        (delta) => { assistant.content += delta },
        false,
        false,
        (workflow) => { assistant.workflowStatus = workflow.message }
      )
      const answer = await activeStream
      if (!assistant.content && answer?.answer) assistant.content = answer.answer
      assistant.referencesJson = answer?.referencesJson || ''
      assistant.references = answer?.references || []
      assistant.workflowStatus = ''
      assistant.streaming = false
      await loadSessions(selectedVideo.value.id)
    } catch (error) {
      failAssistantMessage(assistant)
      if (error.message !== '流式连接已关闭') ElMessage.error(error.message || '流式响应失败')
    } finally {
      activeStream = null
      loadingChat.value = false
    }
  }

  function openReference(reference) {
    if (referenceType(reference) === 'WEB' && reference.url) {
      window.open(reference.url, '_blank', 'noopener,noreferrer')
      return
    }
    const videoId = reference.videoId || selectedVideo.value?.id
    if (!videoId) return
    const timestamp = Number.isFinite(reference.startSeconds) ? `#t=${reference.startSeconds}` : ''
    window.open(`/api/videos/${videoId}/stream${timestamp}`, '_blank', 'noopener')
  }

  onBeforeUnmount(() => activeStream?.cancel?.())

  return {
    sessions, messages, chatView, sessionListLoading, sessionListError, sessionDetailLoading,
    activeSessionId, question, answerScope, loadingChat, historyScrollTop, resetForVideo,
    loadSessions, createSession, prepareNewSession, openSession, openRestoredSession,
    showHistory, returnToChat, backToHistory, sendQuestion, openReference, normalizeReferences
  }
}
