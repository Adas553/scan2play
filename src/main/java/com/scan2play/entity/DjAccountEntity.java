package com.scan2play.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An account that logs in with an e-mail address and a password (V72), beside Google's. The rest of the app knows it by
 * {@link #userId()} — {@code local:<id>} — where a Google account is its subject; nothing else in the app tells them apart. The password
 * is kept only as a bcrypt hash, the links of the e-mails only as the SHA-256 of their secret ({@code PasswordAccountService}).
 */
@Entity
@Table(name = "dj_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DjAccountEntity {

    /** The prefix of an account's id in the app: a Google subject is digits only, so the two kinds never meet. */
    public static final String USER_ID_PREFIX = "local:";
    public static final int EMAIL_MAX = 320;
    public static final int DISPLAY_NAME_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower case; one account an address. */
    @Column(nullable = false, unique = true, length = EMAIL_MAX)
    private String email;

    /** DelegatingPasswordEncoder's form, {@code {bcrypt}$2a$…}; 255 leaves room for a later kind's (V73). */
    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(length = DISPLAY_NAME_MAX)
    private String displayName;

    @Column(nullable = false)
    private Instant createdAt;

    /** Null until the address is confirmed: such an account cannot log in. */
    private Instant emailVerifiedAt;

    @Column(length = 64)
    private String verifyTokenHash;
    private Instant verifyExpiresAt;

    @Column(length = 64)
    private String resetTokenHash;
    private Instant resetExpiresAt;

    /** The id the rest of the app knows the account by ({@code party_settings.owner_id} and the like). */
    public String userId() {
        return USER_ID_PREFIX + id;
    }

    public boolean isVerified() {
        return emailVerifiedAt != null;
    }
}
