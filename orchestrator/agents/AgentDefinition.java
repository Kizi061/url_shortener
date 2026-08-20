package com.example.urlshortener.orchestrator.agents;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Immutable description of one agent's contract and autonomy boundary. */
public record AgentDefinition(
        String name,
        String purpose,
        Stage stage,
        String promptPath,
        List<ContractField> inputContract,
        List<ContractField> outputContract,
        Set<Context> requiredWorkflowContext,
        Set<Action> allowedActions,
        Set<Action> approvalRequiredActions,
        Set<Action> prohibitedActions,
        List<String> validationRules,
        List<String> failureConditions,
        List<String> humanApprovalConditions,
        Set<AuditField> requiredAuditFields) {

    public AgentDefinition {
        requireText(name, "name");
        requireText(purpose, "purpose");
        requireText(promptPath, "promptPath");
        if (stage == null) throw new IllegalArgumentException("stage is required");
        inputContract = List.copyOf(inputContract);
        outputContract = List.copyOf(outputContract);
        requiredWorkflowContext = Set.copyOf(requiredWorkflowContext);
        allowedActions = Set.copyOf(allowedActions);
        approvalRequiredActions = Set.copyOf(approvalRequiredActions);
        prohibitedActions = Set.copyOf(prohibitedActions);
        validationRules = List.copyOf(validationRules);
        failureConditions = List.copyOf(failureConditions);
        humanApprovalConditions = List.copyOf(humanApprovalConditions);
        requiredAuditFields = Set.copyOf(requiredAuditFields);

        EnumSet<Action> classified = EnumSet.noneOf(Action.class);
        addDisjoint(classified, allowedActions);
        addDisjoint(classified, approvalRequiredActions);
        addDisjoint(classified, prohibitedActions);
        if (!classified.equals(EnumSet.allOf(Action.class))) {
            throw new IllegalArgumentException("Every action must be allowed, approval-controlled, or prohibited");
        }
        if (inputContract.isEmpty() || outputContract.isEmpty() || validationRules.isEmpty()
                || failureConditions.isEmpty() || humanApprovalConditions.isEmpty()
                || requiredAuditFields.isEmpty()) {
            throw new IllegalArgumentException("Agent contract sections must not be empty");
        }
    }

    private static void addDisjoint(Set<Action> classified, Set<Action> actions) {
        for (Action action : actions) {
            if (!classified.add(action)) {
                throw new IllegalArgumentException("Action appears in multiple autonomy classifications: " + action);
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record ContractField(String name, String type, boolean required, String description) {
        public ContractField {
            requireText(name, "contract field name");
            requireText(type, "contract field type");
            requireText(description, "contract field description");
        }
    }

    public enum Stage {
        REQUIREMENT_ANALYSIS, ARCHITECTURE, PLANNING, IMPLEMENTATION,
        TESTING, VALIDATION, DOCUMENTATION, RELEASE_READINESS
    }

    public enum Context {
        REQUIREMENTS, TASK_GRAPH, ARCHITECTURE, IMPLEMENTATION_CHANGES,
        TEST_RESULTS, VALIDATION_RESULTS, DOCUMENTATION, DECISIONS, RISKS,
        APPROVALS, ROLLBACK_INFORMATION, AUDIT_HISTORY
    }

    public enum Action {
        ANALYZE_REQUIREMENTS, NORMALIZE_REQUIREMENTS, IDENTIFY_AMBIGUITIES,
        PROPOSE_ASSUMPTIONS, DEFINE_ACCEPTANCE_CRITERIA, RECORD_RISK,
        DECOMPOSE_TASKS, DEFINE_DEPENDENCIES, DEFINE_EXECUTION_SEQUENCE, DEFINE_RETRY_POLICY,
        DESIGN_ARCHITECTURE, PROPOSE_PUBLIC_API_CHANGE, PROPOSE_DATABASE_SCHEMA_CHANGE,
        RECORD_ARCHITECTURE_DECISION, DEFINE_ROLLBACK_STRATEGY, MODEL_SECURITY_BOUNDARIES,
        MODIFY_SOURCE_CODE, MODIFY_CONFIGURATION, ADD_OR_UPDATE_DEPENDENCY,
        CHANGE_AUTHENTICATION_OR_SECURITY,
        CHANGE_PUBLIC_API, CHANGE_DATABASE_SCHEMA, DELETE_FILE,
        CREATE_TESTS, MODIFY_TESTS, EXECUTE_TESTS, RECORD_TEST_RESULTS,
        VALIDATE_ACCEPTANCE_CRITERIA, VALIDATE_SECURITY, EVALUATE_QUALITY_GATE,
        WAIVE_VALIDATION_FAILURE, WAIVE_SECURITY_FINDING,
        CREATE_DOCUMENTATION, MODIFY_DOCUMENTATION,
        ASSESS_RELEASE_READINESS, CREATE_RELEASE_MANIFEST,
        DEPLOY_RELEASE, EXECUTE_ROLLBACK,
        APPROVE_REQUIREMENTS, APPROVE_DESIGN, APPROVE_RELEASE, ORCHESTRATE_WORKFLOW
    }

    public enum AuditField {
        WORKFLOW_ID, REQUIREMENT_VERSION, TASK_GRAPH_VERSION,
        STATE_REVISION_BEFORE, STATE_REVISION_AFTER,
        AGENT_NAME, TASK_ID, TASK_ATTEMPT,
        INPUT_ARTIFACT_IDS, OUTPUT_ARTIFACT_IDS,
        DECISIONS, RISKS, VALIDATION_RESULTS, APPROVAL_REFERENCES, ERROR,
        STARTED_AT, COMPLETED_AT
    }
}
