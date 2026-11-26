package cz.ideamirror.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * Entry point. First run asks for the role of this device; boards go straight to the receive screen.
 * Presenter screen: one tap "Sdílet" per saved board, add boards by QR / LAN+PIN / code, live card while sharing.
 */
public class MainActivity extends Activity {
    private static final int SCAN = 11, CAPTURE = 12, PERMS = 13;
    private static final String K_QUALITY = "quality", K_AUDIO = "audio", K_AUDIO_VOL = "audio_vol", K_ADV_OPEN = "advanced_open",
        K_KEEP_ON = "keep_screen_on", K_FULL_SCREEN = "full_screen", K_MINIMIZE = "minimize_on_share";

    private int quality = ShareService.QUALITY_BALANCED;
    private boolean audioEnabled = true, keepScreenOn = true, fullScreenOnly = true, minimizeOnShare = true;
    /** Set when capture was granted with minimizing enabled; the tick hides the app once the board receives frames. */
    private boolean pendingMinimize;
    private long pendingMinimizeAt;
    private int audioVolume = 70, lastNonZeroVolume = 70;
    private String pendingShareFp;

    private LinearLayout boardsList, liveCard, advancedBox, updateCard;
    private TextView updateText;
    private TextView statusPill, liveTitle, liveStatus, liveStats, volumeLabel;
    private Button pauseBtn, liveMuteBtn, muteBtn, qualityBtn, advToggle;
    private SeekBar volumeSeek;
    private Switch audioSwitch, liveKeepOnSwitch, advKeepOnSwitch;
    private String lastBoardsSignature = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        public void run() {
            if (pendingMinimize) {
                if (ShareService.connected) { pendingMinimize = false; moveTaskToBack(true); }
                // The service starts asynchronously, so "not active" only means failure after a short grace period.
                else if (!ShareService.active && SystemClock.elapsedRealtime() - pendingMinimizeAt > 3000) pendingMinimize = false;
            }
            renderLive();
            String sig = ClientLink.prefs(MainActivity.this).getString("boards", "");
            if (!sig.equals(lastBoardsSignature)) renderBoards();
            main.postDelayed(this, 1000);
        }
    };

    private SharedPreferences prefs() { return ClientLink.prefs(this); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        String role = Ui.role(this);
        if (role == null) {
            // 0.6 boards had the always-on switch enabled — keep them on the board role after the update.
            if (BoardSettings.isAlwaysOn(this)) { Ui.setRole(this, Ui.ROLE_BOARD); role = Ui.ROLE_BOARD; }
        }
        if (Ui.ROLE_BOARD.equals(role)) { openBoard(); return; }
        if (role == null) { showRoleChooser(); return; }
        buildSenderUi();
    }

    private void openBoard() {
        startActivity(new Intent(this, ReceiveActivity.class));
        finish();
    }

    // ------------------------------------------------------------------ role chooser

    private void showRoleChooser() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);
        setContentView(sc);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon);
        root.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView t = Ui.text(this, "Vítejte v Tabule Link", 28);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        root.addView(t, Ui.vSpace(this, 12, 4));
        TextView st = Ui.muted(this, "Jak budete toto zařízení používat? Volbu lze později změnit.", 16);
        st.setGravity(Gravity.CENTER);
        root.addView(st, Ui.vSpace(this, 0, 22));

        boolean bigScreen = getResources().getConfiguration().smallestScreenWidthDp >= 900;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View board = roleCard("🖥  Tohle je TABULE", "Zobrazuje obraz z tabletů a notebooků (Huawei IdeaHub, TV, projektor).", bigScreen, () -> {
            Ui.setRole(this, Ui.ROLE_BOARD);
            openBoard();
        });
        View sender = roleCard("📱  Tohle zařízení VYSÍLÁ", "Sdílí svou obrazovku a zvuk na tabuli.", !bigScreen, () -> {
            Ui.setRole(this, Ui.ROLE_SENDER);
            buildSenderUi();
        });
        LinearLayout.LayoutParams lp1 = wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams lp2 = wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2);
        if (wide) lp1.setMargins(0, 0, Ui.dp(this, 10), 0); else lp1.setMargins(0, 0, 0, Ui.dp(this, 12));
        row.addView(board, lp1);
        row.addView(sender, lp2);
    }

    private View roleCard(String title, String desc, boolean recommended, Runnable click) {
        LinearLayout c = Ui.card(this, recommended);
        TextView t = Ui.text(this, title, 21);
        t.setTypeface(null, Typeface.BOLD);
        c.addView(t);
        c.addView(Ui.muted(this, desc, 14));
        if (recommended) c.addView(Ui.section(this, "DOPORUČENO PRO TOTO ZAŘÍZENÍ"), Ui.vSpace(this, 6, 0));
        c.addView(recommended ? Ui.button(this, "Vybrat", click) : Ui.secondaryButton(this, "Vybrat", click), Ui.vSpace(this, 12, 0));
        c.setOnClickListener(v -> click.run());
        return c;
    }

    // ------------------------------------------------------------------ presenter UI

    private void buildSenderUi() {
        SharedPreferences sp = prefs();
        quality = sp.getInt(K_QUALITY, ShareService.QUALITY_BALANCED);
        if (quality < 0 || quality >= ShareService.QUALITY_LABELS.length) quality = ShareService.QUALITY_BALANCED;
        audioEnabled = sp.getBoolean(K_AUDIO, true);
        audioVolume = AudioUtil.clampPercent(sp.getInt(K_AUDIO_VOL, 70));
        if (audioVolume > 0) lastNonZeroVolume = audioVolume;
        ShareService.audioVolumePercent = audioVolume;
        keepScreenOn = sp.getBoolean(K_KEEP_ON, true);
        fullScreenOnly = sp.getBoolean(K_FULL_SCREEN, true);
        minimizeOnShare = sp.getBoolean(K_MINIMIZE, true);
        if (!ShareService.active) ShareService.keepScreenOn = keepScreenOn;

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 16), pad, pad);
        sc.addView(root);
        setContentView(sc);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        logoLp.setMargins(0, 0, Ui.dp(this, 12), 0);
        header.addView(logo, logoLp);
        LinearLayout titleCol = Ui.column(this);
        TextView title = Ui.text(this, "Tabule Link", 26);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 0);
        titleCol.addView(title);
        TextView subtitle = Ui.muted(this, "Sdílení obrazovky a zvuku na tabuli · šifrováno", 13);
        subtitle.setPadding(0, 0, 0, 0);
        titleCol.addView(subtitle);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        statusPill = Ui.pill(this, "● Připraveno", Ui.PANEL, Ui.CARD_STROKE, Ui.MUTED);
        header.addView(statusPill);
        Button menuBtn = Ui.secondaryButton(this, "⋮", null);
        menuBtn.setOnClickListener(this::showMenu);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-2, -2);
        mLp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        header.addView(menuBtn, mLp);
        root.addView(header, Ui.vSpace(this, 0, 14));

        // Update card (only when a newer version was found)
        updateCard = Ui.card(this, true);
        updateCard.addView(Ui.section(this, "AKTUALIZACE"));
        updateText = Ui.text(this, "", 16);
        updateCard.addView(updateText);
        LinearLayout updBtns = new LinearLayout(this);
        updBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button updNow = Ui.button(this, "Aktualizovat", () -> { if (Updater.pending != null) Updater.offer(this, Updater.pending); else Updater.checkInteractive(this); });
        Button updLater = Ui.secondaryButton(this, "Později", () -> updateCard.setVisibility(View.GONE));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.setMargins(0, 0, Ui.dp(this, 8), 0);
        updBtns.addView(updNow, ulp);
        updBtns.addView(updLater);
        updateCard.addView(updBtns, Ui.vSpace(this, 10, 0));
        updateCard.setVisibility(View.GONE);
        root.addView(updateCard, Ui.vSpace(this, 0, 14));

        // Live card
        liveCard = Ui.card(this, true);
        liveCard.addView(Ui.section(this, "PRÁVĚ VYSÍLÁTE"));
        liveTitle = Ui.text(this, "", 22);
        liveTitle.setTypeface(null, Typeface.BOLD);
        liveCard.addView(liveTitle);
        liveStatus = Ui.text(this, "", 14);
        liveStatus.setTextColor(Ui.ACCENT);
        liveCard.addView(liveStatus);
        liveStats = Ui.muted(this, "", 12);
        liveCard.addView(liveStats);
        LinearLayout liveBtns = new LinearLayout(this);
        liveBtns.setOrientation(LinearLayout.HORIZONTAL);
        pauseBtn = Ui.secondaryButton(this, "⏸ Pozastavit", () -> { ShareService.setPaused(!ShareService.paused); renderLive(); });
        liveMuteBtn = Ui.secondaryButton(this, "🔇 Ztlumit", this::toggleMute);
        Button stopBtn = Ui.dangerButton(this, "■ Ukončit", () -> {
            stopService(new Intent(this, ShareService.class));
            ShareService.status = "Vysílání ukončeno.";
            renderLive();
        });
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0, -2, 1f);
        third.setMargins(0, 0, Ui.dp(this, 6), 0);
        liveBtns.addView(pauseBtn, third);
        liveBtns.addView(liveMuteBtn, new LinearLayout.LayoutParams(third));
        liveBtns.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        liveCard.addView(liveBtns, Ui.vSpace(this, 10, 0));
        liveKeepOnSwitch = Ui.toggleRow(liveCard, "☀ Nezhasínat obrazovku", "Zařízení během vysílání neusne ani se nezamkne.",
            keepScreenOn, (b, checked) -> setKeepScreenOn(checked));
        root.addView(liveCard, Ui.vSpace(this, 0, 14));

        // Boards
        LinearLayout boardsCard = Ui.card(this, false);
        boardsCard.addView(Ui.section(this, "MOJE TABULE"));
        boardsList = Ui.column(this);
        boardsCard.addView(boardsList);
        LinearLayout addRow = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        addRow.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        Button scanBtn = Ui.button(this, "＋ Naskenovat QR kód", this::scan);
        Button lanBtn = Ui.secondaryButton(this, "Najít v síti (PIN)", this::discoverAndPair);
        Button codeBtn = Ui.secondaryButton(this, "Vložit kód", this::manual);
        LinearLayout.LayoutParams a1 = wide ? new LinearLayout.LayoutParams(0, -2, 1.2f) : new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams a2 = wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2);
        LinearLayout.LayoutParams a3 = wide ? new LinearLayout.LayoutParams(0, -2, 0.8f) : new LinearLayout.LayoutParams(-1, -2);
        int g = Ui.dp(this, 6);
        if (wide) { a1.setMargins(0, 0, g, 0); a2.setMargins(0, 0, g, 0); } else { a1.setMargins(0, 0, 0, g); a2.setMargins(0, 0, 0, g); }
        addRow.addView(scanBtn, a1);
        addRow.addView(lanBtn, a2);
        addRow.addView(codeBtn, a3);
        boardsCard.addView(addRow, Ui.vSpace(this, 10, 0));
        root.addView(boardsCard, Ui.vSpace(this, 0, 14));

        // Advanced
        LinearLayout advCard = Ui.card(this, false);
        advToggle = Ui.secondaryButton(this, "", this::toggleAdvanced);
        advCard.addView(advToggle, new LinearLayout.LayoutParams(-1, -2));
        advancedBox = Ui.column(this);
        advCard.addView(advancedBox);
        buildAdvanced();
        root.addView(advCard, Ui.vSpace(this, 0, 14));
        setAdvancedOpen(sp.getBoolean(K_ADV_OPEN, false));

        TextView footer = Ui.muted(this, Ui.info() + "  ·  Chráněný DRM obsah může být bez obrazu/zvuku.", 12);
        footer.setTextIsSelectable(true);
        root.addView(footer);

        renderBoards();
        renderLive();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void buildAdvanced() {
        Ui.toggleRow(advancedBox, "Vždy sdílet celou obrazovku",
            Build.VERSION.SDK_INT >= 34 ? "Vypnuto: Android nabídne i sdílení jen jedné aplikace" : "Tato verze Androidu sdílí vždy celou obrazovku",
            fullScreenOnly, (b, checked) -> { fullScreenOnly = checked; prefs().edit().putBoolean(K_FULL_SCREEN, checked).apply(); });
        Ui.toggleRow(advancedBox, "Po zahájení vysílání skrýt aplikaci", "Tabule Link se minimalizuje, jakmile tabule začne zobrazovat obraz",
            minimizeOnShare, (b, checked) -> { minimizeOnShare = checked; prefs().edit().putBoolean(K_MINIMIZE, checked).apply(); });
        advKeepOnSwitch = Ui.toggleRow(advancedBox, "Nezhasínat obrazovku během vysílání", "Lze přepnout i za běhu v kartě vysílání",
            keepScreenOn, (b, checked) -> setKeepScreenOn(checked));
        Ui.toggleRow(advancedBox, "Automaticky kontrolovat aktualizace", "Jednou denně se podívá na GitHub; instaluje se jen po potvrzení",
            Updater.autoCheck(this), (b, checked) -> Updater.setAutoCheck(this, checked));
        Ui.toggleRow(advancedBox, "Přenášet zvuk aplikací", Build.VERSION.SDK_INT >= 29 ? "YouTube, videa, prezentace (AAC stereo)" : "Vyžaduje Android 10 nebo novější",
            audioEnabled && Build.VERSION.SDK_INT >= 29, (b, checked) -> {
                audioEnabled = checked;
                prefs().edit().putBoolean(K_AUDIO, checked).apply();
                updateVolumeControlsEnabled();
            });
        LinearLayout volHeader = new LinearLayout(this);
        volHeader.setOrientation(LinearLayout.HORIZONTAL);
        volHeader.setGravity(Gravity.CENTER_VERTICAL);
        volumeLabel = Ui.text(this, "", 14);
        volHeader.addView(volumeLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        muteBtn = Ui.secondaryButton(this, "", this::toggleMute);
        muteBtn.setTextSize(13);
        volHeader.addView(muteBtn);
        advancedBox.addView(volHeader);
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(audioVolume);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                setVolume(progress, false);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { prefs().edit().putInt(K_AUDIO_VOL, audioVolume).apply(); }
        });
        advancedBox.addView(volumeSeek);
        advancedBox.addView(Ui.muted(this, "Funguje i za běhu. Nemění hlasitost videokonference tabule.", 12), Ui.vSpace(this, 0, 10));
        qualityBtn = Ui.secondaryButton(this, "Režim obrazu: " + ShareService.QUALITY_LABELS[quality], this::chooseQuality);
        advancedBox.addView(qualityBtn, new LinearLayout.LayoutParams(-1, -2));
        setVolume(audioVolume, false);
        updateVolumeControlsEnabled();
    }

    private void toggleAdvanced() { setAdvancedOpen(advancedBox.getVisibility() != View.VISIBLE); }

    private void setAdvancedOpen(boolean open) {
        advancedBox.setVisibility(open ? View.VISIBLE : View.GONE);
        advToggle.setText((open ? "▾" : "▸") + "  Nastavení přenosu (obrazovka, zvuk, kvalita)");
        prefs().edit().putBoolean(K_ADV_OPEN, open).apply();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Jméno tohoto zařízení…");
        pm.getMenu().add(0, 2, 1, "Zkopírovat diagnostiku");
        pm.getMenu().add(0, 3, 2, "Licence");
        pm.getMenu().add(0, 4, 3, "Přepnout na roli TABULE");
        pm.getMenu().add(0, 5, 4, "Zkontrolovat aktualizace");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: editDeviceName(); return true;
                case 2:
                    Ui.copy(this, "Diagnostika", Ui.info() + "\nRole: vysílač · jméno: " + ClientLink.deviceName(this)
                        + "\nSíť zařízení: " + Wire.formatEndpointsInline(Wire.endpoints(this)) + "\n" + ShareService.diagnostics());
                    return true;
                case 3: showLicenses(); return true;
                case 5: Updater.checkInteractive(this); return true;
                case 4:
                    Ui.setRole(this, Ui.ROLE_BOARD);
                    stopService(new Intent(this, ShareService.class));
                    openBoard();
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void editDeviceName() {
        EditText e = new EditText(this);
        e.setText(ClientLink.deviceName(this));
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("Jméno tohoto zařízení")
            .setMessage("Zobrazí se na tabuli při prezentaci a v seznamu spárovaných zařízení.")
            .setView(e)
            .setPositiveButton("Uložit", (d, w) -> ClientLink.setDeviceName(this, e.getText().toString()))
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void renderBoards() {
        if (boardsList == null) return;
        lastBoardsSignature = prefs().getString("boards", "");
        boardsList.removeAllViews();
        List<BoardStore.Board> boards = ClientLink.load(this).list();
        if (boards.isEmpty()) {
            boardsList.addView(Ui.muted(this, "Zatím žádná tabule. Naskenujte QR kód z tabule, nebo ji najděte v síti a zadejte PIN.", 14));
            return;
        }
        for (BoardStore.Board b : boards) {
            LinearLayout row = Ui.subpanel(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labels = Ui.column(this);
            TextView n = Ui.text(this, b.name, 17);
            n.setTypeface(null, Typeface.BOLD);
            n.setPadding(0, 0, 0, 0);
            labels.addView(n);
            TextView sub = Ui.muted(this, b.address() + (b.hasV2Key() ? "" : " · starší spárování (0.6)"), 12);
            sub.setPadding(0, 0, 0, 0);
            labels.addView(sub);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Button share = Ui.button(this, "Sdílet", () -> startShare(b.fp));
            row.addView(share);
            Button more = Ui.secondaryButton(this, "⋯", null);
            more.setOnClickListener(v -> boardMenu(v, b));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            row.addView(more, mlp);
            boardsList.addView(row, Ui.vSpace(this, 0, 8));
        }
    }

    private void boardMenu(View anchor, BoardStore.Board b) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Přejmenovat");
        pm.getMenu().add(0, 2, 1, "Odebrat");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                EditText e = new EditText(this);
                e.setText(b.name);
                e.setSingleLine(true);
                new AlertDialog.Builder(this).setTitle("Název tabule").setView(e)
                    .setPositiveButton("Uložit", (d, w) -> {
                        BoardStore.Board c = ClientLink.load(this).find(b.fp);
                        if (c != null) { c.name = Proto.sanitize(e.getText().toString(), 64, c.name); ClientLink.save(this, c); renderBoards(); }
                    })
                    .setNegativeButton("Zrušit", null).show();
            } else {
                new AlertDialog.Builder(this).setTitle("Odebrat „" + b.name + "“?")
                    .setMessage("Pro další sdílení bude nutné tabuli znovu spárovat.")
                    .setPositiveButton("Odebrat", (d, w) -> { ClientLink.remove(this, b.fp); renderBoards(); })
                    .setNegativeButton("Zrušit", null).show();
            }
            return true;
        });
        pm.show();
    }

    private void renderLive() {
        if (liveCard == null) return;
        boolean live = ShareService.active;
        liveCard.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) {
            liveTitle.setText("Vysíláte na: " + (ShareService.targetName.isEmpty() ? "tabuli" : ShareService.targetName));
            liveStatus.setText(ShareService.status);
            liveStats.setText(ShareService.liveStats());
            pauseBtn.setText(ShareService.paused ? "▶ Pokračovat" : "⏸ Pozastavit");
            liveMuteBtn.setText(audioVolume == 0 ? "🔊 Zvuk" : "🔇 Ztlumit");
            statusPill.setText(ShareService.reconnecting ? "● Obnovuji…" : (ShareService.paused ? "● Pozastaveno" : "● Vysílám živě"));
            statusPill.setBackground(Ui.rounded(this, Ui.CARD_HI, Ui.ACCENT, 999));
            statusPill.setTextColor(Ui.ACCENT);
        } else {
            statusPill.setText("● Připraveno");
            statusPill.setBackground(Ui.rounded(this, Ui.PANEL, Ui.CARD_STROKE, 999));
            statusPill.setTextColor(Ui.MUTED);
            String st = ShareService.status;
            if (st != null && !st.startsWith("Připraveno") && !st.equals(lastShownStatus)) {
                lastShownStatus = st;
                if (!st.equals("Vysílání ukončeno.")) Toast.makeText(this, st, Toast.LENGTH_LONG).show();
            }
        }
    }

    private String lastShownStatus = "Připraveno k připojení.";

    // ------------------------------------------------------------------ audio / quality

    private void setVolume(int v, boolean persist) {
        audioVolume = AudioUtil.clampPercent(v);
        if (audioVolume > 0) lastNonZeroVolume = audioVolume;
        ShareService.audioVolumePercent = audioVolume;
        if (volumeLabel != null) volumeLabel.setText("Hlasitost zvuku na tabuli: " + audioVolume + " %");
        if (muteBtn != null) muteBtn.setText(audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit");
        if (volumeSeek != null && volumeSeek.getProgress() != audioVolume) volumeSeek.setProgress(audioVolume);
        if (persist) prefs().edit().putInt(K_AUDIO_VOL, audioVolume).apply();
    }

    private void setKeepScreenOn(boolean on) {
        keepScreenOn = on;
        prefs().edit().putBoolean(K_KEEP_ON, on).apply();
        ShareService.setKeepScreenOn(on);
        // setChecked with an unchanged value fires no listener, so syncing the twin switch cannot loop.
        if (liveKeepOnSwitch != null && liveKeepOnSwitch.isChecked() != on) liveKeepOnSwitch.setChecked(on);
        if (advKeepOnSwitch != null && advKeepOnSwitch.isChecked() != on) advKeepOnSwitch.setChecked(on);
    }

    private void toggleMute() {
        setVolume(audioVolume == 0 ? Math.max(20, lastNonZeroVolume) : 0, true);
        renderLive();
    }

    private void updateVolumeControlsEnabled() {
        boolean on = audioEnabled && Build.VERSION.SDK_INT >= 29;
        float alpha = on ? 1f : 0.45f;
        volumeSeek.setEnabled(on);
        muteBtn.setEnabled(on);
        volumeLabel.setAlpha(alpha);
        volumeSeek.setAlpha(alpha);
        muteBtn.setAlpha(alpha);
    }

    private void chooseQuality() {
        new AlertDialog.Builder(this)
            .setTitle("Režim kvality a odezvy")
            .setSingleChoiceItems(ShareService.QUALITY_LABELS, quality, (d, which) -> {
                quality = which;
                prefs().edit().putInt(K_QUALITY, quality).apply();
                qualityBtn.setText("Režim obrazu: " + ShareService.QUALITY_LABELS[quality]);
                d.dismiss();
            })
            .setNegativeButton("Zavřít", null)
            .show();
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
            Ui.message(this, "Licence", Ui.problem(e), null);
        }
    }

    // ------------------------------------------------------------------ pairing

    private interface Job { BoardStore.Board run(Wire.PhaseListener phase) throws Exception; }

    /** Runs a pairing job in the background with a progress dialog, then starts sharing to the paired board. */
    private void runPairing(String title, Job job) {
        ProgressBar pb = new ProgressBar(this);
        TextView msg = Ui.muted(this, "Připojuji se…", 14);
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, p);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(pb);
        box.addView(msg, Ui.vSpace(this, 10, 0));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).show();
        new Thread(() -> {
            try {
                BoardStore.Board b = job.run(phase -> runOnUiThread(() -> msg.setText(phase + "…")));
                runOnUiThread(() -> {
                    dlg.dismiss();
                    renderBoards();
                    Toast.makeText(this, "Spárováno: " + b.name, Toast.LENGTH_SHORT).show();
                    startShare(b.fp);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dlg.dismiss();
                    Ui.error(this, e instanceof Proto.ProtoException ? e.getMessage() : Ui.problem(e));
                });
            }
        }, "tabule-pair").start();
    }

    private void pairCode(String text) {
        final PairCode code;
        try {
            code = new PairCode(text);
        } catch (Exception e) {
            Ui.error(this, e.getMessage());
            return;
        }
        String hint = Wire.subnetMismatchHint(this, code.host);
        runPairing("Párování s tabulí", phase -> {
            try {
                return ClientLink.pairWithCode(this, code, phase);
            } catch (Exception e) {
                if (hint != null && !(e instanceof Proto.ProtoException)) throw new Exception(Ui.problem(e) + "\n" + hint);
                throw e;
            }
        });
    }

    void scan() {
        if (ShareService.active) { Ui.error(this, "Nejprve ukončete současné vysílání."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, SCAN);
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
            .setMessage("Na tabuli: ⚙ Správa → Ruční kód.")
            .setView(e)
            .setPositiveButton("Spárovat", (d, w) -> pairCode(e.getText().toString()))
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void discoverAndPair() {
        if (ShareService.active) { Ui.error(this, "Nejprve ukončete současné vysílání."); return; }
        ProgressBar pb = new ProgressBar(this);
        AlertDialog searching = new AlertDialog.Builder(this).setTitle("Hledám tabule v síti…").setView(pb).setCancelable(true).show();
        new Thread(() -> {
            List<DiscoveryProtocol.Offer> offers = DiscoveryClient.discover(1800);
            runOnUiThread(() -> {
                if (!searching.isShowing()) return;
                searching.dismiss();
                if (offers.isEmpty()) {
                    Ui.message(this, "Žádná tabule nenalezena",
                        "Ověřte, že je tabule ve stejné síti a má otevřený Tabule Link 0.7 (nebo zapnutý trvalý příjem). Případně naskenujte QR kód.", null);
                    return;
                }
                String[] labels = new String[offers.size()];
                for (int i = 0; i < offers.size(); i++) labels[i] = offers.get(i).name + "  (" + offers.get(i).ip + ")";
                new AlertDialog.Builder(this)
                    .setTitle("Vyberte tabuli")
                    .setItems(labels, (d, w) -> askPin(offers.get(w)))
                    .setNegativeButton("Zrušit", null)
                    .show();
            });
        }, "tabule-discover").start();
    }

    private void askPin(DiscoveryProtocol.Offer offer) {
        BoardStore.Board known = ClientLink.load(this).find(offer.fp);
        if (known != null && known.hasV2Key()) {
            if (!known.host.equals(offer.ip) || known.port != offer.port) { known.host = offer.ip; known.port = offer.port; ClientLink.save(this, known); }
            startShare(known.fp);
            return;
        }
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("6místný PIN z tabule");
        e.setTextSize(24);
        new AlertDialog.Builder(this)
            .setTitle(offer.name)
            .setMessage("Zadejte PIN zobrazený na tabuli.")
            .setView(e)
            .setPositiveButton("Spárovat", (d, w) -> {
                String pin = DiscoveryProtocol.normalizePin(e.getText().toString());
                if (pin == null) { Ui.error(this, "PIN musí mít 6 číslic."); return; }
                runPairing("Párování PINem", phase -> ClientLink.pairWithPin(this, offer, pin, phase));
            })
            .setNegativeButton("Zrušit", null)
            .show();
    }

    // ------------------------------------------------------------------ sharing

    private void startShare(String fp) {
        if (ShareService.active) { Ui.error(this, "Nejprve ukončete současné vysílání."); return; }
        pendingShareFp = fp;
        List<String> perms = new ArrayList<>();
        if (audioEnabled && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) { requestPermissions(perms.toArray(new String[0]), PERMS); return; }
        launchCapture();
    }

    private void launchCapture() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent capture;
        if (fullScreenOnly && Build.VERSION.SDK_INT >= 34) {
            // Android 14+ otherwise opens the consent dialog on "share one app"; this config offers only the whole screen.
            capture = m.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            capture = m.createScreenCaptureIntent();
        }
        startActivityForResult(capture, CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == SCAN) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(new Intent(this, ScanActivity.class), SCAN);
            } else {
                Ui.error(this, "Fotoaparát slouží pouze ke čtení QR kódu. Můžete tabuli najít v síti (PIN) nebo vložit kód.");
            }
        } else if (request == PERMS && pendingShareFp != null) {
            if (audioEnabled && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Oprávnění pro zvuk nebylo uděleno — přenáším jen obraz.", Toast.LENGTH_LONG).show();
            }
            launchCapture();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN && result == RESULT_OK && data != null) {
            pairCode(data.getStringExtra("pairing"));
        } else if (request == CAPTURE) {
            if (result == RESULT_OK && data != null && pendingShareFp != null) {
                Intent i = new Intent(this, ShareService.class)
                    .putExtra("board_fp", pendingShareFp)
                    .putExtra("quality", quality)
                    .putExtra("audio", audioEnabled)
                    .putExtra("audioVolume", audioVolume)
                    .putExtra("keepScreenOn", keepScreenOn)
                    .putExtra("result", result)
                    .putExtra("capture", data);
                try {
                    startForegroundService(i);
                    lastShownStatus = "Připojuji se k tabuli…";
                    pendingMinimize = minimizeOnShare;
                    pendingMinimizeAt = SystemClock.elapsedRealtime();
                } catch (Exception e) {
                    Ui.error(this, Ui.problem(e));
                }
            } else if (result != RESULT_OK) {
                Toast.makeText(this, "Sdílení obrazovky nebylo povoleno.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("pending_fp", pendingShareFp); }
    @Override protected void onRestoreInstanceState(Bundle b) { super.onRestoreInstanceState(b); pendingShareFp = b.getString("pending_fp"); }
    @Override protected void onResume() {
        super.onResume();
        if (boardsList == null) return;
        main.removeCallbacks(tick);
        main.post(tick);
        showUpdateCard(Updater.availableVersion(this));
        Updater.maybeAutoCheck(this, (found, error) -> showUpdateCard(found.version));
    }

    private void showUpdateCard(String version) {
        if (updateCard == null) return;
        if (version == null) { updateCard.setVisibility(View.GONE); return; }
        updateText.setText("Je k dispozici Tabule Link " + version + " (máte " + Updater.currentVersion(this) + ").");
        updateCard.setVisibility(View.VISIBLE);
    }
    @Override protected void onPause() { main.removeCallbacks(tick); pendingMinimize = false; super.onPause(); }
}
