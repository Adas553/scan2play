package com.scan2play.controller;

/**
 * Centralizes constants for model attribute names used in Thymeleaf views.
 * This prevents typos and makes refactoring easier.
 */
public final class ViewAttributes {

    private ViewAttributes() {
        // Prevent instantiation
    }

    // --- Common Attributes ---
    public static final String GLOBAL_VIBE = "globalVibe";
    public static final String ACTIVE_PROVIDER = "activeProvider";

    // --- Dashboard Attributes ---
    public static final String PLAYBACK_MODE = "playbackMode";
    public static final String IS_SPOTIFY_CONNECTED = "isSpotifyConnected";
    public static final String HISTORY = "history";
    public static final String QR_CODE_BASE64 = "qrCodeBase64";

    // --- Index/Guest Attributes ---
    public static final String PUBLIC_QUEUE = "publicQueue";

    // --- Result Page Attributes ---
    public static final String RESPONSE = "response";
}
