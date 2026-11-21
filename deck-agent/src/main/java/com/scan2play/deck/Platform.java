package com.scan2play.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Where the DJ programs keep their history on this computer. Windows and macOS (a DJ's laptop); the paths as the open-source readers
 * of these programs name them (docs/ideas.md item 18) and as seen on a DJ's Windows (VirtualDJ under %LOCALAPPDATA%).
 */
final class Platform {

    private final Path home;
    private final Map<String, String> env;
    private final boolean windows;

    Platform(Path home, Map<String, String> env, boolean windows) {
        this.home = home;
        this.env = env;
        this.windows = windows;
    }

    static Platform current() {
        return new Platform(Path.of(System.getProperty("user.home")), System.getenv(),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    /** Every program's history this computer may have; which of them is there is the source's own question (present()). */
    List<HistorySource> sources() {
        return List.of(new VirtualDjHistory(virtualDjFolders()), new SeratoSessions(seratoSessionFolders()),
                new Serato4Database(serato4Databases()));
    }

    List<Path> virtualDjFolders() {
        List<Path> folders = new ArrayList<>();
        registryHomeFolder().ifPresent(folder -> folders.add(folder.resolve("History")));
        if (windows) {
            folder("LOCALAPPDATA").ifPresent(local -> folders.add(local.resolve("VirtualDJ").resolve("History")));
            folders.add(home.resolve("Documents").resolve("VirtualDJ").resolve("History"));
        } else {
            folders.add(home.resolve("Library").resolve("Application Support").resolve("VirtualDJ").resolve("History"));
            folders.add(home.resolve("Documents").resolve("VirtualDJ").resolve("History"));
        }
        return folders;
    }

    List<Path> seratoSessionFolders() {
        return List.of(home.resolve("Music").resolve("_Serato_").resolve("History").resolve("Sessions"));
    }

    List<Path> serato4Databases() {
        Path library = windows
                ? folder("APPDATA").orElse(home.resolve("AppData").resolve("Roaming")).resolve("Serato").resolve("Library")
                : home.resolve("Library").resolve("Application Support").resolve("Serato").resolve("Library");
        return List.of(library.resolve("master.sqlite"));
    }

    /** A computer's name for the panel's list: "DESKTOP-1AB2 — Windows". */
    static String computerName() {
        String host = System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", ""));
        if (host.isBlank()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                host = "Komputer";
            }
        }
        String os = System.getProperty("os.name", "");
        return (host + " — " + (os.toLowerCase(Locale.ROOT).contains("mac") ? "Mac" : os.toLowerCase(Locale.ROOT).contains("win") ? "Windows" : os)).strip();
    }

    private Optional<Path> folder(String variable) {
        String value = env.get(variable);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    /** VirtualDJ's own setting of where its folder is (HKCU\Software\VirtualDJ, HomeFolder), on Windows. */
    private Optional<Path> registryHomeFolder() {
        if (!windows) {
            return Optional.empty();
        }
        try {
            Process reg = new ProcessBuilder("reg", "query", "HKCU\\Software\\VirtualDJ", "/v", "HomeFolder").redirectErrorStream(true).start();
            if (!reg.waitFor(3, TimeUnit.SECONDS)) {
                reg.destroy();
                return Optional.empty();
            }
            return homeFolderOf(new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The folder in reg's answer: "    HomeFolder    REG_SZ    C:\Users\dj\Documents\VirtualDJ\". */
    static Optional<Path> homeFolderOf(String answer) {
        for (String line : answer.split("\r?\n")) {
            int type = line.indexOf("REG_SZ");
            if (line.strip().startsWith("HomeFolder") && type >= 0) {
                String value = line.substring(type + "REG_SZ".length()).strip();
                if (!value.isEmpty()) {
                    try {
                        return Optional.of(Path.of(value));
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }
}
