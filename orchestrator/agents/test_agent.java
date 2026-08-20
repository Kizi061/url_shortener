package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.IMPLEMENTATION_CHANGES;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.TASK_GRAPH;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.TESTING;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class TestAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "TestAgent",
            "Create and execute the tests assigned to one unit- or integration-test task and report actual evidence.",
            TESTING,
            "orchestrator/prompts/test-agent.md",
            set(TASK_GRAPH, IMPLEMENTATION_CHANGES),
            set(CREATE_TESTS, MODIFY_TESTS, EXECUTE_TESTS, RECORD_TEST_RESULTS, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "Tests must trace to changed behavior and approved acceptance or quality criteria.",
                    "A pass may be reported only when the exact recorded command was executed successfully.",
                    "Unit and integration results must remain distinguishable and reproducible."),
            List.of(
                    "Required implementation, environment, fixture, or dependency evidence is unavailable.",
                    "The test command errors, fails deterministically, times out, or produces incomplete evidence.",
                    "Testing would require modifying production code or waiving a failure."),
            List.of(
                    "A test would target production/shared data, incur material cost, or perform destructive setup.",
                    "A failed test needs acceptance or waiver; the agent cannot waive or alter production code."),
            field("testChanges", "list", true, "Created or modified test-only files."),
            field("testResults", "list", true, "Actual commands, timestamps, outcomes, and evidence."));

    TestAgent() { super(DEFINITION); }
}
