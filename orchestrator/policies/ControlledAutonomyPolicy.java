package com.example.urlshortener.orchestrator.policies;

import com.example.urlshortener.orchestrator.agents.AgentDefinition;
import com.example.urlshortener.orchestrator.agents.AgentResult;
import com.example.urlshortener.orchestrator.agents.WorkflowState;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Central fail-closed policy for deciding whether one requested agent operation may execute.
 * The orchestrator must evaluate this policy before invoking an operational agent implementation.
 */
public final class ControlledAutonomyPolicy {

    public Decision evaluate(AgentDefinition agent, WorkflowState state,
                             OperationRequest request, Instant evaluatedAt) {
        if (agent == null || state == null || request == null || evaluatedAt == null) {
            throw new IllegalArgumentException("agent, state, request, and evaluatedAt are required");
        }

        RiskLevel risk = assessedRisk(request);
        WorkflowState.Task task = state.task(request.taskId());
        if (task == null) {
            return decision(risk, AutonomyLevel.PROHIBITED, false, false, false,
                    "The requested task does not exist in WorkflowState.");
        }
        if (task.assignedAgent() != null && !task.assignedAgent().equals(agent.name())) {
            return decision(risk, AutonomyLevel.PROHIBITED, false, false, false,
                    "The task is assigned to a different agent.");
        }
        if (agent.prohibitedActions().contains(request.action())) {
            return decision(risk, AutonomyLevel.PROHIBITED, false, false, false,
                    "The requested action is outside this agent's autonomy boundary.");
        }

        if (agent.approvalRequiredActions().contains(request.action())) {
            risk = RiskLevel.HIGH;
        }

        return switch (risk) {
            case LOW -> decision(risk, AutonomyLevel.AUTONOMOUS, true, false, false,
                    "Low-risk action may execute autonomously within the assigned task.");
            case MEDIUM -> decision(risk, AutonomyLevel.AUTONOMOUS_WITH_VALIDATION, true, true, false,
                    "Medium-risk action may execute, but successful completion requires validation evidence.");
            case HIGH -> highRiskDecision(state, task, request, evaluatedAt);
        };
    }

    /** Enforces the medium-risk evidence requirement before a successful result can be accepted. */
    public PostExecutionCheck validateResult(Decision decision, AgentResult result) {
        if (decision == null || result == null) {
            throw new IllegalArgumentException("decision and result are required");
        }
        if (result.status() != AgentResult.Status.SUCCEEDED) {
            return new PostExecutionCheck(true, "Non-success result made no success claim requiring acceptance.");
        }
        if (!decision.executionAllowed()) {
            return new PostExecutionCheck(false, "A successful result cannot be accepted for a prohibited operation.");
        }
        if (decision.validationRequired() && result.validationResults().isEmpty()) {
            return new PostExecutionCheck(false,
                    "Medium-risk success is missing the validation evidence required by policy.");
        }
        return new PostExecutionCheck(true, "Result satisfies controlled-autonomy policy.");
    }

    public RiskLevel assessedRisk(OperationRequest request) {
        RiskLevel risk = baselineRisk(request.action());
        if (request.destructive() || request.securitySensitive() || request.criticalPath()) {
            risk = RiskLevel.HIGH;
        }
        if (request.action() == AgentDefinition.Action.MODIFY_CONFIGURATION
                && (request.environment() == Environment.PRODUCTION
                || request.environment() == Environment.SHARED)) {
            risk = RiskLevel.HIGH;
        }
        if (request.action() == AgentDefinition.Action.EXECUTE_TESTS
                && (request.environment() == Environment.PRODUCTION
                || request.environment() == Environment.SHARED)) {
            risk = RiskLevel.HIGH;
        }
        if (request.action() == AgentDefinition.Action.ADD_OR_UPDATE_DEPENDENCY
                && request.dependencyChange() != DependencyChange.PATCH) {
            risk = RiskLevel.HIGH;
        }
        return risk;
    }

    private Decision highRiskDecision(WorkflowState state, WorkflowState.Task task,
                                      OperationRequest request, Instant evaluatedAt) {
        boolean approved = task.requiredApprovalIds().stream()
                .map(state::approval)
                .filter(approval -> approval != null)
                .anyMatch(approval -> approval.isEffectiveFor(task.taskId(), request.action(), evaluatedAt));
        if (!approved) {
            return decision(RiskLevel.HIGH, AutonomyLevel.HUMAN_APPROVAL_REQUIRED,
                    false, false, false,
                    "High-risk action requires an effective task- and action-scoped human approval.");
        }
        return decision(RiskLevel.HIGH, AutonomyLevel.HUMAN_APPROVAL_REQUIRED,
                true, true, true,
                "High-risk action may execute because the required scoped human approval is effective; validation remains mandatory.");
    }

    private static Decision decision(RiskLevel risk, AutonomyLevel autonomy, boolean execute,
                                     boolean validate, boolean approvalSatisfied, String reason) {
        return new Decision(risk, autonomy, execute, validate,
                autonomy == AutonomyLevel.HUMAN_APPROVAL_REQUIRED, approvalSatisfied, reason,
                EnumSet.allOf(AuditItem.class));
    }

    private static RiskLevel baselineRisk(AgentDefinition.Action action) {
        return switch (action) {
            case ANALYZE_REQUIREMENTS, NORMALIZE_REQUIREMENTS, IDENTIFY_AMBIGUITIES,
                    PROPOSE_ASSUMPTIONS, DEFINE_ACCEPTANCE_CRITERIA, RECORD_RISK,
                    DECOMPOSE_TASKS, DEFINE_DEPENDENCIES, DEFINE_EXECUTION_SEQUENCE, DEFINE_RETRY_POLICY,
                    DESIGN_ARCHITECTURE, PROPOSE_PUBLIC_API_CHANGE, PROPOSE_DATABASE_SCHEMA_CHANGE,
                    RECORD_ARCHITECTURE_DECISION, DEFINE_ROLLBACK_STRATEGY, MODEL_SECURITY_BOUNDARIES,
                    CREATE_TESTS, RECORD_TEST_RESULTS, CREATE_DOCUMENTATION, MODIFY_DOCUMENTATION,
                    ASSESS_RELEASE_READINESS, CREATE_RELEASE_MANIFEST -> RiskLevel.LOW;

            case MODIFY_SOURCE_CODE, MODIFY_CONFIGURATION, ADD_OR_UPDATE_DEPENDENCY,
                    MODIFY_TESTS, EXECUTE_TESTS,
                    VALIDATE_ACCEPTANCE_CRITERIA, VALIDATE_SECURITY, EVALUATE_QUALITY_GATE -> RiskLevel.MEDIUM;

            case CHANGE_AUTHENTICATION_OR_SECURITY, CHANGE_PUBLIC_API, CHANGE_DATABASE_SCHEMA,
                    DELETE_FILE, WAIVE_VALIDATION_FAILURE, WAIVE_SECURITY_FINDING,
                    DEPLOY_RELEASE, EXECUTE_ROLLBACK,
                    APPROVE_REQUIREMENTS, APPROVE_DESIGN, APPROVE_RELEASE,
                    ORCHESTRATE_WORKFLOW -> RiskLevel.HIGH;
        };
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public enum AutonomyLevel {
        AUTONOMOUS,
        AUTONOMOUS_WITH_VALIDATION,
        HUMAN_APPROVAL_REQUIRED,
        PROHIBITED
    }

    public enum Environment { LOCAL, TEST, STAGING, SHARED, PRODUCTION }

    public enum DependencyChange { NONE, PATCH, MINOR, MAJOR, NEW }

    public enum AuditItem {
        WORKFLOW_ID, TASK_ID, REQUIREMENT_VERSION, TASK_GRAPH_VERSION,
        AGENT_NAME, ACTION, ASSESSED_RISK, AUTONOMY_LEVEL, ENVIRONMENT,
        INPUT_ARTIFACT_IDS, AFFECTED_RESOURCES, BEFORE_AND_AFTER_HASHES,
        COMMANDS_AND_TOOLS, VALIDATION_RESULTS, APPROVAL_ID, APPROVER,
        DECISIONS_AND_RATIONALE, RISKS, RETRY_HISTORY, ERROR,
        STARTED_AT, COMPLETED_AT, FINAL_OUTCOME, ROLLBACK_REFERENCE
    }

    public record OperationRequest(
            String taskId,
            AgentDefinition.Action action,
            Environment environment,
            DependencyChange dependencyChange,
            boolean destructive,
            boolean securitySensitive,
            boolean criticalPath,
            String rationale) {
        public OperationRequest {
            if (taskId == null || taskId.isBlank() || action == null || environment == null
                    || dependencyChange == null || rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("A complete operation request is required");
            }
        }

        public static OperationRequest standard(String taskId, AgentDefinition.Action action,
                                                Environment environment, String rationale) {
            return new OperationRequest(taskId, action, environment, DependencyChange.NONE,
                    false, false, false, rationale);
        }
    }

    public record Decision(
            RiskLevel riskLevel,
            AutonomyLevel autonomyLevel,
            boolean executionAllowed,
            boolean validationRequired,
            boolean humanApprovalRequired,
            boolean approvalSatisfied,
            String reason,
            Set<AuditItem> requiredAuditItems) {
        public Decision {
            requiredAuditItems = Set.copyOf(requiredAuditItems);
        }
    }

    public record PostExecutionCheck(boolean accepted, String reason) { }
}
