package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PhoneRegistrationRepository extends JpaRepository<PhoneRegistration, String> {

    /**
     * The one question the spend gate and every projection-create asks: does an
     * unrevoked registration exist for this phone? Backed by the partial index
     * {@code idx_phone_registration_live}.
     */
    boolean existsByPhoneNumberAndRevokedAtIsNull(String phoneNumber);

    /**
     * Locks the phone's registration row for the duration of the writing
     * transaction so two concurrent proofs for the same phone (the app's login
     * assertion racing ticketing's promote webhook, or two logins from two
     * devices) serialise instead of both inserting.
     *
     * <p>Empty means no row yet — the caller inserts. The insert can still lose
     * a race with another transaction that had not yet committed when this lock
     * was taken, which surfaces as a primary-key violation; that is caught and
     * treated as "someone else registered it", because the outcome is identical.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PhoneRegistration r WHERE r.phoneNumber = :phoneNumber")
    Optional<PhoneRegistration> lockByPhoneNumber(@Param("phoneNumber") String phoneNumber);
}
