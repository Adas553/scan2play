package com.scan2play.model;

/**
 * How the AI words its comment to the guest, as the DJ picks it (V22; the owner, 2026-10-07). {@link #CLASSIC} is the prompt as it
 * was before: no block of its own. The others add a block of {@code prompts/prompt-comment-style_{pl,en}.txt} to the prompt. Stored
 * by name (a check constraint lists them, V22); the order is the order of the list on the dashboard.
 */
public enum CommentStyle {
    CLASSIC,
    FUNNY,
    SARCASTIC_LIGHT,
    SARCASTIC,
    SHORT
}
