package com.scan2play.deck;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.desktop.AppReopenedListener;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The program's one small window, in Scan2Play's colours (Main sets the dark theme): the logo, then unpaired the panel's code (the
 * server behind "Zmień serwer"); paired, a status line (working, or what is wrong), "Czyta" — each history read, or that none is seen
 * with "Wskaż folder historii…" — and "Oznaczone" — how many songs were marked since the start and the last
 * {@value Agent#MARKED_SHOWN} with their times, never a night's whole list. Closing it leaves the program in the tray (the notification
 * area, the menu bar) while there is one, where "Pokaż" brings it back and "Zakończ" ends it. Polish only: the program is for the Polish
 * DJs of Scan2Play, and its texts speak to the DJ without a gender, as the app's.
 */
final class Window {

    // the app's colours (app.css)
    static final Color BACKGROUND = new Color(0x161616);
    static final Color SECTION = new Color(0x1e1e1e);
    static final Color BORDER = new Color(0x333333);
    static final Color TEXT = new Color(0xe0e0e0);
    static final Color MUTED = new Color(0xa3acb6);
    static final Color CYAN = new Color(0x0dcaf0);
    static final Color GREEN = new Color(0x1db954);
    static final Color AMBER = new Color(0xffc107);
    static final Color RED = new Color(0xea868f);

    /** Read: no history of either program anywhere it is looked for. */
    static final String NO_HISTORY = "<html>Brak historii VirtualDJ i Serato. Zagraj w programie DJ-a jedną piosenkę<br>"
            + "albo wskaż folder, w którym trzyma historię.</html>";
    /** The folder picked holds no history the program reads. */
    static final String NOT_IN_FOLDER = "<html>W tym folderze nie ma historii — VirtualDJ: …\\VirtualDJ\\History,<br>"
            + "Serato: …\\_Serato_\\History\\Sessions albo …\\Serato\\Library.</html>";
    /**
     * The longest song or folder shown whole: a guest's request may be a remix's whole title, and the window, packed to its content,
     * would grow past the screen with "Odłącz ten komputer" and the time. Longer is cut with "…", the whole in the line's tooltip.
     */
    static final int LONGEST_LINE = 60;

    static String shortened(String text) {
        return text.length() <= LONGEST_LINE ? text : text.substring(0, LONGEST_LINE - 1) + "…";
    }

    /** A line of a song or a folder: {@link #shortened}, the whole of a long one in its tooltip. */
    private static JLabel longText(String words, Color color) {
        JLabel label = text(shortened(words), color, 0);
        label.setToolTipText(words.length() > LONGEST_LINE ? words : null);
        return label;
    }

    /**
     * The texts of Java's own dialogs in Polish — the folder chooser, the message of a second copy: Java has none of its own, and an
     * English "Look In" / "Cancel" beside the program's "Wybierz" looks unfinished. Set after the theme, which may set its own.
     */
    /**
     * "Oznaczone" before the first song: when a song marks itself, for either program — Serato's library counts a play a few seconds
     * after Play ({@link Serato4Database}), VirtualDJ writes its history after ~45 s.
     */
    static final String NOTHING_MARKED = "<html>Jeszcze nic. Piosenka z kolejki oznaczy się sama po puszczeniu:<br>"
            + "z Serato po kilku sekundach, z VirtualDJ po około 45 s.</html>";

    /**
     * The size a pack asks for: the content's height, but never narrower than before — the status line's width differs by state,
     * and with a venue's Wi-Fi coming and going the window would change its width every few seconds.
     */
    static Dimension grownOnly(Dimension wanted, Dimension before) {
        return before == null ? wanted : new Dimension(Math.max(wanted.width, before.width), wanted.height);
    }

    static void polishDialogs() {
        String[][] texts = {
                {"FileChooser.lookInLabelText", "Szukaj w:"}, {"FileChooser.folderNameLabelText", "Nazwa folderu:"},
                {"FileChooser.fileNameLabelText", "Nazwa:"}, {"FileChooser.filesOfTypeLabelText", "Pliki typu:"},
                {"FileChooser.acceptAllFileFilterText", "Wszystkie pliki"}, {"FileChooser.cancelButtonText", "Anuluj"},
                {"FileChooser.cancelButtonToolTipText", "Zamknij bez wyboru"}, {"FileChooser.openButtonText", "Otwórz"},
                {"FileChooser.upFolderToolTipText", "W górę"}, {"FileChooser.homeFolderToolTipText", "Folder domowy"},
                {"FileChooser.newFolderToolTipText", "Nowy folder"}, {"FileChooser.listViewButtonToolTipText", "Lista"},
                {"FileChooser.detailsViewButtonToolTipText", "Szczegóły"}, {"FileChooser.fileNameHeaderText", "Nazwa"},
                {"FileChooser.fileSizeHeaderText", "Rozmiar"}, {"FileChooser.fileDateHeaderText", "Zmieniono"},
                {"OptionPane.cancelButtonText", "Anuluj"}, {"OptionPane.yesButtonText", "Tak"}, {"OptionPane.noButtonText", "Nie"}};
        for (String[] text : texts) {
            javax.swing.UIManager.put(text[0], text[1]);
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("Scan2Play — Zagrane samo");
    private final JPanel pairPanel = column();
    private final JTextField code = new JTextField(7);
    private final JTextField server = new JTextField(24);
    private final JPanel serverRow;
    private final JButton connect = new JButton("Połącz");
    private final JLabel pairAnswer = text(" ", AMBER, 0);
    private final JPanel pairedPanel = column();
    private final JLabel state = text(" ", TEXT, Font.BOLD);
    private final Dot dot = new Dot();
    private final JLabel checked = text(" ", MUTED, 0);
    private final JPanel readingRows = column();
    private final JLabel markedTitle = text(" ", MUTED, Font.BOLD);
    private final JPanel markedRows = column();
    private final JButton pickFolder = new JButton("Wskaż folder historii…");
    private final JButton forgetFolder = link("Nie czytaj go");
    private final JLabel folderLine = text(" ", MUTED, 0);
    private final JButton disconnect = new JButton("Odłącz ten komputer");
    private final Settings settings;
    private final Predicate<Path> onFolder;
    private boolean wasPaired;
    private Agent.Status shown;

    /**
     * @param onConnect the code and the server typed
     * @param onDisconnect "Odłącz ten komputer"
     * @param onFolder a folder picked ("Wskaż folder historii…"), or null for "Nie czytaj go": whether a history is in it
     */
    Window(Settings settings, Consumer<String[]> onConnect, Runnable onDisconnect, Predicate<Path> onFolder) {
        this.settings = settings;
        this.onFolder = onFolder;
        this.wasPaired = settings.token() != null;

        // ---- unpaired: the panel's code ----
        pairPanel.add(left(text("Połącz ten komputer z imprezą", TEXT, Font.BOLD)));
        pairPanel.add(gap(6));
        pairPanel.add(left(text("<html>1. W panelu Scan2Play: kolejka › „Z programu DJ-a” › „Połącz komputer”.<br>"
                + "2. Wpisz tutaj kod z panelu.</html>", MUTED, 0)));
        pairPanel.add(gap(10));
        code.setFont(code.getFont().deriveFont(Font.BOLD, 22f));
        code.setHorizontalAlignment(SwingConstants.CENTER);
        code.putClientProperty("JTextField.placeholderText", "123 456");
        code.setMaximumSize(new Dimension(170, 44));
        connect.putClientProperty("JButton.buttonType", "default");
        connect.setFont(connect.getFont().deriveFont(Font.BOLD));
        pairPanel.add(left(row(code, connect)));
        pairPanel.add(gap(6));
        pairPanel.add(left(pairAnswer));
        server.setText(settings.server());
        JButton changeServer = link("Zmień serwer");
        serverRow = row(text("Serwer:", MUTED, 0), server);
        serverRow.setVisible(!Settings.DEFAULT_SERVER.equals(settings.server()));
        changeServer.setVisible(!serverRow.isVisible());
        changeServer.addActionListener(e -> {
            serverRow.setVisible(true);
            changeServer.setVisible(false);
            repack();
        });
        pairPanel.add(gap(8));
        pairPanel.add(left(changeServer));
        pairPanel.add(left(serverRow));
        connect.addActionListener(e -> {
            String notYet = beforeSending(code.getText());
            if (notYet != null) {
                pairAnswer.setText(notYet);
                return;
            }
            connect.setEnabled(false);
            pairAnswer.setForeground(MUTED);
            pairAnswer.setText("Łączenie…");
            onConnect.accept(new String[]{code.getText(), server.getText()});
        });
        code.addActionListener(e -> connect.doClick());

        // ---- paired: the state, what is read, what was marked ----
        JPanel stateRow = new JPanel(new BorderLayout(8, 0));
        stateRow.setOpaque(false);
        JPanel stateLeft = row(dot, state);
        stateRow.add(stateLeft, BorderLayout.WEST);
        stateRow.add(checked, BorderLayout.EAST);
        pairedPanel.add(left(stateRow));
        pairedPanel.add(gap(12));

        JPanel reading = section();
        reading.add(left(text("CZYTA", MUTED, Font.BOLD)));
        reading.add(gap(6));
        reading.add(left(readingRows));
        reading.add(gap(6));
        reading.add(left(folderLine));
        reading.add(left(row(pickFolder, forgetFolder)));
        pairedPanel.add(left(reading));
        pairedPanel.add(gap(10));

        JPanel marked = section();
        marked.add(left(markedTitle));
        marked.add(gap(6));
        marked.add(left(markedRows));
        pairedPanel.add(left(marked));
        pairedPanel.add(gap(12));

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(text("<html>Do Scan2Play trafiają tylko wykonawca,<br>tytuł i godzina piosenek.</html>", MUTED, 0), BorderLayout.WEST);
        footer.add(disconnect, BorderLayout.EAST);
        pairedPanel.add(left(footer));
        pickFolder.addActionListener(e -> pick());
        forgetFolder.addActionListener(e -> {
            onFolder.test(null);
            showFolder();
        });
        disconnect.addActionListener(e -> onDisconnect.run());

        JPanel content = column();
        content.setOpaque(true);
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        content.add(left(header()));
        content.add(gap(14));
        content.add(left(pairPanel));
        content.add(left(pairedPanel));
        frame.setContentPane(content);
        frame.setIconImages(List.of(icon(16), icon(32), icon(64)));
        frame.setDefaultCloseOperation(SystemTray.isSupported() ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
        frame.getRootPane().setDefaultButton(connect);
        tray();
        reopenFromTheDock();
        showPaired(settings.token() != null);
        showFolder();
        update(new Agent.Status(settings.token() != null, List.of(), java.time.Instant.now(), List.of(), 0, null));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            frame.toFront();
            // the code's box when it is asked for; else no button framed by the focus as if it were the thing to press
            if (pairPanel.isVisible()) {
                code.requestFocusInWindow();
            } else {
                frame.getRootPane().requestFocusInWindow();
            }
        });
    }

    /** The pairing's answer: paired, or why not. */
    void paired(ServerClient.Outcome outcome) {
        SwingUtilities.invokeLater(() -> {
            connect.setEnabled(true);
            pairAnswer.setForeground(AMBER);
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
            repack();
        });
    }

    /** What to say instead of asking the server: an empty box is not a wrong code, the DJ has not typed one yet. */
    static String beforeSending(String code) {
        return code == null || code.isBlank() ? "Wpisz kod z panelu." : null;
    }

    void update(Agent.Status status) {
        SwingUtilities.invokeLater(() -> {
            showPaired(status.paired());
            // said once, when the panel's "Odłącz" is heard: said at every look it would wipe the answer to the code just typed
            if (wasPaired && !status.paired() && status.last() == ServerClient.Outcome.DISCONNECTED) {
                pairAnswer.setForeground(AMBER);
                pairAnswer.setText("Ten komputer odłączono w panelu — połącz go nowym kodem.");
            }
            wasPaired = status.paired();
            Line line = line(status);
            dot.color = line.color();
            dot.repaint();
            state.setText(line.text());
            checked.setText("sprawdzone " + TIME.format(status.checkedAt()));
            // the lists rebuilt only when they changed: every 3 s they would flicker and lose a hover
            if (shown == null || !shown.reading().equals(status.reading())) {
                readingRows.removeAll();
                if (status.reading().isEmpty()) {
                    readingRows.add(left(text(NO_HISTORY, AMBER, 0)));
                }
                for (String source : status.reading()) {
                    boolean broken = source.contains("nie da się odczytać");
                    readingRows.add(left(row(text(broken ? "!" : "✓", broken ? RED : GREEN, Font.BOLD), text(source, broken ? RED : TEXT, 0))));
                }
                // nothing seen: a button the DJ cannot miss; something read: a quiet link, for the rare other folder
                boolean nothing = status.reading().isEmpty();
                pickFolder.setText(nothing ? "Wskaż folder historii…" : "Inny folder historii…");
                pickFolder.putClientProperty("JButton.buttonType", nothing ? null : "borderless");
                pickFolder.setForeground(nothing ? null : CYAN);
            }
            if (shown == null || !shown.marked().equals(status.marked()) || shown.markedCount() != status.markedCount()) {
                markedTitle.setText("OZNACZONE OD URUCHOMIENIA" + (status.markedCount() > 0 ? " · " + status.markedCount() : ""));
                markedRows.removeAll();
                if (status.marked().isEmpty()) {
                    markedRows.add(left(text(NOTHING_MARKED, MUTED, 0)));
                }
                for (Agent.Marked song : status.marked()) {
                    JLabel time = text(MINUTE.format(song.at()), MUTED, 0);
                    time.setFont(time.getFont().deriveFont(Font.PLAIN));
                    readingWidth(time);
                    markedRows.add(left(row(time, longText(song.song(), TEXT))));
                }
            }
            shown = status;
            repack();
        });
    }

    /** The status line: working, or the one thing wrong now. */
    record Line(String text, Color color) {
    }

    static Line line(Agent.Status status) {
        if (status.last() == ServerClient.Outcome.FAILED) {
            return new Line("Brak połączenia z serwerem — kolejna próba za chwilę", RED);
        }
        if (status.last() == ServerClient.Outcome.TOO_MANY) {
            return new Line("Serwer prosi o chwilę przerwy", AMBER);
        }
        if (status.last() == ServerClient.Outcome.NO_PLAN) {
            return new Line("„Zagrane samo” działa w planie Pro — ta impreza go nie ma", AMBER);
        }
        if (status.reading().isEmpty()) {
            return new Line("Połączono — brak historii VirtualDJ i Serato", AMBER);
        }
        return new Line("Działa — połączono z imprezą", GREEN);
    }

    private void pick() {
        JFileChooser chooser = new JFileChooser(settings.folder() != null ? settings.folder().toFile() : null);
        chooser.setDialogTitle("Folder historii VirtualDJ albo Serato");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setApproveButtonText("Wybierz");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        boolean found = onFolder.test(chooser.getSelectedFile().toPath());
        showFolder();
        if (!found) {
            folderLine.setForeground(AMBER);
            folderLine.setText(NOT_IN_FOLDER);
            repack();
        }
    }

    private void showFolder() {
        Path folder = settings.folder();
        folderLine.setForeground(MUTED);
        folderLine.setText(folder == null ? " " : "Wskazany folder: " + shortened(folder.toString()));
        folderLine.setToolTipText(folder == null || folder.toString().length() <= LONGEST_LINE ? null : folder.toString());
        folderLine.setVisible(folder != null);
        forgetFolder.setVisible(folder != null);
        repack();
    }

    private void showPaired(boolean paired) {
        if (pairedPanel.isVisible() != paired || pairPanel.isVisible() == paired) {
            pairPanel.setVisible(!paired);
            pairedPanel.setVisible(paired);
            repack();
        }
    }

    /**
     * Packed only when the content's size changed: a pack at every look (3 s) would undo a window the DJ stretched. Never narrower
     * than before ({@link #grownOnly}).
     */
    private void repack() {
        Dimension content = frame.getContentPane().getPreferredSize();
        Dimension wanted = grownOnly(content, packedFor);
        if (!wanted.equals(packedFor)) {
            packedFor = wanted;
            frame.pack();
            if (content.width < wanted.width) {
                frame.setSize(frame.getWidth() + wanted.width - content.width, frame.getHeight());
            }
        }
    }

    private Dimension packedFor;

    // ---- the header and the logo ----

    private static JComponent header() {
        JLabel logo = new JLabel(new ImageIconOf(icon(64), 34));
        JLabel name = new JLabel("<html><span style='color:#f8f9fa'>Scan</span><span style='color:#0dcaf0'>2</span>"
                + "<span style='color:#f8f9fa'>Play</span></html>");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 19f));
        JLabel what = text("Zagrane samo", MUTED, 0);
        JPanel words = column();
        words.add(left(name));
        words.add(left(what));
        JPanel header = row(logo, words);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        header.setBorder(BorderFactory.createCompoundBorder(header.getBorder(), BorderFactory.createEmptyBorder(0, 0, 12, 0)));
        return header;
    }

    /**
     * The Scan2Play mark (logo-small.svg): cyan modules in the shape of a play triangle on a dark rounded square — drawn, so the
     * program ships no picture; the window's, the taskbar's and the tray's icon.
     */
    static Image icon(int size) {
        float[][] modules = {{16.1f, 10.4f}, {16.1f, 17.8f}, {16.1f, 25.2f}, {16.1f, 32.6f}, {16.1f, 40f}, {16.1f, 47.4f},
                {23.5f, 14.1f}, {23.5f, 28.9f}, {23.5f, 36.3f}, {23.5f, 43.7f}, {30.9f, 17.8f}, {30.9f, 25.2f}, {30.9f, 32.6f},
                {30.9f, 40f}, {38.3f, 21.5f}, {38.3f, 28.9f}, {38.3f, 36.3f}, {45.7f, 25.2f}, {45.7f, 32.6f}};
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 64.0, size / 64.0);
        g.setColor(new Color(0x1a1a1a));
        g.fill(new RoundRectangle2D.Float(0, 0, 64, 64, 28, 28));
        g.setColor(CYAN);
        for (float[] module : modules) {
            g.fill(new RoundRectangle2D.Float(module[0], module[1], 6.2f, 6.2f, 2.8f, 2.8f));
        }
        g.dispose();
        return image;
    }

    /** An image drawn at a size of its own (the 64 px mark shown at 34, sharp on a screen at 150 % too). */
    private record ImageIconOf(Image image, int size) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(image, x, y, size, size, null);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    /** The status line's dot: drawn, not "●", which not every font of Windows has. */
    private static final class Dot extends JComponent {
        Color color = MUTED;

        Dot() {
            setPreferredSize(new Dimension(10, 10));
            setMaximumSize(new Dimension(10, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(0, (getHeight() - 10) / 2, 10, 10);
            g2.dispose();
        }
    }

    // ---- the tray ----

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

    // ---- small pieces ----

    private static JPanel column() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        return column;
    }

    /** A framed block of the paired view ("Czyta", "Oznaczone"), as a card of the panel. */
    private static JPanel section() {
        JPanel section = column();
        section.setOpaque(true);
        section.setBackground(SECTION);
        section.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return section;
    }

    private static JPanel row(Component... parts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                row.add(Box.createHorizontalStrut(8));
            }
            row.add(parts[i]);
        }
        return row;
    }

    private static JLabel text(String words, Color color, int style) {
        JLabel label = new JLabel(words);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style == 0 ? Font.PLAIN : style));
        return label;
    }

    /** A button that looks as a link of the panel: cyan text, no frame. */
    private static JButton link(String words) {
        JButton link = new JButton(words);
        link.putClientProperty("JButton.buttonType", "borderless");
        link.setForeground(CYAN);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return link;
    }

    /** The times of the marked songs one width, so the songs start in one column. */
    private static void readingWidth(JLabel time) {
        time.setPreferredSize(new Dimension(time.getFontMetrics(time.getFont()).stringWidth("00:00") + 4, time.getPreferredSize().height));
    }

    private static Component gap(int height) {
        return Box.createVerticalStrut(height);
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    Settings settings() {
        return settings;
    }

    void failedToSave(IOException e) {
        SwingUtilities.invokeLater(() -> {
            connect.setEnabled(true);
            pairAnswer.setForeground(RED);
            pairAnswer.setText("Nie udało się zapisać połączenia: " + e.getMessage());
            repack();
        });
    }
}
