<script setup>
import { formatDate, formatDuration, formatFileSize } from '../presentation'

defineProps({
  videos: { type: Array, default: () => [] },
  selectedVideo: { type: Object, default: null },
  refreshing: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  uploadProgress: { type: Number, default: 0 }
})
defineEmits(['select', 'refresh', 'upload', 'delete'])
</script>

<template>
  <aside class="video-library" aria-label="视频库">
    <div class="library-heading">
      <div><h1>视频库</h1><span>{{ videos.length }} 个视频</span></div>
      <button class="icon-button" :class="{ spinning: refreshing }" type="button" title="刷新视频库" :disabled="refreshing" @click="$emit('refresh')">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 6v5h-5M4 18v-5h5M18.2 9A7 7 0 0 0 6.4 6.4L4 9m16 6-2.4 2.6A7 7 0 0 1 5.8 15" /></svg>
      </button>
    </div>

    <el-upload class="library-upload" :show-file-list="false" :http-request="(options) => $emit('upload', options)" accept="video/*">
      <el-button class="primary-action" :loading="uploading">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4" /></svg>
        上传视频
      </el-button>
    </el-upload>
    <el-progress v-if="uploading" class="library-progress" :percentage="uploadProgress" :stroke-width="4" :show-text="false" />

    <div v-if="videos.length" class="video-list" role="listbox" aria-label="视频文件">
      <div
        v-for="video in videos"
        :key="video.id"
        class="video-item"
        :class="{ active: selectedVideo?.id === video.id }"
        role="option"
        tabindex="0"
        :aria-selected="selectedVideo?.id === video.id"
        @click="$emit('select', video)"
        @keydown.enter.prevent="$emit('select', video)"
        @keydown.space.prevent="$emit('select', video)"
      >
        <span class="video-file-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h4M10 11l5 3-5 3z" /></svg></span>
        <span class="video-item-copy">
          <strong>{{ video.originalFilename }}</strong>
          <small>
            <template v-if="video.durationSeconds">{{ formatDuration(video.durationSeconds) }} · </template>
            {{ formatFileSize(video.fileSize) }} · {{ formatDate(video.createdTime) }}
          </small>
        </span>
        <span class="video-item-actions">
          <button type="button" title="删除视频" @click.stop="$emit('delete', video)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7m4 4v5m4-5v5" /></svg>
          </button>
        </span>
      </div>
    </div>
    <div v-else class="library-empty">
      <span class="video-file-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h4M10 11l5 3-5 3z" /></svg></span>
      <strong>还没有视频</strong>
      <p>上传本地视频后即可开始解析。</p>
    </div>
  </aside>
</template>
