package com.example.urlshortener.orchestrator.agents;

import java.util.List;

import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Action.*;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Context.REQUIREMENTS;
import static com.example.urlshortener.orchestrator.agents.AgentDefinition.Stage.PLANNING;
import static com.example.urlshortener.orchestrator.agents.AgentSupport.*;

final class PlanningAgent extends AbstractAgent {
    private static final AgentDefinition DEFINITION = AgentSupport.definition(
            "PlanningAgent",
            "Decompose approved requirements into bounded DAG tasks, dependencies, owners, and validation obligations.",
            PLANNING,
            "orchestrator/prompts/planner-agent.md",
            set(REQUIREMENTS),
            set(DECOMPOSE_TASKS, DEFINE_DEPENDENCIES, DEFINE_EXECUTION_SEQUENCE, DEFINE_RETRY_POLICY, RECORD_RISK),
            noApprovalActions(),
            List.of(
                    "Every task must map to approved requirements or required quality work.",
                    "The proposed graph must be acyclic and expose safe parallelism and synchronization points.",
                    "Retries must be bounded and each task must have an owner and completion evidence."),
            List.of(
                    "Requirements are not approved or are stale.",
                    "The plan contains a cycle, an ownerless task, or an unsatisfied dependency.",
                    "Architecture-dependent work cannot be safely planned without a synchronization decision."),
            List.of(
                    "The plan introduces work outside approved scope or a high-impact change.",
                    "The reconciled plan is ready for the human Design Approval gate; the agent cannot approve it."),
            field("taskGraph", "artifact", true, "Versioned acyclic task graph."),
            field("executionPlan", "artifact", true, "Owners, sequencing, retry bounds, and evidence obligations."));

    PlanningAgent() { super(DEFINITION); }
}
