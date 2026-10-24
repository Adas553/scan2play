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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pages "Dla kogo" once the plans are enforced (V35; the owner, 2026-10-24): a step or a feature of the Pro plan
 * ({@code PlanFeature}) carries "PRO", and "Ile to kosztuje?" says what Free has and what Pro adds — the pages promise nothing of Pro
 * as free. While the plans are not enforced every party has everything and no page says "Pro" ({@code ForWhomPagesWebTest}).
 */
@WebMvcTest(ForWhomController.class)
@Import({SecurityConfig.class, ForWhomPagesPlansWebTest.PlansOn.class})
class ForWhomPagesPlansWebTest {

    @TestConfiguration
    static class PlansOn {
        @Bean
        PlanService planService() {
            return Plans.enforced();
        }
    }

    /** Which steps and features of each page are Pro: the hosts' lists, the profiles, the summary, the limits, the comment styles, the tips. */
    static final Map<String, List<String>> PRO = Map.of(
            "/dj-weselny", List.of("step2", "step4", "feature4"),
            "/kluby", List.of("feature3", "feature4"),
            "/puby", List.of("feature1", "feature3"));

    @Autowired
    private MockMvc mockMvc;

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The steps and features marked "PRO", by their place: "step2", "feature4". */
    static List<String> marked(String html) {
        List<String> marked = new ArrayList<>();
        String[] steps = html.split("class=\"s2p-landing-step\"", -1);
        for (int i = 1; i < steps.length; i++) {
            if (steps[i].substring(0, steps[i].indexOf("</li>")).contains("class=\"s2p-pro\"")) {
                marked.add("step" + i);
            }
        }
        String features = html.substring(html.indexOf("id=\"forWhomFeatures\""), html.indexOf("id=\"forWhomFaq\""));
        String[] cards = features.split("class=\"s2p-landing-card\"", -1);
        for (int i = 1; i < cards.length; i++) {
            if (cards[i].contains("class=\"s2p-pro\"")) {
                marked.add("feature" + i);
            }
        }
        return marked;
    }

    @Test
    void theProStepsAndFeatures_carryPro() throws Exception {
        for (Map.Entry<String, List<String>> page : PRO.entrySet()) {
            assertThat(marked(page(page.getKey()))).as(page.getKey()).isEqualTo(page.getValue());
        }
    }

    @Test
    void thePrice_saysWhatFreeHasAndWhatProAdds() throws Exception {
        String faq = page("/puby").split("id=\"forWhomFaq\"")[1];
        assertThat(faq).contains("Za darmo masz wszystko, czego potrzeba na imprezę. Pro dodaje")
                .doesNotContain("Nic. Scan2Play jest za darmo");
    }
}
