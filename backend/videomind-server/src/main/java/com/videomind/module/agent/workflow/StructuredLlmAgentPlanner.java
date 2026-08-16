package com.videomind.module.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.QueryOrigin;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
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
    static final String VIDEO_TOOL = "VIDEO_TIMELINE_RETRIEVAL";
    static final String DOCUMENT_TOOL = "USER_DOCUMENT_RETRIEVAL";
    static final Set<String> ALLOWED_TOOLS = Set.of(VIDEO_TOOL, DOCUMENT_TOOL);
    private final WorkflowDecisionClient decisions;
    private final ObjectMapper objectMapper;

    public Plan plan(Request request, Plan previous, Critique critique, int generation, int maxSteps) {
        String system = """
                你是 VideoMind 的受限检索 Planner。只返回一个 JSON 对象，不要 Markdown，不要解释推理过程。
                格式：
                {"route":"DIRECT_CONVERSATION|VIDEO_RAG|DOCUMENT_RAG|MIXED_RAG|OUT_OF_SCOPE",
                 "reasonCode":"简短枚举原因",
                 "steps":[{"id":"唯一字符串","tool":"VIDEO_TIMELINE_RETRIEVAL|USER_DOCUMENT_RETRIEVAL",
                           "input":"查询文本","queryOrigin":"ORIGINAL|REWRITE_1|REWRITE_2"}]}

                路由规则：
                - 问候、能力说明或无需外部事实的对话使用 DIRECT_CONVERSATION，不生成步骤。
                - 明显与当前视频及文档知识范围无关的问题使用 OUT_OF_SCOPE，不生成步骤。
                - 可能需要知识证据时，选择 VIDEO_RAG、DOCUMENT_RAG 或 MIXED_RAG。
                - VIDEO_RAG 只能使用 VIDEO_TIMELINE_RETRIEVAL；DOCUMENT_RAG 只能使用 USER_DOCUMENT_RETRIEVAL。
                - MIXED_RAG 可以使用两种检索工具，但不能使用任何未列出的工具。
                - 禁止联网、代码执行和扩大用户已经锁定的知识库范围。
                - 不得输出隐藏思维链，只输出可审计的 reasonCode。
                """;
        String user = "问题：" + request.question()
                + "\n视频知识库可用：" + (request.scope().videoKnowledgeBaseId() != null)
                + "\n文档知识库数量：" + request.scope().documentKnowledgeBaseIds().size()
                + "\n会话摘要：" + request.conversation().summary()
                + "\n最近对话：" + String.join("\n", request.conversation().recentTurns())
                + "\n计划代数：" + generation
                + "\n上次审查：" + critiqueText(critique)
                + "\n本代最多步骤：" + maxSteps;
        return parse(decisions.decide(system, user), request, critique, generation, maxSteps);
    }

    Plan parse(String raw, Request request, Critique critique, int generation, int maxSteps) {
        try {
            JsonNode root = objectMapper.readTree(WorkflowJson.object(raw));
            Route route = Route.valueOf(root.path("route").asText(""));
            String reasonCode = root.path("reasonCode").asText("UNSPECIFIED").trim();
            JsonNode source = root.path("steps");
            if (!source.isArray() || source.size() > maxSteps || !StringUtils.hasText(reasonCode)) {
                throw new IllegalArgumentException("WORKFLOW_PLAN_SHAPE_INVALID");
            }
            if ((route == Route.DIRECT_CONVERSATION || route == Route.OUT_OF_SCOPE) && !source.isEmpty()) {
                throw new IllegalArgumentException("WORKFLOW_PLAN_ROUTE_MUST_NOT_RETRIEVE");
            }
            if (retrievalRoute(route) && source.isEmpty()) {
                throw new IllegalArgumentException("WORKFLOW_PLAN_ROUTE_REQUIRES_RETRIEVAL");
            }
            List<Step> steps = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> calls = new HashSet<>();
            for (JsonNode value : source) {
                String id = value.path("id").asText("").trim();
                String tool = value.path("tool").asText("").trim();
                String modelInput = value.path("input").asText("").trim();
                QueryOrigin origin = QueryOrigin.valueOf(value.path("queryOrigin").asText(
                        generation == 0 ? "ORIGINAL" : ""));
                String input = generation == 0 ? request.question().strip() : modelInput;
                if (!StringUtils.hasText(id) || !ids.add(id) || !ALLOWED_TOOLS.contains(tool)
                        || !StringUtils.hasText(input) || input.length() > 500 || !toolAllowed(route, tool)
                        || !scopeAvailable(request, tool) || generation == 0 && origin != QueryOrigin.ORIGINAL
                        || generation > 0 && !allowedRewrite(critique, input, origin)) {
                    throw new IllegalArgumentException("WORKFLOW_PLAN_STEP_INVALID");
                }
                if (!calls.add(tool + "\n" + input)) {
                    continue;
                }
                steps.add(new Step(id, tool, input, origin));
            }
            if (route == Route.MIXED_RAG && steps.stream().map(Step::tool).distinct().count() < 2) {
                throw new IllegalArgumentException("WORKFLOW_MIXED_ROUTE_INCOMPLETE");
            }
            return new Plan(route, steps, generation, reasonCode);
        } catch (IllegalArgumentException known) {
            throw known;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("WORKFLOW_PLAN_JSON_INVALID", invalid);
        }
    }

    private boolean allowedRewrite(Critique critique, String input, QueryOrigin origin) {
        if (critique == null || critique.querySet() == null || origin == QueryOrigin.ORIGINAL) return false;
        int index = origin == QueryOrigin.REWRITE_1 ? 0 : 1;
        return critique.querySet().rewrittenQueries().size() > index
                && critique.querySet().rewrittenQueries().get(index).equals(input);
    }

    private boolean scopeAvailable(Request request, String tool) {
        return VIDEO_TOOL.equals(tool) ? request.scope().videoKnowledgeBaseId() != null
                : !request.scope().documentKnowledgeBaseIds().isEmpty();
    }

    private boolean toolAllowed(Route route, String tool) {
        return route == Route.MIXED_RAG || route == Route.VIDEO_RAG && VIDEO_TOOL.equals(tool)
                || route == Route.DOCUMENT_RAG && DOCUMENT_TOOL.equals(tool);
    }

    private boolean retrievalRoute(Route route) {
        return route == Route.VIDEO_RAG || route == Route.DOCUMENT_RAG || route == Route.MIXED_RAG;
    }

    private String critiqueText(Critique critique) {
        if (critique == null) return "无";
        return critique.verdict() + ":" + critique.reasonCode() + ":" + critique.reason()
                + ";可用改写=" + (critique.querySet() == null ? List.of()
                : critique.querySet().rewrittenQueries());
    }
}
