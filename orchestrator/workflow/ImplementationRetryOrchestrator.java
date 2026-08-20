package com.example.urlshortener.orchestrator.workflow;

import com.example.urlshortener.orchestrator.agents.Agent;
import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.AgentResult;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the bounded Implementation -> Test -> Validation corrective retry cycle. */
public final class ImplementationRetryOrchestrator {
    private final RetryConfiguration configuration;
    private final StageExecutor implementation;
    private final StageExecutor test;
    private final StageExecutor validation;
    private final Clock clock;

    public ImplementationRetryOrchestrator(RetryConfiguration configuration,
                                           StageExecutor implementation,
                                           StageExecutor test,
                                           StageExecutor validation,
                                           Clock clock) {
        if (configuration == null || implementation == null || test == null
                || validation == null || clock == null) {
            throw new IllegalArgumentException("retry configuration, stages, and clock are required");
        }
        this.configuration = configuration;
        this.implementation = implementation;
        this.test = test;
        this.validation = validation;
        this.clock = clock;
    }

    public ExecutionResult execute(WorkflowState initialState, PipelineTasks pipeline) {
        validatePipeline(initialState, pipeline);
        WorkflowState state = initialState;
        WorkflowState.Task implementationTask = state.task(pipeline.implementationTaskId());
        int maximumAttempts = Math.min(configuration.implementationMaxAttempts(), implementationTask.maxAttempts());
        int attempt = implementationTask.status() == WorkflowState.TaskStatus.RETRYING
                ? implementationTask.attemptCount()
                : Math.max(1, implementationTask.attemptCount() + 1);
        WorkflowState.RetryContext retryContext = retryContextFor(state, attempt);

        while (true) {
            state = updateTask(state, pipeline.implementationTaskId(), WorkflowState.TaskStatus.RUNNING, attempt);
            AgentResult implementationResult = implementation.execute(
                    pipeline.implementationTaskId(), state, retryContext);
            Failure failure = failureFrom(implementationResult, Stage.IMPLEMENTATION);
            if (failure != null) {
                FailureHandling handling = handleFailure(state, pipeline, attempt, maximumAttempts, failure);
                if (handling.terminal() != null) return handling.terminal();
                state = handling.state();
                retryContext = handling.retryContext();
                attempt++;
                continue;
            }

            state = updateTask(state, pipeline.implementationTaskId(), WorkflowState.TaskStatus.SUCCEEDED, attempt);
            AgentResult testResult = test.execute(pipeline.testTaskId(), state, retryContext);
            failure = failureFrom(testResult, Stage.TEST);
            if (failure != null) {
                FailureHandling handling = handleFailure(state, pipeline, attempt, maximumAttempts, failure);
                if (handling.terminal() != null) return handling.terminal();
                state = handling.state();
                retryContext = handling.retryContext();
                attempt++;
                continue;
            }

            state = updateTask(state, pipeline.testTaskId(), WorkflowState.TaskStatus.SUCCEEDED,
                    nextTaskAttempt(state.task(pipeline.testTaskId())));
            AgentResult validationResult = validation.execute(pipeline.validationTaskId(), state, retryContext);
            failure = failureFrom(validationResult, Stage.VALIDATION);
            if (failure != null) {
                FailureHandling handling = handleFailure(state, pipeline, attempt, maximumAttempts, failure);
                if (handling.terminal() != null) return handling.terminal();
                state = handling.state();
                retryContext = handling.retryContext();
                attempt++;
                continue;
            }

            return successful(state, pipeline, attempt);
        }
    }

    private FailureHandling handleFailure(WorkflowState state, PipelineTasks pipeline, int attempt,
                                          int maximumAttempts, Failure failure) {
        Instant failedAt = clock.instant();
        WorkflowState.FailureContext context = new WorkflowState.FailureContext(
                state.workflowId(), pipeline.implementationTaskId(), failure.agentName(), attempt,
                failure.type().name(), failure.reason(), failure.failedTests(), failure.validationFindings(),
                state.changedFiles(), failedAt);
        state = recordFailure(state, context);

        if (!failure.type().retryable()) {
            if (failure.type().requiresApproval()) {
                WorkflowState waiting = transitionNonRetry(state, pipeline, context,
                        WorkflowState.WorkflowStatus.WAITING_APPROVAL,
                        WorkflowState.TaskStatus.WAITING_APPROVAL, false,
                        "Obtain task- and action-scoped human approval, then resume from the checkpoint.");
                return new FailureHandling(waiting, null,
                        new ExecutionResult(ExecutionStatus.REQUIRES_HUMAN_APPROVAL, waiting));
            }
            if (failure.type().safeStops()) {
                WorkflowState stopped = transitionNonRetry(state, pipeline, context,
                        WorkflowState.WorkflowStatus.SAFE_STOPPED, WorkflowState.TaskStatus.FAILED, true,
                        "Review the complete retry/failure evidence and authorize remediation or replanning.");
                return new FailureHandling(stopped, null,
                        new ExecutionResult(ExecutionStatus.SAFE_STOP, stopped));
            }
            WorkflowState blocked = transitionNonRetry(state, pipeline, context,
                    WorkflowState.WorkflowStatus.PAUSED, WorkflowState.TaskStatus.BLOCKED, false,
                    "Clarify or approve the blocked scope before resuming this task.");
            return new FailureHandling(blocked, null,
                    new ExecutionResult(ExecutionStatus.BLOCKED, blocked));
        }

        if (attempt >= maximumAttempts) {
            WorkflowState stopped = safeStopForExhaustion(state, pipeline, context, attempt);
            return new FailureHandling(stopped, null,
                    new ExecutionResult(ExecutionStatus.SAFE_STOP, stopped));
        }

        int retryAttempt = attempt + 1;
        WorkflowState.RetryRecord retry = new WorkflowState.RetryRecord(
                retryId(state, pipeline.implementationTaskId(), retryAttempt),
                pipeline.implementationTaskId(), retryAttempt, context,
                WorkflowState.RetryOutcome.SCHEDULED, failedAt, null);
        int retryCount = state.retryCountByTask().getOrDefault(pipeline.implementationTaskId(), 0) + 1;
        WorkflowState.StateEvent audit = retryAudit(state, pipeline.implementationTaskId(), retryAttempt,
                failure.agentName(), failure.reason(), failedAt);
        WorkflowState next = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.RUNNING)
                .task(state.task(pipeline.implementationTaskId())
                        .withStatus(WorkflowState.TaskStatus.RETRYING, retryAttempt))
                .task(resetForRerun(state.task(pipeline.testTaskId())))
                .task(resetForRerun(state.task(pipeline.validationTaskId())))
                .retryCount(pipeline.implementationTaskId(), retryCount)
                .retryRecord(retry)
                .lastFailure(context)
                .reliabilityMetrics(state.reliabilityMetrics().retryScheduled(pipeline.implementationTaskId()))
                .auditEvent(audit)
                .build();
        return new FailureHandling(next, WorkflowState.RetryContext.fromFailure(retryAttempt, context), null);
    }

    private ExecutionResult successful(WorkflowState state, PipelineTasks pipeline, int attempt) {
        Instant completedAt = clock.instant();
        List<WorkflowState.RetryRecord> history = completeLatestRetry(
                state.retryHistory(), pipeline.implementationTaskId(),
                WorkflowState.RetryOutcome.SUCCEEDED, completedAt);
        WorkflowState.ReliabilityMetrics metrics = state.reliabilityMetrics();
        if (attempt > 1 && !history.isEmpty()) {
            Instant firstFailureAt = history.stream()
                    .filter(record -> record.taskId().equals(pipeline.implementationTaskId()))
                    .map(record -> record.previousFailure().timestamp())
                    .min(Instant::compareTo)
                    .orElse(completedAt);
            metrics = metrics.recovered(Math.max(0, Duration.between(firstFailureAt, completedAt).toMillis()));
        }
        WorkflowState.Builder builder = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.RUNNING)
                .task(state.task(pipeline.implementationTaskId())
                        .withStatus(WorkflowState.TaskStatus.SUCCEEDED, attempt))
                .task(state.task(pipeline.testTaskId()).withStatus(WorkflowState.TaskStatus.SUCCEEDED,
                        Math.max(1, state.task(pipeline.testTaskId()).attemptCount())))
                .task(state.task(pipeline.validationTaskId()).withStatus(WorkflowState.TaskStatus.SUCCEEDED,
                        Math.max(1, state.task(pipeline.validationTaskId()).attemptCount())))
                .retryHistory(history)
                .reliabilityMetrics(metrics);
        if (attempt > 1) {
            builder.auditEvent(new WorkflowState.StateEvent(
                    eventId(state, "RECOVERED", attempt), "RECOVERED", completedAt,
                    Map.of("workflowId", state.workflowId(), "taskId", pipeline.implementationTaskId(),
                            "agent", "ImplementationAgent", "attempt", Integer.toString(attempt))));
        }
        WorkflowState succeeded = builder.build();
        return new ExecutionResult(ExecutionStatus.SUCCESS, succeeded);
    }

    private WorkflowState safeStopForExhaustion(WorkflowState state, PipelineTasks pipeline,
                                                WorkflowState.FailureContext context, int attempts) {
        Instant stoppedAt = clock.instant();
        List<WorkflowState.RetryRecord> history = completeLatestRetry(
                state.retryHistory(), pipeline.implementationTaskId(),
                WorkflowState.RetryOutcome.FAILED, stoppedAt);
        String reason = "Implementation retry limit exhausted after " + attempts + " attempts: "
                + context.failureReason();
        return WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(WorkflowState.WorkflowStatus.SAFE_STOPPED)
                .task(state.task(pipeline.implementationTaskId())
                        .withStatus(WorkflowState.TaskStatus.FAILED, attempts))
                .task(blockDependent(state.task(pipeline.testTaskId())))
                .task(blockDependent(state.task(pipeline.validationTaskId())))
                .retryHistory(history)
                .lastFailure(context)
                .safeStop(reason, "Review the complete retry history and unresolved findings; approve remediation or replan.")
                .reliabilityMetrics(state.reliabilityMetrics().safeStopped())
                .auditEvent(new WorkflowState.StateEvent(eventId(state, "SAFE_STOP", attempts),
                        "SAFE_STOP", stoppedAt,
                        Map.of("workflowId", state.workflowId(), "taskId", pipeline.implementationTaskId(),
                                "agent", context.agentName(), "attempt", Integer.toString(attempts),
                                "reason", reason)))
                .build();
    }

    private WorkflowState transitionNonRetry(WorkflowState state, PipelineTasks pipeline,
                                             WorkflowState.FailureContext context,
                                             WorkflowState.WorkflowStatus workflowStatus,
                                             WorkflowState.TaskStatus taskStatus,
                                             boolean countSafeStop,
                                             String humanAction) {
        Instant now = clock.instant();
        WorkflowState.ReliabilityMetrics metrics = countSafeStop
                ? state.reliabilityMetrics().safeStopped() : state.reliabilityMetrics();
        WorkflowState.Builder builder = WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .status(workflowStatus)
                .task(state.task(pipeline.implementationTaskId()).withStatus(taskStatus,
                        Math.max(1, context.attemptNumber())))
                .lastFailure(context)
                .reliabilityMetrics(metrics);
        if (workflowStatus == WorkflowState.WorkflowStatus.SAFE_STOPPED) {
            builder.task(blockDependent(state.task(pipeline.testTaskId())))
                    .task(blockDependent(state.task(pipeline.validationTaskId())))
                    .safeStop(context.failureReason(), humanAction)
                    .auditEvent(new WorkflowState.StateEvent(eventId(state, "SAFE_STOP", context.attemptNumber()),
                            "SAFE_STOP", now, Map.of("workflowId", state.workflowId(),
                            "taskId", pipeline.implementationTaskId(), "agent", context.agentName(),
                            "attempt", Integer.toString(context.attemptNumber()),
                            "reason", context.failureReason())));
        }
        return builder.build();
    }

    private WorkflowState recordFailure(WorkflowState state, WorkflowState.FailureContext context) {
        return WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .lastFailure(context)
                .failure(new WorkflowState.StateEvent(
                        eventId(state, "FAILURE", context.attemptNumber()), "FAILURE", context.timestamp(),
                        Map.of("workflowId", context.workflowId(), "taskId", context.taskId(),
                                "agent", context.agentName(), "attempt", Integer.toString(context.attemptNumber()),
                                "failureType", context.failureType(), "reason", context.failureReason())))
                .build();
    }

    private WorkflowState updateTask(WorkflowState state, String taskId,
                                     WorkflowState.TaskStatus status, int attempt) {
        return WorkflowState.from(state)
                .stateRevision(state.stateRevision() + 1)
                .task(state.task(taskId).withStatus(status, attempt))
                .build();
    }

    private Failure failureFrom(AgentResult result, Stage stage) {
        if (result == null) {
            return new Failure(FailureType.UNKNOWN_FAILURE, stage.agentName,
                    "Agent returned no result.", List.of(), List.of());
        }
        if (result.status() == AgentResult.Status.SUCCEEDED) return null;
        FailureType type;
        if (result.status() == AgentResult.Status.WAITING_APPROVAL || result.requiresHumanApproval()) {
            type = FailureType.MISSING_HUMAN_APPROVAL;
        } else {
            type = FailureType.fromCode(result.error() == null ? null : result.error().code());
        }
        String reason = result.error() == null ? result.summary() : result.error().message();
        List<String> findings = result.validationResults().stream()
                .filter(value -> !"PASSED".equalsIgnoreCase(value.result()))
                .map(WorkflowState.Validation::summary)
                .toList();
        List<String> failedTests = stage == Stage.TEST ? findings : List.of();
        return new Failure(type, result.agentName(), reason, failedTests, findings);
    }

    private static WorkflowState.RetryContext retryContextFor(WorkflowState state, int attempt) {
        if (attempt <= 1) return WorkflowState.RetryContext.firstAttempt();
        if (state.lastFailure() == null) {
            throw new IllegalStateException("Cannot resume a retry without previous failure context");
        }
        return WorkflowState.RetryContext.fromFailure(attempt, state.lastFailure());
    }

    private static List<WorkflowState.RetryRecord> completeLatestRetry(
            List<WorkflowState.RetryRecord> records, String taskId,
            WorkflowState.RetryOutcome outcome, Instant completedAt) {
        if (records.isEmpty()) return records;
        List<WorkflowState.RetryRecord> updated = new ArrayList<>(records);
        for (int index = updated.size() - 1; index >= 0; index--) {
            WorkflowState.RetryRecord record = updated.get(index);
            if (record.taskId().equals(taskId) && record.outcome() == WorkflowState.RetryOutcome.SCHEDULED) {
                updated.set(index, record.complete(outcome, completedAt));
                break;
            }
        }
        return List.copyOf(updated);
    }

    private static WorkflowState.Task resetForRerun(WorkflowState.Task task) {
        return task.withStatus(WorkflowState.TaskStatus.READY, task.attemptCount());
    }

    private static WorkflowState.Task blockDependent(WorkflowState.Task task) {
        if (task.status() == WorkflowState.TaskStatus.SUCCEEDED) return task;
        return task.withStatus(WorkflowState.TaskStatus.BLOCKED, task.attemptCount());
    }

    private static int nextTaskAttempt(WorkflowState.Task task) {
        return Math.min(task.maxAttempts(), Math.max(1, task.attemptCount() + 1));
    }

    private static WorkflowState.StateEvent retryAudit(WorkflowState state, String taskId, int attempt,
                                                       String agent, String reason, Instant timestamp) {
        return new WorkflowState.StateEvent(eventId(state, "RETRY", attempt), "RETRY", timestamp,
                Map.of("workflowId", state.workflowId(), "taskId", taskId, "agent", agent,
                        "attempt", Integer.toString(attempt), "reason", reason));
    }

    private static String retryId(WorkflowState state, String taskId, int attempt) {
        return state.workflowId() + ":" + taskId + ":retry:" + attempt;
    }

    private static String eventId(WorkflowState state, String type, int attempt) {
        return state.workflowId() + ":" + type + ":" + attempt + ":" + state.stateRevision();
    }

    private static void validatePipeline(WorkflowState state, PipelineTasks pipeline) {
        if (state == null || pipeline == null) throw new IllegalArgumentException("state and pipeline are required");
        requireStage(state, pipeline.implementationTaskId(), AgentDefinition.Stage.IMPLEMENTATION);
        requireStage(state, pipeline.testTaskId(), AgentDefinition.Stage.TESTING);
        requireStage(state, pipeline.validationTaskId(), AgentDefinition.Stage.VALIDATION);
    }

    private static void requireStage(WorkflowState state, String taskId, AgentDefinition.Stage stage) {
        WorkflowState.Task task = state.task(taskId);
        if (task == null || task.stage() != stage) {
            throw new IllegalArgumentException("Pipeline task is missing or has the wrong stage: " + taskId);
        }
    }

    @FunctionalInterface
    public interface StageExecutor {
        AgentResult execute(String taskId, WorkflowState state, WorkflowState.RetryContext retryContext);

        static StageExecutor fromAgent(Agent agent) {
            return agent::execute;
        }
    }

    public record PipelineTasks(String implementationTaskId, String testTaskId, String validationTaskId) {
        public PipelineTasks {
            if (implementationTaskId == null || testTaskId == null || validationTaskId == null
                    || implementationTaskId.isBlank() || testTaskId.isBlank() || validationTaskId.isBlank()) {
                throw new IllegalArgumentException("all pipeline task IDs are required");
            }
        }
    }

    public enum ExecutionStatus { SUCCESS, REQUIRES_HUMAN_APPROVAL, BLOCKED, SAFE_STOP }

    public record ExecutionResult(ExecutionStatus status, WorkflowState workflowState) { }

    public enum FailureType {
        IMPLEMENTATION_FAILURE(true, false, false),
        TEST_FAILURE(true, false, false),
        CORRECTABLE_VALIDATION_FAILURE(true, false, false),
        BUILD_FAILURE(true, false, false),
        TRANSIENT_EXECUTION_ERROR(true, false, false),
        DATABASE_SCHEMA_APPROVAL_REQUIRED(false, true, false),
        BREAKING_API_CHANGE(false, true, false),
        MISSING_HUMAN_APPROVAL(false, true, false),
        SECURITY_POLICY_VIOLATION(false, false, true),
        CRITICAL_VALIDATION_FINDING(false, false, true),
        DESTRUCTIVE_OPERATION(false, false, false),
        AMBIGUOUS_REQUIREMENT(false, false, false),
        UNAUTHORIZED_FILE_DELETION(false, false, false),
        UNKNOWN_FAILURE(false, false, false);

        private final boolean retryable;
        private final boolean requiresApproval;
        private final boolean safeStops;

        FailureType(boolean retryable, boolean requiresApproval, boolean safeStops) {
            this.retryable = retryable;
            this.requiresApproval = requiresApproval;
            this.safeStops = safeStops;
        }

        public boolean retryable() { return retryable; }
        public boolean requiresApproval() { return requiresApproval; }
        public boolean safeStops() { return safeStops; }

        static FailureType fromCode(String code) {
            if (code == null) return UNKNOWN_FAILURE;
            try {
                return valueOf(code);
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN_FAILURE;
            }
        }
    }

    private enum Stage {
        IMPLEMENTATION("ImplementationAgent"), TEST("TestAgent"), VALIDATION("ValidationAgent");
        private final String agentName;
        Stage(String agentName) { this.agentName = agentName; }
    }

    private record Failure(FailureType type, String agentName, String reason,
                           List<String> failedTests, List<String> validationFindings) { }

    private record FailureHandling(WorkflowState state, WorkflowState.RetryContext retryContext,
                                   ExecutionResult terminal) { }
}
