package com.scan2play.model;


/**
 * Represents the DJ's response to a song request.
 *
 * @param decision    The decision made by the DJ (e.g., "accepted", "rejected").
 * @param comment     A short comment or feedback from the DJ.
 * @param songName    The confirmed name of the song.
 * @param energyLevel The energy level of the song on a scale of 1-10.
 */
public record DjResponse(
        String decision,
        String comment,
        String songName,
        int energyLevel
) {}