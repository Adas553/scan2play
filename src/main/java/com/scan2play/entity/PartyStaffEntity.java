package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One person of a party's staff (V30): a bartender or a second DJ who logs in with their own Google account and works the owner's
 * queue and history — no settings. Joined by the owner's invitation link; the owner takes the access away.
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
}
