package com.scan2play.model;

/**
 * A command the DJ gives to the dashboard window that plays, from any window of the party (the phone as a remote
 * control of the computer): it travels to the window that plays inside the answer to that window's next lease report.
 */
public enum PlayerCommand {
    /** Skip to the next track: what {@code next-track} hands out now, whatever the player is doing. */
    NEXT
}
