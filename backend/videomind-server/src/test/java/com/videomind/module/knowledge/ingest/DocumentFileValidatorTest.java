package com.videomind.module.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DocumentFileValidatorTest {
    private final DocumentFileValidator validator = new DocumentFileValidator();

    @Test
    void validatesMagicBytesInsteadOfTrustingFilename() throws Exception {
        assertThat(validator.validateAndContentType("manual.pdf", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("application/pdf");
        assertThat(validator.validateAndContentType("manual.docx", docx()))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThatThrownBy(() -> validator.validateAndContentType("fake.pdf", "plain".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void requiresMineruOnlyForPdfAndDocx() {
        assertThat(validator.mineruRequired("A.PDF")).isTrue();
        assertThat(validator.mineruRequired("note.md")).isFalse();
        assertThatThrownBy(() -> validator.validateAndContentType("image.png", new byte[] {1}))
                .isInstanceOf(BizException.class);
    }

    private static byte[] docx() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("document".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
