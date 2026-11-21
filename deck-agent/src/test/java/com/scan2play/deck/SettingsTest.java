package com.scan2play.deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What the program remembers: the token is kept for its owner alone and survives a restart. */
class SettingsTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theTokensFileAndFolder_areTheOwnersAlone_whateverTheyWereBefore(@TempDir Path home) throws IOException {
        Path folder = home.resolve(".scan2play");
        Files.createDirectories(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = folder.resolve("zagrane.properties");
        Files.writeString(file, "token=old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        new Settings(file).paired("https://www.scan2play.com.pl", "secret-token");

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)));
    }

    @Test
    void aSave_leavesOnlyTheFile_andTheTokenComesBack_andGoesOnDisconnect(@TempDir Path home) throws IOException {
        Path file = home.resolve(".scan2play").resolve("zagrane.properties");
        Settings settings = new Settings(file);
        settings.paired("https://dev.example", "secret-token");

        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of("zagrane.properties"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("secret-token", new Settings(file).token());
        assertEquals("https://dev.example", new Settings(file).server());

        settings.disconnected();
        assertNull(new Settings(file).token());
    }
}
