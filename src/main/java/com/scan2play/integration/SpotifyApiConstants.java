package com.scan2play.integration;

/**
 * Centralizes constants related to the Spotify Web API, particularly for the OAuth2 flow.
 * This avoids magic strings and ensures consistency across the application.
 */
public final class SpotifyApiConstants {

    private SpotifyApiConstants() {
        // Prevent instantiation
    }

    // --- Parameter Names ---
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_SCOPE = "scope";
    public static final String PARAM_GRANT_TYPE = "grant_type";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_REFRESH_TOKEN = "refresh_token";

    // --- Parameter Values ---
    public static final String VALUE_RESPONSE_TYPE_CODE = "code";

    public static final class GrantTypes {
        private GrantTypes() {}
        public static final String AUTHORIZATION_CODE = "authorization_code";
        public static final String REFRESH_TOKEN = "refresh_token";
    }

    public static final class Scopes {
        private Scopes() {}
        public static final String USER_MODIFY_PLAYBACK_STATE = "user-modify-playback-state";
        public static final String USER_READ_PLAYBACK_STATE = "user-read-playback-state";
        
        // A combined string for convenience
        public static final String PLAYBACK_SCOPES = USER_MODIFY_PLAYBACK_STATE + " " + USER_READ_PLAYBACK_STATE;
    }
}
