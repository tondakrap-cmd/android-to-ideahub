package cz.ideamirror.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H.264 decoder for one presenter slot. Unlike 0.6 it survives losing the Surface (board shows another app),
 * resolution changes of the presenter (tablet rotation) and stalls of the hardware decoder: it drops frames,
 * waits for the next key frame and asks the presenter for one instead of tearing down the connection.
 * Network reading never blocks for more than ~60 ms on the decoder, so audio keeps flowing.
 */
final class VideoSink {
    interface Host {
        /** Current surface for the slot (null when the receive screen is not visible) and its version counter. */
        Surface surfaceFor(int slot);
        int surfaceVersion(int slot);
        void onFrameShown(int slot, int width, int height);
        void requestKeyFrame(int slot);
        void onDecoderInfo(String info);
        void onRendered(long rendered, long skipped, int fps);
    }

    private final int slot;
    private final Host host;
    private MediaCodec codec;
    private int codecSurfaceVersion = -1;
    private volatile int width, height;
    private byte[] lastConfig;
    private boolean awaitingKey = true;
    private volatile boolean frameReported = false;
    private Thread drain;
    private AtomicBoolean drainRunning;
    private volatile boolean codecBroken = false;
    private long lastKfRequestMs = 0;
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
        if (awaitingKey && !key) { dropped++; requestKeyFrame(false); return; }
        if (key) awaitingKey = false;
        if (!queue(p.data, flags, p.pts)) {
            dropped++;
            awaitingKey = true;
            requestKeyFrame(false);
        }
    }

    /** Presenter switched resolution (fmt control): the next packets are a new config + key frame. */
    synchronized void onFormatChange(int w, int h) {
        releaseCodec();
        width = w;
        height = h;
        lastConfig = null;
        awaitingKey = true;
        frameReported = false;
    }

    /** Called (also from the UI thread) when the slot's surface is destroyed or replaced. */
    synchronized void onSurfaceChanged() {
        releaseCodec();
        awaitingKey = true;
        frameReported = false;
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
        if (codec != null && (codecBroken || ver != codecSurfaceVersion || surf == null || !surf.isValid())) releaseCodec();
        if (codec != null || surf == null || !surf.isValid()) return;
        try {
            codec = createDecoder(surf, width, height);
            codecSurfaceVersion = ver;
            codecBroken = false;
            codec.start();
            startDrain(codec);
            awaitingKey = true;
            if (lastConfig != null) queue(lastConfig, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0);
            requestKeyFrame(true);
        } catch (Exception e) {
            host.onDecoderInfo("chyba dekodéru: " + Ui.problem(e));
            if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            codec = null;
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
        final int w = width, h = height;
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
                    if (!frameReported) {
                        frameReported = true;
                        host.onFrameShown(slot, w, h);
                    }
                    long now = SystemClock.elapsedRealtime();
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
        AtomicBoolean run = drainRunning;
        if (run != null) run.set(false);
        Thread t = drain;
        drain = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(600); } catch (InterruptedException ignored) {}
        }
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
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

    private MediaCodec createDecoder(Surface surface, int w, int h) throws Exception {
        Exception firstErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            MediaCodec c = null;
            try {
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                String mode;
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setFloat(MediaFormat.KEY_OPERATING_RATE, 60.0f);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Packet.MAX_SIZE);
                    if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = applyVendorLowLatencyKeys(f, c.getName());
                } else if (attempt == 1) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = "basic-ll";
                } else {
                    mode = "default";
                }
                c.configure(f, surface, null, 0);
                host.onDecoderInfo(c.getName() + " (" + mode + ", " + w + "x" + h + ")");
                return c;
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
                if (c != null) try { c.release(); } catch (Exception ignored) {}
            }
        }
        throw firstErr != null ? firstErr : new IllegalStateException("H.264 dekodér nelze spustit");
    }
}
