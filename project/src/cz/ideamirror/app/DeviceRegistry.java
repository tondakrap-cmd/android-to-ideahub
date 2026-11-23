package cz.ideamirror.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * Board-side list of paired presenter devices. Every device gets its own random 128-bit key; the board
 * stores only its SHA-256 hash, so devices can be revoked individually. Pure Java (portable tests).
 */
final class DeviceRegistry {
    static final int MAX_DEVICES = 200;

    static final class Device {
        final String id;
        String name;
        final String keyHash;
        final long created;
        long lastSeen;
        Device(String id, String name, String keyHash, long created, long lastSeen) {
            this.id = id; this.name = name; this.keyHash = keyHash; this.created = created; this.lastSeen = lastSeen;
        }
    }

    private final List<Device> devices = new ArrayList<>();

    static String hashKey(String key) {
        return TlsSupport.hex(Spake2.sha256(("TabuleLink device key|" + key).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String randomHex(SecureRandom r, int bytes) {
        byte[] b = new byte[bytes];
        r.nextBytes(b);
        return TlsSupport.hex(b);
    }

    synchronized Device issue(String name, SecureRandom random, long now, String[] outKey) {
        String id;
        do { id = randomHex(random, 8); } while (findLocked(id) != null);
        String key = randomHex(random, 16);
        Device d = new Device(id, Proto.deviceName(name), hashKey(key), now, now);
        devices.add(d);
        while (devices.size() > MAX_DEVICES) {
            Device oldest = devices.get(0);
            for (Device x : devices) if (x.lastSeen < oldest.lastSeen) oldest = x;
            devices.remove(oldest);
        }
        outKey[0] = key;
        return d;
    }

    /** Constant-time key check; updates name and last-seen time on success. */
    synchronized Device verify(String id, String key, String name, long now) {
        Device d = findLocked(id);
        String actual = hashKey(key == null ? "" : key);
        String expected = d == null ? hashKey("-missing-") : d.keyHash;
        boolean ok = MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
        if (d == null || !ok) return null;
        d.lastSeen = now;
        if (name != null && !name.isEmpty()) d.name = Proto.deviceName(name);
        return d;
    }

    synchronized boolean revoke(String id) {
        Device d = findLocked(id);
        return d != null && devices.remove(d);
    }

    synchronized void clear() { devices.clear(); }

    synchronized int size() { return devices.size(); }

    synchronized boolean contains(String id) { return findLocked(id) != null; }

    /** Snapshot sorted by last use (newest first). */
    synchronized List<Device> list() {
        List<Device> copy = new ArrayList<>();
        for (Device d : devices) copy.add(new Device(d.id, d.name, d.keyHash, d.created, d.lastSeen));
        Collections.sort(copy, (a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return copy;
    }

    private Device findLocked(String id) {
        if (id == null) return null;
        for (Device d : devices) if (d.id.equals(id)) return d;
        return null;
    }

    synchronized String encode() {
        StringBuilder sb = new StringBuilder();
        for (Device d : devices) {
            sb.append(d.id).append('\t').append(d.keyHash).append('\t').append(d.created).append('\t').append(d.lastSeen)
                .append('\t').append(Base64.getUrlEncoder().withoutPadding().encodeToString(d.name.getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        }
        return sb.toString();
    }

    static DeviceRegistry decode(String text) {
        DeviceRegistry r = new DeviceRegistry();
        if (text == null) return r;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length != 5 || !Proto.isHex(p[0], 16) || !Proto.isHex(p[1], 64)) continue;
            try {
                String name = new String(Base64.getUrlDecoder().decode(p[4]), StandardCharsets.UTF_8);
                r.devices.add(new Device(p[0], Proto.deviceName(name), p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
            } catch (Exception ignored) {}
        }
        return r;
    }
}
