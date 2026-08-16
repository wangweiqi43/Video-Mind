<script setup>
import { computed } from 'vue'
import { formatDate, formatDuration, formatFileSize, parseSummary, taskStatusMeta } from '../presentation'

const props = defineProps({
  selectedVideo: { type: Object, default: null }, task: { type: Object, default: null }, taskResult: { type: Object, default: null },
  taskLoading: { type: Boolean, default: false }, resultLoading: { type: Boolean, default: false },
  resultRefreshing: { type: Boolean, default: false }, resultView: { type: String, default: 'summary' },
  uploading: { type: Boolean, default: false }
})
defineEmits(['upload', 'analyze', 'play', 'show-transcript', 'refresh-result', 'update:result-view'])

const status = computed(() => taskStatusMeta(props.task, props.taskResult, props.resultLoading))
const summary = computed(() => parseSummary(props.taskResult?.summaryText))
const summaryMissing = computed(() => {
  const taskStatus = String(props.task?.taskStatus || props.task?.status || '').toUpperCase()
  return taskStatus === 'SUCCESS' && Boolean(props.taskResult) && !String(props.taskResult?.summaryText || '').trim()
})
const summaryEmptyTitle = computed(() => summaryMissing.value ? '摘要未生成' : '等待解析结果')
const summaryPlaceholder = computed(() => {
  if (props.resultLoading) return '正在读取视频摘要…'
  if (summaryMissing.value) return '该历史任务只保存了转录文本，没有生成视频摘要。请重新执行 AI 视频解析补齐结果。'
  return '完成 AI 视频解析后，这里会显示结构化摘要。'
})
const transcriptPlaceholder = computed(() => props.resultLoading ? '正在读取转录文本…' : '完成 AI 视频解析后，这里会显示语音转录。')
</script>

<template>
  <section class="analysis-workspace" aria-label="视频解析工作区">
    <template v-if="selectedVideo">
      <header class="current-video">
        <p class="section-label">当前视频</p>
        <div class="video-identity">
          <span class="large-video-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h4M10 11l5 3-5 3z" /></svg></span>
          <div class="video-identity-copy">
            <h2>{{ selectedVideo.originalFilename }}</h2>
            <div class="video-metadata">
              <span v-if="selectedVideo.durationSeconds"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/></svg>{{ formatDuration(selectedVideo.durationSeconds) }}</span>
              <span><svg viewBox="0 0 24 24"><rect x="5" y="5" width="14" height="14" rx="2"/><path d="M8 3v4m8-4v4M8 11h8"/></svg>{{ formatDate(selectedVideo.createdTime) }}</span>
              <span><svg viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6zM14 3v5h4"/></svg>{{ formatFileSize(selectedVideo.fileSize) }}</span>
            </div>
            <div class="task-status" :class="status.tone"><span></span>任务状态：{{ status.label }}</div>
          </div>
        </div>
        <div class="video-actions">
          <el-upload :show-file-list="false" :http-request="(options) => $emit('upload', options)" accept="video/*">
            <el-button class="secondary-action" :loading="uploading"><svg viewBox="0 0 24 24"><path d="M4 6h6l2 2h8v10H4z"/></svg>选择视频</el-button>
          </el-upload>
          <el-button class="accent-action" :loading="taskLoading" @click="$emit('analyze')"><svg viewBox="0 0 24 24"><path d="m12 3 1.2 4.1L17 9l-3.8 1.9L12 15l-1.2-4.1L7 9l3.8-1.9L12 3Zm6 11 .7 2.3L21 17.5l-2.3 1.2L18 21l-.7-2.3-2.3-1.2 2.3-1.2L18 14Z"/></svg>AI 视频解析</el-button>
          <el-button class="secondary-action" @click="$emit('play')"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="m10 8 6 4-6 4z"/></svg>播放视频</el-button>
          <el-button class="secondary-action" @click="$emit('show-transcript')"><svg viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6zM14 3v5h4M9 12h6m-6 4h6"/></svg>转录文本</el-button>
        </div>
      </header>

      <article class="analysis-result">
        <div class="panel-heading">
          <h2>解析结果</h2>
          <button class="icon-button" :class="{ spinning: resultRefreshing }" type="button" title="刷新解析结果" :disabled="!task?.id || resultRefreshing" @click="$emit('refresh-result')">
            <svg viewBox="0 0 24 24"><path d="M20 6v5h-5M4 18v-5h5M18.2 9A7 7 0 0 0 6.4 6.4L4 9m16 6-2.4 2.6A7 7 0 0 1 5.8 15"/></svg>
          </button>
        </div>
        <nav class="analysis-tabs" aria-label="解析结果视图">
          <button :class="{ active: resultView === 'summary' }" @click="$emit('update:result-view', 'summary')">简洁摘要</button>
          <button :class="{ active: resultView === 'transcript' }" @click="$emit('update:result-view', 'transcript')">转录文本</button>
        </nav>
        <div class="analysis-scroll">
          <div v-if="resultView === 'summary' && summary.length" class="summary-content">
            <template v-for="block in summary" :key="block.title">
              <section v-if="block.type === 'intro'" class="summary-intro">
                <h3>{{ block.title }}</h3><p v-for="paragraph in block.paragraphs" :key="paragraph">{{ paragraph }}</p>
              </section>
              <section v-else class="summary-points">
                <h3>{{ block.title }}</h3>
                <div v-for="(item, index) in block.items" :key="`${item.title}-${index}`" class="summary-point">
                  <span>{{ index + 1 }}</span><div><h4>{{ item.title }}</h4><p v-for="paragraph in item.paragraphs" :key="paragraph">{{ paragraph }}</p></div>
                </div>
              </section>
            </template>
          </div>
          <div v-else-if="resultView === 'summary'" class="content-empty">
            <strong>{{ summaryEmptyTitle }}</strong>
            <p>{{ summaryPlaceholder }}</p>
            <button v-if="summaryMissing" class="empty-action" type="button" :disabled="taskLoading" @click="$emit('analyze')">
              {{ taskLoading ? '正在提交…' : '重新执行解析' }}
            </button>
          </div>
          <div v-else-if="taskResult?.transcriptionText" class="transcript-preview">{{ taskResult.transcriptionText }}</div>
          <div v-else class="content-empty"><strong>暂无转录文本</strong><p>{{ transcriptPlaceholder }}</p></div>
        </div>
      </article>
    </template>
    <div v-else class="workspace-empty">
      <span class="large-video-icon"><svg viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6zM14 3v5h4M10 11l5 3-5 3z"/></svg></span>
      <h2>选择一个视频开始工作</h2><p>从视频库中选择文件，或上传一个新视频。</p>
    </div>
  </section>
</template>
