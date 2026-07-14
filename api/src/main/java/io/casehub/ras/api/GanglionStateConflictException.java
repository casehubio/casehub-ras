package io.casehub.ras.api;

public class GanglionStateConflictException extends RuntimeException {
    public GanglionStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
