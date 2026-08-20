package com.example.urlshortener.orchestrator.workflow;

import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Approval-gated rollback coordinator. Contains no Git, database, or deployment implementation. */
public final class ControlledRollbackOrchestrator {
    private final CheckpointVerifier checkpointVerifier;
    private final RollbackActionExecutor actionExecutor;
    private final RollbackValidator rollbackValidator;
    private final Clock clock;

    public ControlledRollbackOrchestrator(CheckpointVerifier checkpointVerifier,
                                          RollbackActionExecutor actionExecutor,
                                          RollbackValidator rollbackValidator,
                                          Clock clock) {
        if (checkpointVerifier == null || actionExecutor == null || rollbackValidator == null || clock == null) {
            throw new IllegalArgumentException("rollback collaborators and clock are required");
        }
        this.checkpointVerifier = checkpointVerifier;
        this.actionExecutor = actionExecutor;
        this.rollbackValidator = rollbackValidator;
        this.clock = clock;
    }

    public Result execute(WorkflowState state, RollbackRequest request) {
        validateRequest(state, request);
        Instant startedAt = clock.instant();
        CheckResult checkpoint = checkpointVerifier.verify(request.checkpoint());
        if (!checkpoint.valid()) {
            return terminal(state, request, Status.BLOCKED, "CHECKPOINT_INVALID: " + checkpoint.reason(),
                    false, startedAt);
        }
        if (!hasApproval(state, request, startedAt)) {
            WorkflowState waiting = WorkflowState.from(state)
                    .stateRevision(state.stateRevision() + 1)
                    .status(WorkflowState.WorkflowStatus.WAITING_APPROVAL)
                    .task(state.task(request.taskId()).withStatus(
                            WorkflowState.TaskStatus.WAITING_APPROVAL,
                            Math.max(1, state.task(request.taskId()).attemptCount())))
                    .rollbackEvent(event(state, request, "PLANNED", "Human rollback approval required.", startedAt))
                    .auditEvent(event(state, request, "ROLLBACK_APPROVAL_REQUIRED",
                            "Awaiting exact EXECUTE_ROLLBACK approval.", startedAt))
                    .build();
            return new Result(Status.REQUIRES_HUMAN_APPROVAL, waiting, "Human rollback approval required.");
        }

        WorkflowState running = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.ROLLING_BACK)
                .rollbackEvent(event(state, request, "RUNNING", request.trigger(), startedAt))
                .auditEvent(event(state, request, "ROLLBACK_STARTED", request.trigger(), startedAt))
                .build();

        for (RollbackAction action : request.actions()) {
            ActionResult actionResult = actionExecutor.execute(action, request.checkpoint(), running);
            if (!actionResult.succeeded()) {
                return terminal(running, request, Status.FAILED_SAFE_STOPPED,
                        "Rollback action failed: " + action.actionId() + ": " + actionResult.reason(),
                        true, startedAt);
            }
        }

        ValidationResult validation = rollbackValidator.validate(request, running);
        if (!validation.passed()) {
            return terminal(running, request, Status.FAILED_SAFE_STOPPED,
                    "Rollback validation failed: " + validation.reason(), true, startedAt);
        }

        Instant completedAt = clock.instant();
        WorkflowState restored = WorkflowState.from(running)
                .stateRevision(running.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.SAFE_STOPPED)
                .task(running.task(request.taskId()).withStatus(
                        WorkflowState.TaskStatus.ROLLED_BACK,
                        Math.max(1, running.task(request.taskId()).attemptCount())))
                .safeStop("Rollback completed and validated; automatic continuation is prohibited.",
                        "Review restored state and approve a new checkpoint or replan before resuming.")
                .rollbackEvent(event(running, request, "SUCCEEDED", validation.reason(), completedAt))
                .auditEvent(event(running, request, "ROLLBACK_SUCCEEDED", validation.reason(), completedAt))
                .build();
        return new Result(Status.ROLLED_BACK_SAFE_STOPPED, restored,
                "Rollback succeeded and remains safe-stopped for human review.");
    }

    private Result terminal(WorkflowState state, RollbackRequest request, Status status,
                            String reason, boolean executionStarted, Instant startedAt) {
        Instant completedAt = clock.instant();
        WorkflowState.Builder builder = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.SAFE_STOPPED)
                .safeStop(reason, "Inspect rollback evidence and restore manually or approve a corrected rollback plan.")
                .rollbackEvent(event(state, request, executionStarted ? "FAILED" : "CANCELLED", reason, completedAt))
                .auditEvent(event(state, request, executionStarted ? "ROLLBACK_FAILED" : "ROLLBACK_BLOCKED",
                        reason, completedAt));
        WorkflowState stopped = builder.build();
        return new Result(status, stopped, reason);
    }

    private static boolean hasApproval(WorkflowState state, RollbackRequest request, Instant now) {
        return state.task(request.taskId()).requiredApprovalIds().stream()
                .map(state::approval)
                .filter(approval -> approval != null)
                .anyMatch(approval -> approval.isEffectiveFor(
                        request.taskId(), AgentDefinition.Action.EXECUTE_ROLLBACK, now));
    }

    private static void validateRequest(WorkflowState state, RollbackRequest request) {
        if (state == null || request == null) throw new IllegalArgumentException("state and request are required");
        if (state.status() != WorkflowState.WorkflowStatus.SAFE_STOPPED) {
            throw new IllegalStateException("rollback may start only from SAFE_STOPPED");
        }
        if (state.task(request.taskId()) == null) throw new IllegalArgumentException("rollback task does not exist");
        if (!request.checkpoint().knownGood()) throw new IllegalArgumentException("checkpoint is not known-good");
        if (request.actions().isEmpty()) throw new IllegalArgumentException("rollback actions are required");
        boolean schemaRollback = request.actions().stream().anyMatch(
                action -> action.type() == ActionType.ROLLBACK_SCHEMA);
        if (schemaRollback && (request.checkpoint().databaseBackupReference() == null
                || request.checkpoint().databaseBackupReference().isBlank())) {
            throw new IllegalArgumentException("schema rollback requires a database backup reference");
        }
    }

    private static WorkflowState.StateEvent event(WorkflowState state, RollbackRequest request,
                                                  String type, String reason, Instant timestamp) {
        return new WorkflowState.StateEvent(
                state.workflowId() + ":" + request.rollbackId() + ":" + type + ":" + state.stateRevision(),
                type, timestamp,
                Map.of("workflowId", state.workflowId(), "rollbackId", request.rollbackId(),
                        "taskId", request.taskId(), "checkpointId", request.checkpoint().checkpointId(),
                        "reason", reason, "actionCount", Integer.toString(request.actions().size())));
    }

    public enum Status { REQUIRES_HUMAN_APPROVAL, BLOCKED, ROLLED_BACK_SAFE_STOPPED, FAILED_SAFE_STOPPED }
    public enum ActionType { RESTORE_FILE, RESTORE_ARTIFACT, REVERT_COMMIT, ROLLBACK_SCHEMA, ROLLBACK_DEPLOYMENT }

    public record Checkpoint(String checkpointId, long stateRevision, String gitCommit,
                             String snapshotUri, String snapshotHash,
                             String databaseBackupReference, boolean knownGood) {
        public Checkpoint {
            if (checkpointId == null || checkpointId.isBlank() || stateRevision < 0
                    || gitCommit == null || gitCommit.isBlank()
                    || snapshotUri == null || snapshotUri.isBlank()
                    || snapshotHash == null || snapshotHash.isBlank()) {
                throw new IllegalArgumentException("complete immutable checkpoint metadata is required");
            }
        }
    }

    public record RollbackAction(String actionId, ActionType type, String target, String idempotencyKey) {
        public RollbackAction {
            if (actionId == null || actionId.isBlank() || type == null || target == null || target.isBlank()
                    || idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("complete rollback action metadata is required");
            }
        }
    }

    public record RollbackRequest(String rollbackId, String taskId, String trigger,
                                  Checkpoint checkpoint, List<RollbackAction> actions) {
        public RollbackRequest {
            if (rollbackId == null || rollbackId.isBlank() || taskId == null || taskId.isBlank()
                    || trigger == null || trigger.isBlank() || checkpoint == null) {
                throw new IllegalArgumentException("complete rollback request is required");
            }
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    public record Result(Status status, WorkflowState workflowState, String summary) { }
    public record CheckResult(boolean valid, String reason) { }
    public record ActionResult(boolean succeeded, String reason) { }
    public record ValidationResult(boolean passed, String reason) { }

    @FunctionalInterface public interface CheckpointVerifier { CheckResult verify(Checkpoint checkpoint); }
    @FunctionalInterface public interface RollbackActionExecutor {
        ActionResult execute(RollbackAction action, Checkpoint checkpoint, WorkflowState state);
    }
    @FunctionalInterface public interface RollbackValidator {
        ValidationResult validate(RollbackRequest request, WorkflowState state);
    }
}
