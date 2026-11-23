package cz.ideamirror.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.Surface;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;

/**
 * Receiver (board) foreground service.
 * <ul>
 * <li>All start/stop/reset requests run on one control thread → no duplicate servers at boot (0.6 bug).</li>
 * <li>Protocol v2: per-device keys, one-time QR token, SPAKE2 PIN pairing, brute-force limiter; legacy 0.6 secret.</li>
 * <li>Up to two presenter slots (split screen), optional approval of takeovers (moderator mode).</li>
 * <li>Sessions survive the receive screen being hidden; video resumes with a key-frame request.</li>
 * <li>Back-channel: takeover/close notices, key-frame requests and touch input for the presenter.</li>
 * </ul>
 */
public class ReceiverService extends Service implements Proto.ServerContext, VideoSink.Host {
    static final String ACTION_START = "start";
    static final String ACTION_START_ALWAYS_ON = "start_always_on";
    static final String ACTION_STOP = "stop";
    static final String ACTION_ROTATE_PAIRING = "rotate_pairing";
    static final String ACTION_REVOKE_ALL = "revoke_all";
    static final String ACTION_END_PRESENTATION = "end_presentation";

    static final int SLOTS = 2;
    private static final int NOTIF_ID = 2;
    private static final int INCOMING_NOTIF_ID = 3;
    private static final long APPROVAL_TIMEOUT_MS = 30_000L;

    interface UiListener {
        /** Pairing data, endpoints, session list, pause state or settings changed. */
        void onBoardChanged();
        /** First decoded frame of a slot (also after a format change) → size the view and hide the overlay. */
        void onSlotVideo(int slot, int width, int height);
        void onSlotEnded(int slot, String message);
        void onApprovalRequest(Approval a);
        void onDiagnostics();
        void onToast(String text);
    }

    /** Pending takeover approval shown on the board (moderator mode). */
    static final class Approval {
        final String deviceName;
        final String currentName;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean allowed;
        Approval(String deviceName, String currentName) { this.deviceName = deviceName; this.currentName = currentName; }
        void decide(boolean allow) { allowed = allow; latch.countDown(); }
        boolean await(long ms) throws InterruptedException { return latch.await(ms, TimeUnit.MILLISECONDS) && allowed; }
    }

    /** Snapshot of a presenter for the UI. */
    static final class SlotInfo {
        final int slot; final String name; final boolean paused, touch; final int width, height;
        SlotInfo(int slot, String name, boolean paused, boolean touch, int width, int height) {
            this.slot = slot; this.name = name; this.paused = paused; this.touch = touch; this.width = width; this.height = height;
        }
    }

    private final class Session {
        final int slot;
        final SSLSocket sock;
        final DataOutputStream out;
        final Proto.AuthResult auth;
        final String key;
        final long startedAt = SystemClock.elapsedRealtime();
        final ExecutorService writer = Executors.newSingleThreadExecutor();
        final Object writeLock = new Object();
        volatile int width, height;
        volatile boolean paused;
        volatile String endNotice;
        VideoSink sink;
        AudioPlayer audio;
        Packet audioConfig;
        boolean audioFed;
        Thread thread;

        Session(int slot, SSLSocket sock, DataOutputStream out, Proto.AuthResult auth, String remote, int w, int h) {
            this.slot = slot; this.sock = sock; this.out = out; this.auth = auth; this.width = w; this.height = h;
            this.key = auth.legacy ? "legacy:" + remote : auth.deviceId;
        }

        boolean canControl() { return !auth.legacy && auth.has(Proto.CAP_CTL); }

        void sendControl(final String cmd) {
            if (!canControl()) return;
            try {
                writer.execute(() -> {
                    try {
                        synchronized (writeLock) {
                            Packet.writeSingleRecord(out, null, Packet.FLAG_CONTROL, 0, cmd.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                        Wire.close(sock);
                    }
                });
            } catch (RejectedExecutionException ignored) {}
        }

        /** Ends the session, telling a v2 presenter why (so it does not auto-reconnect). */
        void end(String notice) {
            endNotice = notice;
            if (canControl() && notice != null) {
                sendControl(notice);
                main.postDelayed(() -> Wire.close(sock), 400);
            } else {
                Wire.close(sock);
            }
            writer.shutdown();
        }
    }

    static volatile ReceiverService instance;
    static volatile boolean running = false;

    static boolean isAlwaysOnEnabled(Context c) { return BoardSettings.isAlwaysOn(c); }
    static boolean isAutoOpenOnBootEnabled(Context c) { return BoardSettings.isAutoOpenOnBoot(c); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final AuthLimiter limiter = new AuthLimiter();
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger pendingHandshakes = new AtomicInteger();
    private BoardSettings settings;

    private volatile Wire.Server server;
    private volatile Wire.DiscoveryResponder discovery;
    private Thread acceptThread;
    private boolean serverStarted = false; // control thread only

    private final Object sessionLock = new Object();
    private final Session[] slots = new Session[SLOTS];
    private final Object surfaceLock = new Object();
    private final Surface[] surfaces = new Surface[SLOTS];
    private final int[] surfaceVersions = new int[SLOTS];
    private volatile UiListener uiListener;
    private WifiManager.WifiLock wifiLock;
    private volatile String lastPresenter = null;

    volatile String diagPhase = "spouštění";
    volatile String diagKeySource = "-";
    volatile String diagDecoder = "-";
    volatile String diagAudio = "čekám";
    volatile String diagLastError = "-";
    volatile long diagPackets = 0, diagRendered = 0, diagSkipped = 0;
    volatile int diagFps = 0;

    BoardSettings settings() { return settings; }

    @Override public void onCreate() {
        super.onCreate();
        settings = new BoardSettings(this);
        instance = this;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String action = i == null ? ACTION_START_ALWAYS_ON : i.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            control.execute(this::stopServerInternals);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        running = true;
        startForegroundSafe(idleNotificationText());
        if (ACTION_ROTATE_PAIRING.equals(action)) {
            control.execute(() -> { settings.rotateToken(); settings.rotatePin(); limiter.onPinRotated(); notifyBoard(); });
        } else if (ACTION_REVOKE_ALL.equals(action)) {
            control.execute(() -> { settings.revokeAll(); limiter.onPinRotated(); endAll(Proto.CTL_END_REVOKED); notifyBoard(); });
        } else if (ACTION_END_PRESENTATION.equals(action)) {
            endAll(Proto.CTL_END_CLOSED);
        }
        control.execute(this::ensureServer);
        return settings.alwaysOn() ? START_STICKY : START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ notifications

    private String idleNotificationText() {
        Wire.Server s = server;
        return "Připraveno k příjmu" + (s != null ? " (port " + s.socket.getLocalPort() + ")" : "");
    }

    private void startForegroundSafe(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("receiving", "Příjem obrazu (Tabule Link)", NotificationManager.IMPORTANCE_LOW));
            NotificationChannel highCh = new NotificationChannel("incoming_cast", "Příchozí sdílení obrazovky", NotificationManager.IMPORTANCE_HIGH);
            highCh.setSound(null, null);
            highCh.enableVibration(false);
            nm.createNotificationChannel(highCh);

            PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, ReceiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 11,
                new Intent(this, ReceiverService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "receiving")
                .setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle(settings.alwaysOn() ? "Tabule Link — trvalý příjem" : "Tabule Link — příjem")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Zobrazit", open).build())
                .addAction(new Notification.Action.Builder(null, "Vypnout", stop).build())
                .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            diagLastError = Ui.problem(e);
        }
    }

    private void updateNotification() {
        if (!running) return;
        List<SlotInfo> s = sessions();
        if (s.isEmpty()) { startForegroundSafe(idleNotificationText()); return; }
        StringBuilder sb = new StringBuilder("Prezentuje: ");
        for (int i = 0; i < s.size(); i++) { if (i > 0) sb.append(" + "); sb.append(s.get(i).name); }
        startForegroundSafe(sb.toString());
    }

    private void bringReceiveActivityToFront(String presenter) {
        Intent ui = new Intent(this, ReceiveActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(ui); } catch (Exception ignored) {}
        try {
            PendingIntent pi = PendingIntent.getActivity(this, 12, ui, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "incoming_cast")
                .setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle("Tabule Link — příchozí sdílení")
                .setContentText((presenter == null ? "Někdo" : presenter) + " sdílí obrazovku. Klepněte pro zobrazení.")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(15000)
                .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(INCOMING_NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    private void cancelIncomingNotification() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(INCOMING_NOTIF_ID); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ UI API

    void attachUi(UiListener l) {
        uiListener = l;
        if (l != null) l.onBoardChanged();
    }

    void detachUi(UiListener l) {
        if (uiListener == l) uiListener = null;
    }

    /** Sets (or clears with null) the surface of a slot. Sessions keep running without a surface. */
    void setSurface(int slot, Surface s) {
        Session sess;
        synchronized (surfaceLock) {
            if (surfaces[slot] == s) return;
            surfaces[slot] = s;
            surfaceVersions[slot]++;
        }
        synchronized (sessionLock) { sess = slots[slot]; }
        if (sess != null && sess.sink != null) sess.sink.onSurfaceChanged();
        if (s != null) cancelIncomingNotification();
        applyVolume(settings.rxVolume());
    }

    boolean anySurface() {
        synchronized (surfaceLock) {
            for (Surface s : surfaces) if (s != null && s.isValid()) return true;
            return false;
        }
    }

    List<SlotInfo> sessions() {
        List<SlotInfo> list = new ArrayList<>();
        synchronized (sessionLock) {
            for (Session s : slots) {
                if (s != null) list.add(new SlotInfo(s.slot, s.auth.deviceName, s.paused, touchEnabled(s), s.width, s.height));
            }
        }
        return list;
    }

    String lastPresenter() { return lastPresenter; }

    Wire.Server getServer() { return server; }

    /** QR/manual code for the chosen board address (contains the one-time token, not a permanent key). */
    String pairingUri(String ip) {
        Wire.Server s = server;
        if (s == null || ip == null) return null;
        return Wire.uri(ip, s.socket.getLocalPort(), s.fp, settings.qrToken());
    }

    void setAdvertisedIp(String ip) {
        settings.setAdvertisedIp(ip);
        Wire.DiscoveryResponder d = discovery;
        if (d != null) d.setAdvertisedIp(ip);
    }

    void onBoardNameChanged() {
        Wire.DiscoveryResponder d = discovery;
        if (d != null) d.setDeviceName(settings.boardName());
        notifyBoard();
    }

    void applyVolume(int percent) {
        int effective = anySurface() ? percent : 0; // hidden receive screen → do not play sound over other apps
        synchronized (sessionLock) {
            for (Session s : slots) if (s != null && s.audio != null) s.audio.setVolumePercent(effective);
        }
    }

    void setReceiveVolume(int percent) {
        settings.setRxVolume(percent);
        applyVolume(percent);
    }

    void endAll(String notice) {
        List<Session> list = new ArrayList<>();
        synchronized (sessionLock) { for (Session s : slots) if (s != null) list.add(s); }
        for (Session s : list) s.end(notice);
    }

    void endSlot(int slot) {
        Session s;
        synchronized (sessionLock) { s = slots[slot]; }
        if (s != null) s.end(Proto.CTL_END_CLOSED);
    }

    void revokeDevice(String id) {
        control.execute(() -> {
            settings.registry().revoke(id);
            settings.saveRegistry();
            List<Session> list = new ArrayList<>();
            synchronized (sessionLock) { for (Session s : slots) if (s != null && id.equals(s.auth.deviceId)) list.add(s); }
            for (Session s : list) s.end(Proto.CTL_END_REVOKED);
            notifyBoard();
        });
    }

    private boolean touchEnabled(Session s) {
        return settings.allowTouch() && !s.auth.legacy && s.auth.has(Proto.CAP_TOUCH);
    }

    boolean touchEnabled(int slot) {
        synchronized (sessionLock) { return slots[slot] != null && touchEnabled(slots[slot]); }
    }

    void sendTouch(int slot, char action, int x, int y) {
        Session s;
        synchronized (sessionLock) { s = slots[slot]; }
        if (s != null && touchEnabled(s)) s.sendControl(Proto.touch(action, x, y));
    }

    void applySplitSetting() {
        if (!settings.split()) {
            Session s;
            synchronized (sessionLock) { s = slots[1]; }
            if (s != null) s.end(Proto.CTL_END_CLOSED);
        }
        notifyBoard();
    }

    private void notifyBoard() {
        UiListener l = uiListener;
        if (l != null) l.onBoardChanged();
    }

    private void toast(String text) {
        UiListener l = uiListener;
        if (l != null) l.onToast(text);
        else main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------ Proto.ServerContext

    @Override public String fingerprint() { Wire.Server s = server; return s == null ? "" : s.fp; }
    @Override public String boardName() { return settings.boardName(); }
    @Override public String legacySecret() { return settings.legacySecret(); }
    @Override public String qrToken() { return settings.qrToken(); }
    @Override public String pin() { return settings.pin(); }
    @Override public DeviceRegistry registry() { return settings.registry(); }
    @Override public AuthLimiter limiter() { return limiter; }
    @Override public SecureRandom random() { return random; }
    @Override public long nowMs() { return System.currentTimeMillis(); }
    @Override public void onTokenUsed() { settings.rotateToken(); notifyBoard(); }
    @Override public void onPinRotationNeeded() { settings.rotatePin(); limiter.onPinRotated(); notifyBoard(); }
    @Override public void onRegistryChanged() { settings.saveRegistry(); }

    // ------------------------------------------------------------------ VideoSink.Host

    @Override public Surface surfaceFor(int slot) { synchronized (surfaceLock) { return surfaces[slot]; } }
    @Override public int surfaceVersion(int slot) { synchronized (surfaceLock) { return surfaceVersions[slot]; } }

    @Override public void onFrameShown(int slot, int width, int height) {
        UiListener l = uiListener;
        if (l != null) l.onSlotVideo(slot, width, height);
    }

    @Override public void requestKeyFrame(int slot) {
        Session s;
        synchronized (sessionLock) { s = slots[slot]; }
        if (s != null) s.sendControl(Proto.CTL_KEYFRAME);
    }

    @Override public void onDecoderInfo(String info) { diagDecoder = info; }

    @Override public void onRendered(long rendered, long skipped, int fps) {
        diagRendered = rendered;
        diagSkipped = skipped;
        diagFps = fps;
        UiListener l = uiListener;
        if (l != null) l.onDiagnostics();
    }

    // ------------------------------------------------------------------ server lifecycle (control thread)

    private void ensureServer() {
        if (serverStarted || !running) return;
        try {
            diagPhase = "příprava TLS serveru";
            Wire.Server local = new Wire.Server(getApplicationContext());
            if (!running) { local.close(); return; }
            server = local;
            diagKeySource = local.keySource + (local.defaultPort ? "" : " · POZOR: port 42521 obsazen, použit " + local.socket.getLocalPort());
            String ip = settings.advertisedIp();
            if (ip.isEmpty()) {
                List<Wire.Endpoint> eps = Wire.endpoints(this);
                ip = eps.isEmpty() ? "" : eps.get(0).ip;
            }
            try {
                discovery = new Wire.DiscoveryResponder(this, settings.boardName(), local.socket.getLocalPort(), local.fp, ip);
            } catch (Exception e) {
                discovery = null;
                diagLastError = "LAN vyhledávání nedostupné: " + Ui.problem(e);
            }
            serverStarted = true;
            acceptThread = new Thread(() -> acceptLoop(local), "tabule-accept");
            acceptThread.start();
            diagPhase = "čekám na připojení";
            updateNotification();
            notifyBoard();
        } catch (Exception e) {
            diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
            notifyBoard();
            main.postDelayed(() -> control.execute(this::ensureServer), 3000);
        }
    }

    private void stopServerInternals() {
        serverStarted = false;
        endAll(Proto.CTL_END_CLOSED);
        Wire.close(discovery);
        discovery = null;
        Wire.close(server);
        server = null;
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
    }

    private void acceptLoop(Wire.Server local) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        while (running && server == local) {
            try {
                final SSLSocket incoming = (SSLSocket) local.socket.accept();
                if (pendingHandshakes.incrementAndGet() > 16) {
                    pendingHandshakes.decrementAndGet();
                    Wire.close(incoming);
                    continue;
                }
                new Thread(() -> handleClient(incoming), "tabule-client").start();
            } catch (IOException e) {
                if (!running || server != local) break;
            }
        }
    }

    // ------------------------------------------------------------------ one client

    private int chooseSlot(String key) {
        if (!settings.split()) return 0;
        synchronized (sessionLock) {
            for (Session s : slots) if (s != null && s.key.equals(key)) return s.slot;
            for (int i = 0; i < SLOTS; i++) if (slots[i] == null) return i;
            return slots[0].startedAt <= slots[1].startedAt ? 0 : 1;
        }
    }

    private boolean isCurrent(Session s) {
        synchronized (sessionLock) { return slots[s.slot] == s; }
    }

    private boolean isAudioOwner(Session s) {
        synchronized (sessionLock) {
            for (Session o : slots) if (o != null && o != s && o.startedAt > s.startedAt) return false;
            return slots[s.slot] == s;
        }
    }

    private void handleClient(SSLSocket sock) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        String remote = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
        String phase = "TLS handshake";
        Session session = null;
        boolean handshakeCounted = true;
        try {
            try { sock.setReceiveBufferSize(256 * 1024); } catch (Exception ignored) {}
            sock.setSoTimeout(10000);
            sock.setTcpNoDelay(true);
            sock.startHandshake();

            phase = "ověření";
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 64 * 1024));
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            Proto.AuthResult auth = Proto.serverAuthenticate(in, out, this, remote);
            if (auth.paired) {
                toast("Spárováno nové zařízení: " + auth.deviceName);
                notifyBoard();
            }
            pendingHandshakes.decrementAndGet();
            handshakeCounted = false;

            phase = "hlavička videa";
            sock.setSoTimeout(auth.paired && auth.has(Proto.CAP_PAIR) ? 15000 : 10000);
            int magic;
            try {
                magic = in.readInt();
            } catch (EOFException pairOnly) {
                return; // pairing-only connection closed by the presenter
            }
            if (magic != Wire.MAGIC) throw new IOException("Nekompatibilní verze přenosu.");
            int width = in.readInt(), height = in.readInt();
            if (!Proto.validVideoSize(width, height)) {
                String msg = "Nepodporované rozlišení (" + width + "×" + height + ").";
                if (!auth.legacy) Proto.writeStartError(out, Proto.ERR_PROTO, msg);
                throw new IOException(msg);
            }

            String key = auth.legacy ? "legacy:" + remote : auth.deviceId;
            int slot = chooseSlot(key);
            Session current;
            synchronized (sessionLock) { current = slots[slot]; }
            boolean replacing = current != null && !current.key.equals(key);

            if (replacing && auth.has(Proto.CAP_RESUME)) {
                Proto.writeStartError(out, Proto.ERR_BUSY, "Mezitím začal prezentovat " + current.auth.deviceName + ".");
                return;
            }
            if (replacing && settings.moderator()) {
                phase = "čekám na schválení";
                if (!auth.legacy) Proto.writeWait(out);
                if (!requestApproval(auth.deviceName, current.auth.deviceName)) {
                    if (!auth.legacy) Proto.writeStartError(out, Proto.ERR_DENIED, "Převzetí obrazovky bylo na tabuli odmítnuto.");
                    return;
                }
            }

            session = new Session(slot, sock, out, auth, remote, width, height);
            session.thread = Thread.currentThread();
            Session previous;
            synchronized (sessionLock) {
                previous = slots[slot];
                slots[slot] = session;
            }
            if (previous != null) {
                previous.end(Proto.CTL_END_TAKEOVER);
                Thread pt = previous.thread;
                if (pt != null && pt != Thread.currentThread()) try { pt.join(1200); } catch (InterruptedException ignored) {}
                toast(auth.deviceName + " převzal(a) obrazovku od " + previous.auth.deviceName + ".");
            }
            if (!auth.legacy) Proto.writeGo(out);
            sock.setSoTimeout(20000);
            lastPresenter = auth.deviceName;

            acquireWifiLock();
            updateNotification();
            notifyBoard();
            if (surfaceFor(slot) == null) bringReceiveActivityToFront(auth.deviceName);

            phase = "přijímám obraz";
            diagPhase = phase;
            receive(session, in);
        } catch (EOFException e) {
            if (session != null) endedMessage(session, "Prezentace skončila.");
        } catch (Exception e) {
            if (session != null) {
                endedMessage(session, session.endNotice != null ? null : "Připojení ukončeno: " + Ui.problem(e));
            } else if (running) {
                diagLastError = Ui.problem(e) + " [" + phase + ", " + remote + "]";
                notifyBoard();
            }
        } finally {
            if (handshakeCounted) pendingHandshakes.decrementAndGet();
            if (session != null) {
                // let a pending "end:…" notice reach the presenter before closing
                session.writer.shutdown();
                try { session.writer.awaitTermination(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            }
            Wire.close(sock);
            if (session != null) {
                if (session.sink != null) session.sink.close();
                if (session.audio != null) session.audio.close();
                boolean removed = false;
                synchronized (sessionLock) {
                    if (slots[session.slot] == session) { slots[session.slot] = null; removed = true; }
                }
                if (removed) {
                    UiListener l = uiListener;
                    if (l != null) l.onSlotEnded(session.slot, pendingEndMessage(session));
                }
                releaseWifiLockIfIdle();
                updateNotification();
                notifyBoard();
            }
        }
    }

    private final Map<Session, String> endMessages = Collections.synchronizedMap(new WeakHashMap<Session, String>());

    private void endedMessage(Session s, String msg) { if (msg != null) endMessages.put(s, msg); }

    private String pendingEndMessage(Session s) {
        String m = endMessages.remove(s);
        if (Proto.CTL_END_TAKEOVER.equals(s.endNotice)) return null;
        return m != null ? m : "Prezentace skončila.";
    }

    private boolean requestApproval(String newName, String currentName) throws InterruptedException {
        Approval a = new Approval(newName, currentName);
        UiListener l = uiListener;
        if (l == null) {
            bringReceiveActivityToFront(newName);
            long until = SystemClock.elapsedRealtime() + 5000;
            while ((l = uiListener) == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
        }
        if (l == null) return false;
        l.onApprovalRequest(a);
        return a.await(APPROVAL_TIMEOUT_MS);
    }

    private void receive(Session s, DataInputStream in) throws Exception {
        s.sink = new VideoSink(s.slot, this, s.width, s.height);
        s.audio = new AudioPlayer(anySurface() ? settings.rxVolume() : 0);
        diagPackets = 0;
        while (running && isCurrent(s)) {
            Packet p = Packet.read(in);
            if (p.data.length == 0) continue; // heartbeat
            if (Packet.isControl(p.flags)) {
                if (s.auth.legacy) continue;
                if (!handleControl(s, Proto.controlText(p))) return;
                continue;
            }
            if (Packet.isAudio(p.flags)) {
                if (Packet.isConfig(p.flags)) s.audioConfig = p;
                if (!isAudioOwner(s)) { s.audioFed = false; continue; }
                if (!s.audioFed && s.audioConfig != null && p != s.audioConfig) s.audio.enqueue(s.audioConfig);
                s.audioFed = true;
                s.audio.enqueue(p);
                diagAudio = s.audio.status() + " (" + s.audio.decodedFrames() + ")";
                continue;
            }
            diagPackets++;
            if (!s.paused || Packet.isConfig(p.flags) || Packet.isKey(p.flags)) s.sink.onPacket(p);
        }
    }

    /** Returns false when the presenter said goodbye. */
    private boolean handleControl(Session s, String cmd) {
        if (Proto.CTL_PAUSE.equals(cmd)) {
            s.paused = true;
            notifyBoard();
        } else if (Proto.CTL_RESUME.equals(cmd)) {
            s.paused = false;
            notifyBoard();
        } else if (Proto.CTL_BYE.equals(cmd)) {
            s.endNotice = Proto.CTL_BYE;
            endedMessage(s, s.auth.deviceName + " ukončil(a) prezentaci.");
            return false;
        } else {
            int[] fmt = Proto.parseFmt(cmd);
            if (fmt != null) {
                s.width = fmt[0];
                s.height = fmt[1];
                s.sink.onFormatChange(fmt[0], fmt[1]);
                notifyBoard();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Wi-Fi lock

    @SuppressWarnings("deprecation")
    private synchronized void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm == null) return;
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "TabuleLink:receive");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private synchronized void releaseWifiLockIfIdle() {
        if (!sessions().isEmpty()) return;
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        running = false;
        stopServerInternals();
        control.shutdown();
        releaseWifiLockIfIdle();
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
