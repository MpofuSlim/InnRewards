package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VoucherBatchRepository extends JpaRepository<VoucherBatch, UUID> {
}
