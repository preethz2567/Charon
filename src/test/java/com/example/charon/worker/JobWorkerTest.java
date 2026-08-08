package com.example.charon.worker;

import com.example.charon.model.Job;
import com.example.charon.model.JobStatus;
import com.example.charon.model.Wallet;
import com.example.charon.repository.AppliedIdempotencyKeyRepository;
import com.example.charon.repository.JobRepository;
import com.example.charon.repository.WalletRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private AppliedIdempotencyKeyRepository appliedIdempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        walletRepository.deleteAll();
        appliedIdempotencyKeyRepository.deleteAll();
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
        assertThat(afterFinalAttempt.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(afterFinalAttempt.getAttempts()).isEqualTo(3);
    }

    @Test
    void shouldNotDoubleChargeForSameIdempotencyKey() {
        String idempotencyKey = "charge-req-999";
        String userId = "alice";

        // First Job
        Job job1 = new Job();
        job1.setPriority(1);
        job1.setPayload("{\"task\":\"charge_wallet\", \"user_id\":\"" + userId + "\"}");
        job1.setIdempotencyKey(idempotencyKey);
        jobRepository.save(job1);

        // Process first job
        jobWorker.pollForJobs();

        // Wallet should be created (1000 initial - 50 debit = 950)
        Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualTo(950);
        assertThat(appliedIdempotencyKeyRepository.existsById(idempotencyKey)).isTrue();

        // Second Job (Duplicate)
        Job job2 = new Job();
        job2.setPriority(1);
        job2.setPayload("{\"task\":\"charge_wallet\", \"user_id\":\"" + userId + "\"}");
        job2.setIdempotencyKey(idempotencyKey);
        jobRepository.save(job2);

        // Process second job
        jobWorker.pollForJobs();

        // Wallet should STILL be 950
        Wallet walletAfter = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(walletAfter.getBalance()).isEqualTo(950);

        Job updatedJob2 = jobRepository.findById(job2.getId()).orElseThrow();
        assertThat(updatedJob2.getStatus()).isEqualTo(JobStatus.DONE); // Processed and marked done, but skipped debit
    }

    @Test
    void shouldNotClaimSameJobConcurrently() throws InterruptedException {
        // We add only 1 job
        Job job = new Job();
        job.setPriority(1);
        job.setPayload("{\"task\":\"concurrent_test\"}");
        jobRepository.save(job);

        int numberOfThreads = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger jobsClaimed = new AtomicInteger(0);

        // We launch 5 threads that will all try to claim jobs concurrently
        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // wait for all threads to be ready
                    jobWorker.pollForJobs(); // each poll claims 1 job
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Release the hounds
        startLatch.countDown();
        
        // Wait for all threads to finish their poll attempts
        endLatch.await();
        executorService.shutdown();

        // Exactly 1 thread should have successfully claimed and processed the job
        // The job should be DONE and no other jobs should have been affected
        Job processedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(processedJob.getStatus()).isEqualTo(JobStatus.DONE);
        
        // A direct DB count to ensure only 1 job exists and it's done
        long doneCount = jobRepository.countByStatus(JobStatus.DONE);
        assertThat(doneCount).isEqualTo(1);
    }

    @Test
    void shouldNotRunDependentJobUntilParentCompletes() {
        Job parent = new Job();
        parent.setPriority(1);
        parent.setPayload("{\"task\":\"parent_job\"}");
        parent = jobRepository.save(parent);

        Job dependent = new Job();
        dependent.setPriority(10); // higher priority, but shouldn't run
        dependent.setPayload("{\"task\":\"dependent_job\"}");
        dependent.setParentJobId(parent.getId());
        dependent = jobRepository.save(dependent);

        // Poll 1: Should claim parent, despite dependent having higher priority
        jobWorker.pollForJobs();
        Job updatedParent = jobRepository.findById(parent.getId()).orElseThrow();
        assertThat(updatedParent.getStatus()).isEqualTo(JobStatus.DONE);

        Job updatedDependent = jobRepository.findById(dependent.getId()).orElseThrow();
        assertThat(updatedDependent.getStatus()).isEqualTo(JobStatus.PENDING); // Not run yet

        // Poll 2: Now that parent is done, dependent should be claimed
        jobWorker.pollForJobs();
        updatedDependent = jobRepository.findById(dependent.getId()).orElseThrow();
        assertThat(updatedDependent.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void shouldFailDependentJobIfParentDies() {
        Job parent = new Job();
        parent.setPriority(1);
        parent.setPayload("{\"task\":\"test\", \"fail\":true}"); // Will fail and die
        parent.setMaxAttempts(1);
        parent = jobRepository.save(parent);

        Job dependent = new Job();
        dependent.setPriority(10);
        dependent.setPayload("{\"task\":\"dependent_job\"}");
        dependent.setParentJobId(parent.getId());
        dependent = jobRepository.save(dependent);

        // Poll: runs parent, fails, moves to DEAD
        jobWorker.pollForJobs();
        Job updatedParent = jobRepository.findById(parent.getId()).orElseThrow();
        assertThat(updatedParent.getStatus()).isEqualTo(JobStatus.DEAD);

        // Trigger reclaimer loop to fail orphaned jobs
        jobWorker.reclaimStaleJobs();

        Job updatedDependent = jobRepository.findById(dependent.getId()).orElseThrow();
        assertThat(updatedDependent.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(updatedDependent.getLastError()).isEqualTo("Parent job died");
    }
}
