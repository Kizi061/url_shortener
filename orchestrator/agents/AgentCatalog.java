package com.example.urlshortener.orchestrator.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public registry that exposes package-private concrete agents through the Agent contract. */
public final class AgentCatalog {
    private static final List<Agent> AGENTS = List.of(
            new RequirementAgent(),
            new PlanningAgent(),
            new ArchitectureAgent(),
            new ImplementationAgent(),
            new TestAgent(),
            new ValidationAgent(),
            new DocumentationAgent(),
            new ReleaseReadinessAgent());

    private static final Map<String, Agent> BY_NAME = indexByName(AGENTS);

    private AgentCatalog() { }

    public static List<Agent> all() {
        return AGENTS;
    }

    public static Agent requireByName(String name) {
        Agent agent = BY_NAME.get(name);
        if (agent == null) throw new IllegalArgumentException("Unknown agent: " + name);
        return agent;
    }

    private static Map<String, Agent> indexByName(List<Agent> agents) {
        Map<String, Agent> indexed = new LinkedHashMap<>();
        for (Agent agent : agents) {
            Agent previous = indexed.put(agent.definition().name(), agent);
            if (previous != null) throw new IllegalStateException("Duplicate agent name: " + agent.definition().name());
        }
        return Map.copyOf(indexed);
    }
}
