package io.github.profetgit.buildbuddy.blueprint;

/** A schematic that cannot be read; the message is written for the player. */
public final class LitematicException extends RuntimeException {
    public LitematicException(String message) {
        super(message);
    }

    public LitematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
