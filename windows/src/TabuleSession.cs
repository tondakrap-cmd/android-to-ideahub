using System;
using System.Diagnostics;
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
            Index = index;
            Label = label;
            MaxSide = maxSide;
            Fps = fps;
            BitrateKbps = bitrateKbps;
        }

        public static readonly QualityProfile[] All = new QualityProfile[]
        {
            new QualityProfile(0, "Rychlá odezva (960p · 3 Mb/s)", 960, 30, 3000),
            new QualityProfile(1, "Vyvážený (1280p · 5 Mb/s)", 1280, 30, 5000),
            new QualityProfile(2, "Vysoká ostrost (1600p · 8 Mb/s)", 1600, 30, 8000),
            new QualityProfile(3, "Ultra ostrost (1920p · 12 Mb/s)", 1920, 30, 12000)
        };
    }

    public sealed class SessionConfig
    {
        public string Host;
        public int Port;
        public string Fingerprint;
        public string Secret; // either 32-hex secret OR null when using Pin6
        public string Pin6;   // 6-digit PIN for first pairing
        public int ScreenIndex;
        public QualityProfile Quality;
        public bool AudioEnabled;
        public Func<int> GetVolumePercent;
    }

    public sealed class TabuleSession : IDisposable
    {
        private readonly SessionConfig cfg;
        private readonly Action<string> onStatus;
        private readonly Action<string> onPairedUri;
        private readonly Action onStopped;

        private volatile bool running;
        private Thread mainThread;
        private TcpClient tcp;
        private SslStream ssl;
        private Process videoProc;
        private Process audioProc;
        private WasapiLoopback wasapi;
        private FrameQueue queue;

        public volatile string DiagPhase = "nečinný";
        public volatile string DiagCapture = "-";
        public volatile string DiagAudio = "vypnuto";
        public volatile string DiagError = "-";
        public volatile int DiagFps = 0;
        public volatile int DiagAvgWriteMs = 0;
        public long DiagVideoFrames;
        public long DiagAudioFrames;
        public long DiagDrops;

        public bool IsRunning { get { return running; } }

        public TabuleSession(SessionConfig cfg, Action<string> onStatus, Action<string> onPairedUri, Action onStopped)
        {
            this.cfg = cfg;
            this.onStatus = onStatus;
            this.onPairedUri = onPairedUri;
            this.onStopped = onStopped;
        }

        public static string FindFfmpeg()
        {
            try
            {
                string exeDir = Path.GetDirectoryName(Application.ExecutablePath);
                if (!string.IsNullOrEmpty(exeDir))
                {
                    string local = Path.Combine(exeDir, "ffmpeg.exe");
                    if (File.Exists(local)) return local;
                }
            }
            catch { }

            string pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
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
            return null;
        }

        public string BuildDiagnostics()
        {
            return "Windows " + Environment.OSVersion.VersionString + " · Tabule Link " + WireConstants.Version
                + "\r\nCíl: " + cfg.Host + ":" + cfg.Port
                + "\r\nFáze: " + DiagPhase
                + "\r\nVideo: " + DiagCapture
                + "\r\nZvuk: " + DiagAudio + " (" + Interlocked.Read(ref DiagAudioFrames) + " rámců)"
                + "\r\nFPS / zápis: " + DiagFps + " fps · ~" + DiagAvgWriteMs + " ms"
                + "\r\nOdeslané / přeskočené snímky: " + Interlocked.Read(ref DiagVideoFrames) + " / " + Interlocked.Read(ref DiagDrops)
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
            running = false;
            if (queue != null) queue.Close();
            KillProcess(ref videoProc);
            KillProcess(ref audioProc);
            if (wasapi != null) { try { wasapi.Dispose(); } catch { } wasapi = null; }
            if (ssl != null) { try { ssl.Close(); } catch { } ssl = null; }
            if (tcp != null) { try { tcp.Close(); } catch { } tcp = null; }
        }

        public void Dispose()
        {
            Stop();
        }

        private void Report(string phase, string message)
        {
            DiagPhase = phase;
            if (onStatus != null) onStatus(message);
        }

        private void Run()
        {
            Thread netThread = null;
            Thread audioReaderThread = null;
            Exception netFailure = null;
            queue = new FrameQueue();

            try
            {
                string ffmpegPath = FindFfmpeg();
                if (string.IsNullOrEmpty(ffmpegPath))
                {
                    throw new FileNotFoundException("Nebyl nalezen program ffmpeg.exe. Položte ffmpeg.exe do složky s TabuleLink.exe nebo nainstalujte FFmpeg (winget install Gyan.FFmpeg).");
                }

                // 1. TCP connect
                Report("TCP připojení", "Připojuji se k " + cfg.Host + ":" + cfg.Port + "…");
                tcp = new TcpClient();
                tcp.NoDelay = true;
                tcp.SendBufferSize = 128 * 1024;
                tcp.ReceiveBufferSize = 128 * 1024;
                IAsyncResult ar = tcp.BeginConnect(cfg.Host, cfg.Port, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(8000, false))
                {
                    throw new IOException("Vypršel časový limit TCP připojení k tabuli.");
                }
                tcp.EndConnect(ar);
                tcp.SendTimeout = 8000;
                tcp.ReceiveTimeout = 8000;

                // 2. TLS 1.2 handshake with strict SHA-256 certificate pinning
                Report("TLS 1.2 handshake", "Ověřuji zabezpečené spojení TLS 1.2…");
                string expectedFp = cfg.Fingerprint.ToLowerInvariant();
                ssl = new SslStream(tcp.GetStream(), false, delegate(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errs)
                {
                    if (cert == null) return false;
                    byte[] hash;
                    using (SHA256 sha = SHA256.Create())
                    {
                        hash = sha.ComputeHash(cert.GetRawCertData());
                    }
                    StringBuilder sb = new StringBuilder(64);
                    for (int i = 0; i < hash.Length; i++) sb.Append(hash[i].ToString("x2", CultureInfo.InvariantCulture));
                    return FixedTimeEquals(sb.ToString(), expectedFp);
                });
                ssl.AuthenticateAsClient(cfg.Host, null, SslProtocols.Tls12, false);

                // 3. Authenticate using saved 128-bit secret OR 6-digit PIN
                Report("Ověření klíče", "Ověřuji přístup na tabuli…");
                if (!string.IsNullOrEmpty(cfg.Pin6))
                {
                    WireIO.WriteJavaUtf(ssl, "PIN:" + cfg.Pin6);
                    ssl.Flush();
                    int resp = WireIO.ReadInt32BE(ssl);
                    if (resp != WireConstants.MagicPinOk)
                    {
                        throw new IOException("Tabule odmítla PIN (zkontrolujte, zda na tabuli běží Tabule Link 0.5.0+).");
                    }
                    string receivedSecret = WireIO.ReadJavaUtf(ssl);
                    int ack = WireIO.ReadInt32BE(ssl);
                    if (ack != WireConstants.Magic) throw new IOException("Tabule nepotvrdila spojení.");
                    if (onPairedUri != null && !string.IsNullOrEmpty(receivedSecret))
                    {
                        string uri = PairCode.BuildUri(cfg.Host, cfg.Port, expectedFp, receivedSecret);
                        onPairedUri(uri);
                    }
                }
                else
                {
                    WireIO.WriteJavaUtf(ssl, cfg.Secret);
                    ssl.Flush();
                    int ack = WireIO.ReadInt32BE(ssl);
                    if (ack != WireConstants.Magic) throw new IOException("Tabule připojení odmítla (neplatný párovací klíč).");
                }

                // 4. Compute monitor geometry (aligned to 16)
                Screen[] screens = Screen.AllScreens;
                int screenIdx = cfg.ScreenIndex;
                if (screenIdx < 0 || screenIdx >= screens.Length) screenIdx = 0;
                Screen screen = screens[screenIdx];
                int rawW = screen.Bounds.Width;
                int rawH = screen.Bounds.Height;
                double scale = Math.Min(1.0, (double)cfg.Quality.MaxSide / Math.Max(1, Math.Max(rawW, rawH)));
                int width = Math.Max(16, ((int)(rawW * scale) / 16) * 16);
                int height = Math.Max(16, ((int)(rawH * scale) / 16) * 16);

                // 5. Send video header
                Report("Hlavička videa", "Spouštím přenos " + width + " × " + height + "…");
                WireIO.WriteInt32BE(ssl, WireConstants.Magic);
                WireIO.WriteInt32BE(ssl, width);
                WireIO.WriteInt32BE(ssl, height);
                ssl.Flush();
                tcp.SendTimeout = 0;
                tcp.ReceiveTimeout = 0;

                // 6. Start network writer thread
                Stopwatch clock = Stopwatch.StartNew();
                long lastWriteMs = clock.ElapsedMilliseconds;
                netThread = new Thread(delegate()
                {
                    byte[] scratch = new byte[128 * 1024];
                    long fpsWinStart = clock.ElapsedMilliseconds;
                    long fpsWinCount = 0;
                    long writeMsSum = 0;
                    long writeOps = 0;
                    try
                    {
                        while (running && !queue.IsClosed)
                        {
                            Packet pkt = queue.Poll(1500);
                            long now = clock.ElapsedMilliseconds;
                            if (pkt == null)
                            {
                                if (running && now - lastWriteMs >= 1800)
                                {
                                    WireIO.WriteHeartbeat(ssl);
                                    lastWriteMs = clock.ElapsedMilliseconds;
                                }
                                continue;
                            }
                            long t0 = clock.ElapsedMilliseconds;
                            scratch = WireIO.WritePacketSingleRecord(ssl, scratch, pkt);
                            long dt = clock.ElapsedMilliseconds - t0;
                            lastWriteMs = clock.ElapsedMilliseconds;
                            if (!pkt.IsAudio && !pkt.IsConfig)
                            {
                                Interlocked.Increment(ref DiagVideoFrames);
                                fpsWinCount++;
                            }
                            Interlocked.Exchange(ref DiagDrops, queue.DroppedVideo);
                            writeMsSum += dt;
                            writeOps++;
                            if (lastWriteMs - fpsWinStart >= 1000)
                            {
                                DiagFps = (int)Math.Round(fpsWinCount * 1000.0 / Math.Max(1, lastWriteMs - fpsWinStart));
                                DiagAvgWriteMs = (int)(writeMsSum / Math.Max(1, writeOps));
                                fpsWinStart = lastWriteMs;
                                fpsWinCount = 0;
                                writeMsSum = 0;
                                writeOps = 0;
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        if (running) netFailure = ex;
                    }
                    finally
                    {
                        queue.Close();
                    }
                });
                netThread.IsBackground = true;
                netThread.Priority = ThreadPriority.AboveNormal;
                netThread.Name = "tabule-win-net";
                netThread.Start();

                // 7. Start WASAPI Loopback + AAC audio encoder (optional, isolated from video)
                if (cfg.AudioEnabled)
                {
                    audioReaderThread = StartAudioPipeline(ffmpegPath, clock);
                }
                else
                {
                    DiagAudio = "vypnuto uživatelem";
                }

                // 8. Start video capture (try GPU ddagrab first, automatic fallback to gdigrab)
                bool ok = RunVideoPipeline(ffmpegPath, screenIdx, screen, width, height, true, clock, delegate() { return netFailure; });
                if (!ok && running && netFailure == null)
                {
                    Report("Přepínám na GDI zachytávání", "GPU zachytávání nedostupné, přepínám na GDI…");
                    RunVideoPipeline(ffmpegPath, screenIdx, screen, width, height, false, clock, delegate() { return netFailure; });
                }

                if (netFailure != null) throw netFailure;
            }
            catch (Exception ex)
            {
                if (running)
                {
                    DiagError = ex.GetType().Name + ": " + ex.Message;
                    if (DiagPhase == "vysílání" && Interlocked.Read(ref DiagVideoFrames) > 0 && (ex is IOException || ex is SocketException))
                    {
                        Report("ukončeno", "Vysílání ukončeno (tabule příjem zavřela nebo sdílení převzal jiný uživatel).");
                    }
                    else
                    {
                        string hint = LocalNet.SubnetMismatchHint(cfg.Host);
                        string msg = "Přenos se nezdařil (" + DiagPhase + "): " + ex.Message;
                        if (!string.IsNullOrEmpty(hint)) msg += "\r\n" + hint;
                        Report("chyba", msg);
                    }
                }
            }
            finally
            {
                running = false;
                if (queue != null) queue.Close();
                KillProcess(ref videoProc);
                KillProcess(ref audioProc);
                if (wasapi != null) { try { wasapi.Dispose(); } catch { } wasapi = null; }
                if (ssl != null) { try { ssl.Close(); } catch { } ssl = null; }
                if (tcp != null) { try { tcp.Close(); } catch { } tcp = null; }
                if (netThread != null) { try { netThread.Join(1000); } catch { } }
                if (audioReaderThread != null) { try { audioReaderThread.Join(800); } catch { } }
                if (onStopped != null) onStopped();
            }
        }

        private bool RunVideoPipeline(string ffmpegPath, int screenIdx, Screen screen, int width, int height,
            bool useDda, Stopwatch clock, Func<Exception> getNetFailure)
        {
            int fps = cfg.Quality.Fps;
            int br = cfg.Quality.BitrateKbps;
            int buf = Math.Max(1000, br / 2);
            string args;
            if (useDda)
            {
                args = string.Format(CultureInfo.InvariantCulture,
                    "-hide_banner -loglevel error -init_hw_device d3d11va -filter_complex \"ddagrab=output_idx={0}:framerate={1}:draw_mouse=1,hwdownload,format=bgra,scale={2}:{3}:flags=fast_bilinear,format=yuv420p\" " +
                    "-c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -bf 0 -g 150 -keyint_min 30 -b:v {4}k -maxrate {4}k -bufsize {5}k -x264-params \"repeat-headers=1:scenecut=0:aud=1:slices=1\" -f h264 pipe:1",
                    screenIdx, fps, width, height, br, buf);
                DiagCapture = "ddagrab + libx264 baseline (" + width + "x" + height + "@" + fps + ", " + (br / 1000) + " Mb/s)";
            }
            else
            {
                args = string.Format(CultureInfo.InvariantCulture,
                    "-hide_banner -loglevel error -f gdigrab -framerate {0} -offset_x {1} -offset_y {2} -video_size {3}x{4} -draw_mouse 1 -i desktop " +
                    "-vf \"scale={5}:{6}:flags=fast_bilinear,format=yuv420p\" -pix_fmt yuv420p " +
                    "-c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -bf 0 -g 150 -keyint_min 30 -b:v {7}k -maxrate {7}k -bufsize {8}k -x264-params \"repeat-headers=1:scenecut=0:aud=1:slices=1\" -f h264 pipe:1",
                    fps, screen.Bounds.X, screen.Bounds.Y, screen.Bounds.Width, screen.Bounds.Height, width, height, br, buf);
                DiagCapture = "gdigrab + libx264 baseline (" + width + "x" + height + "@" + fps + ", " + (br / 1000) + " Mb/s)";
            }

            Process p = StartHiddenProcess(ffmpegPath, args, false, true);
            videoProc = p;
            StringBuilder stderr = new StringBuilder();
            Thread errThread = new Thread(delegate()
            {
                try
                {
                    string line;
                    while ((line = p.StandardError.ReadLine()) != null)
                    {
                        if (stderr.Length < 1024) stderr.AppendLine(line);
                    }
                }
                catch { }
            });
            errThread.IsBackground = true;
            errThread.Start();

            bool gotAnyFrame = false;
            AnnexBParser parser = new AnnexBParser(delegate(int flags, byte[] data)
            {
                if (!gotAnyFrame && (flags & WireConstants.FlagCodecConfig) == 0)
                {
                    gotAnyFrame = true;
                    Report("vysílání", "Vysílám na " + cfg.Host + " · " + width + " × " + height
                        + (cfg.AudioEnabled ? " · obraz + zvuk" : " · jen obraz") + " · šifrováno");
                }
                long ptsUs = clock.ElapsedTicks * 1000000L / Stopwatch.Frequency;
                queue.Offer(new Packet(flags, ptsUs, data));
            });

            byte[] ioBuf = new byte[65536];
            Stream stdout = p.StandardOutput.BaseStream;
            while (running && getNetFailure() == null)
            {
                int n = stdout.Read(ioBuf, 0, ioBuf.Length);
                if (n <= 0) break;
                parser.Push(ioBuf, 0, n);
            }
            parser.Flush();

            KillProcess(ref videoProc);
            try { errThread.Join(300); } catch { }

            if (!gotAnyFrame && running && getNetFailure() == null)
            {
                if (!useDda)
                {
                    string errText = stderr.ToString().Trim();
                    throw new IOException("Zachytávání obrazovky (ffmpeg) nevydalo žádný snímek" + (errText.Length > 0 ? ": " + errText : "."));
                }
                return false; // allow automatic fallback to gdigrab
            }
            return true;
        }

        private Thread StartAudioPipeline(string ffmpegPath, Stopwatch clock)
        {
            try
            {
                wasapi = new WasapiLoopback();
                wasapi.Open();
                string args = string.Format(CultureInfo.InvariantCulture,
                    "-hide_banner -loglevel error -f s16le -ar {0} -ac 2 -i pipe:0 -ar 48000 -ac 2 -c:a aac -profile:a aac_low -b:a 128k -f adts pipe:1",
                    wasapi.SampleRate);
                Process ap = StartHiddenProcess(ffmpegPath, args, true, true);
                audioProc = ap;

                DiagAudio = wasapi.FormatDescription + " → AAC 48 kHz";
                Stream stdin = ap.StandardInput.BaseStream;

                AdtsParser adts = new AdtsParser(delegate(int flags, byte[] data)
                {
                    long ptsUs = clock.ElapsedTicks * 1000000L / Stopwatch.Frequency;
                    queue.Offer(new Packet(flags, ptsUs, data));
                    if ((flags & WireConstants.FlagCodecConfig) == 0)
                    {
                        Interlocked.Increment(ref DiagAudioFrames);
                    }
                });

                Thread reader = new Thread(delegate()
                {
                    byte[] buf = new byte[8192];
                    try
                    {
                        Stream aOut = ap.StandardOutput.BaseStream;
                        while (running && !queue.IsClosed)
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
                        if (!running || queue.IsClosed) return;
                        try { stdin.Write(pcm, 0, len); } catch { }
                    },
                    cfg.GetVolumePercent != null ? cfg.GetVolumePercent : (Func<int>)delegate() { return 70; },
                    delegate(Exception ex) { DiagAudio = "chyba zvuku: " + ex.Message; }
                );
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
            try
            {
                if (!local.HasExited) local.Kill();
            }
            catch { }
            try { local.Dispose(); } catch { }
        }

        private static bool FixedTimeEquals(string a, string b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= (a[i] ^ b[i]);
            return diff == 0;
        }
    }
}
