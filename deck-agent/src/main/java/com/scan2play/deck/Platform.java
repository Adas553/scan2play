package com.scan2play.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** What {@link #look} found at the start beside the usual places. */
    private final Found found;
    /** False for {@link #withoutAsking}: VirtualDJ's HomeFolder not asked of the registry (up to 3 s). */
    private boolean askRegistry = true;

    /** The histories found by looking through the user's folders: added to the usual places, never instead of them. */
    record Found(List<Path> virtualDjFolders, List<Path> seratoSessionFolders, List<Path> serato4Databases, int folders) {
        static final Found NONE = new Found(List.of(), List.of(), List.of(), 0);
    }

    /** How deep a look goes below each of its folders, and how many folders it sees at most: seconds, never the whole disk. */
    static final int LOOK_DEPTH = 4;
    static final int LOOK_MAX_FOLDERS = 30_000;

    Platform(Path home, Map<String, String> env, boolean windows) {
        this(home, env, windows, Map.of());
    }

    Platform(Path home, Map<String, String> env, boolean windows, Map<String, Path> known) {
        this(home, env, windows, known, Found.NONE);
    }

    Platform(Path home, Map<String, String> env, boolean windows, Map<String, Path> known, Found found) {
        this.home = home;
        this.env = env;
        this.windows = windows;
        this.known = known;
        this.found = found;
    }

    /**
     * The usual places at once, without asking Windows (PowerShell, the registry) or looking through the folders: what the program
     * reads while {@link #current} works, in the background, for seconds.
     */
    static Platform withoutAsking() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Platform quick = new Platform(Path.of(System.getProperty("user.home")), System.getenv(), windows);
        quick.askRegistry = false;
        return quick;
    }

    static Platform current() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path home = Path.of(System.getProperty("user.home"));
        Map<String, String> env = System.getenv();
        Map<String, Path> known = windows ? askWindows() : Map.of();
        Platform usual = new Platform(home, env, windows, known);
        return new Platform(home, env, windows, known, look(usual.lookIn()));
    }

    /**
     * The histories a folder the DJ picked may hold, itself or right under it as the programs make them: VirtualDJ's {@code .m3u}
     * (in it or its {@code History}), Serato's {@code .session} (in it, {@code History/Sessions} or {@code Sessions}) and Serato's
     * {@code master.sqlite} (in it or {@code Library}). Each source says itself whether it is there.
     */
    static List<HistorySource> sourcesIn(Path folder) {
        return List.of(new VirtualDjHistory(List.of(folder, folder.resolve("History"))),
                new SeratoSessions(List.of(folder, folder.resolve("History").resolve("Sessions"), folder.resolve("Sessions"))),
                new Serato4Database(List.of(folder.resolve("master.sqlite"), folder.resolve("Library").resolve("master.sqlite"))));
    }

    /**
     * Where {@link #look} looks, the likeliest first — Music, Documents, OneDrive, AppData (both) — and the whole profile last: the
     * look's folders are shared by all, and a profile with many folders must not use them up before Music is reached.
     */
    List<Path> lookIn() {
        List<Path> roots = new ArrayList<>();
        Optional.ofNullable(known.get("music")).ifPresent(roots::add);
        Optional.ofNullable(known.get("documents")).ifPresent(roots::add);
        oneDrive().ifPresent(roots::add);
        folder("LOCALAPPDATA").ifPresent(roots::add);
        folder("APPDATA").ifPresent(roots::add);
        roots.add(home);
        return roots.stream().distinct().toList();
    }

    /**
     * Folders no DJ program writes its history in, skipped by the look: hidden ones (.cache, .git, .m2), node_modules, Windows' app
     * packages (AppData\Local\Packages), macOS' Caches and Containers, the recycle bin — they hold most of a profile's folders.
     */
    private static final Set<String> NO_HISTORY_IN = Set.of("node_modules", "packages", "caches", "containers", "$recycle.bin");

    private static boolean noHistoryIn(String name) {
        return name.startsWith(".") || NO_HISTORY_IN.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * A short look through the folders for the DJ programs' histories in places no reader names (another version, another install —
     * Serato DJ Lite 5.1 moved its database): a folder "Serato" with {@code Library/master.sqlite}, "_Serato_" with
     * {@code History/Sessions}, "VirtualDJ" with {@code History}, at most {@value #LOOK_DEPTH} levels down and {@value #LOOK_MAX_FOLDERS}
     * folders in all; links not followed, a folder that cannot be opened skipped. Once, at the start.
     */
    static Found look(List<Path> roots) {
        return look(roots, LOOK_MAX_FOLDERS);
    }

    /** {@link #look} with a limit of its own (the tests). */
    static Found look(List<Path> roots, int maxFolders) {
        Set<Path> virtualDj = new LinkedHashSet<>();
        Set<Path> sessions = new LinkedHashSet<>();
        Set<Path> databases = new LinkedHashSet<>();
        int[] seen = {0};
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), LOOK_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                        String name = folder.getFileName() == null ? "" : folder.getFileName().toString();
                        // another root (Music inside the profile) is looked through on its own: once is enough
                        if (!folder.equals(root) && (roots.contains(folder) || noHistoryIn(name))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (++seen[0] > maxFolders) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (name.equalsIgnoreCase("Serato") && Files.isRegularFile(folder.resolve("Library").resolve("master.sqlite"))) {
                            databases.add(folder.resolve("Library").resolve("master.sqlite"));
                        } else if (name.equalsIgnoreCase("_Serato_") && Files.isDirectory(folder.resolve("History").resolve("Sessions"))) {
                            sessions.add(folder.resolve("History").resolve("Sessions"));
                        } else if (name.equalsIgnoreCase("VirtualDJ") && Files.isDirectory(folder.resolve("History"))) {
                            virtualDj.add(folder.resolve("History"));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;   // a folder closed to the user, a link that leads nowhere
                    }
                });
            } catch (IOException | RuntimeException e) {
                // the root itself unreadable: the usual places stay
            }
            if (seen[0] > maxFolders) {
                break;
            }
        }
        return new Found(List.copyOf(virtualDj), List.copyOf(sessions), List.copyOf(databases), seen[0]);
    }

    /** Every program's history this computer may have; which of them is there is the source's own question (present()). */
    List<HistorySource> sources() {
        List<HistorySource> sources = new ArrayList<>(List.of(new VirtualDjHistory(virtualDjFolders()),
                new SeratoSessions(seratoSessionFolders()), new Serato4Database(serato4Databases())));
        sources.addAll(MusicApps.ON_THIS_COMPUTER);
        return List.copyOf(sources);
    }

    /**
     * The music apps (Spotify, TIDAL), one of each for the program's life (the usual places and the look's whole list both hold
     * them): each counts the song playing now and, on Windows, they share one PowerShell — new ones would count from zero and start
     * another PowerShell.
     */
    private static final class MusicApps {
        static final List<NowPlaying> ON_THIS_COMPUTER = NowPlaying.onThisComputer();
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
        folders.addAll(found.virtualDjFolders());
        return folders.stream().distinct().toList();
    }

    List<Path> seratoSessionFolders() {
        List<Path> folders = new ArrayList<>(List.of(home.resolve("Music").resolve("_Serato_").resolve("History").resolve("Sessions")));
        Optional.ofNullable(known.get("music")).ifPresent(music -> folders.add(music.resolve("_Serato_").resolve("History").resolve("Sessions")));
        oneDrive().ifPresent(cloud -> folders.add(cloud.resolve("Music").resolve("_Serato_").resolve("History").resolve("Sessions")));
        folders.addAll(found.seratoSessionFolders());
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

    /**
     * Serato 4 and later: {@code master.sqlite}. On Windows under {@code %LOCALAPPDATA%} (Serato DJ Lite 5.1 on a DJ's Windows 11) and
     * under {@code %APPDATA%} (where the readers of Serato 4 put it); the one written last is read ({@link Serato4Database#version}).
     */
    List<Path> serato4Databases() {
        List<Path> databases = new ArrayList<>();
        if (windows) {
            Path local = folder("LOCALAPPDATA").orElse(home.resolve("AppData").resolve("Local"));
            Path roaming = folder("APPDATA").orElse(home.resolve("AppData").resolve("Roaming"));
            databases.add(local.resolve("Serato").resolve("Library").resolve("master.sqlite"));
            databases.add(roaming.resolve("Serato").resolve("Library").resolve("master.sqlite"));
        } else {
            databases.add(home.resolve("Library").resolve("Application Support").resolve("Serato").resolve("Library").resolve("master.sqlite"));
        }
        databases.addAll(found.serato4Databases());
        return databases.stream().distinct().toList();
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
        if (!windows || !askRegistry) {
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
