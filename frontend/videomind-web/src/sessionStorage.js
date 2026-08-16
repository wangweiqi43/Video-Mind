export const VIDEO_CHAT_SESSION_KEY = 'videomind:video-chat-sessions'

export function readVideoSessionMap(storage = globalThis.localStorage) {
  try {
    return JSON.parse(storage?.getItem(VIDEO_CHAT_SESSION_KEY) || '{}')
  } catch {
    return {}
  }
}

export function getVideoSessionId(videoId, storage) {
  return readVideoSessionMap(storage)[String(videoId)]
}

export function saveVideoSessionId(videoId, sessionId, storage = globalThis.localStorage) {
  const sessionMap = readVideoSessionMap(storage)
  sessionMap[String(videoId)] = sessionId
  storage?.setItem(VIDEO_CHAT_SESSION_KEY, JSON.stringify(sessionMap))
}

export function removeVideoSessionId(videoId, storage = globalThis.localStorage) {
  const sessionMap = readVideoSessionMap(storage)
  delete sessionMap[String(videoId)]
  storage?.setItem(VIDEO_CHAT_SESSION_KEY, JSON.stringify(sessionMap))
}
