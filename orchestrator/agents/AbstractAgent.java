package com.example.urlshortener.orchestrator.agents;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Fail-closed base class that enforces common boundaries before role-specific work begins. */
public abstract class AbstractAgent implements Agent {
    private static final Set<WorkflowState.TaskStatus> INVOCABLE = EnumSet.of(
            WorkflowState.TaskStatus.READY,
            WorkflowState.TaskStatus.RUNNING,
            WorkflowState.TaskStatus.RETRYING);

    private final AgentDefinition definition;
    private final Clock clock;

    protected AbstractAgent(AgentDefinition definition) {
        this(definition, Clock.systemUTC());
    }

    AbstractAgent(AgentDefinition definition, Clock clock) {
        if (definition == null || clock == null) throw new IllegalArgumentException("definition and clock are required");
        this.definition = definition;
        this.clock = clock;
    }

    @Override
    public final AgentDefinition definition() {
        return definition;
    }

    @Override
    public final AgentResult execute(String taskId, WorkflowState state) {
        Instant startedAt = clock.instant();
        String safeTaskId = taskId == null || taskId.isBlank() ? "<missing>" : taskId;
        try {
            AgentResult guardResult = validateInvocation(taskId, safeTaskId, state, startedAt);
            if (guardResult != null) return guardResult;
            return performTask(taskId, state, startedAt);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "No error detail was provided." : exception.getMessage();
            return result(safeTaskId, AgentResult.Status.FAILED, "Agent invocation failed safely.", false,
                    error("AGENT_EXECUTION_ERROR", message, false, Map.of()), startedAt);
        }
    }

    /**
     * Future deterministic or LLM-backed implementations override this one-task hook.
     * They must return data only and must never invoke another agent or persist WorkflowState.
     */
    protected AgentResult performTask(String taskId, WorkflowState state, Instant startedAt) {
        return result(taskId, AgentResult.Status.BLOCKED,
                "The contract is valid, but operational execution is not implemented.", false,
                error("EXECUTION_NOT_IMPLEMENTED", "No role executor or LLM adapter is configured.", false, Map.of()),
                startedAt);
    }

    private AgentResult validateInvocation(String taskId, String safeTaskId, WorkflowState state,
                                           Instant startedAt) {
        if (taskId == null || taskId.isBlank()) {
            return failed(safeTaskId, "INVALID_INPUT", "taskId is required.", startedAt);
        }
        if (state == null) {
            return failed(safeTaskId, "INVALID_INPUT", "workflowState is required.", startedAt);
        }
        WorkflowState.Task task = state.task(taskId);
        if (task == null) return failed(taskId, "TASK_NOT_FOUND", "Task is absent from WorkflowState.", startedAt);
        if (task.stage() != definition.stage()) {
            return failed(taskId, "STAGE_MISMATCH", "Task stage is outside this agent's responsibility.", startedAt);
        }
        if (task.assignedAgent() != null && !task.assignedAgent().equals(definition.name())) {
            return failed(taskId, "AGENT_ASSIGNMENT_MISMATCH", "Task is assigned to another agent.", startedAt);
        }
        if (state.status() != WorkflowState.WorkflowStatus.RUNNING
                && state.status() != WorkflowState.WorkflowStatus.REPLANNING) {
            return blocked(taskId, "WORKFLOW_NOT_EXECUTABLE",
                    "Workflow status does not permit execution: " + state.status(), startedAt);
        }
        if (task.status() == WorkflowState.TaskStatus.WAITING_APPROVAL) {
            return waiting(taskId, "TASK_WAITING_APPROVAL", "Task is paused at a human gate.", Map.of(), startedAt);
        }
        if (!INVOCABLE.contains(task.status())) {
            return blocked(taskId, "TASK_NOT_EXECUTABLE",
                    "Task status does not permit invocation: " + task.status(), startedAt);
        }
        if (task.status() == WorkflowState.TaskStatus.RETRYING && task.attemptCount() >= task.maxAttempts()) {
            return blocked(taskId, "RETRY_LIMIT_EXHAUSTED", "Bounded retry limit has been reached.", startedAt);
        }
        if (!state.availableContext().containsAll(definition.requiredWorkflowContext())) {
            EnumSet<AgentDefinition.Context> missing = EnumSet.copyOf(definition.requiredWorkflowContext());
            missing.removeAll(state.availableContext());
            return blocked(taskId, "MISSING_WORKFLOW_CONTEXT", "Required context is missing: " + missing, startedAt);
        }
        for (String artifactId : task.inputArtifactIds()) {
            if (!state.artifacts().containsKey(artifactId)) {
                return blocked(taskId, "INPUT_ARTIFACT_MISSING",
                        "Required input artifact is missing: " + artifactId, startedAt);
            }
        }
        for (WorkflowState.Dependency dependency : task.dependencies()) {
            AgentResult blocked = validateDependency(taskId, dependency, state, startedAt);
            if (blocked != null) return blocked;
        }
        for (String approvalId : task.requiredApprovalIds()) {
            WorkflowState.Approval approval = state.approval(approvalId);
            if (approval == null || !approval.isEffectiveFor(taskId, clock.instant())) {
                return waiting(taskId, "APPROVAL_REQUIRED", "A required approval is not effective.",
                        Map.of("approvalId", approvalId), startedAt);
            }
        }
        return null;
    }

    private AgentResult validateDependency(String taskId, WorkflowState.Dependency dependency,
                                           WorkflowState state, Instant startedAt) {
        WorkflowState.Task upstream = state.task(dependency.taskId());
        if (upstream == null) {
            return failed(taskId, "INVALID_TASK_GRAPH",
                    "Dependency task is absent: " + dependency.taskId(), startedAt);
        }
        boolean statusSatisfied = switch (dependency.requiredStatus()) {
            case SUCCEEDED -> upstream.status() == WorkflowState.TaskStatus.SUCCEEDED;
            case COMPLETED -> upstream.status() == WorkflowState.TaskStatus.SUCCEEDED
                    || upstream.status() == WorkflowState.TaskStatus.SKIPPED;
            case APPROVED -> state.approvals().stream()
                    .anyMatch(approval -> approval.isEffectiveFor(upstream.taskId(), clock.instant()));
        };
        if (!statusSatisfied) {
            return blocked(taskId, "DEPENDENCY_NOT_SATISFIED",
                    "Dependency has not reached " + dependency.requiredStatus() + ": " + dependency.taskId(),
                    startedAt);
        }
        for (String artifactId : dependency.requiredArtifactIds()) {
            if (!state.artifacts().containsKey(artifactId)) {
                return blocked(taskId, "DEPENDENCY_ARTIFACT_MISSING",
                        "Dependency artifact is missing: " + artifactId, startedAt);
            }
        }
        return null;
    }

    private AgentResult failed(String taskId, String code, String message, Instant startedAt) {
        return result(taskId, AgentResult.Status.FAILED, message, false,
                error(code, message, false, Map.of()), startedAt);
    }

    private AgentResult blocked(String taskId, String code, String message, Instant startedAt) {
        return result(taskId, AgentResult.Status.BLOCKED, message, false,
                error(code, message, false, Map.of()), startedAt);
    }

    private AgentResult waiting(String taskId, String code, String message, Map<String, String> details,
                                Instant startedAt) {
        return result(taskId, AgentResult.Status.WAITING_APPROVAL, message, true,
                error(code, message, false, details), startedAt);
    }

    private AgentResult result(String taskId, AgentResult.Status status, String summary, boolean approval,
                               AgentResult.AgentError error, Instant startedAt) {
        return AgentResult.empty(definition.name(), taskId, status, summary, approval, error,
                startedAt, clock.instant());
    }

    private static AgentResult.AgentError error(String code, String message, boolean retryable,
                                                Map<String, String> details) {
        return new AgentResult.AgentError(code, message, retryable, details);
    }
}
