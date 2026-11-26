package cz.ideamirror.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * In-app updates from GitHub Releases: check (manual or at most once a day), download, verify (size, GitHub
 * SHA-256, package name and signing certificate) and install through the system PackageInstaller. Nothing is
 * installed without the user tapping "Aktualizovat" and confirming the system dialog.
 */
final class Updater {
    private static final String PREFS = "tabule_updates", K_AUTO = "auto_check", K_LAST = "last_check", K_AVAILABLE = "available_version";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    static volatile UpdateCheck.Release pending;

    private Updater() {}

    interface Result { void done(UpdateCheck.Release found, String error); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean autoCheck(Context c) { return prefs(c).getBoolean(K_AUTO, true); }
    static void setAutoCheck(Context c, boolean on) { prefs(c).edit().putBoolean(K_AUTO, on).apply(); }

    /** Version of the installed app (from the package, so it can't drift from a constant). */
    static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return Ui.VERSION; }
    }

    /** Newer version found by the last check that is still newer than the installed one, else null. */
    static String availableVersion(Context c) {
        String v = prefs(c).getString(K_AVAILABLE, null);
        return v != null && UpdateCheck.compareVersions(v, currentVersion(c)) > 0 ? v : null;
    }

    /** Quiet check at most once a day (when enabled); result only when an update exists. */
    static void maybeAutoCheck(Context c, Result onFound) {
        if (!autoCheck(c)) return;
        if (System.currentTimeMillis() - prefs(c).getLong(K_LAST, 0) < DAY_MS) return;
        check(c, (found, error) -> { if (found != null && onFound != null) onFound.done(found, null); });
    }

    static void check(Context ctx, Result result) {
        Context c = ctx.getApplicationContext();
        String current = currentVersion(c);
        new Thread(() -> {
            UpdateCheck.Release found = null;
            String error = null;
            try {
                found = UpdateCheck.findNewer(get(UpdateCheck.RELEASES_API, current), current);
                prefs(c).edit().putLong(K_LAST, System.currentTimeMillis())
                    .putString(K_AVAILABLE, found != null ? found.version : null).apply();
                if (found != null) pending = found;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            UpdateCheck.Release f = found;
            String err = error;
            MAIN.post(() -> result.done(f, err));
        }, "tabule-update-check").start();
    }

    private static HttpURLConnection open(String url, String current) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "TabuleLink-Updater/" + current);
        con.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = con.getResponseCode();
        if (code != 200) { con.disconnect(); throw new IOException("GitHub odpověděl kódem " + code); }
        return con;
    }

    private static String get(String url, String current) throws IOException {
        HttpURLConnection con = open(url, current);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 16 * 1024 * 1024) throw new IOException("Odpověď GitHubu je příliš velká.");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { con.disconnect(); }
    }

    // ------------------------------------------------------------------ UI flow

    /** Manual check from a menu/button: shows the result, offers the update when found. */
    static void checkInteractive(Activity a) {
        Toast.makeText(a, "Hledám novou verzi na GitHubu…", Toast.LENGTH_SHORT).show();
        check(a, (found, error) -> {
            if (a.isFinishing()) return;
            if (found != null) { offer(a, found); return; }
            if (error != null) {
                new AlertDialog.Builder(a).setTitle("Aktualizace")
                    .setMessage("Nepodařilo se spojit s GitHubem: " + error + "\n\nZkontrolujte připojení k internetu. Vydání najdete i na " + UpdateCheck.RELEASES_PAGE)
                    .setPositiveButton("OK", null).show();
            } else {
                new AlertDialog.Builder(a).setTitle("Aktualizace")
                    .setMessage("Máte nejnovější verzi Tabule Link " + currentVersion(a) + ".")
                    .setPositiveButton("OK", null).show();
            }
        });
    }

    static void offer(Activity a, UpdateCheck.Release u) {
        String notes = UpdateCheck.shortNotes(u.notes, 1200);
        new AlertDialog.Builder(a)
            .setTitle("Je k dispozici Tabule Link " + u.version)
            .setMessage("Máte verzi " + currentVersion(a) + ".\n\n" + (notes.isEmpty() ? "" : notes + "\n\n")
                + "Aplikace se stáhne z GitHubu, ověří se a Android pak požádá o potvrzení instalace. Nastavení i spárované tabule zůstanou.")
            .setPositiveButton("Aktualizovat", (d, w) -> start(a, u))
            .setNegativeButton("Později", null)
            .show();
    }

    private static void start(Activity a, UpdateCheck.Release u) {
        if (busy) { Toast.makeText(a, "Aktualizace už probíhá.", Toast.LENGTH_SHORT).show(); return; }
        if (ShareService.active) { Toast.makeText(a, "Nejdřív ukončete vysílání.", Toast.LENGTH_LONG).show(); return; }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("Povolit instalaci aktualizací")
                .setMessage("Android potřebuje jednou povolit, aby Tabule Link mohl instalovat své aktualizace.\n\n"
                    + "V otevřeném nastavení zapněte „Povolit z tohoto zdroje“, vraťte se zpět a klepněte znovu na Aktualizovat.")
                .setPositiveButton("Otevřít nastavení", (d, w) -> {
                    try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                    catch (Exception e) { Toast.makeText(a, "Nastavení nelze otevřít: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Zrušit", null).show();
            return;
        }
        busy = true;
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p, p, p);
        TextView label = Ui.text(a, "Stahuji Tabule Link " + u.version + "…", 15);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar, Ui.vSpace(a, 12, 0));
        AlertDialog progress = new AlertDialog.Builder(a).setTitle("Aktualizace").setView(box).setCancelable(false).show();
        Context c = a.getApplicationContext();
        new Thread(() -> {
            String error;
            try { error = downloadAndInstall(c, u, pct -> MAIN.post(() -> { bar.setProgress(pct); label.setText("Stahuji Tabule Link " + u.version + "… " + pct + " %"); })); }
            catch (Exception e) { error = "Aktualizace se nepodařila: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()); }
            String err = error;
            MAIN.post(() -> {
                busy = false;
                try { progress.dismiss(); } catch (Exception ignored) {}
                if (err != null && !a.isFinishing())
                    new AlertDialog.Builder(a).setTitle("Aktualizace").setMessage(err).setPositiveButton("OK", null).show();
            });
        }, "tabule-update-download").start();
    }

    interface Progress { void at(int percent); }

    /** Returns null when the system installer took over, else a Czech error. */
    private static String downloadAndInstall(Context c, UpdateCheck.Release u, Progress progress) throws Exception {
        if (!UpdateCheck.isOwnDownload(u.assetUrl)) return "Neplatná adresa ke stažení.";
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) return "Nelze vytvořit složku pro aktualizaci.";
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, u.assetName);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long length = 0;
        HttpURLConnection con = open(u.assetUrl, currentVersion(c));
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            long total = u.size > 0 ? u.size : con.getContentLength();
            byte[] buf = new byte[65536];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                length += n;
                if (length > UpdateCheck.MAX_ASSET_BYTES) return "Soubor aktualizace je příliš velký.";
                if (total > 0) {
                    int pct = (int) Math.min(100, length * 100 / total);
                    if (pct != last) { last = pct; progress.at(pct); }
                }
            }
        } finally { con.disconnect(); }
        String error = UpdateCheck.validateDownload(u, length, UpdateCheck.hex(sha.digest()));
        if (error == null) error = checkPackage(c, apk, u.version);
        if (error != null) { apk.delete(); return error; }
        install(c, apk);
        return null;
    }

    /** Early, readable failure instead of the system's generic error: same package, same signing key, newer version. */
    @SuppressWarnings("deprecation")
    private static String checkPackage(Context c, File apk, String version) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) return "Stažený soubor není platná aplikace pro Android.";
        if (!c.getPackageName().equals(archive.packageName)) return "Stažený soubor není aplikace Tabule Link.";
        if (UpdateCheck.compareVersions(archive.versionName, version) != 0) return "Verze ve staženém souboru neodpovídá vydání.";
        try {
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(), flags);
            if (!Arrays.equals(signers(installed), signers(archive)))
                return "Aktualizace je podepsaná jiným klíčem než nainstalovaná aplikace, Android by ji odmítl. Nainstalujte ji ručně (po odinstalaci staré verze).";
            if (Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() <= installed.getLongVersionCode() : archive.versionCode <= installed.versionCode)
                return "Stažená verze není novější než nainstalovaná.";
        } catch (Exception e) {
            return "Nelze ověřit podpis aktualizace: " + e.getMessage();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String[] signers(PackageInfo p) throws Exception {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            if (p.signingInfo == null) return new String[0];
            sigs = p.signingInfo.getApkContentsSigners(); // current signer(s), without rotated-out history
        } else {
            sigs = p.signatures;
        }
        if (sigs == null) return new String[0];
        String[] out = new String[sigs.length];
        for (int i = 0; i < sigs.length; i++) out[i] = UpdateCheck.sha256Hex(sigs[i].toByteArray());
        Arrays.sort(out);
        return out;
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, id, status, piFlags);
            session.commit(pi.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { installer.abandonSession(id); } catch (Exception ignored) {}
            throw e;
        }
    }
}
