package cz.ideamirror.app;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * One-time pairing credentials of the board (QR token and 6-digit PIN). Validation and rotation happen under one
 * lock, so two connections racing with the same token or PIN can never both pair. Pure Java (portable tests).
 */
final class PairingCredentials {
    /** Persistent storage (SharedPreferences on Android, memory in tests). */
    interface Store {
        String loadToken();
        void saveToken(String token);
        String loadPin();
        void savePin(String pin);
    }

    /** PIN as seen when a PIN pairing started; it can be consumed only if no rotation happened since. */
    static final class PinTicket {
        final String pin;
        final long generation;
        PinTicket(String pin, long generation) { this.pin = pin; this.generation = generation; }
    }

    private final Store store;
    private final SecureRandom random;
    private long pinGeneration = 0;

    PairingCredentials(Store store, SecureRandom random) {
        this.store = store;
        this.random = random;
    }

    synchronized String token() {
        String t = store.loadToken();
        return Proto.isHex(t, 32) ? t : rotateToken();
    }

    synchronized String rotateToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String t = TlsSupport.hex(b);
        store.saveToken(t);
        return t;
    }

    synchronized String pin() {
        String p = store.loadPin();
        return p != null && p.matches("[0-9]{6}") ? p : rotatePin();
    }

    synchronized String rotatePin() {
        String p = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        store.savePin(p);
        pinGeneration++;
        return p;
    }

    /** Atomically checks the QR token and, if it matches, replaces it. At most one caller can succeed per token. */
    synchronized boolean consumeToken(String candidate) {
        if (!Proto.constEq(candidate, token())) return false;
        rotateToken();
        return true;
    }

    synchronized PinTicket pinTicket() {
        String p = pin();
        return new PinTicket(p, pinGeneration);
    }

    /**
     * Consumes the PIN of a successful PIN pairing: succeeds only if the PIN was not rotated (by another pairing,
     * brute-force protection or the administrator) since the ticket was taken, and rotates it.
     */
    synchronized boolean consumePin(PinTicket ticket) {
        if (ticket == null || ticket.generation != pinGeneration) return false;
        rotatePin();
        return true;
    }
}
