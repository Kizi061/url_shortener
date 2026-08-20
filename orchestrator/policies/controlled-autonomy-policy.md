# Controlled-Autonomy Policy

## Purpose

This policy determines whether one requested AI-agent operation may execute autonomously. It is evaluated per operation, before execution. When more than one rule applies, the highest risk wins. Missing, ambiguous, stale, or mismatched authorization fails closed.

The executable policy is [`ControlledAutonomyPolicy.java`](ControlledAutonomyPolicy.java). The future orchestrator must call `evaluate(...)` before operational `Agent.execute(...)`, and call `validateResult(...)` before accepting a successful result.

## Autonomy levels

| Risk | Autonomy | Execution condition |
|---|---|---|
| `LOW` | `AUTONOMOUS` | The agent may execute within its assigned task. Validation is recommended but not a policy precondition. |
| `MEDIUM` | `AUTONOMOUS_WITH_VALIDATION` | The agent may execute, but a successful result is rejected unless validation evidence is present. |
| `HIGH` | `HUMAN_APPROVAL_REQUIRED` | Execution is denied until an effective approval references the current task and exact action. Validation is still mandatory after approval. |
| Any agent-prohibited action | `PROHIBITED` | Approval cannot expand an agent's bounded responsibility. |

Agents cannot approve their own operations. An approval must be referenced by the task, have status `APPROVED`, include the task ID, match the exact `AgentDefinition.Action`, and remain unexpired.

## Human approval interaction

`HumanApprovalInteraction.java` is the orchestrator-owned presentation and response layer for a denied `HIGH`-risk policy decision. It is not part of an agent and it never executes an operation. Opening an interaction moves the workflow and task to `WAITING_APPROVAL` and records `HUMAN_APPROVAL_REQUESTED` in the audit history.

The standard interaction is:

```text
AGENT: ImplementationAgent

Database migration detected:

ALTER TABLE shortened_urls
ADD expires_at TIMESTAMP NULL;

Risk level: HIGH

Reason:
Schema modification may affect existing persistence behavior.

Approve?

[Y] Approve
[N] Reject
[M] Modify
```

| Response | State transition | Authorization effect |
|---|---|---|
| `Y` / Approve | Workflow `RUNNING`; task `READY` | Records a time-bounded approval for the exact task and action. The orchestrator must re-evaluate `ControlledAutonomyPolicy`; the interaction itself does not authorize execution. |
| `N` / Reject | Workflow `PAUSED`; task `BLOCKED` | Records the rejection audit event and creates no effective approval. |
| `M` / Modify | Workflow `REPLANNING`; task `INVALIDATED` | Records mandatory human modification instructions and creates no effective approval. A revised task must pass policy evaluation again. |

The prompt is bound to the workflow ID, state revision, task ID, action, environment, approval ID, request time, and expiry. A stale, expired, reused, malformed, or context-mismatched response fails closed. The audit entry records the decision, authenticated approver ID and role, rationale, modification instructions when applicable, action, and environment.

The console `Reader`/`Writer` adapter exists for local integration and automated testing. A production adapter must obtain approver identity and role from an authenticated, authorized channel; it must not trust an agent-supplied identity. Change previews must be sanitized before display because approval and audit channels must not expose secrets or personal data.

## Operation classification

| Operation | Default risk | Autonomous behavior | Required validation | Human approval |
|---|---|---|---|---|
| Requirement analysis, ambiguity/assumption identification, acceptance criteria | Low | Allowed | Contract and traceability checks | No |
| Planning, architecture proposals, ADRs, rollback planning | Low | Allowed; proposals cannot enact changes | Structural/traceability checks | No; downstream gates remain human-controlled |
| Source-code edit within approved non-critical scope | Medium | Allowed | Build, relevant tests, static/contract checks | No |
| Critical-path or security-sensitive source edit | High | Denied until approved | Full relevant tests and security checks | Yes |
| Database schema change | High | Denied until approved | Migration/compatibility/data/rollback checks | Yes |
| Public API contract change | High | Denied until approved | Contract diff and compatibility tests | Yes |
| Authentication, authorization, cryptography, secrets, or security-policy change | High | Denied until approved | Threat/security/negative authorization checks | Yes |
| Patch dependency upgrade | Medium by policy baseline | Allowed only if the agent boundary also permits it | Build, tests, vulnerability/license/provenance scan | Agent definitions may conservatively elevate it to high |
| Minor/major/new or unspecified dependency change | High | Denied until approved | Compatibility, supply-chain, license, and rollback checks | Yes |
| File deletion or destructive operation | High | Denied until approved; currently prohibited for all defined agents | Reference, build/test, and recovery validation | Approval cannot override an agent prohibition |
| Local/test/staging configuration change | Medium | Allowed | Syntax/schema, secret scan, smoke test | No |
| Production/shared configuration change | High | Denied until approved | Dry run, secret handling, canary/health/rollback checks | Yes |
| Test generation | Low | Allowed | Compile/run where available; never invent a pass | No |
| Test modification or execution | Medium | Allowed in authorized non-production environments | Actual command and reproducible result evidence | No |
| Test execution against production/shared targets or destructive fixtures | High | Denied until approved | Isolation, data-safety, execution, and recovery checks | Yes |
| Documentation generation/update for current behavior | Low | Allowed | Traceability, link/example verification | No |
| Deployment | High | Denied until approved; currently prohibited for all defined agents | Immutable artifact, quality/security gates, health and rollback checks | Yes |
| Rollback execution | High | Denied until approved; currently prohibited for all defined agents | Target/checkpoint/data compatibility and recovery checks | Yes |
| Validation/security waiver or gate approval | High | Prohibited for all defined agents | Human risk review and compensating controls | Authorized human only |

## Context escalation

An operation is elevated to `HIGH` when it is marked destructive, security-sensitive, or critical-path. Configuration and test execution are elevated in `PRODUCTION` or `SHARED` environments. Dependency changes other than an explicitly identified patch are elevated to high. Agent-specific `approvalRequiredActions` also elevate the operation to high.

The risk policy cannot relax an agent definition. If an action is prohibited for that agent, the decision remains `PROHIBITED` even when an approval exists.

## Validation and failure behavior

1. The orchestrator identifies one action and constructs an `OperationRequest` with task, environment, dependency-change type, flags, and rationale.
2. The policy checks task ownership and the agent's allowed/approval/prohibited action classification.
3. Low-risk work may execute.
4. Medium-risk work may execute, but `validateResult` rejects a successful `AgentResult` with no `validationResults`.
5. High-risk work returns `executionAllowed=false` until an exact approval is effective. The workflow must transition to `WAITING_APPROVAL`.
6. A human may approve, reject, or request modification. Only approval creates an effective approval record.
7. The orchestrator re-evaluates policy after approval; approved high-risk work still requires validation evidence.
8. Prohibited, ambiguous, unsafe, or mismatched work must block or safe-stop; it must never be reported as successful.

## Required audit information

Every decision requires: workflow/task/requirement/task-graph identifiers; agent and action; assessed risk and autonomy level; environment; input artifacts; affected resources; before/after hashes; tools/commands; validation results; approval and approver; decisions/rationale; risks; retries; errors; timestamps; final outcome; and rollback reference.

Audit records must be append-only. A material change to action, task, scope, environment, state version, or artifact hash invalidates the prior decision and approval.
