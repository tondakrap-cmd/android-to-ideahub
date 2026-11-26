using System;
using System.Security.Cryptography;
using System.Text;

namespace TabuleLinkWin
{
    /// <summary>Update check: versions, choosing the release asset, GitHub digest and the update signature.</summary>
    internal static class UpdateCheckTests
    {
        private static void Check(bool ok, string msg) { WindowsTests.Check(ok, msg); }

        private const string Dl = "https://github.com/tondakrap-cmd/android-to-ideahub/releases/download/";

        private static string Asset(string tag, string name, long size, string digest)
        {
            return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"digest\":" + (digest == null ? "null" : "\"" + digest + "\"")
                + ",\"browser_download_url\":\"" + Dl + tag + "/" + name + "\"}";
        }

        private static string Release(string tag, bool draft, bool pre, params string[] assets)
        {
            return "{\"tag_name\":\"" + tag + "\",\"name\":\"Tabule Link " + tag.TrimStart('v') + "\",\"draft\":" + (draft ? "true" : "false")
                + ",\"prerelease\":" + (pre ? "true" : "false") + ",\"html_url\":\"https://github.com/tondakrap-cmd/android-to-ideahub/releases/tag/" + tag
                + "\",\"body\":\"Novinky " + tag + "\\r\\n- bod\",\"assets\":[" + string.Join(",", assets) + "]}";
        }

        internal static void Run()
        {
            // versions
            Check(UpdateCheck.ParseVersion("0.9.0")[1] == 9 && UpdateCheck.ParseVersion("v1.10.3")[1] == 10, "parse versions");
            Check(UpdateCheck.ParseVersion("0.9") == null && UpdateCheck.ParseVersion("0.9.0-beta") == null && UpdateCheck.ParseVersion(null) == null, "reject non-plain versions");
            Check(UpdateCheck.CompareVersions("0.10.0", "0.9.9") > 0, "0.10.0 > 0.9.9 (numeric, not text)");
            Check(UpdateCheck.CompareVersions("0.9.0", "v0.9.0") == 0, "v prefix ignored");
            Check(UpdateCheck.CompareVersions("0.8.0", "0.9.0") < 0 && UpdateCheck.CompareVersions("1.0.0", "0.99.99") > 0, "compare");

            // choosing the newest Windows build with a signature
            string d1 = "sha256:" + new string('a', 64);
            string json = "[" + string.Join(",", new string[]
            {
                Release("v1.2.0", true, false, Asset("v1.2.0", "TabuleLink-Windows-1.2.0.exe", 5000, d1), Asset("v1.2.0", "TabuleLink-Windows-1.2.0.exe.sig", 512, null)),
                Release("v1.1.0", false, true, Asset("v1.1.0", "TabuleLink-Windows-1.1.0.exe", 5000, d1), Asset("v1.1.0", "TabuleLink-Windows-1.1.0.exe.sig", 512, null)),
                Release("v1.0.1", false, false, Asset("v1.0.1", "TabuleLink-Windows-1.0.1.exe", 5000, d1)),
                Release("v1.0.0", false, false, Asset("v1.0.0", "Tabule-Link-1.0.0.apk", 9000, d1), Asset("v1.0.0", "TabuleLink-Windows-1.0.0.exe", 4096, "sha256:" + new string('B', 64)),
                    Asset("v1.0.0", "TabuleLink-Windows-1.0.0.exe.sig", 512, null), Asset("v1.0.0", "TabuleLink-Windows-working.exe", 1, null)),
                Release("v0.9.0", false, false, Asset("v0.9.0", "TabuleLink-Windows-0.9.0.exe", 4000, null), Asset("v0.9.0", "TabuleLink-Windows-0.9.0.exe.sig", 512, null))
            }) + "]";
            UpdateRelease u = UpdateCheck.FindNewer(json, "0.9.0");
            Check(u != null && u.Version == "1.0.0", "skips draft, prerelease and unsigned builds (got " + (u == null ? "null" : u.Version) + ")");
            Check(u.AssetName == "TabuleLink-Windows-1.0.0.exe" && u.AssetSize == 4096 && u.Tag == "v1.0.0", "asset fields");
            Check(u.AssetSha256 == new string('b', 64), "digest parsed and lower-cased");
            Check(u.SignatureUrl == Dl + "v1.0.0/TabuleLink-Windows-1.0.0.exe.sig", "signature url");
            Check(u.Notes.StartsWith("Novinky v1.0.0", StringComparison.Ordinal), "release notes");
            Check(UpdateCheck.FindNewer(json, "1.0.0") == null, "no update when current");
            Check(UpdateCheck.FindNewer(json, "2.0.0") == null, "no downgrade");
            Check(UpdateCheck.FindNewer("[]", "0.1.0") == null, "empty release list");

            string foreign = "[" + Release("v5.0.0", false, false,
                "{\"name\":\"TabuleLink-Windows-5.0.0.exe\",\"size\":10,\"browser_download_url\":\"https://evil.example/TabuleLink-Windows-5.0.0.exe\"}",
                "{\"name\":\"TabuleLink-Windows-5.0.0.exe.sig\",\"size\":10,\"browser_download_url\":\"https://evil.example/x.sig\"}") + "]";
            Check(UpdateCheck.FindNewer(foreign, "0.9.0") == null, "rejects downloads outside this repository");
            Check(!UpdateCheck.IsOwnDownload(Dl + "../../other/repo/x.exe") && !UpdateCheck.IsOwnDownload("http://github.com/tondakrap-cmd/android-to-ideahub/releases/download/v1/x.exe"), "rejects path tricks and http");

            Check(UpdateCheck.DigestSha256("sha256:" + new string('C', 64)) == new string('c', 64) && UpdateCheck.DigestSha256("sha1:abc") == null && UpdateCheck.DigestSha256(null) == null, "digest parsing");
            Check(UpdateCheck.Sha256Hex(Encoding.ASCII.GetBytes("abc")) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256 known vector");

            // signature: a throw-away test key (the real private key never leaves the publisher)
            byte[] exe = new byte[4096];
            new Random(7).NextBytes(exe);
            exe[0] = (byte)'M'; exe[1] = (byte)'Z';
            string pubXml, sig, bareSig;
            using (RSACng rsa = new RSACng(3072))
            {
                pubXml = rsa.ToXmlString(false);
                sig = Convert.ToBase64String(rsa.SignData(UpdateCheck.SignedPayload("1.0.0", exe), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
                bareSig = Convert.ToBase64String(rsa.SignData(exe, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
            }
            Check(UpdateCheck.VerifySignature(exe, "1.0.0", sig, pubXml), "valid signature accepted");
            byte[] tampered = (byte[])exe.Clone();
            tampered[2000] ^= 1;
            Check(!UpdateCheck.VerifySignature(tampered, "1.0.0", sig, pubXml), "tampered file rejected");
            Check(!UpdateCheck.VerifySignature(exe, "1.0.0", sig, UpdateCheck.WindowsPublicKeyXml), "signature by another key rejected");
            Check(!UpdateCheck.VerifySignature(exe, "1.0.0", "not base64!", pubXml) && !UpdateCheck.VerifySignature(exe, "1.0.0", "", pubXml), "garbage signature rejected");
            // rollback: an old signed build re-uploaded under a higher version must not verify
            Check(!UpdateCheck.VerifySignature(exe, "999.0.0", sig, pubXml), "signature is bound to the version (no rollback)");
            Check(!UpdateCheck.VerifySignature(exe, "1.0.0", bareSig, pubXml), "signature over the bare EXE (no version) rejected");
            Check(!UpdateCheck.VerifySignature(exe, "1.0", sig, pubXml), "malformed version rejected");

            UpdateRelease r = new UpdateRelease();
            r.Version = "1.0.0";
            r.AssetSize = exe.Length;
            r.AssetSha256 = UpdateCheck.Sha256Hex(exe);
            Check(UpdateCheck.ValidateDownload(r, exe, sig, pubXml) == null, "download validates");
            r.AssetSha256 = new string('0', 64);
            Check(UpdateCheck.ValidateDownload(r, exe, sig, pubXml) != null, "digest mismatch rejected");
            r.AssetSha256 = null;
            r.AssetSize = exe.Length + 1;
            Check(UpdateCheck.ValidateDownload(r, exe, sig, pubXml) != null, "size mismatch rejected");
            r.AssetSize = 0;
            Check(UpdateCheck.ValidateDownload(r, exe, sig, pubXml) == null, "digest and size are optional, signature is not");
            Check(UpdateCheck.ValidateDownload(r, tampered, sig, pubXml) != null, "tampered download rejected");
            byte[] notExe = (byte[])exe.Clone();
            notExe[0] = (byte)'P';
            Check(UpdateCheck.ValidateDownload(r, notExe, sig, pubXml) != null, "non-PE file rejected");
            r.Version = "999.0.0";
            Check(UpdateCheck.ValidateDownload(r, exe, sig, pubXml) != null, "renamed old build rejected as a higher version");

            // the embedded production public key must be a real RSA key of at least 3072 bits
            using (RSACng prod = new RSACng())
            {
                prod.FromXmlString(UpdateCheck.WindowsPublicKeyXml);
                Check(prod.KeySize >= 3072, "embedded update key is RSA >= 3072");
            }
            Check(UpdateCheck.WindowsPublicKeyXml.IndexOf("<D>", StringComparison.Ordinal) < 0, "embedded key is public only");

            Check(UpdateCheck.ShortNotes("a\nb", 100) == "a\r\nb" && UpdateCheck.ShortNotes(new string('x', 50), 10).Length == 11, "short notes");
        }
    }
}
