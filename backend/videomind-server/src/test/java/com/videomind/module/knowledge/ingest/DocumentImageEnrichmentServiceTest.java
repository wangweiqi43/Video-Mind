package com.videomind.module.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.knowledge.entity.DocumentAsset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentImageEnrichmentServiceTest {
    @Test
    void rewritesRepeatedReferencesWithOnePersistedDescription() {
        DocumentAsset asset = asset(8L, "images/chart.png", "READY", "蓝色柱状图，标题为季度收入");
        String source = "A ![](images/chart.png) B ![old](./images/chart.png)";

        String enhanced = DocumentImageEnrichmentService.rewrite(source, 3L, List.of(asset));

        assertThat(enhanced.split("/api/knowledge-documents/3/assets/8", -1)).hasSize(3);
        assertThat(enhanced).contains("A ![蓝色柱状图，标题为季度收入]")
                .contains("B ![蓝色柱状图，标题为季度收入]");
    }

    @Test
    void onlyFallsBackToBasenameWhenItIsUniqueAndMarksFailures() {
        DocumentAsset left = asset(8L, "a/chart.png", "READY", "图一");
        DocumentAsset right = asset(9L, "b/chart.png", "DEGRADED", null);

        String ambiguous = DocumentImageEnrichmentService.rewrite("![](chart.png)", 3L, List.of(left, right));
        String degraded = DocumentImageEnrichmentService.rewrite("![](b/chart.png)", 3L, List.of(left, right));

        assertThat(ambiguous).isEqualTo("[该图片识别失败]");
        assertThat(degraded).isEqualTo("![该图片识别失败](/api/knowledge-documents/3/assets/9)");
    }

    @Test
    void embeddingInputKeepsDescriptionButDropsPrivateAssetUrl() {
        String value = DocumentImageEnrichmentService.forEmbedding(
                "![系统架构图](/api/knowledge-documents/3/assets/8)");
        assertThat(value).isEqualTo("图片说明：系统架构图");
    }

    @Test
    void normalizesMultilineVisionTextIntoOneSafeMarkdownAlt() {
        DocumentAsset asset = asset(8L, "images/chart.png", "READY",
                "流程图：\nUpload [DOCX]\t→  Qwen-VL\r\n发布 READY");

        String enhanced = DocumentImageEnrichmentService.rewrite("![](images/chart.png)", 3L, List.of(asset));

        assertThat(enhanced).isEqualTo(
                "![流程图： Upload （DOCX） → Qwen-VL 发布 READY](/api/knowledge-documents/3/assets/8)");
    }

    private static DocumentAsset asset(Long id, String path, String status, String description) {
        DocumentAsset value = new DocumentAsset();
        value.setId(id);
        value.setSourcePath(path);
        value.setVisionStatus(status);
        value.setDescription(description);
        return value;
    }
}
