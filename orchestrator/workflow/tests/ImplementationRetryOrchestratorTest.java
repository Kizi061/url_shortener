package com.example.urlshortener.orchestrator.workflow;

import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.AgentResult;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JDK-only executable unit tests for orchestrator-owned implementation retries. */
public final class ImplementationRetryOrchestratorTest {
    private static final Instant BASE_TIME = Instant.parse("2026-08-20T12:00:00Z");
    private static final ImplementationRetryOrchestrator.PipelineTasks PIPELINE =
            new ImplementationRetryOrchestrator.PipelineTasks("impl-1", "test-1", "validation-1");
    private static int assertions;

    private ImplementationRetryOrchestratorTest() { }

    public static void main(String[] args) throws Exception {
        configurationIsLoaded();
        implementationPassesFirstAttempt();
        validationFailsOnceThenRetrySucceeds();
        implementationFailsTwiceAndSafeStops();
        missingApprovalDoesNotRetry();
        securityViolationDoesNotRetry();
        successfulRetryPersistsHistoryAndMttr();
        System.out.println("Implementation retry orchestrator tests passed: " + assertions + " assertions");
    }

    private static void configurationIsLoaded() throws Exception {
        RetryConfiguration configuration = RetryConfiguration.load(
                Path.of("orchestrator/workflow/retry-config.yaml"));
        assertEquals(2, configuration.implementationMaxAttempts(), "configured attempts");
    }

    private static void implementationPassesFirstAttempt() {
        ScriptedStage implementation = stage(success("ImplementationAgent"));
        ScriptedStage test = stage(success("TestAgent"));
        ScriptedStage validation = stage(success("ValidationAgent"));
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, test, validation).execute(initialState(), PIPELINE);

        assertEquals(ImplementationRetryOrchestrator.ExecutionStatus.SUCCESS, result.status(), "first pass status");
        assertEquals(1, implementation.calls, "implementation first-pass calls");
        assertEquals(1, test.calls, "test first-pass calls");
        assertEquals(1, validation.calls, "validation first-pass calls");
        assertEquals(0L, result.workflowState().reliabilityMetrics().totalRetries(), "no retry metric");
        assertTrue(result.workflowState().retryHistory().isEmpty(), "no retry history");
        assertEquals(WorkflowState.TaskStatus.SUCCEEDED,
                result.workflowState().task(PIPELINE.implementationTaskId()).status(), "task succeeds");
    }

    private static void validationFailsOnceThenRetrySucceeds() {
        ScriptedStage implementation = stage(success("ImplementationAgent"), success("ImplementationAgent"));
        ScriptedStage test = stage(success("TestAgent"), success("TestAgent"));
        ScriptedStage validation = stage(
                failure("ValidationAgent",
                        ImplementationRetryOrchestrator.FailureType.CORRECTABLE_VALIDATION_FAILURE,
                        "URL redirect behavior is incorrect.", "Redirect criterion failed."),
                success("ValidationAgent"));
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, test, validation).execute(initialState(), PIPELINE);

        assertEquals(ImplementationRetryOrchestrator.ExecutionStatus.SUCCESS, result.status(), "recovery status");
        assertEquals(2, implementation.calls, "implementation reruns");
        assertEquals(2, test.calls, "test reruns");
        assertEquals(2, validation.calls, "validation reruns");
        assertEquals(1, result.workflowState().retryCountByTask().get("impl-1"), "one retry");
        assertEquals(WorkflowState.TaskStatus.SUCCEEDED,
                result.workflowState().task("impl-1").status(), "recovered task succeeds");
        WorkflowState.RetryContext correction = implementation.contexts.get(1);
        assertEquals(2, correction.attempt(), "retry attempt number");
        assertEquals("impl-1", correction.previousFailure().taskId(), "retry remains same task");
        assertEquals("ValidationAgent", correction.previousFailure().agentName(), "validation failure preserved");
        assertTrue(correction.validationFindings().contains("Redirect criterion failed."),
                "validation finding passed to implementation");
    }

    private static void implementationFailsTwiceAndSafeStops() {
        ScriptedStage implementation = stage(
                failure("ImplementationAgent", ImplementationRetryOrchestrator.FailureType.BUILD_FAILURE,
                        "Compilation failed.", "Compiler error."),
                failure("ImplementationAgent", ImplementationRetryOrchestrator.FailureType.BUILD_FAILURE,
                        "Compilation still fails.", "Compiler error remains."));
        ScriptedStage test = stage();
        ScriptedStage validation = stage();
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, test, validation).execute(initialState(), PIPELINE);

        WorkflowState state = result.workflowState();
        assertEquals(ImplementationRetryOrchestrator.ExecutionStatus.SAFE_STOP, result.status(), "exhausted status");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, state.status(), "workflow safe-stopped");
        assertEquals(WorkflowState.TaskStatus.FAILED, state.task("impl-1").status(), "task failed");
        assertEquals(2, state.task("impl-1").attemptCount(), "two total attempts");
        assertEquals(2, implementation.calls, "no third implementation attempt");
        assertEquals(0, test.calls, "dependent tests not continued");
        assertEquals(0, validation.calls, "dependent validation not continued");
        assertEquals(1, state.retryHistory().size(), "complete retry history");
        assertEquals(WorkflowState.RetryOutcome.FAILED, state.retryHistory().get(0).outcome(), "retry failed");
        assertTrue(state.safeStopReason().contains("2 attempts"), "final reason records attempts");
        assertTrue(!state.recommendedHumanAction().isBlank(), "human action recorded");
    }

    private static void missingApprovalDoesNotRetry() {
        ScriptedStage implementation = stage(waitingApproval("ImplementationAgent"));
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, stage(), stage()).execute(initialState(), PIPELINE);

        WorkflowState state = result.workflowState();
        assertEquals(ImplementationRetryOrchestrator.ExecutionStatus.REQUIRES_HUMAN_APPROVAL,
                result.status(), "approval outcome");
        assertEquals(WorkflowState.WorkflowStatus.WAITING_APPROVAL, state.status(), "workflow waits");
        assertEquals(WorkflowState.TaskStatus.WAITING_APPROVAL, state.task("impl-1").status(), "task waits");
        assertEquals(0L, state.reliabilityMetrics().totalRetries(), "approval failure not retried");
        assertTrue(state.retryHistory().isEmpty(), "approval failure has no retry record");
    }

    private static void securityViolationDoesNotRetry() {
        ScriptedStage implementation = stage(success("ImplementationAgent"));
        ScriptedStage test = stage(success("TestAgent"));
        ScriptedStage validation = stage(failure("ValidationAgent",
                ImplementationRetryOrchestrator.FailureType.SECURITY_POLICY_VIOLATION,
                "Authorization policy violation.", "Critical authorization finding."));
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, test, validation).execute(initialState(), PIPELINE);

        assertEquals(ImplementationRetryOrchestrator.ExecutionStatus.SAFE_STOP, result.status(), "security stop");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, result.workflowState().status(),
                "security workflow safe-stop");
        assertEquals(1, implementation.calls, "security no implementation retry");
        assertEquals(0L, result.workflowState().reliabilityMetrics().totalRetries(), "security no retry metric");
        assertEquals(1L, result.workflowState().reliabilityMetrics().safeStopCount(), "security safe-stop metric");
    }

    private static void successfulRetryPersistsHistoryAndMttr() {
        ScriptedStage implementation = stage(success("ImplementationAgent"), success("ImplementationAgent"));
        ScriptedStage test = stage(
                failure("TestAgent", ImplementationRetryOrchestrator.FailureType.TEST_FAILURE,
                        "Unit test failure.", "ShortUrlServiceTest failed."),
                success("TestAgent"));
        ScriptedStage validation = stage(success("ValidationAgent"));
        ImplementationRetryOrchestrator.ExecutionResult result = orchestrator(
                implementation, test, validation).execute(initialState(), PIPELINE);

        WorkflowState state = result.workflowState();
        assertEquals(1L, state.reliabilityMetrics().totalRetries(), "total retries metric");
        assertEquals(1, state.reliabilityMetrics().retriesPerTask().get("impl-1"), "retries per task metric");
        assertEquals(1L, state.reliabilityMetrics().successfulRetries(), "successful retries metric");
        assertEquals(1.0, state.reliabilityMetrics().retrySuccessRate(), "retry success rate");
        assertEquals(1L, state.reliabilityMetrics().tasksRecoveredAfterRetry(), "recovered tasks metric");
        assertTrue(state.reliabilityMetrics().meanTimeToRecoveryMillis() > 0, "MTTR recorded");
        assertEquals(WorkflowState.RetryOutcome.SUCCEEDED, state.retryHistory().get(0).outcome(),
                "retry history recovered");
        assertTrue(state.auditEntries().stream().anyMatch(event -> "RETRY".equals(event.type())),
                "retry audit event recorded");
        assertTrue(state.auditEntries().stream().anyMatch(event -> "RECOVERED".equals(event.type())),
                "recovery audit event recorded");
        assertEquals(List.of(new WorkflowState.FileChange("backend/src/ShortUrlService.java", "MODIFY", "abc")),
                implementation.contexts.get(1).previousChangedFiles(), "previous changed files preserved");
    }

    private static ImplementationRetryOrchestrator orchestrator(
            ScriptedStage implementation, ScriptedStage test, ScriptedStage validation) {
        return new ImplementationRetryOrchestrator(new RetryConfiguration(2), implementation, test, validation,
                new TickClock(BASE_TIME));
    }

    private static WorkflowState initialState() {
        WorkflowState.Task implementation = task("impl-1", AgentDefinition.Stage.IMPLEMENTATION,
                "ImplementationAgent");
        WorkflowState.Task test = task("test-1", AgentDefinition.Stage.TESTING, "TestAgent");
        WorkflowState.Task validation = task("validation-1", AgentDefinition.Stage.VALIDATION, "ValidationAgent");
        return WorkflowState.builder("WF-1001")
                .task(implementation).task(test).task(validation)
                .changedFile(new WorkflowState.FileChange("backend/src/ShortUrlService.java", "MODIFY", "abc"))
                .build();
    }

    private static WorkflowState.Task task(String id, AgentDefinition.Stage stage, String agent) {
        return new WorkflowState.Task(id, stage, agent, WorkflowState.TaskStatus.READY,
                List.of(), List.of(), List.of(), 0, 2);
    }

    private static ScriptedStage stage(AgentResult... results) {
        return new ScriptedStage(List.of(results));
    }

    private static AgentResult success(String agent) {
        return new AgentResult(agent, taskIdFor(agent), AgentResult.Status.SUCCEEDED, "Succeeded.",
                List.of(), List.of(), List.of(), List.of(), false, null, BASE_TIME, BASE_TIME.plusSeconds(1));
    }

    private static AgentResult failure(String agent, ImplementationRetryOrchestrator.FailureType type,
                                       String reason, String finding) {
        WorkflowState.Validation validation = new WorkflowState.Validation(
                "finding-1", "QUALITY", "FAILED", finding, List.of());
        AgentResult.AgentError error = new AgentResult.AgentError(type.name(), reason, type.retryable(), Map.of());
        return new AgentResult(agent, taskIdFor(agent), AgentResult.Status.FAILED, reason,
                List.of(), List.of(), List.of(), List.of(validation), false, error,
                BASE_TIME, BASE_TIME.plusSeconds(1));
    }

    private static AgentResult waitingApproval(String agent) {
        AgentResult.AgentError error = new AgentResult.AgentError("MISSING_HUMAN_APPROVAL",
                "Design approval is missing.", false, Map.of());
        return new AgentResult(agent, taskIdFor(agent), AgentResult.Status.WAITING_APPROVAL,
                "Approval required.", List.of(), List.of(), List.of(), List.of(), true, error,
                BASE_TIME, BASE_TIME.plusSeconds(1));
    }

    private static String taskIdFor(String agent) {
        return switch (agent) {
            case "ImplementationAgent" -> "impl-1";
            case "TestAgent" -> "test-1";
            case "ValidationAgent" -> "validation-1";
            default -> throw new IllegalArgumentException("unknown agent");
        };
    }

    private static final class ScriptedStage implements ImplementationRetryOrchestrator.StageExecutor {
        private final List<AgentResult> results;
        private final List<WorkflowState.RetryContext> contexts = new ArrayList<>();
        private int calls;

        private ScriptedStage(List<AgentResult> results) { this.results = results; }

        @Override
        public AgentResult execute(String taskId, WorkflowState state, WorkflowState.RetryContext retryContext) {
            contexts.add(retryContext);
            if (calls >= results.size()) throw new AssertionError("Unexpected stage invocation for " + taskId);
            return results.get(calls++);
        }
    }

    private static final class TickClock extends Clock {
        private Instant current;

        private TickClock(Instant current) { this.current = current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            Instant value = current;
            current = current.plusSeconds(1);
            return value;
        }
    }

    private static void assertTrue(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
