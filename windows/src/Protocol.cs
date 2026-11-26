using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;

namespace TabuleLinkWin
{
    public static class WireConstants
    {
        public const string Version = "0.9.0";
        public const int Magic = 0x54424c31;
        public const int MagicPinOk = 0x54424c32;
        public const int DefaultPort = 42521;
        public const int DiscoveryPort = 42522;
        public const int MaxFrame = 2 * 1024 * 1024;
        public const int HeaderSize = 16;

        public const int FlagKeyFrame = 1;
        public const int FlagCodecConfig = 2;
        public const int FlagAudio = 16;

        public const string DiscoverRequest = "TABULELINK_DISCOVER_V1";
        public const string OfferPrefix = "TABULELINK_OFFER_V1\t";
    }

    public sealed class PairCode
    {
        private static readonly Regex Format = new Regex(
            "^tabulelink://([0-9.]{7,15}):([0-9]{4,5})\\?fp=([a-f0-9]{64})&key=([a-f0-9]{32})$",
            RegexOptions.Compiled);

        public readonly string Host;
        public readonly int Port;
        public readonly string Fingerprint;
        public readonly string Secret;

        public PairCode(string text)
        {
            if (text == null || text.Length > 256) throw Invalid();
            Match m = Format.Match(text.Trim());
            if (!m.Success) throw Invalid();
            Host = m.Groups[1].Value;
            Port = int.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture);
            Fingerprint = m.Groups[3].Value;
            Secret = m.Groups[4].Value;
            if (Port < 1024 || Port > 65535) throw Invalid();
            if (!IsValidLanIpv4(Host)) throw Invalid();
        }

        public static bool IsValidLanIpv4(string host)
        {
            if (string.IsNullOrEmpty(host)) return false;
            for (int i = 0; i < host.Length; i++) if (host[i] != '.' && (host[i] < '0' || host[i] > '9')) return false;
            string[] parts = host.Split('.');
            if (parts.Length != 4) return false;
            for (int i = 0; i < 4; i++)
            {
                string part = parts[i];
                if (part.Length == 0 || part.Length > 3 || (part.Length > 1 && part[0] == '0')) return false;
                int n;
                if (!int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out n) || n > 255) return false;
            }
            int first = int.Parse(parts[0], CultureInfo.InvariantCulture);
            if (first == 0 || first == 127 || first >= 224 || host == "255.255.255.255") return false;
            return true;
        }

        public static string BuildUri(string host, int port, string fp, string secret)
        {
            return "tabulelink://" + host + ":" + port.ToString(CultureInfo.InvariantCulture) + "?fp=" + fp + "&key=" + secret;
        }

        private static IOException Invalid()
        {
            return new IOException("Neplatný párovací kód. Vložte kód tabulelink://… z aplikace na tabuli.");
        }
    }

    public sealed class Packet
    {
        public readonly int Flags;
        public readonly long PtsUs;
        public readonly byte[] Data;

        public Packet(int flags, long ptsUs, byte[] data)
        {
            Flags = flags;
            PtsUs = ptsUs;
            Data = data;
        }

        public bool IsAudio { get { return (Flags & WireConstants.FlagAudio) != 0; } }
        public bool IsConfig { get { return (Flags & WireConstants.FlagCodecConfig) != 0; } }
        public bool IsKeyFrame { get { return (Flags & WireConstants.FlagKeyFrame) != 0; } }
        public bool IsControl { get { return (Flags & Proto2.FlagControl) != 0; } }
    }

    public static class WireIO
    {
        private static readonly byte[] HeartbeatBytes = new byte[WireConstants.HeaderSize];

        public static void WriteInt32BE(Stream s, int value)
        {
            byte[] b = new byte[4];
            b[0] = (byte)((value >> 24) & 0xFF);
            b[1] = (byte)((value >> 16) & 0xFF);
            b[2] = (byte)((value >> 8) & 0xFF);
            b[3] = (byte)(value & 0xFF);
            s.Write(b, 0, 4);
        }

        public static int ReadInt32BE(Stream s)
        {
            byte[] b = ReadExact(s, 4);
            return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
        }

        public static void WriteJavaUtf(Stream s, string text)
        {
            byte[] utf = Encoding.UTF8.GetBytes(text);
            if (utf.Length > 65535) throw new IOException("Řetězec je příliš dlouhý.");
            byte[] outBuf = new byte[2 + utf.Length];
            outBuf[0] = (byte)((utf.Length >> 8) & 0xFF);
            outBuf[1] = (byte)(utf.Length & 0xFF);
            Buffer.BlockCopy(utf, 0, outBuf, 2, utf.Length);
            s.Write(outBuf, 0, outBuf.Length);
        }

        public static string ReadJavaUtf(Stream s)
        {
            byte[] lenBuf = ReadExact(s, 2);
            int len = (lenBuf[0] << 8) | lenBuf[1];
            byte[] utf = ReadExact(s, len);
            return Encoding.UTF8.GetString(utf);
        }

        public static byte[] ReadExact(Stream s, int count)
        {
            byte[] buf = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                int n = s.Read(buf, offset, count - offset);
                if (n <= 0) throw new EndOfStreamException("Spojení bylo uzavřeno.");
                offset += n;
            }
            return buf;
        }

        public static byte[] WritePacketSingleRecord(Stream s, byte[] scratch, Packet pkt)
        {
            int len = pkt.Data.Length;
            if (len > WireConstants.MaxFrame) throw new IOException("Příliš velký snímek.");
            int total = WireConstants.HeaderSize + len;
            if (scratch == null || scratch.Length < total)
            {
                scratch = new byte[Math.Max(total, 128 * 1024)];
            }
            scratch[0] = (byte)((len >> 24) & 0xFF);
            scratch[1] = (byte)((len >> 16) & 0xFF);
            scratch[2] = (byte)((len >> 8) & 0xFF);
            scratch[3] = (byte)(len & 0xFF);
            scratch[4] = (byte)((pkt.Flags >> 24) & 0xFF);
            scratch[5] = (byte)((pkt.Flags >> 16) & 0xFF);
            scratch[6] = (byte)((pkt.Flags >> 8) & 0xFF);
            scratch[7] = (byte)(pkt.Flags & 0xFF);
            long pts = pkt.PtsUs;
            scratch[8] = (byte)((pts >> 56) & 0xFF);
            scratch[9] = (byte)((pts >> 48) & 0xFF);
            scratch[10] = (byte)((pts >> 40) & 0xFF);
            scratch[11] = (byte)((pts >> 32) & 0xFF);
            scratch[12] = (byte)((pts >> 24) & 0xFF);
            scratch[13] = (byte)((pts >> 16) & 0xFF);
            scratch[14] = (byte)((pts >> 8) & 0xFF);
            scratch[15] = (byte)(pts & 0xFF);
            if (len > 0) Buffer.BlockCopy(pkt.Data, 0, scratch, WireConstants.HeaderSize, len);
            s.Write(scratch, 0, total);
            s.Flush();
            return scratch;
        }

        public static void WriteHeartbeat(Stream s)
        {
            s.Write(HeartbeatBytes, 0, WireConstants.HeaderSize);
            s.Flush();
        }
    }

    public static class AudioUtil
    {
        public static int ClampPercent(int percent)
        {
            if (percent < 0) return 0;
            if (percent > 100) return 100;
            return percent;
        }

        public static float PercentToGain(int percent)
        {
            int p = ClampPercent(percent);
            if (p == 0) return 0f;
            if (p == 100) return 1f;
            float n = p / 100f;
            return n * n;
        }

        public static void ApplyGainPcm16Le(byte[] pcm, int length, int percent)
        {
            if (pcm == null || length <= 0) return;
            int p = ClampPercent(percent);
            if (p >= 100) return;
            int limit = Math.Min(length, pcm.Length) & ~1;
            if (p == 0)
            {
                Array.Clear(pcm, 0, limit);
                return;
            }
            float gain = PercentToGain(p);
            for (int i = 0; i < limit; i += 2)
            {
                short sample = (short)(pcm[i] | (pcm[i + 1] << 8));
                int scaled = (int)Math.Round(sample * gain);
                if (scaled > 32767) scaled = 32767;
                else if (scaled < -32768) scaled = -32768;
                pcm[i] = (byte)(scaled & 0xFF);
                pcm[i + 1] = (byte)((scaled >> 8) & 0xFF);
            }
        }
    }

    public sealed class DiscoveryOffer
    {
        public readonly string Name;
        public readonly int Port;
        public readonly string Fingerprint;
        public readonly string Ip;

        public DiscoveryOffer(string name, int port, string fp, string ip)
        {
            Name = name;
            Port = port;
            Fingerprint = fp;
            Ip = ip;
        }

        public string DisplayLabel
        {
            get { return Name + "  (" + Ip + ":" + Port.ToString(CultureInfo.InvariantCulture) + ")"; }
        }

        public static string NormalizePin(string raw)
        {
            if (raw == null) return null;
            string digits = raw.Replace(" ", "").Replace("-", "").Trim();
            if (digits.Length != 6) return null;
            for (int i = 0; i < 6; i++)
            {
                if (digits[i] < '0' || digits[i] > '9') return null;
            }
            return digits;
        }

        public static DiscoveryOffer Parse(string text, string fallbackIp)
        {
            if (string.IsNullOrEmpty(text) || !text.StartsWith(WireConstants.OfferPrefix, StringComparison.Ordinal)) return null;
            string[] parts = text.Split('\t');
            if (parts.Length < 5) return null;
            string name = parts[1].Trim();
            if (name.Length == 0) name = "Tabule Link";
            int port;
            if (!int.TryParse(parts[2].Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out port)) return null;
            if (port < 1024 || port > 65535) return null;
            string fp = parts[3].Trim().ToLowerInvariant();
            if (fp.Length != 64 || !Regex.IsMatch(fp, "^[a-f0-9]{64}$")) return null;
            string ip = parts[4].Trim();
            if (ip.Length == 0) ip = fallbackIp ?? "";
            if (ip.Length > 0 && !PairCode.IsValidLanIpv4(ip)) return null;
            name = Proto2.Sanitize(name, 64, "Tabule Link");
            return new DiscoveryOffer(name, port, fp, ip);
        }

        /// <summary>Prefers the address the reply came from (multi-homed boards may advertise an unreachable one).</summary>
        public static string PreferredIp(string sourceIp, string advertisedIp)
        {
            if (PairCode.IsValidLanIpv4(sourceIp)) return sourceIp;
            if (PairCode.IsValidLanIpv4(advertisedIp)) return advertisedIp;
            return null;
        }

        public static List<DiscoveryOffer> DiscoverInLan(int timeoutMs)
        {
            List<IPEndPoint> targets = new List<IPEndPoint>();
            targets.Add(new IPEndPoint(IPAddress.Broadcast, WireConstants.DiscoveryPort));
            foreach (LocalEndpoint ep in LocalNet.GetEndpoints())
            {
                IPAddress bcast = ep.BroadcastAddress();
                if (bcast != null) targets.Add(new IPEndPoint(bcast, WireConstants.DiscoveryPort));
            }
            return Discover(timeoutMs, targets);
        }

        /// <summary>Waits the full timeout (0.6.x gave up after the first 250 ms of silence); one offer per board fingerprint.</summary>
        public static List<DiscoveryOffer> Discover(int timeoutMs, List<IPEndPoint> targets)
        {
            Dictionary<string, DiscoveryOffer> found = new Dictionary<string, DiscoveryOffer>();
            List<string> order = new List<string>();
            byte[] req = Encoding.UTF8.GetBytes(WireConstants.DiscoverRequest);
            try
            {
                using (UdpClient udp = new UdpClient())
                {
                    udp.EnableBroadcast = true;
                    int end = Environment.TickCount + Math.Max(300, timeoutMs);
                    int nextSend = Environment.TickCount;
                    int rounds = 0;
                    while (true)
                    {
                        int now = Environment.TickCount;
                        if (now - end >= 0) break;
                        if (now - nextSend >= 0 && rounds < 3)
                        {
                            foreach (IPEndPoint ep in targets)
                            {
                                try { udp.Send(req, req.Length, ep); } catch { }
                            }
                            rounds++;
                            nextSend = now + Math.Max(200, timeoutMs / 3);
                        }
                        int wait = Math.Max(1, Math.Min(250, Math.Min(end - now, Math.Max(1, nextSend - now))));
                        udp.Client.ReceiveTimeout = wait;
                        try
                        {
                            IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                            byte[] data = udp.Receive(ref remote);
                            DiscoveryOffer offer = Parse(Encoding.UTF8.GetString(data), null);
                            string ip = offer == null ? null : PreferredIp(remote.Address.ToString(), offer.Ip);
                            if (offer == null || ip == null) continue;
                            if (!found.ContainsKey(offer.Fingerprint)) order.Add(offer.Fingerprint);
                            found[offer.Fingerprint] = new DiscoveryOffer(offer.Name, offer.Port, offer.Fingerprint, ip);
                        }
                        catch (SocketException ex)
                        {
                            if (ex.SocketErrorCode == SocketError.TimedOut || ex.SocketErrorCode == SocketError.WouldBlock) continue;
                            if (ex.SocketErrorCode == SocketError.ConnectionReset) continue; // ICMP port unreachable on Windows
                            break;
                        }
                    }
                }
            }
            catch { }
            List<DiscoveryOffer> result = new List<DiscoveryOffer>();
            foreach (string fp in order) result.Add(found[fp]);
            return result;
        }

        public static DiscoveryOffer FindByFingerprint(string fp, int timeoutMs)
        {
            foreach (DiscoveryOffer o in DiscoverInLan(timeoutMs)) if (o.Fingerprint == fp) return o;
            return null;
        }
    }

    public sealed class LocalEndpoint
    {
        public readonly string Ip;
        public readonly int Prefix;
        public readonly string AdapterName;
        public readonly string Kind;

        public LocalEndpoint(string ip, int prefix, string adapterName, string kind)
        {
            Ip = ip;
            Prefix = prefix;
            AdapterName = adapterName;
            Kind = kind;
        }

        public string Label
        {
            get { return Kind + " · " + Ip + "/" + Prefix.ToString(CultureInfo.InvariantCulture) + " (" + AdapterName + ")"; }
        }

        public IPAddress BroadcastAddress()
        {
            IPAddress addr;
            if (!IPAddress.TryParse(Ip, out addr) || Prefix < 1 || Prefix > 31) return null;
            byte[] b = addr.GetAddressBytes();
            if (b.Length != 4) return null;
            uint ipVal = ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];
            uint mask = 0xFFFFFFFFu << (32 - Prefix);
            uint bcast = ipVal | ~mask;
            return new IPAddress(new byte[]
            {
                (byte)((bcast >> 24) & 0xFF),
                (byte)((bcast >> 16) & 0xFF),
                (byte)((bcast >> 8) & 0xFF),
                (byte)(bcast & 0xFF)
            });
        }
    }

    public static class LocalNet
    {
        public static List<LocalEndpoint> GetEndpoints()
        {
            List<LocalEndpoint> list = new List<LocalEndpoint>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                        ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                    string kind = ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? "Wi-Fi"
                        : (ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? "Ethernet" : "Síť");
                    IPInterfaceProperties props = ni.GetIPProperties();
                    foreach (UnicastIPAddressInformation uni in props.UnicastAddresses)
                    {
                        if (uni.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string ip = uni.Address.ToString();
                        if (ip.StartsWith("127.", StringComparison.Ordinal) || ip.StartsWith("169.254.", StringComparison.Ordinal)) continue;
                        int prefix = MaskToPrefix(uni.IPv4Mask);
                        list.Add(new LocalEndpoint(ip, prefix, ni.Name, kind));
                    }
                }
            }
            catch { }
            return list;
        }

        private static int MaskToPrefix(IPAddress mask)
        {
            if (mask == null) return 24;
            byte[] b = mask.GetAddressBytes();
            if (b.Length != 4) return 24;
            int bits = 0;
            for (int i = 0; i < 4; i++)
            {
                byte v = b[i];
                for (int bit = 7; bit >= 0; bit--)
                {
                    if ((v & (1 << bit)) != 0) bits++;
                }
            }
            return bits > 0 ? bits : 24;
        }

        public static bool SameSubnet(string ipA, int prefix, string ipB)
        {
            IPAddress a, b;
            if (prefix < 1 || prefix > 32 || !IPAddress.TryParse(ipA, out a) || !IPAddress.TryParse(ipB, out b)) return false;
            byte[] ba = a.GetAddressBytes();
            byte[] bb = b.GetAddressBytes();
            if (ba.Length != 4 || bb.Length != 4) return false;
            uint va = ((uint)ba[0] << 24) | ((uint)ba[1] << 16) | ((uint)ba[2] << 8) | ba[3];
            uint vb = ((uint)bb[0] << 24) | ((uint)bb[1] << 16) | ((uint)bb[2] << 8) | bb[3];
            uint mask = prefix == 32 ? 0xFFFFFFFFu : (0xFFFFFFFFu << (32 - prefix));
            return (va & mask) == (vb & mask);
        }

        public static string SubnetMismatchHint(string targetHost)
        {
            List<LocalEndpoint> eps = GetEndpoints();
            if (eps.Count == 0) return "Notebook nemá žádnou aktivní IPv4 adresu.";
            foreach (LocalEndpoint ep in eps)
            {
                if (SameSubnet(ep.Ip, ep.Prefix, targetHost)) return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < eps.Count; i++)
            {
                if (i > 0) sb.Append(", ");
                sb.Append(eps[i].Label);
            }
            return "Pozor: Tabule má IP " + targetHost + ", ale notebook je v jiném rozsahu (" + sb.ToString() + ").";
        }
    }

    /// <summary>
    /// Splits a raw H.264 Annex-B byte stream (from ffmpeg -f h264) into clean MediaCodec packets:
    /// SPS+PPS as FlagCodecConfig, IDR access units as FlagKeyFrame, P-frames as flags=0.
    /// </summary>
    public sealed class AnnexBParser
    {
        private readonly Action<int, byte[]> onPacket;
        private readonly MemoryStream pending = new MemoryStream();
        private readonly MemoryStream auBuf = new MemoryStream();
        private byte[] lastSps;
        private byte[] lastPps;
        private bool sentConfig;
        private bool auHasVcl;
        private bool auIsKey;

        /// <summary>Most recent SPS+PPS (re-sent first after a reconnect).</summary>
        public byte[] LastConfig { get; private set; }

        public AnnexBParser(Action<int, byte[]> onPacket)
        {
            this.onPacket = onPacket;
        }

        public void Push(byte[] data, int offset, int count)
        {
            if (count <= 0) return;
            pending.Write(data, offset, count);
            byte[] buf = pending.GetBuffer();
            int len = (int)pending.Length;

            int pos = FindStartCode(buf, 0, len);
            if (pos < 0)
            {
                // Keep last 3 bytes in case a start code straddles the buffer boundary
                KeepTail(buf, len, Math.Min(3, len));
                return;
            }

            while (true)
            {
                int scLen = (pos + 3 < len && buf[pos] == 0 && buf[pos + 1] == 0 && buf[pos + 2] == 0 && buf[pos + 3] == 1) ? 4 : 3;
                int next = FindStartCode(buf, pos + scLen, len);
                if (next < 0)
                {
                    KeepTail(buf, len, len - pos);
                    return;
                }
                ProcessNal(buf, pos, next - pos, scLen);
                pos = next;
            }
        }

        public void Flush()
        {
            byte[] buf = pending.GetBuffer();
            int len = (int)pending.Length;
            int pos = FindStartCode(buf, 0, len);
            if (pos >= 0 && pos < len)
            {
                int scLen = (pos + 3 < len && buf[pos] == 0 && buf[pos + 1] == 0 && buf[pos + 2] == 0 && buf[pos + 3] == 1) ? 4 : 3;
                if (len - pos > scLen) ProcessNal(buf, pos, len - pos, scLen);
            }
            pending.SetLength(0);
            EmitCurrentAu();
        }

        private void KeepTail(byte[] buf, int len, int tailLen)
        {
            if (tailLen <= 0)
            {
                pending.SetLength(0);
                return;
            }
            byte[] tail = new byte[tailLen];
            Buffer.BlockCopy(buf, len - tailLen, tail, 0, tailLen);
            pending.SetLength(0);
            pending.Write(tail, 0, tailLen);
        }

        private static int FindStartCode(byte[] buf, int start, int len)
        {
            for (int i = start; i <= len - 3; i++)
            {
                if (buf[i] == 0 && buf[i + 1] == 0)
                {
                    if (buf[i + 2] == 1) return i;
                    if (i <= len - 4 && buf[i + 2] == 0 && buf[i + 3] == 1) return i;
                }
            }
            return -1;
        }

        private void ProcessNal(byte[] buf, int offset, int length, int scLen)
        {
            if (length <= scLen) return;
            int nalType = buf[offset + scLen] & 0x1F;

            if (nalType == 9) // AUD — cleanly ends previous access unit
            {
                EmitCurrentAu();
                return;
            }
            if (nalType == 6 || nalType == 12) return; // SEI / Filler

            if (nalType == 7) // SPS
            {
                EmitCurrentAu();
                lastSps = Slice(buf, offset, length);
                sentConfig = false;
                TryEmitConfig();
                return;
            }
            if (nalType == 8) // PPS
            {
                EmitCurrentAu();
                lastPps = Slice(buf, offset, length);
                sentConfig = false;
                TryEmitConfig();
                return;
            }

            if (nalType == 5 || nalType == 1)
            {
                // First bit of slice_header (first_mb_in_slice == 0) marks the start of a new frame
                bool newFrame = length > scLen + 1 && (buf[offset + scLen + 1] & 0x80) != 0;
                if (auHasVcl && newFrame)
                {
                    EmitCurrentAu();
                }
                if (nalType == 5 && !sentConfig) TryEmitConfig();
                auBuf.Write(buf, offset, length);
                auHasVcl = true;
                if (nalType == 5) auIsKey = true;
            }
        }

        private void TryEmitConfig()
        {
            if (sentConfig || lastSps == null || lastPps == null) return;
            byte[] cfg = new byte[lastSps.Length + lastPps.Length];
            Buffer.BlockCopy(lastSps, 0, cfg, 0, lastSps.Length);
            Buffer.BlockCopy(lastPps, 0, cfg, lastSps.Length, lastPps.Length);
            sentConfig = true;
            LastConfig = cfg;
            onPacket(WireConstants.FlagCodecConfig, cfg);
        }

        private void EmitCurrentAu()
        {
            if (!auHasVcl || auBuf.Length == 0)
            {
                auBuf.SetLength(0);
                auHasVcl = false;
                auIsKey = false;
                return;
            }
            byte[] frame = auBuf.ToArray();
            int flags = auIsKey ? WireConstants.FlagKeyFrame : 0;
            auBuf.SetLength(0);
            auHasVcl = false;
            auIsKey = false;
            onPacket(flags, frame);
        }

        private static byte[] Slice(byte[] buf, int offset, int length)
        {
            byte[] copy = new byte[length];
            Buffer.BlockCopy(buf, offset, copy, 0, length);
            return copy;
        }
    }

    /// <summary>
    /// Strips 7/9-byte ADTS headers from ffmpeg AAC output, emits 2-byte AudioSpecificConfig (csd-0)
    /// and raw AAC-LC payloads compatible with Android MediaCodec AAC decoder.
    /// </summary>
    public sealed class AdtsParser
    {
        private readonly Action<int, byte[]> onPacket;
        private readonly MemoryStream pending = new MemoryStream();
        private bool sentConfig;

        public AdtsParser(Action<int, byte[]> onPacket)
        {
            this.onPacket = onPacket;
        }

        public void Push(byte[] data, int offset, int count)
        {
            if (count <= 0) return;
            pending.Write(data, offset, count);
            byte[] buf = pending.GetBuffer();
            int len = (int)pending.Length;
            int pos = 0;

            while (pos + 7 <= len)
            {
                if (buf[pos] != 0xFF || (buf[pos + 1] & 0xF0) != 0xF0)
                {
                    pos++;
                    continue;
                }
                int hdrLen = (buf[pos + 1] & 0x01) == 0 ? 9 : 7;
                int frameLen = ((buf[pos + 3] & 0x03) << 11) | (buf[pos + 4] << 3) | ((buf[pos + 5] & 0xE0) >> 5);
                if (frameLen < hdrLen || frameLen > 16384)
                {
                    pos++;
                    continue;
                }
                if (pos + frameLen > len) break;

                if (!sentConfig)
                {
                    int objType = ((buf[pos + 2] >> 6) & 0x03) + 1;
                    int freqIdx = (buf[pos + 2] >> 2) & 0x0F;
                    int chanCfg = ((buf[pos + 2] & 0x01) << 2) | ((buf[pos + 3] >> 6) & 0x03);
                    byte[] csd = new byte[2];
                    csd[0] = (byte)((objType << 3) | (freqIdx >> 1));
                    csd[1] = (byte)(((freqIdx & 1) << 7) | (chanCfg << 3));
                    sentConfig = true;
                    onPacket(WireConstants.FlagAudio | WireConstants.FlagCodecConfig, csd);
                }

                int payloadLen = frameLen - hdrLen;
                if (payloadLen > 0)
                {
                    byte[] raw = new byte[payloadLen];
                    Buffer.BlockCopy(buf, pos + hdrLen, raw, 0, payloadLen);
                    onPacket(WireConstants.FlagAudio, raw);
                }
                pos += frameLen;
            }

            if (pos > 0)
            {
                int rem = len - pos;
                if (rem > 0)
                {
                    byte[] tail = new byte[rem];
                    Buffer.BlockCopy(buf, pos, tail, 0, rem);
                    pending.SetLength(0);
                    pending.Write(tail, 0, rem);
                }
                else
                {
                    pending.SetLength(0);
                }
            }
        }
    }

    /// <summary>
    /// Bounded low-latency video + audio packet queue (same design as Android FrameQueue).
    /// </summary>
    public sealed class FrameQueue
    {
        private const int MaxVideoDepth = 6;
        private const int MaxAudioDepth = 6;

        private readonly object sync = new object();
        private readonly LinkedList<Packet> videoQueue = new LinkedList<Packet>();
        private readonly LinkedList<Packet> audioQueue = new LinkedList<Packet>();
        private readonly LinkedList<Packet> controlQueue = new LinkedList<Packet>();
        private bool closed;
        private bool awaitingKeyFrame;
        private long droppedVideo;
        private long droppedAudio;

        public long DroppedVideo { get { lock (sync) { return droppedVideo; } } }
        public long DroppedAudio { get { lock (sync) { return droppedAudio; } } }

        public void Offer(Packet pkt)
        {
            lock (sync)
            {
                if (closed) return;
                if (pkt.IsControl)
                {
                    if (controlQueue.Count < 64) controlQueue.AddLast(pkt);
                    System.Threading.Monitor.PulseAll(sync);
                    return;
                }
                if (pkt.IsAudio)
                {
                    if (!pkt.IsConfig)
                    {
                        while (CountNonConfig(audioQueue) >= MaxAudioDepth)
                        {
                            if (!DropOldestNonConfig(audioQueue)) break;
                            droppedAudio++;
                        }
                    }
                    audioQueue.AddLast(pkt);
                    System.Threading.Monitor.PulseAll(sync);
                    return;
                }

                if (pkt.IsConfig)
                {
                    videoQueue.AddLast(pkt);
                    System.Threading.Monitor.PulseAll(sync);
                    return;
                }

                if (pkt.IsKeyFrame)
                {
                    awaitingKeyFrame = false;
                    if (CountNonConfig(videoQueue) >= 2) DropNonConfigVideo();
                    videoQueue.AddLast(pkt);
                    System.Threading.Monitor.PulseAll(sync);
                    return;
                }

                if (awaitingKeyFrame)
                {
                    droppedVideo++;
                    return;
                }

                if (CountNonConfig(videoQueue) >= MaxVideoDepth)
                {
                    DropNonConfigVideo();
                    awaitingKeyFrame = true;
                    droppedVideo++;
                    return;
                }

                videoQueue.AddLast(pkt);
                System.Threading.Monitor.PulseAll(sync);
            }
        }

        private static int CountNonConfig(LinkedList<Packet> list)
        {
            int count = 0;
            for (LinkedListNode<Packet> n = list.First; n != null; n = n.Next)
            {
                if (!n.Value.IsConfig) count++;
            }
            return count;
        }

        private static bool DropOldestNonConfig(LinkedList<Packet> list)
        {
            for (LinkedListNode<Packet> n = list.First; n != null; n = n.Next)
            {
                if (!n.Value.IsConfig)
                {
                    list.Remove(n);
                    return true;
                }
            }
            return false;
        }

        private void DropNonConfigVideo()
        {
            LinkedListNode<Packet> n = videoQueue.First;
            while (n != null)
            {
                LinkedListNode<Packet> next = n.Next;
                if (!n.Value.IsConfig)
                {
                    videoQueue.Remove(n);
                    droppedVideo++;
                }
                n = next;
            }
        }

        /// <summary>Drops queued video frames and waits for the next key frame (pause/resume, reconnect, receiver request).</summary>
        public void RequireKeyFrame()
        {
            lock (sync)
            {
                DropNonConfigVideo();
                awaitingKeyFrame = true;
            }
        }

        /// <summary>Drops everything incl. codec config (capture restarted with a new resolution).</summary>
        public void ClearVideo()
        {
            lock (sync)
            {
                droppedVideo += CountNonConfig(videoQueue);
                videoQueue.Clear();
                awaitingKeyFrame = false;
            }
        }

        public bool IsAwaitingKeyFrame { get { lock (sync) { return awaitingKeyFrame; } } }

        public Packet Poll(int timeoutMs)
        {
            lock (sync)
            {
                if (videoQueue.Count == 0 && audioQueue.Count == 0 && controlQueue.Count == 0 && !closed && timeoutMs > 0)
                {
                    System.Threading.Monitor.Wait(sync, timeoutMs);
                }
                if (controlQueue.Count > 0)
                {
                    Packet c = controlQueue.First.Value;
                    controlQueue.RemoveFirst();
                    return c;
                }
                if (videoQueue.Count > 0 && videoQueue.First.Value.IsConfig)
                {
                    Packet p = videoQueue.First.Value;
                    videoQueue.RemoveFirst();
                    return p;
                }
                if (audioQueue.Count > 0)
                {
                    Packet p = audioQueue.First.Value;
                    audioQueue.RemoveFirst();
                    return p;
                }
                if (videoQueue.Count > 0)
                {
                    Packet p = videoQueue.First.Value;
                    videoQueue.RemoveFirst();
                    return p;
                }
                return null;
            }
        }

        public void Close()
        {
            lock (sync)
            {
                closed = true;
                System.Threading.Monitor.PulseAll(sync);
            }
        }

        public bool IsClosed { get { lock (sync) { return closed; } } }
    }
}
