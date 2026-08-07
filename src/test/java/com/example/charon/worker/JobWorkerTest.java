package com.example.charon.worker;

import com.example.charon.model.Job;
import com.example.charon.model.JobStatus;
import com.example.charon.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.scheduling.enabled=false")
class JobWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobWorker jobWorker;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    void shouldClaimHighestPriorityEarliestDueJob() {
        // Job 1: normal priority, due now
        Job job1 = new Job();
        job1.setPriority(0);
        job1.setRunAt(OffsetDateTime.now().minusMinutes(1));
        job1.setPayload("{\"task\":\"low_priority\"}");
        job1.setIdempotencyKey("task1");
        jobRepository.save(job1);

        // Job 2: high priority, due now
        Job job2 = new Job();
        job2.setPriority(10);
        job2.setRunAt(OffsetDateTime.now().minusMinutes(1));
        job2.setPayload("{\"task\":\"high_priority\"}");
        job2.setIdempotencyKey("task2");
        jobRepository.save(job2);

        // Job 3: high priority, due later (should not be claimed)
        Job job3 = new Job();
        job3.setPriority(10);
        job3.setRunAt(OffsetDateTime.now().plusHours(1));
        job3.setPayload("{\"task\":\"future\"}");
        job3.setIdempotencyKey("task3");
        jobRepository.save(job3);

        // Manually trigger the worker poll
        jobWorker.pollForJobs();

        // Job 2 should be DONE
        Job updatedJob2 = jobRepository.findById(job2.getId()).orElseThrow();
        assertThat(updatedJob2.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(updatedJob2.getLockedBy()).isNull();

        // Job 1 should still be PENDING
        Job updatedJob1 = jobRepository.findById(job1.getId()).orElseThrow();
        assertThat(updatedJob1.getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void shouldReclaimStaleLeasedJobs() {
        // Create a job that was leased but the lock expired
        Job staleJob = new Job();
        staleJob.setStatus(JobStatus.LEASED);
        staleJob.setLockedBy("dead-worker");
        staleJob.setLockedUntil(OffsetDateTime.now().minusSeconds(1));
        staleJob.setPayload("{\"task\":\"stale\"}");
        staleJob.setIdempotencyKey("stale-1");
        jobRepository.save(staleJob);

        // Create a job that is leased but still active
        Job activeJob = new Job();
        activeJob.setStatus(JobStatus.LEASED);
        activeJob.setLockedBy("active-worker");
        activeJob.setLockedUntil(OffsetDateTime.now().plusSeconds(30));
        activeJob.setPayload("{\"task\":\"active\"}");
        activeJob.setIdempotencyKey("active-1");
        jobRepository.save(activeJob);

        // Trigger the reclaimer
        jobWorker.reclaimStaleJobs();

        // The stale job should be PENDING again
        Job reclaimedJob = jobRepository.findById(staleJob.getId()).orElseThrow();
        assertThat(reclaimedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reclaimedJob.getLockedBy()).isNull();
        assertThat(reclaimedJob.getLockedUntil()).isNull();

        // The active job should still be LEASED
        Job untouchedJob = jobRepository.findById(activeJob.getId()).orElseThrow();
        assertThat(untouchedJob.getStatus()).isEqualTo(JobStatus.LEASED);
        assertThat(untouchedJob.getLockedBy()).isEqualTo("active-worker");
    }

    @Test
    void shouldRetryWithBackoffAndFailEventually() {
        // Job with payload causing failure, 0 attempts
        Job failingJob = new Job();
        failingJob.setPriority(5);
        failingJob.setRunAt(OffsetDateTime.now().minusMinutes(1));
        failingJob.setPayload("{\"task\":\"test\", \"fail\":true}");
        failingJob.setIdempotencyKey("fail-1");
        jobRepository.save(failingJob);

        // First poll: fails, attempt 1, backoff calculated
        jobWorker.pollForJobs();

        Job afterFirstAttempt = jobRepository.findById(failingJob.getId()).orElseThrow();
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(afterFirstAttempt.getAttempts()).isEqualTo(1);
        assertThat(afterFirstAttempt.getLastError()).contains("Simulated job failure");
        assertThat(afterFirstAttempt.getRunAt()).isAfter(OffsetDateTime.now());

        // Fast-forward runAt to now and set attempts to maxAttempts - 1 to test FAILED transition
        afterFirstAttempt.setRunAt(OffsetDateTime.now().minusMinutes(1));
        afterFirstAttempt.setAttempts(2); // assuming maxAttempts = 3
        jobRepository.save(afterFirstAttempt);

        // Second poll: reaches max attempts (3)
        jobWorker.pollForJobs();

        Job afterFinalAttempt = jobRepository.findById(failingJob.getId()).orElseThrow();
        assertThat(afterFinalAttempt.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(afterFinalAttempt.getAttempts()).isEqualTo(3);
    }
}
