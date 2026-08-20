package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.IMPLEMENTATION;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class ImplementationAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "ImplementationAgent",
            "Implement the smallest approved code/configuration change for one task and record its exact effects.",
            IMPLEMENTATION,
            "orchestrator/prompts/implementation-agent.md",
            set(REQUIREMENTS, TASK_GRAPH, ARCHITECTURE, APPROVALS, ROLLBACK_INFORMATION),
            set(MODIFY_SOURCE_CODE, MODIFY_CONFIGURATION, ADD_OR_UPDATE_DEPENDENCY, RECORD_RISK),
            set(CHANGE_AUTHENTICATION_OR_SECURITY,
                    CHANGE_PUBLIC_API, CHANGE_DATABASE_SCHEMA),
            List.of(
                    "The task, design approval, inputs, and rollback checkpoint must be current.",
                    "Changed files must remain within approved task scope and preserve unrelated behavior.",
                    "Every changed behavior must identify required tests and documentation."),
            List.of(
                    "Required design, approval, dependency, or checkpoint evidence is missing or stale.",
                    "The requested change exceeds the task boundary or requires a prohibited action.",
                    "A coherent change cannot be completed within the bounded retry policy."),
            List.of(
                    "Adding a dependency, performing a non-patch dependency upgrade, or changing authentication, security, a public API, or a database schema.",
                    "The task reveals an unapproved security, data-loss, destructive, or scope-expanding action."),
            field("changedFiles", "list", true, "Exact created or modified files and hashes."),
            field("implementationArtifact", "artifact", true, "Traceable implementation output."),
            field("requiredValidation", "list", true, "Tests, security checks, and documentation obligations."));

    ImplementationAgent() { super(DEFINITION); }
}
