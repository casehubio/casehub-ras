package io.casehub.ras.drools;

public class DroolsSessionStoreException extends RuntimeException {

    public DroolsSessionStoreException(String message) {
        super(message);
    }

    public DroolsSessionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
