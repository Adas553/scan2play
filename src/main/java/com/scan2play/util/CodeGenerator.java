package com.scan2play.util;

import java.security.SecureRandom;
import java.util.Random;

public final class CodeGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 5;
    private static final Random RANDOM = new SecureRandom();

    private CodeGenerator() {
        // Private constructor to prevent instantiation
    }

    /**
     * Generates a random, 5-character alphanumeric string (uppercase letters and digits).
     *
     * @return A unique party code, e.g., "X7B9Q".
     */
    public static String generatePartyCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
