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

Hibernate creates or updates the `short_urls` table. A database-level unique constraint protects `short_code` in addition to the service collision check and bounded retry logic.

## Start the frontend

In another terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. If the API is hosted elsewhere, copy `.env.example` to `.env.local` and change `VITE_API_BASE_URL`. Set the matching backend `APP_ALLOWED_ORIGIN` when the frontend origin changes.

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

Visiting `GET /aB12Cd` returns `302 Found` with the original URL in the `Location` header.

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
