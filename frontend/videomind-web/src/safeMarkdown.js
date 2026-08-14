import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: false })
const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, env, self) => self.renderToken(tokens, index, options))

markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, env, self)
}

export function renderSafeMarkdown(content) {
  return markdown.render(normalizeMarkdownForRendering(content))
}

export function normalizeMarkdownForRendering(content) {
  if (typeof content !== 'string') return ''
  let fenceCharacter = null
  return content.split(/(\r?\n)/).map((part) => {
    if (/^\r?\n$/.test(part)) return part
    const fence = part.match(/^\s{0,3}(`{3,}|~{3,})/)
    if (fence) {
      const character = fence[1][0]
      fenceCharacter = fenceCharacter === character ? null : (fenceCharacter || character)
      return part
    }
    if (fenceCharacter) return part
    return part
      .replace(/^(\s{0,3}#{1,6})(?=[^\s#])/, '$1 ')
      .replace(/^(\s{0,3}[-+*])(?=\*\*)/, '$1 ')
  }).join('')
}
