package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The owner's two tray icons were two processes: the guard must hold across them, not only inside one JVM. */
    @Test
    void aSecondProcess_isRefused_whileTheFirstRuns(@TempDir Path home) throws Exception {
        Path lock = home.resolve(".scan2play").resolve("zagrane.lock");
        SingleInstance first = SingleInstance.tryAcquire(lock);
        assertNotNull(first);
        try {
            assertEquals("refused", secondProcess(lock));
        } finally {
            first.release();
        }
        assertEquals("runs", secondProcess(lock), "after the first one ended");
    }

    private static String secondProcess(Path lock) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SecondCopy.class.getName(),
                lock.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();   // stderr apart: a JVM may print notes there
        assertEquals(true, process.waitFor(60, TimeUnit.SECONDS), "the second process ended");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    /** The second start, in a JVM of its own. */
    static final class SecondCopy {
        public static void main(String[] args) throws IOException {
            SingleInstance copy = SingleInstance.tryAcquire(Path.of(args[0]));
            System.out.print(copy == null ? "refused" : "runs");
        }
    }
}
