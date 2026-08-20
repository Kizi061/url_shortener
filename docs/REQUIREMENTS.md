# Agentic URL Shortener Requirements

## 1. Purpose and interpretation
The assignment defines two related systems:

- The URL-shortener product.
- The agentic engineering orchestrator that produces and validates SDLC outcomes.

## 2. Functional requirements

FR-01 Create short URL
FR-02 Redirect short URL
FR-03 Retrieve URL metadata
FR-04 Record click analytics
FR-05 Support expiration
FR-06 Handle inactive/expired URLs
FR-07 Validate input URL

## 3. Non-functional requirements

NFR-01 Unique short codes
NFR-02 Low redirect latency
NFR-03 Persistent storage
NFR-04 Input validation
NFR-05 Auditability
NFR-06 Testability
NFR-07 Safe error handling
NFR-08 Maintainable architecture

## 4. Agentic orchestration requirements

AR-01 Convert requirements to tasks
AR-02 Represent dependencies explicitly
AR-03 Execute independent tasks in parallel
AR-04 Maintain workflow state
AR-05 Human approval for high-impact operations
AR-06 Retry failed tasks within bounds
AR-07 Safe-stop after retry exhaustion
AR-08 Record decisions and task history
AR-09 Re-plan when upstream requirements change
AR-10 Generate final engineering summary

