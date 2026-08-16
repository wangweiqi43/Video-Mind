package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.QuerySet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class QueryRewriteGuard {
    private static final int MAX_QUERY_CHARS = 500;
    private static final Pattern QUOTED = Pattern.compile("[\\\"'“”‘’]([^\\\"'“”‘’]{1,80})[\\\"'“”‘’]");
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\p{L}\\p{N}_])[A-Za-z][A-Za-z0-9_./+#:-]{1,79}");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])(?:v)?\\d+(?:[.:-]\\d+)*(?:%|ms|s|秒|分钟|小时|KB|MB|GB|TB|Hz|kHz|MHz|GHz)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPPERCASE_TERM = Pattern.compile("(?<![A-Za-z0-9])[A-Z][A-Z0-9_-]{1,31}(?![A-Za-z0-9])");

    public QuerySet validate(String original, List<String> rewrites, List<String> modelProtectedTerms) {
        String canonical = canonicalOriginal(original);
        LinkedHashSet<String> protectedTerms = new LinkedHashSet<>(extract(canonical));
        if (modelProtectedTerms != null) {
            modelProtectedTerms.stream().filter(StringUtils::hasText).map(String::strip)
                    .filter(value -> value.length() <= 80 && canonical.toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT)))
                    .forEach(protectedTerms::add);
        }
        List<String> accepted = new ArrayList<>();
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(normalize(canonical));
        if (rewrites != null) {
            for (String candidate : rewrites) {
                if (!StringUtils.hasText(candidate)) continue;
                String value = candidate.strip();
                if (value.length() > MAX_QUERY_CHARS || !preserves(value, protectedTerms)) continue;
                if (normalized.add(normalize(value))) accepted.add(value);
                if (accepted.size() == 2) break;
            }
        }
        return new QuerySet(canonical, accepted);
    }

    public List<String> extract(String original) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collect(values, QUOTED.matcher(original), 1);
        collect(values, IDENTIFIER.matcher(original), 0);
        collect(values, NUMBER.matcher(original), 0);
        collect(values, UPPERCASE_TERM.matcher(original), 0);
        return List.copyOf(values);
    }

    private boolean preserves(String rewrite, Set<String> protectedTerms) {
        String lowered = rewrite.toLowerCase(Locale.ROOT);
        return protectedTerms.stream().allMatch(term -> lowered.contains(term.toLowerCase(Locale.ROOT)));
    }

    private void collect(Set<String> values, Matcher matcher, int group) {
        while (matcher.find()) values.add(matcher.group(group));
    }

    private String canonicalOriginal(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("WORKFLOW_ORIGINAL_QUERY_EMPTY");
        String canonical = value.strip();
        if (canonical.length() > MAX_QUERY_CHARS) return canonical.substring(0, MAX_QUERY_CHARS);
        return canonical;
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
