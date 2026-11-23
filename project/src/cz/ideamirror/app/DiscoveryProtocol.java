package cz.ideamirror.app;

import java.util.Locale;

/** Pure-Java LAN discovery message builder/parser and 6-digit PIN helpers (tested in CoreTest). */
final class DiscoveryProtocol {
    static final int DISCOVERY_PORT = 42522;
    static final String REQUEST = "TABULELINK_DISCOVER_V1";
    static final String OFFER_PREFIX = "TABULELINK_OFFER_V1\t";

    static final class Offer {
        final String name;
        final int port;
        final String fp;
        final String ip;
        Offer(String name, int port, String fp, String ip) {
            this.name = name; this.port = port; this.fp = fp; this.ip = ip;
        }
    }

    static String sanitizeName(String raw) {
        if (raw == null) return "Tabule Link";
        String cleaned = raw.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.isEmpty()) return "Tabule Link";
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /** Never includes the pairing secret or PIN — only public identity and LAN address. */
    static String buildOffer(String deviceName, int port, String fp, String ip) {
        return OFFER_PREFIX + sanitizeName(deviceName) + "\t" + port + "\t" + fp + "\t" + (ip == null ? "" : ip);
    }

    static Offer parseOffer(String text) {
        if (text == null || !text.startsWith(OFFER_PREFIX)) return null;
        String[] parts = text.split("\t", -1);
        if (parts.length < 5) return null;
        String name = sanitizeName(parts[1]);
        int port;
        try { port = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return null; }
        if (port < 1024 || port > 65535) return null;
        String fp = parts[3].trim().toLowerCase(Locale.ROOT);
        if (!fp.matches("[a-f0-9]{64}")) return null;
        String ip = parts[4].trim();
        if (!ip.isEmpty() && NetUtil.parseIpv4(ip) < 0) return null;
        return new Offer(name, port, fp, ip);
    }

    static String formatPin(String pin6) {
        if (pin6 == null || pin6.length() != 6) return pin6 == null ? "" : pin6;
        return pin6.substring(0, 3) + " " + pin6.substring(3);
    }

    static String normalizePin(String raw) {
        if (raw == null) return null;
        String digits = raw.replace(" ", "").replace("-", "").trim();
        return digits.matches("[0-9]{6}") ? digits : null;
    }
}
