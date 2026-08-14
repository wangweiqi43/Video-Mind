export function decodeChatDelta(data) {
  if (typeof data !== 'string') return ''
  try {
    const payload = JSON.parse(data)
    return typeof payload?.delta === 'string' ? payload.delta : data
  } catch {
    return data
  }
}
