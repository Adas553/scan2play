package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A guest's dedication with a request (V38): "dla Ani na 18-stkę" — a short message the DJ sees by the song, never the other
 * guests. The AI checks it with the request and an offensive one is not saved; when the AI could not be asked it goes to the DJ
 * unchecked ({@link #checked} false, marked in the queue). Deleted with its request (the database's cascade).
 */
@Entity
@Table(name = "song_request_dedications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongRequestDedicationEntity {

    /** The longest dedication: one line on a phone's screen and in the DJ's queue. */
    public static final int TEXT_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The request it was sent with: the song's row (a vote's dedication is on the waiting song it was counted on). */
    @Column(nullable = false)
    private Long songRequestId;

    @Column(nullable = false, length = TEXT_MAX)
    private String text;

    /** Whether the AI checked it; false when the AI could not be asked. */
    @Column(nullable = false)
    private boolean checked;

    @Column(nullable = false)
    private Instant createdAt;
}
