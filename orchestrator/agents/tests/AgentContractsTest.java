package com.example.urlshortener.orchestrator.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JDK-only executable contract tests; throws AssertionError on failure. */
public final class AgentContractsTest {
    private static int assertions;

    private AgentContractsTest() { }

    public static void main(String[] args) {
        catalogContainsEightBoundedAgents();
        definitionsAreCompleteAndDisjoint();
        validInvocationFailsClosedUntilExecutorExists();
        missingContextBlocksInvocation();
        missingApprovalPausesForHuman();
        wrongAssignmentFailsInvocation();
        System.out.println("Agent contract tests passed: " + assertions + " assertions");
    }

    private static void catalogContainsEightBoundedAgents() {
        List<Agent> agents = AgentCatalog.all();
        assertEquals(8, agents.size(), "catalog size");
        assertEquals(Set.of("RequirementAgent", "PlanningAgent", "ArchitectureAgent", "ImplementationAgent",
                        "TestAgent", "ValidationAgent", "DocumentationAgent", "ReleaseReadinessAgent"),
                names(agents), "agent names");
        assertEquals(8L, agents.stream().map(agent -> agent.definition().stage()).distinct().count(),
                "one owner per requested stage");
    }

    private static void definitionsAreCompleteAndDisjoint() {
        Set<String> requiredResultFields = Set.of("agentName", "taskId", "status", "summary", "artifacts",
                "decisions", "risks", "validationResults", "requiresHumanApproval", "error",
                "startedAt", "completedAt");
        for (Agent agent : AgentCatalog.all()) {
            AgentDefinition definition = agent.definition();
            EnumSet<AgentDefinition.Action> classified = EnumSet.noneOf(AgentDefinition.Action.class);
            int declaredCount = definition.allowedActions().size()
                    + definition.approvalRequiredActions().size()
                    + definition.prohibitedActions().size();
            classified.addAll(definition.allowedActions());
            classified.addAll(definition.approvalRequiredActions());
            classified.addAll(definition.prohibitedActions());
            assertEquals(AgentDefinition.Action.values().length, declaredCount,
                    definition.name() + " action sets are disjoint");
            assertEquals(EnumSet.allOf(AgentDefinition.Action.class), classified,
                    definition.name() + " classifies every action");
            assertTrue(definition.prohibitedActions().contains(AgentDefinition.Action.ORCHESTRATE_WORKFLOW),
                    definition.name() + " cannot orchestrate");
            assertTrue(definition.prohibitedActions().contains(AgentDefinition.Action.APPROVE_RELEASE),
                    definition.name() + " cannot approve release");
            assertTrue(definition.prohibitedActions().contains(AgentDefinition.Action.DEPLOY_RELEASE),
                    definition.name() + " cannot deploy");
            assertEquals(EnumSet.allOf(AgentDefinition.AuditField.class), definition.requiredAuditFields(),
                    definition.name() + " requires complete audit fields");
            Set<String> outputNames = new HashSet<>();
            definition.outputContract().forEach(field ->
                    assertTrue(outputNames.add(field.name()), definition.name() + " has unique output field " + field.name()));
            assertTrue(outputNames.containsAll(requiredResultFields), definition.name() + " exposes AgentResult fields");
            assertTrue(Files.isRegularFile(Path.of(definition.promptPath())),
                    definition.name() + " prompt exists");
        }
        Agent implementation = AgentCatalog.requireByName("ImplementationAgent");
        assertTrue(implementation.definition().prohibitedActions().contains(AgentDefinition.Action.DELETE_FILE),
                "ImplementationAgent cannot delete files");
        assertTrue(implementation.definition().approvalRequiredActions()
                        .contains(AgentDefinition.Action.CHANGE_DATABASE_SCHEMA),
                "database schema changes require approval");
    }

    private static void validInvocationFailsClosedUntilExecutorExists() {
        for (Agent agent : AgentCatalog.all()) {
            WorkflowState state = stateFor(agent, List.of());
            AgentResult result = agent.execute("task-1", state);
            assertEquals(AgentResult.Status.BLOCKED, result.status(), agent.definition().name() + " is contract-only");
            assertEquals("EXECUTION_NOT_IMPLEMENTED", result.error().code(), "explicit implementation gap");
            assertEquals(agent.definition().name(), result.agentName(), "result identifies agent");
        }
    }

    private static void missingContextBlocksInvocation() {
        Agent agent = AgentCatalog.requireByName("RequirementAgent");
        WorkflowState.Task task = taskFor(agent, List.of());
        WorkflowState state = WorkflowState.builder("workflow-1").task(task).availableContext(Set.of()).build();
        AgentResult result = agent.execute(task.taskId(), state);
        assertEquals(AgentResult.Status.BLOCKED, result.status(), "missing context status");
        assertEquals("MISSING_WORKFLOW_CONTEXT", result.error().code(), "missing context error");
    }

    private static void missingApprovalPausesForHuman() {
        Agent agent = AgentCatalog.requireByName("ImplementationAgent");
        WorkflowState state = stateFor(agent, List.of("design-approval"));
        AgentResult result = agent.execute("task-1", state);
        assertEquals(AgentResult.Status.WAITING_APPROVAL, result.status(), "approval status");
        assertTrue(result.requiresHumanApproval(), "approval flag");
        assertEquals("APPROVAL_REQUIRED", result.error().code(), "approval error");
    }

    private static void wrongAssignmentFailsInvocation() {
        Agent agent = AgentCatalog.requireByName("TestAgent");
        WorkflowState.Task task = new WorkflowState.Task("task-1", agent.definition().stage(), "AnotherAgent",
                WorkflowState.TaskStatus.READY, List.of(), List.of(), List.of(), 0, 2);
        WorkflowState state = WorkflowState.builder("workflow-1").task(task)
                .availableContext(agent.definition().requiredWorkflowContext()).build();
        AgentResult result = agent.execute(task.taskId(), state);
        assertEquals(AgentResult.Status.FAILED, result.status(), "wrong assignment status");
        assertEquals("AGENT_ASSIGNMENT_MISMATCH", result.error().code(), "wrong assignment error");
    }

    private static WorkflowState stateFor(Agent agent, List<String> approvalIds) {
        WorkflowState.Task task = taskFor(agent, approvalIds);
        return WorkflowState.builder("workflow-1")
                .task(task)
                .availableContext(agent.definition().requiredWorkflowContext())
                .build();
    }

    private static WorkflowState.Task taskFor(Agent agent, List<String> approvalIds) {
        return new WorkflowState.Task("task-1", agent.definition().stage(), agent.definition().name(),
                WorkflowState.TaskStatus.READY, List.of(), List.of(), approvalIds, 0, 2);
    }

    private static Set<String> names(List<Agent> agents) {
        Set<String> names = new HashSet<>();
        agents.forEach(agent -> names.add(agent.definition().name()));
        return names;
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
