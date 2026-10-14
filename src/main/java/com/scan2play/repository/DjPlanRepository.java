package com.scan2play.repository;

import com.scan2play.entity.DjPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** The DJs' plans (V35), by the organiser's Google subject. */
public interface DjPlanRepository extends JpaRepository<DjPlanEntity, String> {
}
