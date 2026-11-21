package com.scan2play.deck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The little JSON the program writes and reads: objects of strings and numbers, an array of strings — no library for it. */
final class Json {

    private Json() {
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * The songs as the server takes them: {@code {"plays":[{"at":…,"artist":"…","title":"…"}],"sentAt":…}} — {@code sentAt} this
     * computer's clock as it sends, so the server can correct the songs' times if the clock is off.
     */
    static String plays(List<Played> songs, Instant sentAt) {
        StringBuilder out = new StringBuilder("{\"plays\":[");
        for (int i = 0; i < songs.size(); i++) {
            Played song = songs.get(i);
            out.append(i == 0 ? "" : ",").append("{\"at\":").append(song.at() == null ? "null" : song.at().getEpochSecond())
                    .append(",\"artist\":").append(quote(song.artist())).append(",\"title\":").append(quote(song.title())).append('}');
        }
        return out.append("],\"sentAt\":").append(sentAt.getEpochSecond()).append('}').toString();
    }

    /** A string member of an object: {@code "token"} of {"token":"…"}. */
    static Optional<String> string(String json, String name) {
        int key = json.indexOf(quote(name));
        if (key < 0) {
            return Optional.empty();
        }
        int i = json.indexOf(':', key) + 1;
        while (i > 0 && i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i <= 0 || i >= json.length() || json.charAt(i) != '"') {
            return Optional.empty();
        }
        StringBuilder value = new StringBuilder();
        return readString(json, i, value) < 0 ? Optional.empty() : Optional.of(value.toString());
    }

    /** The strings of an array member: {@code "marked"} of {"marked":["…","…"]}. */
    static List<String> strings(String json, String name) {
        List<String> values = new ArrayList<>();
        int key = json.indexOf(quote(name));
        if (key < 0) {
            return values;
        }
        int i = json.indexOf('[', key);
        if (i < 0) {
            return values;
        }
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ']') {
                break;
            }
            if (c == '"') {
                StringBuilder value = new StringBuilder();
                i = readString(json, i, value);
                if (i < 0) {
                    break;
                }
                values.add(value.toString());
                continue;
            }
            i++;
        }
        return values;
    }

    /** Reads the string starting at the quote {@code start} into {@code value}; the index after it, or -1 for a broken one. */
    private static int readString(String json, int start, StringBuilder value) {
        int i = start + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 5 >= json.length()) {
                            return -1;
                        }
                        value.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> value.append(next);
                }
                i += 2;
                continue;
            }
            value.append(c);
            i++;
        }
        return -1;
    }
}
