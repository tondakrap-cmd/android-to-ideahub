using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    public sealed class QualityProfile
    {
        public readonly int Index;
        public readonly string Label;
        public readonly int MaxSide;
        public readonly int Fps;
        public readonly int BitrateKbps;

        public QualityProfile(int index, string label, int maxSide, int fps, int bitrateKbps)
        {
            Index = index; Label = label; MaxSide = maxSide; Fps = fps; BitrateKbps = bitrateKbps;
        }

        public static readonly QualityProfile[] All = new QualityProfile[]
        {
            new QualityProfile(0, "Rychlá odezva (960p · 3 Mb/s)", 960, 30, 3000),
            new QualityProfile(1, "Vyvážený (1280p · 5 Mb/s)", 1280, 30, 5000),
            new QualityProfile(2, "Vysoká ostrost (1600p · 8 Mb/s)", 1600, 30, 8000),
            new QualityProfile(3, "Ultra ostrost (1920p · 12 Mb/s)", 1920, 30, 12000)
        };
    }

    /// <summary>H.264 encoder choices passed to ffmpeg.</summary>
    public static class Encoders
    {
        public static readonly string[] Ids = new string[] { "auto", "h264_nvenc", "h264_qsv", "h264_amf", "h264_mf", "libx264" };
        public static readonly string[] Labels = new string[]
        {
            "Automaticky (grafická karta, jinak procesor)",
            "NVIDIA NVENC", "Intel Quick Sync", "AMD AMF", "Windows Media Foundation", "Procesor (x264)"
        };

        public static string Describe(string id)
        {
            for (int i = 0; i < Ids.Length; i++) if (Ids[i] == id) return Labels[i];
            return id;
        }
    }

    public sealed class SessionConfig
    {
        public BoardEntry Board;
        public string FfmpegPath;
        public int ScreenIndex;
        public IntPtr WindowHandle;  // IntPtr.Zero = share a monitor
        public string WindowTitle;
        public QualityProfile Quality;
        public string Encoder = "auto";
        public bool AudioEnabled;
        public Func<int> GetVolumePercent;
        public bool MuteSpeakers;
        public bool AllowTouch;
        public string DeviceName;
    }

    /// <summary>
    /// One sharing session. Capture (ffmpeg video, WASAPI + ffmpeg AAC audio) runs for the whole session; the
    /// network link to the board is managed separately and re-established automatically after short Wi-Fi drops
    /// (v2 boards). The board can send back takeover/close notices, key-frame requests and touch input.
    /// </summary>
    public sealed class TabuleSession : IDisposable
    {
        private const int ReconnectWindowMs = 30000;

        private readonly SessionConfig cfg;
        private readonly Action<string> onStatus;
        private readonly Action<BoardEntry> onBoardUpdated;
        private readonly Action<string, bool> onStopped;
        private readonly Action<string> onNotice;

        private volatile bool running;
        private volatile bool paused;
        private volatile bool reconnecting;
        private Thread mainThread;
        private Process videoProc;
        private Process audioProc;
        private WasapiLoopback wasapi;
        private volatile Link link;
        private volatile byte[] lastVideoConfig;
        private volatile byte[] lastAudioConfig;
        private volatile int curW, curH;
        private Rectangle captureRect;
        private string stopReason;
        private bool stopIsError;
        private bool? speakerMutedBefore;

        public volatile string DiagPhase = "nečinný";
        public volatile string DiagCapture = "-";
        public volatile string DiagAudio = "vypnuto";
        public volatile string DiagError = "-";
        public volatile int DiagFps = 0;
        public volatile int DiagAvgWriteMs = 0;
        public long DiagVideoFrames;
        public long DiagAudioFrames;
        public long DiagDrops;
        public volatile string BoardName = "";

        public bool IsRunning { get { return running; } }
        public bool IsPaused { get { return paused; } }
        public bool IsReconnecting { get { return reconnecting; } }
        public BoardEntry Board { get { return cfg.Board; } }

        private sealed class Link
        {
            public TcpClient Tcp;
            public SslStream Ssl;
            public bool V2;
            public readonly FrameQueue Queue = new FrameQueue();
            public volatile string EndNotice;
            public volatile bool Dead;
            public long WritingSince; // Environment.TickCount when a write started, 0 = idle
            public void Close()
            {
                Dead = true;
                Queue.Close();
                try { Ssl.Close(); } catch { }
                try { Tcp.Close(); } catch { }
            }
        }

        public TabuleSession(SessionConfig cfg, Action<string> onStatus, Action<BoardEntry> onBoardUpdated, Action<string, bool> onStopped, Action<string> onNotice)
        {
            this.cfg = cfg;
            this.onStatus = onStatus;
            this.onBoardUpdated = onBoardUpdated;
            this.onStopped = onStopped;
            this.onNotice = onNotice;
            BoardName = cfg.Board.Name;
        }

        public static string FindFfmpeg(string customPath)
        {
            if (!string.IsNullOrEmpty(customPath))
            {
                try { if (File.Exists(customPath)) return customPath; } catch { }
            }

            // 1. Next to TabuleLink.exe
            try
            {
                string exeDir = Path.GetDirectoryName(Application.ExecutablePath);
                if (!string.IsNullOrEmpty(exeDir))
                {
                    string[] localCandidates = new string[]
                    {
                        Path.Combine(exeDir, "ffmpeg.exe"),
                        Path.Combine(exeDir, "bin", "ffmpeg.exe"),
                        Path.Combine(exeDir, "ffmpeg", "bin", "ffmpeg.exe")
                    };
                    for (int i = 0; i < localCandidates.Length; i++)
                    {
                        if (File.Exists(localCandidates[i])) return localCandidates[i];
                    }
                }
            }
            catch { }

            // 2. App's LocalAppData folder
            string localApp = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            try
            {
                string appFfmpeg = Path.Combine(localApp, "TabuleLink", "ffmpeg.exe");
                if (File.Exists(appFfmpeg)) return appFfmpeg;
            }
            catch { }

            // 3. Process, User, and Machine PATH (handles freshly installed FFmpeg before Explorer restarts)
            string[] pathVars = new string[]
            {
                Environment.GetEnvironmentVariable("PATH"),
                SafeEnv("PATH", EnvironmentVariableTarget.User),
                SafeEnv("PATH", EnvironmentVariableTarget.Machine)
            };
            for (int p = 0; p < pathVars.Length; p++)
            {
                string pathEnv = pathVars[p];
                if (string.IsNullOrEmpty(pathEnv)) continue;
                string[] dirs = pathEnv.Split(';');
                for (int i = 0; i < dirs.Length; i++)
                {
                    string d = dirs[i].Trim().Trim('"');
                    if (d.Length == 0) continue;
                    try
                    {
                        string cand = Path.Combine(d, "ffmpeg.exe");
                        if (File.Exists(cand)) return cand;
                    }
                    catch { }
                }
            }

            // 4. Common Windows / WinGet install locations
            try
            {
                string wingetLink = Path.Combine(localApp, "Microsoft", "WinGet", "Links", "ffmpeg.exe");
                if (File.Exists(wingetLink)) return wingetLink;
                string wingetPkgs = Path.Combine(localApp, "Microsoft", "WinGet", "Packages");
                if (Directory.Exists(wingetPkgs))
                {
                    string[] dirs = Directory.GetDirectories(wingetPkgs, "*FFmpeg*");
                    for (int i = 0; i < dirs.Length; i++)
                    {
                        string[] found = Directory.GetFiles(dirs[i], "ffmpeg.exe", SearchOption.AllDirectories);
                        if (found.Length > 0) return found[0];
                    }
                }
            }
            catch { }

            string[] commonPaths = new string[]
            {
                @"C:\ffmpeg\bin\ffmpeg.exe",
                @"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
                @"C:\Program Files (x86)\ffmpeg\bin\ffmpeg.exe"
            };
            for (int i = 0; i < commonPaths.Length; i++)
            {
                try { if (File.Exists(commonPaths[i])) return commonPaths[i]; } catch { }
            }

            return null;
        }

        private static string SafeEnv(string name, EnvironmentVariableTarget target)
        {
            try { return Environment.GetEnvironmentVariable(name, target); } catch { return null; }
        }

        // ------------------------------------------------------------------ pairing (no streaming)

        private static SslStream OpenTls(string host, int port, string fp, int timeoutMs, out TcpClient tcp)
        {
            tcp = new TcpClient();
            tcp.NoDelay = true;
            tcp.SendBufferSize = 256 * 1024;
            tcp.ReceiveBufferSize = 128 * 1024;
            IAsyncResult ar = tcp.BeginConnect(host, port, null, null);
            if (!ar.AsyncWaitHandle.WaitOne(timeoutMs, false))
            {
                try { tcp.Close(); } catch { }
                throw new IOException("Vypršel časový limit připojení k tabuli " + host + ":" + port.ToString(CultureInfo.InvariantCulture) + ".");
            }
            tcp.EndConnect(ar);
            tcp.SendTimeout = 8000;
            tcp.ReceiveTimeout = 8000;
            string expected = fp.ToLowerInvariant();
            SslStream ssl = new SslStream(tcp.GetStream(), false, delegate(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errs)
            {
                if (cert == null) return false;
                byte[] hash;
                using (SHA256 sha = SHA256.Create()) hash = sha.ComputeHash(cert.GetRawCertData());
                return Spake2.MacEquals(Spake2.Hex(hash), expected);
            });
            try
            {
                ssl.AuthenticateAsClient(host, null, SslProtocols.Tls12, false);
            }
            catch
            {
                try { ssl.Close(); } catch { }
                try { tcp.Close(); } catch { }
                throw;
            }
            return ssl;
        }

        private static BoardEntry FromResult(string fp, string host, int port, ClientResult r)
        {
            BoardEntry b = new BoardEntry();
            b.Name = r.BoardName;
            b.Fingerprint = fp;
            b.Host = host;
            b.Port = port;
            b.DeviceId = r.DeviceId;
            b.DeviceKey = r.DeviceKey;
            b.LastUsed = DateTime.UtcNow.Ticks;
            return b;
        }

        /// <summary>LAN discovery offer + 6-digit PIN (SPAKE2: the PIN never leaves the notebook).</summary>
        public static BoardEntry PairWithPin(DiscoveryOffer offer, string pin, string deviceName)
        {
            TcpClient tcp;
            SslStream ssl = OpenTls(offer.Ip, offer.Port, offer.Fingerprint, 8000, out tcp);
            try
            {
                ClientResult r = Proto2.PairPin(ssl, pin, offer.Fingerprint, deviceName, new string[] { Proto2.CapCtl, Proto2.CapPair });
                BoardEntry b = FromResult(offer.Fingerprint, offer.Ip, offer.Port, r);
                if (b.Name == "Tabule Link") b.Name = offer.Name;
                return b;
            }
            catch (OldServerException)
            {
                throw new IOException("Tabule má starší verzi Tabule Link (0.6). Aktualizujte ji, nebo vložte ruční kód z tabule.");
            }
            finally
            {
                try { ssl.Close(); } catch { }
                try { tcp.Close(); } catch { }
            }
        }

        /// <summary>tabulelink:// code: one-time token (0.7 boards) or the shared secret of 0.6 boards.</summary>
        public static BoardEntry PairWithCode(PairCode code, string deviceName)
        {
            TcpClient tcp;
            SslStream ssl = OpenTls(code.Host, code.Port, code.Fingerprint, 8000, out tcp);
            try
            {
                ClientResult r = Proto2.Hello(ssl, "TOKEN", "-", code.Secret, deviceName, new string[] { Proto2.CapCtl, Proto2.CapPair });
                return FromResult(code.Fingerprint, code.Host, code.Port, r);
            }
            catch (OldServerException)
            {
                try { ssl.Close(); } catch { }
                try { tcp.Close(); } catch { }
                ssl = OpenTls(code.Host, code.Port, code.Fingerprint, 8000, out tcp);
                Proto2.Legacy(ssl, code.Secret);
                BoardEntry b = new BoardEntry();
                b.Name = "Tabule " + code.Host + " (0.6)";
                b.Fingerprint = code.Fingerprint;
                b.Host = code.Host;
                b.Port = code.Port;
                b.LegacySecret = code.Secret;
                b.LastUsed = DateTime.UtcNow.Ticks;
                return b;
            }
            finally
            {
                try { ssl.Close(); } catch { }
                try { tcp.Close(); } catch { }
            }
        }

        // ------------------------------------------------------------------ public control

        public string BuildDiagnostics()
        {
            return "Windows " + Environment.OSVersion.VersionString + " · Tabule Link " + WireConstants.Version
                + "\r\nCíl: " + BoardName + " (" + cfg.Board.Address + ")" + (link != null && !link.V2 ? " · starší tabule 0.6" : "")
                + "\r\nZdroj: " + (cfg.WindowHandle != IntPtr.Zero ? "okno „" + cfg.WindowTitle + "“" : "monitor " + (cfg.ScreenIndex + 1))
                + "\r\nFáze: " + DiagPhase + (paused ? " · pozastaveno" : "") + (reconnecting ? " · obnovuji spojení" : "")
                + "\r\nVideo: " + DiagCapture
                + "\r\nZvuk: " + DiagAudio + " (" + Interlocked.Read(ref DiagAudioFrames) + " rámců)"
                + "\r\nFPS / zápis: " + DiagFps + " fps · ~" + DiagAvgWriteMs + " ms"
                + "\r\nOdeslané / přeskočené snímky: " + Interlocked.Read(ref DiagVideoFrames) + " / " + Interlocked.Read(ref DiagDrops)
                + "\r\nDotykové ovládání z tabule: " + (cfg.AllowTouch ? "povoleno" : "vypnuto")
                + "\r\nPoslední chyba: " + DiagError;
        }

        public void Start()
        {
            if (running) return;
            running = true;
            mainThread = new Thread(Run);
            mainThread.IsBackground = true;
            mainThread.Name = "tabule-win-session";
            mainThread.Start();
        }

        public void Stop()
        {
            if (!running) return;
            Finish("Vysílání ukončeno.", false);
        }

        public void Dispose() { Stop(); }

        /// <summary>Freezes the board on the last frame (audio keeps playing).</summary>
        public void SetPaused(bool p)
        {
            paused = p;
            Link l = link;
            if (l != null && l.V2) l.Queue.Offer(Proto2.Control(p ? Proto2.CtlPause : Proto2.CtlResume));
            if (!p && l != null) l.Queue.RequireKeyFrame();
        }

        private void Report(string phase, string message)
        {
            DiagPhase = phase;
            if (onStatus != null && message != null) onStatus(message);
        }

        private void Finish(string reason, bool error)
        {
            lock (this)
            {
                if (stopReason == null) { stopReason = reason; stopIsError = error; }
            }
            running = false;
            Link l = link;
            if (l != null) SayBye(l);
            KillProcess(ref videoProc);
            KillProcess(ref audioProc);
            WasapiLoopback w = wasapi;
            if (w != null) { try { w.Dispose(); } catch { } }
        }

        private static void SayBye(Link l)
        {
            if (l.V2 && !l.Dead)
            {
                l.Queue.Offer(Proto2.Control(Proto2.CtlBye));
                ThreadPool.QueueUserWorkItem(delegate { Thread.Sleep(250); l.Close(); });
            }
            else
            {
                l.Close();
            }
        }

        // ------------------------------------------------------------------ main

        private void Run()
        {
            Thread linkThread = null;
            Thread audioReader = null;
            try
            {
                string ffmpegPath = FindFfmpeg(cfg.FfmpegPath);
                if (string.IsNullOrEmpty(ffmpegPath))
                {
                    throw new FileNotFoundException("Nebyl nalezen program ffmpeg.exe. Klikněte na „Nainstalovat FFmpeg“ nebo „Vybrat ffmpeg.exe“.");
                }
                Rectangle rect = CurrentSourceRect();
                if (rect.Width < 16 || rect.Height < 16) throw new IOException("Sdílené okno nebo monitor není k dispozici.");
                captureRect = rect;
                int[] g = VideoGeometry.Fit(rect.Width, rect.Height, cfg.Quality.MaxSide);
                curW = g[0];
                curH = g[1];

                if (cfg.MuteSpeakers) speakerMutedBefore = Native.SetSpeakerMute(true);

                linkThread = new Thread(ManageLink);
                linkThread.IsBackground = true;
                linkThread.Name = "tabule-win-link";
                linkThread.Start();

                if (cfg.AudioEnabled) audioReader = StartAudioPipeline(ffmpegPath);
                else DiagAudio = "vypnuto uživatelem";

                RunCaptureLoop(ffmpegPath);
            }
            catch (Exception ex)
            {
                if (running)
                {
                    DiagError = ex.GetType().Name + ": " + ex.Message;
                    Finish("Sdílení se nepodařilo: " + ex.Message, true);
                }
            }
            finally
            {
                if (running) Finish("Zachytávání obrazovky skončilo.", true);
                running = false;
                if (linkThread != null) { try { linkThread.Join(2000); } catch { } }
                if (audioReader != null) { try { audioReader.Join(800); } catch { } }
                if (wasapi != null) { try { wasapi.Dispose(); } catch { } wasapi = null; }
                if (speakerMutedBefore.HasValue && !speakerMutedBefore.Value) Native.SetSpeakerMute(false);
                if (onStopped != null) onStopped(stopReason ?? "Vysílání ukončeno.", stopIsError);
            }
        }

        private Rectangle CurrentSourceRect()
        {
            if (cfg.WindowHandle != IntPtr.Zero)
            {
                if (!Native.IsWindow(cfg.WindowHandle)) return Rectangle.Empty;
                return Native.WindowBounds(cfg.WindowHandle);
            }
            Screen[] screens = Screen.AllScreens;
            int idx = cfg.ScreenIndex < 0 || cfg.ScreenIndex >= screens.Length ? 0 : cfg.ScreenIndex;
            return screens[idx].Bounds;
        }

        private void Offer(Packet p)
        {
            Link l = link;
            if (l == null || l.Dead) return;
            if (p.IsControl && !l.V2) return;
            l.Queue.Offer(p);
        }

        // ------------------------------------------------------------------ network link

        private Link Connect(bool resume)
        {
            BoardEntry b = cfg.Board;
            TcpClient tcp;
            SslStream ssl;
            try
            {
                ssl = OpenTls(b.Host, b.Port, b.Fingerprint, resume ? 4000 : 6000, out tcp);
            }
            catch (Exception ex)
            {
                if (ex is AuthenticationException) throw;
                Report("hledám tabuli", "Hledám tabuli v síti (mohla změnit adresu)…");
                DiscoveryOffer o = DiscoveryOffer.FindByFingerprint(b.Fingerprint, 1500);
                if (o == null || (o.Ip == b.Host && o.Port == b.Port)) throw;
                b.Host = o.Ip;
                b.Port = o.Port;
                if (onBoardUpdated != null) onBoardUpdated(b.Clone());
                ssl = OpenTls(b.Host, b.Port, b.Fingerprint, 6000, out tcp);
            }
            Link l = new Link();
            l.Tcp = tcp;
            l.Ssl = ssl;
            try
            {
                if (b.HasV2Key)
                {
                    List<string> caps = new List<string>();
                    caps.Add(Proto2.CapCtl);
                    caps.Add(Proto2.CapFmt);
                    if (cfg.AllowTouch) caps.Add(Proto2.CapTouch);
                    if (resume) caps.Add(Proto2.CapResume);
                    ClientResult r = Proto2.Hello(ssl, "KEY", b.DeviceId, b.DeviceKey, cfg.DeviceName, caps);
                    l.V2 = true;
                    if (r.BoardName != b.Name)
                    {
                        b.Name = r.BoardName;
                        BoardName = r.BoardName;
                        if (onBoardUpdated != null) onBoardUpdated(b.Clone());
                    }
                }
                else if (b.LegacySecret != null)
                {
                    Proto2.Legacy(ssl, b.LegacySecret);
                    l.V2 = false;
                }
                else
                {
                    throw new ProtoException(Proto2.ErrAuth, "Tabule není spárována. Zadejte PIN nebo vložte kód z tabule.");
                }
                Proto2.WriteHeader(ssl, curW, curH);
                if (l.V2)
                {
                    tcp.ReceiveTimeout = 45000;
                    Proto2.AwaitGo(ssl, delegate { Report("čekám na schválení", "Čekám na schválení převzetí na tabuli…"); });
                }
                tcp.ReceiveTimeout = 0;
                tcp.SendTimeout = 0;
                return l;
            }
            catch
            {
                l.Close();
                throw;
            }
        }

        private void ManageLink()
        {
            bool resume = false;
            int reconnectUntil = 0;
            int attempt = 0;
            while (running)
            {
                Link l;
                try
                {
                    Report(resume ? "obnovuji spojení" : "připojení", resume ? null : "Připojuji se k " + cfg.Board.Name + "…");
                    l = Connect(resume);
                }
                catch (ProtoException ex)
                {
                    Finish(ex.Message, ex.Code != Proto2.ErrBusy);
                    return;
                }
                catch (Exception ex)
                {
                    if (!running) return;
                    if (resume && Environment.TickCount - reconnectUntil < 0)
                    {
                        attempt++;
                        Report("obnovuji spojení", "Spojení s tabulí přerušeno — obnovuji (pokus " + attempt + ")…");
                        Thread.Sleep(Math.Min(3000, 700 * attempt));
                        continue;
                    }
                    DiagError = ex.GetType().Name + ": " + ex.Message;
                    string hint = LocalNet.SubnetMismatchHint(cfg.Board.Host);
                    Finish("Připojení k tabuli se nezdařilo: " + ex.Message + (string.IsNullOrEmpty(hint) ? "" : "\r\n" + hint), true);
                    return;
                }

                byte[] vcfg = lastVideoConfig;
                if (vcfg != null) l.Queue.Offer(new Packet(WireConstants.FlagCodecConfig, 0, vcfg));
                byte[] acfg = lastAudioConfig;
                if (acfg != null) l.Queue.Offer(new Packet(WireConstants.FlagAudio | WireConstants.FlagCodecConfig, 0, acfg));
                if (paused && l.V2) l.Queue.Offer(Proto2.Control(Proto2.CtlPause));
                l.Queue.RequireKeyFrame();
                link = l;
                if (reconnecting && onNotice != null) onNotice("Spojení s tabulí obnoveno.");
                reconnecting = false;
                attempt = 0;
                Report("vysílání", "Vysílám na " + BoardName + " · " + curW + " × " + curH + (cfg.AudioEnabled ? " · obraz + zvuk" : " · jen obraz") + (l.V2 ? "" : " · starší tabule") + " · šifrováno");

                Thread writer = new Thread(delegate() { WriteLoop(l); });
                writer.IsBackground = true;
                writer.Priority = ThreadPriority.AboveNormal;
                writer.Name = "tabule-win-net";
                writer.Start();
                if (l.V2)
                {
                    Thread reader = new Thread(delegate() { ReadLoop(l); });
                    reader.IsBackground = true;
                    reader.Name = "tabule-win-ctl";
                    reader.Start();
                }
                while (running && !l.Dead)
                {
                    Thread.Sleep(300);
                    long since = Interlocked.Read(ref l.WritingSince);
                    if (since != 0 && Environment.TickCount - (int)since > 6000)
                    {
                        DiagError = "Zápis do sítě se zasekl (>6 s)";
                        l.Close();
                    }
                }
                if (!running) { SayBye(l); link = null; return; }
                l.Close();
                link = null;
                try { writer.Join(1000); } catch { }
                string notice = l.EndNotice;
                if (notice == Proto2.CtlEndTakeover) { Finish("Sdílení převzal jiný prezentující.", false); return; }
                if (notice == Proto2.CtlEndClosed) { Finish("Prezentaci ukončila tabule.", false); return; }
                if (notice == Proto2.CtlEndRevoked) { Finish("Tabule tento notebook odvolala. Spárujte jej znovu.", true); return; }
                if (!l.V2) { Finish("Vysílání ukončeno (tabule příjem zavřela nebo sdílení převzal jiný uživatel).", false); return; }
                resume = true;
                reconnecting = true;
                reconnectUntil = Environment.TickCount + ReconnectWindowMs;
                if (onNotice != null) onNotice("Spojení s tabulí přerušeno — obnovuji…");
                Report("obnovuji spojení", "Spojení s tabulí přerušeno — obnovuji…");
            }
        }

        private void WriteLoop(Link l)
        {
            byte[] scratch = new byte[256 * 1024];
            Stopwatch clock = Stopwatch.StartNew();
            long lastWrite = 0, fpsStart = 0, fpsFrames = 0, writeSum = 0, writes = 0;
            try
            {
                while (!l.Dead)
                {
                    Packet pkt = l.Queue.Poll(1500);
                    long now = clock.ElapsedMilliseconds;
                    if (pkt == null)
                    {
                        if (now - lastWrite >= 1800)
                        {
                            Interlocked.Exchange(ref l.WritingSince, Environment.TickCount | 1);
                            WireIO.WriteHeartbeat(l.Ssl);
                            Interlocked.Exchange(ref l.WritingSince, 0);
                            lastWrite = clock.ElapsedMilliseconds;
                        }
                        continue;
                    }
                    Interlocked.Exchange(ref l.WritingSince, Environment.TickCount | 1);
                    scratch = WireIO.WritePacketSingleRecord(l.Ssl, scratch, pkt);
                    Interlocked.Exchange(ref l.WritingSince, 0);
                    lastWrite = clock.ElapsedMilliseconds;
                    writeSum += lastWrite - now;
                    writes++;
                    if (!pkt.IsAudio && !pkt.IsConfig && !pkt.IsControl)
                    {
                        Interlocked.Increment(ref DiagVideoFrames);
                        fpsFrames++;
                    }
                    Interlocked.Exchange(ref DiagDrops, l.Queue.DroppedVideo);
                    if (lastWrite - fpsStart >= 1000)
                    {
                        DiagFps = (int)Math.Round(fpsFrames * 1000.0 / Math.Max(1, lastWrite - fpsStart));
                        DiagAvgWriteMs = (int)(writeSum / Math.Max(1, writes));
                        fpsStart = lastWrite; fpsFrames = 0; writeSum = 0; writes = 0;
                    }
                }
            }
            catch (Exception ex)
            {
                if (running) DiagError = "Síť: " + ex.Message;
            }
            finally
            {
                Interlocked.Exchange(ref l.WritingSince, 0);
                l.Close();
            }
        }

        /// <summary>Back-channel from the board: end notices, key-frame requests, touch input.</summary>
        private void ReadLoop(Link l)
        {
            try
            {
                byte[] hdr;
                while (!l.Dead)
                {
                    hdr = WireIO.ReadExact(l.Ssl, WireConstants.HeaderSize);
                    int len = (hdr[0] << 24) | (hdr[1] << 16) | (hdr[2] << 8) | hdr[3];
                    int flags = (hdr[4] << 24) | (hdr[5] << 16) | (hdr[6] << 8) | hdr[7];
                    if (len < 0 || len > 4096) throw new IOException("Neplatná zpráva tabule.");
                    byte[] payload = len > 0 ? WireIO.ReadExact(l.Ssl, len) : new byte[0];
                    if ((flags & Proto2.FlagControl) == 0) continue;
                    string cmd = Encoding.UTF8.GetString(payload);
                    if (cmd.StartsWith("end:", StringComparison.Ordinal))
                    {
                        l.EndNotice = cmd;
                        l.Close();
                    }
                    else if (cmd.StartsWith("t:", StringComparison.Ordinal))
                    {
                        HandleTouch(cmd);
                    }
                    else if (cmd == Proto2.CtlKeyFrame)
                    {
                        // GOP is 1 s, the next key frame is close; nothing to do.
                    }
                }
            }
            catch { }
            finally { l.Close(); }
        }

        private void HandleTouch(string cmd)
        {
            if (!cfg.AllowTouch) return;
            int[] t = Proto2.ParseTouch(cmd);
            if (t == null) return;
            Rectangle r = cfg.WindowHandle != IntPtr.Zero ? Native.WindowBounds(cfg.WindowHandle) : captureRect;
            if (r.Width <= 0 || r.Height <= 0) return;
            int x = r.Left + (int)Math.Round(t[1] / 65535.0 * (r.Width - 1));
            int y = r.Top + (int)Math.Round(t[2] / 65535.0 * (r.Height - 1));
            try { Native.InjectMouse(x, y, (char)t[0]); } catch { }
        }

        // ------------------------------------------------------------------ video capture

        private static string encoderList;

        private static bool EncoderAvailable(string ffmpegPath, string id)
        {
            if (id == "libx264") return true;
            if (encoderList == null)
            {
                try
                {
                    using (Process p = StartHiddenProcess(ffmpegPath, "-hide_banner -encoders", false, true))
                    {
                        encoderList = p.StandardOutput.ReadToEnd();
                        p.WaitForExit(5000);
                    }
                }
                catch { encoderList = ""; }
            }
            return encoderList.Contains(" " + id + " ");
        }

        private List<string> EncoderCandidates(string ffmpegPath)
        {
            List<string> list = new List<string>();
            if (cfg.Encoder != null && cfg.Encoder != "auto")
            {
                if (EncoderAvailable(ffmpegPath, cfg.Encoder)) list.Add(cfg.Encoder);
            }
            else
            {
                string[] order = new string[] { "h264_nvenc", "h264_qsv", "h264_amf" };
                foreach (string e in order) if (EncoderAvailable(ffmpegPath, e)) list.Add(e);
            }
            if (!list.Contains("libx264")) list.Add("libx264");
            return list;
        }

        /// <summary>ffmpeg encoder arguments: 1 s GOP (0.6 used 5 s → up to 5 s freezes after packet loss), no B-frames.</summary>
        public static string EncoderArgs(string id, int kbps)
        {
            int buf = Math.Max(1000, kbps / 2);
            string rate = string.Format(CultureInfo.InvariantCulture, "-b:v {0}k -maxrate {0}k -bufsize {1}k", kbps, buf);
            switch (id)
            {
                case "h264_nvenc":
                    return "-c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -delay 0 -rc cbr " + rate + " -bf 0 -g 30 -forced-idr 1 -profile:v main -aud 1";
                case "h264_qsv":
                    return "-c:v h264_qsv -preset veryfast -look_ahead 0 -async_depth 1 " + rate + " -bf 0 -g 30 -idr_interval 0 -profile:v main -aud 1";
                case "h264_amf":
                    return "-c:v h264_amf -usage ultralowlatency -quality speed -rc cbr " + rate + " -bf 0 -g 30 -header_insertion_mode idr -aud 1";
                case "h264_mf":
                    return "-c:v h264_mf -hw_encoding 1 -rate_control cbr -scenario display_remoting " + rate + " -bf 0 -g 30";
                default:
                    return "-c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -bf 0 -g 30 -keyint_min 30 " + rate
                        + " -x264-params \"repeat-headers=1:scenecut=0:aud=1:slices=1\"";
            }
        }

        private string CaptureArgs(string method, string encoderId, int w, int h)
        {
            int fps = cfg.Quality.Fps;
            string pix = encoderId == "libx264" ? "yuv420p" : "nv12";
            string scale = string.Format(CultureInfo.InvariantCulture, "scale={0}:{1}:flags=bilinear,format={2}", w, h, pix);
            if (method == "ddagrab")
            {
                Screen[] screens = Screen.AllScreens;
                int idx = cfg.ScreenIndex < 0 || cfg.ScreenIndex >= screens.Length ? 0 : cfg.ScreenIndex;
                int adapter, output;
                if (!Native.FindDxgiOutput(screens[idx].DeviceName, out adapter, out output)) { adapter = 0; output = idx; }
                DiagCapture = "ddagrab (GPU " + adapter + ", výstup " + output + ")";
                return string.Format(CultureInfo.InvariantCulture,
                    "-hide_banner -loglevel error -init_hw_device d3d11va=tl:{0} -filter_hw_device tl -filter_complex \"ddagrab=output_idx={1}:framerate={2}:draw_mouse=1,hwdownload,format=bgra,{3}\"",
                    adapter, output, fps, scale);
            }
            if (method == "gdigrab-window")
            {
                DiagCapture = "gdigrab okno";
                return string.Format(CultureInfo.InvariantCulture, "-hide_banner -loglevel error -f gdigrab -framerate {0} -draw_mouse 1 -i {1} -vf \"{2}\"",
                    fps, Native.QuoteArg("title=" + cfg.WindowTitle), scale);
            }
            Rectangle r = CurrentSourceRect();
            DiagCapture = "gdigrab oblast";
            return string.Format(CultureInfo.InvariantCulture,
                "-hide_banner -loglevel error -f gdigrab -framerate {0} -offset_x {1} -offset_y {2} -video_size {3}x{4} -draw_mouse 1 -i desktop -vf \"{5}\"",
                fps, r.X, r.Y, r.Width - (r.Width % 2), r.Height - (r.Height % 2), scale);
        }

        private List<string> CaptureMethods()
        {
            List<string> m = new List<string>();
            if (cfg.WindowHandle != IntPtr.Zero) { m.Add("gdigrab-window"); m.Add("gdigrab-region"); }
            else { m.Add("ddagrab"); m.Add("gdigrab-region"); }
            return m;
        }

        /// <summary>
        /// Finds a working capture+encoder combination (GPU first, CPU fallback) and keeps capture running for the
        /// whole session, restarting ffmpeg when it exits (resolution change, UAC/secure desktop, window resized).
        /// </summary>
        private void RunCaptureLoop(string ffmpegPath)
        {
            string workingMethod = null, workingEncoder = null;
            int restarts = 0;
            int restartWindowStart = Environment.TickCount;
            string lastError = "";
            while (running)
            {
                // geometry may have changed (monitor resolution, window size)
                Rectangle rect = CurrentSourceRect();
                if (rect.Width < 16 || rect.Height < 16)
                {
                    if (cfg.WindowHandle != IntPtr.Zero && !Native.IsWindow(cfg.WindowHandle)) { Finish("Sdílené okno bylo zavřeno.", false); return; }
                    Thread.Sleep(500);
                    continue;
                }
                captureRect = rect;
                int[] g = VideoGeometry.Fit(rect.Width, rect.Height, cfg.Quality.MaxSide);
                Link l = link;
                if ((g[0] != curW || g[1] != curH) && (l == null || l.V2))
                {
                    curW = g[0];
                    curH = g[1];
                    lastVideoConfig = null;
                    if (l != null)
                    {
                        l.Queue.ClearVideo();
                        l.Queue.Offer(Proto2.Control(Proto2.Fmt(curW, curH)));
                    }
                }

                bool produced = false;
                if (workingMethod != null)
                {
                    produced = RunPipeline(ffmpegPath, workingMethod, workingEncoder, out lastError);
                }
                else
                {
                    foreach (string method in CaptureMethods())
                    {
                        foreach (string enc in EncoderCandidates(ffmpegPath))
                        {
                            if (!running) return;
                            Report("spouštím zachytávání", "Spouštím zachytávání (" + method + " + " + Encoders.Describe(enc) + ")…");
                            if (RunPipeline(ffmpegPath, method, enc, out lastError))
                            {
                                workingMethod = method;
                                workingEncoder = enc;
                                produced = true;
                                break;
                            }
                        }
                        if (produced) break;
                    }
                }
                if (!running) return;
                if (workingMethod == null)
                {
                    throw new IOException("Zachytávání obrazovky (ffmpeg) nevydalo žádný snímek" + (lastError.Length > 0 ? ": " + lastError : "."));
                }
                // ffmpeg ended after producing frames → restart (bounded)
                if (Environment.TickCount - restartWindowStart > 60000) { restartWindowStart = Environment.TickCount; restarts = 0; }
                restarts++;
                if (restarts > 6) throw new IOException("Zachytávání obrazovky se opakovaně zastavilo: " + lastError);
                if (!produced) { workingMethod = null; workingEncoder = null; }
                Report("obnovuji zachytávání", "Obnovuji zachytávání obrazovky…");
                Thread.Sleep(400);
            }
        }

        /// <summary>Runs one ffmpeg video process until it exits. Returns true if it produced at least one frame.</summary>
        private bool RunPipeline(string ffmpegPath, string method, string encoder, out string error)
        {
            error = "";
            int w = curW, h = curH;
            string args = CaptureArgs(method, encoder, w, h) + " " + EncoderArgs(encoder, cfg.Quality.BitrateKbps) + " -f h264 pipe:1";
            string captureDesc = DiagCapture + " + " + Encoders.Describe(encoder) + " (" + w + "x" + h + "@" + cfg.Quality.Fps + ", " + (cfg.Quality.BitrateKbps / 1000) + " Mb/s)";
            Process p;
            try { p = StartHiddenProcess(ffmpegPath, args, false, true); }
            catch (Exception ex) { error = ex.Message; return false; }
            videoProc = p;
            StringBuilder stderr = new StringBuilder();
            Thread errThread = new Thread(delegate()
            {
                try
                {
                    string line;
                    while ((line = p.StandardError.ReadLine()) != null) { lock (stderr) { if (stderr.Length < 1500) stderr.AppendLine(line); } }
                }
                catch { }
            });
            errThread.IsBackground = true;
            errThread.Start();

            bool gotFrame = false;
            int startTick = Environment.TickCount;
            System.Threading.Timer firstFrameWatchdog = new System.Threading.Timer(delegate
            {
                if (!gotFrame) { try { if (!p.HasExited) p.Kill(); } catch { } }
            }, null, 7000, Timeout.Infinite);

            AnnexBParser parser = new AnnexBParser(delegate(int flags, byte[] data)
            {
                if ((flags & WireConstants.FlagCodecConfig) != 0)
                {
                    lastVideoConfig = data;
                    Offer(new Packet(flags, 0, data));
                    return;
                }
                if (!gotFrame)
                {
                    gotFrame = true;
                    DiagCapture = captureDesc;
                }
                if (paused) return;
                Offer(new Packet(flags, (long)(Environment.TickCount - startTick) * 1000L, data));
            });

            try
            {
                byte[] ioBuf = new byte[65536];
                Stream stdout = p.StandardOutput.BaseStream;
                while (running)
                {
                    int n = stdout.Read(ioBuf, 0, ioBuf.Length);
                    if (n <= 0) break;
                    parser.Push(ioBuf, 0, n);
                }
                parser.Flush();
            }
            catch (Exception ex)
            {
                error = ex.Message;
            }
            finally
            {
                firstFrameWatchdog.Dispose();
                KillProcess(ref videoProc);
                try { errThread.Join(300); } catch { }
            }
            lock (stderr) { if (stderr.Length > 0) error = stderr.ToString().Trim(); }
            return gotFrame;
        }

        // ------------------------------------------------------------------ audio

        private Thread StartAudioPipeline(string ffmpegPath)
        {
            try
            {
                wasapi = new WasapiLoopback();
                wasapi.Open();
                string args = string.Format(CultureInfo.InvariantCulture,
                    "-hide_banner -loglevel error -f s16le -ar {0} -ac 2 -i pipe:0 -ar 48000 -ac 2 -c:a aac -profile:a aac_low -b:a 128k -flush_packets 1 -f adts pipe:1",
                    wasapi.SampleRate);
                Process ap = StartHiddenProcess(ffmpegPath, args, true, true);
                audioProc = ap;
                DiagAudio = wasapi.FormatDescription + " → AAC 48 kHz" + (cfg.MuteSpeakers ? " · reproduktory ztlumeny" : "");
                Stream stdin = ap.StandardInput.BaseStream;
                AdtsParser adts = new AdtsParser(delegate(int flags, byte[] data)
                {
                    if ((flags & WireConstants.FlagCodecConfig) != 0) lastAudioConfig = data;
                    Offer(new Packet(flags, 0, data));
                    if ((flags & WireConstants.FlagCodecConfig) == 0) Interlocked.Increment(ref DiagAudioFrames);
                });
                Thread reader = new Thread(delegate()
                {
                    byte[] buf = new byte[8192];
                    try
                    {
                        Stream aOut = ap.StandardOutput.BaseStream;
                        while (running)
                        {
                            int n = aOut.Read(buf, 0, buf.Length);
                            if (n <= 0) break;
                            adts.Push(buf, 0, n);
                        }
                    }
                    catch { }
                });
                reader.IsBackground = true;
                reader.Priority = ThreadPriority.AboveNormal;
                reader.Name = "tabule-win-aac";
                reader.Start();
                wasapi.Start(
                    delegate(byte[] pcm, int len)
                    {
                        if (!running) return;
                        try { stdin.Write(pcm, 0, len); stdin.Flush(); } catch { }
                    },
                    cfg.GetVolumePercent ?? (Func<int>)delegate() { return 70; },
                    delegate(Exception ex) { DiagAudio = "chyba zvuku: " + ex.Message; });
                return reader;
            }
            catch (Exception ex)
            {
                DiagAudio = "nedostupné (" + ex.Message + ")";
                KillProcess(ref audioProc);
                if (wasapi != null) { try { wasapi.Dispose(); } catch { } wasapi = null; }
                return null;
            }
        }

        private static Process StartHiddenProcess(string fileName, string arguments, bool redirectStdin, bool redirectStdout)
        {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = fileName;
            psi.Arguments = arguments;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = redirectStdin;
            psi.RedirectStandardOutput = redirectStdout;
            psi.RedirectStandardError = true;
            Process p = new Process();
            p.StartInfo = psi;
            p.Start();
            return p;
        }

        private static void KillProcess(ref Process p)
        {
            Process local = Interlocked.Exchange(ref p, null);
            if (local == null) return;
            try { if (!local.HasExited) local.Kill(); } catch { }
            try { local.Dispose(); } catch { }
        }
    }
}
