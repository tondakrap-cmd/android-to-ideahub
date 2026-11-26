package cz.ideamirror.app;

import java.util.*;

/** Update check: versions, choosing the APK from GitHub releases, digest, JSON parser. */
final class UpdateCheckTest {
    private UpdateCheckTest() {}

    private static final String DL = "https://github.com/tondakrap-cmd/android-to-ideahub/releases/download/";

    private static String asset(String tag, String name, long size, String digest) {
        return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"digest\":" + (digest == null ? "null" : "\"" + digest + "\"")
            + ",\"browser_download_url\":\"" + DL + tag + "/" + name + "\"}";
    }

    private static String release(String tag, boolean draft, boolean pre, String... assets) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"Tabule Link " + tag.substring(1) + "\",\"draft\":" + draft + ",\"prerelease\":" + pre
            + ",\"html_url\":\"https://github.com/tondakrap-cmd/android-to-ideahub/releases/tag/" + tag + "\""
            + ",\"body\":\"**Novinky** \\u017elut\\u00fd k\\u016f\\u0148\\r\\n- bod \\\"1\\\"\",\"reactions\":{\"+1\":2,\"total\":1.5e1},\"assets\":["
            + String.join(",", assets) + "]}";
    }

    static void run() {
        CoreTest.check(UpdateCheck.parseVersion("0.9.0")[1] == 9 && UpdateCheck.parseVersion("v1.10.3")[1] == 10, "update: parse versions");
        CoreTest.check(UpdateCheck.parseVersion("0.9") == null && UpdateCheck.parseVersion("0.9.0-beta") == null && UpdateCheck.parseVersion(null) == null, "update: reject non-plain versions");
        CoreTest.check(UpdateCheck.compareVersions("0.10.0", "0.9.9") > 0, "update: 0.10.0 > 0.9.9");
        CoreTest.check(UpdateCheck.compareVersions("0.9.0", "v0.9.0") == 0, "update: v prefix ignored");
        CoreTest.check(UpdateCheck.compareVersions("0.7.2", "0.9.0") < 0 && UpdateCheck.compareVersions("1.0.0", "0.99.99") > 0, "update: compare");

        String d = "sha256:" + new String(new char[64]).replace('\0', 'A');
        String json = "[" + String.join(",",
            release("v1.2.0", true, false, asset("v1.2.0", "Tabule-Link-1.2.0.apk", 9000, d)),
            release("v1.1.0", false, true, asset("v1.1.0", "Tabule-Link-1.1.0.apk", 9000, d)),
            release("v1.0.1", false, false, asset("v1.0.1", "TabuleLink-Windows-1.0.1.exe", 5000, d), asset("v1.0.1", "Tabule-Link-1.0.1-ci.apk", 1, null)),
            release("v1.0.0", false, false, asset("v1.0.0", "Tabule-Link-1.0.0.apk", 123456, d), asset("v1.0.0", "Tabule-Link-working.apk", 1, null)),
            release("v0.9.0", false, false, asset("v0.9.0", "Tabule-Link-0.9.0.apk", 100000, null))) + "]";
        UpdateCheck.Release u = UpdateCheck.findNewer(json, "0.9.0");
        CoreTest.check(u != null && "1.0.0".equals(u.version), "update: skips draft, prerelease, CI and other-platform builds");
        CoreTest.check("Tabule-Link-1.0.0.apk".equals(u.assetName) && u.size == 123456 && "v1.0.0".equals(u.tag), "update: asset fields");
        CoreTest.check(u.sha256.equals(new String(new char[64]).replace('\0', 'a')), "update: digest lower-cased");
        CoreTest.check(u.notes.startsWith("**Novinky** žlutý kůň\r\n- bod \"1\""), "update: JSON escapes in notes");
        CoreTest.check((DL + "v1.0.0/Tabule-Link-1.0.0.apk").equals(u.assetUrl), "update: asset url");
        CoreTest.check(UpdateCheck.findNewer(json, "1.0.0") == null && UpdateCheck.findNewer(json, "3.0.0") == null, "update: nothing when current or newer");
        CoreTest.check(UpdateCheck.findNewer("[]", "0.1.0") == null, "update: empty list");

        String foreign = "[" + release("v5.0.0", false, false,
            "{\"name\":\"Tabule-Link-5.0.0.apk\",\"size\":10,\"browser_download_url\":\"https://evil.example/Tabule-Link-5.0.0.apk\"}") + "]";
        CoreTest.check(UpdateCheck.findNewer(foreign, "0.9.0") == null, "update: rejects downloads outside this repository");
        CoreTest.check(!UpdateCheck.isOwnDownload(DL + "../../x/y.apk") && !UpdateCheck.isOwnDownload(DL.replace("https:", "http:") + "v1/x.apk"), "update: rejects path tricks and http");

        CoreTest.check(UpdateCheck.digestSha256("sha1:abc") == null && UpdateCheck.digestSha256(null) == null, "update: digest parsing");
        CoreTest.check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(UpdateCheck.sha256Hex(UpdateCheck.utf8("abc"))), "update: sha256 vector");

        UpdateCheck.Release r = new UpdateCheck.Release();
        r.size = 5000;
        r.sha256 = UpdateCheck.sha256Hex(new byte[5000]);
        CoreTest.check(UpdateCheck.validateDownload(r, 5000, UpdateCheck.sha256Hex(new byte[5000])) == null, "update: download validates");
        CoreTest.check(UpdateCheck.validateDownload(r, 4999, r.sha256) != null, "update: size mismatch rejected");
        CoreTest.check(UpdateCheck.validateDownload(r, 5000, UpdateCheck.sha256Hex(new byte[1])) != null, "update: digest mismatch rejected");
        CoreTest.check(UpdateCheck.validateDownload(new UpdateCheck.Release(), 100, "x") != null, "update: tiny file rejected");

        for (String bad : Arrays.asList("", "[", "[1,]", "{\"a\" 1}", "\"\\x\"", "[1] 2", "nul")) {
            boolean threw = false;
            try { UpdateCheck.Json.parse(bad); } catch (IllegalArgumentException e) { threw = true; }
            CoreTest.check(threw, "update: JSON rejects " + bad);
        }
        Object nested = UpdateCheck.Json.parse("{\"a\":[1,-2.5e3,true,false,null,{\"b\":\"\\/\"}]}");
        CoreTest.check(nested instanceof Map && ((List<?>) ((Map<?, ?>) nested).get("a")).size() == 6, "update: JSON nested values");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        boolean threw = false;
        try { UpdateCheck.Json.parse(deep.toString()); } catch (IllegalArgumentException e) { threw = true; }
        CoreTest.check(threw, "update: JSON depth limit");
        CoreTest.check(UpdateCheck.shortNotes("a\r\nb", 100).equals("a\nb") && UpdateCheck.shortNotes(new String(new char[50]).replace('\0', 'x'), 10).length() == 11, "update: short notes");
    }
}
