# Controlled Rollback Handling

## Safety boundary

`ControlledRollbackOrchestrator` owns rollback decisions and sequencing. Agents may propose rollback plans but cannot execute Git, MySQL, deployment, file-restoration, or application-state restoration operations.

This module contains no concrete destructive adapter. Runtime integrations must implement the injected `CheckpointVerifier`, `RollbackActionExecutor`, and `RollbackValidator` ports. Calling those ports is permitted only after the orchestrator completes scope and approval checks.

## Flow

```text
SAFE_STOPPED
  -> validate known-good immutable checkpoint
  -> require exact task-scoped EXECUTE_ROLLBACK approval
  -> ROLLING_BACK
  -> execute ordered idempotent actions
  -> validate restored source/artifacts/build/tests/data
  -> SAFE_STOPPED + task ROLLED_BACK
  -> human review and approved replan/checkpoint before resume
```

Invalid checkpoints are blocked. Missing approval produces `REQUIRES_HUMAN_APPROVAL` and invokes no action. Action or post-rollback validation failure produces `FAILED_SAFE_STOPPED`. A successful rollback produces `ROLLED_BACK_SAFE_STOPPED`; rollback never implies permission to continue the DAG.

## Request contract

A request contains a stable rollback ID, failed task ID, trigger, immutable checkpoint, and ordered actions. Each action contains an action ID, type, exact target, and idempotency key. Supported types are file/artifact restoration, commit revert, schema rollback, and deployment rollback. Schema rollback additionally requires a database-backup reference.

The checkpoint carries its state revision, Git commit, snapshot URI/hash, optional database backup, and known-good classification. The verifier must independently verify these values before approval is consumed.

## Approval and audit

Execution requires an unexpired `WorkflowState.Approval` referenced by the task whose action is exactly `EXECUTE_ROLLBACK`. Approval cannot be inferred from a general design/release approval.

Rollback state and audit events record workflow/rollback/task/checkpoint IDs, trigger/reason, action count, status, and timestamp. `WorkflowState.rollbacks` is append-only through `rollbackEvent(...)`. Success marks the task `ROLLED_BACK`; failures preserve `safeStopReason` and `recommendedHumanAction`.

## Required runtime adapters

- Git adapter should prefer auditable, non-history-rewriting operations such as a scoped revert; broad reset/clean operations require separately constrained policy.
- Database adapter must verify the backup, migration version, transaction/lock strategy, and data compatibility before execution.
- Artifact/deployment adapters must verify immutable digests and environment identity.
- Validation must rebuild and rerun relevant tests/security/data-integrity checks after restoration.

These adapters, distributed locks/leases, durable compare-and-swap state persistence, secret handling, and actual Git/MySQL integration remain intentionally unimplemented.
