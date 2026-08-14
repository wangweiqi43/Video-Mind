package com.videomind.module.knowledge.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {
    private final SemanticChunker chunker = new SemanticChunker();

    @Test
    void usesRepeatedH2AndIgnoresHeadingsInsideCodeFences() {
        String markdown = "# 文档标题\n导言\n\n## 第一节\n正文一\n\n```md\n## 伪标题\n```\n\n## 第二节\n正文二";
        var chunks = chunker.chunkDocument(markdown);
        assertThat(chunks.parents()).extracting(SemanticChunker.ParentChunk::heading)
                .containsExactly("文档导言", "第一节", "第二节");
    }

    @Test
    void boundsParentsAndChildrenWithoutLosingContentOrder() {
        String body = IntStream.range(0, 8_000)
                .mapToObj(index -> "第" + index + "条中文事实。")
                .reduce((left, right) -> left + "\n\n" + right).orElseThrow();
        var chunks = chunker.chunkDocument("# 超长章节\n" + body);
        assertThat(chunks.parents().size()).isGreaterThan(1);
        int maxParent = chunks.parents().stream()
                .mapToInt(parent -> SemanticChunker.tokens(parent.content())).max().orElseThrow();
        int maxChild = chunks.children().stream()
                .mapToInt(child -> SemanticChunker.tokens(child.content())).max().orElseThrow();
        assertThat(maxParent).isLessThanOrEqualTo(SemanticChunker.MAX_PARENT_TOKENS);
        assertThat(maxChild).isLessThanOrEqualTo(SemanticChunker.MAX_CHILD_TOKENS + 32);
        assertThat(chunks.children()).allMatch(child -> child.content().startsWith("# 超长章节"));
    }

    @Test
    void createsOverlappingChildrenForLongSections() {
        String body = IntStream.range(0, 450)
                .mapToObj(index -> "段落" + index + "，这里包含用于验证重叠窗口的中文内容。")
                .reduce((left, right) -> left + "\n\n" + right).orElseThrow();
        var children = chunker.chunkDocument("# 章节\n" + body).children();
        assertThat(children.size()).isGreaterThan(1);
        String firstTail = children.get(0).content().substring(
                Math.max(0, children.get(0).content().length() - 80));
        assertThat(children.get(1).content()).contains(firstTail.substring(firstTail.length() - 24));
    }
}
