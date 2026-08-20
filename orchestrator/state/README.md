# Shared Workflow State

## Purpose

This directory defines the language-neutral state contract shared by an SDLC orchestration system across:

- Requirement analysis
- Architecture
- Planning
- Implementation
- Testing
- Validation
- Documentation
- Release readiness

Files:

- `workflow-state.schema.json` — structural JSON Schema contract using Draft 2020-12.
- `workflow-state.example.json` — representative state after a requirement change, dynamic replan, retry, checkpoint, and human approval request.

The names `RequirementAgent`, `PlannerAgent`, `ArchitectureAgent`, `ImplementationAgent`, `TestAgent`, `ValidationAgent`, `DocumentationAgent`, and `ReleaseAgent` in the example are assignment identifiers only. This state contract does not implement those agents.

## Design rationale

### Durable snapshot plus append-only events

The JSON document is the current workflow snapshot. `audit.events` records the ordered decisions that produced it. A production implementation should persist events append-only and materialize the snapshot for efficient reads.

The snapshot and its latest audit event must be committed atomically. An event must never claim a state revision that was not durably written.

### Optimistic concurrency

`stateRevision` is a monotonically increasing compare-and-swap version. A writer may update revision `N` only when the stored revision is still `N`; the successful update becomes `N + 1`.

This prevents two agents from silently overwriting each other's state. A rejected write must reload current state and either merge safely or trigger replanning.

### Artifact references instead of embedded content

Large requirements, source files, test reports, architecture documents, logs, and release manifests should live in an artifact store. Workflow state records their URI, content hash, producer, requirement version, plan version, and lineage.

This keeps checkpoints small and lets the orchestrator verify that an artifact has not changed before resuming work.

Secrets, credentials, unrestricted prompts, and sensitive tool output must not be embedded in state or audit events.

### Stable identity and lineage

Requirement versions, plan versions, task IDs, artifact IDs, decision IDs, failure IDs, approval IDs, and checkpoint IDs are stable. Completed history is not rewritten.

When an output is replaced, the old record remains and is marked `STALE`, `SUPERSEDED`, `INVALIDATED`, or `ROLLED_BACK`. This preserves decision lineage and makes a final artifact traceable to its requirement, plan, tasks, evidence, and approvals.

## Workflow resumption

A safe resume operation should:

1. Load the latest snapshot and verify its hash against the newest durable checkpoint.
2. Reject a snapshot whose schema version is unsupported.
3. Verify that audit sequence numbers and state revisions are monotonic.
4. Expire abandoned task leases whose `expiresAt` value has passed.
5. Check each resumable task's idempotency record before repeating side effects.
6. Revalidate requirement, dependency-artifact, policy, and approval versions.
7. Requeue only tasks whose dependencies are satisfied and whose approvals remain valid.
8. Move to `REPLANNING` instead of resuming when an upstream input has changed.
9. Create a new audit event and checkpoint before executing the next side-effecting task.

A task lease prevents multiple workers from executing the same task concurrently. Workers must renew `heartbeatAt`; an expired lease may be reclaimed only after idempotency and external-side-effect checks.

## Dynamic replanning

When a requirement, policy, decision, or upstream artifact changes, the orchestrator should:

1. Create a new immutable version of the changed input.
2. Increment the task-graph plan version.
3. Traverse dependency edges to find affected downstream tasks.
4. Mark affected incomplete tasks `CANCELLED` and affected completed tasks `INVALIDATED`.
5. Mark affected output artifacts `STALE` rather than deleting them.
6. Preserve completed tasks whose inputs and policies are unchanged.
7. Add replacement tasks with new IDs or plan-version-qualified idempotency keys.
8. Record added, invalidated, preserved, and stale entities in `replanHistory`.
9. Reevaluate risks, rollback plans, and approval gates.
10. Require renewed human approval when the replan changes approved scope, architecture, security posture, schema, external interfaces, cost, or release commitments.

## Task-state rules

Recommended transitions:

```text
PLANNED -> READY -> RUNNING -> SUCCEEDED
                    |   |
                    |   +-> FAILED -> RETRYING -> READY
                    |   +-> WAITING_APPROVAL
                    |   +-> BLOCKED
                    +-> CANCELLED

SUCCEEDED -> INVALIDATED
SUCCEEDED -> ROLLED_BACK
```

Policy or approval failures are not ordinary transient failures. They should block, escalate, or safe-stop rather than consume retry attempts.

## Semantic invariants

JSON Schema validates structure. The workflow engine must additionally enforce these cross-record rules:

1. Every task-map key equals its task's `taskId`.
2. Every dependency, entry task, terminal task, and affected task refers to an existing task.
3. The active dependency graph is acyclic.
4. Every artifact-map key equals its artifact's `artifactId`.
5. Every artifact producer refers to an existing task.
6. Every task input and output artifact reference exists.
7. `requirement.currentVersion` exists in `requirement.versions` and requirement versions increase monotonically.
8. Task-graph and replan versions increase monotonically.
9. A task cannot become `READY` until its dependencies, required artifacts, policies, and approvals are satisfied.
10. A task cannot become `SUCCEEDED` without its required output artifacts and exit-gate evidence.
11. `attemptCount` cannot exceed `maxAttempts`.
12. At most one unexpired execution lease exists for a task.
13. A task's side effects execute at most once for an idempotency key.
14. Completed artifacts and decisions are immutable; replacement creates a new version.
15. Test totals satisfy `total = passed + failed + skipped`.
16. A failed validation gate prevents dependent work unless an authorized waiver is recorded.
17. A required approval must be approved, unexpired, unrevoked, and scoped to the action before execution.
18. Rollback actions are idempotent and cannot exceed their approved scope.
19. Checkpoint revisions cannot exceed the workflow's current state revision.
20. Audit event sequences, timestamps, and state revisions are monotonic.

## Failure, retry, fallback, and safe stop

Each failed attempt creates a `failure` record. A `retry` record is created only when policy classifies that failure as retriable and the task remains below its attempt bound.

Examples of normally retriable failures:

- Temporary tool or network failure
- Dependency timeout
- Optimistic concurrency conflict
- Transient infrastructure unavailability

Examples of normally non-retriable outcomes:

- Invalid requirement
- Failed security policy
- Rejected or expired approval
- Deterministic test failure without a changed input
- Unsupported schema version

When retry is unsafe or exhausted, the engine selects an approved fallback, rollback, escalation, or `SAFE_STOPPED` state. The selected action and rationale must be audited.

## Approval model

Approvals are first-class records rather than Boolean flags. Each approval identifies:

- The gate being decided
- The tasks and artifacts in scope
- The proposed action
- Required human roles
- Request, response, expiry, and revocation information

An approval for one plan version must not automatically authorize materially changed work in a later plan version.

## Rollback model

Rollback information records the trigger, affected tasks, checkpoint, action sequence, status, and an idempotency key for each action. A rollback can itself be interrupted and resumed.

File restoration should use recorded before/after hashes. Schema and deployment rollback require an explicit reviewed strategy; the presence of a rollback record does not prove that a destructive operation is reversible.

## Metrics derivation

The state supports calculation of:

- Workflow success rate from terminal workflow states
- Task success rate from task outcomes
- Retry frequency from `retries`
- Rollback frequency from `rollbacks`
- Mean time to recovery from failure and resolution timestamps
- Stage and end-to-end latency from audit and task timestamps
- Approval wait time from approval request and response timestamps
- Replan frequency and invalidated-work rate from `replanHistory`

Metric definitions, windows, and targets still require human approval before they can be used as release gates.
