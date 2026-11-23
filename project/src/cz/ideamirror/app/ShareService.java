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
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

public class ShareService extends Service {
    static final int QUALITY_FAST = 0;
    static final int QUALITY_BALANCED = 1;
    static final int QUALITY_SHARP = 2;
    static final int QUALITY_ULTRA = 3;

    static final String[] QUALITY_LABELS = {
        "Rychlá odezva (960p · 3 Mb/s)",
        "Vyvážený (1280p · 5 Mb/s)",
        "Vysoká ostrost (1600p · 8 Mb/s)",
        "Ultra ostrost (1920p · 12 Mb/s)"
    };

    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_BITRATE = 128_000;

    static volatile String status = "Připraveno k připojení.";
    static volatile boolean active = false;
    static volatile int audioVolumePercent = 70;
    private static volatile String diagPhase = "nečinný";
    private static volatile String diagEncoder = "-";
    private static volatile String diagAudio = "vypnuto";
    private static volatile String diagError = "-";
    private static volatile String diagTarget = "-";
    private static volatile long diagFrames = 0;
    private static volatile long diagAudioFrames = 0;
    private static volatile long diagDrops = 0;
    private static volatile int diagBitrateKbps = 0;
    private static volatile int diagFps = 0;
    private static volatile int diagAvgWriteMs = 0;

    private volatile boolean running = false;
    private volatile int currentSessionId = 0;
    private volatile SSLSocket socket;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long lastWrite;

    static String diagnostics() {
        return "Stav: " + status
            + "\nCíl: " + diagTarget
            + "\nFáze: " + diagPhase
            + "\nKodér: " + diagEncoder
            + "\nZvuk: " + diagAudio + " (" + diagAudioFrames + " rámců · hlasitost " + audioVolumePercent + " %)"
            + "\nBitrate / FPS: " + String.format(Locale.ROOT, "%.1f Mb/s · %d fps · zápis ~%d ms", diagBitrateKbps / 1000.0, diagFps, diagAvgWriteMs)
            + "\nOdeslané / přeskočené snímky: " + diagFrames + " / " + diagDrops
            + "\nPoslední chyba: " + diagError;
    }

    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (!running) return;
            if (lastWrite > 0 && SystemClock.elapsedRealtime() - lastWrite > 15000) {
                diagError = "Watchdog timeout (>15 s bez zápisu)";
                status = "Přenos se zastavil. Zkontrolujte síť a připojte se znovu.";
                stopSelf();
                return;
            }
            main.postDelayed(this, 3000);
        }
    };

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
            boolean canCaptureAudio = wantAudio
                && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("sharing", "Sdílení obrazovky", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ShareService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "sharing")
                .setSmallIcon(cz.ideamirror.app.R.drawable.ic_stat_cast)
                .setContentTitle("Tabule Link — sdílení obrazovky")
                .setContentText(canCaptureAudio ? "Obraz i zvuk tabletu se přenáší na tabuli." : "Obraz tabletu se přenáší na tabuli.")
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
            currentSessionId = id;
            running = true;
            active = true;
            lastWrite = 0;
            diagFrames = 0;
            diagAudioFrames = 0;
            diagDrops = 0;
            diagFps = 0;
            diagAvgWriteMs = 0;
            diagBitrateKbps = 0;
            diagError = "-";
            diagEncoder = "-";
            diagAudio = canCaptureAudio ? "spouštím" : (wantAudio ? "nedostupné (vyžaduje Android 10+ a oprávnění)" : "vypnuto uživatelem");
            diagPhase = "spouštění služby";
            status = "Připojuji se k tabuli…";
            wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabuleLink:sharing");
            wake.acquire(2 * 60 * 60 * 1000L);
            final int sessionId = id;
            final boolean captureAudio = canCaptureAudio;
            worker = new Thread(() -> stream(i, sessionId, captureAudio), "tabule-send");
            worker.start();
            main.post(watchdog);
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

    private int[] qualityProfile(int quality) {
        if (quality == QUALITY_FAST) return new int[]{960, 30, 3_000_000};
        if (quality == QUALITY_SHARP) return new int[]{1600, 30, 8_000_000};
        if (quality == QUALITY_ULTRA) return new int[]{1920, 30, 12_000_000};
        return new int[]{1280, 30, 5_000_000};
    }

    private int[] screenGeometry(int maxSide) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int rawW, rawH, dpi;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            rawW = bounds.width();
            rawH = bounds.height();
            dpi = getResources().getConfiguration().densityDpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            @SuppressWarnings("deprecation")
            Display display = wm.getDefaultDisplay();
            @SuppressWarnings("deprecation")
            int unused = 0;
            display.getRealMetrics(metrics);
            rawW = metrics.widthPixels;
            rawH = metrics.heightPixels;
            dpi = metrics.densityDpi;
        }
        float scale = Math.min(1f, (float) maxSide / Math.max(1, Math.max(rawW, rawH)));
        int width = Math.max(16, ((int) (rawW * scale) / 16) * 16);
        int height = Math.max(16, ((int) (rawH * scale) / 16) * 16);
        return new int[]{width, height, Math.max(120, dpi)};
    }

    private static int[] chooseBaselineProfileLevel(MediaCodec codec) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (caps == null || caps.profileLevels == null) return null;
            int bestConstrainedLevel = 0;
            int bestBaselineLevel = 0;
            int constrainedProfile = Build.VERSION.SDK_INT >= 27 ? MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline : -1;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == constrainedProfile && pl.level > bestConstrainedLevel) {
                    bestConstrainedLevel = pl.level;
                } else if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline && pl.level > bestBaselineLevel) {
                    bestBaselineLevel = pl.level;
                }
            }
            if (bestConstrainedLevel > 0) {
                return new int[]{constrainedProfile, Math.min(bestConstrainedLevel, MediaCodecInfo.CodecProfileLevel.AVCLevel51)};
            }
            if (bestBaselineLevel > 0) {
                return new int[]{MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, Math.min(bestBaselineLevel, MediaCodecInfo.CodecProfileLevel.AVCLevel51)};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private MediaCodec createAndConfigureEncoder(int width, int height, int fps, int bitrate) throws IOException {
        Exception firstErr = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

                if (attempt <= 2) {
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
                    if (Build.VERSION.SDK_INT >= 29) {
                        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, (float) fps);
                        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    }
                }
                if (attempt == 0) {
                    int[] pl = chooseBaselineProfileLevel(codec);
                    if (pl != null) {
                        format.setInteger(MediaFormat.KEY_PROFILE, pl[0]);
                        format.setInteger(MediaFormat.KEY_LEVEL, pl[1]);
                    } else {
                        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                    }
                    format.setInteger(MediaFormat.KEY_LATENCY, 1);
                    if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    format.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(60, fps * 2));
                } else if (attempt == 1) {
                    int[] pl = chooseBaselineProfileLevel(codec);
                    if (pl != null) {
                        format.setInteger(MediaFormat.KEY_PROFILE, pl[0]);
                        format.setInteger(MediaFormat.KEY_LEVEL, pl[1]);
                    }
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                String mode = attempt == 0 ? "Baseline+LowLat+CapFPS"
                    : (attempt == 1 ? "Baseline+CapFPS"
                    : (attempt == 2 ? "Default+NoB+CapFPS" : "Minimal"));
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
        if (csd0 != null) {
            ByteBuffer dup = csd0.duplicate();
            byte[] b = new byte[dup.remaining()];
            dup.get(b);
            out.write(b, 0, b.length);
        }
        if (csd1 != null) {
            ByteBuffer dup = csd1.duplicate();
            byte[] b = new byte[dup.remaining()];
            dup.get(b);
            out.write(b, 0, b.length);
        }
        byte[] all = out.toByteArray();
        return all.length > 0 ? all : null;
    }

    private void runAudioCapture(MediaProjection projection, FrameQueue frameQueue) {
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
            int recBuf = Math.max(minBuf * 2, 4096 * 4);
            recorder = new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(recBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord se nepodařilo inicializovat.");
            }

            MediaFormat encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
            recorder.startRecording();
            diagAudio = "AAC-LC 48 kHz stereo · " + aac.getName();

            byte[] pcm = new byte[4096]; // 1024 stereo 16-bit samples = 21.33 ms
            MediaCodec.BufferInfo aInfo = new MediaCodec.BufferInfo();
            boolean sentAudioConfig = false;
            long totalSamples = 0;

            while (running && !frameQueue.isClosed()) {
                int read = recorder.read(pcm, 0, pcm.length);
                if (read <= 0) {
                    if (read < 0) break;
                    continue;
                }
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
                while (running && !frameQueue.isClosed()) {
                    int outIdx = aac.dequeueOutputBuffer(aInfo, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!sentAudioConfig) {
                            byte[] csd = extractCsd(aac.getOutputFormat());
                            if (csd != null) {
                                frameQueue.offer(new Packet(Packet.FLAG_AUDIO | Packet.FLAG_CODEC_CONFIG, 0L, csd), SystemClock.elapsedRealtime());
                                sentAudioConfig = true;
                            }
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
                                if (isCfg) sentAudioConfig = true;
                                int wireFlags = Packet.FLAG_AUDIO | (isCfg ? Packet.FLAG_CODEC_CONFIG : 0);
                                frameQueue.offer(new Packet(wireFlags, aInfo.presentationTimeUs, data), SystemClock.elapsedRealtime());
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
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
            }
            if (aac != null) {
                try { aac.stop(); } catch (Exception ignored) {}
                try { aac.release(); } catch (Exception ignored) {}
            }
        }
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

    private void stream(Intent i, int sessionId, boolean captureAudio) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        MediaProjection projection = null;
        VirtualDisplay display = null;
        MediaCodec encoder = null;
        Surface surface = null;
        Wire.Pair pair = null;
        FrameQueue frameQueue = new FrameQueue();
        Thread netThread = null;
        Thread audioThread = null;
        AtomicReference<Exception> netFailure = new AtomicReference<>();

        try {
            pair = new Wire.Pair(i.getStringExtra("pairing"));
            diagTarget = pair.host + ":" + pair.port;
            int quality = i.getIntExtra("quality", QUALITY_BALANCED);
            int[] prof = qualityProfile(quality);
            int maxSide = prof[0], targetFps = prof[1], targetBitrate = prof[2];
            int minBitrate = Math.max(1_200_000, targetBitrate / 3);
            int currentBitrate = targetBitrate;
            diagBitrateKbps = currentBitrate / 1000;

            socket = Wire.connect(pair, phase -> {
                diagPhase = phase;
                status = phase + "…";
            });
            if (!running) return;
            socket.setSoTimeout(0);

            diagPhase = "konfigurace H.264 kodéru";
            int[] geom = screenGeometry(maxSide);
            int width = geom[0], height = geom[1], dpi = geom[2];
            encoder = createAndConfigureEncoder(width, height, targetFps, targetBitrate);
            surface = encoder.createInputSurface();
            encoder.start();

            diagPhase = "získání MediaProjection";
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent captureIntent = getCaptureIntent(i);
            projection = manager.getMediaProjection(i.getIntExtra("result", Activity.RESULT_CANCELED), captureIntent);
            if (projection == null) throw new IOException("Sdílení obrazovky nebylo povoleno.");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (running) {
                        diagPhase = "ukončeno systémem";
                        status = "Sdílení ukončil systém nebo uživatel.";
                        stopSelf(sessionId);
                    }
                }
            }, main);

            diagPhase = "vytvoření VirtualDisplay";
            display = projection.createVirtualDisplay("Tabule Link", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main);

            diagPhase = "odesílání hlavičky videa";
            OutputStream rawOut = socket.getOutputStream();
            byte[] headerBytes = new byte[12];
            headerBytes[0] = (byte) (Wire.MAGIC >>> 24); headerBytes[1] = (byte) (Wire.MAGIC >>> 16);
            headerBytes[2] = (byte) (Wire.MAGIC >>> 8);  headerBytes[3] = (byte) Wire.MAGIC;
            headerBytes[4] = (byte) (width >>> 24);      headerBytes[5] = (byte) (width >>> 16);
            headerBytes[6] = (byte) (width >>> 8);       headerBytes[7] = (byte) width;
            headerBytes[8] = (byte) (height >>> 24);     headerBytes[9] = (byte) (height >>> 16);
            headerBytes[10] = (byte) (height >>> 8);     headerBytes[11] = (byte) height;
            rawOut.write(headerBytes);
            rawOut.flush();

            lastWrite = SystemClock.elapsedRealtime();
            diagPhase = "vysílání";
            status = "Vysílám na " + pair.host + " · " + width + " × " + height
                + (captureAudio ? " · obraz + zvuk" : " · jen obraz") + " · šifrováno";

            final OutputStream netOut = rawOut;
            netThread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
                byte[] scratch = new byte[128 * 1024];
                long writeAccumMs = 0, writeCount = 0;
                long fpsWindowStart = SystemClock.elapsedRealtime();
                long fpsWindowFrames = 0;
                try {
                    while (running && !frameQueue.isClosed()) {
                        Packet pkt = frameQueue.poll(1500);
                        long now = SystemClock.elapsedRealtime();
                        if (pkt == null) {
                            if (running && now - lastWrite >= 1800) {
                                Packet.writeHeartbeatRecord(netOut);
                                lastWrite = SystemClock.elapsedRealtime();
                            }
                            continue;
                        }
                        long t0 = SystemClock.elapsedRealtime();
                        scratch = Packet.writeSingleRecord(netOut, scratch, pkt.flags, pkt.pts, pkt.data);
                        long dt = SystemClock.elapsedRealtime() - t0;
                        lastWrite = SystemClock.elapsedRealtime();
                        if (!Packet.isAudio(pkt.flags)) {
                            diagFrames++;
                            fpsWindowFrames++;
                        }
                        diagDrops = frameQueue.droppedFrames();
                        writeAccumMs += dt;
                        writeCount++;
                        if (lastWrite - fpsWindowStart >= 1000) {
                            diagFps = (int) Math.round(fpsWindowFrames * 1000.0 / Math.max(1, lastWrite - fpsWindowStart));
                            diagAvgWriteMs = (int) (writeAccumMs / Math.max(1, writeCount));
                            fpsWindowStart = lastWrite;
                            fpsWindowFrames = 0;
                            writeAccumMs = 0;
                            writeCount = 0;
                        }
                    }
                } catch (Exception e) {
                    if (running) netFailure.set(e);
                } finally {
                    frameQueue.close();
                }
            }, "tabule-net");
            netThread.start();

            if (captureAudio) {
                final MediaProjection projRef = projection;
                audioThread = new Thread(() -> runAudioCapture(projRef, frameQueue), "tabule-audio");
                audioThread.start();
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sentConfig = false;
            long lastCongestionMs = 0;
            long lastBitrateAdjustMs = SystemClock.elapsedRealtime();

            while (running && netFailure.get() == null) {
                int index = encoder.dequeueOutputBuffer(info, 10000);
                long now = SystemClock.elapsedRealtime();
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!sentConfig) {
                        byte[] csd = extractCsd(encoder.getOutputFormat());
                        if (csd != null && csd.length <= Wire.MAX_FRAME) {
                            frameQueue.offer(new Packet(Packet.FLAG_CODEC_CONFIG, 0L, csd), now);
                            sentConfig = true;
                        }
                    }
                    continue;
                }
                if (index < 0) {
                    if (currentBitrate < targetBitrate && now - lastCongestionMs > 6000 && now - lastBitrateAdjustMs > 3000) {
                        currentBitrate = Math.min(targetBitrate, (int) (currentBitrate * 1.15));
                        updateBitrate(encoder, currentBitrate);
                        lastBitrateAdjustMs = now;
                    }
                    continue;
                }
                try {
                    if (info.size > Wire.MAX_FRAME) throw new IOException("Příliš velký obrazový snímek (" + info.size + " B).");
                    if (info.size > 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] data = new byte[info.size];
                            buffer.get(data);
                            int outFlags = Packet.sanitizeFlags(info.flags);
                            if ((outFlags & Packet.FLAG_CODEC_CONFIG) != 0) sentConfig = true;
                            boolean needSync = frameQueue.offer(new Packet(outFlags, info.presentationTimeUs, data), now);
                            diagDrops = frameQueue.droppedFrames();
                            if (needSync) {
                                requestSyncFrame(encoder);
                                lastCongestionMs = now;
                                if (currentBitrate > minBitrate && now - lastBitrateAdjustMs > 1500) {
                                    currentBitrate = Math.max(minBitrate, (int) (currentBitrate * 0.75));
                                    updateBitrate(encoder, currentBitrate);
                                    lastBitrateAdjustMs = now;
                                }
                            } else if (currentBitrate < targetBitrate && now - lastCongestionMs > 6000 && now - lastBitrateAdjustMs > 3000) {
                                currentBitrate = Math.min(targetBitrate, (int) (currentBitrate * 1.15));
                                updateBitrate(encoder, currentBitrate);
                                lastBitrateAdjustMs = now;
                            }
                        }
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
            }
            if (netFailure.get() != null) throw netFailure.get();
        } catch (Exception e) {
            if (running) {
                diagError = Ui.problem(e);
                if ("vysílání".equals(diagPhase) && diagFrames > 0 && (e instanceof IOException)) {
                    status = "Vysílání ukončeno (tabule příjem zavřela nebo sdílení převzal jiný uživatel).";
                } else {
                    String hint = pair != null ? Wire.subnetMismatchHint(this, pair.host) : null;
                    status = "Přenos se nezdařil (" + diagPhase + "): " + diagError
                        + (hint != null ? "\n" + hint : "\nZkontrolujte stejnou síť, otevřený příjem na tabuli a načtěte nový QR kód.");
                }
            }
        } finally {
            running = false;
            frameQueue.close();
            if (currentSessionId == sessionId) active = false;
            Wire.close(socket);
            socket = null;
            if (audioThread != null) {
                audioThread.interrupt();
                try { audioThread.join(800); } catch (InterruptedException ignored) {}
            }
            if (netThread != null) {
                netThread.interrupt();
                try { netThread.join(1000); } catch (InterruptedException ignored) {}
            }
            if (display != null) try { display.release(); } catch (Exception ignored) {}
            if (projection != null) try { projection.stop(); } catch (Exception ignored) {}
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
            }
            if (surface != null) try { surface.release(); } catch (Exception ignored) {}
            stopSelf(sessionId);
        }
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        main.removeCallbacks(watchdog);
        Wire.close(socket);
        if (worker != null) worker.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
