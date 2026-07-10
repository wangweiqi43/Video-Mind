<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  videos: { type: Array, default: () => [] },
  selectedVideo: { type: Object, default: null },
  summaryText: { type: String, default: '' },
  summaryLoading: { type: Boolean, default: false },
  capabilities: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['select-video'])
const draft = ref('')
const suggestionPage = ref(0)

const videoKey = computed(() => props.selectedVideo?.id ? `videomind:advanced:draft:${props.selectedVideo.id}` : '')
const displaySummary = computed(() => props.summaryText || (props.summaryLoading
  ? '正在读取已有视频总结...'
  : '当前视频还没有可用摘要，请先在普通模式完成视频解析。'))

const suggestionPool = computed(() => {
  const title = props.selectedVideo?.originalFilename?.replace(/\.[^.]+$/, '') || '这个视频'
  const sectionTitles = String(props.summaryText || '')
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
}, { immediate: true })

watch(draft, (value) => {
  if (videoKey.value) localStorage.setItem(videoKey.value, value)
})

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

function sendAdvancedMessage() {
  if (!props.capabilities.advanced_chat) {
    unavailable('高级模式')
    return
  }
  unavailable('Agent 对话')
}

function handleExport(format) {
  const supported = format === 'pdf'
    ? props.capabilities.report_export_pdf
    : props.capabilities.report_export_docx
  if (!supported) {
    unavailable('智能报告导出功能')
    return
  }
  unavailable('Agent 报告导出功能')
}

function videoStatus(video) {
  if (video.id === props.selectedVideo?.id && props.summaryText) return '已解析'
  if (video.summaryStatus === 'SUCCESS') return '已解析'
  if (['PROCESSING', 'PENDING'].includes(video.summaryStatus)) return '处理中'
  return video.transcriptVersion > 0 ? '已转录' : '待解析'
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

    <article class="advanced-summary">
      <header>
        <div>
          <span>INSIGHT</span>
          <h2>AI 视频总结</h2>
        </div>
        <el-dropdown trigger="click" @command="handleExport">
          <button class="export-button" type="button" title="导出报告" data-testid="report-export">↗</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="pdf">PDF 格式</el-dropdown-item>
              <el-dropdown-item command="docx">Word 格式</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <div class="advanced-summary-body">
        <p>{{ displaySummary }}</p>
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
          <button type="button" @click="unavailable('高级新建对话')">新建对话</button>
        </div>
      </header>

      <div class="advanced-notice">
        <strong>高级 Agent 模式预览</strong>
        <span>当前可以查看视频总结和推荐问题；Agent 对话、深度研究、PPT 与报告导出将在后续开放。</span>
      </div>

      <div class="agent-conversation">
        <div class="agent-empty">
          <div class="agent-mark">VM</div>
          <h3>围绕当前视频继续探索</h3>
          <p>Agent 服务接入后，这里会展示流式回答、研究进度、引用来源和任务卡片。</p>
        </div>

        <section class="suggestions" data-testid="suggested-questions">
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

      <div class="advanced-composer" @click.self="unavailable('高级模式')">
        <el-input
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          placeholder="高级 Agent 能力即将上线，暂时无法发送消息"
          resize="none"
          @keyup.ctrl.enter="sendAdvancedMessage"
        />
        <div class="advanced-tools">
          <button class="add-tool" type="button" title="添加附件" @click="unavailable('附件功能')">＋</button>
          <button type="button" data-testid="deep-research" @click="unavailable('深度研究功能')">
            ◇ 深度研究 <small>Beta</small>
          </button>
          <button type="button" data-testid="ppt-generation" @click="unavailable('PPT 生成功能')">
            ▣ PPT 生成
          </button>
          <span class="tool-spacer" />
          <button class="send-tool" type="button" aria-disabled="true" @click="sendAdvancedMessage">↑</button>
        </div>
      </div>
    </article>
  </section>
</template>

<style scoped>
.advanced-layout {
  display: grid;
  grid-template-columns: minmax(210px, 240px) minmax(380px, 0.9fr) minmax(440px, 1.1fr);
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
  padding: 22px 14px 18px 0;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  overflow: hidden;
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
}

.advanced-summary-body {
  min-height: 0;
  overflow: auto;
  padding-right: 8px;
}

.advanced-summary-body p {
  color: #ded4c7;
  white-space: pre-wrap;
  line-height: 1.85;
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
