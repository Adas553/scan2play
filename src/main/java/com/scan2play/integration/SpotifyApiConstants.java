package com.scan2play.integration;

/**
 * Constants used for Spotify Web API integration.
 * <p>
 * This class centralizes all "magic strings" related to OAuth2 parameters,
 * grant types, permission scopes, and JSON response keys to ensure type safety and avoid typos.
 * </p>
 */
public final class SpotifyApiConstants {

    private SpotifyApiConstants() {
        // Prevent instantiation
    }

    // --- OAuth2 Parameters ---
    public static final String PARAM_CLIENT_ID = "client_id";
    public static final String PARAM_CLIENT_SECRET = "client_secret";
    public static final String PARAM_RESPONSE_TYPE = "response_type";
    public static final String PARAM_REDIRECT_URI = "redirect_uri";
    public static final String PARAM_SCOPE = "scope";
    public static final String PARAM_STATE = "state";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_GRANT_TYPE = "grant_type";
    public static final String PARAM_REFRESH_TOKEN = "refresh_token";

    // --- Parameter Values ---
    public static final String VALUE_RESPONSE_TYPE_CODE = "code";

    public static final class GrantTypes {
        private GrantTypes() {}
        public static final String AUTHORIZATION_CODE = "authorization_code";
        public static final String REFRESH_TOKEN = "refresh_token";
    }

    // --- JSON Response Keys ---
    public static final class JsonKeys {
        private JsonKeys() {}
        public static final String ACCESS_TOKEN = "access_token";
        public static final String REFRESH_TOKEN = "refresh_token";
        public static final String EXPIRES_IN = "expires_in";
    }

    // --- Permission Scopes ---
    public static final class Scopes {
        /**
         * Scopes required for playback control and reading current state.
         * <br>
         * - user-modify-playback-state: Needed to queue songs.
         * - user-read-playback-state: Needed to check active device.
         * - user-read-private: Needed for basic profile info (optional but good practice).
         */
        public static final String PLAYBACK_SCOPES = 
            "user-modify-playback-state user-read-playback-state user-read-private";
            
        private Scopes() {}
    }
}
