package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Two copies of the program would send every song twice and show two tray icons: the second start must be refused. */
class SingleInstanceTest {

    @Test
    void aSecondStart_isRefused_whileTheFirstRuns_andAllowedAfterItEnds(@TempDir Path home) throws IOException {
        Path lock = home.resolve(".scan2play").resolve("zagrane.lock");

        SingleInstance first = SingleInstance.tryAcquire(lock);
        assertNotNull(first, "the first start runs");
        assertNull(SingleInstance.tryAcquire(lock), "the second start is refused while the first runs");

        first.release();
        SingleInstance again = SingleInstance.tryAcquire(lock);
        assertNotNull(again, "a start after the first one ended runs");
        again.release();
    }
}
