package com.videomind.module.agent.workflow;

final class WorkflowJson {
    private WorkflowJson() {
    }

    static String object(String value) {
        if (value == null) {
            throw new IllegalArgumentException("WORKFLOW_JSON_EMPTY");
        }
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine < 0 || closing <= firstLine) {
                throw new IllegalArgumentException("WORKFLOW_JSON_FENCE_INVALID");
            }
            text = text.substring(firstLine + 1, closing).trim();
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new IllegalArgumentException("WORKFLOW_JSON_OBJECT_REQUIRED");
        }
        return text;
    }
}
