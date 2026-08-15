import axios from 'axios'
import { decodeChatDelta, decodeWorkflowEvent } from './chatStream'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000,
  withCredentials: true
})

http.interceptors.response.use((response) => {
  const payload = response.data
  if (payload && payload.code !== 0) {
    return Promise.reject(new Error(payload.message || '请求失败'))
  }
  return payload.data
}, (error) => {
  const message = error.response?.data?.message || error.message || '请求失败'
  return Promise.reject(new Error(message))
})

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
  uploadKnowledgeDocument(knowledgeBaseId, file, onUploadProgress) {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/knowledge-bases/${knowledgeBaseId}/documents`, form, { onUploadProgress })
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
  return new Promise((resolve, reject) => {
    const source = new EventSource(`/api/chat/message/stream?${params.toString()}`)
    source.addEventListener('delta', (event) => {
      onDelta(decodeChatDelta(event.data))
    })
    source.addEventListener('workflow', (event) => {
      const workflow = decodeWorkflowEvent(event.data)
      if (workflow) onWorkflow(workflow)
    })
    source.addEventListener('done', (event) => {
      source.close()
      resolve(JSON.parse(event.data))
    })
    source.addEventListener('error', (event) => {
      source.close()
      reject(new Error(event.data || '流式响应失败'))
    })
    source.onerror = () => {
      source.close()
      reject(new Error('流式连接失败'))
    }
  })
}
