package cz.ideamirror.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.*;
import android.security.keystore.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

final class Wire {
    static final int MAGIC = 0x54424c31, MAX_FRAME = Packet.MAX_SIZE;
    static final int DEFAULT_PORT = 42521;
    static final String ALIAS = "tabule-link-tls-v2";
    static final SecureRandom RANDOM = new SecureRandom();

    interface PhaseListener { void onPhase(String phase); }

    static final class Endpoint {
        final String ip;
        final int prefix;
        final String iface;
        final String transport;
        final boolean active;
        final boolean hasDefaultRoute;
        final int rank;

        Endpoint(String ip, int prefix, String iface, String transport, boolean active, boolean hasDefaultRoute) {
            this.ip = ip;
            this.prefix = prefix;
            this.iface = iface == null ? "" : iface;
            this.transport = transport == null ? NetUtil.TRANSPORT_OTHER : transport;
            this.active = active;
            this.hasDefaultRoute = hasDefaultRoute;
            this.rank = NetUtil.rankEndpoint(ip, this.iface, this.transport, active, hasDefaultRoute);
        }

        String label() { return NetUtil.formatEndpoint(ip, prefix, iface, transport, active); }
    }

    static String hex(byte[] b) { return TlsSupport.hex(b); }
    static String token() { byte[] b = new byte[16]; RANDOM.nextBytes(b); return hex(b); }
    static String fingerprint(X509Certificate c) throws Exception { return TlsSupport.fingerprint(c); }

    static final class Pair extends PairCode { Pair(String text) throws IOException { super(text); } }

    static String uri(String host, int port, String fp, String token) {
        return "tabulelink://" + host + ":" + port + "?fp=" + fp + "&key=" + token;
    }

    private static String transportLabel(NetworkCapabilities caps, String iface) {
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return NetUtil.TRANSPORT_VPN;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetUtil.TRANSPORT_ETHERNET;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetUtil.TRANSPORT_WIFI;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return NetUtil.TRANSPORT_CELLULAR;
        }
        return NetUtil.guessTransportFromIface(iface);
    }

    private static void addOrImprove(Map<String, Endpoint> map, Endpoint candidate) {
        Endpoint existing = map.get(candidate.ip);
        if (existing == null || candidate.rank < existing.rank
            || (candidate.rank == existing.rank && candidate.active && !existing.active)) {
            map.put(candidate.ip, candidate);
        }
    }

    @SuppressWarnings("deprecation")
    static List<Endpoint> endpoints(Context context) {
        Map<String, Endpoint> byIp = new LinkedHashMap<>();

        if (context != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNet = cm.getActiveNetwork();
                    List<Network> nets = new ArrayList<>();
                    if (activeNet != null) nets.add(activeNet);
                    Network[] all = cm.getAllNetworks();
                    if (all != null) {
                        for (Network n : all) {
                            if (n != null && !nets.contains(n)) nets.add(n);
                        }
                    }
                    for (Network net : nets) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp == null) continue;
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        String iface = lp.getInterfaceName();
                        String transport = transportLabel(caps, iface);
                        boolean isActive = net.equals(activeNet);
                        boolean hasDefault = false;
                        for (RouteInfo r : lp.getRoutes()) {
                            if (r != null && r.isDefaultRoute()) { hasDefault = true; break; }
                        }
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                                String host = addr.getHostAddress();
                                if (host != null) {
                                    addOrImprove(byIp, new Endpoint(host, la.getPrefixLength(), iface, transport, isActive, hasDefault));
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                String name = n.getName() == null ? "" : n.getName();
                String transport = NetUtil.guessTransportFromIface(name);
                for (InterfaceAddress ia : n.getInterfaceAddresses()) {
                    InetAddress ip = ia.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && !ip.isLinkLocalAddress()) {
                        String host = ip.getHostAddress();
                        if (host != null) {
                            addOrImprove(byIp, new Endpoint(host, ia.getNetworkPrefixLength(), name, transport, false, false));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        List<Endpoint> result = new ArrayList<>(byIp.values());
        Collections.sort(result, (a, b) -> {
            int c = Integer.compare(a.rank, b.rank);
            if (c != 0) return c;
            c = Boolean.compare(!a.active, !b.active);
            return c != 0 ? c : a.ip.compareTo(b.ip);
        });
        return result;
    }

    static String formatEndpointsMultiline(List<Endpoint> eps) {
        if (eps == null || eps.isEmpty()) return "Žádná IPv4 síťová adresa není dostupná.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eps.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("• ").append(eps.get(i).label());
        }
        return sb.toString();
    }

    static String formatEndpointsInline(List<Endpoint> eps) {
        if (eps == null || eps.isEmpty()) return "žádná";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(eps.get(i).label());
        }
        return sb.toString();
    }

    static String subnetMismatchHint(Context context, String targetHost) {
        List<Endpoint> local = endpoints(context);
        if (local.isEmpty()) {
            return "Tablet momentálně nemá žádnou IPv4 síťovou adresu. Zkontrolujte připojení k Wi-Fi.";
        }
        for (Endpoint ep : local) {
            if (NetUtil.sameSubnet(ep.ip, ep.prefix, targetHost)) return null;
        }
        return "Pozor: Adresa tabule " + targetHost + " je z jiného síťového rozsahu než toto zařízení ("
            + formatEndpointsInline(local)
            + "). Na tabuli přepněte síťovou adresu pod QR kódem na rozhraní ve stejné síti, nebo ověřte nastavení Wi-Fi na tabuli.";
    }

    private static byte[] readFileBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeFileBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    }

    private static TlsSupport.Identity loadOrCreateServerIdentity(Context context) throws Exception {
        String keystoreFailure = null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=Tabule Link"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 86400000L))
                    .build());
                gen.generateKeyPair();
            }
            X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
            PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
            if (cert == null || key == null) throw new KeyStoreException("Prázdný záznam v AndroidKeyStore.");
            TlsSupport.verifySigningKey(key, cert);
            return new TlsSupport.Identity(cert, key, fingerprint(cert), "AndroidKeyStore (" + ALIAS + ")");
        } catch (Exception e) {
            keystoreFailure = e.getClass().getSimpleName();
        }

        // Persistent private-storage fallback so saved pairing works even if hardware Keystore is unavailable.
        if (context != null) {
            File certFile = new File(context.getFilesDir(), "tls-fallback-cert.der");
            File keyFile = new File(context.getFilesDir(), "tls-fallback-key.pkcs8");
            try {
                if (certFile.exists() && keyFile.exists()) {
                    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readFileBytes(certFile)));
                    cert.checkValidity();
                    PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(readFileBytes(keyFile)));
                    TlsSupport.verifySigningKey(key, cert);
                    return new TlsSupport.Identity(cert, key, fingerprint(cert), "uložený EC klíč (fallback: " + keystoreFailure + ")");
                }
            } catch (Exception ignored) {}
            TlsSupport.Identity created = TlsSupport.generateSelfSignedEcIdentity(RANDOM, "uložený EC klíč (fallback: " + keystoreFailure + ")");
            try {
                writeFileBytes(certFile, created.cert.getEncoded());
                writeFileBytes(keyFile, created.key.getEncoded());
            } catch (Exception ignored) {}
            return created;
        }
        return TlsSupport.generateSelfSignedEcIdentity(RANDOM, "efemérní EC klíč (fallback: " + keystoreFailure + ")");
    }

    static String loadOrCreateServerSecret(Context context, boolean forceNew) {
        if (context == null) return token();
        SharedPreferences prefs = context.getSharedPreferences("tabule_server", Context.MODE_PRIVATE);
        String existing = prefs.getString("secret", null);
        if (!forceNew && existing != null && existing.matches("[a-f0-9]{32}")) {
            return existing;
        }
        String fresh = token();
        prefs.edit().putString("secret", fresh).apply();
        return fresh;
    }

    static final class Server implements Closeable {
        final SSLServerSocket socket;
        final String fp;
        final String secret;
        final String keySource;

        Server(Context context, boolean forceNewSecret) throws Exception {
            TlsSupport.Identity id = loadOrCreateServerIdentity(context);
            fp = id.fp;
            keySource = id.source;
            secret = loadOrCreateServerSecret(context, forceNewSecret);
            X509ExtendedKeyManager km = TlsSupport.serverKeyManager(ALIAS, id.cert, id.key);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[]{km}, null, RANDOM);
            SSLServerSocketFactory factory = ctx.getServerSocketFactory();
            SSLServerSocket bound;
            try {
                bound = (SSLServerSocket) factory.createServerSocket();
                bound.setReuseAddress(true);
                bound.bind(new InetSocketAddress(DEFAULT_PORT), 2);
            } catch (IOException busy) {
                bound = (SSLServerSocket) factory.createServerSocket(0, 2);
            }
            socket = bound;
            try { socket.setReceiveBufferSize(128 * 1024); } catch (Exception ignored) {}
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
        }

        public void close() throws IOException { socket.close(); }
    }

    static SSLSocket connect(Pair p, PhaseListener listener) throws Exception {
        X509TrustManager tm = TlsSupport.pinningTrustManager(p.fp);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, RANDOM);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        try {
            s.setEnabledProtocols(new String[]{"TLSv1.2"});
            try { s.setSendBufferSize(128 * 1024); } catch (Exception ignored) {}
            try { s.setReceiveBufferSize(128 * 1024); } catch (Exception ignored) {}
            if (listener != null) listener.onPhase("TCP připojení k " + p.host + ":" + p.port);
            s.connect(new InetSocketAddress(p.host, p.port), 8000);
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            if (listener != null) listener.onPhase("TLS 1.2 handshake");
            s.startHandshake();
            if (listener != null) listener.onPhase("Ověření párovacího klíče");
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeUTF(p.secret);
            out.flush();
            if (new DataInputStream(s.getInputStream()).readInt() != MAGIC) {
                throw new IOException("Tabule připojení odmítla.");
            }
            return s;
        } catch (Exception e) {
            close(s);
            throw e;
        }
    }

    static void close(Closeable c) { if (c != null) try { c.close(); } catch (Exception ignored) {} }
}
