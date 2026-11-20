package cz.ideamirror.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int CAMERA = 10, SCAN = 11, CAPTURE = 12, AUDIO_PERM = 13;
    private static final String PREFS = "tabule_client";
    private static final String KEY_SAVED_PAIRING = "saved_pairing";
    private static final String KEY_SAVED_HOST = "saved_host";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_AUDIO = "audio";
    private static final String KEY_AUDIO_VOL = "audio_vol";

    private String pairing;
    private String pendingPairingAfterAudioPerm;
    private int quality = ShareService.QUALITY_BALANCED;
    private boolean audioEnabled = true;
    private int audioVolume = 70;
    private int lastNonZeroVolume = 70;

    private TextView status, statusPill, networkView, savedInfo, volumeLabel;
    private LinearLayout savedBox;
    private Button quickConnectBtn, qualityBtn, muteBtn;
    private Switch audioSwitch;
    private SeekBar volumeSeek;
    private int tickCount = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        public void run() {
            status.setText(ShareService.status);
            if (ShareService.active) {
                statusPill.setText("● Vysílám živě");
                statusPill.setBackground(Ui.rounded(MainActivity.this, Ui.CARD_HI, Ui.ACCENT, 999));
                statusPill.setTextColor(Ui.ACCENT);
            } else {
                statusPill.setText("● Připraveno");
                statusPill.setBackground(Ui.rounded(MainActivity.this, Ui.PANEL, Ui.CARD_STROKE, 999));
                statusPill.setTextColor(Ui.MUTED);
            }
            if (tickCount % 5 == 0) refreshNetworkInfo();
            tickCount++;
            main.postDelayed(this, 1000);
        }
    };

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        if (b != null) pairing = b.getString("pairing");
        SharedPreferences sp = prefs();
        quality = sp.getInt(KEY_QUALITY, ShareService.QUALITY_BALANCED);
        if (quality < 0 || quality >= ShareService.QUALITY_LABELS.length) quality = ShareService.QUALITY_BALANCED;
        audioEnabled = sp.getBoolean(KEY_AUDIO, true);
        audioVolume = AudioUtil.clampPercent(sp.getInt(KEY_AUDIO_VOL, 70));
        if (audioVolume > 0) lastNonZeroVolume = audioVolume;
        ShareService.audioVolumePercent = audioVolume;

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 22);
        root.setPadding(pad, Ui.dp(this, 18), pad, pad);
        sc.addView(root);
        setContentView(sc);

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(cz.ideamirror.app.R.drawable.icon);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        logoLp.setMargins(0, 0, Ui.dp(this, 14), 0);
        header.addView(logo, logoLp);

        LinearLayout titleCol = Ui.column(this);
        TextView title = Ui.text(this, "Tabule Link", 28);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 0);
        titleCol.addView(title);
        TextView subtitle = Ui.muted(this, "Šifrované zrcadlení obrazu a zvuku v místní síti · TLS 1.2", 14);
        subtitle.setPadding(0, 0, 0, 0);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        statusPill = Ui.pill(this, "● Připraveno", Ui.PANEL, Ui.CARD_STROKE, Ui.MUTED);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        header.addView(statusPill, pillLp);
        root.addView(header, Ui.vSpace(this, 0, 16));

        // Responsive two-column grid on tablets/boards
        boolean wide = getResources().getConfiguration().screenWidthDp >= 680;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(grid, Ui.vSpace(this, 0, 14));

        // LEFT CARD: Receiver (Tabule) + Network info
        LinearLayout boardCard = Ui.card(this, false);
        boardCard.addView(Ui.section(this, "1 · NA TABULI (PŘIJÍMAČ)"));
        TextView boardTitle = Ui.text(this, "Zobrazit obraz z tabletu", 19);
        boardTitle.setTypeface(null, Typeface.BOLD);
        boardCard.addView(boardTitle);
        boardCard.addView(Ui.muted(this, "Otevře obrazovku příjmu s QR kódem a stálým klíčem, takže se spárovaný tablet připojí jedním klepnutím.", 14));
        boardCard.addView(Ui.button(this, "Přijímat obraz a zvuk", () -> startActivity(new Intent(this, ReceiveActivity.class))), Ui.vSpace(this, 10, 14));

        LinearLayout netBox = Ui.subpanel(this);
        netBox.addView(Ui.section(this, "SÍŤ TOHOTO ZAŘÍZENÍ (KLEPNUTÍM OBNOVIT)"));
        networkView = Ui.text(this, "Zjišťuji síťová rozhraní…", 13);
        networkView.setTextIsSelectable(true);
        netBox.addView(networkView);
        netBox.setOnClickListener(v -> refreshNetworkInfo());
        boardCard.addView(netBox);

        LinearLayout.LayoutParams leftLp = wide
            ? new LinearLayout.LayoutParams(0, -2, 1f)
            : new LinearLayout.LayoutParams(-1, -2);
        if (wide) leftLp.setMargins(0, 0, Ui.dp(this, 8), 0);
        else leftLp.setMargins(0, 0, 0, Ui.dp(this, 14));
        grid.addView(boardCard, leftLp);

        // RIGHT CARD: Sender (Tablet) + Saved Quick Connect + Audio/Quality settings
        LinearLayout tabletCard = Ui.card(this, false);
        tabletCard.addView(Ui.section(this, "2 · NA TABLETU (VYSÍLAČ)"));
        TextView tabTitle = Ui.text(this, "Sdílet obrazovku na tabuli", 19);
        tabTitle.setTypeface(null, Typeface.BOLD);
        tabletCard.addView(tabTitle);

        // Saved connection quick-connect panel
        savedBox = Ui.card(this, true);
        int spPad = Ui.dp(this, 14);
        savedBox.setPadding(spPad, spPad, spPad, spPad);
        savedBox.addView(Ui.section(this, "ULOŽENÁ TABULE · BEZ QR KÓDU"));
        savedInfo = Ui.muted(this, "", 13);
        savedBox.addView(savedInfo);
        LinearLayout savedBtns = new LinearLayout(this);
        savedBtns.setOrientation(LinearLayout.HORIZONTAL);
        quickConnectBtn = Ui.button(this, "⚡ Připojit k uložené tabuli", this::connectSaved);
        Button forgetBtn = Ui.secondaryButton(this, "Zapomenout", this::forgetSaved);
        LinearLayout.LayoutParams qcLp = new LinearLayout.LayoutParams(0, -2, 1f);
        qcLp.setMargins(0, 0, Ui.dp(this, 8), 0);
        savedBtns.addView(quickConnectBtn, qcLp);
        savedBtns.addView(forgetBtn, new LinearLayout.LayoutParams(-2, -2));
        savedBox.addView(savedBtns, Ui.vSpace(this, 8, 0));
        tabletCard.addView(savedBox, Ui.vSpace(this, 8, 10));

        // QR / Manual pair buttons
        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = Ui.button(this, "Načíst QR kód", this::scan);
        Button manualBtn = Ui.secondaryButton(this, "Ruční kód", this::manual);
        LinearLayout.LayoutParams prLeft = new LinearLayout.LayoutParams(0, -2, 1.3f);
        prLeft.setMargins(0, 0, Ui.dp(this, 8), 0);
        pairRow.addView(scanBtn, prLeft);
        pairRow.addView(manualBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        tabletCard.addView(pairRow, Ui.vSpace(this, 4, 12));

        // Audio + Quality settings box
        LinearLayout settingsBox = Ui.subpanel(this);
        settingsBox.addView(Ui.section(this, "NASTAVENÍ PŘENOSU"));

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        audioRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout audioLabels = Ui.column(this);
        TextView audioTitle = Ui.text(this, "Přenášet zvuk aplikací", 15);
        audioTitle.setPadding(0, 0, 0, 0);
        audioLabels.addView(audioTitle);
        TextView audioSub = Ui.muted(this, Build.VERSION.SDK_INT >= 29
            ? "YouTube, videa, prezentace (AAC stereo)"
            : "Vyžaduje Android 10 nebo novější", 12);
        audioSub.setPadding(0, 0, 0, 0);
        audioLabels.addView(audioSub);
        audioRow.addView(audioLabels, new LinearLayout.LayoutParams(0, -2, 1f));

        audioSwitch = new Switch(this);
        audioSwitch.setChecked(audioEnabled && Build.VERSION.SDK_INT >= 29);
        audioSwitch.setEnabled(Build.VERSION.SDK_INT >= 29);
        audioSwitch.setOnCheckedChangeListener((btn, checked) -> {
            audioEnabled = checked;
            prefs().edit().putBoolean(KEY_AUDIO, checked).apply();
            updateVolumeControlsEnabled();
        });
        audioRow.addView(audioSwitch);
        settingsBox.addView(audioRow, Ui.vSpace(this, 2, 8));

        // Live audio volume slider (sent to IdeaHub, does not change board's videoconferencing volume)
        LinearLayout volHeader = new LinearLayout(this);
        volHeader.setOrientation(LinearLayout.HORIZONTAL);
        volHeader.setGravity(Gravity.CENTER_VERTICAL);
        volumeLabel = Ui.text(this, "Hlasitost zvuku na tabuli: " + audioVolume + " %", 14);
        volumeLabel.setPadding(0, 0, 0, 0);
        volHeader.addView(volumeLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        muteBtn = Ui.secondaryButton(this, audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit", this::toggleMute);
        muteBtn.setMinHeight(Ui.dp(this, 36));
        muteBtn.setMinimumHeight(Ui.dp(this, 36));
        muteBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        muteBtn.setTextSize(13);
        volHeader.addView(muteBtn);
        settingsBox.addView(volHeader, Ui.vSpace(this, 2, 2));

        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(audioVolume);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                audioVolume = AudioUtil.clampPercent(progress);
                if (audioVolume > 0) lastNonZeroVolume = audioVolume;
                ShareService.audioVolumePercent = audioVolume;
                volumeLabel.setText("Hlasitost zvuku na tabuli: " + audioVolume + " %");
                muteBtn.setText(audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                prefs().edit().putInt(KEY_AUDIO_VOL, audioVolume).apply();
            }
        });
        settingsBox.addView(volumeSeek, Ui.vSpace(this, 0, 2));
        settingsBox.addView(Ui.muted(this, "Funguje i za běhu vysílání. Nemění hlasitost videokonference tabule.", 12), Ui.vSpace(this, 0, 10));
        updateVolumeControlsEnabled();

        qualityBtn = Ui.secondaryButton(this, "Režim obrazu: " + ShareService.QUALITY_LABELS[quality], this::chooseQuality);
        settingsBox.addView(qualityBtn, new LinearLayout.LayoutParams(-1, -2));
        tabletCard.addView(settingsBox);

        LinearLayout.LayoutParams rightLp = wide
            ? new LinearLayout.LayoutParams(0, -2, 1f)
            : new LinearLayout.LayoutParams(-1, -2);
        if (wide) rightLp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        grid.addView(tabletCard, rightLp);

        // BOTTOM STATUS & WINDOW CONTROLS CARD
        LinearLayout controlCard = Ui.card(this, false);
        controlCard.addView(Ui.section(this, "STAV A OVLÁDÁNÍ"));
        status = Ui.text(this, ShareService.status, 15);
        status.setTextColor(Ui.ACCENT);
        status.setTextIsSelectable(true);
        controlCard.addView(status, Ui.vSpace(this, 0, 10));

        LinearLayout windowActions = new LinearLayout(this);
        windowActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams act1 = new LinearLayout.LayoutParams(0, -2, 1f);
        act1.setMargins(0, 0, Ui.dp(this, 6), 0);
        LinearLayout.LayoutParams act2 = new LinearLayout.LayoutParams(0, -2, 1f);
        act2.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        LinearLayout.LayoutParams act3 = new LinearLayout.LayoutParams(0, -2, 1f);
        act3.setMargins(Ui.dp(this, 6), 0, 0, 0);

        windowActions.addView(Ui.dangerButton(this, "Ukončit vysílání", () -> {
            stopService(new Intent(this, ShareService.class));
            ShareService.status = "Vysílání ukončeno.";
            status.setText(ShareService.status);
        }), act1);
        windowActions.addView(Ui.secondaryButton(this, "Uklidit do pozadí", () -> moveTaskToBack(true)), act2);
        windowActions.addView(Ui.secondaryButton(this, "Ukončit aplikaci", () -> {
            stopService(new Intent(this, ShareService.class));
            ShareService.status = "Vysílání ukončeno.";
            finishAndRemoveTask();
        }), act3);
        controlCard.addView(windowActions);
        root.addView(controlCard, Ui.vSpace(this, 0, 12));

        // Footer row
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView device = Ui.muted(this, Ui.info() + "  ·  Chráněný DRM obsah může být bez obrazu/zvuku.", 12);
        device.setTextIsSelectable(true);
        footer.addView(device, wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2));

        LinearLayout footerBtns = new LinearLayout(this);
        footerBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button diagBtn = Ui.secondaryButton(this, "Diagnostika", () -> {
            String localNets = Wire.formatEndpointsInline(Wire.endpoints(this));
            Ui.copy(this, "Diagnostika", Ui.info() + "\nSíť zařízení: " + localNets + "\n" + ShareService.diagnostics());
        });
        Button licBtn = Ui.secondaryButton(this, "Licence", this::showLicenses);
        LinearLayout.LayoutParams fb1 = new LinearLayout.LayoutParams(-2, -2);
        fb1.setMargins(wide ? Ui.dp(this, 10) : 0, wide ? 0 : Ui.dp(this, 8), Ui.dp(this, 8), 0);
        LinearLayout.LayoutParams fb2 = new LinearLayout.LayoutParams(-2, -2);
        fb2.setMargins(0, wide ? 0 : Ui.dp(this, 8), 0, 0);
        footerBtns.addView(diagBtn, fb1);
        footerBtns.addView(licBtn, fb2);
        footer.addView(footerBtns);
        root.addView(footer);

        refreshSavedUi();
        refreshNetworkInfo();
    }

    private void showLicenses() {
        try (java.io.InputStream input = getAssets().open("licenses/Apache-2.0.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) bytes.write(buf, 0, n);
            new AlertDialog.Builder(this)
                .setTitle("ZXing Core 3.5.3 · Apache 2.0")
                .setMessage("Copyright ZXing authors.\n\n" + bytes.toString("UTF-8"))
                .setPositiveButton("Zavřít", null)
                .show();
        } catch (Exception e) {
            Ui.error(this, Ui.problem(e));
        }
    }

    private void refreshSavedUi() {
        String saved = prefs().getString(KEY_SAVED_PAIRING, null);
        String host = prefs().getString(KEY_SAVED_HOST, null);
        if (saved != null && host != null) {
            savedBox.setVisibility(View.VISIBLE);
            savedInfo.setText("Poslední spárovaná tabule: " + host);
            quickConnectBtn.setText("⚡ Připojit (" + host + ")");
        } else {
            savedBox.setVisibility(View.GONE);
        }
    }

    private void savePairing(String code, Wire.Pair pair) {
        prefs().edit()
            .putString(KEY_SAVED_PAIRING, code.trim())
            .putString(KEY_SAVED_HOST, pair.host + ":" + pair.port)
            .apply();
        refreshSavedUi();
    }

    private void connectSaved() {
        String saved = prefs().getString(KEY_SAVED_PAIRING, null);
        if (saved == null) { refreshSavedUi(); return; }
        begin(saved);
    }

    private void forgetSaved() {
        prefs().edit().remove(KEY_SAVED_PAIRING).remove(KEY_SAVED_HOST).apply();
        refreshSavedUi();
        Toast.makeText(this, "Uložené spojení bylo odstraněno.", Toast.LENGTH_SHORT).show();
    }

    private void updateVolumeControlsEnabled() {
        boolean on = audioEnabled && Build.VERSION.SDK_INT >= 29;
        volumeSeek.setEnabled(on);
        muteBtn.setEnabled(on);
        float alpha = on ? 1f : 0.45f;
        volumeLabel.setAlpha(alpha);
        volumeSeek.setAlpha(alpha);
        muteBtn.setAlpha(alpha);
    }

    private void toggleMute() {
        int next = audioVolume == 0 ? Math.max(20, lastNonZeroVolume) : 0;
        audioVolume = next;
        ShareService.audioVolumePercent = next;
        volumeSeek.setProgress(next);
        volumeLabel.setText("Hlasitost zvuku na tabuli: " + next + " %");
        muteBtn.setText(next == 0 ? "Obnovit zvuk" : "Ztlumit");
        prefs().edit().putInt(KEY_AUDIO_VOL, next).apply();
    }

    private void chooseQuality() {
        new AlertDialog.Builder(this)
            .setTitle("Režim kvality a odezvy")
            .setSingleChoiceItems(ShareService.QUALITY_LABELS, quality, (d, which) -> {
                quality = which;
                prefs().edit().putInt(KEY_QUALITY, quality).apply();
                qualityBtn.setText("Režim obrazu: " + ShareService.QUALITY_LABELS[quality]);
                d.dismiss();
            })
            .setNegativeButton("Zavřít", null)
            .show();
    }

    private void refreshNetworkInfo() {
        List<Wire.Endpoint> eps = Wire.endpoints(this);
        networkView.setText(Wire.formatEndpointsMultiline(eps));
    }

    void scan() {
        if (ShareService.active) { Ui.error(this, "Nejprve ukončete současné vysílání."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.CAMERA);
            if (audioEnabled && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(perms.toArray(new String[0]), CAMERA);
        } else {
            startActivityForResult(new Intent(this, ScanActivity.class), SCAN);
        }
    }

    void manual() {
        EditText e = new EditText(this);
        e.setHint("tabulelink://…");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
            .setTitle("Párovací kód z tabule")
            .setView(e)
            .setPositiveButton("Připojit", (d, w) -> begin(e.getText().toString()))
            .setNegativeButton("Zrušit", null)
            .show();
    }

    void begin(String code) {
        if (ShareService.active) { Ui.error(this, "Nejprve ukončete současné vysílání."); return; }
        try {
            Wire.Pair pair = new Wire.Pair(code);
            savePairing(code, pair);
            if (audioEnabled && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingPairingAfterAudioPerm = code;
                List<String> perms = new ArrayList<>();
                perms.add(Manifest.permission.RECORD_AUDIO);
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS);
                }
                requestPermissions(perms.toArray(new String[0]), AUDIO_PERM);
                return;
            }
            launchScreenCapture(code);
        } catch (Exception e) {
            Ui.error(this, Ui.problem(e));
        }
    }

    private void launchScreenCapture(String code) {
        pairing = code;
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(new Intent(this, ScanActivity.class), SCAN);
            } else {
                Ui.error(this, "Fotoaparát slouží pouze ke čtení QR kódu. Můžete také vložit párovací kód ručně.");
            }
        } else if (request == AUDIO_PERM) {
            String code = pendingPairingAfterAudioPerm;
            pendingPairingAfterAudioPerm = null;
            if (code != null) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Oprávnění pro zvuk nebylo uděleno — přenáším jen obraz.", Toast.LENGTH_LONG).show();
                }
                launchScreenCapture(code);
            }
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN && result == RESULT_OK && data != null) {
            begin(data.getStringExtra("pairing"));
        }
        if (request == CAPTURE) {
            if (result == RESULT_OK && data != null && pairing != null) {
                Intent i = new Intent(this, ShareService.class)
                    .putExtra("pairing", pairing)
                    .putExtra("quality", quality)
                    .putExtra("audio", audioEnabled)
                    .putExtra("audioVolume", audioVolume)
                    .putExtra("result", result)
                    .putExtra("capture", data);
                try {
                    startForegroundService(i);
                } catch (Exception e) {
                    Ui.error(this, Ui.problem(e));
                }
            } else if (result != RESULT_OK) {
                ShareService.status = "Sdílení obrazovky nebylo povoleno.";
                status.setText(ShareService.status);
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("pairing", pairing); }
    @Override protected void onResume() { super.onResume(); refreshSavedUi(); tickCount = 0; main.post(tick); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
}
