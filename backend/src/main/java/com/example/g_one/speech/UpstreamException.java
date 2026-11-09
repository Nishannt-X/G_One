package com.example.g_one.speech;

/** A failed call to Gnani or the translation service. {@code stage} becomes the user-facing error code. */
public class UpstreamException extends RuntimeException {
    private final String stage;
    private final int status;

    public UpstreamException(String stage, String message) {
        this(stage, message, 0);
    }

    /** @param status the HTTP status the service answered with; 0 when it never answered (timeout or network error) */
    public UpstreamException(String stage, String message, int status) {
        super(message);
        this.stage = stage;
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** A failure worth another try: no answer at all, or a server-side error. */
    public boolean retryable() {
        return status == 0 || status >= 500;
    }

    public String stage() {
        return stage;
    }
}
