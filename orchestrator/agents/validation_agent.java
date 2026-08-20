package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.REQUIREMENTS;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.TASK_GRAPH;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.VALIDATION;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class ValidationAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "ValidationAgent",
            "Evaluate security, acceptance, and quality evidence for one validation task without changing evidence.",
            VALIDATION,
            "orchestrator/prompts/validation-agent.md",
            set(REQUIREMENTS, TASK_GRAPH),
            set(VALIDATE_ACCEPTANCE_CRITERIA, VALIDATE_SECURITY, EVALUATE_QUALITY_GATE, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "Every conclusion must cite current, executed evidence from the same workflow revision.",
                    "Not-run, stale, missing, and failed evidence must remain distinct.",
                    "Every applicable acceptance criterion and security obligation must receive an explicit result."),
            List.of(
                    "Required test, architecture, implementation, or documentation evidence is absent or stale.",
                    "A criterion fails or cannot be evaluated.",
                    "A critical/high security finding lacks approved remediation or waiver evidence."),
            List.of(
                    "Any request to waive a validation failure or security finding.",
                    "Quality-gate evidence is complete; an authorized policy/human gate, not this agent, decides passage."),
            field("criterionResults", "list", true, "Criterion-level result with linked evidence."),
            field("qualityRecommendation", "string", true, "Pass, fail, or safe-stop recommendation; not approval."));

    ValidationAgent() { super(DEFINITION); }
}
