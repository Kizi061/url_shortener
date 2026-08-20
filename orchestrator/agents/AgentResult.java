package com.example.urlshortener.orchestrator.agents;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Structured result returned for exactly one task invocation. */
public record AgentResult(
        String agentName,
        String taskId,
        Status status,
        String summary,
        List<WorkflowState.Artifact> artifacts,
        List<WorkflowState.Decision> decisions,
        List<WorkflowState.Risk> risks,
        List<WorkflowState.Validation> validationResults,
        boolean requiresHumanApproval,
        AgentError error,
        Instant startedAt,
        Instant completedAt) {

    public AgentResult {
        if (agentName == null || agentName.isBlank()) throw new IllegalArgumentException("agentName is required");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (status == null || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("status and summary are required");
        }
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        risks = risks == null ? List.of() : List.copyOf(risks);
        validationResults = validationResults == null ? List.of() : List.copyOf(validationResults);
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("valid audit timestamps are required");
        }
        if ((status == Status.FAILED || status == Status.BLOCKED) && error == null) {
            throw new IllegalArgumentException("failed and blocked results require an error");
        }
        if (status == Status.WAITING_APPROVAL && !requiresHumanApproval) {
            throw new IllegalArgumentException("WAITING_APPROVAL must require human approval");
        }
    }

    static AgentResult empty(String agentName, String taskId, Status status, String summary,
                             boolean approval, AgentError error, Instant startedAt, Instant completedAt) {
        return new AgentResult(agentName, taskId, status, summary, List.of(), List.of(), List.of(), List.of(),
                approval, error, startedAt, completedAt);
    }

    public enum Status { SUCCEEDED, FAILED, BLOCKED, WAITING_APPROVAL }

    public record AgentError(String code, String message, boolean retryable, Map<String, String> details) {
        public AgentError {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("error code and message are required");
            }
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
