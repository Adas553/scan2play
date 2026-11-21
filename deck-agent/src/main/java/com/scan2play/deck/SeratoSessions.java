package com.scan2play.deck;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Serato DJ 2.x / 3.x: a file a session, {@code Music/_Serato_/History/Sessions/<n>.session}, appended while Serato runs (each load
 * and eject of a deck — SSLScrobbler's README). Binary chunks: 4 letters and a 4-byte big-endian length; a song is an {@code oent}
 * holding an {@code adat}, whose fields are a 4-byte number, a 4-byte length and the value — 6 the title, 7 the artist (UTF-16BE), 28
 * the start (seconds), 50 "played" (a byte). A song only loaded on a deck (played 0) is left out.
 */
final class SeratoSessions implements HistorySource {

    private static final int TITLE = 6;
    private static final int ARTIST = 7;
    private static final int START = 28;
    private static final int PLAYED = 50;

    private final List<Path> folders;
    private Path current;

    SeratoSessions(List<Path> folders) {
        this.folders = folders;
    }

    @Override
    public String program() {
        return "Serato";
    }

    @Override
    public boolean present() {
        return folders.stream().anyMatch(Files::isDirectory);
    }

    @Override
    public String version() throws IOException {
        Optional<Path> newest = NewestFile.in(folders, ".session");
        current = newest.orElse(null);
        return newest.isPresent() ? NewestFile.version(newest.get()) : "";
    }

    @Override
    public String reading() {
        return current == null ? "" : current.getFileName().toString();
    }

    @Override
    public List<Played> songs() throws IOException {
        return current == null ? List.of() : parse(Files.readAllBytes(current));
    }

    /** The played songs of a session file, by their start. */
    static List<Played> parse(byte[] data) {
        List<Played> songs = new ArrayList<>();
        ByteBuffer all = ByteBuffer.wrap(data);
        forEachChunk(all, 0, data.length, (name, start, end) -> {
            if (name.equals("oent")) {
                forEachChunk(all, start, end, (inner, from, to) -> {
                    if (inner.equals("adat")) {
                        song(all, from, to).ifPresent(songs::add);
                    }
                });
            }
        });
        songs.sort(Comparator.comparing(Played::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        return songs;
    }

    private interface Chunk {
        void at(String name, int start, int end);
    }

    private static void forEachChunk(ByteBuffer data, int from, int to, Chunk chunk) {
        int i = from;
        while (i + 8 <= to) {
            String name = new String(new byte[]{data.get(i), data.get(i + 1), data.get(i + 2), data.get(i + 3)}, StandardCharsets.ISO_8859_1);
            long length = Integer.toUnsignedLong(data.getInt(i + 4));
            int end = (int) Math.min(i + 8L + length, to);
            chunk.at(name, i + 8, end);
            i = (int) Math.min(i + 8L + length, Integer.MAX_VALUE);
        }
    }

    private static Optional<Played> song(ByteBuffer data, int from, int to) {
        String title = "";
        String artist = "";
        Long start = null;
        Boolean played = null;
        int i = from;
        while (i + 8 <= to) {
            int field = data.getInt(i);
            long length = Integer.toUnsignedLong(data.getInt(i + 4));
            int valueStart = i + 8;
            int valueEnd = (int) Math.min(valueStart + length, to);
            int size = valueEnd - valueStart;
            switch (field) {
                case TITLE -> title = text(data, valueStart, size);
                case ARTIST -> artist = text(data, valueStart, size);
                case START -> start = size == 4 ? Integer.toUnsignedLong(data.getInt(valueStart)) : size == 8 ? data.getLong(valueStart) : null;
                case PLAYED -> played = size > 0 ? data.get(valueStart) != 0 : null;
                default -> { }
            }
            i = (int) Math.min(valueStart + length, Integer.MAX_VALUE);
        }
        Played song = new Played(start == null ? null : Played.fromEpoch(start), artist, title);
        return Boolean.FALSE.equals(played) || song.isEmpty() ? Optional.empty() : Optional.of(song);
    }

    private static String text(ByteBuffer data, int start, int size) {
        byte[] bytes = new byte[size];
        data.get(start, bytes);
        return new String(bytes, StandardCharsets.UTF_16BE).replaceAll("\u0000+$", "");
    }
}
