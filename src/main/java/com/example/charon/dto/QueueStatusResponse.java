package com.example.charon.dto;

import java.util.List;

public class QueueStatusResponse {
    private long pendingCount;
    private long leasedCount;
    private long deadCount;
    private List<LeasedJobDetail> leasedJobs;

    public long getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(long pendingCount) {
        this.pendingCount = pendingCount;
    }

    public long getLeasedCount() {
        return leasedCount;
    }

    public void setLeasedCount(long leasedCount) {
        this.leasedCount = leasedCount;
    }

    public long getDeadCount() {
        return deadCount;
    }

    public void setDeadCount(long deadCount) {
        this.deadCount = deadCount;
    }

    public List<LeasedJobDetail> getLeasedJobs() {
        return leasedJobs;
    }

    public void setLeasedJobs(List<LeasedJobDetail> leasedJobs) {
        this.leasedJobs = leasedJobs;
    }
}
