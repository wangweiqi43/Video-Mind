<script setup>
import { computed } from 'vue'

const props = defineProps({
  selectedVideo: { type: Object, default: null },
  uploading: { type: Boolean, default: false },
  uploadProgress: { type: Number, default: 0 },
  canAnalyze: { type: Boolean, default: false },
  taskLoading: { type: Boolean, default: false },
  task: { type: Object, default: null },
  vectorStatus: { type: Object, default: null },
  autoVectorize: { type: Boolean, default: true },
  applicationMode: { type: String, default: 'NORMAL' }
})

const emit = defineEmits([
  'upload',
  'analyze',
  'play',
  'show-transcript',
  'toggle-auto-vectorize',
  'vectorize'
])

const selectedVideoTitle = computed(() => props.selectedVideo?.originalFilename || '尚未选择视频')
const autoVectorizeLabel = computed(() => props.autoVectorize ? '解析后入库' : '仅解析')
const advanced = computed(() => props.applicationMode.toUpperCase() === 'ADVANCED')
</script>

<template>
  <section class="control-panel panel" data-testid="video-control-panel">
    <div class="control-block">
      <span class="control-label">当前视频</span>
      <strong>{{ selectedVideoTitle }}</strong>
      <small v-if="selectedVideo">ID {{ selectedVideo.id }} · {{ (selectedVideo.fileSize / 1024).toFixed(1) }} KB</small>
      <small v-else>先选择或上传一个本地视频</small>
    </div>

    <div class="control-actions">
      <el-upload class="compact-upload" :show-file-list="false" :http-request="(options) => emit('upload', options)" accept="video/*">
        <el-button class="gold-button" round :loading="uploading">选择视频</el-button>
      </el-upload>
      <el-button
        class="gold-button"
        round
        :disabled="!canAnalyze"
        :loading="taskLoading"
        @click="emit('analyze')"
      >
        {{ advanced ? '生成高级摘要总结' : 'AI 摘要总结' }}
      </el-button>
      <el-button
        class="ghost-button"
        round
        :disabled="!selectedVideo?.id"
        @click="emit('play')"
      >
        播放视频
      </el-button>
      <el-button
        v-if="advanced"
        class="ghost-button"
        round
        :disabled="!selectedVideo?.id"
        @click="emit('show-transcript')"
      >
        转录文本
      </el-button>
      <div v-if="!advanced" class="knowledge-tools">
        <div class="control-knowledge">
          <span class="control-label">知识库</span>
          <div class="knowledge-inline">
            <span><strong>{{ vectorStatus?.chunkCount ?? 0 }}</strong> Chunks</span>
            <i
              class="knowledge-dot"
              :class="{ vectorized: vectorStatus?.vectorized }"
              :title="vectorStatus?.vectorized ? '已入库' : '未入库'"
            />
          </div>
        </div>
        <el-button class="mode-button" round @click="emit('toggle-auto-vectorize')">
          {{ autoVectorizeLabel }}
        </el-button>
        <el-button
          class="ghost-button"
          round
          :disabled="task?.taskStatus !== 'SUCCESS'"
          @click="emit('vectorize')"
        >
          加入知识库
        </el-button>
      </div>
    </div>
    <el-progress v-if="uploading" class="upload-progress" :percentage="uploadProgress" :stroke-width="6" />
  </section>
</template>
