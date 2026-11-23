using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace TabuleLinkWin
{
    /// <summary>
    /// SPAKE2 over the RFC 3526 2048-bit MODP group — identical to Spake2.java (shared test vector).
    /// Proves knowledge of the board PIN without sending it and binds the board's TLS fingerprint.
    /// </summary>
    public static class Spake2
    {
        public const string PHex =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF";
        public const int ElementBytes = 256;
        public static readonly BigInteger P = FromHexUnsigned(PHex);
        public static readonly BigInteger Q = (P - BigInteger.One) / 2;
        public static readonly BigInteger G = new BigInteger(2);
        public static readonly BigInteger M = HashToGroup("TabuleLink SPAKE2 M v1");
        public static readonly BigInteger N = HashToGroup("TabuleLink SPAKE2 N v1");

        public sealed class Keys
        {
            public readonly byte[] SessionKey;
            public readonly string MacServer;
            public readonly string MacClient;
            public Keys(byte[] sk, string macS, string macC) { SessionKey = sk; MacServer = macS; MacClient = macC; }
        }

        public static BigInteger FromHexUnsigned(string hex)
        {
            return BigInteger.Parse("0" + hex, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
        }

        public static BigInteger FromBytesUnsigned(byte[] bigEndian)
        {
            byte[] le = new byte[bigEndian.Length + 1];
            for (int i = 0; i < bigEndian.Length; i++) le[i] = bigEndian[bigEndian.Length - 1 - i];
            return new BigInteger(le);
        }

        public static byte[] ToBytes(BigInteger v)
        {
            byte[] le = v.ToByteArray();
            byte[] outBuf = new byte[ElementBytes];
            int n = Math.Min(le.Length, ElementBytes);
            for (int i = 0; i < n; i++) outBuf[ElementBytes - 1 - i] = le[i];
            return outBuf;
        }

        public static string Hex(byte[] b)
        {
            StringBuilder sb = new StringBuilder(b.Length * 2);
            for (int i = 0; i < b.Length; i++) sb.Append(b[i].ToString("x2", CultureInfo.InvariantCulture));
            return sb.ToString();
        }

        public static byte[] Sha256(params byte[][] parts)
        {
            using (SHA256 sha = SHA256.Create())
            {
                for (int i = 0; i < parts.Length; i++) sha.TransformBlock(parts[i], 0, parts[i].Length, null, 0);
                sha.TransformFinalBlock(new byte[0], 0, 0);
                return sha.Hash;
            }
        }

        public static BigInteger HashToGroup(string label)
        {
            byte[] l = Encoding.ASCII.GetBytes(label);
            byte[] all = new byte[ElementBytes];
            for (int i = 0; i < ElementBytes / 32; i++)
            {
                byte[] block = Sha256(l, new byte[] { (byte)i });
                Buffer.BlockCopy(block, 0, all, i * 32, 32);
            }
            BigInteger v = BigInteger.Remainder(FromBytesUnsigned(all), P);
            return BigInteger.Remainder(v * v, P);
        }

        public static BigInteger PasswordScalar(string pin)
        {
            return BigInteger.Remainder(FromBytesUnsigned(Sha256(Encoding.UTF8.GetBytes("TabuleLink SPAKE2 w v1|" + pin))), Q);
        }

        public static BigInteger RandomScalar()
        {
            using (RandomNumberGenerator rng = RandomNumberGenerator.Create())
            {
                while (true)
                {
                    byte[] b = new byte[32];
                    rng.GetBytes(b);
                    BigInteger x = BigInteger.Remainder(FromBytesUnsigned(b), Q);
                    if (!x.IsZero) return x;
                }
            }
        }

        public static bool IsValidElement(BigInteger e)
        {
            return e > BigInteger.One && e < P - BigInteger.One && BigInteger.ModPow(e, Q, P).IsOne;
        }

        public static BigInteger ParseElement(string hex)
        {
            if (!Proto2.IsHex(hex, ElementBytes * 2)) throw new IOException("Neplatná zpráva párování.");
            BigInteger v = FromHexUnsigned(hex);
            if (!IsValidElement(v)) throw new IOException("Neplatná zpráva párování.");
            return v;
        }

        private static string HmacHex(byte[] key, string label)
        {
            using (HMACSHA256 h = new HMACSHA256(key))
            {
                return Hex(h.ComputeHash(Encoding.ASCII.GetBytes(label)));
            }
        }

        public static Keys DeriveKeys(string fp, BigInteger x, BigInteger y, BigInteger k)
        {
            byte[] fpBytes = Encoding.ASCII.GetBytes(fp);
            byte[] len = new byte[] { (byte)(fpBytes.Length >> 24), (byte)(fpBytes.Length >> 16), (byte)(fpBytes.Length >> 8), (byte)fpBytes.Length };
            byte[] sk = Sha256(Encoding.ASCII.GetBytes("TL2-SPAKE2"), len, fpBytes, ToBytes(x), ToBytes(y), ToBytes(k));
            return new Keys(sk, HmacHex(sk, "server-confirm"), HmacHex(sk, "client-confirm"));
        }

        public static bool MacEquals(string a, string b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }

        public sealed class Client
        {
            private readonly BigInteger x;
            private readonly BigInteger w;
            public readonly BigInteger X;

            public Client(string pin) : this(pin, RandomScalar()) { }

            public Client(string pin, BigInteger x)
            {
                this.x = x;
                w = PasswordScalar(pin);
                X = BigInteger.Remainder(BigInteger.ModPow(G, x, P) * BigInteger.ModPow(M, w, P), P);
            }

            public string MessageHex { get { return Hex(ToBytes(X)); } }

            public Keys Finish(string yHex, string fp)
            {
                BigInteger y = ParseElement(yHex);
                BigInteger nInvW = BigInteger.ModPow(N, Q - w, P);
                BigInteger k = BigInteger.ModPow(BigInteger.Remainder(y * nInvW, P), x, P);
                return DeriveKeys(fp, X, y, k);
            }
        }
    }

    public sealed class ProtoException : IOException
    {
        public readonly string Code;
        public ProtoException(string code, string message) : base(message) { Code = code; }
    }

    public sealed class OldServerException : IOException
    {
        public OldServerException() : base("Tabule používá starší verzi Tabule Link (0.6 nebo nižší).") { }
    }

    public sealed class ClientResult
    {
        public string BoardName;
        public string DeviceId;
        public string DeviceKey;
        public HashSet<string> ServerCaps = new HashSet<string>();
    }

    /// <summary>Protocol v2 client side (see Proto.java for the full specification).</summary>
    public static class Proto2
    {
        public const int MagicV1 = 0x54424c31;
        public const int MagicV2 = 0x54424c33;
        public const int MagicGo = 0x54424c34;
        public const int MagicWait = 0x54424c35;
        public const int MagicErr = 0x54424c36;
        public const int FlagControl = 32;

        public const string ErrAuth = "auth", ErrLocked = "locked", ErrBusy = "busy", ErrDenied = "denied", ErrProto = "proto";
        public const string CapCtl = "ctl", CapTouch = "touch", CapResume = "resume", CapFmt = "fmt", CapPair = "pair";
        public const string CtlPause = "pause", CtlResume = "resume", CtlBye = "bye", CtlKeyFrame = "kf";
        public const string CtlEndTakeover = "end:takeover", CtlEndClosed = "end:closed", CtlEndRevoked = "end:revoked";

        public static bool IsHex(string s, int len)
        {
            if (s == null || s.Length != len) return false;
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
            }
            return true;
        }

        public static string Sanitize(string raw, int max, string fallback)
        {
            if (raw == null) return fallback;
            StringBuilder sb = new StringBuilder(raw.Length);
            for (int i = 0; i < raw.Length; i++)
            {
                char c = raw[i];
                if (c == '|' || c < 0x20 || c == 0x7f || char.IsSurrogate(c)) { sb.Append(' '); continue; }
                sb.Append(c);
            }
            string s = sb.ToString().Trim();
            while (s.Contains("  ")) s = s.Replace("  ", " ");
            if (s.Length == 0) return fallback;
            return s.Length > max ? s.Substring(0, max).Trim() : s;
        }

        public static string DeviceName(string raw) { return Sanitize(raw, 48, "Zařízení"); }

        public static string BuildHello(string method, string deviceId, string credential, string deviceName, IEnumerable<string> caps)
        {
            return "TL2|" + method + "|" + (string.IsNullOrEmpty(deviceId) ? "-" : deviceId) + "|" + credential + "|" + DeviceName(deviceName) + "|" + string.Join(",", caps);
        }

        private static string[] ReadReply(Stream s)
        {
            int magic;
            try
            {
                magic = WireIO.ReadInt32BE(s);
            }
            catch (EndOfStreamException)
            {
                throw new OldServerException();
            }
            if (magic != MagicV2) throw new IOException("Neočekávaná odpověď tabule.");
            return WireIO.ReadJavaUtf(s).Split('|');
        }

        private static ProtoException ToError(string[] p)
        {
            string msg = p.Length >= 3 ? string.Join("|", p, 2, p.Length - 2) : "Tabule spojení odmítla.";
            return new ProtoException(p.Length >= 2 ? p[1] : ErrProto, msg);
        }

        private static ClientResult ToResult(string[] p)
        {
            if (p.Length >= 1 && p[0] == "ERR") throw ToError(p);
            if (p.Length != 5 || p[0] != "OK") throw new IOException("Nesrozumitelná odpověď tabule.");
            if (!IsHex(p[1], 16)) throw new IOException("Tabule vrátila neplatné ID zařízení.");
            if (p[2] != "-" && !IsHex(p[2], 32)) throw new IOException("Tabule vrátila neplatný klíč zařízení.");
            ClientResult r = new ClientResult();
            r.DeviceId = p[1];
            r.DeviceKey = p[2] == "-" ? null : p[2];
            r.BoardName = Sanitize(p[3], 64, "Tabule Link");
            foreach (string c in p[4].Split(',')) if (c.Trim().Length > 0) r.ServerCaps.Add(c.Trim());
            return r;
        }

        public static ClientResult Hello(Stream s, string method, string deviceId, string credential, string deviceName, IEnumerable<string> caps)
        {
            WireIO.WriteJavaUtf(s, BuildHello(method, deviceId, credential, deviceName, caps));
            s.Flush();
            return ToResult(ReadReply(s));
        }

        public static ClientResult PairPin(Stream s, string pin, string fp, string deviceName, IEnumerable<string> caps)
        {
            Spake2.Client c = new Spake2.Client(pin);
            WireIO.WriteJavaUtf(s, BuildHello("PAKE", "-", c.MessageHex, deviceName, caps));
            s.Flush();
            string[] r = ReadReply(s);
            if (r.Length >= 1 && r[0] == "ERR") throw ToError(r);
            if (r.Length != 3 || r[0] != "PAKE") throw new IOException("Neočekávaná odpověď tabule.");
            Spake2.Keys keys = c.Finish(r[1], fp);
            if (!Spake2.MacEquals(r[2], keys.MacServer))
            {
                try { WireIO.WriteJavaUtf(s, "ABORT"); s.Flush(); } catch { }
                throw new ProtoException(ErrAuth, "Nesprávný PIN (nebo se ozvalo podvržené zařízení místo tabule).");
            }
            WireIO.WriteJavaUtf(s, keys.MacClient);
            s.Flush();
            return ToResult(ReadReply(s));
        }

        public static void Legacy(Stream s, string secret)
        {
            WireIO.WriteJavaUtf(s, secret);
            s.Flush();
            int r;
            try { r = WireIO.ReadInt32BE(s); }
            catch (EndOfStreamException) { throw new ProtoException(ErrAuth, "Tabule odmítla uložený klíč."); }
            if (r != MagicV1) throw new ProtoException(ErrAuth, "Tabule odmítla uložený klíč.");
        }

        public static void WriteHeader(Stream s, int width, int height)
        {
            WireIO.WriteInt32BE(s, MagicV1);
            WireIO.WriteInt32BE(s, width);
            WireIO.WriteInt32BE(s, height);
            s.Flush();
        }

        public static void AwaitGo(Stream s, Action onWait)
        {
            while (true)
            {
                int m = WireIO.ReadInt32BE(s);
                if (m == MagicGo) return;
                if (m == MagicWait) { if (onWait != null) onWait(); continue; }
                if (m == MagicErr) throw ToError(WireIO.ReadJavaUtf(s).Split('|'));
                throw new IOException("Neočekávaná odpověď tabule při spuštění přenosu.");
            }
        }

        public static Packet Control(string cmd)
        {
            return new Packet(FlagControl, 0, Encoding.UTF8.GetBytes(cmd));
        }

        public static string Fmt(int w, int h)
        {
            return "fmt:" + w.ToString(CultureInfo.InvariantCulture) + ":" + h.ToString(CultureInfo.InvariantCulture);
        }

        /// <summary>Returns {action, x, y} or null for "t:d|m|u:x:y" (x/y 0..65535 over the video frame).</summary>
        public static int[] ParseTouch(string cmd)
        {
            if (cmd == null || !cmd.StartsWith("t:", StringComparison.Ordinal)) return null;
            string[] p = cmd.Split(':');
            if (p.Length != 4 || p[1].Length != 1 || "dmu".IndexOf(p[1][0]) < 0) return null;
            int x, y;
            if (!int.TryParse(p[2], NumberStyles.None, CultureInfo.InvariantCulture, out x)) return null;
            if (!int.TryParse(p[3], NumberStyles.None, CultureInfo.InvariantCulture, out y)) return null;
            if (x < 0 || x > 65535 || y < 0 || y > 65535) return null;
            return new int[] { p[1][0], x, y };
        }

        public static string RandomHex(int bytes)
        {
            byte[] b = new byte[bytes];
            using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            return Spake2.Hex(b);
        }
    }

    public static class VideoGeometry
    {
        /// <summary>Same formula as VideoGeometry.java: longer side ≤ maxSide, each side rounded to the nearest multiple of 16.</summary>
        public static int[] Fit(int rawW, int rawH, int maxSide)
        {
            rawW = Math.Max(16, rawW);
            rawH = Math.Max(16, rawH);
            double scale = Math.Min(1.0, (double)maxSide / Math.Max(rawW, rawH));
            int w = Round16(rawW * scale), h = Round16(rawH * scale);
            int limit = Math.Max(16, (maxSide / 16) * 16);
            while (Math.Max(w, h) > limit)
            {
                if (w >= h) w -= 16; else h -= 16;
            }
            return new int[] { Math.Max(16, w), Math.Max(16, h) };
        }

        public static int Round16(double v)
        {
            return Math.Max(16, (int)Math.Round(v / 16.0, MidpointRounding.AwayFromZero) * 16);
        }
    }

    public sealed class BoardEntry
    {
        public string Name;
        public string Fingerprint;
        public string Host;
        public int Port;
        public string DeviceId;
        public string DeviceKey;
        public string LegacySecret;
        public long LastUsed;

        public bool HasV2Key { get { return DeviceId != null && DeviceKey != null; } }
        public bool HasAnyKey { get { return HasV2Key || LegacySecret != null; } }
        public string Address { get { return Host + ":" + Port.ToString(CultureInfo.InvariantCulture); } }

        public BoardEntry Clone() { return (BoardEntry)MemberwiseClone(); }
    }

    /// <summary>Paired boards, keyed by TLS fingerprint. Keys are protected by the given transform (DPAPI on Windows).</summary>
    public sealed class BoardStore
    {
        private readonly List<BoardEntry> boards = new List<BoardEntry>();

        public List<BoardEntry> List()
        {
            List<BoardEntry> copy = new List<BoardEntry>();
            foreach (BoardEntry b in boards) copy.Add(b.Clone());
            copy.Sort(delegate(BoardEntry a, BoardEntry b) { return b.LastUsed.CompareTo(a.LastUsed); });
            return copy;
        }

        public int Count { get { return boards.Count; } }

        public BoardEntry Find(string fp)
        {
            foreach (BoardEntry b in boards) if (b.Fingerprint == fp) return b.Clone();
            return null;
        }

        public void Upsert(BoardEntry e)
        {
            for (int i = 0; i < boards.Count; i++)
            {
                if (boards[i].Fingerprint == e.Fingerprint) { boards[i] = e.Clone(); return; }
            }
            boards.Add(e.Clone());
        }

        public bool Remove(string fp)
        {
            for (int i = 0; i < boards.Count; i++)
            {
                if (boards[i].Fingerprint == fp) { boards.RemoveAt(i); return true; }
            }
            return false;
        }

        private static string Opt(string s) { return string.IsNullOrEmpty(s) ? "-" : s; }
        private static string Unopt(string s) { return s == "-" || string.IsNullOrEmpty(s) ? null : s; }

        public string EncodeLine(BoardEntry b, Func<string, string> protect)
        {
            string key = b.DeviceKey == null ? "-" : protect(b.DeviceKey);
            string legacy = b.LegacySecret == null ? "-" : protect(b.LegacySecret);
            return "B1\t" + Convert.ToBase64String(Encoding.UTF8.GetBytes(b.Name ?? "Tabule")) + "\t" + b.Fingerprint + "\t" + b.Host + "\t"
                + b.Port.ToString(CultureInfo.InvariantCulture) + "\t" + Opt(b.DeviceId) + "\t" + key + "\t" + legacy + "\t"
                + b.LastUsed.ToString(CultureInfo.InvariantCulture);
        }

        public List<string> Encode(Func<string, string> protect)
        {
            List<string> lines = new List<string>();
            foreach (BoardEntry b in boards) lines.Add(EncodeLine(b, protect));
            return lines;
        }

        public bool TryAddLine(string line, Func<string, string> unprotect)
        {
            try
            {
                string[] p = line.Split('\t');
                if (p.Length != 9 || p[0] != "B1" || !Proto2.IsHex(p[2], 64) || !PairCode.IsValidLanIpv4(p[3])) return false;
                int port = int.Parse(p[4], CultureInfo.InvariantCulture);
                if (port < 1024 || port > 65535) return false;
                BoardEntry b = new BoardEntry();
                b.Name = Proto2.Sanitize(Encoding.UTF8.GetString(Convert.FromBase64String(p[1])), 64, "Tabule");
                b.Fingerprint = p[2];
                b.Host = p[3];
                b.Port = port;
                b.DeviceId = Unopt(p[5]);
                string key = Unopt(p[6]);
                string legacy = Unopt(p[7]);
                b.DeviceKey = key == null ? null : unprotect(key);
                b.LegacySecret = legacy == null ? null : unprotect(legacy);
                if (b.DeviceId != null && !Proto2.IsHex(b.DeviceId, 16)) b.DeviceId = null;
                if (b.DeviceKey != null && !Proto2.IsHex(b.DeviceKey, 32)) b.DeviceKey = null;
                if (b.LegacySecret != null && !Proto2.IsHex(b.LegacySecret, 32)) b.LegacySecret = null;
                b.LastUsed = long.Parse(p[8], CultureInfo.InvariantCulture);
                Upsert(b);
                return true;
            }
            catch
            {
                return false;
            }
        }
    }
}
