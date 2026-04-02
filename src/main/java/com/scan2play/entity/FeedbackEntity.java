package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "feedback", indexes = {
    @Index(name = "idx_feedback_submitted_at", columnList = "submittedAt")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Party code of the DJ who submitted the feedback. */
    @Column(nullable = false, length = 5)
    private String partyCode;

    /** OAuth2 owner ID of the submitting DJ. */
    @Column(nullable = false)
    private String ownerId;

    /** Feedback message body — max 2000 characters. */
    @Column(nullable = false, length = 2000)
    private String message;

    private LocalDateTime submittedAt;
}

