using System;
using System.Collections.Generic;
using System.IO;
using System.Numerics;
using System.Text;

namespace TabuleLinkWin
{
    /// <summary>Protocol v2 tests; the SPAKE2 vector must match ProtoTest.java byte for byte.</summary>
    internal static class Proto2Tests
    {
        private static void Check(bool ok, string msg) { WindowsTests.Check(ok, msg); }

        private const string VecPin = "482917";
        private const string VecFp = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";

        private static BigInteger VecScalar(string label)
        {
            return BigInteger.Remainder(Spake2.FromBytesUnsigned(Spake2.Sha256(Encoding.ASCII.GetBytes(label))), Spake2.Q);
        }

        public static void Run()
        {
            Check(BigInteger.ModPow(Spake2.G, Spake2.Q, Spake2.P).IsOne, "g order q");
            Check(Spake2.IsValidElement(Spake2.M) && Spake2.IsValidElement(Spake2.N), "M, N valid");
            Spake2.Client c = new Spake2.Client(VecPin, VecScalar("vector x"));
            Check(c.MessageHex.StartsWith("c35afe3af0d618966c25fe152fe2e06e", StringComparison.Ordinal), "vector X matches Java");
            // Server side emulated with the same math to obtain Y for the vector
            BigInteger y = VecScalar("vector y");
            BigInteger w = Spake2.PasswordScalar(VecPin);
            BigInteger Y = BigInteger.Remainder(BigInteger.ModPow(Spake2.G, y, Spake2.P) * BigInteger.ModPow(Spake2.N, w, Spake2.P), Spake2.P);
            string yHex = Spake2.Hex(Spake2.ToBytes(Y));
            Check(yHex.StartsWith("71f87db90828465e71ff9596fb875233", StringComparison.Ordinal), "vector Y matches Java");
            Spake2.Keys k = c.Finish(yHex, VecFp);
            Check(Spake2.Hex(k.SessionKey) == "41114008c7bcd578d9ed9697aab8a39fd2a5a4ceffce1ef9fa87db2e2887968e", "vector session key matches Java");
            Check(k.MacServer == "c4da7e4306f06f217301c3c355267fa8c7efcc249e30e629615aa8a532616086", "vector server mac");
            Check(k.MacClient == "8da3ff04fa87ca1427c5d9e55ce52ae93459a9a16438c7870ed11327796dc7a9", "vector client mac");
            bool threw = false;
            try { c.Finish(Spake2.Hex(Spake2.ToBytes(BigInteger.One)), VecFp); } catch (IOException) { threw = true; }
            Check(threw, "reject identity element");
            Check(Spake2.Hex(Spake2.ToBytes(new BigInteger(255))).EndsWith("00ff", StringComparison.Ordinal) && Spake2.ToBytes(Spake2.P - 1)[0] == 0xFF, "fixed width encoding");

            Check(Proto2.BuildHello("KEY", "0123456789abcdef", "k", "Note|book\u0001 😀", new string[] { "ctl", "touch" }) == "TL2|KEY|0123456789abcdef|k|Note book|ctl,touch", "hello sanitized");
            int[] t = Proto2.ParseTouch("t:m:100:65535");
            Check(t != null && t[0] == 'm' && t[1] == 100 && t[2] == 65535, "touch parse");
            Check(Proto2.ParseTouch("t:m:-1:5") == null && Proto2.ParseTouch("t:q:1:1") == null && Proto2.ParseTouch("x") == null, "touch reject");

            int[] g = VideoGeometry.Fit(1366, 768, 1280);
            Check(g[0] == 1280 && g[1] == 720, "geometry 1366x768");
            g = VideoGeometry.Fit(2560, 1600, 1920);
            Check(g[0] == 1920 && g[1] == 1200, "geometry 16:10");
            g = VideoGeometry.Fit(800, 1280, 960);
            Check(g[0] == 608 && g[1] == 960, "geometry portrait (same as Java)");

            BoardStore store = new BoardStore();
            BoardEntry b = new BoardEntry();
            b.Name = "Zasedačka 2.14"; b.Fingerprint = VecFp; b.Host = "192.168.1.50"; b.Port = 42521;
            b.DeviceId = "0123456789abcdef"; b.DeviceKey = "0123456789abcdef0123456789abcdef"; b.LastUsed = 7;
            store.Upsert(b);
            Func<string, string> rot = delegate(string s) { return "enc:" + s; };
            Func<string, string> unrot = delegate(string s) { return s.Substring(4); };
            List<string> lines = store.Encode(rot);
            Check(lines.Count == 1 && !lines[0].Contains("\t0123456789abcdef0123456789abcdef\t") && lines[0].Contains("enc:"), "board keys protected");
            BoardStore back = new BoardStore();
            Check(back.TryAddLine(lines[0], unrot) && back.Find(VecFp).HasV2Key && back.Find(VecFp).Name == "Zasedačka 2.14", "board store roundtrip");
            Check(!back.TryAddLine("B1\tbad", unrot), "board store rejects garbage");
            BoardEntry moved = back.Find(VecFp); moved.Host = "192.168.1.77"; back.Upsert(moved);
            Check(back.Count == 1 && back.Find(VecFp).Host == "192.168.1.77", "board update by fingerprint");

            Check(DiscoveryOffer.PreferredIp("192.168.5.10", "10.0.0.1") == "192.168.5.10", "discovery prefers source");
            Check(DiscoveryOffer.PreferredIp("127.0.0.1", "10.0.0.1") == "10.0.0.1", "discovery fallback");

            FrameQueue q = new FrameQueue();
            q.Offer(new Packet(WireConstants.FlagCodecConfig, 0, new byte[] { 1 }));
            q.Offer(new Packet(WireConstants.FlagKeyFrame, 1, new byte[] { 2 }));
            q.Offer(Proto2.Control("pause"));
            Check(q.Poll(0).IsControl, "control first");
            q.RequireKeyFrame();
            q.Offer(new Packet(0, 2, new byte[] { 3 }));
            Check(q.Poll(0).IsConfig && q.Poll(0) == null, "RequireKeyFrame drops P frames");
            q.Offer(new Packet(WireConstants.FlagKeyFrame, 3, new byte[] { 4 }));
            Check(q.Poll(0).PtsUs == 3, "key frame after RequireKeyFrame");
            q.ClearVideo();
            Check(q.Poll(0) == null, "ClearVideo");

            // Protocol replies over a memory stream
            MemoryStream ms = new MemoryStream();
            WireIO.WriteInt32BE(ms, Proto2.MagicWait);
            WireIO.WriteInt32BE(ms, Proto2.MagicErr);
            WireIO.WriteJavaUtf(ms, "ERR|denied|Převzetí odmítnuto na tabuli.");
            ms.Position = 0;
            int waits = 0;
            string code = null;
            try { Proto2.AwaitGo(ms, delegate { waits++; }); }
            catch (ProtoException ex) { code = ex.Code + ":" + ex.Message; }
            Check(waits == 1 && code == "denied:Převzetí odmítnuto na tabuli.", "await go error");
            ms = new MemoryStream();
            MemoryStream reply = new MemoryStream();
            WireIO.WriteInt32BE(reply, Proto2.MagicV2);
            WireIO.WriteJavaUtf(reply, "OK|0123456789abcdef|fedcba9876543210fedcba9876543210|Tabule A|ctl,touch");
            reply.Position = 0;
            DuplexStream ds = new DuplexStream(reply, ms);
            ClientResult r = Proto2.Hello(ds, "TOKEN", "-", "11112222333344445555666677778888", "PC", new string[] { "ctl" });
            Check(r.DeviceId == "0123456789abcdef" && r.DeviceKey == "fedcba9876543210fedcba9876543210" && r.BoardName == "Tabule A" && r.ServerCaps.Contains("touch"), "hello OK parse");
            reply = new MemoryStream();
            ds = new DuplexStream(reply, new MemoryStream());
            threw = false;
            try { Proto2.Hello(ds, "KEY", "0123456789abcdef", "0123456789abcdef0123456789abcdef", "PC", new string[0]); } catch (OldServerException) { threw = true; }
            Check(threw, "old server detected on EOF");
        }
    }

    /// <summary>Test helper: reads from one stream, writes to another.</summary>
    internal sealed class DuplexStream : Stream
    {
        private readonly Stream input, output;
        public DuplexStream(Stream input, Stream output) { this.input = input; this.output = output; }
        public override bool CanRead { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return true; } }
        public override long Length { get { throw new NotSupportedException(); } }
        public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
        public override void Flush() { output.Flush(); }
        public override int Read(byte[] buffer, int offset, int count) { return input.Read(buffer, offset, count); }
        public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
        public override void SetLength(long value) { throw new NotSupportedException(); }
        public override void Write(byte[] buffer, int offset, int count) { output.Write(buffer, offset, count); }
    }
}
