package com.videomind.module.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Identity-aware reciprocal-rank fusion for VideoMind retrieval results.
 *
 * <p>Each input route is sorted by its native relevance score and deduplicated before ranks are
 * assigned. The same logical chunk can therefore contribute at most one vote per route, while
 * occurrences from different routes accumulate on the stable chunk identity.</p>
 */
public final class VideoMindReciprocalRankFuser {
    private VideoMindReciprocalRankFuser() {
    }

    public static <T> List<Fused<T>> fuse(Collection<? extends List<T>> rankedLists, int k,
                                           int perListLimit, Function<T, String> identity,
                                           ToDoubleFunction<T> nativeScore) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return List.of();
        }
        if (k < 0 || perListLimit <= 0) {
            throw new IllegalArgumentException("RRF parameters must be non-negative");
        }

        Map<String, Accumulator<T>> fused = new LinkedHashMap<>();
        long firstSeen = 0;
        for (List<T> rankedList : rankedLists) {
            List<RankedInput<T>> normalized = normalize(rankedList, perListLimit, identity, nativeScore);
            for (int rank = 0; rank < normalized.size(); rank++) {
                RankedInput<T> input = normalized.get(rank);
                Accumulator<T> value = fused.get(input.identity());
                if (value == null) {
                    value = new Accumulator<>(input.value(), input.nativeScore(), firstSeen++);
                    fused.put(input.identity(), value);
                } else if (input.nativeScore() > value.bestNativeScore) {
                    value.representative = input.value();
                    value.bestNativeScore = input.nativeScore();
                }
                value.rrfScore += 1.0 / (k + rank + 1);
            }
        }

        List<Fused<T>> result = new ArrayList<>(fused.size());
        fused.forEach((id, value) -> result.add(new Fused<>(id, value.representative,
                value.rrfScore, value.bestNativeScore, value.firstSeen)));
        result.sort(Comparator.<Fused<T>>comparingDouble(Fused::rrfScore).reversed()
                .thenComparing(Comparator.comparingDouble(Fused<T>::bestNativeScore).reversed())
                .thenComparingLong(Fused::firstSeen)
                .thenComparing(Fused::identity));
        return List.copyOf(result);
    }

    private static <T> List<RankedInput<T>> normalize(List<T> values, int limit,
                                                       Function<T, String> identity,
                                                       ToDoubleFunction<T> nativeScore) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<RankedInput<T>> sorted = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            T value = values.get(index);
            if (value == null) {
                continue;
            }
            String id = identity.apply(value);
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("RRF content identity must not be blank");
            }
            double score = nativeScore.applyAsDouble(value);
            sorted.add(new RankedInput<>(value, id, Double.isFinite(score) ? score : 0, index));
        }
        sorted.sort(Comparator.<RankedInput<T>>comparingDouble(RankedInput::nativeScore).reversed()
                .thenComparingInt(RankedInput::sourceOrder));

        Map<String, RankedInput<T>> unique = new LinkedHashMap<>();
        for (RankedInput<T> value : sorted) {
            unique.putIfAbsent(value.identity(), value);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    public record Fused<T>(String identity, T content, double rrfScore,
                            double bestNativeScore, long firstSeen) {
    }

    private record RankedInput<T>(T value, String identity, double nativeScore, int sourceOrder) {
    }

    private static final class Accumulator<T> {
        private T representative;
        private double bestNativeScore;
        private final long firstSeen;
        private double rrfScore;

        private Accumulator(T representative, double bestNativeScore, long firstSeen) {
            this.representative = representative;
            this.bestNativeScore = bestNativeScore;
            this.firstSeen = firstSeen;
        }
    }
}
