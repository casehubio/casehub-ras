package io.casehub.ras.api;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public record FeedbackConfig(
        Set<String> noiseLabels,
        Set<String> confirmedLabels,
        Duration suppressionCooldown,
        double learningRate,
        Duration retentionPeriod,
        boolean tuningEnabled,
        double overSensitiveThreshold,
        double underSensitiveThreshold,
        Duration crossRefWindow
) {

    public FeedbackConfig(Set<String> noiseLabels, Set<String> confirmedLabels,
                          Duration suppressionCooldown, double learningRate,
                          Duration retentionPeriod, boolean tuningEnabled) {
        this(noiseLabels, confirmedLabels, suppressionCooldown, learningRate,
             retentionPeriod, tuningEnabled, 0.5, 0.5, Duration.ofHours(1));
    }

    public FeedbackConfig {
        Objects.requireNonNull(noiseLabels, "noiseLabels");
        Objects.requireNonNull(confirmedLabels, "confirmedLabels");
        Objects.requireNonNull(suppressionCooldown, "suppressionCooldown");
        Objects.requireNonNull(retentionPeriod, "retentionPeriod");
        Objects.requireNonNull(crossRefWindow, "crossRefWindow");
        noiseLabels = Set.copyOf(noiseLabels);
        confirmedLabels = Set.copyOf(confirmedLabels);
        if (!Collections.disjoint(noiseLabels, confirmedLabels)) {
            throw new IllegalArgumentException(
                    "noiseLabels and confirmedLabels must be disjoint");
        }
        if (suppressionCooldown.isZero() || suppressionCooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "suppressionCooldown must be positive, got: " + suppressionCooldown);
        }
        if (learningRate <= 0.0 || learningRate > 1.0) {
            throw new IllegalArgumentException(
                    "learningRate must be in (0.0, 1.0], got: " + learningRate);
        }
        if (retentionPeriod.isZero() || retentionPeriod.isNegative()) {
            throw new IllegalArgumentException(
                    "retentionPeriod must be positive, got: " + retentionPeriod);
        }
        if (retentionPeriod.compareTo(suppressionCooldown) < 0) {
            throw new IllegalArgumentException(
                    "retentionPeriod must be >= suppressionCooldown: "
                            + retentionPeriod + " < " + suppressionCooldown);
        }
        if (overSensitiveThreshold <= 0.0 || overSensitiveThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "overSensitiveThreshold must be in (0.0, 1.0], got: " + overSensitiveThreshold);
        }
        if (underSensitiveThreshold <= 0.0 || underSensitiveThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "underSensitiveThreshold must be in (0.0, 1.0], got: " + underSensitiveThreshold);
        }
        if (crossRefWindow.isZero() || crossRefWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "crossRefWindow must be positive, got: " + crossRefWindow);
        }
    }

    public OutcomeClassification classify(String outcomeLabel) {
        if (noiseLabels.contains(outcomeLabel)) return OutcomeClassification.NOISE;
        if (confirmedLabels.contains(outcomeLabel)) return OutcomeClassification.CONFIRMED;
        return OutcomeClassification.NEUTRAL;
    }
}
