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
}
