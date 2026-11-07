package com.scan2play.model;

/**
 * How the AI words its comment to the guest, as the DJ picks it (V22). {@link #CLASSIC} is the prompt without a block of its own. The
 * others add a block of {@code prompts/prompt-comment-style_{pl,en}.txt} to the prompt. Stored by name (a check constraint lists them:
 * V26, V65); the order is the order of the list on the dashboard.
 * <p>
 * {@link #TRIVIA} asks for a fact about an accepted song from the model's own knowledge, with no search: a made-up fact would reach
 * the guest as the DJ's words, so its block says to fall back to a plain comment when the model is not sure.
 */
public enum CommentStyle {
    CLASSIC,
    FUNNY,
    SARCASTIC,
    TRIVIA,
    SHORT
}
