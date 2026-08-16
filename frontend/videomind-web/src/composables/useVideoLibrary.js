import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SparkMD5 from 'spark-md5'
import { removeVideoSessionId } from '../sessionStorage'

const UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024

export function useVideoLibrary(client) {
  const videos = ref([])
  const refreshing = ref(false)
  const uploading = ref(false)
  const uploadProgress = ref(0)

  async function load() {
    refreshing.value = true
    try {
      videos.value = await client.listVideos()
      return videos.value
    } catch (error) {
      videos.value = []
      ElMessage.error(error.message || '视频库加载失败')
      return []
    } finally {
      refreshing.value = false
    }
  }

  async function refresh() {
    await load()
  }

  async function upload(options) {
    uploading.value = true
    uploadProgress.value = 0
    try {
      ElMessage.info('正在计算文件指纹，准备分片上传…')
      const fileMd5 = await calculateMd5(options.file, (ratio) => {
        uploadProgress.value = Math.min(10, Math.round(ratio * 10))
      })
      const uploaded = await uploadByChunks(options.file, fileMd5)
      ElMessage.success(uploaded.duplicate ? '视频已存在，秒传成功' : '视频上传成功')
      await load()
      return videos.value.find((item) => item.id === uploaded.videoId) || null
    } catch (error) {
      ElMessage.error(error.message || '视频上传失败')
      return null
    } finally {
      uploading.value = false
    }
  }

  async function uploadByChunks(file, fileMd5) {
    const totalParts = Math.max(1, Math.ceil(file.size / UPLOAD_CHUNK_SIZE))
    const init = await client.initMultipartUpload({
      filename: file.name,
      fileMd5,
      fileSize: file.size,
      contentType: file.type || 'application/octet-stream',
      totalParts,
      chunkSize: UPLOAD_CHUNK_SIZE
    })
    if (init.video) {
      uploadProgress.value = 100
      return init.video
    }
    if (!init.uploadId) throw new Error('后端未返回 uploadId，无法继续分片上传')
    let uploadedSet = new Set(init.uploadedParts || [])
    if (!uploadedSet.size) {
      const status = await client.multipartStatus(init.uploadId)
      uploadedSet = new Set(status.uploadedParts || [])
    }
    updateProgress(uploadedSet.size, totalParts)
    for (let partNumber = 1; partNumber <= totalParts; partNumber += 1) {
      if (uploadedSet.has(partNumber)) continue
      const start = (partNumber - 1) * UPLOAD_CHUNK_SIZE
      const chunk = file.slice(start, Math.min(start + UPLOAD_CHUNK_SIZE, file.size))
      await client.uploadChunk(init.uploadId, partNumber, chunk, await calculateMd5(chunk))
      uploadedSet.add(partNumber)
      updateProgress(uploadedSet.size, totalParts)
    }
    uploadProgress.value = 98
    const uploaded = await client.completeMultipartUpload(init.uploadId)
    uploadProgress.value = 100
    return uploaded
  }

  function updateProgress(done, total) {
    uploadProgress.value = Math.min(95, 10 + Math.round((done / Math.max(total, 1)) * 85))
  }

  async function remove(video) {
    try {
      await ElMessageBox.confirm(
        `确认删除视频「${video.originalFilename}」吗？相关解析任务、转录、摘要和知识库向量都会一起删除。`,
        '删除视频',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning', customClass: 'videomind-dialog' }
      )
      const deletion = await client.deleteVideo(video.id)
      videos.value = videos.value.filter((item) => item.id !== video.id)
      removeVideoSessionId(video.id)
      ElMessage.success(`删除任务已提交（任务 ${deletion.taskId}）`)
      return true
    } catch (error) {
      if (error === 'cancel' || error?.message === 'cancel') return false
      ElMessage.error(error.message || '删除失败')
      return false
    }
  }

  return { videos, refreshing, uploading, uploadProgress, load, refresh, upload, remove }
}

function calculateMd5(file, onProgress = () => {}) {
  return new Promise((resolve, reject) => {
    const chunks = Math.max(1, Math.ceil(file.size / UPLOAD_CHUNK_SIZE))
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    let currentChunk = 0
    reader.onload = (event) => {
      spark.append(event.target.result)
      currentChunk += 1
      onProgress(currentChunk / chunks)
      if (currentChunk < chunks) loadNext()
      else resolve(spark.end())
    }
    reader.onerror = () => reject(new Error('计算文件 MD5 失败，请重新选择文件'))
    function loadNext() {
      const start = currentChunk * UPLOAD_CHUNK_SIZE
      reader.readAsArrayBuffer(file.slice(start, Math.min(start + UPLOAD_CHUNK_SIZE, file.size)))
    }
    loadNext()
  })
}
