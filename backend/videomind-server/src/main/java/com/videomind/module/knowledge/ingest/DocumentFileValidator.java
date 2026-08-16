package com.videomind.module.knowledge.ingest;

import com.videomind.common.exception.BizException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public String validateAndContentType(String filename, Path path) {
        String extension = extension(filename);
        if (!SUPPORTED.contains(extension)) {
            throw new BizException(400, "仅支持 PDF、DOCX、TXT 和 Markdown 文件");
        }
        boolean valid = switch (extension) {
            case "pdf" -> isPdf(path);
            case "docx" -> isDocx(path);
            default -> isUtf8(path);
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

    public String validateAndContentType(String filename, byte[] bytes) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("document-validation-", ".tmp");
            Files.write(temporary, bytes);
            return validateAndContentType(filename, temporary);
        } catch (BizException known) {
            throw known;
        } catch (Exception failure) {
            throw new BizException(400, "无法校验文件内容");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
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

    private static boolean isPdf(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(8);
            int offset = header.length >= 3 && (header[0] & 255) == 0xEF
                    && (header[1] & 255) == 0xBB && (header[2] & 255) == 0xBF ? 3 : 0;
            return header.length >= offset + 5 && header[offset] == '%' && header[offset + 1] == 'P'
                    && header[offset + 2] == 'D' && header[offset + 3] == 'F' && header[offset + 4] == '-';
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isDocx(Path path) {
        boolean contentTypes = false;
        boolean document = false;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
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

    private static boolean isUtf8(Path path) {
        try {
            CharBuffer buffer = CharBuffer.allocate(8192);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = new byte[8192];
                java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocate(16384);
                int read;
                while ((read = input.read(bytes)) >= 0) {
                    byteBuffer.put(bytes, 0, read).flip();
                    decoder.decode(byteBuffer, buffer.clear(), false);
                    byteBuffer.compact();
                }
                byteBuffer.flip();
                decoder.decode(byteBuffer, buffer.clear(), true);
                decoder.flush(buffer.clear());
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }
}
