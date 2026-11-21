package com.scan2play.deck;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.desktop.AppReopenedListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The program's one small window: unpaired, the panel's code (and the server, filled in); paired, what is read, when it last looked
 * and the songs marked. Closing it leaves the program in the tray (the notification area, the menu bar) while there is one, where
 * "Pokaż" brings it back and "Zakończ" ends it. Polish only: the program is for the Polish DJs of Scan2Play, and its texts speak to
 * the DJ without a gender, as the app's.
 */
final class Window {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("Scan2Play — Zagrane samo");
    private final JPanel pairPanel = new JPanel();
    private final JTextField code = new JTextField(8);
    private final JTextField server = new JTextField(24);
    private final JButton connect = new JButton("Połącz");
    private final JLabel pairAnswer = new JLabel(" ");
    private final JPanel pairedPanel = new JPanel();
    private final JLabel reading = new JLabel(" ");
    private final JLabel checked = new JLabel(" ");
    private final JLabel marked = new JLabel(" ");
    private final JLabel problem = new JLabel(" ");
    private final JButton disconnect = new JButton("Odłącz ten komputer");
    private final Settings settings;
    private boolean wasPaired;

    Window(Settings settings, Consumer<String[]> onConnect, Runnable onDisconnect) {
        this.settings = settings;
        this.wasPaired = settings.token() != null;
        pairPanel.setLayout(new BoxLayout(pairPanel, BoxLayout.Y_AXIS));
        pairPanel.add(left(new JLabel("<html>1. W panelu Scan2Play: kolejka › „Z programu DJ-a” › „Połącz komputer”.<br>"
                + "2. Wpisz tutaj kod z panelu.</html>")));
        pairPanel.add(Box.createVerticalStrut(8));
        pairPanel.add(left(row(new JLabel("Kod:"), code, connect)));
        pairPanel.add(left(row(new JLabel("Serwer:"), server)));
        pairPanel.add(left(pairAnswer));
        server.setText(settings.server());
        connect.addActionListener(e -> {
            String notYet = beforeSending(code.getText());
            if (notYet != null) {
                pairAnswer.setText(notYet);
                return;
            }
            connect.setEnabled(false);
            pairAnswer.setText("Łączenie…");
            onConnect.accept(new String[]{code.getText(), server.getText()});
        });
        code.addActionListener(e -> connect.doClick());

        pairedPanel.setLayout(new BoxLayout(pairedPanel, BoxLayout.Y_AXIS));
        pairedPanel.add(left(new JLabel("<html>Program czyta historię VirtualDJ i Serato. Gdy zagra piosenka z kolejki,<br>"
                + "Scan2Play sam oznaczy ją jako zagraną. Do Scan2Play trafiają tylko wykonawca, tytuł i godzina.</html>")));
        pairedPanel.add(Box.createVerticalStrut(8));
        pairedPanel.add(left(reading));
        pairedPanel.add(left(checked));
        pairedPanel.add(left(marked));
        pairedPanel.add(left(problem));
        pairedPanel.add(Box.createVerticalStrut(8));
        pairedPanel.add(left(disconnect));
        problem.setForeground(new Color(0xa3271f));
        disconnect.addActionListener(e -> onDisconnect.run());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        content.add(pairPanel);
        content.add(pairedPanel);
        frame.setContentPane(content);
        frame.setIconImage(icon(64));
        frame.setDefaultCloseOperation(SystemTray.isSupported() ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
        tray();
        reopenFromTheDock();
        showPaired(settings.token() != null);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            frame.toFront();
        });
    }

    /** The pairing's answer: paired, or why not. */
    void paired(ServerClient.Outcome outcome) {
        SwingUtilities.invokeLater(() -> {
            connect.setEnabled(true);
            switch (outcome) {
                case OK -> {
                    code.setText("");
                    pairAnswer.setText(" ");
                    showPaired(true);
                }
                case WRONG_CODE -> pairAnswer.setText("Ten kod nie działa — weź nowy z panelu (kod jest ważny 10 minut).");
                case TOO_MANY -> pairAnswer.setText("Za dużo prób — spróbuj za kilka minut.");
                default -> pairAnswer.setText("Brak połączenia z serwerem — sprawdź internet i adres serwera.");
            }
        });
    }

    /** What to say instead of asking the server: an empty box is not a wrong code, the DJ has not typed one yet. */
    static String beforeSending(String code) {
        return code == null || code.isBlank() ? "Wpisz kod z panelu." : null;
    }

    void update(Agent.Status status) {
        SwingUtilities.invokeLater(() -> {
            Dimension before = frame.getContentPane().getPreferredSize();
            showPaired(status.paired());
            // said once, when the panel's "Odłącz" is heard: said at every look it would wipe the answer to the code just typed
            if (wasPaired && !status.paired() && status.last() == ServerClient.Outcome.DISCONNECTED) {
                pairAnswer.setText("Ten komputer odłączono w panelu — połącz go nowym kodem.");
            }
            wasPaired = status.paired();
            reading.setText(status.reading().isEmpty()
                    ? "Nie widać historii VirtualDJ ani Serato na tym komputerze."
                    : "Czyta: " + String.join(", ", status.reading()));
            checked.setText("Sprawdzone: " + TIME.format(status.checkedAt()));
            marked.setForeground(status.marked().isEmpty() ? Color.GRAY.darker() : new Color(0x1d7a46));
            marked.setText(status.marked().isEmpty() ? "Zagrane samo: jeszcze nic od uruchomienia programu."
                    : "✓ Zagrane samo: " + String.join(" · ", status.marked()));
            problem.setText(status.last() == ServerClient.Outcome.FAILED ? "Brak połączenia z serwerem — kolejna próba za chwilę."
                    : status.last() == ServerClient.Outcome.TOO_MANY ? "Serwer prosi o chwilę przerwy."
                    : status.last() == ServerClient.Outcome.NO_PLAN ? "„Zagrane samo” działa w planie Pro — ta impreza go teraz nie ma." : " ");
            // only when the content changed its size: a pack at every look (3 s) would undo a window the DJ stretched
            if (!before.equals(frame.getContentPane().getPreferredSize())) {
                frame.pack();
            }
        });
    }

    private void showPaired(boolean paired) {
        boolean changed = pairedPanel.isVisible() != paired;
        pairPanel.setVisible(!paired);
        pairedPanel.setVisible(paired);
        if (changed) {
            frame.pack();
        }
    }

    private void tray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Pokaż");
        open.addActionListener(e -> show());
        MenuItem quit = new MenuItem("Zakończ");
        quit.addActionListener(e -> System.exit(0));
        menu.add(open);
        menu.add(quit);
        TrayIcon icon = new TrayIcon(icon(32), "Scan2Play — Zagrane samo", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> show());
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException e) {
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        }
    }

    /**
     * macOS: a click on the program's icon in the Dock (or opening the app again from Finder, which starts no second copy there)
     * shows the window closed into the menu bar; without this the click does nothing and the program looks dead.
     */
    private void reopenFromTheDock() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            Desktop.getDesktop().addAppEventListener((AppReopenedListener) e -> show());
        }
    }

    /** A record's disc with a tick: drawn, so the program ships no picture. */
    static Image icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x0d9488));
        g.fillOval(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(2f, size / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolyline(new int[]{size * 27 / 100, size * 44 / 100, size * 74 / 100}, new int[]{size * 52 / 100, size * 68 / 100, size * 34 / 100}, 3);
        g.dispose();
        return image;
    }

    private static JPanel row(Component... parts) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        for (Component part : parts) {
            row.add(part);
            row.add(Box.createHorizontalStrut(6));
        }
        return row;
    }

    private static <T extends javax.swing.JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (component instanceof JLabel label) {
            label.setFont(label.getFont().deriveFont(Font.PLAIN));
        }
        return component;
    }

    Settings settings() {
        return settings;
    }

    void failedToSave(IOException e) {
        SwingUtilities.invokeLater(() -> pairAnswer.setText("Nie udało się zapisać połączenia: " + e.getMessage()));
    }
}
