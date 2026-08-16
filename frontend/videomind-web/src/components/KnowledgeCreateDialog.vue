<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  statusText: { type: String, default: '' },
  uploadProgress: { type: Number, default: 0 }
})
const emit = defineEmits(['update:modelValue', 'submit'])
const name = ref('')
const file = ref(null)
const fileInput = ref(null)
const canSubmit = computed(() => Boolean(name.value.trim()) && Boolean(file.value) && !props.submitting)

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  name.value = ''
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
})

function selectFile(event) {
  file.value = event.target.files?.[0] || null
}

function close() {
  emit('update:modelValue', false)
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', { name: name.value.trim(), file: file.value })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="knowledge-create-dialog"
    title="新建文档知识库"
    width="520px"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
    :show-close="true"
    @close="close"
  >
    <div class="knowledge-create-form">
      <label>
        <span>知识库名称</span>
        <el-input v-model="name" maxlength="255" placeholder="例如：项目需求文档" :disabled="submitting" />
      </label>
      <label>
        <span>选择文档</span>
        <input
          ref="fileInput"
          class="knowledge-file-input"
          type="file"
          accept=".pdf,.docx,.txt,.md,.markdown"
          :disabled="submitting"
          @change="selectFile"
        />
        <span class="knowledge-file-picker" :class="{ selected: file }">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4"/></svg>
          <strong>{{ file ? file.name : '点击选择本机文档' }}</strong>
          <small>{{ file ? '创建后将立即上传并开始解析' : '支持 PDF、DOCX、TXT、MD 与 Markdown，最大 50 MB' }}</small>
        </span>
      </label>
      <div v-if="submitting" class="knowledge-task-status" aria-live="polite">
        <strong>{{ statusText || '正在处理文档' }}</strong>
        <span v-if="statusText === '正在上传'">{{ uploadProgress }}%</span>
      </div>
    </div>
    <template #footer>
      <button class="dialog-secondary" type="button" @click="close">{{ submitting ? '关闭' : '取消' }}</button>
      <button class="dialog-primary" type="button" :disabled="!canSubmit" @click="submit">
        {{ submitting ? (statusText || '正在处理…') : '创建并上传' }}
      </button>
    </template>
  </el-dialog>
</template>
