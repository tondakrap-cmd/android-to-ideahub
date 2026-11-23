package cz.ideamirror.app;

import java.io.IOException;
import java.util.regex.*;

/**
 * Strict, bounded parser of the board's QR / manual code. It accepts numeric IPv4 addresses only; no DNS lookups.
 * Since 0.7 the "key" is a short-lived pairing token (boards 0.6 and older put their shared secret there).
 */
class PairCode {
    final String host, fp, secret;
    final int port;
    private static final Pattern FORMAT = Pattern.compile("tabulelink://([0-9.]{7,15}):([0-9]{4,5})\\?fp=([a-f0-9]{64})&key=([a-f0-9]{32})");

    PairCode(String text) throws IOException {
        if (text == null || text.length() > 256) throw invalid();
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) throw invalid();
        host = m.group(1);
        port = Integer.parseInt(m.group(2));
        fp = m.group(3);
        secret = m.group(4);
        if (port < 1024 || port > 65535) throw invalid();
        if (!isValidHost(host)) throw invalid();
    }

    static boolean isValidHost(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0"))) return false;
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) < '0' || part.charAt(i) > '9') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        int first = Integer.parseInt(parts[0]);
        return !(first == 0 || first == 127 || first >= 224 || host.equals("255.255.255.255"));
    }

    static String build(String host, int port, String fp, String key) {
        return "tabulelink://" + host + ":" + port + "?fp=" + fp + "&key=" + key;
    }

    private static IOException invalid() { return new IOException("Neplatný párovací kód. Načtěte QR kód z aplikace na tabuli."); }
}
