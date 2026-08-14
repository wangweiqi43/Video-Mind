export function selectableKnowledgeBases(knowledgeBases, videoId) {
  return (Array.isArray(knowledgeBases) ? knowledgeBases : [])
    .filter((item) => item?.type === 'USER')
    .map((item) => ({ ...item, selectable: item.status === 'READY' }))
}

export function selectedDocumentScope(session, knowledgeBases) {
  const userIds = new Set(selectableKnowledgeBases(knowledgeBases).map((item) => Number(item.id)))
  return (Array.isArray(session?.knowledgeBaseIds) ? session.knowledgeBaseIds : [])
    .map(Number)
    .filter((id) => userIds.has(id))
}
