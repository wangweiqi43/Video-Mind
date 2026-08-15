export function decodeChatDelta(data) {
  if (typeof data !== 'string') return ''
  try {
    const payload = JSON.parse(data)
    return typeof payload?.delta === 'string' ? payload.delta : data
  } catch {
    return data
  }
}

export function decodeWorkflowEvent(data) {
  if (typeof data !== 'string') return null
  try {
    const payload = JSON.parse(data)
    const fields = ['phase', 'stepId', 'status', 'message']
    if (!fields.every((field) => typeof payload?.[field] === 'string')) return null
    return Object.fromEntries(fields.map((field) => [field, payload[field]]))
  } catch {
    return null
  }
}
