package com.videomind.module.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownHeadingPolicy {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,}).*$");

    private MarkdownHeadingPolicy() {
    }

    static Selection select(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new Selection(0, List.of());
        }
        List<SectionHeading> headings = new ArrayList<>();
        boolean fenced = false;
        char fenceChar = 0;
        int fenceLength = 0;
        int offset = 0;
        for (String line : markdown.split("(?<=\\n)", -1)) {
            String value = stripLineEnding(line);
            Matcher fence = FENCE.matcher(value);
            if (fence.matches()) {
                String token = fence.group(1);
                if (!fenced) {
                    fenced = true;
                    fenceChar = token.charAt(0);
                    fenceLength = token.length();
                } else if (token.charAt(0) == fenceChar && token.length() >= fenceLength) {
                    fenced = false;
                }
            } else if (!fenced) {
                Matcher heading = HEADING.matcher(value);
                if (heading.matches()) {
                    headings.add(new SectionHeading(
                            heading.group(1).length(), offset, offset + value.length(), heading.group(2).strip()));
                }
            }
            offset += line.length();
        }
        if (headings.isEmpty()) {
            return new Selection(0, List.of());
        }
        int[] counts = new int[7];
        headings.forEach(heading -> counts[heading.level()]++);
        int selected = 0;
        for (int level = 1; level <= 6; level++) {
            if (counts[level] >= 2) {
                selected = level;
                break;
            }
        }
        if (selected == 0) {
            for (int level = 1; level <= 6; level++) {
                if (counts[level] > 0) {
                    selected = level;
                    break;
                }
            }
        }
        int selectedLevel = selected;
        return new Selection(selectedLevel,
                headings.stream().filter(heading -> heading.level() == selectedLevel).toList());
    }

    private static String stripLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    record Selection(int level, List<SectionHeading> headings) {
    }

    record SectionHeading(int level, int startOffset, int endOffset, String text) {
    }
}
