package cz.ideamirror.app;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Bounded, H.264 + AAC aware low-latency frame queue between encoders and TLS network writer.
 * Never drops CODEC_CONFIG packets (video or audio) and never leaves broken H.264 P-frame chains.
 * Audio frames are kept in a separate short queue so audio never starves video or builds lag.
 */
final class FrameQueue {
    static final int SOFT_DEPTH = 3;
    static final int HARD_DEPTH = 8;
    static final int MAX_AUDIO_DEPTH = 6;
    static final long STALE_MS = 140L;
    static final long SYNC_COOLDOWN_MS = 250L;

    private static final class Entry {
        final Packet packet;
        final long enqueuedMs;
        Entry(Packet packet, long enqueuedMs) { this.packet = packet; this.enqueuedMs = enqueuedMs; }
    }

    private final ArrayDeque<Entry> videoQueue = new ArrayDeque<>();
    private final ArrayDeque<Entry> audioQueue = new ArrayDeque<>();
    private final ArrayDeque<Entry> controlQueue = new ArrayDeque<>();
    private boolean closed = false;
    private boolean awaitingKeyFrame = false;
    private long lastSyncRequestMs = 0L;
    private long droppedFrames = 0L;
    private long droppedAudio = 0L;

    synchronized boolean offer(Packet packet, long nowMs) {
        if (closed) return false;
        if (Packet.isControl(packet.flags)) {
            if (controlQueue.size() < 64) controlQueue.addLast(new Entry(packet, nowMs));
            notifyAll();
            return false;
        }
        if (Packet.isAudio(packet.flags)) {
            offerAudio(packet, nowMs);
            return false;
        }
        return offerVideo(packet, nowMs);
    }

    private void offerAudio(Packet packet, long nowMs) {
        boolean isConfig = Packet.isConfig(packet.flags);
        if (!isConfig) {
            while (nonConfigCount(audioQueue) >= MAX_AUDIO_DEPTH) {
                if (!dropOldestNonConfig(audioQueue)) break;
                droppedAudio++;
            }
        }
        audioQueue.addLast(new Entry(packet, nowMs));
        notifyAll();
    }

    private boolean offerVideo(Packet packet, long nowMs) {
        boolean isConfig = Packet.isConfig(packet.flags);
        boolean isKey = (packet.flags & Packet.FLAG_KEY_FRAME) != 0;

        if (isConfig) {
            videoQueue.addLast(new Entry(packet, nowMs));
            notifyAll();
            return false;
        }

        if (isKey) {
            awaitingKeyFrame = false;
            if (nonConfigCount(videoQueue) >= 1 && (nonConfigCount(videoQueue) >= 2 || oldestNonConfigAge(videoQueue, nowMs) >= STALE_MS)) {
                dropAllNonConfig(videoQueue);
            }
            videoQueue.addLast(new Entry(packet, nowMs));
            notifyAll();
            return false;
        }

        if (awaitingKeyFrame) {
            droppedFrames++;
            return shouldRequestSync(nowMs);
        }

        int depth = nonConfigCount(videoQueue);
        long age = oldestNonConfigAge(videoQueue, nowMs);
        if (depth >= HARD_DEPTH || age >= STALE_MS * 3) {
            dropAllNonConfig(videoQueue);
            awaitingKeyFrame = true;
            droppedFrames++;
            return shouldRequestSync(nowMs);
        }

        videoQueue.addLast(new Entry(packet, nowMs));
        notifyAll();
        return (depth + 1 >= SOFT_DEPTH || age >= STALE_MS) && shouldRequestSync(nowMs);
    }

    private boolean shouldRequestSync(long nowMs) {
        if (nowMs - lastSyncRequestMs >= SYNC_COOLDOWN_MS) {
            lastSyncRequestMs = nowMs;
            return true;
        }
        return false;
    }

    private static int nonConfigCount(ArrayDeque<Entry> q) {
        int count = 0;
        for (Entry e : q) {
            if (!Packet.isConfig(e.packet.flags)) count++;
        }
        return count;
    }

    private static long oldestNonConfigAge(ArrayDeque<Entry> q, long nowMs) {
        for (Entry e : q) {
            if (!Packet.isConfig(e.packet.flags)) return Math.max(0L, nowMs - e.enqueuedMs);
        }
        return 0L;
    }

    private static boolean dropOldestNonConfig(ArrayDeque<Entry> q) {
        Iterator<Entry> it = q.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!Packet.isConfig(e.packet.flags)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void dropAllNonConfig(ArrayDeque<Entry> q) {
        Iterator<Entry> it = q.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!Packet.isConfig(e.packet.flags)) {
                it.remove();
                droppedFrames++;
            }
        }
    }

    /** Drops queued video and waits for the next key frame (resume after pause, reconnect, surface swap). */
    synchronized void requireKeyFrame() {
        dropAllNonConfig(videoQueue);
        awaitingKeyFrame = true;
    }

    /** Drops all queued video incl. codec config (encoder is being replaced after a format change). */
    synchronized void clearVideo() {
        droppedFrames += nonConfigCount(videoQueue);
        videoQueue.clear();
        awaitingKeyFrame = false;
    }

    synchronized boolean isAwaitingKeyFrame() { return awaitingKeyFrame; }

    synchronized Packet poll(long timeoutMs) throws InterruptedException {
        if (videoQueue.isEmpty() && audioQueue.isEmpty() && controlQueue.isEmpty() && !closed && timeoutMs > 0) {
            wait(timeoutMs);
        }
        Entry c = controlQueue.pollFirst();
        if (c != null) return c.packet;
        // Prioritize any codec config first, then lightweight audio frames, then video frames.
        Entry vHead = videoQueue.peekFirst();
        if (vHead != null && Packet.isConfig(vHead.packet.flags)) {
            return videoQueue.pollFirst().packet;
        }
        Entry aHead = audioQueue.peekFirst();
        if (aHead != null) {
            return audioQueue.pollFirst().packet;
        }
        Entry v = videoQueue.pollFirst();
        return v == null ? null : v.packet;
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }

    synchronized boolean isClosed() { return closed; }
    synchronized int size() { return videoQueue.size() + audioQueue.size() + controlQueue.size(); }
    synchronized long droppedFrames() { return droppedFrames; }
    synchronized long droppedAudio() { return droppedAudio; }
}
