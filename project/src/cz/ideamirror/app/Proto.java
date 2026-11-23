package cz.ideamirror.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * Tabule Link wire protocol v2 (0.7.0+). Pure Java so the whole handshake is covered by portable tests.
 *
 * <pre>
 * client → writeUTF("TL2|METHOD|deviceId|credential|deviceName|caps")       METHOD = KEY | TOKEN | PAKE
 * server → int MAGIC_V2, writeUTF("OK|deviceId|deviceKey|boardName|caps")   (or "ERR|code|message")
 *          PAKE: int MAGIC_V2, writeUTF("PAKE|Y|macServer"); client → writeUTF(macClient | "ABORT");
 *                server → int MAGIC_V2, writeUTF("OK|…" | "ERR|…")
 * client → int MAGIC, int width, int height                                  (video header, same as v1)
 * server → int MAGIC_WAIT* (waiting for approval on the board), then int MAGIC_GO
 *          or int MAGIC_ERR + writeUTF("ERR|code|message")
 * then 16-byte framed packets both ways; FLAG_CONTROL packets carry short UTF-8 commands.
 * </pre>
 * A bare 32-hex hello is the legacy v1 shared secret (0.4–0.6 clients) and is still accepted.
 */
final class Proto {
    static final int MAGIC_V1 = 0x54424c31;
    static final int MAGIC_V2 = 0x54424c33;
    static final int MAGIC_GO = 0x54424c34;
    static final int MAGIC_WAIT = 0x54424c35;
    static final int MAGIC_ERR = 0x54424c36;

    static final String METHOD_KEY = "KEY", METHOD_TOKEN = "TOKEN", METHOD_PAKE = "PAKE";
    static final String CAP_CTL = "ctl", CAP_TOUCH = "touch", CAP_RESUME = "resume", CAP_FMT = "fmt", CAP_PAIR = "pair";

    static final String ERR_AUTH = "auth", ERR_LOCKED = "locked", ERR_BUSY = "busy", ERR_DENIED = "denied", ERR_PROTO = "proto";

    static final String CTL_PAUSE = "pause", CTL_RESUME = "resume", CTL_BYE = "bye", CTL_KEYFRAME = "kf";
    static final String CTL_END_TAKEOVER = "end:takeover", CTL_END_CLOSED = "end:closed", CTL_END_REVOKED = "end:revoked";
    static final String CTL_FMT_PREFIX = "fmt:", CTL_TOUCH_PREFIX = "t:";

    static final int MAX_CONTROL_BYTES = 4096;

    private Proto() {}

    /** Removes separators, control characters, NUL and non-BMP characters (Java modified-UTF-8 vs. .NET UTF-8 safe). */
    static String sanitize(String raw, int max, String fallback) {
        if (raw == null) return fallback;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '|' || c < 0x20 || c == 0x7f || Character.isSurrogate(c)) { sb.append(' '); continue; }
            sb.append(c);
        }
        String s = sb.toString().trim().replaceAll(" {2,}", " ");
        if (s.isEmpty()) return fallback;
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    static String deviceName(String raw) { return sanitize(raw, 48, "Zařízení"); }

    static boolean isHex(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    static boolean constEq(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static Set<String> parseCaps(String s) {
        Set<String> caps = new LinkedHashSet<>();
        if (s == null) return caps;
        for (String c : s.split(",")) {
            String t = c.trim();
            if (!t.isEmpty() && t.length() <= 16) caps.add(t);
        }
        return caps;
    }

    static String joinCaps(Collection<String> caps) {
        StringBuilder sb = new StringBuilder();
        for (String c : caps) { if (sb.length() > 0) sb.append(','); sb.append(c); }
        return sb.toString();
    }

    // ---------------------------------------------------------------- hello

    static final class Hello {
        final String method, deviceId, credential, deviceName;
        final Set<String> caps;
        Hello(String method, String deviceId, String credential, String deviceName, Set<String> caps) {
            this.method = method;
            this.deviceId = deviceId == null || deviceId.isEmpty() ? "-" : deviceId;
            this.credential = credential;
            this.deviceName = deviceName(deviceName);
            this.caps = caps == null ? new LinkedHashSet<String>() : caps;
        }
        String build() {
            return "TL2|" + method + "|" + deviceId + "|" + credential + "|" + deviceName + "|" + joinCaps(caps);
        }
        boolean has(String cap) { return caps.contains(cap); }

        /** Returns null when the text is not a v2 hello; throws on a malformed v2 hello. */
        static Hello parse(String text) throws IOException {
            if (text == null || !text.startsWith("TL2|")) return null;
            String[] p = text.split("\\|", -1);
            if (p.length != 6) throw new IOException("Neplatná úvodní zpráva klienta.");
            String method = p[1];
            String id = p[2];
            String cred = p[3];
            if (!"-".equals(id) && !isHex(id, 16)) throw new IOException("Neplatné ID zařízení.");
            if (METHOD_KEY.equals(method)) {
                if (!isHex(id, 16) || !isHex(cred, 32)) throw new IOException("Neplatný klíč zařízení.");
            } else if (METHOD_TOKEN.equals(method)) {
                if (!isHex(cred, 32)) throw new IOException("Neplatný párovací token.");
            } else if (METHOD_PAKE.equals(method)) {
                if (!isHex(cred, Spake2.ELEMENT_BYTES * 2)) throw new IOException("Neplatná zpráva PIN párování.");
            } else {
                throw new IOException("Neznámá metoda ověření.");
            }
            return new Hello(method, id, cred, p[4], parseCaps(p[5]));
        }
    }

    // ---------------------------------------------------------------- replies

    static final class Reply {
        final String kind; // OK, ERR, PAKE
        final String[] f;
        Reply(String kind, String... f) { this.kind = kind; this.f = f; }
        static Reply parse(String text) throws IOException {
            if (text == null) throw new IOException("Prázdná odpověď tabule.");
            String[] p = text.split("\\|", -1);
            if ("OK".equals(p[0]) && p.length == 5) return new Reply("OK", p[1], p[2], p[3], p[4]);
            if ("ERR".equals(p[0]) && p.length >= 3) return new Reply("ERR", p[1], text.substring(p[0].length() + p[1].length() + 2));
            if ("PAKE".equals(p[0]) && p.length == 3) return new Reply("PAKE", p[1], p[2]);
            throw new IOException("Nesrozumitelná odpověď tabule.");
        }
    }

    static String ok(String deviceId, String deviceKey, String boardName, String caps) {
        return "OK|" + deviceId + "|" + (deviceKey == null ? "-" : deviceKey) + "|" + sanitize(boardName, 64, "Tabule Link") + "|" + caps;
    }

    static String err(String code, String message) {
        return "ERR|" + code + "|" + sanitize(message, 300, "Chyba");
    }

    /** Error reported by the board with a machine-readable code (decides whether a client may reconnect). */
    static final class ProtoException extends IOException {
        final String code;
        ProtoException(String code, String message) { super(message); this.code = code; }
    }

    /** The board closed the connection without a v2 reply — it runs Tabule Link 0.6 or older. */
    static final class OldServerException extends IOException {
        OldServerException() { super("Tabule používá starší verzi Tabule Link (0.6 nebo nižší)."); }
    }

    // ---------------------------------------------------------------- server side

    interface ServerContext {
        String fingerprint();
        String boardName();
        String legacySecret();
        /** One-time QR token and PIN; checked and rotated atomically (no double pairing by racing connections). */
        PairingCredentials credentials();
        DeviceRegistry registry();
        AuthLimiter limiter();
        SecureRandom random();
        long nowMs();
        /** QR token or PIN changed (consumed by a pairing) → the board refreshes its screen. */
        void onCredentialsChanged();
        /** The PIN was attacked too often (board rotates it). */
        void onPinRotationNeeded();
        void onRegistryChanged();
    }

    static final class AuthResult {
        final boolean legacy;
        final String deviceId, deviceName;
        final Set<String> caps;
        final boolean paired;
        AuthResult(boolean legacy, String deviceId, String deviceName, Set<String> caps, boolean paired) {
            this.legacy = legacy; this.deviceId = deviceId; this.deviceName = deviceName; this.caps = caps; this.paired = paired;
        }
        boolean has(String cap) { return caps.contains(cap); }
    }

    static final String SERVER_CAPS = "ctl,touch,fmt,pause";

    private static void reply(DataOutputStream out, String text) throws IOException {
        out.writeInt(MAGIC_V2);
        out.writeUTF(text);
        out.flush();
    }

    private static ProtoException refuse(DataOutputStream out, String code, String msg) {
        try { reply(out, err(code, msg)); } catch (IOException ignored) {}
        return new ProtoException(code, msg);
    }

    private static String lockMessage(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        return "Příliš mnoho chybných pokusů. Zkuste to znovu za " + s + " s.";
    }

    /** Authenticates one incoming client (v1 legacy secret or v2). Throws on rejection (after replying). */
    static AuthResult serverAuthenticate(DataInputStream in, DataOutputStream out, ServerContext ctx, String remoteKey) throws IOException {
        String text = in.readUTF();
        AuthLimiter limiter = ctx.limiter();
        long now = ctx.nowMs();

        if (isHex(text, 32)) {
            long lock = limiter.lockedForMs(remoteKey, now);
            if (lock > 0) throw new ProtoException(ERR_LOCKED, lockMessage(lock));
            if (!constEq(text, ctx.legacySecret())) {
                if (limiter.recordFailure(remoteKey, now)) ctx.onPinRotationNeeded();
                throw new ProtoException(ERR_AUTH, "Nesprávný párovací klíč (starý klient).");
            }
            limiter.recordSuccess(remoteKey);
            out.writeInt(MAGIC_V1);
            out.flush();
            return new AuthResult(true, "legacy", "Starší klient (0.6)", new LinkedHashSet<String>(), false);
        }

        Hello h;
        try {
            h = Hello.parse(text);
        } catch (IOException e) {
            throw refuse(out, ERR_PROTO, e.getMessage());
        }
        if (h == null) {
            // e.g. the removed plain "PIN:123456" method of 0.5/0.6 Windows clients
            throw new ProtoException(ERR_PROTO, "Nekompatibilní klient — aktualizujte Tabule Link na 0.7.");
        }

        long lock = limiter.lockedForMs(remoteKey, now);
        if (lock > 0) throw refuse(out, ERR_LOCKED, lockMessage(lock));

        DeviceRegistry reg = ctx.registry();
        if (METHOD_KEY.equals(h.method)) {
            DeviceRegistry.Device d = reg.verify(h.deviceId, h.credential, h.deviceName, now);
            if (d == null) {
                if (limiter.recordFailure(remoteKey, now)) ctx.onPinRotationNeeded();
                throw refuse(out, ERR_AUTH, "Zařízení není na této tabuli spárováno (nebo bylo odvoláno). Spárujte jej znovu QR kódem nebo PINem.");
            }
            limiter.recordSuccess(remoteKey);
            ctx.onRegistryChanged();
            reply(out, ok(d.id, "-", ctx.boardName(), SERVER_CAPS));
            return new AuthResult(false, d.id, d.name, h.caps, false);
        }

        if (METHOD_TOKEN.equals(h.method)) {
            // check + rotate under one lock: of several connections racing with the same token only one pairs
            if (!ctx.credentials().consumeToken(h.credential)) {
                if (limiter.recordFailure(remoteKey, now)) ctx.onPinRotationNeeded();
                throw refuse(out, ERR_AUTH, "Tento QR kód už neplatí. Načtěte aktuální QR kód z tabule.");
            }
            ctx.onCredentialsChanged();
            limiter.recordSuccess(remoteKey);
            String[] key = new String[1];
            DeviceRegistry.Device d = reg.issue(h.deviceName, ctx.random(), now, key);
            ctx.onRegistryChanged();
            reply(out, ok(d.id, key[0], ctx.boardName(), SERVER_CAPS));
            return new AuthResult(false, d.id, d.name, h.caps, true);
        }

        // PAKE (6-digit PIN)
        PairingCredentials.PinTicket ticket = ctx.credentials().pinTicket();
        Spake2.Server s = new Spake2.Server(ticket.pin, ctx.random());
        Spake2.Keys keys;
        try {
            keys = s.respond(h.credential, ctx.fingerprint());
        } catch (IOException e) {
            throw refuse(out, ERR_PROTO, e.getMessage());
        }
        boolean success = false;
        try {
            reply(out, "PAKE|" + s.messageHex() + "|" + keys.macServer);
            String clientMac = in.readUTF();
            if (!Spake2.macEquals(clientMac, keys.macClient)) {
                throw refuse(out, ERR_AUTH, "Nesprávný PIN.");
            }
            success = true;
        } finally {
            if (!success && limiter.recordFailure(remoteKey, ctx.nowMs())) ctx.onPinRotationNeeded();
        }
        limiter.recordSuccess(remoteKey);
        // The PIN was right, but it may have been consumed meanwhile by a concurrent pairing (or rotated):
        // only the first pairing per PIN succeeds.
        if (!ctx.credentials().consumePin(ticket)) {
            throw refuse(out, ERR_AUTH, "Tento PIN už byl mezitím použit. Zadejte nový PIN zobrazený na tabuli.");
        }
        ctx.onCredentialsChanged();
        String[] key = new String[1];
        DeviceRegistry.Device d = reg.issue(h.deviceName, ctx.random(), now, key);
        ctx.onRegistryChanged();
        reply(out, ok(d.id, key[0], ctx.boardName(), SERVER_CAPS));
        return new AuthResult(false, d.id, d.name, h.caps, true);
    }

    static void writeGo(DataOutputStream out) throws IOException { out.writeInt(MAGIC_GO); out.flush(); }
    static void writeWait(DataOutputStream out) throws IOException { out.writeInt(MAGIC_WAIT); out.flush(); }
    static void writeStartError(DataOutputStream out, String code, String msg) throws IOException {
        out.writeInt(MAGIC_ERR);
        out.writeUTF(err(code, msg));
        out.flush();
    }

    // ---------------------------------------------------------------- client side

    static final class ClientResult {
        final String boardName, deviceId, deviceKey;
        final Set<String> serverCaps;
        ClientResult(String boardName, String deviceId, String deviceKey, Set<String> serverCaps) {
            this.boardName = boardName; this.deviceId = deviceId; this.deviceKey = deviceKey; this.serverCaps = serverCaps;
        }
    }

    private static Reply readReply(DataInputStream in) throws IOException {
        int magic;
        try {
            magic = in.readInt();
        } catch (EOFException e) {
            throw new OldServerException();
        }
        if (magic != MAGIC_V2) throw new IOException("Neočekávaná odpověď tabule.");
        return Reply.parse(in.readUTF());
    }

    private static ClientResult toResult(Reply r) throws IOException {
        if ("ERR".equals(r.kind)) throw new ProtoException(r.f[0], r.f[1]);
        if (!"OK".equals(r.kind)) throw new IOException("Neočekávaná odpověď tabule.");
        String id = r.f[0], key = r.f[1];
        if (!isHex(id, 16)) throw new IOException("Tabule vrátila neplatné ID zařízení.");
        if (!"-".equals(key) && !isHex(key, 32)) throw new IOException("Tabule vrátila neplatný klíč zařízení.");
        return new ClientResult(sanitize(r.f[2], 64, "Tabule Link"), id, "-".equals(key) ? null : key, parseCaps(r.f[3]));
    }

    static ClientResult clientHello(DataInputStream in, DataOutputStream out, Hello hello) throws IOException {
        out.writeUTF(hello.build());
        out.flush();
        return toResult(readReply(in));
    }

    static ClientResult clientPairPin(DataInputStream in, DataOutputStream out, String pin, String fp,
                                      String deviceName, Set<String> caps, SecureRandom random) throws IOException {
        Spake2.Client c = new Spake2.Client(pin, random);
        out.writeUTF(new Hello(METHOD_PAKE, "-", c.messageHex(), deviceName, caps).build());
        out.flush();
        Reply r = readReply(in);
        if ("ERR".equals(r.kind)) throw new ProtoException(r.f[0], r.f[1]);
        if (!"PAKE".equals(r.kind)) throw new IOException("Neočekávaná odpověď tabule.");
        Spake2.Keys keys = c.finish(r.f[0], fp);
        if (!Spake2.macEquals(r.f[1], keys.macServer)) {
            try { out.writeUTF("ABORT"); out.flush(); } catch (IOException ignored) {}
            throw new ProtoException(ERR_AUTH, "Nesprávný PIN (nebo se ozvalo podvržené zařízení místo tabule).");
        }
        out.writeUTF(keys.macClient);
        out.flush();
        return toResult(readReply(in));
    }

    static void clientLegacy(DataInputStream in, DataOutputStream out, String secret) throws IOException {
        out.writeUTF(secret);
        out.flush();
        int r;
        try { r = in.readInt(); } catch (EOFException e) { throw new ProtoException(ERR_AUTH, "Tabule odmítla uložený klíč."); }
        if (r != MAGIC_V1) throw new ProtoException(ERR_AUTH, "Tabule odmítla uložený klíč.");
    }

    static void writeHeader(DataOutputStream out, int width, int height) throws IOException {
        out.writeInt(MAGIC_V1);
        out.writeInt(width);
        out.writeInt(height);
        out.flush();
    }

    /** v2 only: waits for MAGIC_GO, reporting MAGIC_WAIT (approval pending on the board). */
    static void awaitGo(DataInputStream in, Runnable onWait) throws IOException {
        while (true) {
            int m = in.readInt();
            if (m == MAGIC_GO) return;
            if (m == MAGIC_WAIT) { if (onWait != null) onWait.run(); continue; }
            if (m == MAGIC_ERR) {
                Reply r = Reply.parse(in.readUTF());
                if ("ERR".equals(r.kind)) throw new ProtoException(r.f[0], r.f[1]);
            }
            throw new IOException("Neočekávaná odpověď tabule při spuštění přenosu.");
        }
    }

    // ---------------------------------------------------------------- control packets

    static Packet control(String cmd) { return new Packet(Packet.FLAG_CONTROL, 0L, cmd.getBytes(StandardCharsets.UTF_8)); }

    static String controlText(Packet p) {
        if (p.data.length > MAX_CONTROL_BYTES) return "";
        return new String(p.data, StandardCharsets.UTF_8);
    }

    static String fmt(int w, int h) { return CTL_FMT_PREFIX + w + ":" + h; }

    /** Returns {w, h} or null. */
    static int[] parseFmt(String cmd) {
        if (cmd == null || !cmd.startsWith(CTL_FMT_PREFIX)) return null;
        String[] p = cmd.split(":");
        if (p.length != 3) return null;
        try {
            int w = Integer.parseInt(p[1]), h = Integer.parseInt(p[2]);
            return validVideoSize(w, h) ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean validVideoSize(int w, int h) {
        return w >= 16 && h >= 16 && w <= 2560 && h <= 2560 && (long) w * h <= 2560L * 1600;
    }

    /** action: 'd' down, 'm' move, 'u' up; x/y normalized 0..65535 over the video frame. */
    static String touch(char action, int x, int y) {
        return CTL_TOUCH_PREFIX + action + ":" + clamp16(x) + ":" + clamp16(y);
    }

    /** Returns {action, x, y} or null. */
    static int[] parseTouch(String cmd) {
        if (cmd == null || !cmd.startsWith(CTL_TOUCH_PREFIX)) return null;
        String[] p = cmd.split(":");
        if (p.length != 4 || p[1].length() != 1 || "dmu".indexOf(p[1].charAt(0)) < 0) return null;
        try {
            int x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]);
            if (x < 0 || x > 65535 || y < 0 || y > 65535) return null;
            return new int[]{p[1].charAt(0), x, y};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int clamp16(int v) { return v < 0 ? 0 : (v > 65535 ? 65535 : v); }
}
