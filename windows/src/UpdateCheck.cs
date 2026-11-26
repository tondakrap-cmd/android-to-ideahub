using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace TabuleLinkWin
{
    /// <summary>A newer Windows build found in the GitHub releases.</summary>
    public sealed class UpdateRelease
    {
        public string Version;
        public string Tag;
        public string Name;
        public string Notes;
        public string HtmlUrl;
        public string AssetName;
        public string AssetUrl;
        public long AssetSize;
        public string AssetSha256;   // from GitHub's asset digest ("sha256:…"), may be null
        public string SignatureUrl;  // <asset>.sig, RSA-SHA256 by the Tabule Link update key
    }

    /// <summary>
    /// Update check against GitHub Releases (pure logic, covered by Test-Windows.ps1). Only release assets of this
    /// repository are accepted, and an update is applied only when its .sig verifies with the embedded public key.
    /// </summary>
    public static class UpdateCheck
    {
        public const string Repo = "tondakrap-cmd/android-to-ideahub";
        public const string ReleasesApi = "https://api.github.com/repos/" + Repo + "/releases?per_page=30";
        public const string ReleasesPage = "https://github.com/" + Repo + "/releases";
        public const string DownloadPrefix = "https://github.com/" + Repo + "/releases/download/";
        public const long MaxAssetBytes = 100L * 1024 * 1024;

        /// <summary>Release asset of the Windows app, e.g. TabuleLink-Windows-0.9.0.exe.</summary>
        public static readonly Regex WindowsAsset = new Regex(@"^TabuleLink-Windows-(\d+)\.(\d+)\.(\d+)\.exe$", RegexOptions.CultureInvariant);

        /// <summary>
        /// Public half of the Windows update signing key (RSA 3072). The private key is kept only by the publisher
        /// (project\.signing\windows-update, not in git) and used by Sign-WindowsUpdate.ps1 when releasing.
        /// </summary>
        public const string WindowsPublicKeyXml = "<RSAKeyValue><Modulus>wJNM9daCKya4X/aE5DZEv/p2zsVewlNhHE0x+H2n6sak/oWr8yI5wwDDAm/aw1FeocVnJFEv7qwYZLEhh6ND0iX50kaO8C6KzTtFxa7f0MygW9QhR2v8TYhNVTmNfXHiP6w38ijssAbb40wCSaKIaoJkwJeVNfsbZsyT5Wt3C4eFU61IZ9C3kcXQFM12oftYYU/M339qQUaLgbilRUTPVboWK/J+ykjj3RC1B0OmIcqnRSgcquFyd+WiPxtQ8Fg7K7z7p7NukO0dLmSe1T2Ia5JFDR9nbKXG4DYbHgkOIB52PTns2i86z7KcMr41EleQQnMoIP80PpeOmvjxJwO7PorJMeRRq8Iu1T8HYSN6TmfPizH4NT4fi4vjCsUImw0VV2VmAimxEA8XNsy8By8gvZh/mSBD14kcDMMULs2Gqj9njjYS6cnDt9ZHY/tJEiPapanwJRUm7ebQ9PGNqsRm9GL7dsuSA5mEeFaP9Fpr0jKu4eQd9egXB6Zhf+UAqM2R</Modulus><Exponent>AQAB</Exponent></RSAKeyValue>";

        /// <summary>"0.9.0" or "v0.9.0" → {0, 9, 0}; null when it isn't a plain three-part version.</summary>
        public static int[] ParseVersion(string v)
        {
            if (v == null) return null;
            Match m = Regex.Match(v.Trim(), @"^[vV]?(\d{1,6})\.(\d{1,6})\.(\d{1,6})$", RegexOptions.CultureInvariant);
            if (!m.Success) return null;
            return new int[]
            {
                int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture),
                int.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture),
                int.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture)
            };
        }

        /// <summary>Negative when a is older than b, 0 when equal; unparsable versions sort as oldest.</summary>
        public static int CompareVersions(string a, string b)
        {
            int[] x = ParseVersion(a), y = ParseVersion(b);
            if (x == null || y == null) return (x == null ? 0 : 1) - (y == null ? 0 : 1);
            for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
            return 0;
        }

        /// <summary>
        /// Newest published (not draft, not prerelease) release that carries a Windows build newer than
        /// currentVersion together with its .sig; null when there is none.
        /// </summary>
        public static UpdateRelease FindNewer(string releasesJson, string currentVersion)
        {
            JavaScriptSerializer js = new JavaScriptSerializer();
            js.MaxJsonLength = 16 * 1024 * 1024;
            ArrayList releases = js.DeserializeObject(releasesJson) as ArrayList;
            if (releases == null)
            {
                object[] arr = js.DeserializeObject(releasesJson) as object[];
                if (arr == null) throw new InvalidDataException("GitHub vrátil neočekávanou odpověď.");
                releases = new ArrayList(arr);
            }
            UpdateRelease best = null;
            foreach (object o in releases)
            {
                Dictionary<string, object> r = o as Dictionary<string, object>;
                if (r == null || Bool(r, "draft") || Bool(r, "prerelease")) continue;
                IEnumerable assets = r.ContainsKey("assets") ? r["assets"] as IEnumerable : null;
                if (assets == null) continue;
                Dictionary<string, Dictionary<string, object>> byName = new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
                foreach (object ao in assets)
                {
                    Dictionary<string, object> a = ao as Dictionary<string, object>;
                    string n = Str(a, "name");
                    if (n != null && !byName.ContainsKey(n)) byName[n] = a;
                }
                foreach (KeyValuePair<string, Dictionary<string, object>> kv in byName)
                {
                    Match m = WindowsAsset.Match(kv.Key);
                    if (!m.Success) continue;
                    string version = m.Groups[1].Value + "." + m.Groups[2].Value + "." + m.Groups[3].Value;
                    if (CompareVersions(version, currentVersion) <= 0) continue;
                    if (best != null && CompareVersions(version, best.Version) <= 0) continue;
                    Dictionary<string, object> sig;
                    if (!byName.TryGetValue(kv.Key + ".sig", out sig)) continue;
                    string url = Str(kv.Value, "browser_download_url"), sigUrl = Str(sig, "browser_download_url");
                    if (!IsOwnDownload(url) || !IsOwnDownload(sigUrl)) continue;
                    UpdateRelease u = new UpdateRelease();
                    u.Version = version;
                    u.Tag = Str(r, "tag_name");
                    u.Name = Str(r, "name") ?? u.Tag;
                    u.Notes = Str(r, "body") ?? "";
                    u.HtmlUrl = Str(r, "html_url") ?? ReleasesPage;
                    u.AssetName = kv.Key;
                    u.AssetUrl = url;
                    u.AssetSize = Num(kv.Value, "size");
                    u.AssetSha256 = DigestSha256(Str(kv.Value, "digest"));
                    u.SignatureUrl = sigUrl;
                    best = u;
                }
            }
            return best;
        }

        /// <summary>Download URLs must point to this repository's release assets over HTTPS.</summary>
        public static bool IsOwnDownload(string url)
        {
            return url != null && url.StartsWith(DownloadPrefix, StringComparison.Ordinal) && url.IndexOf("..", StringComparison.Ordinal) < 0;
        }

        /// <summary>"sha256:ABCD…" → lower-case hex, otherwise null.</summary>
        public static string DigestSha256(string digest)
        {
            if (digest == null) return null;
            Match m = Regex.Match(digest.Trim(), @"^sha256:([0-9a-fA-F]{64})$", RegexOptions.CultureInvariant);
            return m.Success ? m.Groups[1].Value.ToLowerInvariant() : null;
        }

        public static string Sha256Hex(byte[] data)
        {
            using (SHA256 sha = SHA256.Create())
            {
                byte[] h = sha.ComputeHash(data);
                StringBuilder sb = new StringBuilder(64);
                foreach (byte b in h) sb.Append(b.ToString("x2", CultureInfo.InvariantCulture));
                return sb.ToString();
            }
        }

        /// <summary>RSA PKCS#1 v1.5 / SHA-256 signature (base64 text, as written by Sign-WindowsUpdate.ps1).</summary>
        public static bool VerifySignature(byte[] data, string signatureText, string publicKeyXml)
        {
            try
            {
                if (data == null || string.IsNullOrEmpty(signatureText) || string.IsNullOrEmpty(publicKeyXml)) return false;
                byte[] sig = Convert.FromBase64String(signatureText.Trim());
                using (RSACng rsa = new RSACng())
                {
                    rsa.FromXmlString(publicKeyXml);
                    if (rsa.KeySize < 2048) return false;
                    return rsa.VerifyData(data, sig, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>Checks a downloaded build: size, GitHub digest (when present) and the update signature.</summary>
        public static string ValidateDownload(UpdateRelease u, byte[] exe, string signatureText, string publicKeyXml)
        {
            if (exe == null || exe.Length < 1024) return "Stažený soubor je prázdný nebo poškozený.";
            if (u.AssetSize > 0 && exe.Length != u.AssetSize) return "Stažený soubor má jinou velikost, než uvádí GitHub.";
            if (u.AssetSha256 != null && !string.Equals(Sha256Hex(exe), u.AssetSha256, StringComparison.Ordinal))
                return "Kontrolní součet SHA-256 staženého souboru nesouhlasí.";
            if (exe[0] != (byte)'M' || exe[1] != (byte)'Z') return "Stažený soubor není program pro Windows.";
            if (!VerifySignature(exe, signatureText, publicKeyXml)) return "Podpis aktualizace je neplatný – soubor nepochází od vydavatele Tabule Link.";
            return null;
        }

        /// <summary>Release notes shortened for a dialog (Markdown left as plain text).</summary>
        public static string ShortNotes(string notes, int max)
        {
            if (string.IsNullOrEmpty(notes)) return "";
            string s = notes.Replace("\r\n", "\n").Trim();
            if (s.Length > max) s = s.Substring(0, max).TrimEnd() + "…";
            return s.Replace("\n", "\r\n");
        }

        private static string Str(Dictionary<string, object> d, string k)
        {
            object v;
            return d != null && d.TryGetValue(k, out v) ? v as string : null;
        }

        private static bool Bool(Dictionary<string, object> d, string k)
        {
            object v;
            return d != null && d.TryGetValue(k, out v) && v is bool && (bool)v;
        }

        private static long Num(Dictionary<string, object> d, string k)
        {
            object v;
            if (d == null || !d.TryGetValue(k, out v) || v == null) return 0;
            try { return Convert.ToInt64(v, CultureInfo.InvariantCulture); } catch { return 0; }
        }
    }
}
