package cz.ideamirror.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update check against GitHub Releases (pure Java, covered by test.py). Only APK assets of this repository are
 * accepted; Android itself refuses an update that is not signed with the same release key as the installed app.
 */
final class UpdateCheck {
    static final String REPO = "tondakrap-cmd/android-to-ideahub";
    static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    static final long MAX_ASSET_BYTES = 200L * 1024 * 1024;
    /** Release asset of the Android app, e.g. Tabule-Link-0.9.0.apk (CI and working builds don't match). */
    static final Pattern APK = Pattern.compile("^Tabule-Link-(\\d+)\\.(\\d+)\\.(\\d+)\\.apk$");

    private UpdateCheck() {}

    static final class Release {
        String version, tag, name, notes, htmlUrl, assetName, assetUrl, sha256;
        long size;
    }

    /** "0.9.0" or "v0.9.0" → {0, 9, 0}; null when it isn't a plain three-part version. */
    static int[] parseVersion(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^[vV]?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$").matcher(v.trim());
        if (!m.matches()) return null;
        return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) };
    }

    /** Negative when a is older than b, 0 when equal; unparsable versions sort as oldest. */
    static int compareVersions(String a, String b) {
        int[] x = parseVersion(a), y = parseVersion(b);
        if (x == null || y == null) return (x == null ? 0 : 1) - (y == null ? 0 : 1);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        return 0;
    }

    /** Newest published (not draft, not prerelease) release with an APK newer than current; null if none. */
    @SuppressWarnings("unchecked")
    static Release findNewer(String releasesJson, String current) {
        Object root = Json.parse(releasesJson);
        if (!(root instanceof List)) throw new IllegalArgumentException("GitHub vrátil neočekávanou odpověď.");
        Release best = null;
        for (Object o : (List<Object>) root) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> r = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(r.get("draft")) || Boolean.TRUE.equals(r.get("prerelease"))) continue;
            Object assets = r.get("assets");
            if (!(assets instanceof List)) continue;
            for (Object ao : (List<Object>) assets) {
                if (!(ao instanceof Map)) continue;
                Map<String, Object> a = (Map<String, Object>) ao;
                String name = str(a, "name");
                if (name == null) continue;
                Matcher m = APK.matcher(name);
                if (!m.matches()) continue;
                String version = m.group(1) + "." + m.group(2) + "." + m.group(3);
                if (compareVersions(version, current) <= 0) continue;
                if (best != null && compareVersions(version, best.version) <= 0) continue;
                String url = str(a, "browser_download_url");
                if (!isOwnDownload(url)) continue;
                Release u = new Release();
                u.version = version;
                u.tag = str(r, "tag_name");
                u.name = str(r, "name") != null ? str(r, "name") : u.tag;
                u.notes = str(r, "body") != null ? str(r, "body") : "";
                u.htmlUrl = str(r, "html_url") != null ? str(r, "html_url") : RELEASES_PAGE;
                u.assetName = name;
                u.assetUrl = url;
                Object size = a.get("size");
                u.size = size instanceof Number ? ((Number) size).longValue() : 0;
                u.sha256 = digestSha256(str(a, "digest"));
                best = u;
            }
        }
        return best;
    }

    /** Download URLs must point to this repository's release assets over HTTPS. */
    static boolean isOwnDownload(String url) {
        return url != null && url.startsWith(DOWNLOAD_PREFIX) && !url.contains("..");
    }

    /** "sha256:ABCD…" → lower-case hex, otherwise null. */
    static String digestSha256(String digest) {
        if (digest == null) return null;
        Matcher m = Pattern.compile("^sha256:([0-9a-fA-F]{64})$").matcher(digest.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    static String hex(byte[] h) {
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Size and GitHub digest of a downloaded APK; null when fine, else a Czech message. */
    static String validateDownload(Release u, long length, String sha256Hex) {
        if (length < 1024) return "Stažený soubor je prázdný nebo poškozený.";
        if (u.size > 0 && length != u.size) return "Stažený soubor má jinou velikost, než uvádí GitHub.";
        if (u.sha256 != null && !u.sha256.equals(sha256Hex)) return "Kontrolní součet SHA-256 staženého souboru nesouhlasí.";
        return null;
    }

    static String shortNotes(String notes, int max) {
        if (notes == null) return "";
        String s = notes.replace("\r\n", "\n").trim();
        return s.length() > max ? s.substring(0, max).trim() + "…" : s;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : null;
    }

    /** Minimal JSON parser (objects → LinkedHashMap, arrays → ArrayList, numbers → Double/Long). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            if (text == null) throw new IllegalArgumentException("Prázdná odpověď.");
            Json j = new Json(text);
            j.ws();
            Object v = j.value(0);
            j.ws();
            if (j.i != j.s.length()) throw j.err("nadbytečná data");
            return v;
        }

        private IllegalArgumentException err(String what) { return new IllegalArgumentException("Neplatný JSON (" + what + " na pozici " + i + ")"); }

        private void ws() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++; }

        private Object value(int depth) {
            if (depth > 64) throw err("příliš hluboké vnoření");
            if (i >= s.length()) throw err("konec dat");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    if (!peek('"')) throw err("očekáván klíč");
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err("neočekávaný znak");
            String num = s.substring(start, i);
            try {
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) return Long.parseLong(num);
                return Double.parseDouble(num);
            } catch (NumberFormatException e) { throw err("číslo"); }
        }

        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        private void expect(char c) { if (!peek(c)) throw err("očekáváno '" + c + "'"); i++; }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("neukončený řetězec");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw err("escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("\\u");
                        try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                        catch (NumberFormatException ex) { throw err("\\u"); }
                        i += 4;
                        break;
                    default: throw err("escape");
                }
            }
        }
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
