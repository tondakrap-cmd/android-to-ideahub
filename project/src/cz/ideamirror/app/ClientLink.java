package cz.ideamirror.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/** Presenter-side pairing and connection helpers (Android): saved boards, QR/PIN pairing, connect with rediscovery. */
final class ClientLink {
    static final String PREFS = "tabule_client";
    private static final String K_BOARDS = "boards", K_DEVICE_NAME = "device_name";
    private static final String K_OLD_PAIRING = "saved_pairing", K_OLD_HOST = "saved_host";

    private ClientLink() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String deviceName(Context c) {
        String def = Proto.deviceName(Build.MANUFACTURER + " " + Build.MODEL);
        return Proto.deviceName(prefs(c).getString(K_DEVICE_NAME, def));
    }

    static void setDeviceName(Context c, String name) { prefs(c).edit().putString(K_DEVICE_NAME, Proto.deviceName(name)).apply(); }

    /** Loads saved boards; migrates the single 0.6 saved pairing (shared secret) on first run. */
    static synchronized BoardStore load(Context c) {
        SharedPreferences p = prefs(c);
        BoardStore store = BoardStore.decode(p.getString(K_BOARDS, ""));
        String old = p.getString(K_OLD_PAIRING, null);
        if (old != null) {
            try {
                PairCode pc = new PairCode(old);
                if (store.find(pc.fp) == null) {
                    BoardStore.Board b = new BoardStore.Board("Tabule " + pc.host, pc.fp, pc.host, pc.port);
                    b.legacySecret = pc.secret;
                    b.lastUsed = System.currentTimeMillis();
                    store.upsert(b);
                }
            } catch (Exception ignored) {}
            p.edit().putString(K_BOARDS, store.encode()).remove(K_OLD_PAIRING).remove(K_OLD_HOST).apply();
        }
        return store;
    }

    static synchronized void save(Context c, BoardStore.Board b) {
        BoardStore store = load(c);
        store.upsert(b);
        prefs(c).edit().putString(K_BOARDS, store.encode()).apply();
    }

    static synchronized void remove(Context c, String fp) {
        BoardStore store = load(c);
        store.remove(fp);
        prefs(c).edit().putString(K_BOARDS, store.encode()).apply();
    }

    static Set<String> caps(String... extra) {
        Set<String> s = new LinkedHashSet<>();
        s.add(Proto.CAP_CTL);
        s.add(Proto.CAP_FMT);
        s.addAll(Arrays.asList(extra));
        return s;
    }

    private static BoardStore.Board fromResult(String fp, String host, int port, Proto.ClientResult r) {
        BoardStore.Board b = new BoardStore.Board(r.boardName, fp, host, port);
        b.deviceId = r.deviceId;
        b.deviceKey = r.deviceKey;
        b.lastUsed = System.currentTimeMillis();
        return b;
    }

    /** QR / manual code: one-time token (0.7 boards) or the shared secret of 0.6 boards. */
    static BoardStore.Board pairWithCode(Context c, PairCode code, Wire.PhaseListener phase) throws Exception {
        SSLSocket s = Wire.openTls(code.host, code.port, code.fp, 8000, phase);
        try {
            if (phase != null) phase.onPhase("Párování s tabulí");
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Proto.ClientResult r = Proto.clientHello(in, out,
                new Proto.Hello(Proto.METHOD_TOKEN, "-", code.secret, deviceName(c), caps(Proto.CAP_PAIR)));
            BoardStore.Board b = fromResult(code.fp, code.host, code.port, r);
            save(c, b);
            return b;
        } catch (Proto.OldServerException old) {
            Wire.close(s);
            s = Wire.openTls(code.host, code.port, code.fp, 8000, phase);
            Proto.clientLegacy(new DataInputStream(s.getInputStream()), new DataOutputStream(s.getOutputStream()), code.secret);
            BoardStore.Board b = new BoardStore.Board("Tabule " + code.host + " (0.6)", code.fp, code.host, code.port);
            b.legacySecret = code.secret;
            b.lastUsed = System.currentTimeMillis();
            save(c, b);
            return b;
        } finally {
            Wire.close(s);
        }
    }

    /** LAN discovery + 6-digit PIN (SPAKE2, MITM-resistant). */
    static BoardStore.Board pairWithPin(Context c, DiscoveryProtocol.Offer offer, String pin, Wire.PhaseListener phase) throws Exception {
        SSLSocket s = Wire.openTls(offer.ip, offer.port, offer.fp, 8000, phase);
        try {
            if (phase != null) phase.onPhase("Ověřuji PIN");
            Proto.ClientResult r = Proto.clientPairPin(new DataInputStream(new BufferedInputStream(s.getInputStream())),
                new DataOutputStream(s.getOutputStream()), pin, offer.fp, deviceName(c), caps(Proto.CAP_PAIR), Wire.RANDOM);
            BoardStore.Board b = fromResult(offer.fp, offer.ip, offer.port, r);
            if (b.name.equals("Tabule Link")) b.name = offer.name;
            save(c, b);
            return b;
        } catch (Proto.OldServerException old) {
            throw new IOException("Tabule má starší verzi (0.6). Aktualizujte Tabule Link na tabuli, nebo naskenujte její QR kód.");
        } finally {
            Wire.close(s);
        }
    }

    /** An authenticated streaming connection (video header already accepted). */
    static final class Link {
        final SSLSocket sock;
        final DataInputStream in;
        final OutputStream out;
        final boolean v2;
        final String boardName;
        final FrameQueue queue = new FrameQueue();
        volatile String endNotice;
        volatile boolean dead;
        volatile long writingSince = 0;
        Link(SSLSocket sock, DataInputStream in, OutputStream out, boolean v2, String boardName) {
            this.sock = sock; this.in = in; this.out = out; this.v2 = v2; this.boardName = boardName;
        }
        void close() { dead = true; queue.close(); Wire.close(sock); }
    }

    private static boolean isNetworkFailure(Exception e) {
        return (e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof NoRouteToHostException
            || (e instanceof IOException && !(e instanceof SSLException) && !(e instanceof Proto.ProtoException)));
    }

    /**
     * Connects to a saved board. If the board cannot be reached, it is looked up in the LAN by fingerprint and
     * its new address is saved (DHCP change).
     */
    static Link connect(Context c, BoardStore.Board b, boolean resume, int width, int height, Runnable onWait, Wire.PhaseListener phase) throws Exception {
        SSLSocket s;
        try {
            s = Wire.openTls(b.host, b.port, b.fp, resume ? 4000 : 6000, phase);
        } catch (Exception e) {
            if (!isNetworkFailure(e)) throw e;
            if (phase != null) phase.onPhase("Hledám tabuli v síti (mohla změnit adresu)");
            DiscoveryProtocol.Offer o = DiscoveryClient.findByFingerprint(b.fp, 1500);
            if (o == null || (o.ip.equals(b.host) && o.port == b.port)) throw e;
            b.host = o.ip;
            b.port = o.port;
            save(c, b);
            s = Wire.openTls(b.host, b.port, b.fp, 6000, phase);
        }
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 16 * 1024));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            boolean v2;
            String boardName = b.name;
            if (phase != null) phase.onPhase("Ověření zařízení");
            if (b.hasV2Key()) {
                Proto.ClientResult r = Proto.clientHello(in, out, new Proto.Hello(Proto.METHOD_KEY, b.deviceId, b.deviceKey,
                    deviceName(c), resume ? caps(Proto.CAP_RESUME) : caps()));
                v2 = true;
                if (!r.boardName.equals(b.name)) { b.name = r.boardName; boardName = r.boardName; }
            } else if (b.legacySecret != null) {
                Proto.clientLegacy(in, out, b.legacySecret);
                v2 = false;
            } else {
                throw new Proto.ProtoException(Proto.ERR_AUTH, "Tabule není spárována. Naskenujte QR kód nebo zadejte PIN.");
            }
            Proto.writeHeader(out, width, height);
            if (v2) {
                s.setSoTimeout(45000);
                Proto.awaitGo(in, onWait);
            }
            s.setSoTimeout(0);
            b.lastUsed = System.currentTimeMillis();
            save(c, b);
            return new Link(s, in, out, v2, boardName);
        } catch (Exception e) {
            Wire.close(s);
            throw e;
        }
    }
}
