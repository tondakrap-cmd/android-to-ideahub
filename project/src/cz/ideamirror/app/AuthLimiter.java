package cz.ideamirror.app;

import java.util.*;

/**
 * Brute-force protection for pairing (PIN / token / key): exponential back-off per client address plus a
 * global limit, so one attacker cannot lock out everybody and cannot enumerate the 6-digit PIN.
 * After {@link #ROTATE_AFTER_FAILURES} failures (any client) the board rotates its PIN.
 */
final class AuthLimiter {
    static final int FREE_FAILURES = 3;
    static final long BASE_LOCK_MS = 15_000L;
    static final long MAX_LOCK_MS = 10 * 60_000L;
    static final int GLOBAL_FAILURES = 20;
    static final long GLOBAL_WINDOW_MS = 10 * 60_000L;
    static final long GLOBAL_LOCK_MS = 60_000L;
    static final int ROTATE_AFTER_FAILURES = 10;
    private static final int MAX_TRACKED = 256;

    private static final class Entry { int failures; long lockedUntil; long lastFailure; }

    private final Map<String, Entry> perKey = new LinkedHashMap<>();
    private final ArrayDeque<Long> globalFailures = new ArrayDeque<>();
    private long globalLockedUntil = 0L;
    private int failuresSinceRotation = 0;

    synchronized long lockedForMs(String key, long now) {
        long until = globalLockedUntil;
        Entry e = perKey.get(key);
        if (e != null) until = Math.max(until, e.lockedUntil);
        return Math.max(0L, until - now);
    }

    /** Records a failed attempt. Returns true when the PIN should be rotated. */
    synchronized boolean recordFailure(String key, long now) {
        Entry e = perKey.get(key);
        if (e == null) {
            if (perKey.size() >= MAX_TRACKED) {
                Iterator<String> it = perKey.keySet().iterator();
                it.next();
                it.remove();
            }
            e = new Entry();
            perKey.put(key, e);
        }
        if (now - e.lastFailure > MAX_LOCK_MS * 3) e.failures = 0;
        e.failures++;
        e.lastFailure = now;
        if (e.failures >= FREE_FAILURES) {
            int exp = Math.min(10, e.failures - FREE_FAILURES);
            e.lockedUntil = now + Math.min(MAX_LOCK_MS, BASE_LOCK_MS << exp);
        }
        globalFailures.addLast(now);
        while (!globalFailures.isEmpty() && now - globalFailures.peekFirst() > GLOBAL_WINDOW_MS) globalFailures.pollFirst();
        if (globalFailures.size() >= GLOBAL_FAILURES) {
            globalLockedUntil = now + GLOBAL_LOCK_MS;
            globalFailures.clear();
        }
        failuresSinceRotation++;
        if (failuresSinceRotation >= ROTATE_AFTER_FAILURES) {
            failuresSinceRotation = 0;
            return true;
        }
        return false;
    }

    synchronized void recordSuccess(String key) {
        perKey.remove(key);
    }

    synchronized void onPinRotated() { failuresSinceRotation = 0; }
}
