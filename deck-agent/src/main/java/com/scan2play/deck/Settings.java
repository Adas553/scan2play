package com.scan2play.deck;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/**
 * What the program remembers between runs, in the DJ's own folder ({@code ~/.scan2play/zagrane.properties}): the server and the token
 * of the pairing, and the history folder the DJ picked. The token opens the party's "Zagrane" and nothing else; the file is the DJ's alone where the system allows it.
 */
final class Settings {

    static final String DEFAULT_SERVER = "https://www.scan2play.com.pl";

    private final Path file;
    private final Properties values = new Properties();

    Settings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException e) {
                values.clear();
            }
        }
    }

    static Settings forThisUser() {
        return new Settings(Path.of(System.getProperty("user.home"), ".scan2play", "zagrane.properties"));
    }

    String server() {
        return values.getProperty("server", DEFAULT_SERVER);
    }

    String token() {
        return values.getProperty("token");
    }

    void paired(String server, String token) throws IOException {
        values.setProperty("server", server);
        values.setProperty("token", token);
        save();
    }

    /** The history folder the DJ picked in the window ("Wskaż folder historii…"), or null: read beside the usual places. */
    Path folder() {
        String value = values.getProperty("folder");
        try {
            return value == null || value.isBlank() ? null : Path.of(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    void folder(Path folder) throws IOException {
        if (folder == null) {
            values.remove("folder");
        } else {
            values.setProperty("folder", folder.toString());
        }
        save();
    }

    void disconnected() throws IOException {
        values.remove("token");
        save();
    }

    /**
     * Written to a file of its own, owner-only from its creation, then moved over the old one: the token is never readable by
     * another user of the computer (not for the moment between creating the file and restricting it), and a crash while writing
     * never leaves a half file — the DJ would have to pair again.
     */
    private void save() throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        restrict(folder, "rwx------");
        Path temp = Files.createTempFile(folder, "zagrane", ".tmp");
        try {
            restrict(temp, "rw-------");
            try (OutputStream out = Files.newOutputStream(temp)) {
                values.store(out, "Scan2Play - Zagrane samo");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void restrict(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the file is in the user's own profile, closed to the other users already
        }
    }
}
