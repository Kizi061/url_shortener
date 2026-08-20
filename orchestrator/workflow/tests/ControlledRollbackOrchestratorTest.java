package com.example.urlshortener.orchestrator.workflow;

import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** JDK-only rollback control-plane tests; no Git or database command is executed. */
public final class ControlledRollbackOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static int assertions;

    private ControlledRollbackOrchestratorTest() { }

    public static void main(String[] args) {
        missingApprovalExecutesNothing();
        approvedRollbackRestoresInOrderAndRemainsSafeStopped();
        invalidCheckpointBlocksExecution();
        failedActionSafeStops();
        failedPostRollbackValidationSafeStops();
        schemaRollbackRequiresBackup();
        System.out.println("Controlled rollback orchestrator tests passed: " + assertions + " assertions");
    }

    private static void missingApprovalExecutesNothing() {
        RecordingExecutor executor = new RecordingExecutor(-1);
        ControlledRollbackOrchestrator.Result result = orchestrator(executor, true, true)
                .execute(state(false), request(actions()));
        assertEquals(ControlledRollbackOrchestrator.Status.REQUIRES_HUMAN_APPROVAL, result.status(), "approval status");
        assertEquals(0, executor.targets.size(), "no action before approval");
        assertEquals(WorkflowState.WorkflowStatus.WAITING_APPROVAL, result.workflowState().status(), "workflow waits");
    }

    private static void approvedRollbackRestoresInOrderAndRemainsSafeStopped() {
        RecordingExecutor executor = new RecordingExecutor(-1);
        ControlledRollbackOrchestrator.Result result = orchestrator(executor, true, true)
                .execute(state(true), request(actions()));
        assertEquals(ControlledRollbackOrchestrator.Status.ROLLED_BACK_SAFE_STOPPED, result.status(), "rollback success");
        assertEquals(List.of("src", "artifact"), executor.targets, "ordered actions");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, result.workflowState().status(), "remains stopped");
        assertEquals(WorkflowState.TaskStatus.ROLLED_BACK, result.workflowState().task("impl-1").status(), "task rolled back");
        assertTrue(result.workflowState().rollbacks().stream().anyMatch(e -> "SUCCEEDED".equals(e.type())), "success recorded");
        assertTrue(result.workflowState().auditEntries().stream().anyMatch(e -> "ROLLBACK_SUCCEEDED".equals(e.type())), "audit recorded");
    }

    private static void invalidCheckpointBlocksExecution() {
        RecordingExecutor executor = new RecordingExecutor(-1);
        ControlledRollbackOrchestrator.Result result = orchestrator(executor, false, true)
                .execute(state(true), request(actions()));
        assertEquals(ControlledRollbackOrchestrator.Status.BLOCKED, result.status(), "invalid checkpoint blocked");
        assertEquals(0, executor.targets.size(), "invalid checkpoint executes nothing");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, result.workflowState().status(), "invalid remains stopped");
    }

    private static void failedActionSafeStops() {
        RecordingExecutor executor = new RecordingExecutor(1);
        ControlledRollbackOrchestrator.Result result = orchestrator(executor, true, true)
                .execute(state(true), request(actions()));
        assertEquals(ControlledRollbackOrchestrator.Status.FAILED_SAFE_STOPPED, result.status(), "action failure");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, result.workflowState().status(), "failure safe-stop");
        assertEquals(List.of("src", "artifact"), executor.targets, "stops at failing action");
        assertTrue(result.workflowState().safeStopReason().contains("restore-2"), "failed action identified");
    }

    private static void failedPostRollbackValidationSafeStops() {
        RecordingExecutor executor = new RecordingExecutor(-1);
        ControlledRollbackOrchestrator.Result result = orchestrator(executor, true, false)
                .execute(state(true), request(actions()));
        assertEquals(ControlledRollbackOrchestrator.Status.FAILED_SAFE_STOPPED, result.status(), "validation failure");
        assertEquals(WorkflowState.WorkflowStatus.SAFE_STOPPED, result.workflowState().status(), "validation safe-stop");
        assertTrue(result.workflowState().safeStopReason().contains("validation failed"), "validation reason");
    }

    private static void schemaRollbackRequiresBackup() {
        ControlledRollbackOrchestrator.RollbackAction schema = new ControlledRollbackOrchestrator.RollbackAction(
                "schema-1", ControlledRollbackOrchestrator.ActionType.ROLLBACK_SCHEMA, "url_mapping", "key-schema");
        ControlledRollbackOrchestrator.Checkpoint checkpoint = new ControlledRollbackOrchestrator.Checkpoint(
                "cp-1", 5, "abc123", "snapshot://cp-1", "sha256:cp", null, true);
        ControlledRollbackOrchestrator.RollbackRequest request = new ControlledRollbackOrchestrator.RollbackRequest(
                "rb-1", "impl-1", "unsafe change", checkpoint, List.of(schema));
        assertThrows(() -> orchestrator(new RecordingExecutor(-1), true, true).execute(state(true), request),
                "schema backup required");
    }

    private static ControlledRollbackOrchestrator orchestrator(RecordingExecutor executor,
                                                               boolean checkpointValid, boolean validationPasses) {
        return new ControlledRollbackOrchestrator(
                checkpoint -> new ControlledRollbackOrchestrator.CheckResult(checkpointValid, "checkpoint check"),
                executor,
                (request, state) -> new ControlledRollbackOrchestrator.ValidationResult(
                        validationPasses, validationPasses ? "restored build and tests passed" : "validation failed"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WorkflowState state(boolean approved) {
        WorkflowState.Task task = new WorkflowState.Task("impl-1", AgentDefinition.Stage.IMPLEMENTATION,
                "ImplementationAgent", WorkflowState.TaskStatus.FAILED, List.of(), List.of(),
                List.of("rollback-approval"), 2, 2);
        WorkflowState.Builder builder = WorkflowState.builder("WF-1001")
                .status(WorkflowState.WorkflowStatus.SAFE_STOPPED).task(task);
        if (approved) {
            builder.approval(new WorkflowState.Approval("rollback-approval", List.of("impl-1"),
                    AgentDefinition.Action.EXECUTE_ROLLBACK.name(), WorkflowState.ApprovalStatus.APPROVED,
                    NOW.plusSeconds(600)));
        }
        return builder.build();
    }

    private static ControlledRollbackOrchestrator.RollbackRequest request(
            List<ControlledRollbackOrchestrator.RollbackAction> actions) {
        ControlledRollbackOrchestrator.Checkpoint checkpoint = new ControlledRollbackOrchestrator.Checkpoint(
                "cp-1", 5, "abc123", "snapshot://cp-1", "sha256:cp", "backup://mysql/cp-1", true);
        return new ControlledRollbackOrchestrator.RollbackRequest(
                "rb-1", "impl-1", "unsafe implementation", checkpoint, actions);
    }

    private static List<ControlledRollbackOrchestrator.RollbackAction> actions() {
        return List.of(
                new ControlledRollbackOrchestrator.RollbackAction("restore-1",
                        ControlledRollbackOrchestrator.ActionType.REVERT_COMMIT, "src", "key-1"),
                new ControlledRollbackOrchestrator.RollbackAction("restore-2",
                        ControlledRollbackOrchestrator.ActionType.RESTORE_ARTIFACT, "artifact", "key-2"));
    }

    private static final class RecordingExecutor implements ControlledRollbackOrchestrator.RollbackActionExecutor {
        private final int failAtIndex;
        private final List<String> targets = new ArrayList<>();
        private RecordingExecutor(int failAtIndex) { this.failAtIndex = failAtIndex; }
        @Override public ControlledRollbackOrchestrator.ActionResult execute(
                ControlledRollbackOrchestrator.RollbackAction action,
                ControlledRollbackOrchestrator.Checkpoint checkpoint, WorkflowState state) {
            targets.add(action.target());
            boolean success = targets.size() - 1 != failAtIndex;
            return new ControlledRollbackOrchestrator.ActionResult(success, success ? "restored" : "executor failed");
        }
    }

    private static void assertThrows(Runnable action, String message) {
        assertions++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static void assertTrue(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
