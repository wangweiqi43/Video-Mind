export const DOCUMENT_EXTENSIONS = new Set(['pdf', 'docx', 'txt', 'md', 'markdown'])
export const MAX_DOCUMENT_BYTES = 50 * 1024 * 1024

export function validateKnowledgeDocument(file) {
  if (!file || file.size === 0) throw new Error('请选择非空文档')
  if (file.size > MAX_DOCUMENT_BYTES) throw new Error('单个文档不能超过 50 MB')
  const extension = String(file.name || '').split('.').pop().toLowerCase()
  if (!DOCUMENT_EXTENSIONS.has(extension)) throw new Error('仅支持 PDF、DOCX、TXT、MD 和 Markdown 文件')
}

export async function createKnowledgeWithDocument(client, { name, file, idempotencyKey, onUploadProgress }) {
  validateKnowledgeDocument(file)
  const created = await client.createKnowledgeBase(name.trim())
  const upload = await client.uploadKnowledgeDocument(created.id, file, idempotencyKey, onUploadProgress)
  return { created, upload }
}
