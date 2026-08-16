<script setup>
import { computed } from 'vue'
import { renderSafeMarkdown } from '../safeMarkdown'
const props = defineProps({ content: { type: String, default: '' } })
const html = computed(() => renderSafeMarkdown(props.content))
</script>

<template><div class="markdown-content" v-html="html"></div></template>
