package com.videomind.module.knowledge.mineru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MineruClientZipTest {

    @Test
    void keepsOnlyImagesReferencedByMarkdown() throws Exception {
        byte[] zip = zip(Map.of(
                "result.md", "# 标题\n![](images/keep.png)".getBytes(StandardCharsets.UTF_8),
                "images/keep.png", new byte[] {1, 2, 3},
                "images/drop.png", new byte[] {4, 5, 6}));
        var result = MineruClient.parseZip(zip, "test");
        assertThat(result.content()).contains("标题");
        assertThat(result.assets()).extracting(MineruClient.Asset::path).containsExactly("images/keep.png");
    }

    @Test
    void rejectsZipSlip() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("result.md", "ok".getBytes(StandardCharsets.UTF_8));
        entries.put("../escape.png", new byte[] {1});
        assertThatThrownBy(() -> MineruClient.parseZip(zip(entries), "test"))
                .isInstanceOf(IOException.class).hasMessageContaining("UNSAFE_PATH");
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
