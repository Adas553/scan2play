package com.scan2play.deck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VirtualDJ 8+: a file a day in its History folder, {@code <date>.m3u}, a song's line appended once it has played for VirtualDJ's
 * {@code historyDelay}: {@code #EXTVDJ:<time>21:39</time><lastplaytime>1674884385</lastplaytime><artist>…</artist><title>…</title>}
 * (What's Now Playing reads the same). The folder: {@code %LOCALAPPDATA%\VirtualDJ\History} on a recent Windows install, else the
 * Documents' {@code VirtualDJ/History}, or wherever the registry's {@code HomeFolder} points (Platform).
 */
final class VirtualDjHistory implements HistorySource {

    private static final Pattern TAG = Pattern.compile("<(\\w+)>(.*?)</\\1>");

    private final List<Path> folders;
    private Path current;

    VirtualDjHistory(List<Path> folders) {
        this.folders = folders;
    }

    @Override
    public String program() {
        return "VirtualDJ";
    }

    @Override
    public boolean present() {
        return folders.stream().anyMatch(Files::isDirectory);
    }

    @Override
    public String version() throws IOException {
        Optional<Path> newest = NewestFile.in(folders, ".m3u");
        current = newest.orElse(null);
        return newest.isPresent() ? NewestFile.version(newest.get()) : "";
    }

    @Override
    public String reading() {
        return current == null ? "" : current.getFileName().toString();
    }

    @Override
    public List<Played> songs() throws IOException {
        if (current == null) {
            return List.of();
        }
        return parse(new String(Files.readAllBytes(current), StandardCharsets.UTF_8));
    }

    /** The songs of a history file's text, in its order. */
    static List<Played> parse(String text) {
        List<Played> songs = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String plain = line.startsWith("﻿") ? line.substring(1) : line;
            if (!plain.startsWith("#EXTVDJ:")) {
                continue;
            }
            String artist = "";
            String title = "";
            Long at = null;
            Matcher tag = TAG.matcher(plain);
            while (tag.find()) {
                String value = Xml.unescape(tag.group(2));
                switch (tag.group(1)) {
                    case "artist" -> artist = value;
                    case "title" -> title = value;
                    case "lastplaytime" -> at = value.matches("\\d{1,15}") ? Long.parseLong(value) : null;
                    default -> { }
                }
            }
            Played song = new Played(at == null ? null : Played.fromEpoch(at), artist, title);
            if (!song.isEmpty()) {
                songs.add(song);
            }
        }
        return songs;
    }
}
