package cz.ideamirror.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SPAKE2 password-authenticated key exchange over the RFC 3526 2048-bit MODP group (prime-order
 * subgroup of a safe prime). Lets a client prove knowledge of the board's 6-digit PIN without
 * revealing it, and binds the board's TLS certificate fingerprint into the key confirmation, so a
 * fake board on the LAN gets at most one online guess and cannot relay the pairing.
 *
 * Pure Java (portable tests). The Windows client implements the identical algorithm (Proto2.cs);
 * a shared test vector in CoreTest / WindowsTests keeps both sides byte-for-byte compatible.
 */
final class Spake2 {
    static final String P_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
        + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
        + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
        + "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
        + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
        + "15728E5A8AACAA68FFFFFFFFFFFFFFFF";
    static final BigInteger P = new BigInteger(P_HEX, 16);
    static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    static final BigInteger G = BigInteger.valueOf(2);
    static final int ELEMENT_BYTES = 256;
    static final BigInteger M = hashToGroup("TabuleLink SPAKE2 M v1");
    static final BigInteger N = hashToGroup("TabuleLink SPAKE2 N v1");

    static final class Keys {
        final byte[] sessionKey;
        final String macServer;
        final String macClient;
        Keys(byte[] sessionKey, String macServer, String macClient) {
            this.sessionKey = sessionKey; this.macServer = macServer; this.macClient = macClient;
        }
    }

    private Spake2() {}

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deterministic hash-to-group: 256 bytes of counter-mode SHA-256, reduced mod P, squared (lands in the order-Q subgroup). */
    static BigInteger hashToGroup(String label) {
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(ELEMENT_BYTES);
        for (int i = 0; i < ELEMENT_BYTES / 32; i++) {
            byte[] block = sha256(l, new byte[]{(byte) i});
            out.write(block, 0, block.length);
        }
        BigInteger v = new BigInteger(1, out.toByteArray()).mod(P);
        return v.multiply(v).mod(P);
    }

    static BigInteger passwordScalar(String pin) {
        byte[] h = sha256(("TabuleLink SPAKE2 w v1|" + pin).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, h).mod(Q);
    }

    static BigInteger randomScalar(SecureRandom random) {
        BigInteger x;
        do { x = new BigInteger(256, random).mod(Q); } while (x.signum() == 0);
        return x;
    }

    static boolean isValidElement(BigInteger e) {
        return e != null && e.compareTo(BigInteger.ONE) > 0 && e.compareTo(P.subtract(BigInteger.ONE)) < 0
            && e.modPow(Q, P).equals(BigInteger.ONE);
    }

    static byte[] toBytes(BigInteger e) {
        byte[] raw = e.toByteArray();
        byte[] out = new byte[ELEMENT_BYTES];
        int copy = Math.min(raw.length, ELEMENT_BYTES);
        System.arraycopy(raw, raw.length - copy, out, ELEMENT_BYTES - copy, copy);
        return out;
    }

    static BigInteger fromHex(String hex) throws IOException {
        if (hex == null || hex.length() != ELEMENT_BYTES * 2 || !hex.matches("[0-9a-f]+")) {
            throw new IOException("Neplatná zpráva párování.");
        }
        BigInteger v = new BigInteger(hex, 16);
        if (!isValidElement(v)) throw new IOException("Neplatná zpráva párování.");
        return v;
    }

    static String toHex(BigInteger e) { return TlsSupport.hex(toBytes(e)); }

    private static String hmacHex(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return TlsSupport.hex(mac.doFinal(label.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Keys deriveKeys(String fp, BigInteger x, BigInteger y, BigInteger k) {
        byte[] fpBytes = fp.getBytes(StandardCharsets.US_ASCII);
        byte[] len = new byte[]{(byte) (fpBytes.length >>> 24), (byte) (fpBytes.length >>> 16), (byte) (fpBytes.length >>> 8), (byte) fpBytes.length};
        byte[] sk = sha256("TL2-SPAKE2".getBytes(StandardCharsets.US_ASCII), len, fpBytes, toBytes(x), toBytes(y), toBytes(k));
        return new Keys(sk, hmacHex(sk, "server-confirm"), hmacHex(sk, "client-confirm"));
    }

    static boolean macEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    /** Client (presenter) side. */
    static final class Client {
        private final BigInteger x, w;
        final BigInteger X;
        Client(String pin, SecureRandom random) { this(pin, randomScalar(random)); }
        Client(String pin, BigInteger x) {
            this.x = x;
            this.w = passwordScalar(pin);
            this.X = G.modPow(x, P).multiply(M.modPow(w, P)).mod(P);
        }
        String messageHex() { return toHex(X); }
        Keys finish(String yHex, String fp) throws IOException {
            BigInteger Y = fromHex(yHex);
            BigInteger nInvW = N.modPow(Q.subtract(w), P);
            BigInteger k = Y.multiply(nInvW).mod(P).modPow(x, P);
            return deriveKeys(fp, X, Y, k);
        }
    }

    /** Server (board) side. */
    static final class Server {
        private final BigInteger y, w;
        final BigInteger Y;
        Server(String pin, SecureRandom random) { this(pin, randomScalar(random)); }
        Server(String pin, BigInteger y) {
            this.y = y;
            this.w = passwordScalar(pin);
            this.Y = G.modPow(y, P).multiply(N.modPow(w, P)).mod(P);
        }
        String messageHex() { return toHex(Y); }
        Keys respond(String xHex, String fp) throws IOException {
            BigInteger X = fromHex(xHex);
            BigInteger mInvW = M.modPow(Q.subtract(w), P);
            BigInteger k = X.multiply(mInvW).mod(P).modPow(y, P);
            return deriveKeys(fp, X, Y, k);
        }
    }
}
