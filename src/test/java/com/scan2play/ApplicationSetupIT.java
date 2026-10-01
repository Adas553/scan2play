package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** Settings of the running application that only the whole context shows (it is the context of the other ITs). */
class ApplicationSetupIT extends PostgresIntegrationTest {

    @Autowired ThreadPoolTaskExecutor applicationTaskExecutor;

    /** Review item 4.4: the guests' requests and @Async run on a bounded executor, not on Boot's unbounded queue. */
    @Test
    void theAsyncExecutorIsBounded() {
        assertThat(applicationTaskExecutor.getCorePoolSize()).isEqualTo(16);
        assertThat(applicationTaskExecutor.getMaxPoolSize()).isEqualTo(32);
        assertThat(applicationTaskExecutor.getQueueCapacity()).isEqualTo(50);
    }
}
