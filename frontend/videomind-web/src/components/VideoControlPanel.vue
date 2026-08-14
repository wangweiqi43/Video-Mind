<script setup>
import { computed } from 'vue'

const props = defineProps({
  selectedVideo: { type: Object, default: null },
  uploading: { type: Boolean, default: false },
  uploadProgress: { type: Number, default: 0 },
  canAnalyze: { type: Boolean, default: false },
  taskLoading: { type: Boolean, default: false },
  task: { type: Object, default: null }
})

const emit = defineEmits(['upload', 'analyze', 'play', 'show-transcript'])
const selectedVideoTitle = computed(() => props.selectedVideo?.originalFilename || '尚未选择视频')
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
      <el-button class="gold-button" round :disabled="!canAnalyze" :loading="taskLoading" @click="emit('analyze')">
        AI 视频解析
      </el-button>
      <el-button class="ghost-button" round :disabled="!selectedVideo?.id" @click="emit('play')">播放视频</el-button>
      <el-button class="ghost-button" round :disabled="!selectedVideo?.id" @click="emit('show-transcript')">转录文本</el-button>
      <span v-if="task?.taskStatus" class="control-label">任务状态：{{ task.taskStatus }}</span>
    </div>
    <el-progress v-if="uploading" class="upload-progress" :percentage="uploadProgress" :stroke-width="6" />
  </section>
</template>
