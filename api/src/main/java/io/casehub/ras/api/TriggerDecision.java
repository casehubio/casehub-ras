package io.casehub.ras.api;

public enum TriggerDecision {
    TRIGGER,
    TRIGGER_AND_CONTINUE,
    CONTINUE_ACCUMULATING,
    DISCARD,
    RESOLVE
}
