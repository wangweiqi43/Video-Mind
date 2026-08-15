package com.videomind.module.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class StructuredLlmAgentPlanner {
    static final Set<String> ALLOWED_TOOLS = Set.of("ALL_SCOPE_HYBRID_RETRIEVAL",
            "VIDEO_TIMELINE_RETRIEVAL", "USER_DOCUMENT_RETRIEVAL", "CONVERSATION_CONTEXT_READ");
    private final WorkflowDecisionClient decisions;
    private final ObjectMapper objectMapper;

    public Plan plan(Request request, Plan previous, Critique critique, int generation) {
        String system = """
                你是 VideoMind 的有界检索 Planner。只返回一个 JSON 对象，不要 Markdown，不要解释过程。
                格式：{"route":"字符串","steps":[{"id":"唯一字符串","tool":"工具名","input":"检索问题"}]}
                工具只允许 ALL_SCOPE_HYBRID_RETRIEVAL、VIDEO_TIMELINE_RETRIEVAL、
                USER_DOCUMENT_RETRIEVAL、CONVERSATION_CONTEXT_READ。
                最多生成 6 个步骤；禁止联网、代码执行和未列出的工具；不得输出隐藏思维链。
                """;
        String user = "问题：" + request.question() + "\n计划代数：" + generation
                + "\n上次评审：" + (critique == null ? "无" : critique.reason());
        return parse(decisions.decide(system, user), generation, request.maxToolCalls());
    }

    Plan parse(String raw, int generation, int maxSteps) {
        try {
            JsonNode root = objectMapper.readTree(WorkflowJson.object(raw));
            String route = root.path("route").asText("").trim();
            JsonNode source = root.path("steps");
            if (!StringUtils.hasText(route) || !source.isArray() || source.isEmpty()
                    || source.size() > maxSteps) {
                throw new IllegalArgumentException("WORKFLOW_PLAN_SHAPE_INVALID");
            }
            List<Step> steps = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode value : source) {
                String id = value.path("id").asText("").trim();
                String tool = value.path("tool").asText("").trim();
                String input = value.path("input").asText("").trim();
                if (!StringUtils.hasText(id) || !ids.add(id) || !ALLOWED_TOOLS.contains(tool)
                        || !StringUtils.hasText(input)) {
                    throw new IllegalArgumentException("WORKFLOW_PLAN_STEP_INVALID");
                }
                steps.add(new Step(id, tool, input));
            }
            return new Plan(route, steps, generation);
        } catch (IllegalArgumentException known) {
            throw known;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("WORKFLOW_PLAN_JSON_INVALID", invalid);
        }
    }
}
