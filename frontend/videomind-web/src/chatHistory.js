export function normalizeHistoryMessages(messages) {
  if (!Array.isArray(messages)) return []
  return messages.map((message) => ({
    ...message,
    role: String(message?.role || 'ASSISTANT').toUpperCase(),
    content: String(message?.content || ''),
    references: normalizeReferences(message),
    streaming: false,
    failed: false
  }))
}

export function normalizeReferences(message) {
  if (Array.isArray(message?.references)) return message.references
  if (!message?.referencesJson) return []
  try {
    const parsed = typeof message.referencesJson === 'string'
      ? JSON.parse(message.referencesJson)
      : message.referencesJson
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function sessionTitle(session) {
  return session?.title?.trim() || '新会话'
}

export function sessionPreview(session) {
  return session?.lastMessagePreview?.trim() || '点击查看并继续对话'
}
