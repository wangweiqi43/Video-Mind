package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.knowledge.retrieval.Evidence;
import com.videomind.module.knowledge.retrieval.VideoMindReciprocalRankFuser;
import java.util.List;

/** Fuses ranked evidence again across tools, original queries and guarded query rewrites. */
final class EvidenceRankFusion {
    private static final int RRF_K = 60;

    private EvidenceRankFusion() {
    }

    static List<Evidence> fuse(List<StepResult> steps) {
        List<List<Evidence>> rankedLists = steps == null ? List.of() : steps.stream()
                .map(StepResult::evidence)
                .filter(values -> values != null && !values.isEmpty())
                .toList();
        if (rankedLists.isEmpty()) {
            return List.of();
        }

        var fused = VideoMindReciprocalRankFuser.fuse(rankedLists, RRF_K, Integer.MAX_VALUE,
                Evidence::evidenceId, Evidence::finalScore);
        if (rankedLists.size() == 1) {
            return fused.stream().map(VideoMindReciprocalRankFuser.Fused::content).toList();
        }

        double maxScore = fused.get(0).rrfScore();
        return fused.stream().map(value -> withFusionScore(value.content(), value.rrfScore(),
                maxScore == 0 ? 0 : value.rrfScore() / maxScore)).toList();
    }

    private static Evidence withFusionScore(Evidence value, double rrfScore, double finalScore) {
        return new Evidence(value.evidenceId(), value.knowledgeBaseId(), value.documentId(),
                value.documentVersionId(), value.chunkIndex(), value.parentIndex(), value.title(),
                value.heading(), value.content(), value.parentContent(), value.startMs(), value.endMs(),
                rrfScore, value.rerankScore(), finalScore);
    }
}
