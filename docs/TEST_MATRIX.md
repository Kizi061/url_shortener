# URL Shortener QA Test Matrix

## 1. Purpose

This matrix covers the URL-shortener behaviors requested for QA review: happy paths, invalid input, short-code collisions, expired and disabled URLs, unknown short codes, click-count updates, concurrent redirects, and repository/database failures.

Priority definitions:

- **P0** — protects core redirect correctness, data integrity, or the public error contract.
- **P1** — protects an important branch or operational failure mode.
- **P2** — useful defense-in-depth or extended-environment coverage.

Coverage status refers to the automated suite in this repository after the accompanying test additions.

## 2. Unit tests

Unit tests isolate validation, generation, and service orchestration with mocked persistence dependencies.

| ID    | Priority | Area | Scenario | Expected result | Risk being mitigated | Coverage |
|-------|----|------------|----------|-----------------|----------------------|----------|
| UT-01 | P0 | Happy path | Create a mapping for a valid absolute HTTPS URL when the generated code is unused. | A new result contains the code, public short URL, and original URL; initial lifecycle fields are correct. | Incorrect mapping creation or malformed response. | Automated: `ShortUrlServiceTest` |
| UT-02 | P1 | Happy path | Submit an exact URL that already has a mapping. | Existing mapping is returned, access metadata is updated, and no new code or row is created. | Duplicate mappings and wasted code space. | Automated: `ShortUrlServiceTest` |
| UT-03 | P0 | Happy path / click count | Resolve an active, unexpired code. | Original URL is returned only after one successful atomic access update. | Redirecting without recording the access decision. | Automated: `ShortUrlServiceTest` |
| UT-04 | P0 | Invalid input | Validate null, empty, and whitespace-only values. | `InvalidUrlException` is raised. | Null handling errors and invalid records. | Automated: `UrlValidatorTest` |
| UT-05 | P0 | Invalid input | Validate relative URLs, unsupported schemes, missing hosts, malformed syntax, and deceptive hostless forms. | Every value is rejected with `InvalidUrlException`. | Open-ended redirects, malformed destinations, and validation bypass. | Automated: `UrlValidatorTest` |
| UT-06 | P1 | Invalid input | Validate well-formed HTTP and HTTPS URLs, including mixed-case schemes and explicit ports. | Values are accepted. | False rejection of legitimate destinations. | Automated: `UrlValidatorTest` |
| UT-07 | P0 | Collision | Generated code already exists during the preliminary lookup. | Service generates another code and saves only the unused value. | Overwriting or reusing another link's code. | Automated: `ShortUrlServiceTest` |
| UT-08 | P0 | Collision | Insert loses a concurrent short-code uniqueness race. | Service rechecks the original URL and retries with a new code when no matching URL was concurrently created. | Race-condition corruption or failed valid requests. | Automated: `ShortUrlServiceTest` |
| UT-09 | P0 | Collision | Another request concurrently creates the same original URL. | Winning mapping is returned and no duplicate logical mapping is exposed. | Duplicate records under concurrent creation. | Automated: `ShortUrlServiceTest` |
| UT-10 | P0 | Collision | Every generation attempt collides. | Retry bound is honored and `ShortCodeGenerationException` is raised. | Infinite loops and resource exhaustion. | Automated: `ShortUrlServiceTest` |
| UT-11 | P0 | Expired/disabled/unknown | Repository returns no eligible redirect candidate. | `ShortUrlNotFoundException` is raised and no access update occurs. | Redirecting expired, disabled, or nonexistent links. | Automated: `ShortUrlServiceTest` |
| UT-12 | P0 | State race | Link becomes disabled or expires after lookup but before the atomic access update. | A zero-row update becomes `ShortUrlNotFoundException`; no redirect URL is returned. | Time-of-check/time-of-use authorization or lifecycle race. | Automated: `ShortUrlServiceTest` |
| UT-13 | P0 | Database failure | Redirect access update throws a data-access exception. | Exception propagates; service does not return the destination. | Redirect success being reported when required state mutation failed. | Automated: `ShortUrlServiceTest` |
| UT-14 | P1 | Database failure | Initial repository lookup fails. | Exception propagates without attempting code generation or persistence. | Masked database outage and unsafe partial work. | Planned |
| UT-15 | P1 | Database failure | Non-uniqueness persistence failure occurs during creation. | Exception propagates rather than being treated as a collision. | Retrying permanent database errors and concealing outages. | Planned |

## 3. Controller tests

Controller tests isolate HTTP mapping, validation, status codes, headers, and error serialization by mocking the service layer.

| ID    | Priority | Area | Scenario | Expected result | Risk being mitigated | Coverage |
|-------|----|------------|----------|-----------------|----------------------|----------|
| CT-01 | P0 | Happy path | `POST /api/urls` creates a new mapping. | `201 Created` and the service response are returned as JSON. | Breaking the primary public API contract. | Automated: `ShortUrlControllerTest` |
| CT-02 | P1 | Happy path | `POST /api/urls` reuses an existing mapping. | `200 OK` is returned rather than `201`. | Clients misinterpreting idempotent reuse as creation. | Automated: `ShortUrlControllerTest` |
| CT-03 | P0 | Happy path | `GET /{shortCode}` resolves successfully. | `302 Found`, correct `Location`, and an empty body are returned. | Redirecting to the wrong destination or returning an incorrect status. | Automated: `ShortUrlControllerTest` |
| CT-04 | P0 | Invalid input | POST body contains a blank URL. | `400`, code `INVALID_REQUEST`, safe message, and timestamp are returned; service is not called. | Validation bypass and unstable client errors. | Automated: `ShortUrlControllerTest` |
| CT-05 | P0 | Invalid input | POST body is missing or malformed JSON. | `400 INVALID_REQUEST` is returned. | Generic 500 responses for client mistakes. | Automated: `ShortUrlControllerTest` |
| CT-06 | P0 | Invalid input | Service rejects a syntactically present but invalid URL. | `400 INVALID_URL` with the validation message is returned. | Loss of the documented validation contract. | Automated: `ShortUrlControllerTest` |
| CT-07 | P0 | Collision | Service exhausts collision retries. | `409 SHORT_CODE_CONFLICT` is returned. | Misclassifying capacity/collision failures as server errors. | Automated: `ShortUrlControllerTest` |
| CT-08 | P0 | Expired/disabled/unknown | Service reports no eligible short code. | `404 SHORT_URL_NOT_FOUND` is returned without revealing whether a link once existed. | Link enumeration and inconsistent lifecycle responses. | Automated: `ShortUrlControllerTest` |
| CT-09 | P0 | Database failure | Service throws a repository/data-access exception. | `500 INTERNAL_SERVER_ERROR` is returned with no database details. | Sensitive exception leakage and unstable error schemas. | Automated: `ShortUrlControllerTest` |
| CT-10 | P1 | Invalid route | Unsupported route or method is requested. | `404` or `405` is returned using the approved error contract. | Misleading `500` responses and poor operability. | Planned; current known limitation |

## 4. Integration tests

Integration tests exercise Spring MVC, service logic, JPA mappings, transactions, and the test database together.

| ID    | Priority | Area | Scenario | Expected result | Risk being mitigated | Coverage |
|-------|----|------------|----------|-----------------|----------------------|----------|
| IT-01 | P0 | Happy path | POST a valid URL through the complete application stack. | Mapping is persisted and `201` response fields match the row. | Layer-integration and ORM mapping defects. | Automated: `ShortUrlIntegrationTest` |
| IT-02 | P1 | Happy path | Repeat an exact POST. | One row remains, original code is returned with `200`, and reuse access metadata updates. | Duplicate persistence under repeated requests. | Automated: `ShortUrlIntegrationTest` |
| IT-03 | P0 | Happy path / click count | Resolve a persisted active link. | `302` points to the original URL and click count increments exactly once. | Disconnect between redirect and analytics persistence. | Automated: `ShortUrlIntegrationTest` |
| IT-04 | P0 | Invalid input | POST an invalid URL through MVC and validation layers. | `400` is returned and no row is stored. | Invalid data reaching persistence. | Automated: `ShortUrlIntegrationTest` |
| IT-05 | P0 | Expired URL | Resolve a link whose expiry is before the access time. | `404`; click count and last-access timestamp remain unchanged. | Expired-link bypass or false analytics. | Automated: `ShortUrlIntegrationTest` |
| IT-06 | P0 | Disabled URL | Resolve an inactive link. | `404`; click count and last-access timestamp remain unchanged. | Administrative disable bypass or false analytics. | Automated: `ShortUrlIntegrationTest` |
| IT-07 | P0 | Unknown code | Resolve a code with no row. | `404 SHORT_URL_NOT_FOUND` is returned. | Incorrect redirect or information leakage. | Automated: `ShortUrlIntegrationTest` |
| IT-08 | P0 | Concurrent redirects | Multiple requests resolve the same active link concurrently. | Every request returns `302`; final click count equals the number of successful requests with no lost updates. | Analytics corruption caused by read-modify-write races. | Automated: `ShortUrlIntegrationTest` |
| IT-09 | P1 | Collision | Persist duplicate short codes directly. | Database uniqueness constraint rejects the duplicate. | Reliance on application-only collision checks. | Planned |
| IT-10 | P1 | Collision | Concurrently create the same original URL. | At most one mapping is persisted and callers converge on the winning mapping. | Duplicate logical mappings under races. | Planned |
| IT-11 | P1 | State race | Disable or expire a link between eligibility lookup and metric update. | Update affects zero rows and redirect is denied. | Time-of-check/time-of-use race across real transactions. | Covered at unit level; integration fault injection planned |
| IT-12 | P1 | Database failure | Database becomes unavailable during creation. | Request returns sanitized `500`; no success response is emitted. | False success and leaked infrastructure details. | Controller-level automated; Testcontainers fault test planned |
| IT-13 | P1 | Database failure | Database becomes unavailable during redirect or counter update. | Request does not redirect and returns a sanitized server error. | Redirecting while required analytics state is uncommitted. | Unit/controller automated; Testcontainers fault test planned |
| IT-14 | P2 | Database compatibility | Run migrations and the suite against the supported MySQL version. | Migrations succeed and behavior matches the H2 suite. | H2/MySQL SQL, collation, and transaction-semantics drift. | Planned for CI/Testcontainers |

## 5. Highest-priority automated additions

The accompanying JUnit additions implement the most consequential gaps that can be tested deterministically in the current build:

1. URL validation boundary and bypass cases.
2. Controller status, header, validation, collision, not-found, and sanitized database-failure contracts.
3. Redirect failure when the atomic click update fails.
4. Concurrent redirects with exact, non-lost click counting.
5. Verification that rejected expired and disabled requests do not change analytics state.

True database-process outage and MySQL-specific concurrency tests remain planned because the current integration profile uses embedded H2 and does not include Testcontainers.
