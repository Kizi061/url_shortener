package com.example.urlshortener.orchestrator.agents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, agent-facing projection of the canonical JSON workflow state.
 * Agents read this snapshot; only the orchestrator may persist a successor revision.
 */
public record WorkflowState(
        String workflowId,
        long stateRevision,
        WorkflowStatus status,
        long requirementVersion,
        long taskGraphVersion,
        Map<String, Task> tasks,
        Map<String, Artifact> artifacts,
        List<FileChange> changedFiles,
        List<Decision> decisions,
        List<Risk> risks,
        List<TestResult> testResults,
        List<Validation> validationResults,
        List<StateEvent> retries,
        List<StateEvent> failures,
        List<Approval> approvals,
        List<StateEvent> rollbacks,
        List<StateEvent> replanHistory,
        List<StateEvent> auditEntries,
        Map<String, Integer> retryCountByTask,
        List<RetryRecord> retryHistory,
        FailureContext lastFailure,
        String safeStopReason,
        String recommendedHumanAction,
        ReliabilityMetrics reliabilityMetrics,
        Set<AgentDefinition.Context> availableContext) {

    public WorkflowState {
        if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("workflowId is required");
        if (stateRevision < 0 || requirementVersion < 1 || taskGraphVersion < 1) {
            throw new IllegalArgumentException("state and version numbers are invalid");
        }
        if (status == null) throw new IllegalArgumentException("status is required");
        tasks = tasks == null ? Map.of() : Map.copyOf(tasks);
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        changedFiles = copy(changedFiles);
        decisions = copy(decisions);
        risks = copy(risks);
        testResults = copy(testResults);
        validationResults = copy(validationResults);
        retries = copy(retries);
        failures = copy(failures);
        approvals = copy(approvals);
        rollbacks = copy(rollbacks);
        replanHistory = copy(replanHistory);
        auditEntries = copy(auditEntries);
        retryCountByTask = retryCountByTask == null ? Map.of() : Map.copyOf(retryCountByTask);
        retryHistory = copy(retryHistory);
        reliabilityMetrics = reliabilityMetrics == null ? ReliabilityMetrics.empty() : reliabilityMetrics;
        availableContext = availableContext == null ? Set.of() : Set.copyOf(availableContext);
        tasks.forEach((id, task) -> {
            if (!id.equals(task.taskId())) throw new IllegalArgumentException("task map key must equal taskId");
        });
        artifacts.forEach((id, artifact) -> {
            if (!id.equals(artifact.artifactId())) {
                throw new IllegalArgumentException("artifact map key must equal artifactId");
            }
        });
    }

    public Task task(String taskId) {
        return tasks.get(taskId);
    }

    public Approval approval(String approvalId) {
        return approvals.stream().filter(value -> value.approvalId().equals(approvalId)).findFirst().orElse(null);
    }

    public static Builder builder(String workflowId) {
        return new Builder(workflowId);
    }

    public static Builder from(WorkflowState state) {
        return new Builder(state);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public enum WorkflowStatus {
        DRAFT, RUNNING, WAITING_APPROVAL, PAUSED, REPLANNING, ROLLING_BACK,
        SAFE_STOPPED, FAILED, COMPLETED, CANCELLED
    }

    public enum TaskStatus {
        PLANNED, READY, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, RETRYING,
        BLOCKED, SKIPPED, INVALIDATED, ROLLED_BACK, CANCELLED
    }

    public enum RequiredStatus { SUCCEEDED, APPROVED, COMPLETED }

    public enum ApprovalStatus { REQUESTED, APPROVED, REJECTED, EXPIRED, REVOKED }

    public record Task(
            String taskId,
            AgentDefinition.Stage stage,
            String assignedAgent,
            TaskStatus status,
            List<Dependency> dependencies,
            List<String> inputArtifactIds,
            List<String> requiredApprovalIds,
            int attemptCount,
            int maxAttempts) {
        public Task {
            requireText(taskId, "taskId");
            if (stage == null || status == null) throw new IllegalArgumentException("task stage and status are required");
            dependencies = copy(dependencies);
            inputArtifactIds = copy(inputArtifactIds);
            requiredApprovalIds = copy(requiredApprovalIds);
            if (attemptCount < 0 || maxAttempts < 1 || attemptCount > maxAttempts) {
                throw new IllegalArgumentException("task retry bounds are invalid");
            }
        }

        public Task withStatus(TaskStatus newStatus, int newAttemptCount) {
            return new Task(taskId, stage, assignedAgent, newStatus, dependencies,
                    inputArtifactIds, requiredApprovalIds, newAttemptCount, maxAttempts);
        }
    }

    public record Dependency(String taskId, RequiredStatus requiredStatus, List<String> requiredArtifactIds) {
        public Dependency {
            requireText(taskId, "dependency taskId");
            if (requiredStatus == null) throw new IllegalArgumentException("requiredStatus is required");
            requiredArtifactIds = copy(requiredArtifactIds);
        }
    }

    public record Approval(String approvalId, List<String> taskIds, String action,
                           ApprovalStatus status, Instant expiresAt) {
        public Approval {
            requireText(approvalId, "approvalId");
            taskIds = copy(taskIds);
            requireText(action, "approval action");
            if (status == null) throw new IllegalArgumentException("approval status is required");
        }

        public boolean isEffectiveFor(String taskId, Instant now) {
            return status == ApprovalStatus.APPROVED && taskIds.contains(taskId)
                    && (expiresAt == null || expiresAt.isAfter(now));
        }

        public boolean isEffectiveFor(String taskId, AgentDefinition.Action requestedAction, Instant now) {
            return isEffectiveFor(taskId, now) && action.equals(requestedAction.name());
        }
    }

    public record Artifact(String artifactId, String type, String location, String contentHash) {
        public Artifact {
            requireText(artifactId, "artifactId");
            requireText(type, "artifact type");
            requireText(location, "artifact location");
        }
    }

    public record FileChange(String path, String operation, String contentHash) {
        public FileChange {
            requireText(path, "file path");
            requireText(operation, "file operation");
        }
    }

    public record Decision(String decisionId, String decision, String rationale) {
        public Decision {
            requireText(decisionId, "decisionId");
            requireText(decision, "decision");
            requireText(rationale, "rationale");
        }
    }

    public record Risk(String riskId, String description, String severity, String mitigation) {
        public Risk {
            requireText(riskId, "riskId");
            requireText(description, "risk description");
            requireText(severity, "risk severity");
            requireText(mitigation, "risk mitigation");
        }
    }

    public record TestResult(String testRunId, String type, String command, String status,
                             Instant startedAt, Instant completedAt) {
        public TestResult {
            requireText(testRunId, "testRunId");
            requireText(type, "test type");
            requireText(command, "test command");
            requireText(status, "test status");
        }
    }

    public record Validation(String validationId, String category, String result,
                             String summary, List<String> evidenceArtifactIds) {
        public Validation {
            requireText(validationId, "validationId");
            requireText(category, "validation category");
            requireText(result, "validation result");
            requireText(summary, "validation summary");
            evidenceArtifactIds = copy(evidenceArtifactIds);
        }
    }

    public record StateEvent(String eventId, String type, Instant occurredAt, Map<String, String> details) {
        public StateEvent {
            requireText(eventId, "eventId");
            requireText(type, "event type");
            if (occurredAt == null) throw new IllegalArgumentException("event timestamp is required");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record FailureContext(
            String workflowId,
            String taskId,
            String agentName,
            int attemptNumber,
            String failureType,
            String failureReason,
            List<String> failedTests,
            List<String> validationFindings,
            List<FileChange> previousChangedFiles,
            Instant timestamp) {
        public FailureContext {
            requireText(workflowId, "failure workflowId");
            requireText(taskId, "failure taskId");
            requireText(agentName, "failure agentName");
            requireText(failureType, "failure type");
            requireText(failureReason, "failure reason");
            if (attemptNumber < 1 || timestamp == null) {
                throw new IllegalArgumentException("failure attempt and timestamp are required");
            }
            failedTests = copy(failedTests);
            validationFindings = copy(validationFindings);
            previousChangedFiles = copy(previousChangedFiles);
        }
    }

    public record RetryContext(
            int attempt,
            FailureContext previousFailure,
            List<String> failedTests,
            List<String> validationFindings,
            List<FileChange> previousChangedFiles) {
        public RetryContext {
            if (attempt < 1) throw new IllegalArgumentException("retry attempt must be positive");
            failedTests = copy(failedTests);
            validationFindings = copy(validationFindings);
            previousChangedFiles = copy(previousChangedFiles);
            if (attempt > 1 && previousFailure == null) {
                throw new IllegalArgumentException("a retry requires previous failure context");
            }
        }

        public static RetryContext firstAttempt() {
            return new RetryContext(1, null, List.of(), List.of(), List.of());
        }

        public static RetryContext fromFailure(int attempt, FailureContext failure) {
            return new RetryContext(attempt, failure, failure.failedTests(),
                    failure.validationFindings(), failure.previousChangedFiles());
        }
    }

    public enum RetryOutcome { SCHEDULED, SUCCEEDED, FAILED }

    public record RetryRecord(
            String retryId,
            String taskId,
            int attempt,
            FailureContext previousFailure,
            RetryOutcome outcome,
            Instant scheduledAt,
            Instant completedAt) {
        public RetryRecord {
            requireText(retryId, "retryId");
            requireText(taskId, "retry taskId");
            if (attempt < 2 || previousFailure == null || outcome == null || scheduledAt == null) {
                throw new IllegalArgumentException("complete retry metadata is required");
            }
            if (completedAt != null && completedAt.isBefore(scheduledAt)) {
                throw new IllegalArgumentException("retry completion cannot precede scheduling");
            }
        }

        public RetryRecord complete(RetryOutcome finalOutcome, Instant completedAt) {
            return new RetryRecord(retryId, taskId, attempt, previousFailure,
                    finalOutcome, scheduledAt, completedAt);
        }
    }

    public record ReliabilityMetrics(
            long totalRetries,
            Map<String, Integer> retriesPerTask,
            long successfulRetries,
            double retrySuccessRate,
            long tasksRecoveredAfterRetry,
            long safeStopCount,
            long meanTimeToRecoveryMillis) {
        public ReliabilityMetrics {
            retriesPerTask = retriesPerTask == null ? Map.of() : Map.copyOf(retriesPerTask);
            if (totalRetries < 0 || successfulRetries < 0 || tasksRecoveredAfterRetry < 0
                    || safeStopCount < 0 || meanTimeToRecoveryMillis < 0
                    || retrySuccessRate < 0 || retrySuccessRate > 1) {
                throw new IllegalArgumentException("reliability metrics cannot be negative or out of range");
            }
        }

        public static ReliabilityMetrics empty() {
            return new ReliabilityMetrics(0, Map.of(), 0, 0, 0, 0, 0);
        }

        public ReliabilityMetrics retryScheduled(String taskId) {
            Map<String, Integer> counts = new LinkedHashMap<>(retriesPerTask);
            counts.merge(taskId, 1, Integer::sum);
            long retries = totalRetries + 1;
            return new ReliabilityMetrics(retries, counts, successfulRetries,
                    ratio(successfulRetries, retries), tasksRecoveredAfterRetry,
                    safeStopCount, meanTimeToRecoveryMillis);
        }

        public ReliabilityMetrics recovered(long recoveryMillis) {
            long successes = successfulRetries + 1;
            long recovered = tasksRecoveredAfterRetry + 1;
            long mean = ((meanTimeToRecoveryMillis * tasksRecoveredAfterRetry) + recoveryMillis) / recovered;
            return new ReliabilityMetrics(totalRetries, retriesPerTask, successes,
                    ratio(successes, totalRetries), recovered, safeStopCount, mean);
        }

        public ReliabilityMetrics safeStopped() {
            return new ReliabilityMetrics(totalRetries, retriesPerTask, successfulRetries,
                    ratio(successfulRetries, totalRetries), tasksRecoveredAfterRetry,
                    safeStopCount + 1, meanTimeToRecoveryMillis);
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0 ? 0 : (double) numerator / denominator;
        }
    }

    public static final class Builder {
        private final String workflowId;
        private long stateRevision;
        private WorkflowStatus status = WorkflowStatus.RUNNING;
        private long requirementVersion = 1;
        private long taskGraphVersion = 1;
        private final Map<String, Task> tasks = new LinkedHashMap<>();
        private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
        private final List<FileChange> changedFiles = new ArrayList<>();
        private final List<Approval> approvals = new ArrayList<>();
        private final List<StateEvent> failures = new ArrayList<>();
        private final List<StateEvent> rollbackEvents = new ArrayList<>();
        private final List<StateEvent> auditEntries = new ArrayList<>();
        private final Map<String, Integer> retryCountByTask = new LinkedHashMap<>();
        private final List<RetryRecord> retryHistory = new ArrayList<>();
        private FailureContext lastFailure;
        private String safeStopReason;
        private String recommendedHumanAction;
        private ReliabilityMetrics reliabilityMetrics = ReliabilityMetrics.empty();
        private Set<AgentDefinition.Context> context = Set.of();
        private WorkflowState source;

        private Builder(String workflowId) { this.workflowId = workflowId; }

        private Builder(WorkflowState state) {
            source = state;
            workflowId = state.workflowId;
            stateRevision = state.stateRevision;
            status = state.status;
            requirementVersion = state.requirementVersion;
            taskGraphVersion = state.taskGraphVersion;
            tasks.putAll(state.tasks);
            artifacts.putAll(state.artifacts);
            changedFiles.addAll(state.changedFiles);
            approvals.addAll(state.approvals);
            failures.addAll(state.failures);
            rollbackEvents.addAll(state.rollbacks);
            auditEntries.addAll(state.auditEntries);
            retryCountByTask.putAll(state.retryCountByTask);
            retryHistory.addAll(state.retryHistory);
            lastFailure = state.lastFailure;
            safeStopReason = state.safeStopReason;
            recommendedHumanAction = state.recommendedHumanAction;
            reliabilityMetrics = state.reliabilityMetrics;
            context = state.availableContext;
        }

        public Builder stateRevision(long value) { stateRevision = value; return this; }
        public Builder status(WorkflowStatus value) { status = value; return this; }
        public Builder requirementVersion(long value) { requirementVersion = value; return this; }
        public Builder taskGraphVersion(long value) { taskGraphVersion = value; return this; }
        public Builder task(Task value) { tasks.put(value.taskId(), value); return this; }
        public Builder artifact(Artifact value) { artifacts.put(value.artifactId(), value); return this; }
        public Builder changedFile(FileChange value) { changedFiles.add(value); return this; }
        public Builder approval(Approval value) { approvals.add(value); return this; }
        public Builder failure(StateEvent value) { failures.add(value); return this; }
        public Builder rollbackEvent(StateEvent value) { rollbackEvents.add(value); return this; }
        public Builder auditEvent(StateEvent value) { auditEntries.add(value); return this; }
        public Builder retryCount(String taskId, int value) { retryCountByTask.put(taskId, value); return this; }
        public Builder retryRecord(RetryRecord value) { retryHistory.add(value); return this; }
        public Builder retryHistory(List<RetryRecord> values) { retryHistory.clear(); retryHistory.addAll(values); return this; }
        public Builder lastFailure(FailureContext value) { lastFailure = value; return this; }
        public Builder safeStop(String reason, String humanAction) { safeStopReason = reason; recommendedHumanAction = humanAction; return this; }
        public Builder reliabilityMetrics(ReliabilityMetrics value) { reliabilityMetrics = value; return this; }
        public Builder availableContext(Set<AgentDefinition.Context> value) { context = Set.copyOf(value); return this; }

        public WorkflowState build() {
            List<FileChange> changed = List.copyOf(changedFiles);
            List<Decision> decisions = source == null ? List.of() : source.decisions;
            List<Risk> risks = source == null ? List.of() : source.risks;
            List<TestResult> tests = source == null ? List.of() : source.testResults;
            List<Validation> validations = source == null ? List.of() : source.validationResults;
            List<StateEvent> retries = source == null ? List.of() : source.retries;
            List<StateEvent> replans = source == null ? List.of() : source.replanHistory;
            return new WorkflowState(workflowId, stateRevision, status, requirementVersion, taskGraphVersion,
                    tasks, artifacts, changed, decisions, risks, tests, validations, retries, failures,
                    approvals, rollbackEvents, replans, auditEntries, retryCountByTask, retryHistory,
                    lastFailure, safeStopReason, recommendedHumanAction, reliabilityMetrics, context);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
