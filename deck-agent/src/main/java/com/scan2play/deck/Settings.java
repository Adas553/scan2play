package com.scan2play.deck;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/**
 * What the program remembers between runs, in the DJ's own folder ({@code ~/.scan2play/zagrane.properties}): the server and the token
 * of the pairing. The token opens the party's "Zagrane" and nothing else; the file is the DJ's alone where the system allows it.
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

    void disconnected() throws IOException {
        values.remove("token");
        save();
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            values.store(out, "Scan2Play - Zagrane samo");
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the file is in the user's own profile already
        }
    }
}
