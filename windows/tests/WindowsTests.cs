using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

namespace TabuleLinkWin
{
    internal static class WindowsTests
    {
        private static int checks;

        internal static void Check(bool ok, string msg)
        {
            checks++;
            if (!ok) throw new Exception("FAIL: " + msg);
        }

        private static void TestPairAndDiscovery()
        {
            string fp = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
            string secret = "1234567890abcdef1234567890abcdef";
            string uri = PairCode.BuildUri("192.168.1.50", 42521, fp, secret);
            PairCode p = new PairCode(" " + uri + "\r\n");
            Check(p.Host == "192.168.1.50" && p.Port == 42521 && p.Fingerprint == fp && p.Secret == secret, "PairCode roundtrip");

            string[] bad = new string[] { null, "", "http://192.168.1.50:42521", uri.Replace("192.168.1.50", "127.0.0.1"), uri.Replace(":42521", ":80") };
            for (int i = 0; i < bad.Length; i++)
            {
                bool threw = false;
                try { new PairCode(bad[i]); } catch (IOException) { threw = true; }
                Check(threw, "reject bad PairCode #" + i);
            }

            string offerLine = WireConstants.OfferPrefix + "HUAWEI IdeaHub Pro\t42521\t" + fp + "\t192.168.1.50";
            DiscoveryOffer offer = DiscoveryOffer.Parse(offerLine, "192.168.1.99");
            Check(offer != null && offer.Name == "HUAWEI IdeaHub Pro" && offer.Port == 42521 && offer.Ip == "192.168.1.50", "DiscoveryOffer parse");
            Check(DiscoveryOffer.NormalizePin(" 482 917 ") == "482917", "NormalizePin space");
            Check(DiscoveryOffer.NormalizePin("482-917") == "482917", "NormalizePin dash");
            Check(DiscoveryOffer.NormalizePin("12345") == null, "Reject 5-digit PIN");

            Check(LocalNet.SameSubnet("192.168.1.10", 24, "192.168.1.50"), "SameSubnet /24");
            Check(!LocalNet.SameSubnet("192.168.1.10", 24, "192.168.2.50"), "DiffSubnet /24");
        }

        private static void TestWireIoAndAudioUtil()
        {
            MemoryStream ms = new MemoryStream();
            WireIO.WriteJavaUtf(ms, "PIN:482917");
            WireIO.WriteInt32BE(ms, WireConstants.MagicPinOk);
            byte[] scratch = WireIO.WritePacketSingleRecord(ms, null, new Packet(WireConstants.FlagKeyFrame, 40000L, new byte[] { 1, 2, 3, 4 }));
            WireIO.WriteHeartbeat(ms);

            ms.Position = 0;
            Check(WireIO.ReadJavaUtf(ms) == "PIN:482917", "Java UTF roundtrip");
            Check(WireIO.ReadInt32BE(ms) == WireConstants.MagicPinOk, "Int32BE roundtrip");
            int pktLen = WireIO.ReadInt32BE(ms);
            int pktFlags = WireIO.ReadInt32BE(ms);
            byte[] ptsBytes = WireIO.ReadExact(ms, 8);
            byte[] payload = WireIO.ReadExact(ms, pktLen);
            Check(pktLen == 4 && pktFlags == WireConstants.FlagKeyFrame && ptsBytes[7] == 0x40 && payload[3] == 4, "Packet wire layout");
            int hbLen = WireIO.ReadInt32BE(ms);
            int hbFlags = WireIO.ReadInt32BE(ms);
            byte[] hbPts = WireIO.ReadExact(ms, 8);
            Check(hbLen == 0 && hbFlags == 0 && hbPts[7] == 0, "Heartbeat layout");

            byte[] pcm = new byte[] { (byte)(20000 & 0xFF), (byte)((20000 >> 8) & 0xFF), (byte)(-20000 & 0xFF), (byte)((-20000 >> 8) & 0xFF) };
            AudioUtil.ApplyGainPcm16Le(pcm, pcm.Length, 50);
            short s1 = (short)(pcm[0] | (pcm[1] << 8));
            short s2 = (short)(pcm[2] | (pcm[3] << 8));
            Check(s1 == 5000 && s2 == -5000, "AudioUtil 50% quadratic gain");
            AudioUtil.ApplyGainPcm16Le(pcm, pcm.Length, 0);
            Check(pcm[0] == 0 && pcm[1] == 0 && pcm[2] == 0 && pcm[3] == 0, "AudioUtil 0% mute");
        }

        private static void TestFrameQueue()
        {
            FrameQueue q = new FrameQueue();
            q.Offer(new Packet(WireConstants.FlagCodecConfig, 0, new byte[] { 7, 8 }));
            q.Offer(new Packet(WireConstants.FlagAudio | WireConstants.FlagCodecConfig, 0, new byte[] { 0x11, 0x90 }));
            for (int i = 0; i < 10; i++) q.Offer(new Packet(WireConstants.FlagAudio, i * 21333L, new byte[] { (byte)i }));
            Check(q.DroppedAudio == 4, "Audio queue bounded");
            Packet first = q.Poll(10);
            Packet second = q.Poll(10);
            Check(first != null && !first.IsAudio && first.IsConfig, "Video config prioritized");
            Check(second != null && second.IsAudio && second.IsConfig, "Audio config preserved");
        }

        private static void TestRealFfmpegParsers()
        {
            // Verify AnnexBParser on real ffmpeg libx264 baseline zerolatency output
            int vConfigs = 0, vKeys = 0, vDeltas = 0;
            AnnexBParser h264 = new AnnexBParser(delegate(int flags, byte[] data)
            {
                if ((flags & WireConstants.FlagCodecConfig) != 0) vConfigs++;
                else if ((flags & WireConstants.FlagKeyFrame) != 0) vKeys++;
                else vDeltas++;
                Check(data.Length > 0, "H.264 packet non-empty");
            });

            byte[] h264Bytes = RunFfmpegStdout(
                "-hide_banner -loglevel error -f lavfi -i testsrc=duration=1:size=640x360:rate=15 -pix_fmt yuv420p " +
                "-c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -bf 0 -g 10 -x264-params \"repeat-headers=1:scenecut=0:aud=1:slices=1\" -f h264 pipe:1");
            // Feed in uneven chunks to test boundary handling
            for (int off = 0; off < h264Bytes.Length; off += 777)
            {
                h264.Push(h264Bytes, off, Math.Min(777, h264Bytes.Length - off));
            }
            h264.Flush();
            Check(vConfigs >= 1, "AnnexBParser extracted SPS+PPS config (got " + vConfigs + ")");
            Check(vKeys >= 1, "AnnexBParser extracted IDR keyframes (got " + vKeys + ")");
            Check(vDeltas >= 5, "AnnexBParser extracted P-frames (got " + vDeltas + ")");

            // Verify AdtsParser on real ffmpeg AAC-LC 48kHz stereo output
            int aConfigs = 0, aFrames = 0;
            byte[] gotCsd = null;
            AdtsParser adts = new AdtsParser(delegate(int flags, byte[] data)
            {
                Check((flags & WireConstants.FlagAudio) != 0, "AdtsParser sets FlagAudio");
                if ((flags & WireConstants.FlagCodecConfig) != 0) { aConfigs++; gotCsd = data; }
                else aFrames++;
            });

            byte[] aacBytes = RunFfmpegStdout(
                "-hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=48000:duration=1 -ac 2 -c:a aac -profile:a aac_low -b:a 128k -f adts pipe:1");
            for (int off = 0; off < aacBytes.Length; off += 333)
            {
                adts.Push(aacBytes, off, Math.Min(333, aacBytes.Length - off));
            }
            Check(aConfigs == 1 && gotCsd != null && gotCsd.Length == 2 && gotCsd[0] == 0x11 && gotCsd[1] == 0x90,
                "AdtsParser extracted exact AAC-LC 48kHz stereo AudioSpecificConfig (0x11 0x90)");
            Check(aFrames >= 40, "AdtsParser extracted raw AAC frames (got " + aFrames + ")");
        }

        private static byte[] RunFfmpegStdout(string args)
        {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = "ffmpeg";
            psi.Arguments = args;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            StringBuilder stderr = new StringBuilder();
            using (Process p = Process.Start(psi))
            using (MemoryStream ms = new MemoryStream())
            {
                p.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e)
                {
                    if (e.Data != null) lock (stderr) stderr.AppendLine(e.Data);
                };
                p.BeginErrorReadLine();
                p.StandardOutput.BaseStream.CopyTo(ms);
                bool exited = p.WaitForExit(10000);
                if (exited) p.WaitForExit(); // flush async stderr callbacks
                string errText;
                lock (stderr) errText = stderr.ToString().Trim();
                Check(exited && p.ExitCode == 0, "ffmpeg exited cleanly" + (errText.Length > 0 ? " (" + errText + ")" : ""));
                return ms.ToArray();
            }
        }

        public static void Main()
        {
            TestPairAndDiscovery();
            TestWireIoAndAudioUtil();
            TestFrameQueue();
            TestRealFfmpegParsers();
            Proto2Tests.Run();
            DisplayMathTests.Run();
            Console.WriteLine("PASS: " + checks + " Windows checks (PairCode, Discovery, PIN, WireIO, AudioUtil, FrameQueue, real ffmpeg H.264 Annex-B + AAC ADTS parsing, protocol v2: SPAKE2 vector shared with Java, hello, board store, geometry, control, virtual monitor placement/modes/touch/driver IDs)");
        }
    }
}
