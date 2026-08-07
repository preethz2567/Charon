package com.example.charon.dto;

import java.time.OffsetDateTime;

public class LeasedJobDetail {
    private Long jobId;
    private String lockedBy;
    private OffsetDateTime lockedUntil;

    public LeasedJobDetail() {}

    public LeasedJobDetail(Long jobId, String lockedBy, OffsetDateTime lockedUntil) {
        this.jobId = jobId;
        this.lockedBy = lockedBy;
        this.lockedUntil = lockedUntil;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(OffsetDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
