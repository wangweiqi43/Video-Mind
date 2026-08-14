package com.videomind.module.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunker {
    public static final int MAX_CHILD_TOKENS = 1024;
    public static final int CHILD_OVERLAP_TOKENS = 256;
    public static final int MAX_PARENT_TOKENS = 6000;

    public DocumentChunks chunkDocument(String markdown) {
        String source = Objects.toString(markdown, "");
        if (source.isBlank()) {
            return new DocumentChunks(List.of(), List.of());
        }
        List<ParentChunk> parents = boundedParents(source);
        List<Chunk> children = new ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(children(parent, children.size()));
        }
        return new DocumentChunks(List.copyOf(parents), List.copyOf(children));
    }

    private List<ParentChunk> boundedParents(String source) {
        List<RawParent> selected = rawParents(source);
        List<ParentChunk> result = new ArrayList<>();
        for (RawParent raw : selected) {
            List<Piece> pieces = paragraphs(raw.content(), raw.startOffset());
            List<Piece> window = new ArrayList<>();
            int tokens = 0;
            int continuation = 1;
            for (Piece piece : pieces) {
                for (Piece bounded : splitLarge(piece, MAX_PARENT_TOKENS)) {
                    int next = tokens(bounded.text());
                    int separator = window.isEmpty() ? 0 : 1;
                    if (!window.isEmpty() && tokens + separator + next > MAX_PARENT_TOKENS) {
                        result.add(parent(result.size(), raw.heading(), continuation++, window));
                        window = new ArrayList<>();
                        tokens = 0;
                        separator = 0;
                    }
                    window.add(bounded);
                    tokens += separator + next;
                }
            }
            if (!window.isEmpty()) {
                result.add(parent(result.size(), raw.heading(), continuation, window));
            }
        }
        return result;
    }

    private List<RawParent> rawParents(String source) {
        List<MarkdownHeadingPolicy.SectionHeading> headings = MarkdownHeadingPolicy.select(source).headings();
        if (headings.isEmpty()) {
            return List.of(new RawParent("文档正文", source.strip(), 0, source.length()));
        }
        List<RawParent> result = new ArrayList<>();
        int first = headings.get(0).startOffset();
        if (first > 0 && !source.substring(0, first).isBlank()) {
            result.add(new RawParent("文档导言", source.substring(0, first).strip(), 0, first));
        }
        for (int index = 0; index < headings.size(); index++) {
            var heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).startOffset() : source.length();
            String content = source.substring(heading.startOffset(), end).strip();
            if (!content.isBlank()) {
                result.add(new RawParent(heading.text(), content, heading.startOffset(), end));
            }
        }
        return result;
    }

    private ParentChunk parent(int index, String heading, int continuation, List<Piece> pieces) {
        Piece first = pieces.get(0);
        Piece last = pieces.get(pieces.size() - 1);
        String label = continuation == 1 ? heading : heading + "（续 " + continuation + "）";
        return new ParentChunk(index, label,
                String.join("\n\n", pieces.stream().map(Piece::text).toList()), first.start(), last.end());
    }

    private List<Chunk> children(ParentChunk parent, int firstIndex) {
        List<Piece> pieces = paragraphs(parent.content(), parent.startOffset());
        List<Chunk> result = new ArrayList<>();
        List<Piece> window = new ArrayList<>();
        int used = 0;
        int childIndex = 0;
        for (Piece piece : pieces) {
            for (Piece bounded : splitLarge(piece, MAX_CHILD_TOKENS)) {
                int next = tokens(bounded.text());
                int separator = window.isEmpty() ? 0 : 1;
                if (!window.isEmpty() && used + separator + next > MAX_CHILD_TOKENS) {
                    result.add(chunk(parent, window, firstIndex + result.size(), childIndex++));
                    window = overlap(window);
                    used = windowTokens(window);
                    while (!window.isEmpty() && used + 1 + next > MAX_CHILD_TOKENS) {
                        window.remove(0);
                        used = windowTokens(window);
                    }
                    separator = window.isEmpty() ? 0 : 1;
                }
                window.add(bounded);
                used += separator + next;
            }
        }
        if (!window.isEmpty()) {
            result.add(chunk(parent, window, firstIndex + result.size(), childIndex));
        }
        return result;
    }

    private Chunk chunk(ParentChunk parent, List<Piece> pieces, int index, int childIndex) {
        Piece first = pieces.get(0);
        Piece last = pieces.get(pieces.size() - 1);
        String body = String.join("\n\n", pieces.stream().map(Piece::text).toList());
        return new Chunk(index, prefix(parent.heading(), body), first.start(), last.end(),
                parent.index(), childIndex, parent.heading());
    }

    private List<Piece> overlap(List<Piece> pieces) {
        List<Piece> result = new ArrayList<>();
        int used = 0;
        for (int index = pieces.size() - 1; index >= 0 && used < CHILD_OVERLAP_TOKENS; index--) {
            result.add(0, pieces.get(index));
            used += tokens(pieces.get(index).text());
        }
        return result;
    }

    private int windowTokens(List<Piece> pieces) {
        return pieces.stream().mapToInt(value -> tokens(value.text())).sum() + Math.max(0, pieces.size() - 1);
    }

    private List<Piece> paragraphs(String value, int baseOffset) {
        List<Piece> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?s).*?(?:\\R\\s*\\R|\\z)").matcher(value);
        while (matcher.find()) {
            String text = matcher.group().strip();
            if (!text.isBlank()) {
                result.add(new Piece(text, baseOffset + matcher.start(), baseOffset + matcher.end()));
            }
        }
        return result.isEmpty() ? List.of(new Piece(value, baseOffset, baseOffset + value.length())) : result;
    }

    private List<Piece> splitLarge(Piece piece, int budget) {
        if (tokens(piece.text()) <= budget) {
            return List.of(piece);
        }
        List<Piece> result = new ArrayList<>();
        int start = 0;
        while (start < piece.text().length()) {
            int end = advanceTokens(piece.text(), start, budget);
            if (end <= start) {
                end = Math.min(piece.text().length(), start + 1);
            }
            result.add(new Piece(piece.text().substring(start, end), piece.start() + start, piece.start() + end));
            start = end;
        }
        return result;
    }

    private static String prefix(String heading, String body) {
        String prefix = "# " + heading;
        return body.stripLeading().startsWith(prefix) ? body : prefix + "\n\n" + body;
    }

    public static int tokens(String value) {
        int units = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            units += Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN || codePoint > 0x2FFF ? 4 : 1;
        }
        return Math.max(1, (units + 3) / 4);
    }

    private static int advanceTokens(String value, int start, int budget) {
        int used = 0;
        int index = start;
        int limit = budget * 4;
        while (index < value.length() && used < limit) {
            int codePoint = value.codePointAt(index);
            int next = used + (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                    || codePoint > 0x2FFF ? 4 : 1);
            if (next > limit && index > start) {
                break;
            }
            used = next;
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private record RawParent(String heading, String content, int startOffset, int endOffset) {
    }

    private record Piece(String text, int start, int end) {
    }

    public record ParentChunk(int index, String heading, String content, int startOffset, int endOffset) {
    }

    public record Chunk(int index, String content, int startOffset, int endOffset,
                        int parentIndex, int childIndex, String heading) {
    }

    public record DocumentChunks(List<ParentChunk> parents, List<Chunk> children) {
    }
}
