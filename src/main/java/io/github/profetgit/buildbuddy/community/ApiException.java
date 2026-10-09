package io.github.profetgit.buildbuddy.community;

/** Why talking to the community site failed, with the words to show the player. */
public final class ApiException extends Exception {
    public enum Kind {
        /** No connection, or the address does not resolve. */
        OFFLINE,
        TIMEOUT,
        RATE_LIMITED,
        NOT_FOUND,
        /** A 5xx answer. */
        SERVER,
        /** The site said our request was wrong (4xx other than 404/429). */
        BAD_REQUEST,
        /** An answer that is not what the API promises. */
        BAD_RESPONSE,
        /** The API version is newer than this mod understands. */
        TOO_NEW,
        TOO_BIG,
        /** The site answered with a redirect; the address in Settings should be the final one. */
        REDIRECT,
        /** The file that arrived is not the file that was announced. */
        CHECKSUM,
        /** The file could not be written. */
        DISK,
        /** The file is not a schematic this mod can open. */
        UNREADABLE,
        CANCELLED
    }

    public final Kind kind;
    public final int status;
    /** For RATE_LIMITED: the seconds the site asked us to wait. */
    public final int retryAfterSeconds;

    public ApiException(Kind kind, String message) {
        this(kind, 0, 0, message, null);
    }

    public ApiException(Kind kind, int status, int retryAfterSeconds, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** One short sentence for the screen. */
    public String forPlayer() {
        return switch (kind) {
            case OFFLINE -> "Cannot reach the community site. Check your internet connection.";
            case TIMEOUT -> "The community site is not answering.";
            case RATE_LIMITED -> "Too many requests. Try again in " + Math.max(1, retryAfterSeconds) + " s.";
            case NOT_FOUND -> "That build is not on the site any more.";
            case SERVER -> "The community site had a problem (error " + status + "). Try again later.";
            case BAD_REQUEST -> "The community site did not accept that request.";
            case BAD_RESPONSE -> "The community site sent something this version cannot read.";
            case TOO_NEW -> "The community site has moved on. Update Build Buddy to use it.";
            case TOO_BIG -> "The answer was bigger than expected, so it was dropped.";
            case REDIRECT -> "The community site has moved. Set its new address in Settings.";
            case CHECKSUM -> "The file did not arrive intact. Try again.";
            case DISK -> "Could not save the file: " + getMessage();
            case UNREADABLE -> "That file is not a blueprint Build Buddy can open: " + getMessage();
            case CANCELLED -> "Cancelled.";
        };
    }

    /** Whether trying again later can help (shown as a Retry button). */
    public boolean retryable() {
        return switch (kind) {
            case OFFLINE, TIMEOUT, RATE_LIMITED, SERVER, CHECKSUM, DISK -> true;
            default -> false;
        };
    }
}
