package com.scan2play.deck;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
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

    private Main() {
    }

    public static void main(String[] args) {
        String version = String.valueOf(Main.class.getPackage().getImplementationVersion());
        Settings settings = Settings.forThisUser();
        ServerClient client = new ServerClient(version);
        List<HistorySource> sources = Platform.current().sources();
        ScheduledExecutorService looks = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "zagrane-samo");
            thread.setDaemon(true);
            return thread;
        });

        if (GraphicsEnvironment.isHeadless() || List.of(args).contains("--pair") || List.of(args).contains("--console")) {
            console(args, settings, client, sources, looks);
            return;
        }

        Window[] window = new Window[1];
        Agent agent = new Agent(sources, client, settings, Clock.systemDefaultZone(), status -> window[0].update(status));
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
                settings.disconnected();
            } catch (IOException e) {
                window[0].failedToSave(e);
            }
            agent.tick();
        }));
        window[0].show();
        looks.scheduleWithFixedDelay(agent::tick, 0, LOOK_EVERY_SECONDS, TimeUnit.SECONDS);
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
            String line = "czyta " + status.reading() + "  " + status.last() + (status.marked().isEmpty() ? "" : "  zagrane: " + status.marked());
            if (!line.equals(said[0])) {   // only what changed
                said[0] = line;
                System.out.println(status.checkedAt() + "  " + line);
            }
        });
        looks.scheduleWithFixedDelay(agent::tick, 0, LOOK_EVERY_SECONDS, TimeUnit.SECONDS);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
