package com.example.urlshortener.orchestrator.agents;

/** A single-purpose SDLC actor. Scheduling and state persistence belong to the orchestrator. */
public interface Agent {
    AgentDefinition definition();

    AgentResult execute(String taskId, WorkflowState workflowState);

    default AgentResult execute(String taskId, WorkflowState workflowState,
                                WorkflowState.RetryContext retryContext) {
        return execute(taskId, workflowState);
    }
}
