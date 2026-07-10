import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.response.use((response) => {
  const payload = response.data
  if (payload && payload.code !== 0) {
    return Promise.reject(new Error(payload.message || '请求失败'))
  }
  return payload.data
})

export const api = {
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
  analyze(videoId, autoVectorize) {
    return http.post('/tasks/analyze', { videoId, autoVectorize })
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
  vectorize(taskId) {
    return http.post(`/knowledge/vectorize/${taskId}`)
  },
  vectorStatus(taskId) {
    return http.get(`/knowledge/status/${taskId}`)
  },
  createSession(videoId) {
    return http.post('/chat/session', { videoId })
  },
  listSessions(videoId) {
    return http.get('/chat/session/list', { params: { videoId } })
  },
  sendMessage(sessionId, videoId, question, answerScope = 'KNOWLEDGE_EXTENDED', applicationMode = 'NORMAL') {
    return http.post('/chat/message', { sessionId, videoId, question, answerScope, applicationMode })
  },
  streamMessage(sessionId, videoId, question, answerScope, applicationMode, onDelta) {
    return streamChatMessage(sessionId, videoId, question, answerScope, applicationMode, onDelta)
  },
  listMessages(sessionId, videoId) {
    return http.get(`/chat/session/${sessionId}/messages`, { params: { videoId } })
  },
  createPresentation(videoId, payload) {
    return http.post(`/videos/${videoId}/presentations`, payload)
  },
  listPresentations(videoId) {
    return http.get(`/videos/${videoId}/presentations`)
  },
  getPresentation(videoId, taskId) {
    return http.get(`/videos/${videoId}/presentations/${taskId}`)
  },
  retryPresentation(videoId, taskId) {
    return http.post(`/videos/${videoId}/presentations/${taskId}/retry`)
  },
  getCapabilities() {
    return http.get('/v1/system/capabilities')
  }
}

function streamChatMessage(sessionId, videoId, question, answerScope, applicationMode, onDelta) {
  const params = new URLSearchParams({
    sessionId: String(sessionId),
    videoId: String(videoId),
    question,
    answerScope,
    applicationMode
  })
  return new Promise((resolve, reject) => {
    const source = new EventSource(`/api/chat/message/stream?${params.toString()}`)
    source.addEventListener('delta', (event) => {
      onDelta(event.data)
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
