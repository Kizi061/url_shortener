package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.REQUIREMENTS;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.ARCHITECTURE;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class ArchitectureAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "ArchitectureAgent",
            "Define system boundaries and design decisions for approved requirements without implementing them.",
            ARCHITECTURE,
            "orchestrator/prompts/architecture-agent.md",
            set(REQUIREMENTS),
            set(DESIGN_ARCHITECTURE, PROPOSE_PUBLIC_API_CHANGE, PROPOSE_DATABASE_SCHEMA_CHANGE,
                    RECORD_ARCHITECTURE_DECISION, DEFINE_ROLLBACK_STRATEGY, MODEL_SECURITY_BOUNDARIES, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "The design must trace to the current approved requirement version.",
                    "Interfaces, data flow, failure modes, security boundaries, and rollback impact must be explicit.",
                    "Alternatives and rationale must accompany material decisions."),
            List.of(
                    "Requirements are stale or insufficient for a safe design.",
                    "The proposal conflicts with an approved constraint without escalation.",
                    "A critical security or rollback gap remains unresolved."),
            List.of(
                    "Any proposed public API, database schema, trust-boundary, or irreversible operational change.",
                    "The architecture package is ready for the human Design Approval gate; the agent cannot approve it."),
            field("architecture", "artifact", true, "Components, interfaces, data flow, and operational design."),
            field("architectureDecisions", "list", true, "Material decisions, alternatives, and rationale."),
            field("rollbackStrategy", "artifact", true, "Recovery assumptions and compensating actions."));

    ArchitectureAgent() { super(DEFINITION); }
}
