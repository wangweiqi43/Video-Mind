import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

const POLL_INTERVAL_MS = 2000
const POLL_TIMEOUT_MS = 15 * 60 * 1000

export function useAnalysisWorkspace({ client, selectedVideo, afterOutcome }) {
  const task = ref(null)
  const taskResult = ref(null)
  const taskLoading = ref(false)
  const resultLoading = ref(false)
  const resultRefreshing = ref(false)
  const resultView = ref('summary')
  const activePollTaskId = ref(null)
  const transcriptDialog = reactive({ visible: false, loading: false, data: null, error: '' })
  let latestRequestId = 0

  const transcriptDialogStatus = computed(() => {
    if (transcriptDialog.loading) return '正在读取最新转录文本…'
    if (transcriptDialog.error) return transcriptDialog.error
    if (transcriptDialog.data?.status === 'READY' && transcriptDialog.data?.transcriptionText) return ''
    const status = String(task.value?.taskStatus || task.value?.status || '').toUpperCase()
    if (['PENDING', 'PROCESSING', 'RETRYING', 'RETRY_WAIT'].includes(status)) return '视频转录处理中，完成后重新打开即可查看。'
    return '尚未生成转录文本，请先启动本地视频解析。'
  })

  function reset() {
    latestRequestId += 1
    activePollTaskId.value = null
    task.value = null
    taskResult.value = null
    resultView.value = 'summary'
    transcriptDialog.visible = false
  }

  async function loadLatest(videoId) {
    const requestId = ++latestRequestId
    resultLoading.value = true
    try {
      const latest = await client.getLatestSuccessfulTask(videoId)
      if (requestId !== latestRequestId || selectedVideo.value?.id !== videoId) return
      if (!latest) return
      task.value = latest
      await loadResult(latest.id, videoId)
    } catch (error) {
      if (requestId !== latestRequestId || selectedVideo.value?.id !== videoId) return
      task.value = null
      taskResult.value = null
      ElMessage.warning(error.message || '暂未找到历史解析结果')
    } finally {
      if (requestId === latestRequestId) resultLoading.value = false
    }
  }

  async function loadResult(taskId = task.value?.id, expectedVideoId = selectedVideo.value?.id) {
    if (!taskId) return
    resultRefreshing.value = true
    try {
      const result = await client.getTaskResult(taskId)
      if (!expectedVideoId || selectedVideo.value?.id === expectedVideoId) taskResult.value = result
    } finally {
      resultRefreshing.value = false
    }
  }

  async function refreshCurrentResult() {
    try {
      await loadResult()
    } catch (error) {
      ElMessage.error(error.message || '解析结果刷新失败')
    }
  }

  async function createTask() {
    if (!selectedVideo.value?.id || taskLoading.value) return
    taskLoading.value = true
    resultLoading.value = true
    try {
      const created = await client.analyze(selectedVideo.value.id)
      task.value = { id: created.taskId, taskStatus: created.status, reused: created.reused }
      if (created.status === 'SUCCESS') {
        await refreshOutcome(created.taskId)
        return
      }
      taskResult.value = null
      if (!created.reused) ElMessage.success('解析任务已提交，完成后会自动刷新结果')
      await poll(created.taskId)
    } catch (error) {
      ElMessage.error(error.message || '解析任务提交失败')
    } finally {
      taskLoading.value = false
      resultLoading.value = false
    }
  }

  async function poll(taskId) {
    activePollTaskId.value = taskId
    const startedAt = Date.now()
    while (activePollTaskId.value === taskId) {
      const current = await client.getTask(taskId)
      if (activePollTaskId.value !== taskId) return
      task.value = current
      if (current.taskStatus === 'SUCCESS') {
        activePollTaskId.value = null
        await refreshOutcome(taskId)
        return
      }
      if (['FAILED', 'DEAD'].includes(current.taskStatus)) {
        activePollTaskId.value = null
        ElMessage.error(current.errorMessage || '解析失败')
        return
      }
      if (current.taskStatus === 'CANCELLED') {
        activePollTaskId.value = null
        ElMessage.info('视频解析任务已取消')
        return
      }
      if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
        activePollTaskId.value = null
        ElMessage.warning('解析耗时较长，请稍后回到该视频查看结果')
        return
      }
      await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
    }
  }

  async function refreshOutcome(taskId) {
    await Promise.all([loadResult(taskId), afterOutcome?.()])
  }

  function play() {
    if (!selectedVideo.value?.id) return ElMessage.warning('请先选择一个视频')
    window.open(`/api/videos/${selectedVideo.value.id}/stream`, '_blank', 'noopener')
  }

  async function openTranscript() {
    const videoId = selectedVideo.value?.id
    if (!videoId) return
    transcriptDialog.visible = true
    transcriptDialog.loading = true
    transcriptDialog.data = null
    transcriptDialog.error = ''
    try {
      const data = await client.getVideoTranscription(videoId)
      if (selectedVideo.value?.id === videoId) transcriptDialog.data = data
    } catch (error) {
      if (selectedVideo.value?.id === videoId) transcriptDialog.error = error.message || '转录文本读取失败'
    } finally {
      if (selectedVideo.value?.id === videoId) transcriptDialog.loading = false
    }
  }

  onBeforeUnmount(() => { activePollTaskId.value = null })

  return {
    task, taskResult, taskLoading, resultLoading, resultRefreshing, resultView,
    transcriptDialog, transcriptDialogStatus, reset, loadLatest, loadResult,
    refreshCurrentResult, createTask, play, openTranscript
  }
}
