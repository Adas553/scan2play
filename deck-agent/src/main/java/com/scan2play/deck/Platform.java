package com.scan2play.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
    /** Windows' own answer where its folders are ({@link #knownFolders}): "music", "documents", "virtualdj" (its HomeFolder). */
    private final Map<String, Path> known;

    Platform(Path home, Map<String, String> env, boolean windows) {
        this(home, env, windows, Map.of());
    }

    Platform(Path home, Map<String, String> env, boolean windows, Map<String, Path> known) {
        this.home = home;
        this.env = env;
        this.windows = windows;
        this.known = known;
    }

    static Platform current() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return new Platform(Path.of(System.getProperty("user.home")), System.getenv(), windows,
                windows ? askWindows() : Map.of());
    }

    /** Every program's history this computer may have; which of them is there is the source's own question (present()). */
    List<HistorySource> sources() {
        return List.of(new VirtualDjHistory(virtualDjFolders()), new SeratoSessions(seratoSessionFolders()),
                new Serato4Database(serato4Databases()));
    }

    List<Path> virtualDjFolders() {
        List<Path> folders = new ArrayList<>();
        Optional.ofNullable(known.get("virtualdj")).or(this::registryHomeFolder).ifPresent(folder -> folders.add(folder.resolve("History")));
        if (windows) {
            folder("LOCALAPPDATA").ifPresent(local -> folders.add(local.resolve("VirtualDJ").resolve("History")));
            folders.add(home.resolve("Documents").resolve("VirtualDJ").resolve("History"));
            Optional.ofNullable(known.get("documents")).ifPresent(documents -> folders.add(documents.resolve("VirtualDJ").resolve("History")));
            oneDrive().ifPresent(cloud -> folders.add(cloud.resolve("Documents").resolve("VirtualDJ").resolve("History")));
        } else {
            folders.add(home.resolve("Library").resolve("Application Support").resolve("VirtualDJ").resolve("History"));
            folders.add(home.resolve("Documents").resolve("VirtualDJ").resolve("History"));
        }
        return folders.stream().distinct().toList();
    }

    List<Path> seratoSessionFolders() {
        List<Path> folders = new ArrayList<>(List.of(home.resolve("Music").resolve("_Serato_").resolve("History").resolve("Sessions")));
        Optional.ofNullable(known.get("music")).ifPresent(music -> folders.add(music.resolve("_Serato_").resolve("History").resolve("Sessions")));
        oneDrive().ifPresent(cloud -> folders.add(cloud.resolve("Music").resolve("_Serato_").resolve("History").resolve("Sessions")));
        return folders.stream().distinct().toList();
    }

    /**
     * OneDrive's folder on a Windows that backs up the Documents (and the Music) there — a new laptop's default: the programs then write
     * their history under it. The folders' names under it are taken as the English ones here; Windows' own answer
     * ({@link #knownFolders}) gives them whatever they are called.
     */
    private Optional<Path> oneDrive() {
        return windows ? folder("OneDrive") : Optional.empty();
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

    /**
     * Windows asked once, at the start, where its Music and Documents are (moved to OneDrive, named in the system's language) and where
     * VirtualDJ's HomeFolder points — by PowerShell, which writes them in UTF-8, so a user name with a Polish letter comes whole (the
     * answer of {@code reg} is in the console's code page). Nothing when PowerShell is missing, blocked or slow: the usual folders stay.
     */
    private static Map<String, Path> askWindows() {
        String script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "'music='+[Environment]::GetFolderPath('MyMusic');"
                + "'documents='+[Environment]::GetFolderPath('MyDocuments');"
                + "'virtualdj='+(Get-ItemProperty -Path 'HKCU:\\Software\\VirtualDJ' -Name HomeFolder -ErrorAction SilentlyContinue).HomeFolder";
        try {
            Process shell = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            shell.getOutputStream().close();
            if (!shell.waitFor(10, TimeUnit.SECONDS)) {
                shell.destroyForcibly();
                return Map.of();
            }
            return knownFolders(new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /** The folders of PowerShell's answer: lines "music=C:\…"; an empty value or any other line left out, a byte order mark too. */
    static Map<String, Path> knownFolders(String answer) {
        Map<String, Path> folders = new HashMap<>();
        for (String line : answer.replace("\uFEFF", "").split("\r?\n")) {
            int equals = line.indexOf('=');
            String name = equals > 0 ? line.substring(0, equals).strip() : "";
            String value = equals > 0 ? line.substring(equals + 1).strip() : "";
            if (List.of("music", "documents", "virtualdj").contains(name) && !value.isEmpty()) {
                try {
                    folders.put(name, Path.of(value));
                } catch (RuntimeException e) {
                    // not a path: left out
                }
            }
        }
        return Map.copyOf(folders);
    }

    /** VirtualDJ's own setting of where its folder is (HKCU\Software\VirtualDJ, HomeFolder), on Windows, when PowerShell said nothing. */
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
