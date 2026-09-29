package com.scan2play.model;

/** How the DJ moves a track of the fallback queue. */
public enum MoveDirection {
    /** One place earlier. */
    UP,
    /** One place later. */
    DOWN,
    /** In front of everything else: "play next". */
    TOP
}
