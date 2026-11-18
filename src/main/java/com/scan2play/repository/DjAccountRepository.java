package com.scan2play.repository;

import com.scan2play.entity.DjAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Uses up the confirmation link of this account, if it still has it: 1, or 0 when a request with the same link took it first —
     * the row's lock makes the second one wait, then find the link gone.
     */
    @Modifying
    @Query("UPDATE DjAccountEntity a SET a.verifyTokenHash = NULL, a.verifyExpiresAt = NULL WHERE a.id = :id AND a.verifyTokenHash = :hash")
    int useVerifyToken(@Param("id") long id, @Param("hash") String hash);

    /** Uses up the new password's link of this account, if it still has it: 1, or 0 (as {@link #useVerifyToken}). */
    @Modifying
    @Query("UPDATE DjAccountEntity a SET a.resetTokenHash = NULL, a.resetExpiresAt = NULL WHERE a.id = :id AND a.resetTokenHash = :hash")
    int useResetToken(@Param("id") long id, @Param("hash") String hash);

    /**
     * Whether a Google account of the app has this address (an organiser's, kept when they opened the panel, V48): such an address
     * logs in with Google, never with a password too. By the index of V72 on {@code lower(owner_email)}.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM party_settings WHERE lower(owner_email) = :email AND owner_id NOT LIKE 'local:%')",
            nativeQuery = true)
    boolean googleAccountHas(@Param("email") String email);
}
