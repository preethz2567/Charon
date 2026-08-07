package com.example.charon.dto;

import java.time.OffsetDateTime;

public class EnqueueJobRequest {
    private Integer priority;
    private OffsetDateTime runAt;
    private String payload;
    private String idempotencyKey;
    private Integer maxAttempts;

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public OffsetDateTime getRunAt() {
        return runAt;
    }

    public void setRunAt(OffsetDateTime runAt) {
        this.runAt = runAt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
