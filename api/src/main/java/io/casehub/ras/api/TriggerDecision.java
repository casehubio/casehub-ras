package io.casehub.ras.api;

public enum TriggerDecision {
    CREATE_CASE,
    CREATE_CASE_AND_CONTINUE,
    CONTINUE_ACCUMULATING,
    DISCARD,
    RESOLVE
}
