package com.scan2play.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** For a {@code @WebMvcTest}: the {@link PlanService} with the plans not enforced, as in production today (V35). */
@TestConfiguration
public class PlansOffConfig {

    @Bean
    PlanService planService() {
        return Plans.off();
    }
}
