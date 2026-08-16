import axios from 'axios'
import { decodeChatDelta, decodeWorkflowEvent } from './chatStream.js'

const AUTH_ROUTES = /\/auth\/(?:login|register|refresh|logout)(?:\?|$)/

function requestError(error) {
  const message = error.response?.data?.message || error.message || '请求失败'
  return new Error(message)
}

export function createHttpClient(options = {}) {
  const client = axios.create({
    baseURL: '/api',
    timeout: 120000,
    withCredentials: true,
    ...options
  })
  let refreshRequest = null

  client.interceptors.response.use((response) => {
    const payload = response.data
    if (payload && payload.code !== 0) {
      return Promise.reject(new Error(payload.message || '请求失败'))
    }
    return payload.data
  }, async (error) => {
    const status = error.response?.status
    const config = error.config
    const canRefresh = (status === 401 || status === 403)
      && config
      && !config.__vmSessionRetry
      && !config.__vmSkipSessionRecovery
      && !AUTH_ROUTES.test(config.url || '')

    if (!canRefresh) return Promise.reject(requestError(error))

    config.__vmSessionRetry = true
    try {
      if (!refreshRequest) {
        refreshRequest = client.post('/auth/refresh', null, { __vmSkipSessionRecovery: true })
          .finally(() => { refreshRequest = null })
      }
      await refreshRequest
      return client.request(config)
    } catch {
      return Promise.reject(new Error('登录状态已过期，请重新登录'))
    }
  })

  return client
}

const http = createHttpClient()

export const api = {
  register(username, password) {
    return http.post('/auth/register', { username, password })
  },
  login(username, password) {
    return http.post('/auth/login', { username, password })
  },
  me() {
    return http.get('/auth/me')
  },
  logout() {
    return http.post('/auth/logout')
  },
  uploadVideo(file, onUploadProgress) {
    const form = new FormData()
    form.append('file', file)
    return http.post('/videos/upload', form, { onUploadProgress })
  },
  checkVideoMd5(fileMd5) {
    return http.get('/videos/check-md5', { params: { fileMd5 } })
  },
  listVideos() {
    return http.get('/videos/list')
  },
  getVideoTranscription(videoId) {
    return http.get(`/videos/${videoId}/transcription`)
  },
  deleteVideo(videoId) {
    return http.delete(`/videos/${videoId}`)
  },
  initMultipartUpload(payload) {
    return http.post('/videos/multipart/init', payload)
  },
  uploadChunk(uploadId, partNumber, chunk, chunkMd5) {
    const form = new FormData()
    form.append('file', chunk)
    return http.post(`/videos/multipart/${uploadId}/chunk`, form, {
      params: { partNumber, chunkMd5 }
    })
  },
  multipartStatus(uploadId) {
    return http.get(`/videos/multipart/${uploadId}/status`)
  },
  completeMultipartUpload(uploadId) {
    return http.post(`/videos/multipart/${uploadId}/complete`)
  },
  analyze(videoId) {
    return http.post('/tasks/analyze', { videoId })
  },
  getTask(taskId) {
    return http.get(`/tasks/${taskId}`)
  },
  getLatestSuccessfulTask(videoId) {
    return http.get(`/tasks/video/${videoId}/latest-success`)
  },
  getTaskResult(taskId) {
    return http.get(`/tasks/${taskId}/result`)
  },
  createSession(videoId, knowledgeBaseIds = []) {
    return http.post('/chat/session', { videoId, knowledgeBaseIds })
  },
  listSessions(videoId) {
    return http.get('/chat/session/list', { params: { videoId } })
  },
  sendMessage(sessionId, videoId, question, answerScope = 'KNOWLEDGE_EXTENDED', webSearchEnabled = false, deepThinkingEnabled = false) {
    return http.post('/chat/message', { sessionId, videoId, question, answerScope, webSearchEnabled, deepThinkingEnabled })
  },
  streamMessage(sessionId, videoId, question, answerScope, onDelta, webSearchEnabled = false,
    deepThinkingEnabled = false, onWorkflow = () => {}) {
    return streamChatMessage(sessionId, videoId, question, answerScope, onDelta, webSearchEnabled,
      deepThinkingEnabled, onWorkflow)
  },
  listMessages(sessionId, videoId) {
    return http.get(`/chat/session/${sessionId}/messages`, { params: { videoId } })
  },
  listKnowledgeBases() {
    return http.get('/knowledge-bases')
  },
  createKnowledgeBase(name) {
    return http.post('/knowledge-bases', { name })
  },
  uploadKnowledgeDocument(knowledgeBaseId, file, idempotencyKey, onUploadProgress) {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/knowledge-bases/${knowledgeBaseId}/documents`, form, {
      headers: { 'Idempotency-Key': idempotencyKey },
      onUploadProgress
    })
  },
  getProcessingTask(taskId) {
    return http.get(`/processing-tasks/${taskId}`)
  },
  deleteKnowledgeBase(knowledgeBaseId) {
    return http.delete(`/knowledge-bases/${knowledgeBaseId}`)
  }
}

function streamChatMessage(sessionId, videoId, question, answerScope, onDelta, webSearchEnabled = false,
  deepThinkingEnabled = false, onWorkflow = () => {}) {
  const params = new URLSearchParams({
    sessionId: String(sessionId),
    videoId: String(videoId),
    question,
    answerScope,
    webSearchEnabled: String(Boolean(webSearchEnabled)),
    deepThinkingEnabled: String(Boolean(deepThinkingEnabled))
  })
  let source
  let rejectStream
  let settled = false
  const stream = new Promise((resolve, reject) => {
    rejectStream = reject
    source = new EventSource(`/api/chat/message/stream?${params.toString()}`)
    const finish = (callback, value) => {
      if (settled) return
      settled = true
      source.close()
      callback(value)
    }
    source.addEventListener('delta', (event) => {
      if (settled) return
      onDelta(decodeChatDelta(event.data))
    })
    source.addEventListener('workflow', (event) => {
      if (settled) return
      const workflow = decodeWorkflowEvent(event.data)
      if (workflow) onWorkflow(workflow)
    })
    source.addEventListener('done', (event) => {
      try {
        finish(resolve, JSON.parse(event.data))
      } catch {
        finish(reject, new Error('流式响应格式错误'))
      }
    })
    source.addEventListener('error', (event) => {
      if (event.data) finish(reject, new Error(event.data))
    })
    source.onerror = () => {
      finish(reject, new Error('流式连接失败'))
    }
  })
  stream.cancel = () => {
    if (settled) return
    settled = true
    source?.close()
    rejectStream?.(new Error('流式连接已关闭'))
  }
  return stream
}
