package com.scan2play.deck;

import java.io.IOException;
import java.util.List;

/** One DJ program's play history on this computer. */
public interface HistorySource {

    /** The program, as the window names it ("VirtualDJ", "Serato"). */
    String program();

    /** Whether the program's history is on this computer at all. */
    boolean present();

    /**
     * Something that changes whenever the history does (a file's name and time): the songs are read again only then. Empty when
     * there is none now.
     */
    String version() throws IOException;

    /** The file or database being read now, for the window ("2026-11-21.m3u"). */
    String reading();

    /** The songs of the history being written now (today's file, the open session), oldest first. */
    List<Played> songs() throws IOException;
}
