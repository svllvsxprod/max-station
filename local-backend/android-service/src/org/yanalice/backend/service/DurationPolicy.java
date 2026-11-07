package org.yanalice.backend.service;

/** Shared diagnostic limit with the existing standalone responder. */
public final class DurationPolicy {
    private DurationPolicy() {}
    public static int requireSeconds(int seconds) {
        if (seconds < 5 || seconds > 1800) throw new IllegalArgumentException("Duration must be 5–1800 seconds");
        return seconds;
    }
}
