package com.example.urlshortener.orchestrator.policies;

import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrator-owned interaction for one high-risk policy decision.
 *
 * <p>This component records a human decision; it never executes the approved operation. After an
 * approval, the orchestrator must evaluate {@link ControlledAutonomyPolicy} again against the
 * returned state before invoking an agent.</p>
 */
public final class HumanApprovalInteraction {

    public PendingApproval requestApproval(
            ControlledAutonomyPolicy.Decision decision,
            ControlledAutonomyPolicy.OperationRequest operation,
            WorkflowState state,
            String approvalId,
            String agentName,
            String operationTitle,
            String changePreview,
            String riskReason,
            Instant requestedAt,
            Instant expiresAt) {
        requireHighRiskDecision(decision);
        requireText(approvalId, "approvalId");
        requireText(agentName, "agentName");
        requireText(operationTitle, "operationTitle");
        requireText(changePreview, "changePreview");
        requireText(riskReason, "riskReason");
        if (operation == null || state == null || requestedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("operation, state, requestedAt, and expiresAt are required");
        }
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("approval expiry must be after the request time");
        }

        WorkflowState.Task task = requireTask(state, operation.taskId());
        if (!task.requiredApprovalIds().contains(approvalId)) {
            throw new IllegalArgumentException("approvalId is not required by the task");
        }
        if (state.approval(approvalId) != null) {
            throw new IllegalStateException("approvalId has already been decided");
        }

        long pendingRevision = state.stateRevision() + 1;
        ApprovalPrompt prompt = new ApprovalPrompt(
                approvalId,
                state.workflowId(),
                pendingRevision,
                operation.taskId(),
                agentName,
                operationTitle,
                changePreview,
                decision.riskLevel(),
                riskReason,
                operation.action(),
                operation.environment(),
                requestedAt,
                expiresAt);

        WorkflowState pendingState = WorkflowState.from(state)
                .stateRevision(pendingRevision)
                .status(WorkflowState.WorkflowStatus.WAITING_APPROVAL)
                .task(task.withStatus(WorkflowState.TaskStatus.WAITING_APPROVAL, task.attemptCount()))
                .auditEvent(event(prompt, "HUMAN_APPROVAL_REQUESTED", requestedAt, Map.of(
                        "agent", agentName,
                        "action", operation.action().name(),
                        "environment", operation.environment().name(),
                        "risk", decision.riskLevel().name(),
                        "reason", riskReason)))
                .build();

        return new PendingApproval(prompt, pendingState);
    }

    public String render(ApprovalPrompt prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt is required");
        return "AGENT: " + prompt.agentName() + "\n\n"
                + prompt.operationTitle() + "\n\n"
                + prompt.changePreview() + "\n\n"
                + "Risk level: " + prompt.riskLevel() + "\n\n"
                + "Reason:\n" + prompt.reason() + "\n\n"
                + "Approve?\n\n"
                + "[Y] Approve\n"
                + "[N] Reject\n"
                + "[M] Modify\n";
    }

    /** Displays a prompt and reads one fail-closed Y/N/M response from the supplied channel. */
    public Choice readChoice(ApprovalPrompt prompt, Reader input, Writer output) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output are required");
        }
        output.write(render(prompt));
        output.flush();
        BufferedReader reader = input instanceof BufferedReader buffered
                ? buffered : new BufferedReader(input);
        String value = reader.readLine();
        if (value == null) throw new IllegalArgumentException("approval response is required");
        return parseChoice(value);
    }

    public Choice parseChoice(String value) {
        if (value == null) throw new IllegalArgumentException("approval response is required");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "Y" -> Choice.APPROVE;
            case "N" -> Choice.REJECT;
            case "M" -> Choice.MODIFY;
            default -> throw new IllegalArgumentException("response must be Y, N, or M");
        };
    }

    public InteractionOutcome recordResponse(
            WorkflowState state,
            ApprovalPrompt prompt,
            HumanResponse response) {
        if (state == null || prompt == null || response == null) {
            throw new IllegalArgumentException("state, prompt, and response are required");
        }
        validatePendingRequest(state, prompt, response);

        WorkflowState.Task task = requireTask(state, prompt.taskId());
        String eventType = switch (response.choice()) {
            case APPROVE -> "HUMAN_APPROVAL_APPROVED";
            case REJECT -> "HUMAN_APPROVAL_REJECTED";
            case MODIFY -> "HUMAN_APPROVAL_MODIFICATION_REQUESTED";
        };
        Map<String, String> details = Map.ofEntries(
                Map.entry("approvalId", prompt.approvalId()),
                Map.entry("agent", prompt.agentName()),
                Map.entry("action", prompt.action().name()),
                Map.entry("environment", prompt.environment().name()),
                Map.entry("choice", response.choice().name()),
                Map.entry("approverId", response.approverId()),
                Map.entry("approverRole", response.approverRole()),
                Map.entry("rationale", response.rationale()),
                Map.entry("modificationInstructions", nullToEmpty(response.modificationInstructions())));

        WorkflowState.Builder builder = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .auditEvent(event(prompt, eventType, response.decidedAt(), details));

        OutcomeStatus status;
        String summary;
        switch (response.choice()) {
            case APPROVE -> {
                builder.approval(new WorkflowState.Approval(
                                prompt.approvalId(), List.of(prompt.taskId()), prompt.action().name(),
                                WorkflowState.ApprovalStatus.APPROVED, prompt.expiresAt()))
                        .status(WorkflowState.WorkflowStatus.RUNNING)
                        .task(task.withStatus(WorkflowState.TaskStatus.READY, task.attemptCount()));
                status = OutcomeStatus.APPROVED;
                summary = "Approval recorded; policy re-evaluation is required before execution.";
            }
            case REJECT -> {
                builder.status(WorkflowState.WorkflowStatus.PAUSED)
                        .task(task.withStatus(WorkflowState.TaskStatus.BLOCKED, task.attemptCount()));
                status = OutcomeStatus.REJECTED;
                summary = "Operation rejected; the task is blocked and the workflow is paused.";
            }
            case MODIFY -> {
                builder.status(WorkflowState.WorkflowStatus.REPLANNING)
                        .task(task.withStatus(WorkflowState.TaskStatus.INVALIDATED, task.attemptCount()));
                status = OutcomeStatus.MODIFICATION_REQUESTED;
                summary = "Modification requested; the current task is invalidated for replanning.";
            }
            default -> throw new IllegalStateException("unsupported approval response");
        }
        return new InteractionOutcome(status, builder.build(), summary);
    }

    private static void requireHighRiskDecision(ControlledAutonomyPolicy.Decision decision) {
        if (decision == null
                || decision.riskLevel() != ControlledAutonomyPolicy.RiskLevel.HIGH
                || !decision.humanApprovalRequired()
                || decision.executionAllowed()
                || decision.approvalSatisfied()) {
            throw new IllegalArgumentException(
                    "a denied high-risk decision awaiting human approval is required");
        }
    }

    private static void validatePendingRequest(
            WorkflowState state, ApprovalPrompt prompt, HumanResponse response) {
        if (!state.workflowId().equals(prompt.workflowId())
                || state.stateRevision() != prompt.stateRevision()) {
            throw new IllegalStateException("approval prompt is stale or belongs to another workflow revision");
        }
        WorkflowState.Task task = requireTask(state, prompt.taskId());
        if (state.status() != WorkflowState.WorkflowStatus.WAITING_APPROVAL
                || task.status() != WorkflowState.TaskStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("workflow and task must still be waiting for approval");
        }
        if (state.approval(prompt.approvalId()) != null) {
            throw new IllegalStateException("approval prompt has already been decided");
        }
        if (response.decidedAt().isBefore(prompt.requestedAt())
                || !response.decidedAt().isBefore(prompt.expiresAt())) {
            throw new IllegalStateException("approval prompt is not active at the response time");
        }
        if (response.choice() == Choice.MODIFY
                && (response.modificationInstructions() == null
                || response.modificationInstructions().isBlank())) {
            throw new IllegalArgumentException("modification instructions are required for M");
        }
    }

    private static WorkflowState.Task requireTask(WorkflowState state, String taskId) {
        WorkflowState.Task task = state.task(taskId);
        if (task == null) throw new IllegalArgumentException("task does not exist in WorkflowState");
        return task;
    }

    private static WorkflowState.StateEvent event(
            ApprovalPrompt prompt, String type, Instant occurredAt, Map<String, String> details) {
        return new WorkflowState.StateEvent(
                prompt.approvalId() + "-" + type.toLowerCase(Locale.ROOT),
                type,
                occurredAt,
                details);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public enum Choice { APPROVE, REJECT, MODIFY }

    public enum OutcomeStatus { APPROVED, REJECTED, MODIFICATION_REQUESTED }

    public record ApprovalPrompt(
            String approvalId,
            String workflowId,
            long stateRevision,
            String taskId,
            String agentName,
            String operationTitle,
            String changePreview,
            ControlledAutonomyPolicy.RiskLevel riskLevel,
            String reason,
            AgentDefinition.Action action,
            ControlledAutonomyPolicy.Environment environment,
            Instant requestedAt,
            Instant expiresAt) {
        public ApprovalPrompt {
            requireText(approvalId, "approvalId");
            requireText(workflowId, "workflowId");
            requireText(taskId, "taskId");
            requireText(agentName, "agentName");
            requireText(operationTitle, "operationTitle");
            requireText(changePreview, "changePreview");
            requireText(reason, "reason");
            if (stateRevision < 0 || riskLevel == null || action == null || environment == null
                    || requestedAt == null || expiresAt == null) {
                throw new IllegalArgumentException("complete approval prompt metadata is required");
            }
        }
    }

    public record PendingApproval(ApprovalPrompt prompt, WorkflowState state) {
        public PendingApproval {
            if (prompt == null || state == null) {
                throw new IllegalArgumentException("prompt and state are required");
            }
        }
    }

    public record HumanResponse(
            String approverId,
            String approverRole,
            Choice choice,
            String rationale,
            String modificationInstructions,
            Instant decidedAt) {
        public HumanResponse {
            requireText(approverId, "approverId");
            requireText(approverRole, "approverRole");
            requireText(rationale, "rationale");
            if (choice == null || decidedAt == null) {
                throw new IllegalArgumentException("choice and decidedAt are required");
            }
        }
    }

    public record InteractionOutcome(
            OutcomeStatus status,
            WorkflowState state,
            String summary) {
        public InteractionOutcome {
            if (status == null || state == null) {
                throw new IllegalArgumentException("status and state are required");
            }
            requireText(summary, "summary");
        }
    }
}
