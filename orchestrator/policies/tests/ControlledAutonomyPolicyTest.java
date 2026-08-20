package com.example.urlshortener.orchestrator.policies;

import com.example.urlshortener.orchestrator.agents.Agent;
import com.example.urlshortener.orchestrator.agents.AgentCatalog;
import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.AgentResult;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.time.Instant;
import java.util.List;

/** JDK-only executable policy tests; throws AssertionError on failure. */
public final class ControlledAutonomyPolicyTest {
    private static final ControlledAutonomyPolicy POLICY = new ControlledAutonomyPolicy();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static int assertions;

    private ControlledAutonomyPolicyTest() { }

    public static void main(String[] args) {
        lowRiskRunsAutonomously();
        mediumRiskRequiresValidationButNotApproval();
        mediumRiskSuccessWithoutEvidenceIsRejected();
        highRiskWaitsForExactApproval();
        exactApprovalAllowsHighRiskExecution();
        wrongOrExpiredApprovalDoesNotAuthorizeAction();
        contextualEscalationUsesHighestRisk();
        prohibitedAgentActionRemainsProhibited();
        auditRequirementsAreComplete();
        System.out.println("Controlled-autonomy policy tests passed: " + assertions + " assertions");
    }

    private static void lowRiskRunsAutonomously() {
        Agent agent = AgentCatalog.requireByName("TestAgent");
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.CREATE_TESTS, ControlledAutonomyPolicy.Environment.TEST));
        assertEquals(ControlledAutonomyPolicy.RiskLevel.LOW, decision.riskLevel(), "low risk");
        assertEquals(ControlledAutonomyPolicy.AutonomyLevel.AUTONOMOUS, decision.autonomyLevel(), "low autonomy");
        assertTrue(decision.executionAllowed(), "low risk executes");
        assertFalse(decision.validationRequired(), "low risk validation optional");
        assertFalse(decision.humanApprovalRequired(), "low risk no approval");
    }

    private static void mediumRiskRequiresValidationButNotApproval() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.MODIFY_SOURCE_CODE, ControlledAutonomyPolicy.Environment.TEST));
        assertEquals(ControlledAutonomyPolicy.RiskLevel.MEDIUM, decision.riskLevel(), "medium risk");
        assertEquals(ControlledAutonomyPolicy.AutonomyLevel.AUTONOMOUS_WITH_VALIDATION,
                decision.autonomyLevel(), "medium autonomy");
        assertTrue(decision.executionAllowed(), "medium executes");
        assertTrue(decision.validationRequired(), "medium validation mandatory");
        assertFalse(decision.humanApprovalRequired(), "medium no approval");
    }

    private static void mediumRiskSuccessWithoutEvidenceIsRejected() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.MODIFY_SOURCE_CODE, ControlledAutonomyPolicy.Environment.TEST));
        AgentResult withoutEvidence = success(agent, List.of());
        assertFalse(POLICY.validateResult(decision, withoutEvidence).accepted(),
                "medium success without evidence rejected");
        WorkflowState.Validation validation = new WorkflowState.Validation(
                "validation-1", "TEST", "PASSED", "Relevant tests passed.", List.of());
        assertTrue(POLICY.validateResult(decision, success(agent, List.of(validation))).accepted(),
                "medium success with evidence accepted");
    }

    private static void highRiskWaitsForExactApproval() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        WorkflowState state = state(agent, List.of("approval-1"), List.of());
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state,
                request(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA,
                        ControlledAutonomyPolicy.Environment.TEST));
        assertEquals(ControlledAutonomyPolicy.RiskLevel.HIGH, decision.riskLevel(), "high risk");
        assertEquals(ControlledAutonomyPolicy.AutonomyLevel.HUMAN_APPROVAL_REQUIRED,
                decision.autonomyLevel(), "high autonomy");
        assertFalse(decision.executionAllowed(), "high denied without approval");
        assertTrue(decision.humanApprovalRequired(), "high approval required");
        assertFalse(decision.approvalSatisfied(), "high approval not satisfied");
    }

    private static void exactApprovalAllowsHighRiskExecution() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        WorkflowState.Approval approval = approval("approval-1",
                AgentDefinition.Action.CHANGE_DATABASE_SCHEMA, NOW.plusSeconds(600));
        ControlledAutonomyPolicy.Decision decision = evaluate(agent,
                state(agent, List.of("approval-1"), List.of(approval)),
                request(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA,
                        ControlledAutonomyPolicy.Environment.TEST));
        assertTrue(decision.executionAllowed(), "approved high risk executes");
        assertTrue(decision.approvalSatisfied(), "approval satisfied");
        assertTrue(decision.validationRequired(), "high risk validation mandatory");
    }

    private static void wrongOrExpiredApprovalDoesNotAuthorizeAction() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        WorkflowState.Approval wrongAction = approval("approval-1",
                AgentDefinition.Action.CHANGE_PUBLIC_API, NOW.plusSeconds(600));
        ControlledAutonomyPolicy.Decision wrongDecision = evaluate(agent,
                state(agent, List.of("approval-1"), List.of(wrongAction)),
                request(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA,
                        ControlledAutonomyPolicy.Environment.TEST));
        assertFalse(wrongDecision.executionAllowed(), "wrong action approval rejected");

        WorkflowState.Approval expired = approval("approval-1",
                AgentDefinition.Action.CHANGE_DATABASE_SCHEMA, NOW.minusSeconds(1));
        ControlledAutonomyPolicy.Decision expiredDecision = evaluate(agent,
                state(agent, List.of("approval-1"), List.of(expired)),
                request(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA,
                        ControlledAutonomyPolicy.Environment.TEST));
        assertFalse(expiredDecision.executionAllowed(), "expired approval rejected");
    }

    private static void contextualEscalationUsesHighestRisk() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        ControlledAutonomyPolicy.Decision productionConfig = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.MODIFY_CONFIGURATION,
                        ControlledAutonomyPolicy.Environment.PRODUCTION));
        assertEquals(ControlledAutonomyPolicy.RiskLevel.HIGH, productionConfig.riskLevel(),
                "production config escalates");
        assertFalse(productionConfig.executionAllowed(), "production config needs approval");

        ControlledAutonomyPolicy.OperationRequest patch = new ControlledAutonomyPolicy.OperationRequest(
                "task-1", AgentDefinition.Action.ADD_OR_UPDATE_DEPENDENCY,
                ControlledAutonomyPolicy.Environment.TEST, ControlledAutonomyPolicy.DependencyChange.PATCH,
                false, false, false, "Approved patch upgrade.");
        ControlledAutonomyPolicy.OperationRequest major = new ControlledAutonomyPolicy.OperationRequest(
                "task-1", AgentDefinition.Action.ADD_OR_UPDATE_DEPENDENCY,
                ControlledAutonomyPolicy.Environment.TEST, ControlledAutonomyPolicy.DependencyChange.MAJOR,
                false, false, false, "Proposed major upgrade.");
        assertEquals(ControlledAutonomyPolicy.RiskLevel.MEDIUM,
                evaluate(agent, state(agent, List.of(), List.of()), patch).riskLevel(),
                "patch dependency medium");
        assertEquals(ControlledAutonomyPolicy.RiskLevel.HIGH,
                evaluate(agent, state(agent, List.of(), List.of()), major).riskLevel(),
                "major dependency high");
    }

    private static void prohibitedAgentActionRemainsProhibited() {
        Agent agent = AgentCatalog.requireByName("TestAgent");
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.DELETE_FILE, ControlledAutonomyPolicy.Environment.TEST));
        assertEquals(ControlledAutonomyPolicy.AutonomyLevel.PROHIBITED,
                decision.autonomyLevel(), "agent prohibition wins");
        assertFalse(decision.executionAllowed(), "prohibited action denied");
    }

    private static void auditRequirementsAreComplete() {
        Agent agent = AgentCatalog.requireByName("DocumentationAgent");
        ControlledAutonomyPolicy.Decision decision = evaluate(agent, state(agent, List.of(), List.of()),
                request(AgentDefinition.Action.CREATE_DOCUMENTATION,
                        ControlledAutonomyPolicy.Environment.LOCAL));
        assertEquals(ControlledAutonomyPolicy.AuditItem.values().length,
                decision.requiredAuditItems().size(), "all audit items required");
    }

    private static ControlledAutonomyPolicy.Decision evaluate(
            Agent agent, WorkflowState state, ControlledAutonomyPolicy.OperationRequest request) {
        return POLICY.evaluate(agent.definition(), state, request, NOW);
    }

    private static ControlledAutonomyPolicy.OperationRequest request(
            AgentDefinition.Action action, ControlledAutonomyPolicy.Environment environment) {
        return ControlledAutonomyPolicy.OperationRequest.standard(
                "task-1", action, environment, "Policy test operation.");
    }

    private static WorkflowState state(Agent agent, List<String> approvalIds,
                                       List<WorkflowState.Approval> approvals) {
        WorkflowState.Task task = new WorkflowState.Task(
                "task-1", agent.definition().stage(), agent.definition().name(), WorkflowState.TaskStatus.READY,
                List.of(), List.of(), approvalIds, 0, 2);
        WorkflowState.Builder builder = WorkflowState.builder("workflow-1").task(task)
                .availableContext(agent.definition().requiredWorkflowContext());
        approvals.forEach(builder::approval);
        return builder.build();
    }

    private static WorkflowState.Approval approval(String id, AgentDefinition.Action action, Instant expiresAt) {
        return new WorkflowState.Approval(id, List.of("task-1"), action.name(),
                WorkflowState.ApprovalStatus.APPROVED, expiresAt);
    }

    private static AgentResult success(Agent agent, List<WorkflowState.Validation> validations) {
        return new AgentResult(agent.definition().name(), "task-1", AgentResult.Status.SUCCEEDED,
                "Operation completed.", List.of(), List.of(), List.of(), validations,
                false, null, NOW, NOW.plusSeconds(1));
    }

    private static void assertTrue(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
