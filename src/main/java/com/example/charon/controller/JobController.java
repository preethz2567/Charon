package com.example.charon.controller;

import com.example.charon.dto.EnqueueJobRequest;
import com.example.charon.model.Job;
import com.example.charon.model.JobStatus;
import com.example.charon.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Job enqueueJob(@RequestBody EnqueueJobRequest request) {
        if (request.getPayload() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload is required");
        }

        Job job = new Job();
        job.setPayload(request.getPayload());

        if (request.getPriority() != null) {
            job.setPriority(request.getPriority());
        }
        if (request.getRunAt() != null) {
            job.setRunAt(request.getRunAt());
        } else {
            job.setRunAt(OffsetDateTime.now());
        }
        if (request.getIdempotencyKey() != null) {
            job.setIdempotencyKey(request.getIdempotencyKey());
        }
        if (request.getMaxAttempts() != null) {
            job.setMaxAttempts(request.getMaxAttempts());
        }

        return jobRepository.save(job);
    }

    @GetMapping("/dead-letters")
    public List<Job> getDeadLetters() {
        return jobRepository.findByStatus(JobStatus.DEAD);
    }

    @PostMapping("/dead-letters/{id}/replay")
    public Job replayDeadLetter(@PathVariable Long id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

        if (job.getStatus() != JobStatus.DEAD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only DEAD jobs can be replayed");
        }

        job.setAttempts(0);
        job.setStatus(JobStatus.PENDING);
        job.setRunAt(OffsetDateTime.now());
        job.setLastError(null);

        return jobRepository.save(job);
    }
}
