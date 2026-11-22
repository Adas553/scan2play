package com.scan2play.deck;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Clock;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * "Scan2Play — Zagrane samo" (docs/ideas.md item 19): the program on the DJ's computer. It reads VirtualDJ's and Serato's play history
 * and sends each new song to Scan2Play, which marks the queue's request of it played. A window and a tray icon on a computer with a
 * screen; without one (a test on a server) it runs in the terminal:
 * <pre>
 *   java -jar deck-agent.jar --pair 123456 [--server https://dev.scan2play.com.pl]   once: the panel's code
 *   java -jar deck-agent.jar                                                     then: reads and sends, prints what it does
 * </pre>
 */
public final class Main {

    static final int LOOK_EVERY_SECONDS = 3;

    static final String ALREADY_RUNNING = "Program już działa — kliknij jego ikonę w zasobniku (obok zegara) i wybierz „Pokaż”.";

    /** Held for the program's whole life: a lock no longer reachable could be closed by the garbage collector. */
    private static SingleInstance running;

    private Main() {
    }

    public static void main(String[] args) {
        String version = String.valueOf(Main.class.getPackage().getImplementationVersion());
        Settings settings = Settings.forThisUser();
        ServerClient client = new ServerClient(version);
        ScheduledExecutorService looks = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "zagrane-samo");
            thread.setDaemon(true);
            return thread;
        });

        boolean inConsole = GraphicsEnvironment.isHeadless() || List.of(args).contains("--pair") || List.of(args).contains("--console");
        if (!inConsole) {
            theme();
        }
        if (!onlyCopy()) {
            if (inConsole) {
                System.out.println(ALREADY_RUNNING);
            } else {
                JOptionPane.showMessageDialog(null, ALREADY_RUNNING, "Scan2Play — Zagrane samo", JOptionPane.INFORMATION_MESSAGE);
            }
            System.exit(1);
        }
        // only now, a copy that will run: the usual places at once, Windows' answer and the look through the folders in the
        // background (seconds) — the window is up meanwhile, and a second copy is refused before any of it
        List<HistorySource> sources = Platform.withoutAsking().sources();
        if (inConsole) {
            console(args, settings, client, sources, looks);
            return;
        }

        Window[] window = new Window[1];
        Agent agent = new Agent(sources, client, settings, Clock.systemDefaultZone(), status -> window[0].update(status));
        agent.useFolder(settings.folder());
        window[0] = new Window(settings, entered -> looks.execute(() -> {
            String server = entered[1].isBlank() ? Settings.DEFAULT_SERVER : ServerClient.stripSlash(entered[1]);
            ServerClient.Answer answer = client.pair(server, entered[0], Platform.computerName());
            if (answer.outcome() == ServerClient.Outcome.OK) {
                try {
                    settings.paired(server, answer.token());
                } catch (IOException e) {
                    window[0].failedToSave(e);
                    return;
                }
            }
            window[0].paired(answer.outcome());
            agent.tick();
        }), () -> looks.execute(() -> {
            try {
                agent.disconnect();
            } catch (IOException e) {
                window[0].failedToSave(e);
            }
            agent.tick();
        }), folder -> {
            try {
                settings.folder(folder);
            } catch (IOException e) {
                window[0].failedToSave(e);
            }
            agent.useFolder(folder);
            looks.execute(agent::tick);
            return folder == null || holdsAHistory(folder);
        });
        window[0].show();
        looks.scheduleWithFixedDelay(agent::tick, 0, LOOK_EVERY_SECONDS, TimeUnit.SECONDS);
        findTheRest(agent);
    }

    /** Windows' answer (PowerShell) and the look through the folders, on a thread of their own; their places read from then on. */
    private static void findTheRest(Agent agent) {
        Thread finder = new Thread(() -> agent.useSources(Platform.current().sources()), "zagrane-szukanie");
        finder.setDaemon(true);
        finder.start();
    }

    /** Scan2Play's look: FlatLaf's dark theme in the app's colours (app.css), round corners as the panel's buttons and fields. */
    private static void theme() {
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#0dcaf0", "@background", "#161616", "@foreground", "#e0e0e0"));
        FlatDarkLaf.setup();
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("Button.default.background", Window.CYAN);
        UIManager.put("Button.default.foreground", new java.awt.Color(0x0b0b0b));
        UIManager.put("Button.default.hoverBackground", new java.awt.Color(0x3dd5f3));
        Window.polishDialogs();
    }

    /** Whether a folder the DJ picked holds a VirtualDJ or Serato history (the window says so when not). */
    static boolean holdsAHistory(Path folder) {
        for (HistorySource source : Platform.sourcesIn(folder)) {
            try {
                if (source.present() && !source.version().isEmpty()) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                return true;   // there, though not readable now: the window's "Czyta" says what is wrong
            }
        }
        return false;
    }

    /**
     * True when no other copy runs. A lock that cannot be made at all (the folder not writable) does not stop the program: the guard
     * only spares the DJ a second copy, it must never keep the only one from starting.
     */
    private static boolean onlyCopy() {
        try {
            running = SingleInstance.tryAcquire(SingleInstance.forThisUser());
            return running != null;
        } catch (IOException e) {
            return true;
        }
    }

    private static void console(String[] args, Settings settings, ServerClient client, List<HistorySource> sources,
                                ScheduledExecutorService looks) {
        List<String> given = List.of(args);
        int pair = given.indexOf("--pair");
        if (pair >= 0) {
            int at = given.indexOf("--server");
            String server = at >= 0 && at + 1 < args.length ? ServerClient.stripSlash(args[at + 1]) : settings.server();
            ServerClient.Answer answer = pair + 1 < args.length ? client.pair(server, args[pair + 1], Platform.computerName())
                    : new ServerClient.Answer(ServerClient.Outcome.WRONG_CODE, null, List.of());
            if (answer.outcome() != ServerClient.Outcome.OK) {
                System.out.println("Nie połączono: " + answer.outcome());
                System.exit(1);
            }
            try {
                settings.paired(server, answer.token());
            } catch (IOException e) {
                System.out.println("Nie udało się zapisać połączenia: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("Połączono z " + server);
        }
        if (settings.token() == null) {
            System.out.println("Najpierw: --pair <kod z panelu> [--server <adres>]");
            System.exit(1);
        }
        String[] said = {""};
        Agent agent = new Agent(sources, client, settings, Clock.systemDefaultZone(), status -> {
            String line = "czyta " + status.reading() + "  " + status.last()
                    + (status.marked().isEmpty() ? "" : "  zagrane (" + status.markedCount() + "): " + status.marked().getFirst().song());
            if (!line.equals(said[0])) {   // only what changed
                said[0] = line;
                System.out.println(status.checkedAt() + "  " + line);
            }
        });
        looks.scheduleWithFixedDelay(agent::tick, 0, LOOK_EVERY_SECONDS, TimeUnit.SECONDS);
        findTheRest(agent);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
