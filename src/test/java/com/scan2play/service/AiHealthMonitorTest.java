package com.scan2play.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiHealthMonitorTest {

    private final AiHealthMonitor monitor = new AiHealthMonitor();

    @Test
    void whileTheAiAnswersEveryRequest_nothingIsReported() {
        monitor.recordAnswered();
        monitor.recordAnswered();

        assertThat(monitor.takeSummary()).isNull();
    }

    @Test
    void aPeriodWithUncheckedRequests_saysHowManyOfAll_andTheNextPeriodStartsFromZero() {
        monitor.recordAnswered();
        monitor.recordUnchecked();
        monitor.recordUnchecked();

        assertThat(monitor.takeSummary()).startsWith("AI check: 2 of 3 guest requests in the last 5 min went to the DJ unchecked");
        assertThat(monitor.takeSummary()).as("counted once").isNull();

        monitor.recordUnchecked();
        assertThat(monitor.takeSummary()).startsWith("AI check: 1 of 1 ");
    }
}
