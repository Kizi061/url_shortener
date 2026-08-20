package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.REQUIREMENTS;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.REQUIREMENT_ANALYSIS;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class RequirementAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "RequirementAgent",
            "Normalize one requirement version into explicit, traceable requirements without selecting a design.",
            REQUIREMENT_ANALYSIS,
            "orchestrator/prompts/requirement-agent.md",
            set(REQUIREMENTS),
            set(ANALYZE_REQUIREMENTS, NORMALIZE_REQUIREMENTS, IDENTIFY_AMBIGUITIES,
                    PROPOSE_ASSUMPTIONS, DEFINE_ACCEPTANCE_CRITERIA, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "The source requirement version and provenance must be present.",
                    "Every ambiguity and inferred behavior must be labeled; assumptions cannot be presented as facts.",
                    "Acceptance criteria must be testable and traceable to a requirement."),
            List.of(
                    "The source requirement is missing, contradictory, or too incomplete to normalize safely.",
                    "A material ambiguity cannot be resolved without changing scope.",
                    "The normalized requirement artifact fails structural validation."),
            List.of(
                    "A proposed assumption changes scope, security posture, data handling, cost, or a public contract.",
                    "The requirement baseline is ready for the Requirement Gate; the agent cannot approve it."),
            field("normalizedRequirements", "artifact", true, "Versioned normalized requirement artifact."),
            field("ambiguities", "list", true, "Explicit unresolved questions."),
            field("assumptions", "list", true, "Explicitly labeled assumptions."),
            field("acceptanceCriteria", "list", true, "Traceable and testable criteria."));

    RequirementAgent() { super(DEFINITION); }
}
