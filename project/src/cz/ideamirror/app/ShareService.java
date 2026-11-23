package cz.ideamirror.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Presenter (tablet) screen + audio sharing service.
 * Capture (MediaProjection, VirtualDisplay, encoders) is set up once; the network link is managed separately so a
 * short Wi-Fi drop reconnects automatically (v2 boards) without a new screen-capture consent. Tablet rotation
 * re-creates the encoder at the new size and tells the board (fmt control packet).
 */
public class ShareService extends Service {
    static final int QUALITY_FAST = 0, QUALITY_BALANCED = 1, QUALITY_SHARP = 2, QUALITY_ULTRA = 3;
    static final String[] QUALITY_LABELS = {
        "Rychlá odezva (960p · 3 Mb/s)",
        "Vyvážený (1280p · 5 Mb/s)",
        "Vysoká ostrost (1600p · 8 Mb/s)",
        "Ultra ostrost (1920p · 12 Mb/s)"
    };

    private static final int AUDIO_SAMPLE_RATE = 48000, AUDIO_CHANNELS = 2, AUDIO_BITRATE = 128_000;
    private static final long RECONNECT_WINDOW_MS = 30_000L;

    static volatile String status = "Připraveno k připojení.";
    static volatile boolean active = false;
    static volatile boolean paused = false;
    static volatile boolean reconnecting = false;
    static volatile String targetName = "";
    static volatile int audioVolumePercent = 70;
    private static volatile ShareService instance;
    private static volatile String diagPhase = "nečinný", diagEncoder = "-", diagAudio = "vypnuto", diagError = "-", diagTarget = "-";
    static volatile long diagFrames = 0, diagAudioFrames = 0, diagDrops = 0;
    static volatile int diagBitrateKbps = 0, diagFps = 0, diagAvgWriteMs = 0;

    private volatile boolean running = false;
    private volatile int sessionId = 0;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    // capture state (worker thread)
    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile MediaCodec encoder;
    private Surface inputSurface;
    private volatile int curW, curH, dpi;
    private int maxSide, targetFps, targetBitrate, minBitrate, currentBitrate;
    private volatile boolean geometryChanged = false;
    private volatile boolean syncRequested = false;
    private volatile byte[] lastVideoCsd, lastAudioCsd;
    private volatile ClientLink.Link link;
    private DisplayManager.DisplayListener displayListener;

    static String diagnostics() {
        return "Stav: " + status
            + "\nCíl: " + diagTarget + (targetName.isEmpty() ? "" : " (" + targetName + ")")
            + "\nFáze: " + diagPhase
            + "\nKodér: " + diagEncoder
            + "\nZvuk: " + diagAudio + " (" + diagAudioFrames + " rámců · hlasitost " + audioVolumePercent + " %)"
            + "\nBitrate / FPS: " + String.format(Locale.ROOT, "%.1f Mb/s · %d fps · zápis ~%d ms", diagBitrateKbps / 1000.0, diagFps, diagAvgWriteMs)
            + "\nOdeslané / přeskočené snímky: " + diagFrames + " / " + diagDrops
            + (paused ? "\nPozastaveno" : "")
            + "\nPoslední chyba: " + diagError;
    }

    static String liveStats() {
        return String.format(Locale.ROOT, "%d fps · %.1f Mb/s · přeskočeno %d", diagFps, diagBitrateKbps / 1000.0, diagDrops);
    }

    /** Freezes the board on the last frame (board shows "pozastaveno"); audio keeps playing. */
    static void setPaused(boolean p) {
        ShareService s = instance;
        paused = p;
        if (s == null) return;
        ClientLink.Link l = s.link;
        if (l != null && l.v2) l.queue.offer(Proto.control(p ? Proto.CTL_PAUSE : Proto.CTL_RESUME), SystemClock.elapsedRealtime());
        if (!p) {
            if (l != null) l.queue.requireKeyFrame();
            s.syncRequested = true;
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if ("stop".equals(i == null ? null : i.getAction())) {
            status = "Vysílání ukončeno.";
            diagPhase = "ukončeno uživatelem";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i == null) { stopSelf(id); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            boolean wantAudio = i.getBooleanExtra("audio", true);
            audioVolumePercent = AudioUtil.clampPercent(i.getIntExtra("audioVolume", audioVolumePercent));
            boolean canCaptureAudio = wantAudio && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("sharing", "Sdílení obrazovky", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ShareService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "sharing")
                .setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle("Tabule Link — sdílení obrazovky")
                .setContentText(canCaptureAudio ? "Obraz i zvuk se přenáší na tabuli." : "Obraz se přenáší na tabuli.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Ukončit", stop).build())
                .build();
            if (Build.VERSION.SDK_INT >= 30 && canCaptureAudio) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, n);
            }
            instance = this;
            sessionId = id;
            running = true;
            active = true;
            paused = false;
            reconnecting = false;
            diagFrames = 0; diagAudioFrames = 0; diagDrops = 0; diagFps = 0; diagAvgWriteMs = 0; diagBitrateKbps = 0;
            diagError = "-"; diagEncoder = "-";
            diagAudio = canCaptureAudio ? "spouštím" : (wantAudio ? "nedostupné (vyžaduje Android 10+ a oprávnění)" : "vypnuto uživatelem");
            diagPhase = "spouštění služby";
            status = "Připojuji se k tabuli…";
            wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabuleLink:sharing");
            wake.acquire(8 * 60 * 60 * 1000L);
            acquireWifiLock();
            final boolean captureAudio = canCaptureAudio;
            worker = new Thread(() -> run(i, id, captureAudio), "tabule-send");
            worker.start();
        } catch (Exception e) {
            diagError = Ui.problem(e);
            diagPhase = "chyba spuštění služby";
            status = "Chyba spuštění: " + diagError;
            active = false;
            running = false;
            stopSelf(id);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "TabuleLink:sharing");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private int[] qualityProfile(int quality) {
        if (quality == QUALITY_FAST) return new int[]{960, 30, 3_000_000};
        if (quality == QUALITY_SHARP) return new int[]{1600, 30, 8_000_000};
        if (quality == QUALITY_ULTRA) return new int[]{1920, 30, 12_000_000};
        return new int[]{1280, 30, 5_000_000};
    }

    /** {rawW, rawH, dpi} of the current (possibly rotated) display. */
    @SuppressWarnings("deprecation")
    private int[] rawScreen() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height(), getResources().getConfiguration().densityDpi};
        }
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels, metrics.densityDpi};
    }

    private static int[] chooseBaselineProfileLevel(MediaCodec codec) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (caps == null || caps.profileLevels == null) return null;
            int bestConstrained = 0, bestBaseline = 0;
            int constrainedProfile = Build.VERSION.SDK_INT >= 27 ? MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline : -1;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == constrainedProfile && pl.level > bestConstrained) bestConstrained = pl.level;
                else if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline && pl.level > bestBaseline) bestBaseline = pl.level;
            }
            if (bestConstrained > 0) return new int[]{constrainedProfile, Math.min(bestConstrained, MediaCodecInfo.CodecProfileLevel.AVCLevel51)};
            if (bestBaseline > 0) return new int[]{MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, Math.min(bestBaseline, MediaCodecInfo.CodecProfileLevel.AVCLevel51)};
        } catch (Exception ignored) {}
        return null;
    }

    private MediaCodec createEncoder(int width, int height, int fps, int bitrate) throws IOException {
        Exception firstErr = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                if (attempt <= 2) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
                    if (Build.VERSION.SDK_INT >= 29) {
                        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) fps);
                        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    }
                }
                if (attempt <= 1) {
                    int[] pl = chooseBaselineProfileLevel(codec);
                    if (pl != null) {
                        format.setInteger(MediaFormat.KEY_PROFILE, pl[0]);
                        format.setInteger(MediaFormat.KEY_LEVEL, pl[1]);
                    } else if (attempt == 0) {
                        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                    }
                }
                if (attempt == 0) {
                    format.setInteger(MediaFormat.KEY_LATENCY, 1);
                    if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    format.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(60, fps * 2));
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                String mode = attempt == 0 ? "Baseline+LowLat" : (attempt == 1 ? "Baseline" : (attempt == 2 ? "Default+NoB" : "Minimal"));
                diagEncoder = codec.getName() + " (" + mode + ", " + width + "x" + height + "@" + fps + ")";
                return codec;
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
                if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            }
        }
        throw new IOException("Nepodařilo se spustit H.264 kodér: " + Ui.problem(firstErr), firstErr);
    }

    private static byte[] extractCsd(MediaFormat format) {
        ByteBuffer csd0 = format.getByteBuffer("csd-0");
        ByteBuffer csd1 = format.getByteBuffer("csd-1");
        if (csd0 == null && csd1 == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ByteBuffer b : new ByteBuffer[]{csd0, csd1}) {
            if (b == null) continue;
            ByteBuffer dup = b.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            out.write(bytes, 0, bytes.length);
        }
        byte[] all = out.toByteArray();
        return all.length > 0 ? all : null;
    }

    private static void requestSyncFrame(MediaCodec encoder) {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(b);
        } catch (Exception ignored) {}
    }

    private static void updateBitrate(MediaCodec encoder, int bitrate) {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            encoder.setParameters(b);
            diagBitrateKbps = bitrate / 1000;
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static Intent getCaptureIntent(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra("capture", Intent.class);
        return i.getParcelableExtra("capture");
    }

    // ------------------------------------------------------------------ main worker

    private void run(Intent i, int startId, boolean captureAudio) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        Thread linkThread = null, audioThread = null;
        try {
            String fp = i.getStringExtra("board_fp");
            BoardStore.Board board = fp == null ? null : ClientLink.load(this).find(fp);
            if (board == null) throw new IOException("Vybraná tabule není uložena. Spárujte ji znovu.");
            diagTarget = board.address();
            targetName = board.name;
            int[] prof = qualityProfile(i.getIntExtra("quality", QUALITY_BALANCED));
            maxSide = prof[0]; targetFps = prof[1]; targetBitrate = prof[2];
            minBitrate = Math.max(1_200_000, targetBitrate / 3);
            currentBitrate = targetBitrate;
            diagBitrateKbps = currentBitrate / 1000;

            diagPhase = "konfigurace H.264 kodéru";
            int[] raw = rawScreen();
            int[] geom = VideoGeometry.fit(raw[0], raw[1], maxSide);
            curW = geom[0]; curH = geom[1]; dpi = Math.max(120, raw[2]);
            encoder = createEncoder(curW, curH, targetFps, currentBitrate);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            diagPhase = "získání MediaProjection";
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(i.getIntExtra("result", Activity.RESULT_CANCELED), getCaptureIntent(i));
            if (projection == null) throw new IOException("Sdílení obrazovky nebylo povoleno.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (running) {
                        diagPhase = "ukončeno systémem";
                        status = "Sdílení ukončil systém nebo uživatel.";
                        stopSelf(startId);
                    }
                }
            }, main);
            diagPhase = "vytvoření VirtualDisplay";
            display = projection.createVirtualDisplay("Tabule Link", curW, curH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, main);
            registerRotationListener();

            final BoardStore.Board target = board;
            linkThread = new Thread(() -> manageLink(target, startId), "tabule-link");
            linkThread.start();
            if (captureAudio) {
                final MediaProjection p = projection;
                audioThread = new Thread(() -> runAudioCapture(p), "tabule-audio");
                audioThread.start();
            }
            pumpVideo();
        } catch (Exception e) {
            if (running) {
                diagError = Ui.problem(e);
                status = "Sdílení se nepodařilo (" + diagPhase + "): " + diagError;
            }
        } finally {
            running = false;
            ClientLink.Link l = link;
            if (l != null) sayBye(l);
            if (linkThread != null) { linkThread.interrupt(); try { linkThread.join(1500); } catch (InterruptedException ignored) {} }
            if (audioThread != null) { audioThread.interrupt(); try { audioThread.join(800); } catch (InterruptedException ignored) {} }
            unregisterRotationListener();
            if (display != null) try { display.release(); } catch (Exception ignored) {}
            if (projection != null) try { projection.stop(); } catch (Exception ignored) {}
            MediaCodec enc = encoder;
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) {} try { enc.release(); } catch (Exception ignored) {} }
            if (inputSurface != null) try { inputSurface.release(); } catch (Exception ignored) {}
            if (sessionId == startId) { active = false; reconnecting = false; paused = false; }
            stopSelf(startId);
        }
    }

    private void sayBye(ClientLink.Link l) {
        if (l.v2 && !l.dead) {
            try {
                l.queue.offer(Proto.control(Proto.CTL_BYE), SystemClock.elapsedRealtime());
                Thread.sleep(150);
            } catch (Exception ignored) {}
        }
        l.close();
    }

    private void offer(Packet p) {
        ClientLink.Link l = link;
        if (l == null || l.dead) return;
        boolean needSync = l.queue.offer(p, SystemClock.elapsedRealtime());
        if (needSync && !Packet.isAudio(p.flags)) onCongestion();
    }

    private long lastCongestionMs = 0, lastBitrateAdjustMs = 0;

    private void onCongestion() {
        long now = SystemClock.elapsedRealtime();
        syncRequested = true;
        lastCongestionMs = now;
        if (currentBitrate > minBitrate && now - lastBitrateAdjustMs > 1500) {
            currentBitrate = Math.max(minBitrate, (int) (currentBitrate * 0.75));
            updateBitrate(encoder, currentBitrate);
            lastBitrateAdjustMs = now;
        }
    }

    private void maybeRaiseBitrate(long now) {
        if (currentBitrate < targetBitrate && now - lastCongestionMs > 6000 && now - lastBitrateAdjustMs > 3000) {
            currentBitrate = Math.min(targetBitrate, (int) (currentBitrate * 1.15));
            updateBitrate(encoder, currentBitrate);
            lastBitrateAdjustMs = now;
        }
    }

    private void pumpVideo() throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        lastBitrateAdjustMs = SystemClock.elapsedRealtime();
        diagPhase = "vysílání";
        while (running) {
            if (geometryChanged) { geometryChanged = false; handleRotation(); }
            MediaCodec enc = encoder;
            if (syncRequested) { syncRequested = false; requestSyncFrame(enc); }
            int index = enc.dequeueOutputBuffer(info, 10_000);
            long now = SystemClock.elapsedRealtime();
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                byte[] csd = extractCsd(enc.getOutputFormat());
                if (csd != null && csd.length <= Wire.MAX_FRAME) {
                    lastVideoCsd = csd;
                    offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0L, csd));
                }
                continue;
            }
            if (index < 0) { maybeRaiseBitrate(now); continue; }
            try {
                if (info.size > 0 && info.size <= Wire.MAX_FRAME) {
                    ByteBuffer buffer = enc.getOutputBuffer(index);
                    if (buffer != null) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buffer.get(data);
                        int flags = Packet.sanitizeFlags(info.flags);
                        if (Packet.isConfig(flags)) {
                            lastVideoCsd = data;
                            offer(new Packet(flags, info.presentationTimeUs, data));
                        } else if (!paused) {
                            offer(new Packet(flags, info.presentationTimeUs, data));
                            maybeRaiseBitrate(now);
                        }
                    }
                }
            } finally {
                enc.releaseOutputBuffer(index, false);
            }
        }
    }

    // ------------------------------------------------------------------ rotation

    private void registerRotationListener() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {}
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) geometryChanged = true; }
            };
            dm.registerDisplayListener(displayListener, main);
        } catch (Exception ignored) {}
    }

    private void unregisterRotationListener() {
        try {
            if (displayListener != null) ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {}
    }

    /** Re-creates the encoder at the rotated size (v2 boards only; 0.6 boards keep the letter-boxed image). */
    private void handleRotation() {
        ClientLink.Link l = link;
        if (l == null || !l.v2) return;
        int[] raw = rawScreen();
        int[] g = VideoGeometry.fit(raw[0], raw[1], maxSide);
        if (g[0] == curW && g[1] == curH) return;
        try {
            MediaCodec next = createEncoder(g[0], g[1], targetFps, currentBitrate);
            Surface nextSurface = next.createInputSurface();
            next.start();
            l.queue.clearVideo();
            l.queue.offer(Proto.control(Proto.fmt(g[0], g[1])), SystemClock.elapsedRealtime());
            display.resize(g[0], g[1], dpi);
            display.setSurface(nextSurface);
            MediaCodec old = encoder;
            Surface oldSurface = inputSurface;
            encoder = next;
            inputSurface = nextSurface;
            curW = g[0];
            curH = g[1];
            lastVideoCsd = null;
            try { old.stop(); } catch (Exception ignored) {}
            try { old.release(); } catch (Exception ignored) {}
            try { oldSurface.release(); } catch (Exception ignored) {}
            status = "Vysílám na " + targetName + " · " + curW + " × " + curH + " · šifrováno";
        } catch (Exception e) {
            diagError = "Otočení: " + Ui.problem(e);
        }
    }

    // ------------------------------------------------------------------ network link (auto-reconnect)

    private void manageLink(BoardStore.Board board, int startId) {
        boolean resume = false;
        long reconnectUntil = 0;
        int attempt = 0;
        while (running) {
            ClientLink.Link l;
            try {
                diagPhase = resume ? "obnovuji spojení" : "připojení";
                l = ClientLink.connect(this, board, resume, curW, curH,
                    () -> status = "Čekám na schválení převzetí na tabuli…",
                    phase -> { diagPhase = phase; if (!reconnecting) status = phase + "…"; });
            } catch (Proto.ProtoException e) {
                fail(e.getMessage());
                return;
            } catch (Exception e) {
                if (!running) return;
                if (resume && SystemClock.elapsedRealtime() < reconnectUntil) {
                    attempt++;
                    status = "Spojení s tabulí přerušeno — obnovuji (pokus " + attempt + ")…";
                    sleep(Math.min(3000, 700L * attempt));
                    continue;
                }
                String hint = Wire.subnetMismatchHint(this, board.host);
                fail("Připojení k tabuli se nezdařilo: " + Ui.problem(e)
                    + (hint != null ? "\n" + hint : "\nZkontrolujte stejnou síť a že Tabule Link na tabuli přijímá."));
                return;
            }
            diagTarget = board.address();
            targetName = l.boardName;
            if (lastVideoCsd != null) l.queue.offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0L, lastVideoCsd), SystemClock.elapsedRealtime());
            if (lastAudioCsd != null) l.queue.offer(new Packet(Packet.FLAG_AUDIO | Packet.FLAG_CODEC_CONFIG, 0L, lastAudioCsd), SystemClock.elapsedRealtime());
            if (paused && l.v2) l.queue.offer(Proto.control(Proto.CTL_PAUSE), SystemClock.elapsedRealtime());
            l.queue.requireKeyFrame();
            link = l;
            syncRequested = true;
            reconnecting = false;
            attempt = 0;
            status = "Vysílám na " + l.boardName + " · " + curW + " × " + curH + (l.v2 ? "" : " · starší tabule") + " · šifrováno";
            Thread writer = new Thread(() -> writeLoop(l), "tabule-net");
            writer.start();
            Thread reader = l.v2 ? new Thread(() -> readLoop(l), "tabule-ctl") : null;
            if (reader != null) reader.start();
            while (running && !l.dead) {
                sleep(300);
                long since = l.writingSince;
                if (since > 0 && SystemClock.elapsedRealtime() - since > 6000) {
                    diagError = "Zápis do sítě se zasekl (>6 s)";
                    l.close();
                }
            }
            if (!running) { sayBye(l); link = null; return; }
            l.close();
            link = null;
            try { writer.join(1000); } catch (InterruptedException ignored) {}
            String notice = l.endNotice;
            if (Proto.CTL_END_TAKEOVER.equals(notice)) { fail("Sdílení převzal jiný prezentující."); return; }
            if (Proto.CTL_END_CLOSED.equals(notice)) { fail("Prezentaci ukončila tabule."); return; }
            if (Proto.CTL_END_REVOKED.equals(notice)) { fail("Tabule toto zařízení odvolala. Spárujte jej znovu."); return; }
            if (!l.v2) { fail("Vysílání ukončeno (tabule příjem zavřela nebo sdílení převzal jiný uživatel)."); return; }
            resume = true;
            reconnecting = true;
            reconnectUntil = SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MS;
            status = "Spojení s tabulí přerušeno — obnovuji…";
        }
    }

    private void fail(String msg) {
        if (!running) return;
        status = msg;
        diagError = msg;
        running = false;
        main.post(() -> stopSelf(sessionId));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void writeLoop(ClientLink.Link l) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        byte[] scratch = new byte[256 * 1024];
        long lastWrite = SystemClock.elapsedRealtime();
        long writeAccum = 0, writes = 0, fpsStart = lastWrite, fpsFrames = 0;
        try {
            while (!l.dead) {
                Packet pkt = l.queue.poll(1500);
                long now = SystemClock.elapsedRealtime();
                if (pkt == null) {
                    if (now - lastWrite >= 1800) {
                        l.writingSince = now;
                        Packet.writeHeartbeatRecord(l.out);
                        l.writingSince = 0;
                        lastWrite = SystemClock.elapsedRealtime();
                    }
                    continue;
                }
                if (Packet.isControl(pkt.flags) && !l.v2) continue;
                l.writingSince = now;
                scratch = Packet.writeSingleRecord(l.out, scratch, pkt.flags, pkt.pts, pkt.data);
                l.writingSince = 0;
                lastWrite = SystemClock.elapsedRealtime();
                writeAccum += lastWrite - now;
                writes++;
                if (!Packet.isAudio(pkt.flags) && !Packet.isControl(pkt.flags) && !Packet.isConfig(pkt.flags)) { diagFrames++; fpsFrames++; }
                diagDrops = l.queue.droppedFrames();
                if (lastWrite - fpsStart >= 1000) {
                    diagFps = (int) Math.round(fpsFrames * 1000.0 / Math.max(1, lastWrite - fpsStart));
                    diagAvgWriteMs = (int) (writeAccum / Math.max(1, writes));
                    fpsStart = lastWrite; fpsFrames = 0; writeAccum = 0; writes = 0;
                }
            }
        } catch (Exception e) {
            if (running) diagError = "Síť: " + Ui.problem(e);
        } finally {
            l.writingSince = 0;
            l.close();
        }
    }

    /** v2 back-channel: end notices and key-frame requests from the board (touch is not supported on Android). */
    private void readLoop(ClientLink.Link l) {
        try {
            while (!l.dead) {
                Packet p = Packet.read(l.in);
                if (!Packet.isControl(p.flags)) continue;
                String cmd = Proto.controlText(p);
                if (Proto.CTL_KEYFRAME.equals(cmd)) {
                    syncRequested = true;
                } else if (cmd.startsWith("end:")) {
                    l.endNotice = cmd;
                    l.close();
                }
            }
        } catch (Exception ignored) {
        } finally {
            l.close();
        }
    }

    // ------------------------------------------------------------------ audio

    private void runAudioCapture(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < 29) return;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioRecord recorder = null;
        MediaCodec aac = null;
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(Math.max(minBuf * 2, 4096 * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord se nepodařilo inicializovat.");

            MediaFormat encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
            recorder.startRecording();
            diagAudio = "AAC-LC 48 kHz stereo · " + aac.getName();

            byte[] pcm = new byte[4096];
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
            long totalSamples = 0;
            while (running) {
                int read = recorder.read(pcm, 0, pcm.length);
                if (read <= 0) { if (read < 0) break; continue; }
                AudioUtil.applyGainPcm16Le(pcm, read, audioVolumePercent);
                int inIdx = aac.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = aac.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        int toCopy = Math.min(read, inBuf.remaining());
                        inBuf.put(pcm, 0, toCopy);
                        long ptsUs = (totalSamples * 1_000_000L) / AUDIO_SAMPLE_RATE;
                        totalSamples += toCopy / 4;
                        aac.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0);
                    }
                }
                while (running) {
                    int outIdx = aac.dequeueOutputBuffer(aInfo, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        byte[] csd = extractCsd(aac.getOutputFormat());
                        if (csd != null) {
                            lastAudioCsd = csd;
                            offer(new Packet(Packet.FLAG_AUDIO | Packet.FLAG_CODEC_CONFIG, 0L, csd));
                        }
                        continue;
                    }
                    if (outIdx < 0) break;
                    try {
                        if (aInfo.size > 0) {
                            ByteBuffer outBuf = aac.getOutputBuffer(outIdx);
                            if (outBuf != null) {
                                outBuf.position(aInfo.offset);
                                outBuf.limit(aInfo.offset + aInfo.size);
                                byte[] data = new byte[aInfo.size];
                                outBuf.get(data);
                                boolean isCfg = (aInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                                if (isCfg) lastAudioCsd = data;
                                offer(new Packet(Packet.FLAG_AUDIO | (isCfg ? Packet.FLAG_CODEC_CONFIG : 0), aInfo.presentationTimeUs, data));
                                if (!isCfg) diagAudioFrames++;
                            }
                        }
                    } finally {
                        aac.releaseOutputBuffer(outIdx, false);
                    }
                }
            }
        } catch (Exception e) {
            diagAudio = "chyba: " + Ui.problem(e);
        } finally {
            if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} try { recorder.release(); } catch (Exception ignored) {} }
            if (aac != null) { try { aac.stop(); } catch (Exception ignored) {} try { aac.release(); } catch (Exception ignored) {} }
        }
    }

    @Override public void onDestroy() {
        running = false;
        ClientLink.Link l = link;
        if (l != null) {
            l.queue.offer(Proto.control(Proto.CTL_BYE), SystemClock.elapsedRealtime());
            main.postDelayed(l::close, 200);
        }
        if (worker != null) worker.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        active = false;
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
