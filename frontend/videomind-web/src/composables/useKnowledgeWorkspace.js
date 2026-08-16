import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { selectableKnowledgeBases, selectedDocumentScope } from '../knowledgeScope'
import { createKnowledgeWithDocument } from '../knowledgeCreation.js'

export function useKnowledgeWorkspace(client) {
  const knowledgeBases = ref([])
  const selectedIds = ref([])
  const loading = ref(false)
  const creating = ref(false)
  const creationStatus = ref('')
  const uploadProgress = ref(0)
  const documentKnowledgeBases = computed(() => selectableKnowledgeBases(knowledgeBases.value))

  async function load() {
    loading.value = true
    try {
      knowledgeBases.value = await client.listKnowledgeBases()
      const readyIds = new Set(documentKnowledgeBases.value.filter((item) => item.selectable).map((item) => Number(item.id)))
      selectedIds.value = selectedIds.value.map(Number).filter((id) => readyIds.has(id))
    } catch (error) {
      ElMessage.warning(error.message || '知识库列表加载失败')
    } finally {
      loading.value = false
    }
  }

  async function create({ name, file }) {
    if (!name?.trim() || !file || creating.value) return false
    creating.value = true
    creationStatus.value = '正在上传'
    uploadProgress.value = 0
    try {
      const idempotencyKey = crypto.randomUUID()
      const { created, upload } = await createKnowledgeWithDocument(client, {
        name, file, idempotencyKey,
        onUploadProgress: ({ loaded, total }) => {
          if (total) uploadProgress.value = Math.min(100, Math.round(loaded * 100 / total))
        }
      })
      uploadProgress.value = 100
      await waitForTask(upload.taskId)
      await load()
      ElMessage.success(`知识库“${created.name}”解析完成`)
      return true
    } catch (error) {
      ElMessage.error(error.message || '知识库创建或文档上传失败')
      return false
    } finally {
      creating.value = false
      creationStatus.value = ''
      uploadProgress.value = 0
    }
  }

  async function waitForTask(taskId) {
    if (!taskId) throw new Error('文档任务未创建')
    for (;;) {
      const task = await client.getProcessingTask(taskId)
      creationStatus.value = taskLabel(task)
      if (task.state === 'SUCCESS') return task
      if (['FAILED', 'DEAD', 'CANCELLED'].includes(task.state)) {
        throw new Error(task.errorMessage || creationStatus.value)
      }
      await new Promise((resolve) => setTimeout(resolve, 1500))
    }
  }

  function taskLabel(task) {
    if (task.state === 'RETRY_WAIT') return '处理失败，等待自动重试'
    if (task.state === 'SUCCESS' && task.stage === 'PUBLISHED') return '解析完成'
    if (['FAILED', 'DEAD'].includes(task.state)) return '解析失败'
    if (task.state === 'CANCELLED') return '任务已取消'
    if (task.state === 'PENDING' || task.stage === 'QUEUED') return '等待处理'
    return ({
      READ_PARSE: '正在解析文档',
      ENRICH_IMAGES: '正在理解文档图片',
      CHUNK_EMBED: '正在建立知识索引',
      PUBLISH: '正在发布知识库'
    })[task.stage] || '正在处理文档'
  }

  function restoreSessionScope(session) {
    selectedIds.value = selectedDocumentScope(session, knowledgeBases.value)
  }

  return { knowledgeBases, selectedIds, loading, creating, creationStatus, uploadProgress,
    documentKnowledgeBases, load, create, restoreSessionScope }
}
