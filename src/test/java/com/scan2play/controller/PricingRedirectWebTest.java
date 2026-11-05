package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pricing page's other addresses through the real security chain, with the plans enforced: a visitor without a login is sent
 * on to {@code /pro} in the address's language — a 301, not Google's login (the addresses are public in {@link SecurityConfig}).
 */
@WebMvcTest(LegalController.class)
@Import({SecurityConfig.class, PricingRedirectWebTest.PlansOn.class})
class PricingRedirectWebTest {

    @TestConfiguration
    static class PlansOn {
        @Bean
        PlanService planService() {
            return Plans.enforced();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cennik_andPricing_leadToThePricingPage_withoutALogin() throws Exception {
        mockMvc.perform(get("/cennik"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/pro?lang=pl"));
        mockMvc.perform(get("/pricing"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/pro?lang=en"));
    }
}
