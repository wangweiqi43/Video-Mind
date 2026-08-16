<script setup>
const visible = defineModel({ type: Boolean, default: false })
defineProps({ dialog: { type: Object, required: true }, status: { type: String, default: '' } })
</script>

<template>
  <el-dialog v-model="visible" class="transcript-dialog" title="转录文本" width="min(760px, 92vw)" destroy-on-close>
    <div v-if="dialog.loading" class="transcript-dialog-state">正在读取最新转录文本…</div>
    <template v-else-if="dialog.data?.status === 'READY' && dialog.data?.transcriptionText">
      <div class="transcript-dialog-meta"><span>版本 {{ dialog.data.transcriptVersion }}</span><span v-if="dialog.data.language">语言 {{ dialog.data.language }}</span></div>
      <pre class="transcript-dialog-text">{{ dialog.data.transcriptionText }}</pre>
    </template>
    <div v-else class="transcript-dialog-state">{{ status }}</div>
  </el-dialog>
</template>
