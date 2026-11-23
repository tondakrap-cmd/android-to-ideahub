package cz.ideamirror.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Board (receiver) settings and pairing credentials stored in private SharedPreferences. */
final class BoardSettings {
    static final String PREFS = "tabule_server";
    private static final String K_ALWAYS_ON = "always_on", K_AUTO_OPEN_BOOT = "auto_open_boot", K_RX_VOLUME = "rx_volume";
    private static final String K_NAME = "board_name", K_MODERATOR = "moderator", K_SPLIT = "split", K_TOUCH = "allow_touch";
    private static final String K_ADMIN = "admin_pin_hash", K_IP = "advertised_ip", K_IDLE = "idle_close";
    private static final String K_SECRET = "secret", K_TOKEN = "qr_token", K_PIN = "pin", K_DEVICES = "devices";

    private final SharedPreferences prefs;
    private DeviceRegistry registry;

    BoardSettings(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ------------------------------------------------------------ simple flags
    static boolean isAlwaysOn(Context c) { return prefs(c).getBoolean(K_ALWAYS_ON, false); }
    static boolean isAutoOpenOnBoot(Context c) { return prefs(c).getBoolean(K_AUTO_OPEN_BOOT, false); }

    boolean alwaysOn() { return prefs.getBoolean(K_ALWAYS_ON, false); }
    void setAlwaysOn(boolean v) { prefs.edit().putBoolean(K_ALWAYS_ON, v).apply(); }
    boolean autoOpenOnBoot() { return prefs.getBoolean(K_AUTO_OPEN_BOOT, false); }
    void setAutoOpenOnBoot(boolean v) { prefs.edit().putBoolean(K_AUTO_OPEN_BOOT, v).apply(); }
    boolean moderator() { return prefs.getBoolean(K_MODERATOR, false); }
    void setModerator(boolean v) { prefs.edit().putBoolean(K_MODERATOR, v).apply(); }
    boolean split() { return prefs.getBoolean(K_SPLIT, false); }
    void setSplit(boolean v) { prefs.edit().putBoolean(K_SPLIT, v).apply(); }
    boolean allowTouch() { return prefs.getBoolean(K_TOUCH, true); }
    void setAllowTouch(boolean v) { prefs.edit().putBoolean(K_TOUCH, v).apply(); }
    boolean idleClose() { return prefs.getBoolean(K_IDLE, true); }
    void setIdleClose(boolean v) { prefs.edit().putBoolean(K_IDLE, v).apply(); }
    int rxVolume() { return AudioUtil.clampPercent(prefs.getInt(K_RX_VOLUME, 100)); }
    void setRxVolume(int v) { prefs.edit().putInt(K_RX_VOLUME, AudioUtil.clampPercent(v)).apply(); }
    String advertisedIp() { return prefs.getString(K_IP, ""); }
    void setAdvertisedIp(String ip) { prefs.edit().putString(K_IP, ip == null ? "" : ip).apply(); }

    String boardName() {
        String def = Proto.sanitize((Build.MANUFACTURER + " " + Build.MODEL).trim(), 64, "Tabule Link");
        return Proto.sanitize(prefs.getString(K_NAME, def), 64, def);
    }
    void setBoardName(String name) { prefs.edit().putString(K_NAME, Proto.sanitize(name, 64, boardName())).apply(); }

    // ------------------------------------------------------------ admin PIN for the "Správa" panel
    private static String hashAdmin(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return TlsSupport.hex(md.digest(("TabuleLink admin|" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
    boolean hasAdminPin() { return prefs.getString(K_ADMIN, null) != null; }
    void setAdminPin(String pin) {
        if (pin == null || pin.isEmpty()) prefs.edit().remove(K_ADMIN).apply();
        else prefs.edit().putString(K_ADMIN, hashAdmin(pin)).apply();
    }
    boolean checkAdminPin(String pin) {
        String h = prefs.getString(K_ADMIN, null);
        return h == null || Proto.constEq(h, hashAdmin(pin == null ? "" : pin.trim()));
    }

    // ------------------------------------------------------------ credentials
    synchronized String legacySecret() {
        String s = prefs.getString(K_SECRET, null);
        if (s == null || !Proto.isHex(s, 32)) {
            s = Wire.token();
            prefs.edit().putString(K_SECRET, s).apply();
        }
        return s;
    }

    private PairingCredentials credentials;

    /** One-time QR token and PIN (validation + rotation under one lock, see PairingCredentials). */
    synchronized PairingCredentials credentials() {
        if (credentials == null) {
            credentials = new PairingCredentials(new PairingCredentials.Store() {
                @Override public String loadToken() { return prefs.getString(K_TOKEN, null); }
                @Override public void saveToken(String t) { prefs.edit().putString(K_TOKEN, t).apply(); }
                @Override public String loadPin() { return prefs.getString(K_PIN, null); }
                @Override public void savePin(String p) { prefs.edit().putString(K_PIN, p).apply(); }
            }, Wire.RANDOM);
        }
        return credentials;
    }

    String qrToken() { return credentials().token(); }
    String pin() { return credentials().pin(); }
    String rotateToken() { return credentials().rotateToken(); }
    String rotatePin() { return credentials().rotatePin(); }

    synchronized DeviceRegistry registry() {
        if (registry == null) registry = DeviceRegistry.decode(prefs.getString(K_DEVICES, ""));
        return registry;
    }

    synchronized void saveRegistry() {
        prefs.edit().putString(K_DEVICES, registry().encode()).apply();
    }

    /** Revokes every paired device and the old 0.6 shared secret; new QR token and PIN. */
    synchronized void revokeAll() {
        registry().clear();
        saveRegistry();
        prefs.edit().putString(K_SECRET, Wire.token()).apply();
        rotateToken();
        rotatePin();
    }
}
