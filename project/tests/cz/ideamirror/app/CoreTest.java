package cz.ideamirror.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;
import com.google.zxing.*;
import com.google.zxing.common.*;

public class CoreTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    static byte[] header(int size, int flags, long pts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(size); o.writeInt(flags); o.writeLong(pts);
        return b.toByteArray();
    }

    static void rejectPacket(byte[] data) throws IOException {
        try {
            Packet.read(new DataInputStream(new ByteArrayInputStream(data)));
            throw new AssertionError("Invalid packet accepted");
        } catch (IOException expected) { checks++; }
    }

    static void rejectCode(String code) throws IOException {
        try {
            new PairCode(code);
            throw new AssertionError("Invalid pairing code accepted: " + code);
        } catch (IOException expected) { checks++; }
    }

    static void runTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) task.run();
    }

    static void handshakeEngines(SSLEngine client, SSLEngine server) throws SSLException {
        ByteBuffer c2s = ByteBuffer.allocate(65536), s2c = ByteBuffer.allocate(65536);
        ByteBuffer app = ByteBuffer.allocate(65536), empty = ByteBuffer.allocate(0);
        client.beginHandshake();
        server.beginHandshake();
        for (int step = 0; step < 64; step++) {
            SSLEngineResult.HandshakeStatus cs = client.getHandshakeStatus();
            SSLEngineResult.HandshakeStatus ss = server.getHandshakeStatus();
            if (cs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && ss == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                return;
            }
            if (cs == SSLEngineResult.HandshakeStatus.NEED_TASK) { runTasks(client); continue; }
            if (ss == SSLEngineResult.HandshakeStatus.NEED_TASK) { runTasks(server); continue; }
            if (cs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                client.wrap(empty, c2s); continue;
            }
            if (ss == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                server.wrap(empty, s2c); continue;
            }
            if (ss == SSLEngineResult.HandshakeStatus.NEED_UNWRAP && c2s.position() > 0) {
                c2s.flip();
                app.clear();
                server.unwrap(c2s, app);
                c2s.compact();
                continue;
            }
            if (cs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP && s2c.position() > 0) {
                s2c.flip();
                app.clear();
                client.unwrap(s2c, app);
                s2c.compact();
                continue;
            }
            throw new AssertionError("TLS handshake stalled: client=" + cs + ", server=" + ss);
        }
        throw new AssertionError("TLS handshake did not complete");
    }

    static void testTlsEndToEnd() throws Exception {
        SecureRandom rng = new SecureRandom();
        TlsSupport.Identity id = TlsSupport.generateSelfSignedEcIdentity(rng, "test");
        check(id.fp.matches("[0-9a-f]{64}"), "valid sha256 fingerprint");
        id.cert.checkValidity();
        checks++;
        TlsSupport.verifySigningKey(id.key, id.cert);
        checks++;

        X509ExtendedKeyManager km = TlsSupport.serverKeyManager("tabule-link-tls-v2", id.cert, id.key);
        check("tabule-link-tls-v2".equals(km.chooseServerAlias("EC", null, null)), "km chooseServerAlias EC");
        check("tabule-link-tls-v2".equals(km.chooseServerAlias("EC_EC", null, null)), "km chooseServerAlias EC_EC");
        check("tabule-link-tls-v2".equals(km.chooseEngineServerAlias("EC", null, null)), "km chooseEngineServerAlias EC");
        check("tabule-link-tls-v2".equals(km.chooseEngineServerAlias("EC_EC", null, null)), "km chooseEngineServerAlias EC_EC");
        check(km.chooseServerAlias("RSA", null, null) == null, "km rejects RSA");
        check(km.chooseEngineServerAlias("RSA", null, null) == null, "km engine rejects RSA");
        check(km.getServerAliases("EC_EC", null) != null && km.getServerAliases("EC_EC", null).length == 1, "km getServerAliases EC_EC");

        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(new KeyManager[]{km}, null, rng);

        // 1. Successful in-memory TLS 1.2 handshake + encrypted packet transfer
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, new TrustManager[]{TlsSupport.pinningTrustManager(id.fp)}, rng);
        SSLEngine srv = serverCtx.createSSLEngine();
        srv.setUseClientMode(false);
        srv.setEnabledProtocols(new String[]{"TLSv1.2"});
        SSLEngine cli = clientCtx.createSSLEngine();
        cli.setUseClientMode(true);
        cli.setEnabledProtocols(new String[]{"TLSv1.2"});

        handshakeEngines(cli, srv);
        check("TLSv1.2".equals(cli.getSession().getProtocol()), "client negotiated TLSv1.2");
        check("TLSv1.2".equals(srv.getSession().getProtocol()), "server negotiated TLSv1.2");

        ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(plainOut);
        Packet.write(dos, Packet.FLAG_CODEC_CONFIG, 0L, new byte[]{0, 0, 0, 1, 103, 66});
        Packet.write(dos, Packet.FLAG_KEY_FRAME, 40000L, new byte[]{0, 0, 0, 1, 101, 1, 2, 3});
        Packet.heartbeat(dos);
        byte[] plainBytes = plainOut.toByteArray();

        ByteBuffer src = ByteBuffer.wrap(plainBytes);
        ByteBuffer net = ByteBuffer.allocate(65536);
        ByteBuffer dst = ByteBuffer.allocate(65536);
        while (src.hasRemaining()) cli.wrap(src, net);
        net.flip();
        while (net.hasRemaining()) srv.unwrap(net, dst);
        dst.flip();
        byte[] decrypted = new byte[dst.remaining()];
        dst.get(decrypted);
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(decrypted));
        Packet p1 = Packet.read(dis);
        Packet p2 = Packet.read(dis);
        Packet hb = Packet.read(dis);
        check(p1.flags == Packet.FLAG_CODEC_CONFIG && p1.data.length == 6, "tls decrypted config packet");
        check(p2.flags == Packet.FLAG_KEY_FRAME && p2.pts == 40000L && p2.data.length == 8, "tls decrypted keyframe");
        check(hb.data.length == 0 && hb.flags == 0 && hb.pts == 0, "tls decrypted heartbeat");

        // 2. Wrong fingerprint must fail TLS 1.2 handshake
        String badFp = (id.fp.startsWith("a") ? "b" : "a") + id.fp.substring(1);
        SSLContext badCtx = SSLContext.getInstance("TLS");
        badCtx.init(null, new TrustManager[]{TlsSupport.pinningTrustManager(badFp)}, rng);
        SSLEngine srv2 = serverCtx.createSSLEngine();
        srv2.setUseClientMode(false);
        srv2.setEnabledProtocols(new String[]{"TLSv1.2"});
        SSLEngine cli2 = badCtx.createSSLEngine();
        cli2.setUseClientMode(true);
        cli2.setEnabledProtocols(new String[]{"TLSv1.2"});
        boolean rejected = false;
        try {
            handshakeEngines(cli2, srv2);
        } catch (SSLException expected) {
            rejected = true;
        }
        check(rejected, "wrong fingerprint rejected during TLS 1.2 handshake");
    }

    public static void main(String[] args) throws Exception {
        String fp = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        String secret = "1234567890abcdef1234567890abcdef";
        String code = "tabulelink://192.168.10.25:42521?fp=" + fp + "&key=" + secret;
        PairCode pair = new PairCode(" \n" + code + "\n ");
        check(pair.port == 42521 && pair.host.equals("192.168.10.25") && pair.fp.equals(fp) && pair.secret.equals(secret), "pair roundtrip");
        for (String bad : Arrays.asList(null, "", code + "&extra=1", code.replace("192.168.10.25", "evil.example"),
            code.replace("192.168.10.25", "127.0.0.1"), code.replace("192.168.10.25", "224.0.0.1"),
            code.replace("192.168.10.25", "0.0.0.0"), code.replace("192.168.10.25", "192.168.10.256"),
            code.replace("192.168.10.25", "192.168.010.25"), code.replace(":42521", ":99999"),
            code.replace(":42521", ":80"), code.replace("tabulelink:", "http:"), code.substring(0, code.length() - 1))) {
            rejectCode(bad);
        }

        // Flag sanitization (Android MediaCodec API 34+ / partial / vendor bits)
        check(Packet.sanitizeFlags(0) == 0, "sanitize 0");
        check(Packet.sanitizeFlags(1) == 1, "sanitize keyframe");
        check(Packet.sanitizeFlags(2) == 2, "sanitize config");
        check(Packet.sanitizeFlags(1 | 8 | 16 | 32 | 0x100) == 1, "sanitize strips high flags");
        check(Packet.sanitizeFlags(4) == 0, "sanitize strips eos");

        Random r = new Random(20261116);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(all);
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            byte[] payload = new byte[1 + r.nextInt(65536)];
            r.nextBytes(payload);
            payloads.add(payload);
            Packet.write(out, i % 3, i * 40000L, payload);
            if (i % 10 == 0) Packet.heartbeat(out);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(all.toByteArray()));
        for (int i = 0; i < 100; i++) {
            Packet p = Packet.read(in);
            check(p.flags == i % 3 && p.pts == i * 40000L && Arrays.equals(p.data, payloads.get(i)), "packet " + i);
            if (i % 10 == 0) {
                Packet h = Packet.read(in);
                check(h.data.length == 0 && h.pts == 0 && h.flags == 0, "heartbeat");
            }
        }
        check(in.read() == -1, "no trailing bytes");
        rejectPacket(header(-1, 0, 0));
        rejectPacket(header(Integer.MAX_VALUE, 0, 0));
        rejectPacket(header(Packet.MAX_SIZE + 1, 0, 0));
        rejectPacket(header(1, 8, 0));
        rejectPacket(header(0, 1, 0));
        rejectPacket(header(0, 0, 1));
        rejectPacket(header(10, 0, 0));
        rejectPacket(new byte[]{0, 0, 0});
        byte[] bound = new byte[Packet.MAX_SIZE];
        all.reset();
        Packet.write(out, 1, 123, bound);
        check(Packet.read(new DataInputStream(new ByteArrayInputStream(all.toByteArray()))).data.length == bound.length, "max frame");

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 3);
        BitMatrix m = new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 700, 700, hints);
        int[] rgb = new int[700 * 700];
        for (int y = 0; y < 700; y++) for (int x = 0; x < 700; x++) rgb[y * 700 + x] = m.get(x, y) ? 0xff000000 : 0xffffffff;
        Result qr = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(700, 700, rgb))));
        check(qr.getText().equals(code), "displayed QR code decodes exactly");

        testTlsEndToEnd();
        testNetUtil();
        testFrameQueueAndFastPacket();
        testAudioUtil();

        System.out.println("PASS: " + checks + " checks (pairing, malformed codes, flag sanitization, framing, single-record write, heartbeat, bounds, truncation, QR roundtrip, TLS 1.2 end-to-end, LAN subnet & interface ranking, low-latency frame queue, audio volume gain)");
    }

    static void testAudioUtil() {
        check(AudioUtil.clampPercent(-5) == 0, "clamp low");
        check(AudioUtil.clampPercent(150) == 100, "clamp high");
        check(AudioUtil.percentToGain(0) == 0f, "gain 0%");
        check(Math.abs(AudioUtil.percentToGain(50) - 0.25f) < 0.0001f, "gain 50% quadratic");
        check(AudioUtil.percentToGain(100) == 1f, "gain 100%");

        // Little-endian signed 16-bit samples: +20000 and -20000
        byte[] pcm = new byte[]{(byte) (20000 & 0xFF), (byte) ((20000 >> 8) & 0xFF),
                                (byte) (-20000 & 0xFF), (byte) ((-20000 >> 8) & 0xFF)};
        byte[] copy100 = pcm.clone();
        AudioUtil.applyGainPcm16Le(copy100, copy100.length, 100);
        check(Arrays.equals(copy100, pcm), "100% leaves PCM unchanged");

        byte[] copy50 = pcm.clone();
        AudioUtil.applyGainPcm16Le(copy50, copy50.length, 50);
        int s1 = (copy50[0] & 0xFF) | (copy50[1] << 8);
        int s2 = (copy50[2] & 0xFF) | (copy50[3] << 8);
        check(s1 == 5000 && s2 == -5000, "50% scales signed PCM by 0.25 -> +/-5000");

        byte[] copy0 = pcm.clone();
        AudioUtil.applyGainPcm16Le(copy0, copy0.length, 0);
        check(copy0[0] == 0 && copy0[1] == 0 && copy0[2] == 0 && copy0[3] == 0, "0% mutes PCM");
    }

    static void testFrameQueueAndFastPacket() throws Exception {
        // Single-record write compatibility with Packet.read
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] scratch = null;
        byte[] cfg = new byte[]{0, 0, 0, 1, 103, 66, 0, 41};
        byte[] kf = new byte[]{0, 0, 0, 1, 101, 10, 20, 30, 40};
        scratch = Packet.writeSingleRecord(raw, scratch, Packet.FLAG_CODEC_CONFIG, 0L, cfg);
        scratch = Packet.writeSingleRecord(raw, scratch, Packet.FLAG_KEY_FRAME, 123456L, kf);
        Packet.writeHeartbeatRecord(raw);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()));
        Packet pCfg = Packet.read(din);
        Packet pKf = Packet.read(din);
        Packet pHb = Packet.read(din);
        check(pCfg.flags == Packet.FLAG_CODEC_CONFIG && Arrays.equals(pCfg.data, cfg), "single-record config");
        check(pKf.flags == Packet.FLAG_KEY_FRAME && pKf.pts == 123456L && Arrays.equals(pKf.data, kf), "single-record keyframe");
        check(pHb.data.length == 0 && pHb.flags == 0 && pHb.pts == 0, "single-record heartbeat");
        check(din.read() == -1, "single-record no trailing bytes");

        // FrameQueue: preserves CODEC_CONFIG, requests sync on soft depth, drops stale chain cleanly on new keyframe
        FrameQueue q = new FrameQueue();
        check(!q.offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0, cfg), 1000), "config does not trigger sync");
        check(!q.offer(new Packet(Packet.FLAG_KEY_FRAME, 1, kf), 1000), "first keyframe no sync");
        check(!q.offer(new Packet(0, 2, new byte[]{1}), 1030), "p1 no sync");
        check(q.offer(new Packet(0, 3, new byte[]{2}), 1060), "p2 reaches soft depth -> requests sync");
        // New keyframe arrives while queue has stale frames -> drops old video frames, keeps CODEC_CONFIG!
        check(!q.offer(new Packet(Packet.FLAG_KEY_FRAME, 4, new byte[]{9, 9}), 1200), "new keyframe clears stale frames");
        check(q.droppedFrames() == 3, "dropped 3 stale video frames");
        Packet first = q.poll(10);
        Packet second = q.poll(10);
        check(first != null && first.flags == Packet.FLAG_CODEC_CONFIG, "CODEC_CONFIG preserved");
        check(second != null && second.flags == Packet.FLAG_KEY_FRAME && second.pts == 4, "fresh keyframe delivered next");
        check(q.size() == 0, "queue drained cleanly");

        // Hard overflow enters awaitingKeyFrame and drops subsequent P-frames until next keyframe
        for (int i = 0; i < FrameQueue.HARD_DEPTH; i++) {
            q.offer(new Packet(0, 10 + i, new byte[]{(byte) i}), 2000 + i * 10L);
        }
        q.offer(new Packet(0, 99, new byte[]{99}), 2500); // triggers hard drop + awaitingKeyFrame
        q.offer(new Packet(0, 100, new byte[]{100}), 2530); // dropped while awaiting keyframe
        check(q.size() == 0, "no broken P-frames queued while awaiting keyframe");
        q.offer(new Packet(Packet.FLAG_KEY_FRAME, 101, new byte[]{7}), 2560);
        Packet recovered = q.poll(10);
        check(recovered != null && recovered.flags == Packet.FLAG_KEY_FRAME && recovered.pts == 101, "recovered cleanly on next keyframe");

        // Audio framing + FrameQueue audio isolation and bounded audio ring
        byte[] aacCfg = new byte[]{0x11, (byte) 0x90};
        ByteArrayOutputStream audioRaw = new ByteArrayOutputStream();
        scratch = Packet.writeSingleRecord(audioRaw, scratch, Packet.FLAG_AUDIO | Packet.FLAG_CODEC_CONFIG, 0L, aacCfg);
        scratch = Packet.writeSingleRecord(audioRaw, scratch, Packet.FLAG_AUDIO, 21333L, new byte[]{1, 2, 3, 4, 5});
        DataInputStream aIn = new DataInputStream(new ByteArrayInputStream(audioRaw.toByteArray()));
        Packet apCfg = Packet.read(aIn);
        Packet apFrm = Packet.read(aIn);
        check(Packet.isAudio(apCfg.flags) && Packet.isConfig(apCfg.flags) && Arrays.equals(apCfg.data, aacCfg), "audio config roundtrip");
        check(Packet.isAudio(apFrm.flags) && !Packet.isConfig(apFrm.flags) && apFrm.pts == 21333L && apFrm.data.length == 5, "audio frame roundtrip");

        FrameQueue aq = new FrameQueue();
        aq.offer(new Packet(Packet.FLAG_AUDIO | Packet.FLAG_CODEC_CONFIG, 0L, aacCfg), 3000);
        for (int i = 0; i < FrameQueue.MAX_AUDIO_DEPTH + 3; i++) {
            aq.offer(new Packet(Packet.FLAG_AUDIO, i * 21333L, new byte[]{(byte) i}), 3010 + i * 20L);
        }
        check(aq.droppedAudio() == 3, "audio queue bounded and drops oldest non-config frames");
        Packet aFirst = aq.poll(10);
        Packet aSecond = aq.poll(10);
        check(aFirst != null && Packet.isAudio(aFirst.flags) && Packet.isConfig(aFirst.flags), "audio config never dropped");
        check(aSecond != null && Packet.isAudio(aSecond.flags) && aSecond.pts == 3 * 21333L, "newest audio frames preserved");
    }

    static void testNetUtil() {
        check(NetUtil.sameSubnet("192.168.1.23", 24, "192.168.1.50"), "same /24 subnet");
        check(!NetUtil.sameSubnet("192.168.1.23", 24, "192.168.49.1"), "different /24 subnet");
        check(NetUtil.sameSubnet("10.10.2.5", 16, "10.10.99.8"), "same /16 subnet");
        check(!NetUtil.sameSubnet("10.10.2.5", 24, "10.10.99.8"), "different /24 in 10.10");
        check(NetUtil.sameSubnet("172.16.4.10", 22, "172.16.7.250"), "same /22 subnet");
        check(!NetUtil.sameSubnet("172.16.4.10", 22, "172.16.8.1"), "different /22 subnet");
        check(!NetUtil.sameSubnet("192.168.1.23", 0, "192.168.1.50"), "reject prefix 0");
        check(!NetUtil.sameSubnet("bad", 24, "192.168.1.50"), "reject bad ip");

        int rWifiActive = NetUtil.rankEndpoint("192.168.1.50", "wlan0", NetUtil.TRANSPORT_WIFI, true, true);
        int rEthLan = NetUtil.rankEndpoint("10.0.0.15", "eth0", NetUtil.TRANSPORT_ETHERNET, false, true);
        int rHotspot = NetUtil.rankEndpoint("192.168.43.1", "ap0", "Hotspot", false, false);
        int rP2p = NetUtil.rankEndpoint("192.168.49.1", "p2p-wlan0-0", "Wi-Fi Direct", false, false);
        int rVpn = NetUtil.rankEndpoint("10.8.0.2", "tun0", NetUtil.TRANSPORT_VPN, true, true);
        check(rWifiActive < rEthLan, "active Wi-Fi ranks before secondary Ethernet");
        check(rEthLan < rHotspot, "Ethernet ranks before Hotspot ap0");
        check(rHotspot < rVpn, "Hotspot ranks before VPN");
        check(rWifiActive < rP2p, "active Wi-Fi ranks before Wi-Fi Direct p2p");
        check(NetUtil.isVirtualOrHotspotIface("p2p-wlan0-0"), "p2p detected as virtual/hotspot");
        check(NetUtil.isVirtualOrHotspotIface("ap0"), "ap0 detected as virtual/hotspot");
        check(NetUtil.isVirtualOrHotspotIface("rndis0"), "rndis0 detected as virtual/hotspot");
        check(!NetUtil.isVirtualOrHotspotIface("wlan0"), "wlan0 is standard LAN");
        check(!NetUtil.isVirtualOrHotspotIface("eth0"), "eth0 is standard LAN");
        check("Wi-Fi · 192.168.1.50/24 (wlan0) [aktivní]".equals(
            NetUtil.formatEndpoint("192.168.1.50", 24, "wlan0", NetUtil.TRANSPORT_WIFI, true)), "endpoint label format");
    }
}
