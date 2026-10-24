package com.scan2play.service;

import com.scan2play.util.SongNames;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The DJ's library as their program exports it — a rekordbox collection ("File > Export Collection in xml format") or a playlist
 * (M3U / M3U8, which every program writes) — read as it streams in, so a collection of tens of thousands of tracks never sits in
 * memory whole. What is kept is each song's {@link SongNames#libraryKey}: never a path, which names the DJ's computer and account.
 */
public final class DjLibraryParser {

    /** The file as it was read. */
    public enum Format { M3U, REKORDBOX }

    /** The tracks read and their keys (fewer than the tracks: the same song in two versions is one key). */
    public record Library(Format format, int tracks, Set<String> keys) {
    }

    /** A file that is not a library this app reads, one with no song, or one with more than the most tracks. */
    public static final class UnreadableLibraryException extends RuntimeException {

        public enum Reason { UNKNOWN_FORMAT, EMPTY, TOO_MANY }

        private final Reason reason;

        UnreadableLibraryException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** The longest line of a playlist: a title and a path, with room. */
    static final int LONGEST_LINE = 8192;

    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");

    /** What no line of a playlist has: a control character, or a byte that is no letter in UTF-8 nor in Windows-1250. */
    private static final Pattern BINARY = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F\\uFFFD]");

    private DjLibraryParser() {
    }

    /** The library in {@code in}: XML when its first character is "<", else M3U. At most {@code maxTracks} tracks. */
    public static Library parse(InputStream in, int maxTracks) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in);
        Library library = startsLikeXml(buffered) ? rekordbox(buffered, maxTracks) : m3u(buffered, maxTracks);
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
     * name with one " - " is kept both ways round.
     */
    private static Library m3u(InputStream in, int maxTracks) throws IOException {
        Set<String> keys = new HashSet<>();
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
            add(keys, name);
            String[] halves = name.split(" - ");
            if (halves.length == 2) {
                add(keys, halves[1] + " - " + halves[0]);
            }
        }
        return new Library(Format.M3U, tracks, keys);
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

    /** "C:\Music\Wilki - Baśka.mp3" is "Wilki - Baśka". */
    private static String fileName(String path) {
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 5 ? name.substring(0, dot) : name;
    }

    /**
     * A rekordbox collection: the {@code TRACK}s of its {@code COLLECTION}, by their {@code Artist} and {@code Name} — the playlists
     * after it only point at them. The DTD and the entities of the file are never read (XXE): the file is the DJ's, not ours.
     */
    private static Library rekordbox(InputStream in, int maxTracks) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        Set<String> keys = new HashSet<>();
        int tracks = 0;
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            boolean root = true;
            boolean inCollection = false;
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
                        String title = xml.getAttributeValue(null, "Name");
                        String artist = xml.getAttributeValue(null, "Artist");
                        if (title != null && !title.isBlank()) {
                            if (++tracks > maxTracks) {
                                throw new UnreadableLibraryException(UnreadableLibraryException.Reason.TOO_MANY);
                            }
                            add(keys, artist == null || artist.isBlank() ? title : artist + " - " + title);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "COLLECTION".equals(xml.getLocalName())) {
                    break;   // the playlists only point at the collection's tracks
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
        return new Library(Format.REKORDBOX, tracks, keys);
    }

    /** StAX keeps the failure of the reading as its "nested exception", not always as its cause. */
    private static Throwable nested(Throwable e) {
        return e instanceof XMLStreamException xml && xml.getNestedException() != e ? xml.getNestedException() : null;
    }

    private static void add(Set<String> keys, String name) {
        String key = SongNames.libraryKey(name);
        if (!key.isEmpty()) {
            keys.add(key);
        }
    }
}
