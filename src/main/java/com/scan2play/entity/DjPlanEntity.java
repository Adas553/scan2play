package com.scan2play.entity;

import com.scan2play.model.Plan;
import com.scan2play.model.PlanSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A DJ's plan (V35): one row per organiser, by their Google subject; no row is {@link Plan#FREE}. Read only through
 * {@code PlanService}, and only while the plans are enforced.
 */
@Entity
@Table(name = "dj_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DjPlanEntity {

    /** The organiser (as {@code party_settings.owner_id}). */
    @Id
    @Column(nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanSource source;

    /** The end of the plan; null = no end. */
    private Instant validUntil;

    @Column(nullable = false)
    private Instant updatedAt;

    /** The plan at {@code now}: {@link #plan} until {@link #validUntil}, then Free. */
    public Plan planAt(Instant now) {
        return validUntil == null || validUntil.isAfter(now) ? plan : Plan.FREE;
    }
}
