# URL Shortener Architecture Decisions

## 1. Purpose and status

This document records the architectural reasoning behind the URL Shortener implementation. Each decision describes the context, selected approach, alternatives considered, and consequences.

All decisions are **Accepted** for the current application unless marked otherwise. They can be superseded as scale, security, or product requirements change.

## 2. Decision summary

| ID | Decision | Primary reason |
|---|---|---|
| ADR-001 | Separate React frontend and Spring Boot backend | Clear client/server boundary and independent deployment |
| ADR-002 | Layer the backend by responsibility | Keep business logic out of controllers and make behavior testable |
| ADR-003 | Use REST creation plus HTTP redirect semantics | Simple interoperable API and native browser behavior |
| ADR-004 | Generate random six-character Base62 codes with `SecureRandom` | Meet the required format without exposing sequential identifiers |
| ADR-005 | Combine collision checks, bounded retry, and database uniqueness | Correctness under both normal and concurrent requests |
| ADR-006 | Use MySQL in runtime and H2 for automated integration tests | Durable production persistence with fast isolated tests |
| ADR-007 | Persist both short code and complete short URL | Meet the storage requirement and return stable stored values |
| ADR-008 | Store timestamps as UTC `Instant` and inject `Clock` | Unambiguous time representation and deterministic tests |
| ADR-009 | Centralize API error mapping | Consistent client contract and thin controllers |
| ADR-010 | Externalize runtime addresses, credentials, and CORS origin | Keep environment-specific values outside compiled code |
| ADR-011 | Keep the first version stateless and synchronous | Minimize operational complexity for the current workload |
| ADR-012 | Deduplicate exact original URLs with a SHA-256 uniqueness key | Prevent duplicate mappings safely under concurrent requests |
| ADR-013 | Version lifecycle schema and atomically record access | Preserve redirect correctness while adding simple operational metrics |

## ADR-001: Separate React frontend and Spring Boot backend

**Status:** Accepted

### Context

The application requires a responsive browser UI and a Java 17 REST backend. The redirect URL must work independently of the UI.

### Decision

Maintain two projects:

- `frontend`: React, JavaScript, Vite, and CSS.
- `backend`: Java 17, Spring Boot, Maven, and MySQL access.

The browser calls the backend over JSON/HTTP. The backend serves the redirect endpoint but does not serve the frontend assets in the current development architecture.

### Alternatives considered

- Serve a React build from Spring Boot as one deployable artifact.
- Use server-rendered HTML templates instead of React.
- Implement both layers in JavaScript.

### Consequences

- Frontend and backend can be developed, tested, scaled, and deployed independently.
- CORS must be configured when the two applications use different origins.
- Local development requires two application processes.
- A production reverse proxy can still expose both behind one public hostname.

## ADR-002: Layer the backend by responsibility

**Status:** Accepted

### Context

Validation, generation, persistence, redirects, and exception translation must remain understandable and independently testable. Controllers should not contain business logic.

### Decision

Use these layers and components:

```text
Controller -> Service -> Repository -> Database
                 |-> URL validator
                 |-> Short-code generator

Exceptions -> Global exception handler -> Error DTO
```

### Alternatives considered

- Put all logic in the REST controller.
- Introduce ports/adapters or a full domain-driven architecture.
- Use static utility methods for validation and generation.

### Consequences

- Controllers only translate HTTP concerns.
- Service behavior can be unit-tested with mocked persistence and generation.
- The structure adds several small classes, but each has a narrow responsibility.
- A full hexagonal architecture was considered unnecessary for the current domain size.

## ADR-003: Use REST creation plus HTTP redirect semantics

**Status:** Accepted

### Context

Clients must create mappings, while browsers must resolve them naturally.

### Decision

- `POST /api/urls` accepts JSON and returns `201 Created` with the code and URLs.
- `GET /{shortCode}` returns `302 Found` with the destination in the `Location` header.

### Alternatives considered

- Return `200 OK` for creation.
- Return the destination as JSON and require client-side navigation.
- Use `301 Moved Permanently`, `307 Temporary Redirect`, or `308 Permanent Redirect`.

### Consequences

- `POST` expresses creation and is not cached as a simple lookup.
- `302` is widely understood by browsers and allows destination mappings to be changed later without permanent caching semantics.
- A root-level code produces compact links but reserves root path space and requires care when adding future backend routes.

## ADR-004: Generate random six-character Base62 codes with `SecureRandom`

**Status:** Accepted

### Context

The required short code is six alphanumeric characters and must not duplicate an existing record.

### Decision

Sample six values from this 62-character alphabet using `SecureRandom`:

```text
abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789
```

This produces 56,800,235,584 possible codes.

### Alternatives considered

- Encode an auto-incrementing database ID.
- Hash the original URL.
- Generate a UUID and truncate it.
- Use the non-cryptographic `Random` class.

### Consequences

- Codes do not reveal record counts or creation order.
- The same original URL may receive multiple different short codes.
- Random generation requires collision detection.
- Six characters impose a fixed capacity and increasing collision probability as usage grows.
- `SecureRandom` costs slightly more CPU than `Random`, but code creation is not the redirect hot path.

## ADR-005: Combine collision checks, bounded retry, and database uniqueness

**Status:** Accepted

### Context

An application-level existence check alone has a race: two requests can both observe an unused code before either inserts it.

### Decision

For a maximum of ten attempts:

1. Generate a candidate.
2. Query `existsByShortCode`.
3. If absent, call `saveAndFlush`.
4. If the database reports a uniqueness violation, generate another candidate.
5. Return `409 Conflict` if all attempts are exhausted.

Also enforce a unique database constraint on `short_code`.

### Alternatives considered

- Trust only the existence query.
- Trust only the unique constraint and use exceptions for every collision.
- Lock a table or central sequence during code allocation.
- Retry without a maximum.

### Consequences

- Correctness does not depend on a timing-sensitive pre-check.
- Normal collisions avoid an attempted failing insert.
- Retry is bounded, preventing runaway requests.
- The database remains the source of truth for uniqueness.
- The current catch treats any `DataIntegrityViolationException` during insertion as a possible collision; more specific constraint classification can be added if the schema becomes more complex.

## ADR-006: Use MySQL in runtime and H2 for automated integration tests

**Status:** Accepted

### Context

Runtime persistence must use MySQL, while tests should be repeatable without depending on a developer's database state.

### Decision

- Use MySQL Connector/J at runtime.
- Use H2 only in test scope with MySQL compatibility mode.
- Activate H2 through the `test` Spring profile.

### Alternatives considered

- Run all tests against a shared local MySQL schema.
- Use Testcontainers with a real ephemeral MySQL instance.
- Use only mocked repositories.

### Consequences

- Tests run quickly and do not need MySQL credentials.
- Integration tests still verify Spring MVC, JPA, Hibernate, and repository behavior together.
- H2 is not perfectly identical to MySQL, especially for collation and DDL behavior.
- Testcontainers is the recommended addition when exact MySQL compatibility becomes important in CI.

## ADR-007: Persist both short code and complete short URL

**Status:** Accepted because it is an explicit product requirement

### Context

The required stored fields are short code, short URL, original URL, and creation timestamp.

### Decision

Construct the short URL from `APP_BASE_URL + "/" + shortCode` and store it with the mapping.

### Alternatives considered

- Store only the short code and construct the complete URL for every response.
- Store a hostname/version separately from the code.

### Consequences

- The stored record contains exactly what was returned at creation time.
- Read responses do not need to reconstruct the value.
- If the public hostname changes, existing rows retain the previous short URL even though code resolution can still work on the new hostname.
- If requirements become flexible, deriving `shortUrl` from the code is a simpler long-term normalization.

## ADR-008: Store timestamps as UTC `Instant` and inject `Clock`

**Status:** Accepted

### Context

Creation timestamps must be in UTC and time-based behavior must be testable.

### Decision

- Represent `createdTimestamp` with `java.time.Instant`.
- Configure Hibernate JDBC time handling as UTC.
- Inject a UTC `Clock` into the service.

### Alternatives considered

- Use `LocalDateTime` and assume a server timezone.
- Call `Instant.now()` directly throughout the code.
- Let the database generate the timestamp.

### Consequences

- Stored instants are globally unambiguous.
- Unit tests can supply a fixed clock.
- Database and Java time handling remain explicit.

## ADR-009: Centralize API error mapping

**Status:** Accepted

### Context

Clients need predictable errors for validation, missing codes, conflicts, and failures. Repeating error construction in controllers would mix concerns.

### Decision

Use `@RestControllerAdvice` with a shared `ErrorResponse` containing:

- Stable machine-readable code.
- Human-readable message.
- UTC timestamp.

Unexpected exceptions are logged server-side and exposed as a generic message.

### Alternatives considered

- Handle exceptions inside every controller method.
- Return Spring Boot's default error structure.
- Adopt RFC 9457 Problem Details immediately.

### Consequences

- Error JSON is consistent across implemented use cases.
- Internal stack traces are not disclosed to API clients.
- The catch-all currently also captures some framework routing exceptions, causing unsupported paths such as `GET /api/urls` to appear as 500. A specific 404/405 mapping should supersede that behavior.
- Problem Details remains a viable future standardization.

## ADR-010: Externalize runtime addresses, credentials, and CORS origin

**Status:** Accepted

### Context

Database credentials and public hostnames differ by environment and must not require source changes.

### Decision

Use environment-backed Spring properties for database access, public base URL, and allowed frontend origin. Use a Vite environment variable for the API base URL.

### Alternatives considered

- Hard-code local values.
- Maintain a separate committed configuration file for each environment.
- Add a secrets-management product in the initial local build.

### Consequences

- The same artifact can run in different environments.
- Credentials do not need to be committed.
- Operators must configure matching frontend and backend origins.
- Production should supply secrets from a managed secret store rather than plain shell history or committed files.

## ADR-011: Keep the first version stateless and synchronous

**Status:** Accepted

### Context

The two use cases are small database transactions and do not require background processing.

### Decision

Handle creation and lookup synchronously. Keep no required mapping state in application memory.

### Alternatives considered

- Add Redis as a required cache.
- Generate codes through a message queue.
- Store mappings in application memory.

### Consequences

- Deployment and failure behavior remain simple.
- Multiple backend instances can share one MySQL database.
- Redirect latency includes a database query.
- Caching can be added later based on observed read traffic rather than assumed demand.

## ADR-012: Deduplicate exact original URLs with a SHA-256 uniqueness key

**Status:** Accepted

### Context

Repeated submissions of the same original URL must return the existing short code rather than create duplicate records. An application lookup alone is not sufficient because concurrent requests can both observe no existing row before either insert commits. A direct unique index on a 2,048-character `original_url` can exceed practical MySQL index-key limits.

### Decision

Compute a lowercase SHA-256 hexadecimal value from the exact original URL and store it in `original_url_hash` with a unique database constraint.

The create flow:

1. Validates the URL and computes its hash.
2. Looks up an existing mapping by hash and verifies the original URL string.
3. Returns the existing mapping with `200 OK` when found.
4. Creates a new mapping with `201 Created` when absent.
5. If a concurrent insert wins, catches the constraint violation, rereads the mapping, and returns it.
6. Falls back to an exact original-URL query for records created before the hash column existed.

### Alternatives considered

- Use only `findByOriginalUrl` without a database uniqueness constraint.
- Add a unique index directly to the full original URL column.
- Canonicalize URLs before comparison.
- Return a new short code for every request.

### Consequences

- Exact repeated URLs reuse one database record and short code.
- Concurrent requests cannot create duplicate new mappings.
- The 64-character hash is efficient to index compared with the full URL.
- The original string comparison prevents a theoretical hash collision from returning an unrelated mapping.
- URL equivalence remains deliberately conservative: variations in case, query ordering, encoding, or trailing slashes are treated as different strings.
- The hash column remains nullable so the Flyway migration can preserve legacy rows; all newly created rows populate it.

## ADR-013: Version lifecycle schema and atomically record access

**Status:** Accepted

### Context

Links need optional expiration, an administrative active state, a click count, and a last-access time. Concurrent redirects must not lose increments, and production schema changes should be repeatable.

### Decision

- Add nullable `expires_at`, required `active`, required `click_count`, and nullable `last_accessed_timestamp` columns.
- Give new links a one-calendar-month lifetime and initialize last access from the same instant as creation.
- Count both successful redirects and repeated submissions of an existing URL as access events.
- Keep the unique short-code index as the redirect access path; evaluate lifecycle predicates after the unique lookup.
- Increment clicks and set last access with one conditional atomic update.
- Repeat active and expiration checks during the update to close the lookup/update race.
- Manage MySQL schema changes with Flyway and set Hibernate to schema validation in runtime profiles.
- Add only `(active, expires_at)` for maintenance scans; do not index frequently updated metric columns.

### Alternatives considered

- Read, increment, and save the counter through a managed entity.
- Add a click-event row synchronously for every redirect.
- Add active and expiration to a composite redirect index.
- Continue using Hibernate `ddl-auto: update` in production.

### Consequences

- Concurrent SQL increments are not lost.
- Inactive and expired links consistently return 404.
- Each successful redirect currently performs one read and one write, which is simple but creates hot-row contention for viral links.
- Flyway provides reviewable schema history, while the one-time baseline flag supports the existing pre-Flyway database.
- At high traffic, click events should move off the synchronous redirect path and become eventually consistent.

## 3. Decision drivers

The decisions above prioritize:

1. Correctness of redirect mappings and code uniqueness.
2. Clear responsibility boundaries.
3. Simple local operation and testing.
4. A small public API built on standard HTTP behavior.
5. Incremental evolution without introducing infrastructure before it is needed.

## 4. Architecture fitness checks

The following checks indicate whether the current architecture remains appropriate:

| Signal | Current expectation | Revisit decision when |
|---|---|---|
| Code creation rate | Low to moderate | Database collision checks or write contention become material |
| Redirect traffic | Served directly from MySQL | Read latency or database load breaches service objectives |
| Record count | Far below six-character practical limits | Collision retry frequency grows measurably |
| Deployment count | Local or a small number of instances | Multi-region consistency or failover becomes required |
| Abuse exposure | Controlled development use | Service becomes publicly writable |
| Schema change rate | Low | Multiple environments require repeatable migrations |
| Client count | One React UI plus simple API consumers | Versioning or backward compatibility becomes necessary |

## 5. Decisions recommended next

These decisions are intentionally deferred and should be recorded before implementation:

- Production rollout strategy for online Flyway migrations on large tables.
- Production deployment topology and TLS termination.
- Rate-limiting location: application, gateway, or reverse proxy.
- Link expiration and cleanup policy.
- Case-sensitive MySQL collation for Base62 codes.
- Cache selection and invalidation policy.
- Observability standard for logs, metrics, and traces.
- Authentication and ownership model if links become private or manageable.
