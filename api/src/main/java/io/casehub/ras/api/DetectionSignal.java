package io.casehub.ras.api;

public enum DetectionSignal {
    NOISE,
    ANTI,
    WEAK,
    DETECTED;

    public boolean isAtLeast(DetectionSignal threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
