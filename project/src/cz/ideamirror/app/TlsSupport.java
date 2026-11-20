package cz.ideamirror.app;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.*;

/** Portable TLS 1.2 helpers (no Android dependencies so they run in portable JDK tests too). */
final class TlsSupport {
    static final class Identity {
        final X509Certificate cert;
        final PrivateKey key;
        final String fp;
        final String source;
        Identity(X509Certificate cert, PrivateKey key, String fp, String source) {
            this.cert = cert; this.key = key; this.fp = fp; this.source = source;
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255));
        return s.toString();
    }

    static String fingerprint(X509Certificate c) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }

    static boolean isEcKeyType(String keyType) {
        if (keyType == null) return false;
        String u = keyType.toUpperCase(Locale.ROOT);
        return u.equals("EC") || u.startsWith("EC_");
    }

    /**
     * KeyManager compatible with both Socket-based and SSLEngine-based Conscrypt/JSSE stacks.
     * Overrides both chooseServerAlias and chooseEngineServerAlias and accepts EC / EC_EC / EC_RSA.
     */
    static X509ExtendedKeyManager serverKeyManager(final String alias, final X509Certificate cert, final PrivateKey key) {
        return new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, Principal[] p) { return null; }
            @Override public String chooseClientAlias(String[] t, Principal[] p, Socket s) { return null; }
            @Override public String chooseEngineClientAlias(String[] t, Principal[] p, SSLEngine e) { return null; }
            @Override public String[] getServerAliases(String t, Principal[] p) {
                return isEcKeyType(t) ? new String[]{alias} : null;
            }
            @Override public String chooseServerAlias(String t, Principal[] p, Socket s) {
                return isEcKeyType(t) ? alias : null;
            }
            @Override public String chooseEngineServerAlias(String t, Principal[] p, SSLEngine e) {
                return isEcKeyType(t) ? alias : null;
            }
            @Override public X509Certificate[] getCertificateChain(String a) {
                return alias.equals(a) ? new X509Certificate[]{cert} : null;
            }
            @Override public PrivateKey getPrivateKey(String a) {
                return alias.equals(a) ? key : null;
            }
        };
    }

    static X509TrustManager pinningTrustManager(final String expectedFp) {
        final byte[] expected = expectedFp.getBytes(StandardCharsets.US_ASCII);
        return new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] c, String t) throws CertificateException {
                throw new CertificateException("Nepodporováno");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try {
                    if (chain == null || chain.length < 1) {
                        throw new CertificateException("Tabule neposlala certifikát.");
                    }
                    byte[] actual = fingerprint(chain[0]).getBytes(StandardCharsets.US_ASCII);
                    if (!MessageDigest.isEqual(actual, expected)) {
                        throw new CertificateException("Identita tabule neodpovídá QR kódu.");
                    }
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
            }
        };
    }

    /**
     * Verifies that the private key can actually perform both SHA256withECDSA and NONEwithECDSA
     * (Conscrypt/BoringSSL uses NONEwithECDSA during TLS 1.2 handshakes).
     */
    static void verifySigningKey(PrivateKey key, X509Certificate cert) throws Exception {
        byte[] sample = "tabule-link-tls-check".getBytes(StandardCharsets.US_ASCII);
        Signature s1 = Signature.getInstance("SHA256withECDSA");
        s1.initSign(key);
        s1.update(sample);
        byte[] sig1 = s1.sign();
        Signature v1 = Signature.getInstance("SHA256withECDSA");
        v1.initVerify(cert.getPublicKey());
        v1.update(sample);
        if (!v1.verify(sig1)) throw new SignatureException("Ověření SHA256withECDSA selhalo.");

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(sample);
        Signature s2 = Signature.getInstance("NONEwithECDSA");
        s2.initSign(key);
        s2.update(digest);
        byte[] sig2 = s2.sign();
        Signature v2 = Signature.getInstance("NONEwithECDSA");
        v2.initVerify(cert.getPublicKey());
        v2.update(digest);
        if (!v2.verify(sig2)) throw new SignatureException("Ověření NONEwithECDSA selhalo.");
    }

    /** Pure-Java fallback generating a secp256r1 key pair and valid RFC 5280 self-signed X.509v3 cert. */
    static Identity generateSelfSignedEcIdentity(SecureRandom random, String source) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair kp = kpg.generateKeyPair();

        BigInteger serial = new BigInteger(63, random).add(BigInteger.ONE);
        byte[] sigAlg = derSeq(derOid(1, 2, 840, 10045, 4, 3, 2)); // ecdsa-with-SHA256 (no NULL per RFC 5758)
        byte[] name = derSeq(derSet(derSeq(derOid(2, 5, 4, 3), derUtf8("Tabule Link"))));
        long now = System.currentTimeMillis();
        byte[] validity = derSeq(derTime(new Date(now - 86400000L)), derTime(new Date(now + 365L * 86400000L)));
        byte[] spki = kp.getPublic().getEncoded();

        byte[] basicConstraints = derSeq(derOid(2, 5, 29, 19), derBool(true), derOctet(derSeq()));
        byte[] keyUsage = derSeq(derOid(2, 5, 29, 15), derBool(true), derOctet(derBitString(new byte[]{(byte) 0x80}, 7)));
        byte[] extKeyUsage = derSeq(derOid(2, 5, 29, 37), derOctet(derSeq(derOid(1, 3, 6, 1, 5, 5, 7, 3, 1))));
        byte[] extensions = derExplicit(3, derSeq(basicConstraints, keyUsage, extKeyUsage));

        byte[] tbs = derSeq(
            derExplicit(0, derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            sigAlg,
            name,
            validity,
            name,
            spki,
            extensions
        );

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(kp.getPrivate(), random);
        signer.update(tbs);
        byte[] sigBytes = signer.sign();

        byte[] certDer = derSeq(tbs, sigAlg, derBitString(sigBytes, 0));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(certDer));
        cert.verify(kp.getPublic());
        return new Identity(cert, kp.getPrivate(), fingerprint(cert), source);
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81); out.write(len);
        } else if (len < 65536) {
            out.write(0x82); out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        } else {
            out.write(0x83); out.write((len >> 16) & 0xFF); out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        }
        out.write(content, 0, len);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] res = new byte[total];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, res, pos, p.length); pos += p.length; }
        return res;
    }

    private static byte[] derSeq(byte[]... parts) { return derTag(0x30, concat(parts)); }
    private static byte[] derSet(byte[]... parts) { return derTag(0x31, concat(parts)); }
    private static byte[] derExplicit(int n, byte[] content) { return derTag(0xA0 | n, content); }
    private static byte[] derInteger(BigInteger v) { return derTag(0x02, v.toByteArray()); }
    private static byte[] derBool(boolean v) { return derTag(0x01, new byte[]{(byte) (v ? 0xFF : 0x00)}); }
    private static byte[] derOctet(byte[] data) { return derTag(0x04, data); }
    private static byte[] derUtf8(String s) { return derTag(0x0C, s.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] derBitString(byte[] bytes, int unusedBits) {
        byte[] content = new byte[bytes.length + 1];
        content[0] = (byte) unusedBits;
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return derTag(0x03, content);
    }

    private static byte[] derOid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7F);
            while ((v >>>= 7) != 0) tmp[n++] = (byte) ((v & 0x7F) | 0x80);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j]);
        }
        return derTag(0x06, out.toByteArray());
    }

    private static byte[] derTime(Date d) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        cal.setTime(d);
        int year = cal.get(Calendar.YEAR);
        if (year >= 1950 && year < 2050) {
            SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return derTag(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return derTag(0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }
}
