package cz.ideamirror.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.view.Surface;
import android.widget.Toast;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

/**
 * Always-on foreground receiver service for Huawei IdeaHub / Android display.
 * - Keeps TLS 1.2 server (port 42521) and UDP LAN discovery (port 42522) alive across sessions.
 * - Supports seamless multi-client takeover: an authenticated new presenter automatically replaces
 *   the current presenter (ClickShare / dongle button style).
 * - Can run persistently in background / after board boot and auto-launch ReceiveActivity when
 *   a presenter connects.
 */
public class ReceiverService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_START_ALWAYS_ON = "start_always_on";
    static final String ACTION_STOP = "stop";
    static final String ACTION_RESET_KEY = "reset_key";

    private static final String PREFS = "tabule_server";
    private static final String KEY_ALWAYS_ON = "always_on";
    private static final String KEY_AUTO_OPEN_BOOT = "auto_open_boot";
    private static final String KEY_RX_VOLUME = "rx_volume";
    private static final int NOTIF_ID = 2;
    private static final int INCOMING_NOTIF_ID = 3;

    interface UiListener {
        void onServerReady();
        void onStateChanged(String message, String pill);
        void onVideoStarted(int width, int height, boolean tookOver);
        void onVideoEnded(String message, String pill);
        void onDiagnosticsUpdated();
    }

    static volatile ReceiverService instance;
    static volatile boolean running = false;

    static boolean isAlwaysOnEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ALWAYS_ON, false);
    }

    static void setAlwaysOnEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ALWAYS_ON, enabled).apply();
    }

    static boolean isAutoOpenOnBootEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_OPEN_BOOT, false);
    }

    static void setAutoOpenOnBootEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_OPEN_BOOT, enabled).apply();
    }

    static int getReceiveVolume(Context c) {
        return AudioUtil.clampPercent(c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_RX_VOLUME, 100));
    }

    static void setReceiveVolume(Context c, int percent) {
        int clamped = AudioUtil.clampPercent(percent);
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_RX_VOLUME, clamped).apply();
        ReceiverService s = instance;
        if (s != null) s.applyVolume(clamped);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sessionLock = new Object();
    private final Object surfaceLock = new Object();
    private final Object pinLock = new Object();

    private volatile Wire.Server server;
    private volatile Wire.DiscoveryResponder discovery;
    private Thread acceptThread;
    private volatile boolean serverLoopRunning = false;

    private volatile Surface activeSurface;
    private volatile UiListener uiListener;

    private long sessionGeneration = 0L;
    private SSLSocket activeClient;
    private Thread activeDecodeThread;
    private volatile AudioPlayer activeAudioPlayer;
    private volatile int receiveVolumePercent = 100;
    private volatile boolean videoActive = false;

    private int failedPinAttempts = 0;
    private long pinLockoutUntilMs = 0L;

    volatile String diagPhase = "spouštění";
    volatile String diagKeySource = "-";
    volatile String diagDecoder = "-";
    volatile String diagAudio = "čekám";
    volatile String diagLastError = "-";
    volatile long diagPackets = 0, diagRendered = 0, diagSkipped = 0;
    volatile int diagFps = 0;
    volatile int videoWidth = 0, videoHeight = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String action = i == null ? ACTION_START : i.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServerAndService();
            return START_NOT_STICKY;
        }
        instance = this;
        receiveVolumePercent = getReceiveVolume(this);
        startForegroundSafe("Připraveno k příjmu (port " + (server != null ? server.socket.getLocalPort() : Wire.DEFAULT_PORT) + ")");
        running = true;

        if (ACTION_RESET_KEY.equals(action)) {
            restartServerAsync(true);
        } else if (!serverLoopRunning) {
            restartServerAsync(false);
        }
        return isAlwaysOnEnabled(this) ? START_STICKY : START_NOT_STICKY;
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
                .setSmallIcon(cz.ideamirror.app.R.drawable.ic_stat_cast)
                .setContentTitle("Tabule Link — trvalý příjem")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Zobrazit QR/PIN", open).build())
                .addAction(new Notification.Action.Builder(null, "Vypnout", stop).build())
                .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            diagLastError = Ui.problem(e);
        }
    }

    void updateNotification(String text) {
        if (!running) return;
        startForegroundSafe(text);
    }

    void applyVolume(int percent) {
        receiveVolumePercent = AudioUtil.clampPercent(percent);
        AudioPlayer ap = activeAudioPlayer;
        if (ap != null) ap.setVolumePercent(receiveVolumePercent);
    }

    void setAdvertisedIp(String ip) {
        Wire.DiscoveryResponder d = discovery;
        if (d != null) d.setAdvertisedIp(ip);
    }

    Wire.Server getServer() { return server; }
    boolean isVideoActive() { return videoActive; }

    void attachUi(UiListener listener, Surface surface) {
        synchronized (surfaceLock) {
            uiListener = listener;
            activeSurface = surface;
            surfaceLock.notifyAll();
        }
        if (server != null && listener != null) {
            listener.onServerReady();
            if (videoActive && videoWidth > 0 && videoHeight > 0) {
                listener.onVideoStarted(videoWidth, videoHeight, false);
            }
        }
    }

    void detachUi(UiListener listener) {
        boolean detached = false;
        synchronized (surfaceLock) {
            if (uiListener == listener) {
                uiListener = null;
                activeSurface = null;
                detached = true;
                surfaceLock.notifyAll();
            }
        }
        if (detached) {
            // Stop active video playback cleanly since its Surface is being destroyed,
            // while keeping the server & discovery alive if always-on mode is enabled.
            disconnectActiveClient();
        }
    }

    void disconnectActiveClient() {
        SSLSocket toClose;
        synchronized (sessionLock) {
            sessionGeneration++;
            toClose = activeClient;
            activeClient = null;
            videoActive = false;
        }
        Wire.close(toClose);
    }

    void restartServerAsync(final boolean forceNewSecret) {
        new Thread(() -> {
            stopServerInternals();
            if (!running) return;
            serverLoopRunning = true;
            acceptThread = new Thread(() -> runAcceptLoop(forceNewSecret), "tabule-accept");
            acceptThread.start();
        }, "tabule-restart").start();
    }

    private void stopServerInternals() {
        serverLoopRunning = false;
        disconnectActiveClient();
        Wire.close(discovery);
        discovery = null;
        Wire.close(server);
        server = null;
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
    }

    private void stopServerAndService() {
        running = false;
        stopServerInternals();
        if (instance == this) instance = null;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(INCOMING_NOTIF_ID);
        } catch (Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void runAcceptLoop(boolean forceNewSecret) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            diagPhase = "příprava TLS serveru";
            Wire.Server local = new Wire.Server(getApplicationContext(), forceNewSecret);
            server = local;
            diagKeySource = local.keySource;
            if (!serverLoopRunning || !running) { local.close(); return; }

            List<Wire.Endpoint> eps = Wire.endpoints(this);
            String initialIp = eps.isEmpty() ? "" : eps.get(0).ip;
            try {
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
                discovery = new Wire.DiscoveryResponder(this, deviceName, local.socket.getLocalPort(), local.fp, initialIp);
            } catch (Exception ignored) {
                discovery = null;
            }

            diagPhase = "čekám na připojení";
            updateNotification("Připraveno k příjmu (port " + local.socket.getLocalPort() + ")");
            UiListener l = uiListener;
            if (l != null) {
                l.onServerReady();
                l.onStateChanged(forceNewSecret
                    ? "Byl vygenerován nový klíč i PIN. Načtěte nový QR kód nebo zadejte nový PIN."
                    : "Načtěte QR kód tabletem, zadejte PIN na notebooku, nebo klepněte na Rychle připojit.",
                    "● Čekám na připojení");
            }

            while (serverLoopRunning && running) {
                try {
                    final SSLSocket incoming = (SSLSocket) local.socket.accept();
                    Thread authThread = new Thread(() -> handleIncomingClient(local, incoming), "tabule-client");
                    authThread.start();
                } catch (IOException e) {
                    if (!serverLoopRunning || !running) break;
                }
            }
        } catch (Exception e) {
            if (serverLoopRunning && running) {
                diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
                UiListener l = uiListener;
                if (l != null) l.onStateChanged("Chyba příjmu (" + diagPhase + "): " + Ui.problem(e), "● Chyba");
            }
        }
    }

    private void handleIncomingClient(Wire.Server local, SSLSocket incoming) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        boolean claimedSession = false;
        long myGen = -1L;
        String localPhase = "TLS 1.2 handshake";
        try {
            try { incoming.setReceiveBufferSize(128 * 1024); } catch (Exception ignored) {}
            incoming.setSoTimeout(10000);
            incoming.setTcpNoDelay(true);
            incoming.startHandshake();

            localPhase = "ověření párovacího klíče";
            DataInputStream in = new DataInputStream(new BufferedInputStream(incoming.getInputStream(), 32 * 1024));
            DataOutputStream out = new DataOutputStream(incoming.getOutputStream());
            String supplied = in.readUTF();

            if (supplied.startsWith("PIN:")) {
                long nowMs = SystemClock.elapsedRealtime();
                synchronized (pinLock) {
                    if (nowMs < pinLockoutUntilMs) {
                        throw new IOException("Příliš mnoho chybných pokusů o PIN. Počkejte 30 sekund.");
                    }
                    String suppliedPin = supplied.substring(4);
                    if (!MessageDigest.isEqual(local.pin.getBytes(StandardCharsets.US_ASCII), suppliedPin.getBytes(StandardCharsets.US_ASCII))) {
                        failedPinAttempts++;
                        if (failedPinAttempts >= 5) {
                            pinLockoutUntilMs = nowMs + 30000L;
                            failedPinAttempts = 0;
                        }
                        throw new IOException("Nesprávný 6místný PIN.");
                    }
                    failedPinAttempts = 0;
                }
                out.writeInt(Wire.MAGIC_PIN_OK);
                out.writeUTF(local.secret);
                out.writeInt(Wire.MAGIC);
                out.flush();
            } else {
                if (!MessageDigest.isEqual(local.secret.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
                    throw new IOException("Nesprávný párovací klíč.");
                }
                out.writeInt(Wire.MAGIC);
                out.flush();
            }

            localPhase = "čtení hlavičky videa";
            if (in.readInt() != Wire.MAGIC) throw new IOException("Nekompatibilní verze přenosu.");
            int width = in.readInt(), height = in.readInt();
            if (width < 16 || height < 16 || width > 2560 || height > 2560 || (long) width * height > 2560L * 1600) {
                throw new IOException("Nepodporované rozlišení (" + width + "×" + height + ").");
            }

            // Authenticated & valid stream! Seamless takeover if another presenter is currently active.
            SSLSocket previousClient;
            Thread previousWorker;
            boolean tookOver;
            synchronized (sessionLock) {
                myGen = ++sessionGeneration;
                previousClient = activeClient;
                previousWorker = activeDecodeThread;
                tookOver = (previousClient != null);
                activeClient = null;
                activeDecodeThread = null;
            }
            if (previousClient != null) {
                Wire.close(previousClient);
            }
            if (previousWorker != null && previousWorker != Thread.currentThread()) {
                try { previousWorker.join(1200); } catch (InterruptedException ignored) {}
            }

            synchronized (sessionLock) {
                if (myGen != sessionGeneration || !running || !serverLoopRunning) {
                    return; // superseded by an even newer presenter
                }
                activeClient = incoming;
                activeDecodeThread = Thread.currentThread();
                claimedSession = true;
                videoWidth = width;
                videoHeight = height;
                diagPackets = 0;
                diagRendered = 0;
                diagSkipped = 0;
                diagFps = 0;
                diagAudio = "čekám";
                diagLastError = "-";
            }

            if (tookOver) {
                main.post(() -> Toast.makeText(getApplicationContext(), "Sdílení převzal další prezentující", Toast.LENGTH_SHORT).show());
            }

            // Ensure ReceiveActivity is visible on screen (auto-wakeup in Always-On background mode!)
            Surface surface = ensureSurfaceReady(myGen);
            if (surface == null) {
                throw new IOException("Obrazovka příjmu nebyla připravena. Otevřete Tabule Link na tabuli.");
            }

            videoActive = true;
            updateNotification("Přijímám obraz (" + width + " × " + height + ")");
            receiveStream(myGen, incoming, in, surface, width, height, tookOver);
        } catch (EOFException e) {
            if (claimedSession && isCurrentSession(myGen) && running) {
                diagPhase = "ukončeno vysílačem";
                UiListener l = uiListener;
                if (l != null) l.onVideoEnded("Vysílání bylo ukončeno.\nKdokoli se může kdykoli znovu připojit.", "● Připraveno");
            }
        } catch (Exception e) {
            if (claimedSession && isCurrentSession(myGen) && running) {
                diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
                UiListener l = uiListener;
                if (l != null) l.onVideoEnded("Připojení ukončeno (" + diagPhase + "): " + Ui.problem(e), "● Odpojeno");
            } else if (!claimedSession && !videoActive && running) {
                // Report auth failure only when nobody is actively presenting
                diagLastError = Ui.problem(e) + " [" + localPhase + "]";
                UiListener l = uiListener;
                if (l != null) l.onStateChanged("Příchozí pokus odmítnut (" + localPhase + "): " + Ui.problem(e), "● Čekám na připojení");
            }
        } finally {
            Wire.close(incoming);
            if (claimedSession) {
                synchronized (sessionLock) {
                    if (myGen == sessionGeneration) {
                        activeClient = null;
                        activeDecodeThread = null;
                        videoActive = false;
                        diagPhase = "čekám na připojení";
                    }
                }
                if (isCurrentSession(myGen) && running) {
                    updateNotification("Připraveno k příjmu (port " + (server != null ? server.socket.getLocalPort() : Wire.DEFAULT_PORT) + ")");
                }
            }
        }
    }

    private boolean isCurrentSession(long gen) {
        synchronized (sessionLock) { return gen == sessionGeneration; }
    }

    private Surface ensureSurfaceReady(long myGen) {
        synchronized (surfaceLock) {
            if (activeSurface != null && activeSurface.isValid()) return activeSurface;
        }
        bringReceiveActivityToFront();
        long deadline = SystemClock.elapsedRealtime() + 6000L;
        synchronized (surfaceLock) {
            while (running && serverLoopRunning && isCurrentSession(myGen)) {
                if (activeSurface != null && activeSurface.isValid()) return activeSurface;
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                try { surfaceLock.wait(Math.min(300L, left)); } catch (InterruptedException e) { return null; }
            }
        }
        return null;
    }

    private void bringReceiveActivityToFront() {
        Intent ui = new Intent(this, ReceiveActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(ui);
        } catch (Exception ignored) {}

        // Also post a high-priority fullScreenIntent notification as fallback for Android 10–15 BAL rules
        try {
            PendingIntent pi = PendingIntent.getActivity(this, 12, ui, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "incoming_cast")
                .setSmallIcon(cz.ideamirror.app.R.drawable.ic_stat_cast)
                .setContentTitle("Tabule Link — příchozí sdílení")
                .setContentText("Klepněte pro zobrazení sdílené obrazovky.")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(6000)
                .build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(INCOMING_NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    private static String applyVendorLowLatencyKeys(MediaFormat format, String codecName) {
        String low = codecName == null ? "" : codecName.toLowerCase(Locale.ROOT);
        String mfg = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (low.contains("hisi") || low.contains("kirin") || (mfg.contains("huawei") && !low.contains("google"))) {
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
            return "hisi-ll";
        }
        if (low.contains("qti") || low.contains("qcom")) {
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            return "qti-ll";
        }
        if (low.contains("mtk")) {
            format.setInteger("vendor.low-latency.enable", 1);
            return "mtk-ll";
        }
        return "std-ll";
    }

    private MediaCodec createAndConfigureDecoder(Surface surface, int width, int height) throws IOException {
        Exception firstErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                String mode;
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setFloat(MediaFormat.KEY_OPERATING_RATE, 60.0f);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
                    if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = applyVendorLowLatencyKeys(f, codec.getName());
                } else if (attempt == 1) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = "basic-ll";
                } else {
                    mode = "default";
                }
                codec.configure(f, surface, null, 0);
                diagDecoder = codec.getName() + " (" + mode + ", " + width + "x" + height + ")";
                return codec;
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
                if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            }
        }
        throw new IOException("Nepodařilo se spustit H.264 dekodér: " + Ui.problem(firstErr), firstErr);
    }

    private void receiveStream(long myGen, SSLSocket sock, DataInputStream in, Surface surface, int width, int height, boolean tookOver) throws Exception {
        diagPhase = "konfigurace H.264 dekodéru";
        MediaCodec codec = createAndConfigureDecoder(surface, width, height);
        AudioPlayer audioPlayer = new AudioPlayer(receiveVolumePercent);
        activeAudioPlayer = audioPlayer;
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean decoding = new AtomicBoolean(true);
        Thread drain = null;
        try {
            codec.start();
            diagPhase = "přijímám obraz";
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.cancel(INCOMING_NOTIF_ID);
            } catch (Exception ignored) {}

            drain = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean first = true;
                long fpsStart = SystemClock.elapsedRealtime();
                long fpsCount = 0;
                try {
                    while (running && decoding.get() && isCurrentSession(myGen)) {
                        int latest = codec.dequeueOutputBuffer(info, 10000);
                        if (latest < 0) continue;
                        while (running && decoding.get() && isCurrentSession(myGen)) {
                            int newer = codec.dequeueOutputBuffer(info, 0);
                            if (newer >= 0) {
                                codec.releaseOutputBuffer(latest, false);
                                diagSkipped++;
                                latest = newer;
                            } else {
                                break;
                            }
                        }
                        codec.releaseOutputBuffer(latest, true);
                        diagRendered++;
                        fpsCount++;
                        long now = SystemClock.elapsedRealtime();
                        if (now - fpsStart >= 1000) {
                            diagFps = (int) Math.round(fpsCount * 1000.0 / Math.max(1, now - fpsStart));
                            diagAudio = audioPlayer.status() + " (" + audioPlayer.decodedFrames() + ")";
                            fpsStart = now;
                            fpsCount = 0;
                            UiListener ul = uiListener;
                            if (ul != null) ul.onDiagnosticsUpdated();
                        }
                        if (first) {
                            first = false;
                            UiListener ul = uiListener;
                            if (ul != null) ul.onVideoStarted(width, height, tookOver);
                        }
                    }
                } catch (Exception e) {
                    if (decoding.get() && isCurrentSession(myGen)) {
                        failure.set(e);
                        Wire.close(sock);
                    }
                }
            }, "tabule-decode");
            drain.start();

            while (running && isCurrentSession(myGen) && failure.get() == null) {
                Packet packet = Packet.read(in);
                int size = packet.data.length;
                if (size == 0) continue;
                if (Packet.isAudio(packet.flags)) {
                    audioPlayer.enqueue(packet);
                    continue;
                }
                diagPackets++;
                int flags = Packet.sanitizeFlags(packet.flags);
                long pts = packet.pts;
                byte[] data = packet.data;
                int index;
                long deadline = SystemClock.elapsedRealtime() + 5000;
                do {
                    index = codec.dequeueInputBuffer(5000);
                    if (!running || !isCurrentSession(myGen)) return;
                    if (failure.get() != null) throw failure.get();
                    if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Dekodér nereaguje.");
                } while (index < 0);
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new IOException("Dekodér neposkytl vstupní buffer.");
                buffer.clear();
                if (buffer.remaining() < size) {
                    throw new IOException("Dekodér nepodporuje velikost snímku (" + size + " > " + buffer.remaining() + " B).");
                }
                buffer.put(data);
                codec.queueInputBuffer(index, 0, size, pts, flags);
            }
            if (failure.get() != null) throw failure.get();
        } finally {
            decoding.set(false);
            if (activeAudioPlayer == audioPlayer) activeAudioPlayer = null;
            diagAudio = audioPlayer.status() + " (" + audioPlayer.decodedFrames() + ")";
            audioPlayer.close();
            if (drain != null) {
                drain.interrupt();
                try { drain.join(1500); } catch (InterruptedException ignored) {}
            }
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
    }

    @Override public void onDestroy() {
        running = false;
        stopServerInternals();
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
