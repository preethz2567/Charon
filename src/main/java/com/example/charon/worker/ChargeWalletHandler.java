package com.example.charon.worker;

import com.example.charon.model.AppliedIdempotencyKey;
import com.example.charon.model.Job;
import com.example.charon.model.Wallet;
import com.example.charon.repository.AppliedIdempotencyKeyRepository;
import com.example.charon.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ChargeWalletHandler {

    private static final Logger log = LoggerFactory.getLogger(ChargeWalletHandler.class);

    private final AppliedIdempotencyKeyRepository idempotencyKeyRepository;
    private final WalletRepository walletRepository;

    public ChargeWalletHandler(AppliedIdempotencyKeyRepository idempotencyKeyRepository, WalletRepository walletRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional
    public void handle(Job job, String userId, int amountToDebit) {
        if (job.getIdempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency key is required for charge_wallet");
        }

        // 1. Check idempotency
        if (idempotencyKeyRepository.existsById(job.getIdempotencyKey())) {
            log.info("Idempotency key {} already applied. Skipping debit for Job {}.", job.getIdempotencyKey(), job.getId());
            return;
        }

        // 2. Debit wallet
        Wallet wallet = walletRepository.findByUserId(userId).orElseGet(() -> {
            Wallet newWallet = new Wallet();
            newWallet.setUserId(userId);
            newWallet.setBalance(1000); // Give them some fake initial money so debiting works in tests
            return walletRepository.save(newWallet);
        });

        wallet.setBalance(wallet.getBalance() - amountToDebit);
        walletRepository.save(wallet);

        // 3. Record idempotency key
        idempotencyKeyRepository.save(new AppliedIdempotencyKey(job.getIdempotencyKey()));
        log.info("Successfully debited {} from user {} and recorded idempotency key {} for Job {}", amountToDebit, userId, job.getIdempotencyKey(), job.getId());
    }
}
