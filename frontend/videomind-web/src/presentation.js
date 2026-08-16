export function formatFileSize(bytes) {
  const value = Math.max(0, Number(bytes) || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(value < 10 * 1024 ? 1 : 0)} KB`
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(value < 10 * 1024 ** 2 ? 1 : 0)} MB`
  return `${(value / 1024 ** 3).toFixed(1)} GB`
}

export function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds) || 0)
  const hours = Math.floor(value / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  const rest = Math.floor(value % 60)
  return hours > 0
    ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}

export function formatDate(value) {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

const TASK_STATUS = {
  SUCCESS: { label: '解析完成', tone: 'success' },
  PROCESSING: { label: '正在解析', tone: 'running' },
  PENDING: { label: '等待处理', tone: 'pending' },
  RETRYING: { label: '准备重试', tone: 'warning' },
  RETRY_WAIT: { label: '等待重试', tone: 'warning' },
  FAILED: { label: '解析失败', tone: 'danger' },
  DEAD: { label: '任务终止', tone: 'danger' },
  CANCELLED: { label: '已取消', tone: 'muted' }
}

export function taskStatusMeta(task, result = null, resultLoading = false) {
  const status = String(task?.taskStatus || task?.status || '').toUpperCase()
  if (status === 'SUCCESS' && result && !resultLoading && !String(result.summaryText || '').trim()) {
    return { label: '摘要未生成', tone: 'warning' }
  }
  return TASK_STATUS[status] || { label: status || '尚未解析', tone: 'muted' }
}

export function referenceType(reference) {
  return String(reference?.sourceType || (reference?.url ? 'WEB' : 'VIDEO_TIMELINE')).toUpperCase()
}

export function referenceTypeLabel(reference) {
  const type = referenceType(reference)
  if (type === 'DOCUMENT') return '文档知识库'
  if (type === 'TRANSCRIPT_SOURCE') return '视频转录'
  if (type === 'WEB') return '网页来源'
  return '视频时间轴'
}

export function referenceTitle(reference) {
  if (referenceType(reference) === 'WEB') return reference?.title || reference?.domain || '网页来源'
  const timestamp = Number.isFinite(reference?.startSeconds) ? ` · ${formatDuration(reference.startSeconds)}` : ''
  return `${reference?.title || referenceTypeLabel(reference)}${timestamp}`
}

export function parseSummary(text) {
  if (!text) return []
  const sections = []
  let current = null
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    const heading = line.match(/^###\s+(.+)$/)
    if (heading) {
      current = { title: heading[1].trim(), lines: [] }
      sections.push(current)
    } else {
      if (!current) {
        current = { title: '简洁摘要', lines: [] }
        sections.push(current)
      }
      current.lines.push(line)
    }
  }
  return sections.map((section, index) => {
    const title = cleanTitle(section.title)
    if (index === 0 || title.includes('摘要')) {
      return { type: 'intro', title, paragraphs: compact(section.lines) }
    }
    return { type: 'points', title: '核心要点', items: parsePoints(title, section.lines) }
  })
}

function parsePoints(sectionTitle, lines) {
  const headingPoint = sectionTitle.match(/^要点\s*\d+\s*[：:]\s*(.+)$/)
  if (headingPoint) return [{ title: cleanPointTitle(headingPoint[1]), paragraphs: compact(lines) }]
  if (!sectionTitle.includes('摘要') && !['要点', '可行动结论'].includes(sectionTitle)) {
    return [{ title: cleanPointTitle(sectionTitle), paragraphs: compact(lines) }]
  }
  const items = []
  let current = null
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line) continue
    const titledBullet = line.match(/^-\s+\*\*(.+?)\*\*[：:]\s*(.*)$/)
    if (titledBullet) {
      current = { title: cleanPointTitle(titledBullet[1]), paragraphs: titledBullet[2] ? [titledBullet[2].trim()] : [] }
      items.push(current)
      continue
    }
    const plainBullet = line.match(/^-\s+(.+)$/)
    if (!current) {
      current = { title: '要点', paragraphs: [] }
      items.push(current)
    }
    current.paragraphs.push((plainBullet?.[1] || line).replace(/\*\*/g, ''))
  }
  return items
}

function compact(lines) {
  return lines.map((line) => line.trim()).filter(Boolean).map((line) => line.replace(/\*\*/g, ''))
}

function cleanTitle(title) {
  return title.replace(/\*\*/g, '').trim()
}

function cleanPointTitle(title) {
  return cleanTitle(title).replace(/^要点\s*\d*\s*[：:]\s*/, '').trim()
}
