package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.IMPLEMENTATION_CHANGES;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.REQUIREMENTS;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.DOCUMENTATION;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class DocumentationAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "DocumentationAgent",
            "Update documentation for approved, implemented behavior without changing code or inventing behavior.",
            DOCUMENTATION,
            "orchestrator/prompts/documentation-agent.md",
            set(REQUIREMENTS, IMPLEMENTATION_CHANGES),
            set(CREATE_DOCUMENTATION, MODIFY_DOCUMENTATION, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "Documentation must trace to current requirements and implementation artifacts.",
                    "Commands, configuration, API examples, and limitations must match recorded behavior.",
                    "Unverified claims must be labeled as pending rather than presented as complete."),
            List.of(
                    "Implementation evidence is incomplete, stale, or inconsistent.",
                    "Required behavior cannot be documented without inventing an undocumented contract.",
                    "The requested edit would alter source code, tests, configuration, or architecture."),
            List.of(
                    "Publishing sensitive operational details or changing externally committed documentation contracts.",
                    "A documentation discrepancy exposes an unapproved product or API decision."),
            field("documentationArtifacts", "list", true, "Updated document references and content hashes."),
            field("documentedLimitations", "list", true, "Known gaps, prerequisites, and pending facts."));

    DocumentationAgent() { super(DEFINITION); }
}
