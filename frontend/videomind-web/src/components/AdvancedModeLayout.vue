<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { api } from '../api'

const props = defineProps({
  videos: { type: Array, default: () => [] },
  selectedVideo: { type: Object, default: null },
  active: { type: Boolean, default: false },
  analysisRunning: { type: Boolean, default: false },
  capabilities: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['select-video'])
const draft = ref('')
const suggestionPage = ref(0)
const messages = ref([])
const activeSessionId = ref(null)
const sending = ref(false)
const webSearchEnabled = ref(false)
const toolBusy = ref(false)
const report = ref(null)
const reportLoading = ref(false)
const reportError = ref('')
let reportTimer = null
const ingest = ref(null)
const ingestLoading = ref(false)
const ingestError = ref('')
let ingestTimer = null
let ingestRequestVersion = 0

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: false })
const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, env, self) => self.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, env, self)
}

const videoKey = computed(() => props.selectedVideo?.id ? `videomind:advanced:draft:${props.selectedVideo.id}` : '')
const reportHtml = computed(() => markdown.render(report.value?.reportMarkdown || ''))
const reportStatusText = computed(() => {
  if (!props.selectedVideo?.id) return '请先选择一个已完成转录的视频。'
  if (!props.capabilities.advanced_report) return '高级摘要总结功能尚未启用。'
  if (reportError.value) return reportError.value
  if (reportLoading.value && !report.value) return '正在读取高级摘要总结状态…'
  const status = String(report.value?.status || '').toUpperCase()
  if (status === 'SYNCING' || status === 'READY') return '正在清洗并建立隐藏的转录研究素材…'
  if (status === 'NOT_STARTED') return '尚未生成高级摘要总结，请点击上方“生成高级摘要总结”。'
  if (['PENDING', 'RUNNING', 'PROCESSING'].includes(status)) {
    const stage = report.value?.stage ? ` · ${report.value.stage}` : ''
    return `高级摘要总结生成并入库中${report.value?.progress != null ? `（${report.value.progress}%）` : ''}${stage}…`
  }
  if (['FAILED', 'CANCELLED'].includes(status)) return report.value?.errorMessage || '高级摘要总结生成失败，可重新尝试。'
  return '尚未生成高级摘要总结。'
})
const ingestStatus = computed(() => String(ingest.value?.status || 'UNSYNCED').toUpperCase())
const ingestStatusText = computed(() => {
  if (!props.selectedVideo?.id) return '请选择视频后同步转录。'
  if (!props.capabilities.agent_ingest) return 'Agent Platform 已接入，转录同步能力当前关闭。'
  if (!(Number(props.selectedVideo?.transcriptVersion) > 0)) return '点击上方“生成高级摘要总结”后将先完成视频转录。'
  if (ingestError.value) return ingestError.value
  if (ingestLoading.value && !ingest.value) return '正在创建转录同步任务…'
  if (ingestStatus.value === 'SUCCESS') return '转录已完成规则清洗和固定 Token 切分，隐藏研究素材已就绪。'
  if (ingestStatus.value === 'FAILED') return ingest.value?.errorMessage || '转录同步失败，可重新同步。'
  if (ingestStatus.value === 'CANCELLED') return '转录同步已取消，可重新同步。'
  if (['PENDING', 'RUNNING'].includes(ingestStatus.value)) {
    const stage = ingest.value?.stage ? ` · ${ingest.value.stage}` : ''
    const progress = ingest.value?.progress != null ? ` · ${ingest.value.progress}%` : ''
    return `正在同步视频转录${stage}${progress}`
  }
  return '尚未同步；只有点击“生成高级摘要总结”才会启动。'
})
const canSend = computed(() => Boolean(
  props.capabilities.advanced_chat
  && props.selectedVideo?.id
  && draft.value.trim()
  && !sending.value
))

const suggestionPool = computed(() => {
  const title = props.selectedVideo?.originalFilename?.replace(/\.[^.]+$/, '') || '这个视频'
  const sectionTitles = String(report.value?.reportMarkdown || '')
    .split(/\r?\n/)
    .map((line) => line.match(/^#{1,3}\s+(.+)$/)?.[1]?.trim())
    .filter(Boolean)
    .slice(0, 4)
  const contextual = sectionTitles.flatMap((section) => [
    `“${section}”这一部分有哪些关键观点？`,
    `请进一步解释视频中的“${section}”。`
  ])
  return [
    `请概括《${title}》的核心内容。`,
    '视频中有哪些重要观点？',
    '请按照时间顺序整理视频内容。',
    '视频中有哪些值得进一步了解的信息？',
    '作者的主要结论和依据分别是什么？',
    '哪些内容适合整理成学习笔记？',
    '视频里有哪些概念可以继续深入研究？',
    '请列出视频内容之间的逻辑关系。',
    ...contextual
  ]
})

const suggestions = computed(() => {
  const pool = suggestionPool.value
  const start = (suggestionPage.value * 4) % pool.length
  return Array.from({ length: 4 }, (_, index) => pool[(start + index) % pool.length])
})

watch(videoKey, (key) => {
  draft.value = key ? localStorage.getItem(key) || '' : ''
  suggestionPage.value = 0
  messages.value = []
  activeSessionId.value = null
  webSearchEnabled.value = false
}, { immediate: true })

watch(draft, (value) => {
  if (videoKey.value) localStorage.setItem(videoKey.value, value)
})

watch(
  () => [props.active, props.selectedVideo?.id, props.analysisRunning,
    props.selectedVideo?.agentReportStatus, props.capabilities.agent_ingest],
  ([active, videoId, running, reportStatus, enabled]) => {
    stopReportPolling()
    stopIngestPolling()
    ingestRequestVersion += 1
    ingest.value = null
    report.value = null
    ingestError.value = ''
    reportError.value = ''
    if (active && videoId && enabled) {
      refreshIngest(ingestRequestVersion, Boolean(running))
      if (running || reportStatus) refreshAdvancedReport(Boolean(running))
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  stopReportPolling()
  stopIngestPolling()
})

async function startIngestSync(requestVersion = ingestRequestVersion) {
  if (!props.active || !props.selectedVideo?.id || !props.capabilities.agent_ingest
      || !(Number(props.selectedVideo?.transcriptVersion) > 0)) return
  ingestLoading.value = true
  ingestError.value = ''
  try {
    const current = await api.syncMindAgentVideo(props.selectedVideo.id)
    if (requestVersion !== ingestRequestVersion) return
    ingest.value = current
    scheduleIngestPolling(requestVersion)
  } catch (error) {
    if (requestVersion === ingestRequestVersion) ingestError.value = error.message || '转录同步启动失败'
  } finally {
    if (requestVersion === ingestRequestVersion) ingestLoading.value = false
  }
}

async function refreshIngest(requestVersion, keepWaiting = props.analysisRunning) {
  if (requestVersion !== ingestRequestVersion || !props.active || !props.selectedVideo?.id) return
  try {
    ingest.value = await api.getMindAgentVideoSync(props.selectedVideo.id)
    if (ingestStatus.value === 'SUCCESS') refreshAdvancedReport(keepWaiting)
    scheduleIngestPolling(requestVersion, keepWaiting)
  } catch (error) {
    if (requestVersion === ingestRequestVersion) ingestError.value = error.message || '转录同步状态读取失败'
  }
}

function scheduleIngestPolling(requestVersion, keepWaiting = props.analysisRunning) {
  stopIngestPolling()
  if (requestVersion !== ingestRequestVersion || !props.active
      || ['SUCCESS', 'FAILED', 'CANCELLED'].includes(ingestStatus.value)
      || (ingestStatus.value === 'UNSYNCED' && !keepWaiting)) return
  ingestTimer = window.setTimeout(() => refreshIngest(requestVersion, keepWaiting), 2000)
}

function stopIngestPolling() {
  if (ingestTimer != null) window.clearTimeout(ingestTimer)
  ingestTimer = null
}

function retryIngest() {
  ingestError.value = ''
  startIngestSync(ingestRequestVersion)
}

async function ensureAdvancedReport() {
  if (!props.active || !props.selectedVideo?.id || !props.capabilities.advanced_report) return
  reportLoading.value = true
  reportError.value = ''
  try {
    report.value = await api.ensureAdvancedReport(props.selectedVideo.id)
    scheduleReportPolling()
  } catch (error) {
    reportError.value = error.message || '高级摘要总结启动失败'
  } finally {
    reportLoading.value = false
  }
}

async function refreshAdvancedReport(keepWaiting = props.analysisRunning) {
  if (!props.active || !props.selectedVideo?.id) return
  try {
    const current = await api.getAdvancedReport(props.selectedVideo.id)
    report.value = current
    const status = String(current?.status || '').toUpperCase()
    if (!['SUCCESS', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(status)
        && (keepWaiting || !['NOT_STARTED', 'READY', 'SYNCING'].includes(status))) scheduleReportPolling(keepWaiting)
  } catch (error) {
    reportError.value = error.message || '高级摘要总结状态读取失败'
    if (keepWaiting) scheduleReportPolling(true)
  }
}

function scheduleReportPolling(keepWaiting = props.analysisRunning) {
  stopReportPolling()
  const status = String(report.value?.status || '').toUpperCase()
  if (!props.active || ['SUCCESS', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(status)) return
  reportTimer = window.setTimeout(() => refreshAdvancedReport(keepWaiting), 2000)
}

function stopReportPolling() {
  if (reportTimer != null) window.clearTimeout(reportTimer)
  reportTimer = null
}

function selectVideo(video) {
  emit('select-video', video)
}

function refreshSuggestions() {
  suggestionPage.value += 1
}

function chooseSuggestion(question) {
  draft.value = question
}

function unavailable(feature = '高级模式') {
  ElMessage.info({
    message: `${feature}正在建设中，暂时无法使用，敬请期待。`,
    customClass: 'videomind-toast'
  })
}

async function ensureSession() {
  if (activeSessionId.value) return activeSessionId.value
  const session = await api.createSession(props.selectedVideo.id, 'ADVANCED')
  activeSessionId.value = session.id || session.sessionId
  return activeSessionId.value
}

function newConversation() {
  messages.value = []
  activeSessionId.value = null
  draft.value = ''
}

function toggleWebSearch() {
  if (!props.capabilities.web_search) {
    unavailable('Agent 联网搜索功能')
    return
  }
  webSearchEnabled.value = !webSearchEnabled.value
}

async function ensureAgentReady() {
  if (!props.selectedVideo?.id) { ElMessage.warning('请先选择一个已解析的视频'); return false }
  if (!(Number(props.selectedVideo?.transcriptVersion) > 0)) {
    ElMessage.warning('请先点击共享操作栏生成高级摘要总结')
    return false
  }
  const binding = await api.mindAgentBindingStatus()
  if (!binding.bound) { ElMessage.warning('请先点击页面右上角“绑定 MindAgent”并完成账号授权'); return false }
  if (ingestStatus.value !== 'SUCCESS') await startIngestSync(ingestRequestVersion)
  if (ingestStatus.value !== 'SUCCESS') {
    ElMessage.info('正在将当前视频转录同步到 MindAgent，请稍后重试')
    return false
  }
  const reportStatus = String(report.value?.status || '').toUpperCase()
  if (!['SUCCESS', 'COMPLETED'].includes(reportStatus) || !report.value?.reportKnowledgeBaseId) {
    await ensureAdvancedReport()
    ElMessage.info('正在生成高级摘要总结并建立正式知识库，请稍后重试')
    return false
  }
  return true
}

async function generatePresentation() {
  if (!props.capabilities.ppt_generation) return unavailable('PPT 生成功能')
  if (toolBusy.value || !(await ensureAgentReady())) return
  toolBusy.value = true
  try {
    const task = await api.createPresentation(props.selectedVideo.id, {
      template: 'professional', language: 'zh-CN', slideCount: 10, audience: 'general', tone: 'concise'
    })
    messages.value.push({ role: 'ASSISTANT', content: `PPT 任务已创建（${task.status}），完成后会在这里提供下载。` })
    pollToolTask('presentation', task.id)
  } catch (error) { ElMessage.error(error.message || 'PPT 任务创建失败') }
  finally { toolBusy.value = false }
}

async function pollToolTask(type, id) {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 2000))
    try {
      const task = await api.getPresentation(props.selectedVideo.id, id)
      if (task.status === 'SUCCESS') {
        messages.value.push({ role: 'ASSISTANT', content: 'PPT 已生成，可下载并继续编辑。', downloadUrl: task.downloadUrl })
        return
      }
      if (['FAILED', 'CANCELLED'].includes(task.status)) {
        ElMessage.error(task.errorMessage || 'PPT 任务失败')
        return
      }
    } catch { return }
  }
  ElMessage.info('任务仍在后台执行，可稍后重新进入高级模式查看')
}

async function sendAdvancedMessage() {
  if (!props.capabilities.advanced_chat) {
    unavailable('高级模式')
    return
  }
  if (!props.selectedVideo?.id) {
    ElMessage.warning('请先选择一个已解析的视频')
    return
  }
  if (!canSend.value) return

  if (!(await ensureAgentReady())) return

  const question = draft.value.trim()
  draft.value = ''
  sending.value = true
  const userMessage = { role: 'USER', content: question }
  const assistantMessage = { role: 'ASSISTANT', content: '', references: [] }
  messages.value.push(userMessage, assistantMessage)
  try {
    const sessionId = await ensureSession()
    const answer = await api.streamMessage(
      sessionId,
      props.selectedVideo.id,
      question,
      'KNOWLEDGE_EXTENDED',
      'ADVANCED',
      (delta) => { assistantMessage.content += delta },
      webSearchEnabled.value
    )
    if (!assistantMessage.content && answer?.answer) assistantMessage.content = answer.answer
    assistantMessage.references = answer?.references || parseReferences(answer?.referencesJson)
  } catch (error) {
    if (!assistantMessage.content) messages.value.pop()
    ElMessage.error(error.message || 'Agent 对话失败')
  } finally {
    sending.value = false
  }
}

function parseReferences(value) {
  if (!value) return []
  try { return JSON.parse(value) } catch { return [] }
}

function referenceTitle(reference, index) {
  return reference.title || reference.domain || reference.chunkText?.slice(0, 42) || `来源 ${index + 1}`
}

function videoStatus(video) {
  if (video.id === props.selectedVideo?.id && ['SUCCESS', 'COMPLETED'].includes(String(report.value?.status).toUpperCase())) return '高级摘要已完成'
  if (video.agentReportStatus === 'SUCCESS') return '高级摘要已完成'
  if (['PROCESSING', 'PENDING', 'RUNNING'].includes(video.agentReportStatus)) return '高级摘要处理中'
  return video.transcriptVersion > 0 ? '待生成高级摘要' : '待转录'
}
</script>

<template>
  <section class="advanced-layout" data-testid="advanced-layout">
    <aside class="advanced-videos">
      <div class="advanced-column-title">
        <span>VIDEOS</span>
        <strong>视频列表</strong>
      </div>
      <div class="advanced-video-list">
        <button
          v-for="video in videos"
          :key="video.id"
          type="button"
          :class="{ active: selectedVideo?.id === video.id }"
          @click="selectVideo(video)"
        >
          <i />
          <span>
            <strong>{{ video.originalFilename }}</strong>
            <small>{{ videoStatus(video) }}</small>
          </span>
        </button>
      </div>
    </aside>

    <div class="advanced-toolbar">
      <slot name="toolbar" />
    </div>

    <article class="advanced-summary">
      <header>
        <div>
          <span>RESEARCH</span>
          <h2>高级摘要总结</h2>
        </div>
        <a
          v-if="report?.downloadUrl"
          class="export-button"
          :href="report.downloadUrl"
          title="下载 Markdown 报告"
          data-testid="report-download"
        >↓</a>
      </header>
      <div class="advanced-summary-body">
        <div v-if="reportHtml" class="research-markdown" v-html="reportHtml" />
        <div v-else class="research-state">
          <p>{{ reportStatusText }}</p>
          <button
            v-if="['FAILED', 'CANCELLED'].includes(String(report?.status).toUpperCase()) || reportError"
            type="button"
            @click="ensureAdvancedReport"
          >重新生成</button>
        </div>
      </div>
    </article>

    <article class="agent-panel">
      <header class="agent-header">
        <div>
          <span>AGENT</span>
          <h2>VideoMind Agent</h2>
          <small v-if="selectedVideo">正在分析：{{ selectedVideo.originalFilename }}</small>
        </div>
        <div class="agent-header-actions">
          <button type="button" @click="unavailable('高级历史会话')">历史会话</button>
          <button type="button" @click="newConversation">新建对话</button>
        </div>
      </header>

      <div class="advanced-notice">
        <strong>{{ capabilities.agent_enabled ? 'Agent Platform 已接入' : 'Agent Platform 未连接' }}</strong>
        <span v-if="capabilities.agent_ingest">{{ ingestStatusText }}</span>
        <span v-else-if="capabilities.advanced_chat">高级问答由独立 Agent Platform 执行；高级摘要总结仅在明确点击后生成。</span>
        <span v-else-if="capabilities.agent_enabled">VideoMind 已完成平台连接，高级问答等功能将按独立能力开关开放。</span>
        <span v-else>完成 Agent Platform 连接配置后，可使用高级知识库问答、高级摘要总结与内容生成能力。</span>
        <button
          v-if="capabilities.agent_ingest && (ingestError || ['FAILED', 'CANCELLED'].includes(ingestStatus))"
          type="button"
          :disabled="ingestLoading"
          @click="retryIngest"
        >重新同步</button>
      </div>

      <div class="agent-conversation">
        <div v-if="!messages.length" class="agent-empty">
          <div class="agent-mark">VM</div>
          <h3>围绕当前视频继续探索</h3>
          <p>高级模式优先使用高级摘要总结知识库；摘要未覆盖细节时，Agent 会回查隐藏的转录原文。</p>
        </div>

        <section v-if="messages.length" class="advanced-messages" aria-live="polite">
          <article v-for="(message, messageIndex) in messages" :key="messageIndex" :class="['advanced-message', message.role.toLowerCase()]">
            <small>{{ message.role === 'USER' ? '你' : 'VideoMind Agent' }}</small>
            <p>{{ message.content || (sending && messageIndex === messages.length - 1 ? '正在思考…' : '') }}</p>
            <a v-if="message.downloadUrl" :href="message.downloadUrl" target="_blank" rel="noopener noreferrer">下载生成文件</a>
            <div v-if="message.references?.length" class="agent-references">
              <strong>参考来源</strong>
              <a
                v-for="(reference, index) in message.references"
                :key="`${messageIndex}-${index}`"
                :href="reference.sourceType === 'WEB' ? reference.url : undefined"
                :target="reference.sourceType === 'WEB' ? '_blank' : undefined"
                :rel="reference.sourceType === 'WEB' ? 'noopener noreferrer' : undefined"
              >{{ referenceTitle(reference, index) }}</a>
            </div>
          </article>
        </section>

        <section v-else class="suggestions" data-testid="suggested-questions">
          <header>
            <strong>你可能想问</strong>
            <button type="button" @click="refreshSuggestions">换一换</button>
          </header>
          <button
            v-for="(question, index) in suggestions"
            :key="`${suggestionPage}-${index}`"
            type="button"
            @click="chooseSuggestion(question)"
          >
            <span>{{ index + 1 }}</span>{{ question }}
          </button>
        </section>
      </div>

      <div class="advanced-composer">
        <el-input
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          :disabled="!capabilities.advanced_chat || !selectedVideo || sending"
          :placeholder="capabilities.advanced_chat ? '向 Agent 询问当前视频内容（Ctrl + Enter 发送）' : capabilities.agent_enabled ? 'Agent Platform 已接入，高级问答开关当前关闭' : '连接 Agent Platform 后可使用高级问答'"
          resize="none"
          @keyup.ctrl.enter="sendAdvancedMessage"
        />
        <div class="advanced-tools">
          <button class="add-tool" type="button" title="添加附件" @click="unavailable('附件功能')">＋</button>
          <button
            class="web-search-tool"
            :class="{ active: webSearchEnabled }"
            type="button"
            data-testid="web-search"
            :disabled="!capabilities.web_search || sending"
            @click="toggleWebSearch"
          >◎ 联网搜索</button>
          <button type="button" data-testid="ppt-generation" :disabled="!capabilities.ppt_generation || toolBusy" @click="generatePresentation">
            ▣ PPT 生成
          </button>
          <span class="tool-spacer" />
          <button class="send-tool" :class="{ ready: canSend }" type="button" :aria-disabled="!canSend" @click="sendAdvancedMessage">↑</button>
        </div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.advanced-layout {
  display: grid;
  grid-template-columns: minmax(210px, 240px) minmax(380px, 0.9fr) minmax(440px, 1.1fr);
  grid-template-rows: auto minmax(0, 1fr);
  grid-template-areas:
    "videos toolbar toolbar"
    "videos summary agent";
  height: 100%;
  min-height: 0;
  border-top: 1px solid rgba(231, 185, 111, 0.18);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(7, 7, 9, 0.3);
  overflow: hidden;
}

.advanced-videos,
.advanced-summary,
.agent-panel {
  min-width: 0;
  min-height: 0;
}

.advanced-videos {
  grid-area: videos;
  padding: 22px 14px 18px 0;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.advanced-toolbar {
  grid-area: toolbar;
  min-width: 0;
  padding-left: 14px;
}

.advanced-toolbar :deep(.control-panel) {
  height: 100%;
}

.advanced-summary {
  grid-area: summary;
}

.agent-panel {
  grid-area: agent;
}

.advanced-column-title {
  display: grid;
  gap: 5px;
  margin: 0 12px 18px;
}

.advanced-column-title span,
.advanced-summary header span,
.agent-header span {
  color: var(--gold);
  font-family: "Aptos", "Microsoft YaHei", sans-serif;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.2em;
}

.advanced-video-list {
  display: grid;
  gap: 5px;
  max-height: calc(100% - 58px);
  overflow: auto;
}

.advanced-video-list button {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  padding: 11px 12px;
  border: 0;
  border-left: 2px solid transparent;
  color: var(--text);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.advanced-video-list button:hover,
.advanced-video-list button.active {
  border-left-color: var(--gold);
  background: linear-gradient(90deg, rgba(231, 185, 111, 0.13), transparent);
}

.advanced-video-list i {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--gold);
  box-shadow: 0 0 12px rgba(231, 185, 111, 0.55);
}

.advanced-video-list span {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.advanced-video-list strong,
.advanced-video-list small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.advanced-video-list small {
  color: var(--muted);
}

.advanced-summary {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  padding: 22px 24px;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}

.advanced-summary header,
.agent-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.advanced-summary h2,
.agent-header h2 {
  margin: 5px 0 0;
  font-size: 25px;
}

.export-button {
  width: 36px;
  height: 36px;
  border: 1px solid rgba(231, 185, 111, 0.25);
  border-radius: 50%;
  color: var(--gold);
  background: rgba(231, 185, 111, 0.08);
  cursor: pointer;
  font-size: 20px;
  display: grid;
  place-items: center;
  text-decoration: none;
}

.advanced-summary-body {
  min-height: 0;
  overflow: auto;
  padding-right: 8px;
}

.advanced-summary-body p {
  color: #ded4c7;
  line-height: 1.85;
}

.research-state {
  display: grid;
  place-items: center;
  min-height: 220px;
  text-align: center;
}

.research-state button {
  padding: 8px 15px;
  border: 1px solid rgba(231, 185, 111, 0.35);
  border-radius: 18px;
  color: var(--gold);
  background: rgba(231, 185, 111, 0.08);
  cursor: pointer;
}

.research-markdown {
  color: #ded4c7;
  line-height: 1.75;
}

.research-markdown :deep(h1),
.research-markdown :deep(h2),
.research-markdown :deep(h3) {
  margin: 1.25em 0 0.55em;
  color: #fff8ec;
  line-height: 1.35;
}

.research-markdown :deep(h1) { font-size: 24px; }
.research-markdown :deep(h2) { font-size: 19px; }
.research-markdown :deep(h3) { font-size: 16px; }
.research-markdown :deep(ul),
.research-markdown :deep(ol) { padding-left: 1.35em; }
.research-markdown :deep(a) { color: var(--gold); }
.research-markdown :deep(pre) {
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.32);
}
.research-markdown :deep(code) { font-family: Consolas, monospace; }
.research-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
}
.research-markdown :deep(th),
.research-markdown :deep(td) {
  padding: 7px 9px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  text-align: left;
}

.agent-panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  padding: 22px 0 16px 24px;
}

.agent-header {
  padding-right: 2px;
}

.agent-header small {
  display: block;
  max-width: 310px;
  margin-top: 7px;
  overflow: hidden;
  color: var(--muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-header-actions {
  display: flex;
  gap: 8px;
}

.agent-header-actions button,
.suggestions header button {
  border: 0;
  color: var(--muted);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.advanced-notice {
  display: grid;
  gap: 5px;
  margin: 14px 18px 4px 0;
  padding: 10px 12px;
  border-left: 2px solid var(--gold);
  color: var(--muted);
  background: rgba(231, 185, 111, 0.07);
  font-size: 12px;
  line-height: 1.5;
}

.advanced-notice strong {
  color: var(--gold);
}

.advanced-notice button {
  width: max-content;
  padding: 5px 11px;
  border: 1px solid rgba(231, 185, 111, 0.35);
  border-radius: 14px;
  color: var(--gold);
  background: rgba(231, 185, 111, 0.08);
  cursor: pointer;
}

.agent-conversation {
  min-height: 0;
  overflow: auto;
  padding: 12px 18px 10px 0;
}

.agent-empty {
  display: grid;
  place-items: center;
  padding: 20px 12px 14px;
  text-align: center;
}

.agent-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(231, 185, 111, 0.32);
  border-radius: 14px;
  color: var(--gold);
  background: rgba(231, 185, 111, 0.08);
  font-size: 13px;
  font-weight: 800;
}

.agent-empty h3 {
  margin: 11px 0 5px;
  font-size: 18px;
}

.agent-empty p {
  max-width: 430px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.advanced-messages {
  display: grid;
  gap: 14px;
}

.advanced-message {
  display: grid;
  gap: 6px;
  max-width: 92%;
}

.advanced-message.user {
  justify-self: end;
}

.advanced-message small {
  color: var(--muted);
  font-size: 10px;
}

.advanced-message.user small {
  text-align: right;
}

.advanced-message p {
  margin: 0;
  padding: 10px 13px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 4px 15px 15px 15px;
  color: #e7ded1;
  background: rgba(255, 255, 255, 0.045);
  white-space: pre-wrap;
  line-height: 1.65;
}

.advanced-message.user p {
  border-color: rgba(231, 185, 111, 0.18);
  border-radius: 15px 4px 15px 15px;
  background: rgba(231, 185, 111, 0.1);
}

.agent-references {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.agent-references strong {
  color: var(--muted);
  font-size: 10px;
}

.agent-references a {
  max-width: 220px;
  padding: 4px 8px;
  overflow: hidden;
  border: 1px solid rgba(231, 185, 111, 0.16);
  border-radius: 999px;
  color: var(--gold);
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
}

.suggestions {
  display: grid;
  gap: 5px;
  margin-top: 8px;
}

.suggestions header {
  display: flex;
  justify-content: space-between;
  padding: 0 4px 7px;
}

.suggestions header button {
  color: var(--gold);
}

.suggestions > button {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid transparent;
  color: #d9d0c3;
  background: rgba(255, 255, 255, 0.025);
  text-align: left;
  cursor: pointer;
  font-size: 12px;
  line-height: 1.45;
}

.suggestions > button:hover {
  border-color: rgba(231, 185, 111, 0.24);
  background: rgba(231, 185, 111, 0.07);
}

.suggestions > button span {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--gold);
  background: rgba(231, 185, 111, 0.1);
  font-size: 10px;
}

.advanced-composer {
  margin: 0 18px 0 0;
  padding: 10px 12px 8px;
  border: 1px solid rgba(231, 185, 111, 0.2);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.045);
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.22);
}

.advanced-composer :deep(.el-textarea__inner) {
  min-height: 48px !important;
  padding: 3px 4px !important;
  border: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
}

.advanced-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 7px;
}

.advanced-tools button {
  min-height: 31px;
  padding: 0 10px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.035);
  cursor: pointer;
  font-size: 12px;
}

.advanced-tools button:hover {
  color: var(--text);
  border-color: rgba(231, 185, 111, 0.28);
}

.advanced-tools button:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.advanced-tools .web-search-tool.active {
  color: #171008;
  border-color: var(--gold);
  background: var(--gold);
  opacity: 1;
}

.advanced-tools button small {
  margin-left: 3px;
  color: var(--gold);
  font-size: 9px;
}

.advanced-tools .add-tool,
.advanced-tools .send-tool {
  width: 32px;
  min-width: 32px;
  padding: 0;
  font-size: 18px;
}

.advanced-tools .send-tool {
  color: #171008;
  border-color: var(--gold);
  background: var(--gold);
  opacity: 0.52;
}

.advanced-tools .send-tool.ready {
  opacity: 1;
}

.tool-spacer {
  flex: 1;
}

@media (max-width: 1180px) {
  .advanced-layout {
    grid-template-columns: 190px minmax(330px, 0.9fr) minmax(390px, 1.1fr);
  }
}

@media (max-width: 900px) {
  .advanced-layout {
    grid-template-columns: 74px minmax(330px, 0.9fr) minmax(380px, 1.1fr);
  }

  .advanced-column-title strong,
  .advanced-video-list button span {
    display: none;
  }

  .advanced-video-list button {
    place-items: center;
    grid-template-columns: 1fr;
  }
}
</style>
