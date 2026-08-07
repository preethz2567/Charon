# Charon

A job queue system built in Spring Boot and Postgres from scratch.

## Milestone 1 Complete
- Initialized Spring Boot skeleton with Web, Data JPA, Postgres, Flyway, and Validation.
- Replaced Gradle with Maven.
- Created `jobs` table in Postgres via Flyway migration.
- Added `Job` entity, `JobStatus` enum, and `JobRepository`.
- Added a basic `POST /jobs` endpoint to enqueue jobs.

### Prerequisites
- Java 21
- Maven
- Docker (for Testcontainers)

### How to Run
Ensure you have a PostgreSQL database available, configure your `application.properties` with the database credentials, and start the application:
```bash
mvn spring-boot:run
```

### How to Test
The integration tests use Testcontainers to spin up a real PostgreSQL instance automatically. Make sure Docker is running on your machine, then execute:
```bash
mvn test
```

### Endpoints
**Enqueue a Job**
`POST /jobs`
```json
{
  "priority": 10,
  "runAt": "2026-01-01T10:00:00Z",
  "payload": "{\"task\": \"send_email\"}",
  "idempotencyKey": "email-123",
  "maxAttempts": 5
}
```
Returns a `201 Created` with the saved Job details.
