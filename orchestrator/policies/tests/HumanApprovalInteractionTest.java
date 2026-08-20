package com.example.urlshortener.orchestrator.policies;

import com.example.urlshortener.orchestrator.agents.Agent;
import com.example.urlshortener.orchestrator.agents.AgentCatalog;
import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

/** JDK-only executable tests for the orchestrator-owned human approval interaction. */
public final class HumanApprovalInteractionTest {
    private static final ControlledAutonomyPolicy POLICY = new ControlledAutonomyPolicy();
    private static final HumanApprovalInteraction INTERACTION = new HumanApprovalInteraction();
    private static final Agent AGENT = AgentCatalog.requireByName("ImplementationAgent");
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static int assertions;

    private HumanApprovalInteractionTest() { }

    public static void main(String[] args) throws Exception {
        rendersHighRiskDatabasePromptAndWaits();
        approveRecordsScopedApprovalAndRequiresPolicyReevaluation();
        rejectBlocksTaskAndPausesWorkflow();
        modifyInvalidatesTaskAndStartsReplanning();
        consoleChoiceIsParsedAndInvalidInputFailsClosed();
        mediumRiskCannotOpenHumanApprovalInteraction();
        stalePromptCannotBeReused();
        System.out.println("Human approval interaction tests passed: " + assertions + " assertions");
    }

    private static void rendersHighRiskDatabasePromptAndWaits() {
        HumanApprovalInteraction.PendingApproval pending = request("approval-render");
        String rendered = INTERACTION.render(pending.prompt());

        assertContains(rendered, "AGENT: ImplementationAgent", "agent shown");
        assertContains(rendered, "Database migration detected:", "operation title shown");
        assertContains(rendered, "ALTER TABLE shortened_urls\nADD expires_at TIMESTAMP NULL;",
                "change preview shown");
        assertContains(rendered, "Risk level: HIGH", "risk shown");
        assertContains(rendered, "Schema modification may affect existing persistence behavior.",
                "reason shown");
        assertContains(rendered, "[Y] Approve\n[N] Reject\n[M] Modify", "choices shown");
        assertEquals(WorkflowState.WorkflowStatus.WAITING_APPROVAL,
                pending.state().status(), "workflow waits");
        assertEquals(WorkflowState.TaskStatus.WAITING_APPROVAL,
                pending.state().task("task-1").status(), "task waits");
        assertEquals(1L, pending.state().stateRevision(), "request increments revision");
        assertEquals("HUMAN_APPROVAL_REQUESTED",
                pending.state().auditEntries().get(0).type(), "request audited");
    }

    private static void approveRecordsScopedApprovalAndRequiresPolicyReevaluation() {
        HumanApprovalInteraction.PendingApproval pending = request("approval-approve");
        HumanApprovalInteraction.InteractionOutcome outcome = INTERACTION.recordResponse(
                pending.state(), pending.prompt(), response(
                        HumanApprovalInteraction.Choice.APPROVE,
                        "Reviewed migration and rollback plan.", null));

        assertEquals(HumanApprovalInteraction.OutcomeStatus.APPROVED,
                outcome.status(), "approved outcome");
        assertEquals(WorkflowState.WorkflowStatus.RUNNING, outcome.state().status(), "workflow resumes");
        assertEquals(WorkflowState.TaskStatus.READY,
                outcome.state().task("task-1").status(), "task ready");
        WorkflowState.Approval approval = outcome.state().approval("approval-approve");
        assertTrue(approval != null, "approval persisted");
        assertEquals(List.of("task-1"), approval.taskIds(), "approval task scope");
        assertEquals(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA.name(),
                approval.action(), "approval action scope");
        assertEquals(WorkflowState.ApprovalStatus.APPROVED,
                approval.status(), "approval status");
        assertEquals("human-42", lastAudit(outcome.state()).details().get("approverId"),
                "approver audited");

        ControlledAutonomyPolicy.Decision reevaluated = POLICY.evaluate(
                AGENT.definition(), outcome.state(), operation(), NOW.plusSeconds(61));
        assertTrue(reevaluated.executionAllowed(), "policy allows only after reevaluation");
        assertTrue(reevaluated.approvalSatisfied(), "policy sees exact approval");
        assertTrue(reevaluated.validationRequired(), "approved high risk still needs validation");
    }

    private static void rejectBlocksTaskAndPausesWorkflow() {
        HumanApprovalInteraction.PendingApproval pending = request("approval-reject");
        HumanApprovalInteraction.InteractionOutcome outcome = INTERACTION.recordResponse(
                pending.state(), pending.prompt(), response(
                        HumanApprovalInteraction.Choice.REJECT,
                        "Migration risk is not acceptable.", null));

        assertEquals(HumanApprovalInteraction.OutcomeStatus.REJECTED,
                outcome.status(), "rejected outcome");
        assertEquals(WorkflowState.WorkflowStatus.PAUSED, outcome.state().status(), "workflow paused");
        assertEquals(WorkflowState.TaskStatus.BLOCKED,
                outcome.state().task("task-1").status(), "task blocked");
        assertTrue(outcome.state().approval("approval-reject") == null,
                "rejection creates no effective approval");
        assertEquals("HUMAN_APPROVAL_REJECTED", lastAudit(outcome.state()).type(), "rejection audited");
        assertFalse(POLICY.evaluate(AGENT.definition(), outcome.state(), operation(),
                NOW.plusSeconds(61)).executionAllowed(), "rejected operation stays denied");
    }

    private static void modifyInvalidatesTaskAndStartsReplanning() {
        HumanApprovalInteraction.PendingApproval pending = request("approval-modify");
        String instructions = "Use an additive migration with a tested down script.";
        HumanApprovalInteraction.InteractionOutcome outcome = INTERACTION.recordResponse(
                pending.state(), pending.prompt(), response(
                        HumanApprovalInteraction.Choice.MODIFY,
                        "Rollback evidence is incomplete.", instructions));

        assertEquals(HumanApprovalInteraction.OutcomeStatus.MODIFICATION_REQUESTED,
                outcome.status(), "modify outcome");
        assertEquals(WorkflowState.WorkflowStatus.REPLANNING,
                outcome.state().status(), "workflow replans");
        assertEquals(WorkflowState.TaskStatus.INVALIDATED,
                outcome.state().task("task-1").status(), "old task invalidated");
        assertEquals(instructions, lastAudit(outcome.state()).details().get("modificationInstructions"),
                "instructions audited");
        assertTrue(outcome.state().approval("approval-modify") == null,
                "modify creates no approval");
        assertFalse(POLICY.evaluate(AGENT.definition(), outcome.state(), operation(),
                NOW.plusSeconds(61)).executionAllowed(), "modified operation stays denied");

        HumanApprovalInteraction.PendingApproval emptyModification = request("approval-modify-empty");
        assertThrows(IllegalArgumentException.class, () -> INTERACTION.recordResponse(
                emptyModification.state(),
                emptyModification.prompt(),
                response(HumanApprovalInteraction.Choice.MODIFY, "Please revise.", "")),
                "modify requires instructions");
    }

    private static void consoleChoiceIsParsedAndInvalidInputFailsClosed() throws Exception {
        HumanApprovalInteraction.PendingApproval pending = request("approval-console");
        StringWriter output = new StringWriter();
        HumanApprovalInteraction.Choice choice = INTERACTION.readChoice(
                pending.prompt(), new StringReader("y\n"), output);
        assertEquals(HumanApprovalInteraction.Choice.APPROVE, choice, "lowercase Y accepted");
        assertContains(output.toString(), "Approve?", "console renders prompt");
        assertEquals(HumanApprovalInteraction.Choice.REJECT,
                INTERACTION.parseChoice(" N "), "trimmed N accepted");
        assertEquals(HumanApprovalInteraction.Choice.MODIFY,
                INTERACTION.parseChoice("m"), "lowercase M accepted");
        assertThrows(IllegalArgumentException.class,
                () -> INTERACTION.parseChoice("yes"), "unexpected input fails closed");
    }

    private static void mediumRiskCannotOpenHumanApprovalInteraction() {
        WorkflowState state = state("approval-medium");
        ControlledAutonomyPolicy.OperationRequest medium =
                ControlledAutonomyPolicy.OperationRequest.standard(
                        "task-1", AgentDefinition.Action.MODIFY_SOURCE_CODE,
                        ControlledAutonomyPolicy.Environment.TEST, "Scoped code edit.");
        ControlledAutonomyPolicy.Decision decision = POLICY.evaluate(
                AGENT.definition(), state, medium, NOW);
        assertThrows(IllegalArgumentException.class, () -> INTERACTION.requestApproval(
                decision, medium, state, "approval-medium", AGENT.definition().name(),
                "Source edit detected:", "backend/Service.java", "Routine code edit.",
                NOW, NOW.plusSeconds(600)), "medium risk cannot request human approval");
    }

    private static void stalePromptCannotBeReused() {
        HumanApprovalInteraction.PendingApproval pending = request("approval-stale");
        HumanApprovalInteraction.InteractionOutcome first = INTERACTION.recordResponse(
                pending.state(), pending.prompt(), response(
                        HumanApprovalInteraction.Choice.APPROVE, "Approved once.", null));
        assertThrows(IllegalStateException.class, () -> INTERACTION.recordResponse(
                first.state(), pending.prompt(), response(
                        HumanApprovalInteraction.Choice.APPROVE, "Attempted reuse.", null)),
                "stale prompt rejected");

        HumanApprovalInteraction.PendingApproval expiring = request("approval-expired");
        HumanApprovalInteraction.HumanResponse late = new HumanApprovalInteraction.HumanResponse(
                "human-42", "DatabaseOwner", HumanApprovalInteraction.Choice.APPROVE,
                "Too late.", null, NOW.plusSeconds(601));
        assertThrows(IllegalStateException.class, () -> INTERACTION.recordResponse(
                expiring.state(), expiring.prompt(), late), "expired prompt rejected");
    }

    private static HumanApprovalInteraction.PendingApproval request(String approvalId) {
        WorkflowState state = state(approvalId);
        ControlledAutonomyPolicy.Decision decision = POLICY.evaluate(
                AGENT.definition(), state, operation(), NOW);
        return INTERACTION.requestApproval(
                decision,
                operation(),
                state,
                approvalId,
                AGENT.definition().name(),
                "Database migration detected:",
                "ALTER TABLE shortened_urls\nADD expires_at TIMESTAMP NULL;",
                "Schema modification may affect existing persistence behavior.",
                NOW,
                NOW.plusSeconds(600));
    }

    private static ControlledAutonomyPolicy.OperationRequest operation() {
        return ControlledAutonomyPolicy.OperationRequest.standard(
                "task-1",
                AgentDefinition.Action.CHANGE_DATABASE_SCHEMA,
                ControlledAutonomyPolicy.Environment.TEST,
                "Add optional URL expiration timestamp.");
    }

    private static WorkflowState state(String approvalId) {
        WorkflowState.Task task = new WorkflowState.Task(
                "task-1", AGENT.definition().stage(), AGENT.definition().name(),
                WorkflowState.TaskStatus.READY, List.of(), List.of(), List.of(approvalId), 0, 2);
        return WorkflowState.builder("workflow-1")
                .task(task)
                .availableContext(AGENT.definition().requiredWorkflowContext())
                .build();
    }

    private static HumanApprovalInteraction.HumanResponse response(
            HumanApprovalInteraction.Choice choice, String rationale, String instructions) {
        return new HumanApprovalInteraction.HumanResponse(
                "human-42", "DatabaseOwner", choice, rationale, instructions, NOW.plusSeconds(60));
    }

    private static WorkflowState.StateEvent lastAudit(WorkflowState state) {
        return state.auditEntries().get(state.auditEntries().size() - 1);
    }

    private static void assertTrue(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertContains(String actual, String expected, String message) {
        assertions++;
        if (!actual.contains(expected)) {
            throw new AssertionError(message + " expected fragment=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected, ThrowingAction action, String message) {
        assertions++;
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) return;
            throw new AssertionError(message + " wrong exception=" + actual, actual);
        }
        throw new AssertionError(message + " expected exception=" + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
