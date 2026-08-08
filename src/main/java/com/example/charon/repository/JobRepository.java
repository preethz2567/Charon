package com.example.charon.repository;

import com.example.charon.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    @Query(value = "SELECT j.* FROM jobs j LEFT JOIN jobs p ON j.parent_job_id = p.id WHERE j.status = 'PENDING' AND j.run_at <= :now AND (j.parent_job_id IS NULL OR p.status = 'DONE') ORDER BY j.priority DESC, j.run_at ASC LIMIT 1 FOR UPDATE OF j SKIP LOCKED", nativeQuery = true)
    Optional<Job> claimNextJob(@Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE Job j SET j.status = com.example.charon.model.JobStatus.PENDING, j.lockedBy = null, j.lockedUntil = null WHERE j.status = com.example.charon.model.JobStatus.LEASED AND j.lockedUntil <= :now")
    int reclaimStaleJobs(@Param("now") OffsetDateTime now);

    List<Job> findByStatus(com.example.charon.model.JobStatus status);

    long countByStatus(com.example.charon.model.JobStatus status);

    @Modifying
    @Query("UPDATE Job j SET j.status = com.example.charon.model.JobStatus.DEAD, j.lastError = 'Parent job died' WHERE j.status = com.example.charon.model.JobStatus.PENDING AND j.parentJobId IN (SELECT p.id FROM Job p WHERE p.status = com.example.charon.model.JobStatus.DEAD)")
    int failOrphanedJobs();
}
