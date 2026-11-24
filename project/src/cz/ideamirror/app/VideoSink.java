package cz.ideamirror.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H.264 decoder for one presenter slot. It survives losing the Surface (board shows another app), resolution
 * changes of the presenter (phone/tablet rotation) and stalls of the hardware decoder:
 * <ul>
 * <li>Rotation: the decoder is configured for adaptive playback (max size = longer side), so a new resolution
 *     only needs the new SPS/PPS + key frame — no stop/release of the decoder (HiSilicon OMX on Android 9 could
 *     hang in stop(), the receive thread then stopped reading and the presenter timed out).</li>
 * <li>Watchdog: when key frames are fed but nothing is rendered for {@link #STALL_MS}, the decoder is re-created
 *     in the next, simpler mode (vendor low-latency → basic → default).</li>
 * <li>stop()/release() run on a helper thread with a timeout, so a stuck decoder never blocks the network loop.</li>
 * </ul>
 * Network reading never blocks for more than ~60 ms on the decoder, so audio keeps flowing.
 */
final class VideoSink {
    static final long STALL_MS = 2500;
    static final String[] MODE_NAMES = {"nízká latence (HiSilicon)", "základní nízká latence", "výchozí"};

    interface Host {
        /** Current surface for the slot (null when the receive screen is not visible) and its version counter. */
        Surface surfaceFor(int slot);
        int surfaceVersion(int slot);
        void onFrameShown(int slot, int width, int height);
        void requestKeyFrame(int slot);
        void onDecoderInfo(String info);
        void onRendered(long rendered, long skipped, int fps);
        /** Short human-readable event for the diagnostics log. */
        void onEvent(String event);
    }

    private final int slot;
    private final Host host;
    private MediaCodec codec;
    private int codecSurfaceVersion = -1;
    private int codecMaxSide = 0;          // >0: adaptive playback up to this size (both orientations)
    private int mode = 0;                  // index into MODE_NAMES of the next / current decoder
    private int codecMode = -1;
    private volatile int width, height;
    private byte[] lastConfig;
    private boolean awaitingKey = true;
    private volatile boolean frameReported = false;
    private Thread drain;
    private AtomicBoolean drainRunning;
    private volatile boolean codecBroken = false;
    private long lastKfRequestMs = 0;
    private long keyFedMs = 0;             // first key frame fed that has not been followed by a rendered frame
    private volatile long lastRenderMs = 0;
    private int resets = 0;
    volatile long rendered = 0, skipped = 0, dropped = 0;

    VideoSink(int slot, Host host, int width, int height) {
        this.slot = slot; this.host = host; this.width = width; this.height = height;
    }

    int width() { return width; }
    int height() { return height; }

    synchronized void onPacket(Packet p) {
        int flags = Packet.sanitizeFlags(p.flags);
        if (Packet.isConfig(flags)) {
            lastConfig = p.data;
            if (codec != null) queue(p.data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0);
            return;
        }
        ensureCodec();
        if (codec == null) { awaitingKey = true; dropped++; return; }
        boolean key = Packet.isKey(flags);
        if (awaitingKey && !key) { dropped++; requestKeyFrame(false); checkStall(); return; }
        if (key) awaitingKey = false;
        if (!queue(p.data, flags, p.pts)) {
            dropped++;
            awaitingKey = true;
            requestKeyFrame(false);
        } else if (key) {
            long now = SystemClock.elapsedRealtime();
            if (keyFedMs == 0 || lastRenderMs >= keyFedMs) keyFedMs = now;
        }
        checkStall();
    }

    /** Decoder gets key frames but renders nothing → re-create it in the next (simpler) mode. */
    private void checkStall() {
        if (codec == null || keyFedMs == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (lastRenderMs >= keyFedMs || now - keyFedMs < STALL_MS) return;
        resets++;
        int failed = codecMode;
        mode = (Math.max(0, failed) + 1) % MODE_NAMES.length;
        host.onEvent("dekodér " + (STALL_MS / 1000.0) + " s nevydal obraz (režim „" + MODE_NAMES[Math.max(0, failed)]
            + "“, " + width + "×" + height + ") → zkouším „" + MODE_NAMES[mode] + "“ (" + resets + ". pokus)");
        releaseCodec();
        keyFedMs = 0;
        awaitingKey = true;
        frameReported = false;
        requestKeyFrame(true);
    }

    /** Presenter switched resolution (fmt control): the next packets are a new config + key frame. */
    synchronized void onFormatChange(int w, int h) {
        boolean keep = codec != null && codecMaxSide > 0 && Math.max(w, h) <= codecMaxSide && !codecBroken;
        host.onEvent("změna rozlišení " + width + "×" + height + " → " + w + "×" + h
            + (keep ? " (dekodér pokračuje)" : " (nový dekodér)"));
        if (!keep) releaseCodec();
        width = w;
        height = h;
        lastConfig = null;
        awaitingKey = true;
        frameReported = false;
        keyFedMs = 0;
        if (keep) requestKeyFrame(true);
    }

    /** Called (also from the UI thread) when the slot's surface is destroyed or replaced. */
    synchronized void onSurfaceChanged() {
        releaseCodec();
        awaitingKey = true;
        frameReported = false;
        keyFedMs = 0;
    }

    synchronized void close() { releaseCodec(); }

    private void requestKeyFrame(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastKfRequestMs > 500) {
            lastKfRequestMs = now;
            host.requestKeyFrame(slot);
        }
    }

    private void ensureCodec() {
        Surface surf = host.surfaceFor(slot);
        int ver = host.surfaceVersion(slot);
        if (codec != null && (codecBroken || ver != codecSurfaceVersion || surf == null || !surf.isValid())) {
            if (codecBroken) host.onEvent("dekodér nahlásil chybu → vytvářím znovu");
            releaseCodec();
        }
        if (codec != null || surf == null || !surf.isValid()) return;
        try {
            codec = createDecoder(surf, width, height);
            codecSurfaceVersion = ver;
            codecBroken = false;
            codec.start();
            startDrain(codec);
            awaitingKey = true;
            keyFedMs = 0;
            if (lastConfig != null) queue(lastConfig, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0);
            requestKeyFrame(true);
        } catch (Exception e) {
            host.onDecoderInfo("chyba dekodéru: " + Ui.problem(e));
            host.onEvent("dekodér nelze spustit: " + Ui.problem(e));
            if (codec != null) {
                final MediaCodec broken = codec;
                codec = null;
                releaseAsync(broken);
            }
        }
    }

    private boolean queue(byte[] data, int flags, long pts) {
        try {
            int index = -1;
            for (int i = 0; i < 3 && index < 0; i++) index = codec.dequeueInputBuffer(20_000);
            if (index < 0) return false;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) return false;
            buffer.clear();
            if (buffer.remaining() < data.length) {
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                return false;
            }
            buffer.put(data);
            codec.queueInputBuffer(index, 0, data.length, pts, flags);
            return true;
        } catch (Exception e) {
            codecBroken = true;
            return false;
        }
    }

    private void startDrain(final MediaCodec c) {
        final AtomicBoolean run = new AtomicBoolean(true);
        drainRunning = run;
        drain = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long fpsStart = SystemClock.elapsedRealtime(), fpsCount = 0;
            try {
                while (run.get()) {
                    int latest = c.dequeueOutputBuffer(info, 10_000);
                    if (latest < 0) continue;
                    while (run.get()) {
                        int newer = c.dequeueOutputBuffer(info, 0);
                        if (newer < 0) break;
                        c.releaseOutputBuffer(latest, false);
                        skipped++;
                        latest = newer;
                    }
                    if (!run.get()) { c.releaseOutputBuffer(latest, false); break; }
                    c.releaseOutputBuffer(latest, true);
                    rendered++;
                    fpsCount++;
                    long now = SystemClock.elapsedRealtime();
                    lastRenderMs = now;
                    if (!frameReported) {
                        frameReported = true;
                        host.onFrameShown(slot, width, height); // current size (also after a rotation)
                    }
                    if (now - fpsStart >= 1000) {
                        host.onRendered(rendered, skipped, (int) Math.round(fpsCount * 1000.0 / Math.max(1, now - fpsStart)));
                        fpsStart = now;
                        fpsCount = 0;
                    }
                }
            } catch (Exception e) {
                if (run.get()) codecBroken = true; // recreated with the next packet
            }
        }, "tabule-decode-" + slot);
        drain.start();
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        codecMaxSide = 0;
        AtomicBoolean run = drainRunning;
        if (run != null) run.set(false);
        Thread t = drain;
        drain = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(600); } catch (InterruptedException ignored) {}
        }
        if (c != null) releaseAsync(c);
    }

    /** stop()+release() on a helper thread; if the vendor decoder hangs, the caller continues after 1.5 s. */
    private void releaseAsync(final MediaCodec c) {
        Thread r = new Thread(() -> {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }, "tabule-codec-release");
        r.setDaemon(true);
        r.start();
        try { r.join(1500); } catch (InterruptedException ignored) {}
        if (r.isAlive()) host.onEvent("uvolnění dekodéru se zaseklo — pokračuji bez čekání");
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

    private static boolean supportsAdaptive(MediaCodec c) {
        try {
            MediaCodecInfo.CodecCapabilities caps = c.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            return caps != null && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback);
        } catch (Exception e) {
            return false;
        }
    }

    /** Tries the current mode first, then the simpler ones. */
    private MediaCodec createDecoder(Surface surface, int w, int h) throws Exception {
        Exception firstErr = null;
        for (int i = 0; i < MODE_NAMES.length; i++) {
            int attempt = (mode + i) % MODE_NAMES.length;
            MediaCodec c = null;
            try {
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                String tag;
                int maxSide = 0;
                if (attempt < 2 && supportsAdaptive(c)) {
                    // adaptive playback for both orientations: rotation needs no decoder restart
                    maxSide = Math.max(w, h);
                    f.setInteger(MediaFormat.KEY_MAX_WIDTH, maxSide);
                    f.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxSide);
                }
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setFloat(MediaFormat.KEY_OPERATING_RATE, 60.0f);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Packet.MAX_SIZE);
                    if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    tag = applyVendorLowLatencyKeys(f, c.getName());
                } else if (attempt == 1) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    tag = "basic-ll";
                } else {
                    tag = "default";
                }
                c.configure(f, surface, null, 0);
                codecMaxSide = maxSide;
                codecMode = attempt;
                mode = attempt;
                host.onDecoderInfo(c.getName() + " (" + tag + (maxSide > 0 ? ", adaptivní ≤" + maxSide : "") + ", " + w + "x" + h + ")");
                return c;
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
                if (c != null) try { c.release(); } catch (Exception ignored) {}
            }
        }
        throw firstErr != null ? firstErr : new IllegalStateException("H.264 dekodér nelze spustit");
    }
}
