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
                if (job.getPayload().contains("\"fail\":true")) {
                    throw new RuntimeException("Simulated job failure");
                }
                System.out.println("done");
                
                // Mark as done
                transactionTemplate.execute(status -> {
                    job.setStatus(JobStatus.DONE);
                    job.setLockedBy(null);
                    job.setLockedUntil(null);
                    return jobRepository.save(job);
                });
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    log.error("Worker {} interrupted while processing job {}", workerId, job.getId());
                    return;
                }

                transactionTemplate.execute(status -> {
                    int attempts = job.getAttempts() + 1;
                    job.setAttempts(attempts);
                    job.setLastError(e.getMessage());
                    job.setLockedBy(null);
                    job.setLockedUntil(null);

                    if (attempts >= job.getMaxAttempts()) {
                        job.setStatus(JobStatus.DEAD);
                        log.warn("Job {} reached max attempts ({}). Marked as DEAD.", job.getId(), job.getMaxAttempts());
                    } else {
                        job.setStatus(JobStatus.PENDING);
                        
                        // Backoff: 2^attempts + jitter
                        long delaySeconds = (long) Math.pow(2, attempts);
                        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 5);
                        long totalDelay = delaySeconds + jitter;
                        
                        job.setRunAt(OffsetDateTime.now().plusSeconds(totalDelay));
                        log.info("Job {} failed. Computed delay: {}s (2^{} + {}s jitter). Next run_at: {}. (Attempt {}/{})", 
                                job.getId(), totalDelay, attempts, jitter, job.getRunAt(), attempts, job.getMaxAttempts());
                    }
                    return jobRepository.save(job);
                });
            }
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void reclaimStaleJobs() {
        Integer reclaimedCount = transactionTemplate.execute(status -> 
            jobRepository.reclaimStaleJobs(OffsetDateTime.now())
        );
        if (reclaimedCount != null && reclaimedCount > 0) {
            log.info("Worker {} reclaimed {} stale job(s)", workerId, reclaimedCount);
        }
    }
}
