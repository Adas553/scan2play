package com.scan2play.service;

import com.scan2play.util.LibraryKey;
import com.scan2play.util.SongNames;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The DJ's library as their program exports it — a rekordbox collection ("File > Export Collection in xml format"), whole or one of
 * its playlists, or a playlist (M3U / M3U8, which every program writes) — read as it streams in, so a collection of tens of
 * thousands of tracks never sits in memory as text. What is kept is each song's {@link LibraryKey}, which the queue is matched by,
 * and its {@link Track} as the file writes it — the artist, the title, the tempo, the key, the genre, the year (V57) —: never a path,
 * which names the DJ's computer and account, nor the DJ's own notes, ratings or play counts.
 */
public final class DjLibraryParser {

    /** The file as it was read. */
    public enum Format { M3U, REKORDBOX }

    /**
     * A song as the DJ's file writes it (V57), for showing — the queue shows the tempo and the key of a song the DJ has; never matched
     * by (that is its {@link LibraryKey}). What the file does not say is null: an M3U has no tempo, no key, no genre, no year, and its
     * artist is a guess (the part of the name before " - "). Each field cut to its column.
     */
    public record Track(String artist, String title, BigDecimal bpm, String tonality, String genre, Integer year) {

        /** Nothing known but the key: a library made from keys alone (the tests'). */
        public static final Track NONE = new Track(null, null, null, null, null, null);

        public Track {
            artist = cut(artist, LibraryKey.MAX);
            title = cut(title, LibraryKey.MAX);
            tonality = cut(tonality, TONALITY_MAX);
            genre = cut(genre, GENRE_MAX);
            bpm = bpm == null || bpm.signum() <= 0 || bpm.compareTo(BPM_LIMIT) >= 0 ? null : bpm.setScale(2, RoundingMode.HALF_UP);
            year = year == null || year < 1 || year > 9999 ? null : year;
        }

        /** The same song under another artist and title (an M3U's name, an untagged track read as "Artist - Title"). */
        Track named(String newArtist, String newTitle) {
            return new Track(newArtist, newTitle, bpm, tonality, genre, year);
        }

        /**
         * Of two tracks of one key (the versions of a song), the one kept: the shorter title — "One More Time" before "One More Time
         * (Extended Mix)" —, the first read when they are as long.
         */
        static Track shorter(Track first, Track second) {
            return length(second.title) < length(first.title) ? second : first;
        }

        private static int length(String text) {
            return text == null ? Integer.MAX_VALUE : text.length();
        }

        private static String cut(String text, int max) {
            String line = text == null ? "" : SongNames.tidy(text);
            return line.isEmpty() ? null : line.length() > max ? line.substring(0, max) : line;
        }
    }

    /** The longest key of a song ("5A", "F#m", "Abmaj"), as the column {@code dj_library_track.tonality}. */
    static final int TONALITY_MAX = 10;

    /** The longest genre kept, as the column {@code dj_library_track.genre}. */
    static final int GENRE_MAX = 100;

    /** A tempo of this or more is no song's (the column holds 999.99). */
    private static final BigDecimal BPM_LIMIT = BigDecimal.valueOf(1000);

    /**
     * The songs read: each key with the track it is shown as ({@link Track}; the same song in two versions is one key, so there are
     * fewer than the tracks); {@code swappedSongs} the keys of names read the other way round ("Baśka - Wilki" for "Wilki - Baśka" —
     * an M3U's, an untagged track's), none of them among the songs: such a key has an artist's name for its title, so it may only say
     * "✓", never "? Podobny" ({@link LibraryKey#markIn}); {@code playlist} the rekordbox playlist they are, by its folders ("Kluby /
     * Piątek"), null for the whole collection or an M3U.
     */
    public record Library(Format format, int tracks, Map<LibraryKey, Track> songs, Map<LibraryKey, Track> swappedSongs,
                          String playlist) {

        /** A library of keys alone, nothing known of their tracks. */
        public Library(Format format, int tracks, Set<LibraryKey> keys, Set<LibraryKey> swapped, String playlist) {
            this(format, tracks, unknown(keys), unknown(swapped), playlist);
        }

        /** A library of keys alone without swapped ones. */
        public Library(Format format, int tracks, Set<LibraryKey> keys, String playlist) {
            this(format, tracks, keys, Set.of(), playlist);
        }

        public Set<LibraryKey> keys() {
            return songs.keySet();
        }

        public Set<LibraryKey> swapped() {
            return swappedSongs.keySet();
        }

        private static Map<LibraryKey, Track> unknown(Set<LibraryKey> keys) {
            Map<LibraryKey, Track> songs = new LinkedHashMap<>();
            keys.forEach(key -> songs.put(key, Track.NONE));
            return songs;
        }
    }

    /** The songs of a file as they are read: a song's versions are one key, shown as the shortest ({@link Track#shorter}). */
    private static final class Songs {

        private final Map<LibraryKey, Track> songs = new HashMap<>();
        private final Map<LibraryKey, Track> swapped = new HashMap<>();

        /** A track by its artist and title, as a rekordbox collection has them; none without a title to match. */
        void add(Track track) {
            LibraryKey key = LibraryKey.of(track.artist(), track.title());
            if (!key.isEmpty()) {
                songs.merge(key, track, Track::shorter);
            }
        }

        /**
         * A track known only by a name "Artist - Title" (an M3U's line, a collection's track without its tags): the programs write
         * "Artist - Title" or "Title - Artist", so a name with one " - " is kept the other way round too, as a swapped key. None
         * without a title to match.
         */
        void addName(String name, Track details) {
            String line = SongNames.tidy(name);
            LibraryKey key = LibraryKey.of(line);
            if (key.isEmpty()) {
                return;
            }
            int dash = line.indexOf(" - ");
            songs.merge(key, dash < 0 ? details.named(null, line) : details.named(line.substring(0, dash), line.substring(dash + 3)),
                    Track::shorter);
            String[] halves = line.split(" - ");
            if (halves.length == 2) {
                LibraryKey other = LibraryKey.of(halves[1] + " - " + halves[0]);
                if (!other.isEmpty()) {
                    swapped.merge(other, details.named(halves[1], halves[0]), Track::shorter);
                }
            }
        }

        Library library(Format format, int tracks, String playlist) {
            swapped.keySet().removeAll(songs.keySet());   // a song the file has both ways round is one the DJ has as it is
            return new Library(format, tracks, songs, swapped, playlist);
        }
    }

    /** A file that is not a library this app reads, one with no song, or one with more than the most tracks. */
    public static final class UnreadableLibraryException extends RuntimeException {

        public enum Reason { UNKNOWN_FORMAT, EMPTY, TOO_MANY, COLLECTION_TOO_LARGE, NO_PLAYLIST }

        private final Reason reason;

        UnreadableLibraryException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * The most tracks of a rekordbox collection one playlist of which is read: each is held in memory until the playlists, after the
     * collection, say which of them to keep — a large DJ's collection, with room. Unlimited, the 64 MB of an upload would hold 1.6
     * million tiny tracks: half a gigabyte of the server's memory.
     */
    public static final int MAX_COLLECTION = 100_000;

    /**
     * The deepest folder of a rekordbox collection's playlists: a DJ's tree is a few folders deep. Each playlist is named by its
     * folders, so a tree without a limit — a million folders, one in another, and a million playlists at the bottom — would take
     * hours of the server's time for a file of a few megabytes.
     */
    static final int MAX_DEPTH = 32;

    /** The longest line of a playlist: a title and a path, with room. */
    static final int LONGEST_LINE = 8192;

    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");

    /** What no line of a playlist has: a control character, or a byte that is no letter in UTF-8 nor in Windows-1250. */
    private static final Pattern BINARY = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F\\uFFFD]");

    private DjLibraryParser() {
    }

    /** The library in {@code in}, the first playlist of the name when one is named: {@link #parse(InputStream, int, int, String, int)}. */
    public static Library parse(InputStream in, int maxTracks, String playlist) throws IOException {
        return parse(in, maxTracks, MAX_COLLECTION, playlist, 1);
    }

    /**
     * The library in {@code in}: XML when its first character is "<", else M3U. At most {@code maxTracks} tracks kept. {@code playlist}
     * (a rekordbox collection's, by its folders; null: the whole collection) keeps only its tracks — the {@code occurrence}th playlist
     * of that name, as rekordbox lets two of a folder have one name — out of a collection of at most {@code maxCollection} tracks; an
     * M3U is one playlist already.
     */
    public static Library parse(InputStream in, int maxTracks, int maxCollection, String playlist, int occurrence) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in);
        String chosen = playlist == null || playlist.isBlank() ? null : playlist.strip();
        Library library = startsLikeXml(buffered)
                ? rekordbox(buffered, maxTracks, maxCollection, chosen, Math.max(1, occurrence)) : m3u(buffered, maxTracks);
        if (library.keys().isEmpty()) {
            throw new UnreadableLibraryException(UnreadableLibraryException.Reason.EMPTY);
        }
        return library;
    }

    /** Whether the first character but a byte-order mark and white space is "<"; the stream is put back where it was. */
    private static boolean startsLikeXml(BufferedInputStream in) throws IOException {
        in.mark(1024);
        try {
            for (int i = 0, b; i < 1024 && (b = in.read()) != -1; i++) {
                if (b == '<') {
                    return true;
                }
                if (!Character.isWhitespace(b) && b != 0xEF && b != 0xBB && b != 0xBF) {
                    return false;
                }
            }
            return false;
        } finally {
            in.reset();
        }
    }

    /**
     * An M3U playlist: a track is the title of its {@code #EXTINF} line ("#EXTINF:245,Wilki - Baśka"), or its file's name without
     * the folder and the extension when there is none. The order of the artist and the title is not fixed among the programs, so a
     * name with one " - " is kept the other way round too, as a swapped key.
     */
    private static Library m3u(InputStream in, int maxTracks) throws IOException {
        Songs songs = new Songs();
        int tracks = 0;
        String title = null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String line; (line = nextLine(in, bytes)) != null; ) {
            line = line.replace("\uFEFF", "").strip();
            if (line.isEmpty()) {
                continue;
            }
            if (BINARY.matcher(line).find()) {
                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);   // a song file, a picture
            }
            if (line.startsWith("#EXTINF")) {
                int comma = line.indexOf(',');
                title = comma < 0 ? null : line.substring(comma + 1).strip();
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String name = title != null && !title.isEmpty() ? title : fileName(line);
            title = null;
            if (++tracks > maxTracks) {
                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.TOO_MANY);
            }
            songs.addName(name, Track.NONE);
        }
        return songs.library(Format.M3U, tracks, null);
    }

    /**
     * The next line of a playlist, without its line break; null at the end. UTF-8 (M3U8, and what most programs write now), else
     * Windows-1250 — an old Windows playlist (Winamp, VirtualDJ) with Polish letters. A line longer than {@value #LONGEST_LINE}
     * bytes is no playlist's: a song file or a picture without line breaks.
     */
    private static String nextLine(InputStream in, ByteArrayOutputStream line) throws IOException {
        line.reset();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (line.size() == LONGEST_LINE) {
                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
            }
            line.write(b);
        }
        if (b == -1 && line.size() == 0) {
            return null;
        }
        byte[] read = line.toByteArray();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(read)).toString();
        } catch (CharacterCodingException e) {
            return new String(read, WINDOWS_1250);
        }
    }

    /** A track's number before a file's name: "01 - ", "01. ", "01) ", "01-" — when an "Artist - Title" follows it. */
    private static final Pattern TRACK_NUMBER = Pattern.compile("^\\d{1,3}\\s*[-.)]\\s*(?=\\S.*\\s-\\s)");

    /**
     * "C:\Music\Wilki - Baśka.mp3" is "Wilki - Baśka"; so are "01 - Wilki - Baśka.mp3" (a track's number: the artist would be "01")
     * and "Wilki_-_Baśka.mp3" (underscores for spaces). A number followed by a space alone stays: "50 Cent - In da Club".
     */
    static String fileName(String path) {
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        name = dot > 0 && name.length() - dot <= 5 ? name.substring(0, dot) : name;
        name = name.replace('_', ' ').replaceAll("\\s+", " ").strip();
        return TRACK_NUMBER.matcher(name).replaceFirst("");
    }

    /**
     * A rekordbox collection: the {@code TRACK}s of its {@code COLLECTION}, by their {@code Artist} and {@code Name} (with its
     * {@code Mix} when the name does not say it) — a track without an artist, whose name is "Artist - Title" (a file never tagged:
     * rekordbox names it by the file), read as an M3U's name —, with their {@code AverageBpm}, {@code Tonality}, {@code Genre} and
     * {@code Year} for showing; or, with a
     * playlist, the tracks its {@code TRACK Key}s point at — the {@code PLAYLISTS} after the collection are a tree of {@code NODE}s,
     * folders ({@code Type="0"}, the tree's own "ROOT" not named) and playlists ({@code Type="1"}), named as rekordbox shows them:
     * "Kluby / Piątek". The DTD and the entities of the file are never read (XXE): the file is the DJ's, not ours. The JDK's own StAX,
     * whatever else a library on the class path offers: its limits (the elements' depth, an attribute's length) refuse a file built to
     * use up the memory.
     */
    private static Library rekordbox(InputStream in, int maxTracks, int maxCollection, String playlist, int occurrence)
            throws IOException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        Songs songs = new Songs();
        Map<Integer, Track> byId = new HashMap<>();   // the collection by TrackID, when a playlist is chosen
        Deque<String> folders = new ArrayDeque<>();   // the NODEs open: their names, "" for the ROOT and the playlists
        int tracks = 0;
        int named = 0;   // the playlists of the chosen name so far
        boolean found = false;
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            boolean root = true;
            boolean inCollection = false;
            boolean inChosen = false;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = xml.getLocalName();
                    if (root && !"DJ_PLAYLISTS".equals(element)) {
                        throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
                    }
                    root = false;
                    if ("COLLECTION".equals(element)) {
                        inCollection = true;
                    } else if (inCollection && "TRACK".equals(element)) {
                        Track track = trackOf(xml);
                        if (LibraryKey.of(track.artist(), track.title()).isEmpty()) {
                            continue;
                        }
                        if (playlist != null) {
                            Integer id = trackId(xml.getAttributeValue(null, "TrackID"));
                            if (id == null) {
                                continue;   // no playlist can point at it
                            }
                            if (byId.size() >= maxCollection) {
                                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.COLLECTION_TOO_LARGE);
                            }
                            byId.put(id, track);
                        } else {
                            if (++tracks > maxTracks) {
                                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.TOO_MANY);
                            }
                            add(songs, track);
                        }
                    } else if (playlist != null && "NODE".equals(element)) {
                        String name = xml.getAttributeValue(null, "Name");
                        boolean isPlaylist = "1".equals(xml.getAttributeValue(null, "Type"));
                        boolean isRoot = folders.isEmpty() && "ROOT".equals(name);
                        if (isPlaylist && !found && playlist.equals(pathOf(folders, name)) && ++named == occurrence) {
                            inChosen = found = true;
                        }
                        if (folders.size() >= MAX_DEPTH) {
                            throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
                        }
                        folders.push(isPlaylist || isRoot || name == null ? "" : name);
                    } else if (inChosen && "TRACK".equals(element)) {
                        Integer id = trackId(xml.getAttributeValue(null, "Key"));
                        Track track = id == null ? null : byId.get(id);
                        if (track != null) {
                            if (++tracks > maxTracks) {
                                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.TOO_MANY);
                            }
                            add(songs, track);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = xml.getLocalName();
                    if ("COLLECTION".equals(element)) {
                        inCollection = false;
                        if (playlist == null) {
                            break;   // the playlists only point at the collection's tracks
                        }
                    } else if ("NODE".equals(element) && !folders.isEmpty()) {
                        folders.pop();
                        inChosen = false;   // a playlist holds no NODE: its end is this one's
                    }
                }
            }
            if (root) {
                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
            }
        } catch (XMLStreamException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause() != null ? cause.getCause() : nested(cause)) {
                if (cause instanceof IOException io) {
                    throw io;   // the reading failed (the upload too large, the connection gone), not the file
                }
            }
            throw new UnreadableLibraryException(UnreadableLibraryException.Reason.UNKNOWN_FORMAT);
        }
        if (playlist != null && !found) {
            throw new UnreadableLibraryException(UnreadableLibraryException.Reason.NO_PLAYLIST);
        }
        return songs.library(Format.REKORDBOX, tracks, playlist);
    }

    /**
     * A collection's {@code TRACK} as it is shown: its artist and its name — with its {@code Mix} in brackets when the name does not
     * say it already ("Levels" of the mix "Skrillex Remix" is "Levels (Skrillex Remix)"; the key leaves a bracket out anyway) —, its
     * tempo, key, genre and year. A number that is no number is left out, not the track.
     */
    private static Track trackOf(XMLStreamReader xml) {
        String name = xml.getAttributeValue(null, "Name");
        String mix = xml.getAttributeValue(null, "Mix");
        String title = name == null || mix == null || mix.isBlank()
                || name.toLowerCase(Locale.ROOT).contains(mix.strip().toLowerCase(Locale.ROOT)) ? name : name + " (" + mix.strip() + ")";
        return new Track(xml.getAttributeValue(null, "Artist"), title, decimal(xml.getAttributeValue(null, "AverageBpm")),
                xml.getAttributeValue(null, "Tonality"), xml.getAttributeValue(null, "Genre"),
                trackId(xml.getAttributeValue(null, "Year")));
    }

    /** A collection's track among the songs: by its artist and title, or — no artist, a name "Artist - Title" — by its name. */
    private static void add(Songs songs, Track track) {
        if (track.artist() == null && track.title() != null && track.title().contains(" - ")) {
            songs.addName(track.title(), track);
        } else {
            songs.add(track);
        }
    }

    /** A decimal of the file ("128.00"); null when it is none. */
    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A playlist's name with the folders it is in, from the outermost: "Kluby / Piątek". */
    private static String pathOf(Deque<String> folders, String name) {
        StringBuilder path = new StringBuilder();
        folders.descendingIterator().forEachRemaining(folder -> {
            if (!folder.isEmpty()) {
                path.append(folder).append(" / ");
            }
        });
        return path.append(name == null ? "" : name).toString();
    }

    /** A whole number of the file: a track's number in the collection ({@code TrackID}, a playlist's {@code Key}), a year; null when it is none. */
    private static Integer trackId(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** StAX keeps the failure of the reading as its "nested exception", not always as its cause. */
    private static Throwable nested(Throwable e) {
        return e instanceof XMLStreamException xml && xml.getNestedException() != e ? xml.getNestedException() : null;
    }
}
