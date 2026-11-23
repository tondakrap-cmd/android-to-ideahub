package cz.ideamirror.app;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Presenter-side list of paired boards. Boards are identified by their TLS fingerprint, so when the board
 * gets a new DHCP address the client finds it again via LAN discovery and just updates host/port.
 * Pure Java (portable tests).
 */
final class BoardStore {
    static final class Board {
        String name;
        final String fp;
        String host;
        int port;
        String deviceId;     // v2 per-device credentials
        String deviceKey;
        String legacySecret; // v1 shared secret (boards running 0.6)
        long lastUsed;

        Board(String name, String fp, String host, int port) {
            this.name = Proto.sanitize(name, 64, "Tabule");
            this.fp = fp;
            this.host = host;
            this.port = port;
        }

        boolean hasV2Key() { return deviceId != null && deviceKey != null; }
        boolean hasAnyKey() { return hasV2Key() || legacySecret != null; }
        String address() { return host + ":" + port; }

        Board copy() {
            Board b = new Board(name, fp, host, port);
            b.deviceId = deviceId; b.deviceKey = deviceKey; b.legacySecret = legacySecret; b.lastUsed = lastUsed;
            return b;
        }
    }

    private final List<Board> boards = new ArrayList<>();

    synchronized List<Board> list() {
        List<Board> copy = new ArrayList<>();
        for (Board b : boards) copy.add(b.copy());
        Collections.sort(copy, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return copy;
    }

    synchronized Board find(String fp) {
        for (Board b : boards) if (b.fp.equals(fp)) return b.copy();
        return null;
    }

    synchronized void upsert(Board b) {
        for (int i = 0; i < boards.size(); i++) {
            if (boards.get(i).fp.equals(b.fp)) { boards.set(i, b.copy()); return; }
        }
        boards.add(b.copy());
    }

    synchronized boolean remove(String fp) {
        for (Iterator<Board> it = boards.iterator(); it.hasNext();) {
            if (it.next().fp.equals(fp)) { it.remove(); return true; }
        }
        return false;
    }

    synchronized int size() { return boards.size(); }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static String opt(String s) { return s == null ? "-" : s; }
    private static String unopt(String s) { return "-".equals(s) || s.isEmpty() ? null : s; }

    synchronized String encode() {
        StringBuilder sb = new StringBuilder();
        for (Board b : boards) {
            sb.append("B1\t").append(b64(b.name)).append('\t').append(b.fp).append('\t').append(b.host).append('\t').append(b.port)
                .append('\t').append(opt(b.deviceId)).append('\t').append(opt(b.deviceKey)).append('\t').append(opt(b.legacySecret))
                .append('\t').append(b.lastUsed).append('\n');
        }
        return sb.toString();
    }

    static BoardStore decode(String text) {
        BoardStore s = new BoardStore();
        if (text == null) return s;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length != 9 || !"B1".equals(p[0]) || !Proto.isHex(p[2], 64) || !PairCode.isValidHost(p[3])) continue;
            try {
                int port = Integer.parseInt(p[4]);
                if (port < 1024 || port > 65535) continue;
                Board b = new Board(unb64(p[1]), p[2], p[3], port);
                b.deviceId = unopt(p[5]);
                b.deviceKey = unopt(p[6]);
                b.legacySecret = unopt(p[7]);
                if (b.deviceId != null && !Proto.isHex(b.deviceId, 16)) b.deviceId = null;
                if (b.deviceKey != null && !Proto.isHex(b.deviceKey, 32)) b.deviceKey = null;
                if (b.legacySecret != null && !Proto.isHex(b.legacySecret, 32)) b.legacySecret = null;
                b.lastUsed = Long.parseLong(p[8]);
                s.upsert(b);
            } catch (Exception ignored) {}
        }
        return s;
    }
}
