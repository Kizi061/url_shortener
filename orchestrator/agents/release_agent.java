package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.RELEASE_READINESS;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class ReleaseReadinessAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "ReleaseReadinessAgent",
            "Assemble and assess one release evidence package without deploying, approving, or executing rollback.",
            RELEASE_READINESS,
            "orchestrator/prompts/release-agent.md",
            set(REQUIREMENTS, TASK_GRAPH, IMPLEMENTATION_CHANGES, TEST_RESULTS, VALIDATION_RESULTS,
                    DOCUMENTATION, RISKS, APPROVALS, ROLLBACK_INFORMATION),
            set(ASSESS_RELEASE_READINESS, CREATE_RELEASE_MANIFEST, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "All evidence must reference the same requirement, plan, implementation, and state revisions.",
                    "Required tests, validation, documentation, configuration, residual risks, and rollback steps must exist.",
                    "Readiness is a recommendation and cannot be represented as release approval or deployment."),
            List.of(
                    "Evidence is missing, stale, internally inconsistent, or tied to different revisions.",
                    "An unapproved high/critical residual risk or failed gate remains.",
                    "Rollback information is incomplete or cannot be linked to the release candidate."),
            List.of(
                    "The evidence package is ready for Human Release Approval.",
                    "Any request to deploy, accept residual risk, waive a gate, or execute rollback."),
            field("releaseManifest", "artifact", true, "Versioned candidate and immutable evidence references."),
            field("readinessRecommendation", "string", true, "Ready, not ready, or safe-stop recommendation."),
            field("residualRisks", "list", true, "Explicit risks requiring release-owner visibility."));

    ReleaseReadinessAgent() { super(DEFINITION); }
}
