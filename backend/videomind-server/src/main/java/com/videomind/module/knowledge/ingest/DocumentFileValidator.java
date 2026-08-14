package com.videomind.module.knowledge.ingest;

import com.videomind.common.exception.BizException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
public class DocumentFileValidator {
    private static final Set<String> SUPPORTED = Set.of("pdf", "docx", "txt", "md", "markdown");

    public String validateAndContentType(String filename, byte[] bytes) {
        String extension = extension(filename);
        if (!SUPPORTED.contains(extension)) {
            throw new BizException(400, "仅支持 PDF、DOCX、TXT 和 Markdown 文件");
        }
        boolean valid = switch (extension) {
            case "pdf" -> isPdf(bytes);
            case "docx" -> isDocx(bytes);
            default -> isUtf8(bytes);
        };
        if (!valid) {
            throw new BizException(400, "文件内容与扩展名不一致");
        }
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "text/plain; charset=utf-8";
        };
    }

    public boolean mineruRequired(String filename) {
        String extension = extension(filename);
        return "pdf".equals(extension) || "docx".equals(extension);
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isPdf(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 255) == 0xEF
                && (bytes[1] & 255) == 0xBB && (bytes[2] & 255) == 0xBF ? 3 : 0;
        return bytes.length >= offset + 5 && bytes[offset] == '%' && bytes[offset + 1] == 'P'
                && bytes[offset + 2] == 'D' && bytes[offset + 3] == 'F' && bytes[offset + 4] == '-';
    }

    private static boolean isDocx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            return false;
        }
        boolean contentTypes = false;
        boolean document = false;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && count++ < 10_000) {
                String name = entry.getName().replace('\\', '/');
                contentTypes |= "[Content_Types].xml".equals(name);
                document |= "word/document.xml".equals(name);
                if (contentTypes && document) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException invalid) {
            return false;
        }
    }
}
