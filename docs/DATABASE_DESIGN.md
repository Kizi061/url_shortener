# URL Shortener Database Design

## 1. Scope

This design keeps the first implementation operationally simple while adding link lifecycle controls and basic redirect metrics. MySQL remains the system of record, Flyway owns production schema changes, and Hibernate validates rather than mutates the runtime schema.

The executable migrations are:

- `V1__create_short_urls.sql`: baseline table and uniqueness constraints.
- `V2__add_link_lifecycle_and_metrics.sql`: expiration, active state, counters, timestamps, collation, and maintenance index.
- `V3__backfill_link_timestamps.sql`: legacy-row backfill and required timestamp constraints.

## 2. Production schema

The combined schema after all migrations is equivalent to:

```sql
CREATE TABLE short_urls (
    id BIGINT NOT NULL AUTO_INCREMENT,
    short_code VARCHAR(6)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    short_url VARCHAR(512) NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    original_url_hash VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    click_count BIGINT NOT NULL DEFAULT 0,
    last_accessed_timestamp DATETIME(6) NOT NULL,
    created_timestamp DATETIME(6) NOT NULL,

    CONSTRAINT pk_short_urls
        PRIMARY KEY (id),
    CONSTRAINT uk_short_urls_short_code
        UNIQUE (short_code),
    CONSTRAINT uk_short_urls_original_url_hash
        UNIQUE (original_url_hash),
    CONSTRAINT ck_short_urls_click_count_nonnegative
        CHECK (click_count >= 0),

    INDEX idx_short_urls_active_expires_at (active, expires_at)
) ENGINE=InnoDB;
```

`original_url_hash` is nullable only for compatibility with records created before the hash was introduced. Every record created by the current application supplies a SHA-256 hash.

## 3. Indexes

| Index | Type | Supports | Reasoning |
|---|---|---|---|
| `PRIMARY (id)` | Unique clustered InnoDB key | Internal identity and atomic metric update | Small stable key for foreign references and row updates |
| `uk_short_urls_short_code (short_code)` | Unique | Redirect lookup and code-allocation correctness | A redirect resolves at most one row; uniqueness is enforced under concurrent creation |
| `uk_short_urls_original_url_hash (original_url_hash)` | Unique | Existing-URL lookup and duplicate prevention | Fixed-width key avoids indexing a 2,048-character URL and closes concurrent duplicate races |
| `idx_short_urls_active_expires_at (active, expires_at)` | Non-unique composite | Expiration scans and lifecycle maintenance | Supports jobs that find active links whose expiration time has passed |

No index is added to `click_count` or `last_accessed_timestamp` initially. Both fields change on redirects; indexing them would add write amplification and page churn without helping the primary redirect lookup.

The redirect query starts with the unique `short_code` predicate and then checks `active` and `expires_at` on the single matching row. A composite redirect index is unnecessary because `short_code` is already unique.

`ascii_bin` makes six-character Base62 codes case-sensitive, so `aB12Cd` and `Ab12Cd` remain distinct values. The URL hash is lowercase hexadecimal, but binary collation keeps its comparison behavior explicit.

## 4. Column rationale

| Column | Rationale |
|---|---|
| `id` | Surrogate primary key keeps internal references independent of the public code and gives InnoDB a compact clustered key. |
| `short_code` | Public six-character lookup key. Required and unique because one code must resolve to exactly one destination. |
| `short_url` | Stores the complete URL returned at creation, as required by the product contract. It can become stale if the public hostname changes. |
| `original_url` | Authoritative redirect destination and final equality check after a hash lookup. Length 2048 covers normal browser URLs without using an unbounded text type. |
| `original_url_hash` | SHA-256 hexadecimal key for indexed exact-URL deduplication. It avoids MySQL index-length problems on the full URL. |
| `expires_at` | Required UTC instant after which resolution returns 404. It is one calendar month after creation. V3 applies the same rule to legacy rows. |
| `active` | Administrative lifecycle switch. `FALSE` disables a link immediately without deleting its history. Default `TRUE` keeps creation simple. |
| `click_count` | Cumulative count of successful redirects and repeated submissions of an already stored original URL. `BIGINT` provides substantial headroom and a check constraint prevents negative values. |
| `last_accessed_timestamp` | Required UTC time of the most recent successful redirect or repeated existing-URL submission. It is initialized to the exact creation instant for new and migrated legacy links. |
| `created_timestamp` | Immutable UTC creation time for audit, retention, and operational analysis. |

All time fields use microsecond-capable `DATETIME(6)` and are mapped to Java `Instant`. The application and Hibernate JDBC configuration use UTC.

## 5. Redirect and metric update

The current redirect path uses two statements:

```sql
SELECT ...
FROM short_urls
WHERE short_code = ?
  AND active = TRUE
  AND (expires_at IS NULL OR expires_at > ?);

UPDATE short_urls
SET click_count = click_count + 1,
    last_accessed_timestamp = ?
WHERE id = ?
  AND active = TRUE
  AND (expires_at IS NULL OR expires_at > ?);
```

The second statement is atomic and repeats the lifecycle predicates. If a link is deactivated or expires after the select but before the update, zero rows are updated and the application returns 404 rather than redirecting stale state.

Successful redirect decisions increment the count. Re-submitting an existing original URL also increments it atomically and refreshes last access. Unknown, inactive, and expired redirect codes do not.

## 6. Concurrency risks and controls

### Lost click increments

Risk: reading `click_count`, incrementing it in Java, and saving the entity would allow concurrent redirects to overwrite one another.

Control: use `click_count = click_count + 1` in an atomic SQL update. InnoDB serializes updates to the same row, preserving increments.

### Lifecycle race

Risk: a link can be deactivated or pass its expiration time between lookup and redirect.

Control: repeat `active` and expiration predicates in the atomic metric update. A zero-row update aborts the redirect.

### Duplicate original URLs

Risk: two creation requests can both observe that an original URL is absent.

Control: the unique URL hash constraint chooses one winner. The losing request rereads and returns the winning mapping. The service also compares the original URL string before returning it, protecting correctness in the theoretical event of a SHA-256 collision.

### Duplicate short codes

Risk: two generators can produce the same six-character code.

Control: check for existence, enforce the unique code index, catch concurrent constraint failures, and retry with a fixed maximum attempt count.

### Hot-link row contention

Risk: a highly popular code causes every redirect to update one InnoDB row. Row locks serialize those writes and can dominate redirect latency even when reads are cached.

Current choice: accept the synchronous update for initial simplicity and exact counters at modest traffic.

### Counter overflow

Risk: a signed `BIGINT` can theoretically overflow.

Control: its maximum is far beyond the expected lifetime traffic of this implementation. Monitor instead of adding complexity now.

### Migration locking

Risk: `ALTER TABLE` can block or rebuild a large production table depending on MySQL version and operation.

Current choice: the table is small, so the V2 migration is intentionally straightforward. For a large table, split additions into online-compatible steps, backfill in batches, and add constraints/indexes separately.

## 7. Flyway migration policy

Runtime configuration uses:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    baseline-on-migrate: false
    baseline-version: 1
```

For a new empty database, Flyway applies V1 followed by V2. For an existing pre-Flyway database, set `FLYWAY_BASELINE_ON_MIGRATE=true` for the first controlled startup only; Flyway records version 1 as the baseline and applies V2. Disable the flag afterward. Automatic baselining should not be a permanent production default because pointing the application at the wrong non-empty schema could hide a configuration mistake.

The test profile disables Flyway and uses Hibernate `create-drop` with H2 so unit and integration tests remain isolated and fast.

## 8. Growth to millions of redirects per day

The metadata schema can remain, but the synchronous metric design should change before very high traffic.

### Separate the read path from analytics writes

- Resolve links from a cache such as Redis, with MySQL as the source of truth.
- Publish click events asynchronously to a durable stream or queue.
- Aggregate counts in batches and periodically merge them into MySQL or an analytics store.
- Accept that displayed analytics may be eventually consistent while redirects stay fast.

This removes the hot-row update from every redirect and is the most important scaling change.

### Scale lookup capacity

- Cache the mapping by short code, including active state and expiration.
- Use read replicas for cache misses if replication lag is acceptable for lifecycle changes.
- Invalidate or version cache entries when links are disabled, edited, or expired.
- Keep connection pools bounded so traffic spikes do not overwhelm MySQL.

### Separate operational and analytical data

- Keep current destination and lifecycle metadata in the primary table.
- Store detailed click events in a stream, columnar warehouse, or time-series/analytics system.
- Avoid adding per-click rows or many changing analytics indexes to the redirect database.

### Revisit code capacity

Six Base62 characters provide about 56.8 billion combinations, but collision frequency rises long before the space is exhausted. Monitor retry rates and move to seven or eight characters before allocation latency becomes material.

### Operational changes

- Use online schema-change tooling and expand/backfill/contract migrations.
- Add database backups, point-in-time recovery, replicas, failover testing, and capacity alerts.
- Define retention for inactive and expired records; archive rather than immediately delete if auditability matters.
- Load-test skewed traffic, because one viral link stresses counters differently from evenly distributed redirects.

## 9. Intentionally deferred features

To keep the initial implementation simple, the current change does not add:

- A public API for setting expiration or toggling active state.
- Detailed per-click event storage.
- Geographic, device, or referrer analytics.
- Background expiration deletion.
- Cache infrastructure.
- Approximate or sharded counters.

The schema supports lifecycle values now; an administrative API can be added separately without changing redirect table fundamentals.
