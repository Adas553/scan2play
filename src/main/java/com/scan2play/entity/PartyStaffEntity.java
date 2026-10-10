package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;

import java.time.Instant;
import java.util.Set;

/**
 * One person of a party's staff (V30): a bartender, a second DJ, the venue's manager — someone who logs in with their own Google
 * account and works the owner's party as far as the owner allows ({@link #permissions}, V32). Joined by the owner's invitation link;
 * the owner takes the access away.
 */
@Entity
@Table(name = "party_staff", indexes = {
    @Index(name = "idx_party_staff_member_id", columnList = "memberId")
})   // (party_code, member_id) is UNIQUE (uk_party_staff_member)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyStaffEntity {

    /** The longest name kept (the column, V30). */
    public static final int NAME_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    /** The staff member (the Google subject), as {@link PartySettingsEntity#getOwnerId()} is the owner's. */
    @Column(nullable = false)
    private String memberId;

    /** The name of their Google account, for the owner's list; null when Google gave none. */
    @Column(length = NAME_MAX)
    private String memberName;

    @Column(nullable = false)
    private Instant joinedAt;

    /** What the person may do (V32): the organiser's pick, a role's set or ticked one by one ({@code StaffRole.of} names it). */
    @Builder.Default
    @Convert(converter = StaffPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<StaffPermission> permissions = StaffRole.DEFAULT.permissions();

    /** The role the permissions make ({@link StaffRole#CUSTOM} when ticked one by one) — the owner's page shows it picked. */
    public StaffRole role() {
        return StaffRole.of(permissions);
    }
}
