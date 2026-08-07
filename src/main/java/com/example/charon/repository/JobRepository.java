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

    @Query(value = "SELECT * FROM jobs WHERE status = 'PENDING' AND run_at <= :now ORDER BY priority DESC, run_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Job> claimNextJob(@Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE Job j SET j.status = com.example.charon.model.JobStatus.PENDING, j.lockedBy = null, j.lockedUntil = null WHERE j.status = com.example.charon.model.JobStatus.LEASED AND j.lockedUntil <= :now")
    int reclaimStaleJobs(@Param("now") OffsetDateTime now);

    List<Job> findByStatus(com.example.charon.model.JobStatus status);
}
