package com.example.charon.controller;

import com.example.charon.model.JobStatus;
import com.example.charon.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JobControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void shouldEnqueueJobSuccessfully() throws Exception {
        String payload = """
                {
                    "priority": 10,
                    "payload": "{\\"task\\": \\"send_email\\"}",
                    "idempotencyKey": "email-123",
                    "maxAttempts": 5
                }
                """;

        mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priority").value(10))
                .andExpect(jsonPath("$.payload").value("{\"task\": \"send_email\"}"))
                .andExpect(jsonPath("$.idempotencyKey").value("email-123"))
                .andExpect(jsonPath("$.maxAttempts").value(5));

        // Verify it was actually saved in the database
        assertThat(jobRepository.findAll()).hasSize(1);
        var job = jobRepository.findAll().get(0);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPayload()).isEqualTo("{\"task\": \"send_email\"}");
        assertThat(job.getIdempotencyKey()).isEqualTo("email-123");
    }

    @Test
    void shouldFailIfPayloadIsMissing() throws Exception {
        String payload = """
                {
                    "priority": 10
                }
                """;

        mockMvc.perform(post("/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldListDeadLetters() throws Exception {
        Job deadJob = new Job();
        deadJob.setStatus(JobStatus.DEAD);
        deadJob.setPayload("{}");
        deadJob.setPriority(1);
        jobRepository.save(deadJob);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deadJob.getId()))
                .andExpect(jsonPath("$[0].status").value("DEAD"));
    }

    @Test
    void shouldReplayDeadLetter() throws Exception {
        Job deadJob = new Job();
        deadJob.setStatus(JobStatus.DEAD);
        deadJob.setPayload("{}");
        deadJob.setPriority(1);
        deadJob.setAttempts(3);
        deadJob.setLastError("Failed permanently");
        deadJob = jobRepository.save(deadJob);

        mockMvc.perform(post("/dead-letters/" + deadJob.getId() + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deadJob.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.lastError").doesNotExist());

        // Verify in DB
        Job updatedJob = jobRepository.findById(deadJob.getId()).orElseThrow();
        assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(updatedJob.getAttempts()).isEqualTo(0);
        assertThat(updatedJob.getLastError()).isNull();
    }

    @Test
    void shouldFailReplayForNonDeadJob() throws Exception {
        Job pendingJob = new Job();
        pendingJob.setStatus(JobStatus.PENDING);
        pendingJob.setPayload("{}");
        pendingJob.setPriority(1);
        pendingJob = jobRepository.save(pendingJob);

        mockMvc.perform(post("/dead-letters/" + pendingJob.getId() + "/replay"))
                .andExpect(status().isBadRequest());
    }
}
