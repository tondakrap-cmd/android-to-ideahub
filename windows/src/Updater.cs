using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;

namespace TabuleLinkWin
{
    /// <summary>
    /// Downloads, verifies and installs Windows updates from GitHub Releases. The running EXE can't overwrite itself:
    /// a hidden PowerShell helper waits for this process to exit, replaces the file in place (same path, so
    /// shortcuts keep working) and starts the new version.
    /// </summary>
    public static class Updater
    {
        public static string CurrentVersion { get { return WireConstants.Version; } }

        private static HttpWebRequest Request(string url)
        {
            try { ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12; } catch { }
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(url);
            r.UserAgent = "TabuleLink-Updater/" + CurrentVersion;
            r.Accept = "application/vnd.github+json, application/octet-stream";
            r.Timeout = 20000;
            r.ReadWriteTimeout = 30000;
            r.AllowAutoRedirect = true;
            r.MaximumAutomaticRedirections = 5;
            return r;
        }

        /// <summary>Newer Windows release, or null when this version is current. Throws on network errors.</summary>
        public static UpdateRelease Check()
        {
            string json;
            using (HttpWebResponse resp = (HttpWebResponse)Request(UpdateCheck.ReleasesApi).GetResponse())
            using (StreamReader sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                json = sr.ReadToEnd();
            }
            return UpdateCheck.FindNewer(json, CurrentVersion);
        }

        public static byte[] Download(string url, long expectedSize, Action<int> progress)
        {
            if (!UpdateCheck.IsOwnDownload(url)) throw new IOException("Neplatná adresa ke stažení.");
            using (HttpWebResponse resp = (HttpWebResponse)Request(url).GetResponse())
            using (Stream s = resp.GetResponseStream())
            using (MemoryStream ms = new MemoryStream())
            {
                long total = expectedSize > 0 ? expectedSize : resp.ContentLength;
                byte[] buf = new byte[65536];
                int n, last = -1;
                while ((n = s.Read(buf, 0, buf.Length)) > 0)
                {
                    ms.Write(buf, 0, n);
                    if (ms.Length > UpdateCheck.MaxAssetBytes) throw new IOException("Soubor aktualizace je příliš velký.");
                    if (progress != null && total > 0)
                    {
                        int pct = (int)Math.Min(100, ms.Length * 100 / total);
                        if (pct != last) { last = pct; progress(pct); }
                    }
                }
                return ms.ToArray();
            }
        }

        /// <summary>Downloads the EXE and its signature, verifies both and stores the EXE in %TEMP%. Returns an error or null.</summary>
        public static string DownloadAndVerify(UpdateRelease u, Action<int> progress, out string verifiedExe)
        {
            verifiedExe = null;
            byte[] exe, sig;
            try
            {
                sig = Download(u.SignatureUrl, 0, null);
                exe = Download(u.AssetUrl, u.AssetSize, progress);
            }
            catch (Exception ex)
            {
                return "Stažení aktualizace se nepodařilo: " + ex.Message;
            }
            string error = UpdateCheck.ValidateDownload(u, exe, Encoding.ASCII.GetString(sig), UpdateCheck.WindowsPublicKeyXml);
            if (error != null) return error;
            try
            {
                string dir = Path.Combine(Path.GetTempPath(), "TabuleLink-update");
                Directory.CreateDirectory(dir);
                string path = Path.Combine(dir, u.AssetName);
                File.WriteAllBytes(path, exe);
                verifiedExe = path;
                return null;
            }
            catch (Exception ex)
            {
                return "Aktualizaci nelze uložit: " + ex.Message;
            }
        }

        /// <summary>True when the folder of the running EXE is writable for this user (no admin needed to replace it).</summary>
        public static bool CanReplaceInPlace(string currentExe)
        {
            try
            {
                string probe = Path.Combine(Path.GetDirectoryName(currentExe), ".tabulelink-write-test-" + Guid.NewGuid().ToString("N"));
                File.WriteAllText(probe, "x");
                File.Delete(probe);
                return true;
            }
            catch { return false; }
        }

        /// <summary>Starts the helper that replaces currentExe with newExe after this process exits and restarts it.</summary>
        public static void StartReplaceAfterExit(string newExe, string currentExe, string version)
        {
            string script =
                "$ErrorActionPreference='SilentlyContinue'; "
                + "Wait-Process -Id " + Process.GetCurrentProcess().Id + " -Timeout 60; "
                + "$ok=$false; for ($i=0; $i -lt 40 -and -not $ok; $i++) { "
                + "try { Copy-Item -LiteralPath " + Ps(newExe) + " -Destination " + Ps(currentExe) + " -Force -ErrorAction Stop; $ok=$true } "
                + "catch { Start-Sleep -Milliseconds 500 } }; "
                + "if ($ok) { Remove-Item -LiteralPath " + Ps(newExe) + " -Force; "
                + "Start-Process -FilePath " + Ps(currentExe) + " -ArgumentList " + Ps("--updated=" + version) + " } "
                + "else { Start-Process -FilePath " + Ps(currentExe) + " -ArgumentList " + Ps("\"--update-failed=" + newExe + "\"") + " }";
            ProcessStartInfo psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script)));
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            Process.Start(psi);
        }

        /// <summary>Fallback when the app folder isn't writable: copy the verified EXE to Downloads.</summary>
        public static string SaveToDownloads(string verifiedExe)
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            if (!Directory.Exists(dir)) dir = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
            string target = Path.Combine(dir, Path.GetFileName(verifiedExe));
            File.Copy(verifiedExe, target, true);
            return target;
        }

        private static string Ps(string s) { return "'" + s.Replace("'", "''") + "'"; }
    }
}
