package com.scan2play.entity;

import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * An invitation to a party's staff by the e-mail address of a Google account (V34): the organiser typed the address and what the
 * person will be able to do; whoever logs in with that account is asked "Dołącz" / "Nie, dziękuję". Waits at most
 * {@value #MAX_AGE_DAYS} days. Counted with the staff to the party's 10.
 */
@Entity
@Table(name = "staff_invitation", indexes = {
    @Index(name = "idx_staff_invitation_email_key", columnList = "emailKey")
})   // (party_code, email_key) is UNIQUE (uk_staff_invitation_email)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffInvitationEntity {

    /** How long an invitation waits for its answer (as the guests' requests, 30 days); the privacy policy says so. */
    public static final int MAX_AGE_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    /** The address as the organiser typed it, in lower case (their list shows it). */
    @Column(nullable = false, length = 254)
    private String email;

    /** The address the login is matched against ({@code EmailAddresses.key}). */
    @Column(nullable = false, length = 254)
    private String emailKey;

    /** What the person will be able to do once they join. */
    @Convert(converter = StaffPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<StaffPermission> permissions;

    @Column(nullable = false)
    private Instant invitedAt;

    /** The role the permissions make ({@link StaffRole#CUSTOM} when ticked one by one). */
    public StaffRole role() {
        return StaffRole.of(permissions);
    }
}
