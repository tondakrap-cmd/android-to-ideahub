package cz.ideamirror.app;

import android.media.*;
import android.os.Process;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Low-latency AAC-LC decoder and AudioTrack player for ReceiveActivity. Errors never abort video. */
final class AudioPlayer implements Closeable {
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int DEFAULT_CHANNELS = 2;
    private static final int MAX_QUEUE = 8;
    // Standard AudioSpecificConfig for AAC-LC, 48 kHz, stereo (2 channels)
    private static final byte[] DEFAULT_AAC_CSD = new byte[]{0x11, (byte) 0x90};

    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private volatile boolean running = true;
    private volatile int volumePercent = 100;
    private volatile AudioTrack activeTrack;
    private Thread thread;
    private volatile String status = "čekám na zvuk";
    private volatile long decodedFrames = 0;

    AudioPlayer(int initialVolumePercent) {
        this.volumePercent = AudioUtil.clampPercent(initialVolumePercent);
    }

    void setVolumePercent(int percent) {
        this.volumePercent = AudioUtil.clampPercent(percent);
        AudioTrack t = activeTrack;
        if (t != null) {
            try { t.setVolume(AudioUtil.percentToGain(this.volumePercent)); } catch (Exception ignored) {}
        }
    }

    String status() { return status; }
    long decodedFrames() { return decodedFrames; }

    synchronized void enqueue(Packet packet) {
        if (!running) return;
        if (!Packet.isConfig(packet.flags)) {
            while (queue.size() >= MAX_QUEUE) {
                Packet head = queue.peekFirst();
                if (head != null && Packet.isConfig(head.flags)) break;
                queue.pollFirst();
            }
        }
        queue.addLast(packet);
        if (thread == null) {
            thread = new Thread(this::loop, "tabule-audio");
            thread.start();
        }
        notifyAll();
    }

    private synchronized Packet nextPacket() throws InterruptedException {
        while (running && queue.isEmpty()) wait(500);
        return running ? queue.pollFirst() : null;
    }

    private static AudioTrack createTrack(int sampleRate, int channels) {
        int chMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        int targetBuf = Math.max(minBuf, sampleRate * channels * 2 * 50 / 1000); // ~50 ms
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        AudioFormat fmt = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(chMask)
            .build();
        try {
            return new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(targetBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        } catch (Exception fallback) {
            return new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(targetBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        }
    }

    private static MediaCodec createDecoder(byte[] csd) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);
        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd != null && csd.length >= 2 ? csd : DEFAULT_AAC_CSD));
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        decoder.configure(fmt, null, null, 0);
        decoder.start();
        return decoder;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        MediaCodec decoder = null;
        AudioTrack track = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcmBuf = new byte[8192];
        try {
            while (running) {
                Packet pkt = nextPacket();
                if (pkt == null) continue;
                boolean isCfg = Packet.isConfig(pkt.flags);
                if (decoder == null) {
                    byte[] csd = isCfg ? pkt.data : DEFAULT_AAC_CSD;
                    decoder = createDecoder(csd);
                    track = createTrack(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);
                    track.setVolume(AudioUtil.percentToGain(volumePercent));
                    activeTrack = track;
                    track.play();
                    status = "AAC-LC · " + decoder.getName();
                    if (isCfg) continue;
                } else if (isCfg) {
                    continue;
                }

                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    if (inBuf != null && inBuf.remaining() >= pkt.data.length) {
                        inBuf.clear();
                        inBuf.put(pkt.data);
                        decoder.queueInputBuffer(inIdx, 0, pkt.data.length, pkt.pts, 0);
                    }
                }
                while (running) {
                    int outIdx = decoder.dequeueOutputBuffer(info, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFmt = decoder.getOutputFormat();
                        int sr = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;
                        int ch = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : DEFAULT_CHANNELS;
                        if (sr != DEFAULT_SAMPLE_RATE || ch != DEFAULT_CHANNELS) {
                            if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) {} }
                            track = createTrack(sr, ch);
                            track.setVolume(AudioUtil.percentToGain(volumePercent));
                            activeTrack = track;
                            track.play();
                            status = "AAC-LC " + sr + "Hz/" + ch + "ch · " + decoder.getName();
                        }
                        continue;
                    }
                    if (outIdx < 0) break;
                    try {
                        if (info.size > 0 && track != null) {
                            ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                            if (outBuf != null) {
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                if (pcmBuf.length < info.size) pcmBuf = new byte[info.size];
                                outBuf.get(pcmBuf, 0, info.size);
                                track.write(pcmBuf, 0, info.size);
                                decodedFrames++;
                            }
                        }
                    } finally {
                        decoder.releaseOutputBuffer(outIdx, false);
                    }
                }
            }
        } catch (Exception e) {
            status = "chyba zvuku: " + Ui.problem(e);
        } finally {
            activeTrack = null;
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
        }
    }

    @Override public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            queue.clear();
            t = thread;
            notifyAll();
        }
        if (t != null) {
            t.interrupt();
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
    }
}
