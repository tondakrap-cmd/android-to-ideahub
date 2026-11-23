package cz.ideamirror.app;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Tests for protocol v2 (0.7): SPAKE2, handshake over real sockets, registry, limiter, store, geometry, control. */
final class ProtoTest {
    static void check(boolean ok, String msg) { CoreTest.check(ok, msg); }

    /** Shared cross-implementation vector (WindowsTests.cs asserts the same values). */
    static final String VEC_PIN = "482917";
    static final String VEC_FP = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";

    static BigInteger vecScalar(String label) {
        return new BigInteger(1, Spake2.sha256(label.getBytes(StandardCharsets.US_ASCII))).mod(Spake2.Q);
    }

    static void testSpake2() throws Exception {
        check(Spake2.P.bitLength() == 2048, "spake p 2048 bit");
        check(Spake2.G.modPow(Spake2.Q, Spake2.P).equals(BigInteger.ONE), "g has order q");
        check(Spake2.isValidElement(Spake2.M) && Spake2.isValidElement(Spake2.N) && !Spake2.M.equals(Spake2.N), "M, N valid & distinct");
        check(!Spake2.isValidElement(BigInteger.ONE) && !Spake2.isValidElement(Spake2.P.subtract(BigInteger.ONE)), "reject trivial elements");

        SecureRandom r = new SecureRandom();
        Spake2.Client c = new Spake2.Client("123456", r);
        Spake2.Server s = new Spake2.Server("123456", r);
        Spake2.Keys ks = s.respond(c.messageHex(), VEC_FP);
        Spake2.Keys kc = c.finish(s.messageHex(), VEC_FP);
        check(Arrays.equals(ks.sessionKey, kc.sessionKey) && ks.macServer.equals(kc.macServer) && ks.macClient.equals(kc.macClient), "spake same pin agrees");

        Spake2.Client bad = new Spake2.Client("123457", r);
        Spake2.Server s2 = new Spake2.Server("123456", r);
        check(!s2.respond(bad.messageHex(), VEC_FP).macServer.equals(bad.finish(s2.messageHex(), VEC_FP).macServer), "spake wrong pin disagrees");

        Spake2.Client c3 = new Spake2.Client("123456", r);
        Spake2.Server s3 = new Spake2.Server("123456", r);
        Spake2.Keys k3s = s3.respond(c3.messageHex(), VEC_FP);
        Spake2.Keys k3c = c3.finish(s3.messageHex(), VEC_FP.replace('a', 'b'));
        check(!k3s.macServer.equals(k3c.macServer), "spake binds certificate fingerprint (MITM relay fails)");

        boolean rejected = false;
        try { new Spake2.Server("1", r).respond(Spake2.toHex(BigInteger.ONE), VEC_FP); } catch (IOException e) { rejected = true; }
        check(rejected, "spake rejects identity element");
        rejected = false;
        try { new Spake2.Server("1", r).respond("zz", VEC_FP); } catch (IOException e) { rejected = true; }
        check(rejected, "spake rejects malformed hex");

        // Deterministic vector shared with Windows
        Spake2.Client vc = new Spake2.Client(VEC_PIN, vecScalar("vector x"));
        Spake2.Server vs = new Spake2.Server(VEC_PIN, vecScalar("vector y"));
        Spake2.Keys vk = vs.respond(vc.messageHex(), VEC_FP);
        Spake2.Keys vk2 = vc.finish(vs.messageHex(), VEC_FP);
        check(vk.macServer.equals(vk2.macServer), "vector agrees");
        String vector = TlsSupport.hex(vk.sessionKey);
        if (System.getProperty("printVector") != null) {
            System.out.println("X=" + vc.messageHex().substring(0, 32) + " Y=" + vs.messageHex().substring(0, 32));
            System.out.println("SK=" + vector + " MACS=" + vk.macServer + " MACC=" + vk.macClient);
        }
        check(vc.messageHex().startsWith(VEC_X_PREFIX), "vector X");
        check(vs.messageHex().startsWith(VEC_Y_PREFIX), "vector Y");
        check(vector.equals(VEC_SK), "vector session key");
        check(vk.macServer.equals(VEC_MACS) && vk.macClient.equals(VEC_MACC), "vector macs");
    }

    static final String VEC_X_PREFIX = "c35afe3af0d618966c25fe152fe2e06e";
    static final String VEC_Y_PREFIX = "71f87db90828465e71ff9596fb875233";
    static final String VEC_SK = "41114008c7bcd578d9ed9697aab8a39fd2a5a4ceffce1ef9fa87db2e2887968e";
    static final String VEC_MACS = "c4da7e4306f06f217301c3c355267fa8c7efcc249e30e629615aa8a532616086";
    static final String VEC_MACC = "8da3ff04fa87ca1427c5d9e55ce52ae93459a9a16438c7870ed11327796dc7a9";

    // ---------------------------------------------------------------- handshake over sockets

    static final class Ctx implements Proto.ServerContext {
        String legacy = "0123456789abcdef0123456789abcdef";
        final DeviceRegistry reg = new DeviceRegistry();
        final AuthLimiter limiter = new AuthLimiter();
        final AtomicInteger tokenRotations = new AtomicInteger(), pinRotations = new AtomicInteger();
        volatile String storedToken = "fedcba9876543210fedcba9876543210", storedPin = "246810";
        final PairingCredentials creds = new PairingCredentials(new PairingCredentials.Store() {
            public String loadToken() { return storedToken; }
            public void saveToken(String t) { tokenRotations.incrementAndGet(); storedToken = t; }
            public String loadPin() { return storedPin; }
            public void savePin(String p) { pinRotations.incrementAndGet(); storedPin = p; }
        }, new SecureRandom());
        /** When set, the first two random() calls (right after the PIN ticket is taken) wait for each other. */
        volatile CyclicBarrier ticketBarrier;
        final AtomicInteger randomCalls = new AtomicInteger();
        long now = 1_000_000L;
        String token() { return creds.token(); }
        String pin() { return creds.pin(); }
        public String fingerprint() { return VEC_FP; }
        public String boardName() { return "Zasedačka 2.14"; }
        public String legacySecret() { return legacy; }
        public PairingCredentials credentials() { return creds; }
        public DeviceRegistry registry() { return reg; }
        public AuthLimiter limiter() { return limiter; }
        public SecureRandom random() {
            CyclicBarrier b = ticketBarrier;
            if (b != null && randomCalls.incrementAndGet() <= 2) {
                try { b.await(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            return new SecureRandom();
        }
        public long nowMs() { return now; }
        public void onCredentialsChanged() {}
        public void onPinRotationNeeded() { creds.rotatePin(); limiter.onPinRotated(); }
        public void onRegistryChanged() {}
    }

    interface ClientSide { Object run(DataInputStream in, DataOutputStream out) throws Exception; }

    /** Runs server auth and the given client over a loopback TCP connection; returns {serverResult|Exception, clientResult|Exception}. */
    static Object[] handshake(Ctx ctx, String remoteKey, ClientSide client) throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService ex = Executors.newSingleThreadExecutor();
            Future<Object> server = ex.submit(() -> {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    try { return Proto.serverAuthenticate(in, out, ctx, remoteKey); } catch (Exception e) { return e; }
                }
            });
            Object clientResult;
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort())) {
                c.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream()));
                DataOutputStream out = new DataOutputStream(c.getOutputStream());
                try { clientResult = client.run(in, out); } catch (Exception e) { clientResult = e; }
            }
            Object serverResult = server.get(10, TimeUnit.SECONDS);
            ex.shutdownNow();
            return new Object[]{serverResult, clientResult};
        }
    }

    static Set<String> caps(String... c) { return new LinkedHashSet<>(Arrays.asList(c)); }

    static void testHandshake() throws Exception {
        final Ctx ctx = new Ctx();

        // Token pairing (QR) → device key issued, token rotated
        Object[] r = handshake(ctx, "10.0.0.5", (in, out) -> Proto.clientHello(in, out,
            new Proto.Hello(Proto.METHOD_TOKEN, "-", ctx.token(), "Tablet | Jana\n", caps(Proto.CAP_CTL, Proto.CAP_PAIR))));
        check(r[0] instanceof Proto.AuthResult && r[1] instanceof Proto.ClientResult, "token pairing ok: " + r[0] + " / " + r[1]);
        Proto.ClientResult cr = (Proto.ClientResult) r[1];
        Proto.AuthResult ar = (Proto.AuthResult) r[0];
        check(cr.deviceKey != null && Proto.isHex(cr.deviceKey, 32) && cr.deviceId.equals(ar.deviceId), "token issues device key");
        check("Zasedačka 2.14".equals(cr.boardName) && cr.serverCaps.contains("ctl"), "board name + caps");
        check(ar.paired && ar.has(Proto.CAP_PAIR) && "Tablet Jana".equals(ar.deviceName), "sanitized device name: " + ar.deviceName);
        check(ctx.tokenRotations.get() == 1 && ctx.reg.size() == 1, "token rotated after use");

        // Old token no longer valid
        r = handshake(ctx, "10.0.0.5", (in, out) -> Proto.clientHello(in, out,
            new Proto.Hello(Proto.METHOD_TOKEN, "-", "fedcba9876543210fedcba9876543210", "x", caps())));
        check(r[1] instanceof Proto.ProtoException && Proto.ERR_AUTH.equals(((Proto.ProtoException) r[1]).code), "used token rejected");

        // Device key login
        final String devId = cr.deviceId, devKey = cr.deviceKey;
        r = handshake(ctx, "10.0.0.5", (in, out) -> Proto.clientHello(in, out,
            new Proto.Hello(Proto.METHOD_KEY, devId, devKey, "Tablet Jana", caps(Proto.CAP_CTL, Proto.CAP_RESUME))));
        check(r[1] instanceof Proto.ClientResult && ((Proto.ClientResult) r[1]).deviceKey == null, "device key login");
        check(((Proto.AuthResult) r[0]).has(Proto.CAP_RESUME) && !((Proto.AuthResult) r[0]).paired, "caps passed");

        // Revoked key
        ctx.reg.revoke(devId);
        r = handshake(ctx, "10.0.0.6", (in, out) -> Proto.clientHello(in, out,
            new Proto.Hello(Proto.METHOD_KEY, devId, devKey, "Tablet Jana", caps())));
        check(r[1] instanceof Proto.ProtoException, "revoked device rejected");

        // PIN pairing (SPAKE2) success
        r = handshake(ctx, "10.0.0.7", (in, out) -> Proto.clientPairPin(in, out, ctx.pin(), VEC_FP, "Notebook", caps(Proto.CAP_CTL), new SecureRandom()));
        check(r[0] instanceof Proto.AuthResult && r[1] instanceof Proto.ClientResult && ((Proto.ClientResult) r[1]).deviceKey != null, "pin pairing ok: " + r[1]);
        check(ctx.pinRotations.get() == 1, "pin rotated after successful pairing");

        // Wrong PIN
        r = handshake(ctx, "10.0.0.8", (in, out) -> Proto.clientPairPin(in, out, "000000", VEC_FP, "Útočník", caps(), new SecureRandom()));
        check(r[1] instanceof Proto.ProtoException && r[0] instanceof Exception, "wrong pin rejected both sides");

        // MITM: client pinned a different fingerprint than the real board → client aborts
        r = handshake(ctx, "10.0.0.9", (in, out) -> Proto.clientPairPin(in, out, ctx.pin(), VEC_FP.replace('a', 'c'), "Oběť", caps(), new SecureRandom()));
        check(r[1] instanceof Proto.ProtoException && r[0] instanceof Exception, "fingerprint mismatch aborts pairing");

        // Lockout after repeated failures from one address
        for (int i = 0; i < 3; i++) {
            handshake(ctx, "10.0.0.66", (in, out) -> Proto.clientPairPin(in, out, "999999", VEC_FP, "x", caps(), new SecureRandom()));
        }
        r = handshake(ctx, "10.0.0.66", (in, out) -> Proto.clientPairPin(in, out, ctx.pin(), VEC_FP, "x", caps(), new SecureRandom()));
        check(r[1] instanceof Proto.ProtoException && Proto.ERR_LOCKED.equals(((Proto.ProtoException) r[1]).code), "locked out after failures");
        // ...but another address is not affected
        r = handshake(ctx, "10.0.0.67", (in, out) -> Proto.clientPairPin(in, out, ctx.pin(), VEC_FP, "y", caps(), new SecureRandom()));
        check(r[1] instanceof Proto.ClientResult, "other clients not locked out");
        ctx.now += AuthLimiter.MAX_LOCK_MS + 1;

        // Race: two connections with the same one-time QR token at once → exactly one pairs (Codex review finding)
        for (int round = 0; round < 8; round++) {
            final String tok = ctx.token();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Object[]>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String ip = "10.1." + round + "." + i;
                fs.add(pool.submit(() -> handshake(ctx, ip, (in, out) -> Proto.clientHello(in, out,
                    new Proto.Hello(Proto.METHOD_TOKEN, "-", tok, "Racer", caps(Proto.CAP_PAIR))))));
            }
            int ok = 0, rejected = 0;
            for (Future<Object[]> f : fs) {
                Object c = f.get(20, TimeUnit.SECONDS)[1];
                if (c instanceof Proto.ClientResult) ok++;
                else if (c instanceof Proto.ProtoException && Proto.ERR_AUTH.equals(((Proto.ProtoException) c).code)) rejected++;
            }
            pool.shutdownNow();
            check(ok == 1 && rejected == 1, "racing QR token pairs once (round " + round + ": " + ok + " ok)");
        }

        // Race: two PIN pairings that both took the PIN before either finished → only the first one pairs
        {
            final String pinNow = ctx.pin();
            int devicesBefore = ctx.reg.size();
            ctx.randomCalls.set(0);
            ctx.ticketBarrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Object[]>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String ip = "10.2.0." + i;
                fs.add(pool.submit(() -> handshake(ctx, ip, (in, out) -> Proto.clientPairPin(in, out, pinNow, VEC_FP, "Racer", caps(), new SecureRandom()))));
            }
            int ok = 0, reused = 0;
            for (Future<Object[]> f : fs) {
                Object c = f.get(30, TimeUnit.SECONDS)[1];
                if (c instanceof Proto.ClientResult) ok++;
                else if (c instanceof Proto.ProtoException && ((Proto.ProtoException) c).getMessage().contains("mezitím použit")) reused++;
            }
            pool.shutdownNow();
            ctx.ticketBarrier = null;
            check(ok == 1 && reused == 1, "racing PIN pairs once (" + ok + " ok, " + reused + " reused)");
            check(ctx.reg.size() == devicesBefore + 1 && !ctx.pin().equals(pinNow), "one device issued, PIN rotated");
        }

        // PairingCredentials: a ticket is invalid after any rotation
        PairingCredentials.PinTicket stale = ctx.creds.pinTicket();
        ctx.creds.rotatePin();
        check(!ctx.creds.consumePin(stale) && !ctx.creds.consumePin(null), "stale PIN ticket rejected");
        PairingCredentials.PinTicket fresh = ctx.creds.pinTicket();
        check(ctx.creds.consumePin(fresh) && !ctx.creds.consumePin(fresh), "PIN ticket consumed only once");
        String t0 = ctx.creds.token();
        check(ctx.creds.consumeToken(t0) && !ctx.creds.consumeToken(t0) && !ctx.creds.token().equals(t0), "token consumed only once");

        // Legacy v1 shared secret
        r = handshake(ctx, "10.0.0.10", (in, out) -> { Proto.clientLegacy(in, out, ctx.legacy); return "ok"; });
        check("ok".equals(r[1]) && ((Proto.AuthResult) r[0]).legacy, "legacy secret accepted");
        r = handshake(ctx, "10.0.0.10", (in, out) -> { Proto.clientLegacy(in, out, "00000000000000000000000000000000"); return "ok"; });
        check(r[1] instanceof IOException, "wrong legacy secret rejected");

        // Removed plain-PIN method of 0.5/0.6 Windows clients
        r = handshake(ctx, "10.0.0.11", (in, out) -> { out.writeUTF("PIN:" + ctx.pin()); out.flush(); return in.readInt(); });
        check(r[1] instanceof EOFException && r[0] instanceof Proto.ProtoException, "plain PIN method removed");

        // Malformed v2 hello
        r = handshake(ctx, "10.0.0.12", (in, out) -> Proto.clientHello(in, out, new Proto.Hello("KEY", "zz", "1", "n", caps())));
        check(r[1] instanceof Proto.ProtoException && Proto.ERR_PROTO.equals(((Proto.ProtoException) r[1]).code), "malformed hello rejected");

        // Old server: closes without reply → OldServerException
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> { try (Socket s = ss.accept()) { new DataInputStream(s.getInputStream()).readUTF(); } catch (Exception ignored) {} });
            t.start();
            Object res;
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort())) {
                try {
                    res = Proto.clientHello(new DataInputStream(c.getInputStream()), new DataOutputStream(c.getOutputStream()),
                        new Proto.Hello(Proto.METHOD_TOKEN, "-", ctx.token(), "x", caps()));
                } catch (Exception e) { res = e; }
            }
            t.join(2000);
            check(res instanceof Proto.OldServerException, "old server detected");
        }

        // Header + approval wait + go / error
        ByteArrayOutputStream srv = new ByteArrayOutputStream();
        DataOutputStream so = new DataOutputStream(srv);
        Proto.writeWait(so); Proto.writeWait(so); Proto.writeGo(so);
        final int[] waits = {0};
        Proto.awaitGo(new DataInputStream(new ByteArrayInputStream(srv.toByteArray())), () -> waits[0]++);
        check(waits[0] == 2, "approval waits reported");
        srv.reset();
        Proto.writeStartError(so, Proto.ERR_DENIED, "Převzetí odmítnuto na tabuli.");
        boolean denied = false;
        try { Proto.awaitGo(new DataInputStream(new ByteArrayInputStream(srv.toByteArray())), null); }
        catch (Proto.ProtoException e) { denied = Proto.ERR_DENIED.equals(e.code) && e.getMessage().contains("odmítnuto"); }
        check(denied, "start error carries code + message");
    }

    // ---------------------------------------------------------------- small units

    static void testRegistryStoreLimiter() {
        DeviceRegistry reg = new DeviceRegistry();
        SecureRandom r = new SecureRandom();
        String[] k1 = new String[1], k2 = new String[1];
        DeviceRegistry.Device a = reg.issue("Notebook\tA", r, 10, k1);
        DeviceRegistry.Device b = reg.issue("Tablet B", r, 20, k2);
        check(reg.verify(a.id, k1[0], null, 30) != null && reg.verify(a.id, k2[0], null, 30) == null, "registry key per device");
        check(reg.verify("0000000000000000", k1[0], null, 30) == null, "registry unknown id");
        DeviceRegistry dec = DeviceRegistry.decode(reg.encode());
        check(dec.size() == 2 && dec.verify(b.id, k2[0], null, 40) != null, "registry encode/decode");
        check(dec.list().get(0).id.equals(b.id), "registry sorted by last use");
        check(dec.list().get(1).name.equals("Notebook A"), "registry name sanitized");
        check(dec.revoke(a.id) && dec.size() == 1 && !dec.contains(a.id), "registry revoke");
        check(DeviceRegistry.decode("garbage\nmore\tstuff").size() == 0, "registry ignores garbage");
        check(!reg.encode().contains(k1[0]), "registry never stores raw keys");

        BoardStore store = new BoardStore();
        BoardStore.Board bd = new BoardStore.Board("Zasedačka", VEC_FP, "192.168.1.50", 42521);
        bd.deviceId = "0123456789abcdef"; bd.deviceKey = "0123456789abcdef0123456789abcdef"; bd.lastUsed = 5;
        store.upsert(bd);
        BoardStore.Board old = new BoardStore.Board("Stará", "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", "10.0.0.2", 42521);
        old.legacySecret = "fedcba9876543210fedcba9876543210"; old.lastUsed = 9;
        store.upsert(old);
        BoardStore st2 = BoardStore.decode(store.encode());
        check(st2.size() == 2 && st2.list().get(0).name.equals("Stará") && st2.find(VEC_FP).hasV2Key(), "board store roundtrip");
        BoardStore.Board moved = st2.find(VEC_FP);
        moved.host = "192.168.1.77";
        st2.upsert(moved);
        check(st2.size() == 2 && st2.find(VEC_FP).host.equals("192.168.1.77"), "board store update by fingerprint");
        check(st2.remove(VEC_FP) && st2.size() == 1, "board store remove");
        check(BoardStore.decode("B1\tx\tbad\t1.2.3.4\t1\t-\t-\t-\t0").size() == 0, "board store rejects garbage");

        AuthLimiter l = new AuthLimiter();
        long t = 0;
        check(l.lockedForMs("a", t) == 0, "limiter free");
        l.recordFailure("a", t); l.recordFailure("a", t);
        check(l.lockedForMs("a", t) == 0, "limiter 2 free failures");
        l.recordFailure("a", t);
        check(l.lockedForMs("a", t) == AuthLimiter.BASE_LOCK_MS && l.lockedForMs("b", t) == 0, "limiter per-key lock");
        l.recordFailure("a", t);
        check(l.lockedForMs("a", t) == AuthLimiter.BASE_LOCK_MS * 2, "limiter exponential");
        l.recordSuccess("a");
        check(l.lockedForMs("a", t) == 0, "limiter success clears");
        AuthLimiter g = new AuthLimiter();
        boolean rotate = false;
        for (int i = 0; i < AuthLimiter.GLOBAL_FAILURES; i++) rotate |= g.recordFailure("ip" + i, 1000 + i);
        check(rotate, "limiter requests pin rotation");
        check(g.lockedForMs("fresh", 2000) > 0, "limiter global lock");
        check(g.lockedForMs("fresh", 2000 + AuthLimiter.GLOBAL_LOCK_MS + 100) == 0, "limiter global lock expires");
    }

    static void testGeometryAndControl() {
        check(Arrays.equals(VideoGeometry.fit(1366, 768, 1280), new int[]{1280, 720}), "geometry 1366x768 keeps aspect");
        check(Arrays.equals(VideoGeometry.fit(2560, 1600, 1920), new int[]{1920, 1200}), "geometry 16:10");
        check(Arrays.equals(VideoGeometry.fit(1920, 1080, 1920), new int[]{1920, 1088}) || Arrays.equals(VideoGeometry.fit(1920, 1080, 1920), new int[]{1920, 1072}), "geometry 1080 rounds to 16");
        check(Arrays.equals(VideoGeometry.fit(800, 1280, 960), new int[]{608, 960}), "geometry portrait");
        int[] g = VideoGeometry.fit(5000, 100, 1280);
        check(g[0] == 1280 && g[1] == 32, "geometry extreme aspect: " + g[0] + "x" + g[1]);
        check(VideoGeometry.fit(1, 1, 960)[0] == 16, "geometry minimum");

        check(Arrays.equals(Proto.parseFmt(Proto.fmt(1280, 720)), new int[]{1280, 720}), "fmt roundtrip");
        check(Proto.parseFmt("fmt:9999:9999") == null && Proto.parseFmt("fmt:a:b") == null && Proto.parseFmt("x") == null, "fmt rejects invalid");
        int[] t = Proto.parseTouch(Proto.touch('d', 70000, -5));
        check(t != null && t[0] == 'd' && t[1] == 65535 && t[2] == 0, "touch clamps");
        check(Proto.parseTouch("t:x:1:1") == null && Proto.parseTouch("t:d:1") == null, "touch rejects invalid");
        Packet p = Proto.control(Proto.CTL_PAUSE);
        check(Packet.isControl(p.flags) && Proto.CTL_PAUSE.equals(Proto.controlText(p)), "control packet");
        check("Notebook J Nováka".equals(Proto.deviceName("Notebook | J\u0000 Nováka😀")), "name strips separators and non-BMP: " + Proto.deviceName("Notebook | J\u0000 Nováka😀"));
        check(Proto.deviceName("   ").equals("Zařízení"), "name fallback");
        check(DiscoveryClient.preferredIp("192.168.5.10", "10.0.0.1").equals("192.168.5.10"), "discovery prefers source address");
        check(DiscoveryClient.preferredIp("127.0.0.1", "10.0.0.1").equals("10.0.0.1") && DiscoveryClient.preferredIp(null, "") == null, "discovery fallback address");
    }

    static void testFrameQueueV2() throws Exception {
        FrameQueue q = new FrameQueue();
        q.offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0, new byte[]{1}), 0);
        q.offer(new Packet(Packet.FLAG_KEY_FRAME, 1, new byte[]{2}), 0);
        q.offer(Proto.control("pause"), 0);
        check(Packet.isControl(q.poll(0).flags), "control polled first");
        q.requireKeyFrame();
        q.offer(new Packet(0, 2, new byte[]{3}), 1);
        check(Packet.isConfig(q.poll(0).flags) && q.poll(0) == null, "requireKeyFrame drops P frames, keeps config");
        q.offer(new Packet(Packet.FLAG_KEY_FRAME, 3, new byte[]{4}), 2);
        check(q.poll(0).pts == 3, "key frame accepted after requireKeyFrame");
        q.offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0, new byte[]{1}), 3);
        q.offer(new Packet(0, 4, new byte[]{5}), 3);
        q.clearVideo();
        check(q.poll(0) == null, "clearVideo drops everything");

        // Control packets go through the v2 wire format
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Packet.writeSingleRecord(b, null, Packet.FLAG_CONTROL, 0, "fmt:640:480".getBytes(StandardCharsets.UTF_8));
        Packet back = Packet.read(new DataInputStream(new ByteArrayInputStream(b.toByteArray())));
        check(Packet.isControl(back.flags) && Arrays.equals(Proto.parseFmt(Proto.controlText(back)), new int[]{640, 480}), "control over wire");
    }

    static void testDiscoveryLoopback() throws Exception {
        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            responder.setSoTimeout(3000);
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[256];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    responder.receive(req);
                    // Simulate a slow board (answers after 500 ms): 0.6.x gave up after 250 ms of silence.
                    Thread.sleep(500);
                    byte[] reply = DiscoveryProtocol.buildOffer("IdeaHub", 42521, VEC_FP, "192.168.77.5").getBytes(StandardCharsets.UTF_8);
                    responder.send(new DatagramPacket(reply, reply.length, req.getSocketAddress()));
                } catch (Exception ignored) {}
            });
            t.start();
            List<DiscoveryProtocol.Offer> offers = DiscoveryClient.discover(1500,
                Collections.singletonList(InetAddress.getLoopbackAddress()), responder.getLocalPort());
            t.join(3000);
            check(offers.size() == 1 && offers.get(0).fp.equals(VEC_FP) && offers.get(0).ip.equals("192.168.77.5"), "discovery waits for slow board");
        }
    }

    static void runAll() throws Exception {
        testSpake2();
        testHandshake();
        testRegistryStoreLimiter();
        testGeometryAndControl();
        testFrameQueueV2();
        testDiscoveryLoopback();
    }
}
