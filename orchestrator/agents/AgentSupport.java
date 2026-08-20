package com.example.urlshortener.orchestrator.agents;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class AgentSupport {
    private AgentSupport() { }

    static AgentDefinition definition(
            String name,
            String purpose,
            AgentDefinition.Stage stage,
            String promptPath,
            Set<AgentDefinition.Context> context,
            Set<AgentDefinition.Action> allowed,
            Set<AgentDefinition.Action> approvalRequired,
            List<String> validationRules,
            List<String> failureConditions,
            List<String> approvalConditions,
            AgentDefinition.ContractField... roleOutputs) {
        EnumSet<AgentDefinition.Action> prohibited = EnumSet.allOf(AgentDefinition.Action.class);
        prohibited.removeAll(allowed);
        prohibited.removeAll(approvalRequired);
        List<AgentDefinition.ContractField> outputs = new ArrayList<>(commonOutputs());
        outputs.addAll(List.of(roleOutputs));
        return new AgentDefinition(name, purpose, stage, promptPath, commonInputs(), outputs, context,
                allowed, approvalRequired, prohibited, validationRules, failureConditions, approvalConditions,
                EnumSet.allOf(AgentDefinition.AuditField.class));
    }

    @SafeVarargs
    static <E extends Enum<E>> Set<E> set(E first, E... rest) {
        return Set.copyOf(EnumSet.of(first, rest));
    }

    static Set<AgentDefinition.Action> noApprovalActions() { return Set.of(); }

    private static List<AgentDefinition.ContractField> commonInputs() {
        return List.of(
                field("taskId", "string", true, "One DAG task assigned to this invocation."),
                field("workflowState", "WorkflowState", true, "Immutable current workflow-state snapshot."),
                field("retryContext", "WorkflowState.RetryContext|null", false,
                        "Previous same-task failure context for a targeted implementation retry."));
    }

    private static List<AgentDefinition.ContractField> commonOutputs() {
        return List.of(
                field("agentName", "string", true, "Definition name."),
                field("taskId", "string", true, "Processed task ID."),
                field("status", "AgentResult.Status", true, "Bounded invocation outcome."),
                field("summary", "string", true, "Concise factual result."),
                field("artifacts", "WorkflowState.Artifact[]", true, "Produced artifact references."),
                field("decisions", "WorkflowState.Decision[]", true, "Decisions and rationale."),
                field("risks", "WorkflowState.Risk[]", true, "New or updated risks."),
                field("validationResults", "WorkflowState.Validation[]", true, "Checks and evidence."),
                field("requiresHumanApproval", "boolean", true, "Whether orchestration must pause."),
                field("error", "AgentResult.AgentError|null", false, "Structured failure or block reason."),
                field("startedAt", "Instant", true, "UTC invocation start."),
                field("completedAt", "Instant", true, "UTC invocation completion."));
    }

    static AgentDefinition.ContractField field(String name, String type, boolean required, String description) {
        return new AgentDefinition.ContractField(name, type, required, description);
    }
}
