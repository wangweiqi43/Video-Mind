<script setup>
import { onMounted, ref } from 'vue'
import AppTopBar from './components/AppTopBar.vue'
import AnalysisWorkspace from './components/AnalysisWorkspace.vue'
import AssistantWorkspace from './components/AssistantWorkspace.vue'
import KnowledgeCreateDialog from './components/KnowledgeCreateDialog.vue'
import TranscriptDialog from './components/TranscriptDialog.vue'
import VideoLibrary from './components/VideoLibrary.vue'
import { api } from './api'
import { useAnalysisWorkspace } from './composables/useAnalysisWorkspace'
import { useChatWorkspace } from './composables/useChatWorkspace'
import { useKnowledgeWorkspace } from './composables/useKnowledgeWorkspace'
import { useVideoLibrary } from './composables/useVideoLibrary'

defineProps({ user: { type: Object, required: true } })
defineEmits(['logout'])

const selectedVideo = ref(null)
const knowledgeCreateVisible = ref(false)
const knowledge = useKnowledgeWorkspace(api)
const library = useVideoLibrary(api)
const chat = useChatWorkspace({ client: api, selectedVideo, knowledge })
const analysis = useAnalysisWorkspace({
  client: api,
  selectedVideo,
  afterOutcome: async () => {
    await Promise.all([library.load(), knowledge.load()])
    const refreshed = library.videos.value.find((item) => item.id === selectedVideo.value?.id)
    if (refreshed) selectedVideo.value = refreshed
  }
})

onMounted(async () => {
  await Promise.all([library.load(), knowledge.load()])
  if (library.videos.value.length) await selectVideo(library.videos.value[0])
})

async function selectVideo(video) {
  selectedVideo.value = video
  analysis.reset()
  chat.resetForVideo()
  await Promise.all([analysis.loadLatest(video.id), chat.loadSessions(video.id), knowledge.load()])
  if (selectedVideo.value?.id === video.id) await chat.openRestoredSession(video.id)
}

async function uploadVideo(options) {
  const uploadedVideo = await library.upload(options)
  if (uploadedVideo) await selectVideo(uploadedVideo)
}

async function deleteVideo(video) {
  const deleted = await library.remove(video)
  if (!deleted || selectedVideo.value?.id !== video.id) return
  selectedVideo.value = null
  analysis.reset()
  chat.resetForVideo()
}

async function createKnowledge(payload) {
  const created = await knowledge.create(payload)
  if (created) knowledgeCreateVisible.value = false
}
</script>

<template>
  <main class="app-shell">
    <AppTopBar
      :user="user"
      :knowledge-loading="knowledge.creating.value"
      @create-knowledge="knowledgeCreateVisible = true"
      @logout="$emit('logout')"
    />
    <section class="product-workspace" data-testid="local-layout">
      <VideoLibrary
        :videos="library.videos.value" :selected-video="selectedVideo" :refreshing="library.refreshing.value"
        :uploading="library.uploading.value" :upload-progress="library.uploadProgress.value"
        @select="selectVideo" @refresh="library.refresh" @upload="uploadVideo" @delete="deleteVideo"
      />
      <AnalysisWorkspace
        :selected-video="selectedVideo" :task="analysis.task.value" :task-result="analysis.taskResult.value"
        :task-loading="analysis.taskLoading.value" :result-loading="analysis.resultLoading.value"
        :result-refreshing="analysis.resultRefreshing.value" :result-view="analysis.resultView.value" :uploading="library.uploading.value"
        @upload="uploadVideo" @analyze="analysis.createTask" @play="analysis.play" @show-transcript="analysis.openTranscript"
        @refresh-result="analysis.refreshCurrentResult" @update:result-view="analysis.resultView.value = $event"
      />
      <AssistantWorkspace
        :selected-video="selectedVideo" :sessions="chat.sessions.value" :messages="chat.messages.value" :chat-view="chat.chatView.value"
        :session-list-loading="chat.sessionListLoading.value" :session-list-error="chat.sessionListError.value"
        :session-detail-loading="chat.sessionDetailLoading.value" :active-session-id="chat.activeSessionId.value"
        :question="chat.question.value" :answer-scope="chat.answerScope.value" :loading-chat="chat.loadingChat.value"
        :knowledge-bases="knowledge.documentKnowledgeBases.value" :selected-knowledge-base-ids="knowledge.selectedIds.value"
        :knowledge-loading="knowledge.loading.value"
        :history-scroll-top="chat.historyScrollTop.value"
        @show-history="chat.showHistory" @new-session="chat.prepareNewSession" @return-chat="chat.returnToChat" @back-history="chat.backToHistory"
        @open-session="chat.openSession($event.id, selectedVideo?.id, true, $event.scrollTop)" @reload-sessions="chat.loadSessions()"
        @history-scroll="chat.historyScrollTop.value = $event" @update:question="chat.question.value = $event"
        @update:answer-scope="chat.answerScope.value = $event" @update:selected-knowledge-base-ids="knowledge.selectedIds.value = $event"
        @refresh-knowledge="knowledge.load"
        @send="chat.sendQuestion" @open-reference="chat.openReference"
      />
    </section>
    <KnowledgeCreateDialog v-model="knowledgeCreateVisible" :submitting="knowledge.creating.value"
      :status-text="knowledge.creationStatus.value" :upload-progress="knowledge.uploadProgress.value"
      @submit="createKnowledge" />
    <TranscriptDialog v-model="analysis.transcriptDialog.visible" :dialog="analysis.transcriptDialog" :status="analysis.transcriptDialogStatus.value" />
  </main>
</template>
