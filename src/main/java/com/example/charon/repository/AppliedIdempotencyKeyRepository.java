package com.example.charon.repository;

import com.example.charon.model.AppliedIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppliedIdempotencyKeyRepository extends JpaRepository<AppliedIdempotencyKey, String> {
}
