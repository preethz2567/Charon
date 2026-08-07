package com.example.charon.worker;

import com.example.charon.model.Job;
import com.example.charon.model.JobStatus;
import com.example.charon.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final String workerId;

    public JobWorker(JobRepository jobRepository, TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.transactionTemplate = transactionTemplate;
        this.workerId = UUID.randomUUID().toString();
    }

    @Scheduled(fixedDelay = 1000)
    public void pollForJobs() {
        // Step 1: Claim the job in a short transaction
        Optional<Job> claimedJob = transactionTemplate.execute(status -> {
            Optional<Job> jobOpt = jobRepository.claimNextJob(OffsetDateTime.now());
            if (jobOpt.isPresent()) {
                Job job = jobOpt.get();
                job.setStatus(JobStatus.LEASED);
                job.setLockedBy(workerId);
                job.setLockedUntil(OffsetDateTime.now().plusSeconds(30));
                return Optional.of(jobRepository.save(job));
            }
            return Optional.empty();
        });

        // Step 2: Process the job outside the claim transaction
        claimedJob.ifPresent(job -> {
            log.info("Worker {} claimed job {}: {}", workerId, job.getId(), job.getPayload());
            
            try {
                // Fake job handler
                Thread.sleep(2000);
                System.out.println("done");
                
                // Mark as done
                transactionTemplate.execute(status -> {
                    job.setStatus(JobStatus.DONE);
                    job.setLockedBy(null);
                    job.setLockedUntil(null);
                    return jobRepository.save(job);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Worker {} interrupted while processing job {}", workerId, job.getId());
            }
        });
    }
}
