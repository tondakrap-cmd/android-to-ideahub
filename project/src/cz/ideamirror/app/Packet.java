package cz.ideamirror.app;

import java.io.*;

/** Length-delimited H.264 video and AAC audio frames inside the authenticated TLS connection. */
final class Packet {
    static final int MAX_SIZE = 2 * 1024 * 1024;
    static final int HEADER_SIZE = 16;
    static final int FLAG_KEY_FRAME = 1;
    static final int FLAG_CODEC_CONFIG = 2;
    static final int FLAG_AUDIO = 16;
    /** v2 (0.7+): payload is a short UTF-8 control command (pause, fmt:W:H, kf, touch, end:…), never sent to v1 peers. */
    static final int FLAG_CONTROL = 32;
    static final int VALID_WIRE_FLAGS = FLAG_KEY_FRAME | FLAG_CODEC_CONFIG | 4 | FLAG_AUDIO | FLAG_CONTROL;
    private static final byte[] HEARTBEAT_BYTES = new byte[HEADER_SIZE];

    final int flags;
    final long pts;
    final byte[] data;

    Packet(int flags, long pts, byte[] data) { this.flags = flags; this.pts = pts; this.data = data; }

    /** Keeps only wire-relevant video MediaCodec flags (key frame, codec config), ignoring vendor/API34+ bits. */
    static int sanitizeFlags(int rawFlags) { return rawFlags & (FLAG_KEY_FRAME | FLAG_CODEC_CONFIG); }

    static boolean isAudio(int flags) { return (flags & FLAG_AUDIO) != 0; }
    static boolean isConfig(int flags) { return (flags & FLAG_CODEC_CONFIG) != 0; }
    static boolean isControl(int flags) { return (flags & FLAG_CONTROL) != 0; }
    static boolean isKey(int flags) { return (flags & FLAG_KEY_FRAME) != 0; }

    static Packet read(DataInputStream in) throws IOException {
        int size = in.readInt(), flags = in.readInt();
        long pts = in.readLong();
        if (size < 0 || size > MAX_SIZE || (flags & ~VALID_WIRE_FLAGS) != 0 || (size == 0 && (flags != 0 || pts != 0))) {
            throw new IOException("Neplatný přenosový snímek.");
        }
        byte[] data = new byte[size];
        in.readFully(data);
        return new Packet(flags, pts, data);
    }

    static void write(DataOutputStream out, int flags, long pts, byte[] data) throws IOException {
        if (data.length > MAX_SIZE) throw new IOException("Příliš velký snímek.");
        out.writeInt(data.length);
        out.writeInt(flags);
        out.writeLong(pts);
        out.write(data);
        out.flush();
    }

    /** Writes header + payload in a single OutputStream.write() call to minimize TLS record overhead. */
    static byte[] writeSingleRecord(OutputStream out, byte[] scratch, int flags, long pts, byte[] data) throws IOException {
        int len = data.length;
        if (len > MAX_SIZE) throw new IOException("Příliš velký snímek.");
        int total = HEADER_SIZE + len;
        if (scratch == null || scratch.length < total) {
            scratch = new byte[Math.max(total, 64 * 1024)];
        }
        scratch[0] = (byte) (len >>> 24);
        scratch[1] = (byte) (len >>> 16);
        scratch[2] = (byte) (len >>> 8);
        scratch[3] = (byte) len;
        scratch[4] = (byte) (flags >>> 24);
        scratch[5] = (byte) (flags >>> 16);
        scratch[6] = (byte) (flags >>> 8);
        scratch[7] = (byte) flags;
        scratch[8] = (byte) (pts >>> 56);
        scratch[9] = (byte) (pts >>> 48);
        scratch[10] = (byte) (pts >>> 40);
        scratch[11] = (byte) (pts >>> 32);
        scratch[12] = (byte) (pts >>> 24);
        scratch[13] = (byte) (pts >>> 16);
        scratch[14] = (byte) (pts >>> 8);
        scratch[15] = (byte) pts;
        if (len > 0) System.arraycopy(data, 0, scratch, HEADER_SIZE, len);
        out.write(scratch, 0, total);
        out.flush();
        return scratch;
    }

    static void writeHeartbeatRecord(OutputStream out) throws IOException {
        out.write(HEARTBEAT_BYTES, 0, HEADER_SIZE);
        out.flush();
    }

    static void heartbeat(DataOutputStream out) throws IOException { write(out, 0, 0, new byte[0]); }
}
