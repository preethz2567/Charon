# Charon

A job queue system built in Spring Boot and Postgres from scratch.

## Milestone 3 Complete
- Added a `reclaimStaleJobs` scheduled task running every 5 seconds.
- Automatically reclaims jobs that are stuck in `LEASED` state past their `lockedUntil` timestamp (e.g. if a worker crashes).
- Safely resets the job's status to `PENDING` and clears the lock fields so it can be picked up by the next available worker.
- Added tests to prove that stale jobs are properly reclaimed while active jobs are left untouched.

### Simulating a crash
1. Start the application (`mvn spring-boot:run`).
2. Enqueue a job via `POST /jobs`.
3. Watch the logs. When the worker prints `Worker {id} claimed job {id}...`, hit `Ctrl+C` immediately before it prints `done` 2 seconds later.
4. Restart the application.
5. Within 5 seconds, the `JobReclaimer` will revert the job to `PENDING`, and the worker will pick it up and process it again.

## Milestone 2 Complete
- Added a `@Scheduled` background worker that polls every 1 second.
- Implemented an atomic claim mechanism using `SELECT ... FOR UPDATE SKIP LOCKED` natively via Postgres.
- The worker claims the highest-priority, earliest-due job, marking it `LEASED`.
- Implemented a fake job processor that logs the claim, sleeps for 2 seconds, and marks the job as `DONE`.
- Added integration tests to verify the polling and claiming logic.

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
