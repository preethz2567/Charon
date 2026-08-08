# Charon

A durable, exactly-once, Postgres-backed job queue system built in Spring Boot from scratch (no Celery, Sidekiq, or Quartz).

## Milestone 9 Complete (Stretch Goal: Dependencies)
- Added a `parent_job_id` column to jobs via Flyway.
- Dependent jobs (`parent_job_id` IS NOT NULL) wait in `PENDING` state and are strictly excluded from polling until their parent job completes (`DONE`).
- If a parent job fails permanently (`DEAD`), a background reclaimer aggressively marks all orphaned dependent jobs as `DEAD` with the reason `"Parent job died"`, preventing them from waiting infinitely.
- Tests included to prove the dependency blocking logic and the cascading dead-letter logic.

## Features & Guarantees
- **Atomic Claims**: Concurrent workers will never claim the same job thanks to `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Crash Recovery**: If a worker crashes mid-job, the job's lease expires and is automatically reclaimed.
- **Exponential Backoff**: Failing jobs are automatically retried with exponential backoff (`2^attempts + jitter`) up to a configurable max attempt count.
- **Dead-Letter Queue**: Jobs that consistently fail are moved to a dead-letter queue where they can be inspected and manually replayed.
- **Exactly-Once Idempotency**: Jobs can be keyed with an `idempotency_key` which guarantees that the underlying work (e.g., charging a wallet) only ever happens once, regardless of retries or network drops.

---

## How to Run and Demo

### 1. Prerequisites
- **Java 21**
- **Maven**
- **Docker** (to run PostgreSQL via Testcontainers or manually)

### 2. Start PostgreSQL
If you want to run the app manually against a database, spin up Postgres using Docker:
```bash
docker run --name charon-postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=charon -p 5432:5432 -d postgres:16-alpine
```

Make sure your `src/main/resources/application.properties` (or your environment variables) point to `jdbc:postgresql://localhost:5432/charon` with the correct credentials. Spring Boot Flyway will automatically migrate the schema on startup.

### 3. Start the Application
Run the Spring Boot application using Maven:
```bash
mvn spring-boot:run
```

*(Note: If you run tests, Testcontainers will automatically spin up its own ephemeral Postgres instance without needing the manual docker command).*

---

## Step-by-Step Demo Script

### A. Show Enqueue & Processing
1. Open a terminal and check the queue status:
   ```bash
   curl http://localhost:8080/jobs/queue/status
   ```
   *Expected: Counts are 0.*
2. Enqueue a basic job:
   ```bash
   curl -X POST http://localhost:8080/jobs \
        -H "Content-Type: application/json" \
        -d '{"priority":10, "payload":"{\"task\":\"say_hello\"}"}'
   ```
3. Look at your Spring Boot logs. You will see:
   `Worker {id} claimed job 1...` followed by `done` 2 seconds later.

### B. Show Crash Recovery (Reclaim)
1. Stop your Spring Boot app (Ctrl+C).
2. Start it again.
3. Quickly enqueue a job:
   ```bash
   curl -X POST http://localhost:8080/jobs \
        -H "Content-Type: application/json" \
        -d '{"priority":10, "payload":"{\"task\":\"say_hello\"}"}'
   ```
4. Immediately hit **Ctrl+C** to kill the Spring Boot app as soon as you see `Worker {id} claimed job...`, but *before* you see `done`.
5. Start the app again (`mvn spring-boot:run`).
6. Wait 5 seconds. The `JobReclaimer` will detect the orphaned lease, revert it to `PENDING`, and the worker will instantly pick it up and process it to completion.

### C. Show Retry & Exponential Backoff
1. Enqueue a job specifically designed to fail:
   ```bash
   curl -X POST http://localhost:8080/jobs \
        -H "Content-Type: application/json" \
        -d '{"priority":10, "payload":"{\"task\":\"test\", \"fail\":true}"}'
   ```
2. Watch the logs. The worker will attempt the job, catch the exception, and log:
   `Job {id} failed. Computed delay: Xs (2^1 + Ys jitter). Next run_at: ... (Attempt 1/3)`
3. It will wait, try again, back off further, and finally reach attempt 3.

### D. Show Dead-Letter Queue & Replay
1. Once the failing job from the previous step reaches attempt 3, the logs will show it is marked as `DEAD`.
2. Inspect the Dead-Letter Queue:
   ```bash
   curl http://localhost:8080/jobs/dead-letters
   ```
   *Expected: You will see your failed job in the JSON list with `lastError` populated.*
3. Replay the dead job (assuming its ID is 2):
   ```bash
   curl -X POST http://localhost:8080/jobs/dead-letters/2/replay
   ```
4. The job is instantly moved back to `PENDING`, attempts are reset to 0, and the worker will pick it up to try again!

### E. Show Exactly-Once Idempotency
1. We have a special `charge_wallet` task that deducts money and relies on an idempotency key.
2. Enqueue the charge job with a unique key:
   ```bash
   curl -X POST http://localhost:8080/jobs \
        -H "Content-Type: application/json" \
        -d '{"priority":1, "idempotencyKey":"charge-555", "payload":"{\"task\":\"charge_wallet\", \"user_id\":\"alice\"}"}'
   ```
3. Check the logs: `Successfully debited 50 from user alice and recorded idempotency key charge-555...`
4. Enqueue the **exact same request** again:
   ```bash
   curl -X POST http://localhost:8080/jobs \
        -H "Content-Type: application/json" \
        -d '{"priority":1, "idempotencyKey":"charge-555", "payload":"{\"task\":\"charge_wallet\", \"user_id\":\"alice\"}"}'
   ```
5. Check the logs: `Idempotency key charge-555 already applied. Skipping debit for Job...`
6. The job is marked `DONE`, but the side-effect (wallet debit) was safely bypassed.
