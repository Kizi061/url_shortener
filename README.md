# URL Shortener

A full-stack URL shortener with a React/Vite frontend and a layered Spring Boot REST API. The backend validates HTTP/HTTPS URLs, securely generates globally unique six-character codes, persists them in MySQL, and redirects short-link visitors to the original URL.

## Project structure

```text
backend/   Java 17, Spring Boot 3, Spring Web, Spring Data JPA, Maven
frontend/  React, JavaScript, Vite, responsive CSS
```

## Design documentation

- [System design](docs/SYSTEM_DESIGN.md) - implemented architecture, components, data model, API flows, deployment, testing, and known limitations.
- [Architecture decisions](docs/ARCHITECTURE_DECISIONS.md) - design reasoning, alternatives, tradeoffs, and future decision points.
- [Database design](docs/DATABASE_DESIGN.md) - production schema, indexes, column rationale, concurrency controls, migrations, and high-traffic evolution.

## Prerequisites

- Java 17+
- Maven 3.9+
- Node.js 18+
- MySQL 8+

## Start MySQL and the backend

The default configuration connects to `jdbc:mysql://localhost:3306/url_shortener`, creates the database when permitted, and uses `root` / `password`. Override those defaults with environment variables:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/url_shortener?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
$env:APP_BASE_URL = "http://localhost:8080"
cd backend
mvn spring-boot:run
```

Flyway creates and versions the `short_urls` table, and Hibernate validates it at runtime. Database constraints protect short-code and original-URL uniqueness.

If this is an existing database created before Flyway was added, set `$env:FLYWAY_BASELINE_ON_MIGRATE = "true"` for the first controlled startup only, then remove it. New databases do not need this setting.

## Start the frontend

In another terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. If the API is hosted elsewhere, copy `.env.example` to `.env.local` and change `VITE_API_BASE_URL`. Set the matching backend `APP_ALLOWED_ORIGIN` when the frontend origin changes.

If port `8080` is already occupied, start the backend and frontend with matching overrides:

```powershell
# Backend terminal
$env:SERVER_PORT = "8081"
$env:APP_BASE_URL = "http://localhost:8081"
cd backend
mvn spring-boot:run

# Frontend terminal
$env:VITE_API_BASE_URL = "http://localhost:8081"
cd frontend
npm run dev
```

## API

Create a short URL:

```http
POST /api/urls
Content-Type: application/json

{
  "originalUrl": "https://www.example.com/products/category/item/12345"
}
```

Successful response (`201 Created`):

```json
{
  "shortCode": "aB12Cd",
  "shortUrl": "http://localhost:8080/aB12Cd",
  "originalUrl": "https://www.example.com/products/category/item/12345"
}
```

Submitting the same `originalUrl` again returns the existing mapping with `200 OK`; it does not create another database record or short code.

New links expire one calendar month after creation. Their creation and initial last-access timestamps are identical. Re-submitting an existing URL atomically increments its click count and refreshes its last-access timestamp.

Visiting `GET /aB12Cd` returns `302 Found` with the original URL in the `Location` header.

## Access analytics

After creating a short URL, the frontend displays an **Activity analytics** section. Open the short URL, return to the frontend, and select **Refresh** to retrieve the latest aggregate values.

Analytics can also be read directly using the short code:

```http
GET /api/urls/aB12Cd/analytics
```

For a backend running on the default port:

```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8080/api/urls/aB12Cd/analytics" `
  -Method Get
```

If the backend is running on port `8081`, use:

```text
http://localhost:8081/api/urls/aB12Cd/analytics
```

Example response:

```json
{
  "shortCode": "aB12Cd",
  "accessReuseCount": 7,
  "lastRecordedActivityAt": "2026-08-20T10:37:31Z",
  "hasRecordedActivity": true
}
```

Analytics use the existing aggregate fields:

- `accessReuseCount` comes from `click_count`. It includes successful redirects and repeated submissions of an existing original URL, so it is not a redirect-only or unique-visitor count.
- `lastRecordedActivityAt` comes from `last_accessed_timestamp` and is returned in UTC. It is initialized to creation time; when `hasRecordedActivity` is `false`, the timestamp does not prove that a redirect or repeated submission occurred.
- Reading analytics does not increment the count or update the timestamp.
- Unknown, disabled, and expired short codes return the existing `404 SHORT_URL_NOT_FOUND` response.

The analytics endpoint is currently unauthenticated. Anyone who knows a valid active short code can read its aggregate activity values; do not treat the endpoint as private analytics.

Errors share one shape:

```json
{
  "code": "SHORT_URL_NOT_FOUND",
  "message": "Short URL was not found.",
  "timestamp": "2026-08-19T18:30:00Z"
}
```

## Tests and builds

Backend tests use an isolated H2 database in MySQL compatibility mode:

```powershell
cd backend
mvn test
```

Build the frontend:

```powershell
cd frontend
npm install
npm run build
```
