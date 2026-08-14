<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SparkMD5 from 'spark-md5'
import { api } from './api'
import VideoControlPanel from './components/VideoControlPanel.vue'
import { selectableKnowledgeBases, selectedDocumentScope } from './knowledgeScope'

const state = reactive({
  videos: [],
  selectedVideo: null,
  videoListRefreshing: false,
  uploadProgress: 0,
  uploading: false,
  taskLoading: false,
  resultLoading: false,
  resultRefreshing: false,
  resultView: 'summary',
  task: null,
  taskResult: null,
  sessions: [],
  chatView: 'chat',
  sessionListLoading: false,
  sessionListError: '',
  sessionDetailLoading: false,
  activeSessionId: null,
  messages: [],
  question: '',
  answerScope: 'KNOWLEDGE_EXTENDED',
  loadingChat: false,
  knowledgeBases: [],
  selectedKnowledgeBaseIds: [],
  knowledgeLoading: false,
  knowledgeUploadingId: null
})

const transcriptDialog = reactive({
  visible: false,
  loading: false,
  data: null,
  error: ''
})

const activePollTaskId = ref(null)
const historyListRef = ref(null)
const sessionRequestId = ref(0)
const detailRequestId = ref(0)
const historyScrollTop = ref(0)
const TASK_POLL_INTERVAL_MS = 2000
const TASK_POLL_TIMEOUT_MS = 15 * 60 * 1000
const UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024
const VIDEO_CHAT_SESSION_KEY = 'videomind:video-chat-sessions'

const canAnalyze = computed(() => Boolean(state.selectedVideo?.id) && !state.taskLoading)
const canSendQuestion = computed(() => Boolean(state.selectedVideo?.id) && !state.loadingChat && !state.sessionDetailLoading)
const documentKnowledgeBases = computed(() => selectableKnowledgeBases(state.knowledgeBases, state.selectedVideo?.id))
const chatPlaceholder = computed(() => {
  if (!state.selectedVideo?.id) return '请先选择一个视频，再针对该视频内容提问...'
  return `正在针对《${state.selectedVideo.originalFilename}》提问...`
})
const answerScopeHint = computed(() => state.answerScope === 'KNOWLEDGE_ONLY'
  ? '严格依据当前视频知识库，不使用模型外部知识，也不访问互联网。'
  : '扩展相关片段、章节与上下文来增强回答，仍然不访问互联网。')
const transcriptPreview = computed(() => {
  if (state.taskResult?.transcriptionText) return state.taskResult.transcriptionText
  return state.resultLoading ? '正在读取历史转录文本...' : '解析成功后，这里会出现语音转文字结果。'
})
const summaryPreview = computed(() => {
  if (state.taskResult?.summaryText) return state.taskResult.summaryText
  return state.resultLoading ? '正在读取历史摘要...' : '解析成功后，这里会出现 AI 视频摘要。'
})
const structuredSummary = computed(() => parseSummary(state.taskResult?.summaryText))
const transcriptDialogStatus = computed(() => {
  if (transcriptDialog.loading) return '正在读取最新转录文本...'
  if (transcriptDialog.error) return transcriptDialog.error
  if (transcriptDialog.data?.status === 'READY' && transcriptDialog.data?.transcriptionText) return ''
  const taskStatus = String(state.task?.taskStatus || '').toUpperCase()
  if (['PENDING', 'PROCESSING', 'RETRYING', 'CANCEL_REQUESTED'].includes(taskStatus)) {
    return '视频转录处理中，完成后重新打开即可查看。'
  }
  return '尚未生成转录文本，请先启动本地视频解析。'
})

onMounted(async () => {
  await Promise.all([loadVideos(), loadKnowledgeBases()])
})

async function loadVideos() {
  state.videoListRefreshing = true
  try {
    state.videos = await api.listVideos()
    if (!state.selectedVideo && state.videos.length > 0) {
      await selectVideo(state.videos[0])
    }
  } finally {
    state.videoListRefreshing = false
  }
}

async function refreshVideoList() {
  try {
    await loadVideos()
  } catch (error) {
    ElMessage.error(error.message || '视频库刷新失败')
  }
}

async function selectVideo(video) {
  transcriptDialog.visible = false
  activePollTaskId.value = null
  sessionRequestId.value += 1
  detailRequestId.value += 1
  state.selectedVideo = video
  state.sessions = []
  state.activeSessionId = null
  state.messages = []
  state.chatView = 'chat'
  state.sessionListError = ''
  state.resultLoading = true
  const tasks = [loadSessions(video.id), loadLatestTaskForVideo(video.id), loadKnowledgeBases()]
  await Promise.all(tasks)
  if (state.selectedVideo?.id === video.id) {
    await openVideoSession(video.id)
  }
  state.resultLoading = false
}

async function loadLatestTaskForVideo(videoId) {
  try {
    const task = await api.getLatestSuccessfulTask(videoId)
    if (!task) {
      state.task = null
      state.taskResult = null
      return
    }
    state.task = task
    await loadTaskResult(task.id)
  } catch (error) {
    state.task = null
    state.taskResult = null
    ElMessage.warning(error.message || '暂未找到历史解析结果')
  }
}

async function handleUpload(options) {
  state.uploading = true
  state.uploadProgress = 0
  try {
    ElMessage.info('正在计算文件指纹，准备分片上传...')
    const md5Start = performance.now()
    const fileMd5 = await calculateFileMd5(options.file)
    const clientMd5CostMs = Math.round(performance.now() - md5Start)
    console.info('[VideoMind Perf] frontendMd5CostMs=%d fileSize=%d fileMd5=%s',
      clientMd5CostMs,
      options.file.size,
      fileMd5
    )
    const uploaded = await uploadVideoByChunks(options.file, fileMd5)
    ElMessage.success(uploaded.duplicate ? '视频已存在，秒传成功' : '视频上传成功')
    await loadVideos()
    const video = state.videos.find((item) => item.id === uploaded.videoId)
    if (video) selectVideo(video)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    state.uploading = false
  }
}

function calculateFileMd5(file) {
  return new Promise((resolve, reject) => {
    const chunkSize = UPLOAD_CHUNK_SIZE
    const chunks = Math.ceil(file.size / chunkSize)
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    let currentChunk = 0

    reader.onload = (event) => {
      spark.append(event.target.result)
      currentChunk += 1
      state.uploadProgress = Math.min(10, Math.round((currentChunk / chunks) * 10))
      if (currentChunk < chunks) {
        loadNext()
        return
      }
      resolve(spark.end())
    }

    reader.onerror = () => reject(new Error('计算文件 MD5 失败，请重新选择文件'))

    function loadNext() {
      const start = currentChunk * chunkSize
      const end = Math.min(start + chunkSize, file.size)
      reader.readAsArrayBuffer(file.slice(start, end))
    }

    loadNext()
  })
}

function calculateChunkMd5(chunk) {
  return new Promise((resolve, reject) => {
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    reader.onload = (event) => {
      spark.append(event.target.result)
      resolve(spark.end())
    }
    reader.onerror = () => reject(new Error('计算分片 MD5 失败，请重新上传该分片'))
    reader.readAsArrayBuffer(chunk)
  })
}

async function uploadVideoByChunks(file, fileMd5) {
  const totalParts = Math.max(1, Math.ceil(file.size / UPLOAD_CHUNK_SIZE))
  const init = await api.initMultipartUpload({
    filename: file.name,
    fileMd5,
    fileSize: file.size,
    contentType: file.type || 'application/octet-stream',
    totalParts,
    chunkSize: UPLOAD_CHUNK_SIZE
  })
  if (init.video) {
    state.uploadProgress = 100
    return init.video
  }

  const uploadId = init.uploadId
  let uploadedSet = new Set(init.uploadedParts || [])
  if (!uploadId) {
    throw new Error('后端未返回 uploadId，无法继续分片上传')
  }

  if (uploadedSet.size === 0) {
    const status = await api.multipartStatus(uploadId)
    uploadedSet = new Set(status.uploadedParts || [])
  }

  updateChunkUploadProgress(uploadedSet.size, totalParts)
  for (let partNumber = 1; partNumber <= totalParts; partNumber++) {
    if (uploadedSet.has(partNumber)) {
      continue
    }
    const start = (partNumber - 1) * UPLOAD_CHUNK_SIZE
    const end = Math.min(start + UPLOAD_CHUNK_SIZE, file.size)
    const chunk = file.slice(start, end)
    const chunkMd5 = await calculateChunkMd5(chunk)
    await api.uploadChunk(uploadId, partNumber, chunk, chunkMd5)
    uploadedSet.add(partNumber)
    updateChunkUploadProgress(uploadedSet.size, totalParts)
  }

  state.uploadProgress = 98
  const uploaded = await api.completeMultipartUpload(uploadId)
  state.uploadProgress = 100
  return uploaded
}

function updateChunkUploadProgress(uploadedPartsCount, totalParts) {
  const chunkRatio = totalParts <= 0 ? 1 : uploadedPartsCount / totalParts
  state.uploadProgress = Math.min(95, 10 + Math.round(chunkRatio * 85))
}

async function createAnalyzeTask() {
  if (!state.selectedVideo) return
  state.taskLoading = true
  state.resultLoading = true
  try {
    const task = await api.analyze(state.selectedVideo.id)
    state.task = { id: task.taskId, taskStatus: task.status, reused: task.reused }
    if (task.status === 'SUCCESS') {
      await refreshTaskOutcome(task.taskId)
      return
    }
    state.taskResult = null
    if (!task.reused) {
      ElMessage.success({
        message: '解析任务已提交，完成后会自动刷新结果',
        customClass: 'videomind-toast'
      })
    }
    await pollTask(task.taskId)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    state.taskLoading = false
    state.resultLoading = false
  }
}

function playCurrentVideo() {
  if (!state.selectedVideo?.id) {
    ElMessage.warning('请先选择一个视频')
    return
  }
  window.open(`/api/videos/${state.selectedVideo.id}/stream`, '_blank', 'noopener')
}

async function pollTask(taskId) {
  activePollTaskId.value = taskId
  const startTime = Date.now()
  while (activePollTaskId.value === taskId) {
    const task = await api.getTask(taskId)
    state.task = task
    if (task.taskStatus === 'SUCCESS') {
      await refreshTaskOutcome(taskId)
      activePollTaskId.value = null
      return
    }
    if (task.taskStatus === 'FAILED') {
      activePollTaskId.value = null
      ElMessage.error({
        message: task.errorMessage || '解析失败',
        customClass: 'videomind-toast'
      })
      return
    }
    if (task.taskStatus === 'CANCELLED') {
      activePollTaskId.value = null
      ElMessage.info('视频解析任务已取消')
      return
    }
    if (Date.now() - startTime > TASK_POLL_TIMEOUT_MS) {
      activePollTaskId.value = null
      ElMessage.warning('解析耗时较长，请稍后回到该视频查看结果')
      return
    }
    await wait(TASK_POLL_INTERVAL_MS)
  }
}

async function refreshTaskOutcome(taskId) {
  await Promise.all([loadTaskResult(taskId), refreshSelectedVideoMetadata(), loadKnowledgeBases()])
}

async function loadKnowledgeBases() {
  state.knowledgeLoading = true
  try {
    state.knowledgeBases = await api.listKnowledgeBases()
    const readyIds = new Set(documentKnowledgeBases.value
      .filter((item) => item.selectable)
      .map((item) => Number(item.id)))
    state.selectedKnowledgeBaseIds = state.selectedKnowledgeBaseIds
      .map(Number)
      .filter((id) => readyIds.has(id))
  } catch (error) {
    ElMessage.warning(error.message || '知识库列表加载失败')
  } finally {
    state.knowledgeLoading = false
  }
}

async function createKnowledgeBase() {
  try {
    const { value } = await ElMessageBox.prompt('请输入知识库名称', '新建文档知识库', {
      confirmButtonText: '创建', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '名称不能为空'
    })
    const created = await api.createKnowledgeBase(value.trim())
    await loadKnowledgeBases()
    ElMessage.success(`知识库“${created.name}”已创建，请上传附件`)
  } catch (error) {
    if (error === 'cancel' || error?.message === 'cancel') return
    ElMessage.error(error.message || '知识库创建失败')
  }
}

async function uploadKnowledgeDocument(options, knowledgeBase) {
  state.knowledgeUploadingId = knowledgeBase.id
  try {
    await api.uploadKnowledgeDocument(knowledgeBase.id, options.file)
    ElMessage.success('附件已提交，本机 MinerU 正在解析')
    await loadKnowledgeBases()
  } catch (error) {
    ElMessage.error(error.message || '附件上传失败')
  } finally {
    state.knowledgeUploadingId = null
  }
}

async function openTranscriptDialog() {
  const videoId = state.selectedVideo?.id
  if (!videoId) return
  transcriptDialog.visible = true
  transcriptDialog.loading = true
  transcriptDialog.data = null
  transcriptDialog.error = ''
  try {
    const data = await api.getVideoTranscription(videoId)
    if (transcriptDialog.visible && state.selectedVideo?.id === videoId) {
      transcriptDialog.data = data
    }
  } catch (error) {
    if (transcriptDialog.visible && state.selectedVideo?.id === videoId) {
      transcriptDialog.error = error.message || '转录文本读取失败'
    }
  } finally {
    if (state.selectedVideo?.id === videoId) transcriptDialog.loading = false
  }
}

async function refreshSelectedVideoMetadata() {
  const selectedId = state.selectedVideo?.id
  if (!selectedId) return
  state.videos = await api.listVideos()
  const refreshed = state.videos.find((video) => video.id === selectedId)
  if (refreshed) state.selectedVideo = refreshed
}

async function loadTaskResult(taskId = state.task?.id) {
  if (!taskId) return
  state.resultRefreshing = true
  try {
    state.taskResult = await api.getTaskResult(taskId)
  } finally {
    state.resultRefreshing = false
  }
}

async function refreshCurrentTaskResult() {
  try {
    await loadTaskResult()
  } catch (error) {
    ElMessage.error(error.message || '解析结果刷新失败')
  }
}

async function deleteVideo(video) {
  try {
    await ElMessageBox.confirm(
      `确认删除视频「${video.originalFilename}」吗？相关解析任务、转录、摘要和知识库向量都会一起删除。`,
      '删除视频',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        customClass: 'videomind-dialog'
      }
    )
    const deletion = await api.deleteVideo(video.id)
    ElMessage.success(`删除任务已提交（任务 ${deletion.taskId}）`)
    const wasSelected = state.selectedVideo?.id === video.id
    state.videos = state.videos.filter((item) => item.id !== video.id)
    removeVideoSessionId(video.id)
    if (wasSelected) {
      state.selectedVideo = null
      state.task = null
      state.taskResult = null
      state.activeSessionId = null
      state.messages = []
    }
  } catch (error) {
    if (error === 'cancel' || error?.message === 'cancel') return
    ElMessage.error(error.message || '删除失败')
  }
}

async function loadSessions(videoId = state.selectedVideo?.id) {
  if (!videoId) return
  const requestId = ++sessionRequestId.value
  state.sessionListLoading = true
  state.sessionListError = ''
  try {
    const sessions = await api.listSessions(videoId)
    if (requestId === sessionRequestId.value && state.selectedVideo?.id === videoId) {
      state.sessions = sessions
    }
  } catch (error) {
    if (requestId === sessionRequestId.value && state.selectedVideo?.id === videoId) {
      state.sessions = []
      state.sessionListError = error.message || '历史会话加载失败'
    }
  } finally {
    if (requestId === sessionRequestId.value) {
      state.sessionListLoading = false
    }
  }
}

async function createSession() {
  const videoId = state.selectedVideo?.id
  if (!videoId || state.sessionDetailLoading) return
  state.sessionDetailLoading = true
  try {
    const session = await api.createSession(videoId, state.selectedKnowledgeBaseIds)
    if (state.selectedVideo?.id !== videoId) return
    saveVideoSessionId(videoId, session.sessionId)
    await loadSessions(videoId)
    await openSession(session.sessionId, videoId)
  } catch (error) {
    ElMessage.error(error.message || '新建会话失败')
  } finally {
    state.sessionDetailLoading = false
  }
}

function prepareNewSession() {
  if (state.loadingChat || state.sessionDetailLoading) return
  state.activeSessionId = null
  state.messages = []
  state.chatView = 'chat'
  if (state.selectedVideo?.id) removeVideoSessionId(state.selectedVideo.id)
}

async function openSession(sessionId, videoId = state.selectedVideo?.id, fromHistory = false) {
  if (!videoId || (state.sessionDetailLoading && state.activeSessionId === sessionId)) return
  if (fromHistory && historyListRef.value) {
    historyScrollTop.value = historyListRef.value.scrollTop
  }
  const requestId = ++detailRequestId.value
  state.activeSessionId = sessionId
  state.chatView = 'chat'
  state.sessionDetailLoading = true
  try {
    const messages = await api.listMessages(sessionId, videoId)
    if (requestId === detailRequestId.value && state.selectedVideo?.id === videoId) {
      state.messages = messages
      const session = state.sessions.find((item) => item.id === Number(sessionId))
      if (session) state.selectedKnowledgeBaseIds = selectedDocumentScope(session, state.knowledgeBases)
      saveVideoSessionId(videoId, sessionId)
    }
  } catch (error) {
    if (requestId === detailRequestId.value) {
      state.messages = []
      ElMessage.error(error.message || '会话消息加载失败')
    }
  } finally {
    if (requestId === detailRequestId.value) {
      state.sessionDetailLoading = false
    }
  }
}

async function openVideoSession(videoId) {
  const sessionId = getVideoSessionId(videoId)
  if (!sessionId || !state.sessions.some((session) => session.id === Number(sessionId))) {
    removeVideoSessionId(videoId)
    state.activeSessionId = null
    state.messages = []
    return
  }
  await openSession(Number(sessionId), videoId)
}

async function showHistory() {
  if (!state.selectedVideo?.id) return
  state.chatView = 'history'
  await loadSessions(state.selectedVideo.id)
  await restoreHistoryScroll()
}

async function backToHistory() {
  state.chatView = 'history'
  await loadSessions(state.selectedVideo?.id)
  await restoreHistoryScroll()
}

async function restoreHistoryScroll() {
  await nextTick()
  if (historyListRef.value) {
    historyListRef.value.scrollTop = historyScrollTop.value
  }
}

function rememberHistoryScroll(event) {
  historyScrollTop.value = event.target.scrollTop
}

async function sendQuestion() {
  const question = state.question.trim()
  if (!question) return
  if (!state.selectedVideo?.id) {
    ElMessage.warning('请先选择一个视频，再针对该视频内容提问')
    return
  }
  if (!state.activeSessionId) {
    await createSession()
  }
  state.loadingChat = true
  state.messages.push({ role: 'USER', content: question })
  state.question = ''
  try {
    const assistantIndex = state.messages.push({
      role: 'ASSISTANT',
      content: '',
      referencesJson: '',
      references: []
    }) - 1
    const assistantMessage = state.messages[assistantIndex]
    const typewriter = createTypewriter(assistantMessage)
    const answer = await api.streamMessage(
      state.activeSessionId,
      state.selectedVideo.id,
      question,
      state.answerScope,
      (delta) => {
      typewriter.push(delta)
      }
    )
    if (!assistantMessage.content && answer?.answer) {
      typewriter.push(answer.answer)
    }
    await typewriter.done()
    assistantMessage.referencesJson = answer?.referencesJson
    assistantMessage.references = answer?.references || []
    await loadSessions(state.selectedVideo.id)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    state.loadingChat = false
  }
}

function createTypewriter(message) {
  const queue = []
  let timer = null
  let finished = false
  let resolveDone
  const donePromise = new Promise((resolve) => {
    resolveDone = resolve
  })

  function tick() {
    if (queue.length > 0) {
      message.content += queue.shift()
      return
    }
    if (finished) {
      clearInterval(timer)
      timer = null
      resolveDone()
    }
  }

  function ensureTimer() {
    if (!timer) {
      timer = setInterval(tick, 18)
    }
  }

  return {
    push(content) {
      queue.push(...String(content || ''))
      ensureTimer()
    },
    done() {
      finished = true
      ensureTimer()
      return donePromise
    }
  }
}

function parseRefs(message) {
  if (Array.isArray(message.references)) return message.references
  if (!message.referencesJson) return []
  try {
    return JSON.parse(message.referencesJson)
  } catch {
    return []
  }
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function readVideoSessionMap() {
  try {
    return JSON.parse(localStorage.getItem(VIDEO_CHAT_SESSION_KEY) || '{}')
  } catch {
    return {}
  }
}

function getVideoSessionId(videoId) {
  return readVideoSessionMap()[String(videoId)]
}

function saveVideoSessionId(videoId, sessionId) {
  const sessionMap = readVideoSessionMap()
  sessionMap[String(videoId)] = sessionId
  localStorage.setItem(VIDEO_CHAT_SESSION_KEY, JSON.stringify(sessionMap))
}

function removeVideoSessionId(videoId) {
  const sessionMap = readVideoSessionMap()
  delete sessionMap[String(videoId)]
  localStorage.setItem(VIDEO_CHAT_SESSION_KEY, JSON.stringify(sessionMap))
}

function referenceType(ref) {
  return String(ref.sourceType || (ref.url ? 'WEB' : 'VIDEO')).toUpperCase()
}

function referenceTitle(ref) {
  if (referenceType(ref) === 'WEB') return ref.title || ref.domain || '网页来源'
  const timestamp = Number.isFinite(ref.startSeconds) ? ` · ${formatDuration(ref.startSeconds)}` : ''
  return `${ref.title || '视频内容'}${timestamp}`
}

function openReference(ref) {
  if (referenceType(ref) === 'WEB' && ref.url) {
    window.open(ref.url, '_blank', 'noopener,noreferrer')
    return
  }
  const videoId = ref.videoId || state.selectedVideo?.id
  if (!videoId) return
  const timestamp = Number.isFinite(ref.startSeconds) ? `#t=${ref.startSeconds}` : ''
  window.open(`/api/videos/${videoId}/stream${timestamp}`, '_blank', 'noopener')
}

function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds) || 0)
  const minutes = Math.floor(value / 60)
  return `${minutes}:${String(Math.floor(value % 60)).padStart(2, '0')}`
}

function sessionTitle(session) {
  return session.title?.trim() || session.lastMessagePreview?.trim()?.slice(0, 40) || '新会话'
}

function sessionPreview(session) {
  return session.lastMessagePreview?.trim() || '开始一次新对话'
}

function formatSessionTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  }
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function parseSummary(text) {
  if (!text) return []
  const sections = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let current = null

  for (const line of lines) {
    const heading = line.match(/^###\s+(.+)$/)
    if (heading) {
      current = { title: heading[1].trim(), lines: [] }
      sections.push(current)
      continue
    }
    if (!current) {
      current = { title: '简洁摘要', lines: [] }
      sections.push(current)
    }
    current.lines.push(line)
  }

  return sections.map((section, index) => {
    const title = normalizeSummaryTitle(section.title)
    if (index === 0 || title.includes('摘要')) {
      return {
        type: 'intro',
        title,
        paragraphs: compactParagraphs(section.lines)
      }
    }
    return {
      type: 'points',
      title: '要点',
      items: parsePointItems(title, section.lines)
    }
  })
}

function parsePointItems(sectionTitle, lines) {
  const headingPoint = sectionTitle.match(/^要点\s*\d+\s*[：:]\s*(.+)$/)
  if (headingPoint) {
    return [{
      title: cleanPointTitle(headingPoint[1]),
      paragraphs: compactParagraphs(lines)
    }]
  }

  if (!sectionTitle.includes('摘要') && !['要点', '可行动结论'].includes(sectionTitle)) {
    return [{
      title: cleanPointTitle(sectionTitle),
      paragraphs: compactParagraphs(lines)
    }]
  }

  const items = []
  let current = null

  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) continue

    const titledBullet = line.match(/^-\s+\*\*(.+?)\*\*[：:]\s*(.*)$/)
    if (titledBullet) {
      current = {
        title: cleanPointTitle(titledBullet[1]),
        paragraphs: titledBullet[2] ? [titledBullet[2].trim()] : []
      }
      items.push(current)
      continue
    }

    const plainBullet = line.match(/^-\s+(.+)$/)
    if (plainBullet && current) {
      current.paragraphs.push(plainBullet[1].trim())
      continue
    }

    if (!current) {
      current = { title: '要点', paragraphs: [] }
      items.push(current)
    }
    current.paragraphs.push(line.replace(/\*\*/g, ''))
  }

  return items
}

function normalizeSummaryTitle(title) {
  return title.replace(/\*\*/g, '').trim()
}

function cleanPointTitle(title) {
  return normalizeSummaryTitle(title).replace(/^要点\s*\d*\s*[：:]\s*/, '').trim()
}

function compactParagraphs(lines) {
  return lines
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.replace(/\*\*/g, ''))
}
</script>

<template>
  <main class="shell">
    <header class="brand">
      <h1>VideoMind</h1>
      <p>太长不看？🤔 不看我看！😊</p>
    </header>

    <section class="app-layout" data-testid="local-layout">
      <aside class="panel library-card">
        <div class="section-head">
          <div>
            <p class="eyebrow">Library</p>
            <div class="title-with-refresh">
              <h2>视频库</h2>
              <button
                class="refresh-icon"
                :class="{ spinning: state.videoListRefreshing }"
                type="button"
                title="刷新视频库"
                :disabled="state.videoListRefreshing"
                @click="refreshVideoList"
              >
                ↻
              </button>
            </div>
          </div>
          <span>{{ state.videos.length }} 个文件</span>
        </div>

        <div class="video-list">
          <div
            v-for="video in state.videos"
            :key="video.id"
            class="video-row"
            :class="{ active: state.selectedVideo?.id === video.id }"
          >
            <button class="video-pick" @click="selectVideo(video)">
              <span>{{ video.originalFilename }}</span>
              <small>{{ (video.fileSize / 1024).toFixed(1) }} KB</small>
            </button>
            <button class="video-delete" title="删除视频" @click.stop="deleteVideo(video)">删除</button>
          </div>
        </div>
      </aside>

      <section class="workbench">
        <VideoControlPanel
          :selected-video="state.selectedVideo"
          :uploading="state.uploading"
          :upload-progress="state.uploadProgress"
          :can-analyze="canAnalyze"
          :task-loading="state.taskLoading"
          :task="state.task"
          @upload="handleUpload"
          @analyze="createAnalyzeTask"
          @play="playCurrentVideo"
          @show-transcript="openTranscriptDialog"
        />

        <section class="workspace">
          <article class="panel result-card">
            <div class="section-head">
              <div>
                <p class="eyebrow">Insight</p>
                <div class="title-with-refresh">
                  <h2>解析结果</h2>
                  <button
                    class="refresh-icon"
                    :class="{ spinning: state.resultRefreshing }"
                    type="button"
                    title="刷新解析结果"
                    :disabled="!state.task?.id || state.resultRefreshing"
                    @click="refreshCurrentTaskResult"
                  >
                    ↻
                  </button>
                </div>
              </div>
              <div class="result-actions">
                <div class="result-tabs">
                  <button
                    :class="{ active: state.resultView === 'summary' }"
                    @click="state.resultView = 'summary'"
                  >
                    简洁摘要
                  </button>
                  <button
                    :class="{ active: state.resultView === 'transcript' }"
                    @click="state.resultView = 'transcript'"
                  >
                    转录文本
                  </button>
                </div>
              </div>
            </div>

            <div class="result-grid">
              <section v-if="state.resultView === 'summary'">
                <div v-if="structuredSummary.length" class="summary-structured">
                  <template v-for="block in structuredSummary" :key="block.title">
                    <div v-if="block.type === 'intro'" class="summary-intro">
                      <h4>{{ block.title }}</h4>
                      <p v-for="paragraph in block.paragraphs" :key="paragraph">{{ paragraph }}</p>
                    </div>
                    <div v-else class="summary-points">
                      <template v-for="item in block.items" :key="`${block.title}-${item.title}`">
                        <h4 class="summary-point-title">{{ item.title }}</h4>
                        <p v-for="paragraph in item.paragraphs" :key="paragraph" class="summary-point-paragraph">
                          {{ paragraph }}
                        </p>
                      </template>
                    </div>
                  </template>
                </div>
                <p v-else>{{ summaryPreview }}</p>
              </section>
              <section v-else>
                <span class="result-kicker">Transcript</span>
                <h3>转录文本</h3>
                <p>{{ transcriptPreview }}</p>
              </section>
            </div>
          </article>

      <article class="panel chat-card">
        <div class="section-head">
          <div>
            <p class="eyebrow">Assistant</p>
            <h2>{{ state.chatView === 'history' ? '历史会话' : '智能助手' }}</h2>
          </div>
          <div class="chat-actions">
            <el-button
              v-if="state.chatView !== 'history'"
              class="ghost-button"
              round
              :disabled="!state.selectedVideo?.id"
              @click="showHistory"
            >
              历史会话
            </el-button>
            <el-button
              class="ghost-button"
              round
              :disabled="!state.selectedVideo?.id || state.sessionDetailLoading"
              @click="prepareNewSession"
            >
              新建对话
            </el-button>
          </div>
        </div>

        <div v-if="state.chatView === 'history'" class="history-view">
          <div class="history-heading">
            <span class="result-kicker">当前视频工作空间</span>
            <h3>{{ selectedVideoTitle }}</h3>
            <p>每条会话只属于当前视频，点击后可继续之前的对话。</p>
          </div>

          <div v-if="state.sessionListLoading" class="history-state">正在加载历史会话...</div>
          <div v-else-if="state.sessionListError" class="history-state">
            <p>{{ state.sessionListError }}</p>
            <el-button class="ghost-button" round @click="loadSessions()">重新加载</el-button>
          </div>
          <div v-else-if="!state.sessions.length" class="history-state">
            <h3>当前视频还没有历史会话</h3>
            <p>开始一次新对话后，会话将显示在这里</p>
            <el-button class="gold-button" round @click="prepareNewSession">新建会话</el-button>
          </div>
          <div
            v-else
            ref="historyListRef"
            class="history-list"
            @scroll="rememberHistoryScroll"
          >
            <button
              v-for="session in state.sessions"
              :key="session.id"
              class="history-row"
              :class="{ active: state.activeSessionId === session.id }"
              :disabled="state.sessionDetailLoading && state.activeSessionId === session.id"
              @click="openSession(session.id, state.selectedVideo.id, true)"
            >
              <span class="history-copy">
                <strong>{{ sessionTitle(session) }}</strong>
                <small>{{ sessionPreview(session) }}</small>
              </span>
              <time>{{ formatSessionTime(session.updatedTime) }}</time>
            </button>
          </div>
        </div>

        <div v-else class="chat-layout">
          <div class="conversation">
            <button
              v-if="state.activeSessionId"
              class="history-back"
              type="button"
              @click="backToHistory"
            >
              &lt; 返回历史会话
            </button>
            <div v-if="state.sessionDetailLoading" class="history-state">正在加载会话...</div>
            <div v-else class="messages">
                  <div v-for="(message, index) in state.messages" :key="index" class="message" :class="message.role?.toLowerCase()">
                    <strong>{{ message.role === 'USER' ? '你' : 'VideoMind' }}</strong>
                    <p>{{ message.content }}</p>
                    <div v-if="parseRefs(message).length" class="references">
                      <span class="reference-heading">参考来源</span>
                      <button
                        v-for="(ref, refIndex) in parseRefs(message)"
                        :key="`${ref.taskId || ref.url}-${ref.chunkIndex || refIndex}`"
                        class="reference-card"
                        type="button"
                        @click="openReference(ref)"
                      >
                        <span class="reference-kind" :class="referenceType(ref).toLowerCase()">
                          {{ referenceType(ref) === 'WEB' ? '互联网扩展' : '视频内容' }}
                        </span>
                        <strong>{{ referenceTitle(ref) }}</strong>
                        <small v-if="referenceType(ref) === 'WEB'">
                          {{ ref.domain || '网页' }}<template v-if="ref.publishedAt"> · {{ ref.publishedAt }}</template>
                        </small>
                        <small v-else>{{ ref.chunkText || `片段 #${ref.chunkIndex ?? '-'}` }}</small>
                      </button>
                    </div>
                  </div>
                </div>
                <div class="composer">
                  <div class="knowledge-scope-panel">
                    <div class="knowledge-scope-head">
                      <div>
                        <strong>会话知识库范围</strong>
                        <small>视频时间轴自动绑定；可附加多个文档知识库，新建会话后范围固定。</small>
                      </div>
                      <div>
                        <el-button size="small" :loading="state.knowledgeLoading" @click="loadKnowledgeBases">刷新</el-button>
                        <el-button size="small" type="primary" @click="createKnowledgeBase">新建知识库</el-button>
                      </div>
                    </div>
                    <el-checkbox-group v-model="state.selectedKnowledgeBaseIds" :disabled="Boolean(state.activeSessionId)">
                      <div v-for="knowledgeBase in documentKnowledgeBases" :key="knowledgeBase.id" class="knowledge-scope-row">
                        <el-checkbox :value="Number(knowledgeBase.id)" :disabled="!knowledgeBase.selectable">
                          {{ knowledgeBase.name }} · {{ knowledgeBase.status }} · {{ knowledgeBase.documentCount }} 个文档
                        </el-checkbox>
                        <el-upload
                          :show-file-list="false"
                          :http-request="(options) => uploadKnowledgeDocument(options, knowledgeBase)"
                          accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.html,.htm"
                        >
                          <el-button size="small" :loading="state.knowledgeUploadingId === knowledgeBase.id">上传附件</el-button>
                        </el-upload>
                      </div>
                    </el-checkbox-group>
                    <small v-if="!documentKnowledgeBases.length">尚无文档知识库。先新建知识库，再上传附件。</small>
                    <small v-if="state.activeSessionId">当前会话范围已锁定；如需更换知识库，请新建会话。</small>
                  </div>
                  <div class="answer-scope">
                    <span>回答范围</span>
                    <el-radio-group v-model="state.answerScope" size="small">
                      <el-radio-button value="KNOWLEDGE_ONLY">仅知识库</el-radio-button>
                      <el-radio-button value="KNOWLEDGE_EXTENDED">知识库扩展</el-radio-button>
                    </el-radio-group>
                    <small>{{ answerScopeHint }}</small>
                  </div>
                  <el-input
                    v-model="state.question"
                    size="large"
                    :placeholder="chatPlaceholder"
                    :disabled="!state.selectedVideo?.id || state.sessionDetailLoading"
                    @keyup.enter="sendQuestion"
                  />
                  <el-button
                    class="gold-button"
                    size="large"
                    round
                    :disabled="!canSendQuestion"
                    :loading="state.loadingChat"
                    @click="sendQuestion"
                  >
                    发送
                  </el-button>
                </div>
              </div>
            </div>
          </article>
        </section>
      </section>
    </section>

    <el-dialog
      v-model="transcriptDialog.visible"
      class="transcript-dialog"
      title="转录文本"
      width="min(760px, 92vw)"
      destroy-on-close
    >
      <div v-if="transcriptDialog.loading" class="transcript-dialog-state">正在读取最新转录文本...</div>
      <template v-else-if="transcriptDialog.data?.status === 'READY' && transcriptDialog.data?.transcriptionText">
        <div class="transcript-dialog-meta">
          <span>版本 {{ transcriptDialog.data.transcriptVersion }}</span>
          <span v-if="transcriptDialog.data.language">语言 {{ transcriptDialog.data.language }}</span>
        </div>
        <pre class="transcript-dialog-text">{{ transcriptDialog.data.transcriptionText }}</pre>
      </template>
      <div v-else class="transcript-dialog-state">{{ transcriptDialogStatus }}</div>
    </el-dialog>
  </main>
</template>
