package com.scan2play.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps a party's Spotify tokens encrypted in the database (review 5.3): AES-256-GCM with the key of {@code SPOTIFY_TOKEN_KEY}
 * (32 bytes, base64 — the application does not start without it). A column holds {@code enc:v1:} + base64(nonce + ciphertext).
 * <p>
 * In memory the entity holds the plain token, as before — only the column changes. A value without the prefix is a token
 * written before the encryption: it is read as it is, and {@code SpotifyTokenEncryptionOnStartup} encrypts such rows at
 * start. A value that cannot be decrypted (the key was changed) is read as no token: the party still loads, the DJ connects
 * Spotify again — a thrown exception would have broken every page of the party, not only Spotify.
 * <p>
 * Hibernate takes this converter from Spring (Spring Boot's bean container), so the key is injected.
 */
@Component
@Converter
@Slf4j
public class SpotifyTokenConverter implements AttributeConverter<String, String> {

    /** What every encrypted column value starts with (also in {@code PartySettingsRepository.findPlainSpotifyTokens}). */
    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SpotifyTokenConverter(@Value("${spotify.token-key}") String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SPOTIFY_TOKEN_KEY is not base64 (32 random bytes, e.g. `openssl rand -base64 32`)");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("SPOTIFY_TOKEN_KEY must be 32 bytes (base64 of 32 random bytes), not " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String token) {
        if (token == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(NONCE_BYTES + encrypted.length).put(nonce).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a Spotify token", e);   // AES-GCM is in every JDK: not expected
        }
    }

    @Override
    public String convertToEntityAttribute(String column) {
        if (column == null || !column.startsWith(PREFIX)) return column;   // none, or written before the encryption
        try {
            byte[] stored = Base64.getDecoder().decode(column.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            return new String(cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("A Spotify token cannot be decrypted (was SPOTIFY_TOKEN_KEY changed?) — read as none; the DJ connects Spotify again");
            return null;
        }
    }
}
