package com.scan2play.repository;

import com.scan2play.entity.DjAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** The accounts that log in with a password (V72); every lookup by a unique index. */
public interface DjAccountRepository extends JpaRepository<DjAccountEntity, Long> {

    /** By the address, lower case (as kept). */
    Optional<DjAccountEntity> findByEmail(String email);

    Optional<DjAccountEntity> findByVerifyTokenHash(String verifyTokenHash);

    Optional<DjAccountEntity> findByResetTokenHash(String resetTokenHash);

    /**
     * Whether a Google account of the app has this address (an organiser's, kept when they opened the panel, V48): such an address
     * logs in with Google, never with a password too. By the index of V72 on {@code lower(owner_email)}.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM party_settings WHERE lower(owner_email) = :email AND owner_id NOT LIKE 'local:%')",
            nativeQuery = true)
    boolean googleAccountHas(@Param("email") String email);
}
