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
import java.time.LocalDate;

/**
 * One evening of a party the DJ named or shared (V61, "Podsumowanie wieczoru"): the title the DJ typed and the hosts' read-only link
 * ({@code /s/{token}}) with the moment it stops working. The evening's songs are in {@code song_requests} and {@code evening_song};
 * this row only says how the DJ calls the evening and who may see it. Written by {@code EveningSummaryRepository}'s upserts.
 */
@Entity
@Table(name = "evening_summary")   // (party_code, evening) is UNIQUE (uk_evening_summary_party_evening), share_token too
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EveningSummaryEntity {

    /** The longest title kept (the column, V61). */
    public static final int TITLE_MAX = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String partyCode;

    /** The evening's day ({@code EveningSummaryService.eveningOf}): from 6:00 that day to 6:00 the next, Polish time. */
    @Column(nullable = false)
    private LocalDate evening;

    /** The DJ's name of the evening ("Wesele Ani i Tomka"); null: "Podsumowanie wieczoru". */
    @Column(length = TITLE_MAX)
    private String title;

    /** The secret of the hosts' read-only link; null: none. */
    @Column(length = 32)
    private String shareToken;

    /** When the link stops working. */
    private Instant shareExpiresAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** When the morning e-mail of this evening went (V64); written only by {@code EveningSummaryRepository}'s claim and release. */
    @Column(insertable = false, updatable = false)
    private Instant morningSentAt;

    /** Whether the hosts' link opens at {@code now}. */
    public boolean shareWorks(Instant now) {
        return shareToken != null && shareExpiresAt != null && now.isBefore(shareExpiresAt);
    }
}
