package cz.ideamirror.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

/**
 * Board screen. Idle: big board name, QR code and PIN readable from across the room. Presenting: video only,
 * controls appear on tap and hide again. Everything technical lives in the "Správa" panel (optionally PIN protected).
 */
public class ReceiveActivity extends Activity implements ReceiverService.UiListener {
    private static final long TOOLBAR_HIDE_MS = 4000;
    private static final long IDLE_CLOSE_MS = 10 * 60_000L;

    private FrameLayout root;
    private LinearLayout videoRow;
    private final FrameLayout[] slotFrames = new FrameLayout[ReceiverService.SLOTS];
    private final SurfaceView[] slotViews = new SurfaceView[ReceiverService.SLOTS];
    private final TextView[] slotBadges = new TextView[ReceiverService.SLOTS];
    private final int[] slotW = new int[ReceiverService.SLOTS], slotH = new int[ReceiverService.SLOTS];
    private final boolean[] slotShowing = new boolean[ReceiverService.SLOTS];

    private View pairingOverlay;
    private ScrollView adminPanel;
    private LinearLayout adminContent;
    private LinearLayout toolbar;
    private Button handleBtn;
    private TextView boardNameView, pinView, addressView, statusPill, message, lastPresenterView;
    private ImageView qr;
    private String code;
    private String qrRendered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean attached = false;
    private long lastMoveSent = 0;
    private AlertDialog approvalDialog;

    private final Runnable attachRetry = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            ReceiverService svc = ReceiverService.instance;
            if (svc != null && svc.settings() != null) {
                attached = true;
                svc.attachUi(ReceiveActivity.this);
                for (int i = 0; i < ReceiverService.SLOTS; i++) {
                    Surface s = slotViews[i].getHolder().getSurface();
                    if (s != null && s.isValid()) svc.setSurface(i, s);
                }
            } else {
                main.postDelayed(this, 80);
            }
        }
    };

    private final Runnable hideToolbar = () -> { if (toolbar != null && presenting()) toolbar.setVisibility(View.GONE); };

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            ReceiverService svc = ReceiverService.instance;
            if (svc != null && svc.settings() != null && svc.settings().alwaysOn() && svc.settings().idleClose()
                && svc.sessions().isEmpty() && adminPanel.getVisibility() != View.VISIBLE && approvalDialog == null) {
                finish(); // the service keeps listening; the board returns to its home screen (no QR burn-in)
                return;
            }
            main.postDelayed(this, IDLE_CLOSE_MS);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ensureServiceStarted();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); } catch (Exception ignored) {}
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoRow = new LinearLayout(this);
        videoRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(videoRow, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < ReceiverService.SLOTS; i++) buildSlot(i);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (bt - t) != (ob - ot)) main.post(this::layoutSlots);
        });

        pairingOverlay = buildPairingOverlay();
        root.addView(pairingOverlay, new FrameLayout.LayoutParams(-1, -1));

        adminPanel = new ScrollView(this);
        adminPanel.setFillViewport(true);
        adminPanel.setBackground(Ui.screenBg());
        adminPanel.setVisibility(View.GONE);
        adminContent = Ui.column(this);
        int p = Ui.dp(this, 24);
        adminContent.setPadding(p, Ui.dp(this, 18), p, p);
        adminPanel.addView(adminContent);
        root.addView(adminPanel, new FrameLayout.LayoutParams(-1, -1));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackground(Ui.rounded(this, 0xE60B1320, Ui.CARD_STROKE, 14));
        int tp = Ui.dp(this, 6);
        toolbar.setPadding(tp, tp, tp, tp);
        Button endBtn = Ui.dangerButton(this, "■ Ukončit prezentaci", this::confirmEndPresentation);
        Button adminBtn = Ui.secondaryButton(this, "⚙ Správa", this::openAdmin);
        Button closeBtn = Ui.secondaryButton(this, "✕ Zavřít", this::closeScreen);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(-2, -2);
        tbLp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        toolbar.addView(endBtn, tbLp);
        toolbar.addView(adminBtn, new LinearLayout.LayoutParams(tbLp));
        toolbar.addView(closeBtn, new LinearLayout.LayoutParams(tbLp));
        toolbar.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        int m = Ui.dp(this, 14);
        tlp.setMargins(m, m, m, m);
        root.addView(toolbar, tlp);

        handleBtn = Ui.secondaryButton(this, "⋮", this::showToolbar);
        handleBtn.setAlpha(0.55f);
        handleBtn.setMinWidth(Ui.dp(this, 44));
        handleBtn.setMinimumWidth(Ui.dp(this, 44));
        handleBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        hlp.setMargins(m, m, m, m);
        root.addView(handleBtn, hlp);

        setContentView(root);
        main.postDelayed(idleCheck, IDLE_CLOSE_MS);
    }

    // ------------------------------------------------------------------ building views

    private void buildSlot(final int i) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                ReceiverService svc = ReceiverService.instance;
                if (attached && svc != null) svc.setSurface(i, h.getSurface());
                else { main.removeCallbacks(attachRetry); attachRetry.run(); }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                ReceiverService svc = ReceiverService.instance;
                if (svc != null) svc.setSurface(i, null);
            }
        });
        sv.setOnTouchListener((v, ev) -> onVideoTouch(i, v, ev));
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        TextView badge = Ui.pill(this, "", 0xCC0B1320, Ui.CARD_STROKE, Ui.TEXT);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        int bm = Ui.dp(this, 12);
        blp.setMargins(bm, bm, bm, bm);
        frame.addView(badge, blp);
        slotFrames[i] = frame;
        slotViews[i] = sv;
        slotBadges[i] = badge;
        videoRow.addView(frame, new LinearLayout.LayoutParams(0, -1, 1f));
        if (i > 0) frame.setVisibility(View.GONE);
    }

    private View buildPairingOverlay() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackground(Ui.screenBg());
        LinearLayout wrap = Ui.column(this);
        wrap.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        wrap.setPadding(pad, Ui.dp(this, 20), pad, pad);
        sc.addView(wrap);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
        iconLp.setMargins(0, 0, Ui.dp(this, 14), 0);
        top.addView(icon, iconLp);
        boardNameView = Ui.text(this, "Tabule Link", 34);
        boardNameView.setTypeface(null, Typeface.BOLD);
        top.addView(boardNameView, new LinearLayout.LayoutParams(0, -2, 1f));
        statusPill = Ui.pill(this, "● Připravuji…", Ui.CARD_HI, Ui.CARD_HI_STROKE, Ui.ACCENT);
        top.addView(statusPill);
        Button gear = Ui.secondaryButton(this, "⚙", this::openAdmin);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-2, -2);
        gLp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        top.addView(gear, gLp);
        wrap.addView(top, Ui.vSpace(this, 0, 22));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 640;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(body, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout qrFrame = new FrameLayout(this);
        qrFrame.setBackground(Ui.rounded(this, Color.WHITE, Ui.ACCENT, 22));
        int qp = Ui.dp(this, 16);
        qrFrame.setPadding(qp, qp, qp, qp);
        qr = new ImageView(this);
        int qrSize = Ui.dp(this, wide ? 300 : 230);
        qrFrame.addView(qr, new FrameLayout.LayoutParams(qrSize, qrSize));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(-2, -2);
        qrLp.gravity = Gravity.CENTER;
        if (wide) qrLp.setMargins(0, 0, Ui.dp(this, 36), 0); else qrLp.setMargins(0, 0, 0, Ui.dp(this, 20));
        body.addView(qrFrame, qrLp);

        LinearLayout info = Ui.column(this);
        TextView steps = Ui.text(this, "1.  Otevřete Tabule Link na tabletu nebo notebooku\n2.  Naskenujte QR kód, nebo zadejte PIN\n3.  Hotovo — příště stačí jedno klepnutí", 20);
        steps.setLineSpacing(Ui.dp(this, 6), 1f);
        info.addView(steps, Ui.vSpace(this, 0, 18));
        info.addView(Ui.section(this, "PIN"));
        pinView = Ui.text(this, "··· ···", 64);
        pinView.setTextColor(Ui.ACCENT);
        pinView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinView.setLetterSpacing(0.12f);
        pinView.setPadding(0, 0, 0, 0);
        info.addView(pinView);
        addressView = Ui.muted(this, "", 15);
        info.addView(addressView, Ui.vSpace(this, 6, 10));
        message = Ui.text(this, "Připravuji zabezpečené připojení…", 16);
        info.addView(message);
        lastPresenterView = Ui.muted(this, "", 14);
        info.addView(lastPresenterView, Ui.vSpace(this, 4, 0));
        body.addView(info, wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2));
        return sc;
    }

    // ------------------------------------------------------------------ render state

    private boolean presenting() {
        ReceiverService svc = ReceiverService.instance;
        return svc != null && !svc.sessions().isEmpty();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        ReceiverService svc = ReceiverService.instance;
        if (svc == null || svc.settings() == null) return;
        BoardSettings st = svc.settings();
        boardNameView.setText(st.boardName());
        Wire.Server srv = svc.getServer();
        String ip = currentIp(svc);
        if (srv != null && ip != null) {
            code = svc.pairingUri(ip);
            if (code != null && !code.equals(qrRendered)) renderQr(code);
            pinView.setText(DiscoveryProtocol.formatPin(st.pin()));
            addressView.setText("Adresa tabule: " + ip + ":" + srv.socket.getLocalPort() + " · Wi-Fi / LAN");
            message.setText("QR kód i PIN se po každém spárování změní. Spárovaná zařízení se připojí jedním klepnutím.");
        } else if (srv != null) {
            addressView.setText("Tabule nemá žádnou IPv4 adresu — připojte ji k síti.");
        } else if (!"-".equals(svc.diagLastError)) {
            message.setText("Chyba příjmu: " + svc.diagLastError);
        }
        String lp = svc.lastPresenter();
        lastPresenterView.setText(lp == null ? "" : "Naposledy prezentoval(a): " + lp);

        List<ReceiverService.SlotInfo> sessions = svc.sessions();
        boolean any = !sessions.isEmpty();
        statusPill.setText(srv == null ? "● Spouštím…" : (any ? "● Prezentuje se" : "● Připraveno"));
        boolean[] active = new boolean[ReceiverService.SLOTS];
        for (ReceiverService.SlotInfo s : sessions) {
            active[s.slot] = true;
            String badge = s.name + (s.paused ? "  ·  ⏸ pozastaveno" : "") + (s.touch ? "  ·  ☝ dotyk" : "");
            slotBadges[s.slot].setText(badge);
            if (slotW[s.slot] != s.width || slotH[s.slot] != s.height) { slotW[s.slot] = s.width; slotH[s.slot] = s.height; }
        }
        for (int i = 0; i < ReceiverService.SLOTS; i++) {
            if (!active[i]) slotShowing[i] = false;
            boolean show = active[i] || (i == 0 && !active[1]);
            slotFrames[i].setVisibility(show ? View.VISIBLE : View.GONE);
            boolean showBadge = active[i] && (sessions.size() > 1 || sessionPaused(sessions, i) || toolbar.getVisibility() == View.VISIBLE);
            slotBadges[i].setVisibility(showBadge ? View.VISIBLE : View.GONE);
        }
        boolean anyShowing = false;
        for (boolean b : slotShowing) anyShowing |= b;
        pairingOverlay.setVisibility(anyShowing ? View.GONE : View.VISIBLE);
        handleBtn.setVisibility(anyShowing && toolbar.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        if (!anyShowing) toolbar.setVisibility(View.GONE);
        layoutSlots();
        if (adminPanel.getVisibility() == View.VISIBLE) refreshAdminDynamic();
    }

    private static boolean sessionPaused(List<ReceiverService.SlotInfo> list, int slot) {
        for (ReceiverService.SlotInfo s : list) if (s.slot == slot) return s.paused;
        return false;
    }

    private String currentIp(ReceiverService svc) {
        String ip = svc.settings().advertisedIp();
        List<Wire.Endpoint> eps = Wire.endpoints(this);
        for (Wire.Endpoint e : eps) if (e.ip.equals(ip)) return ip;
        return eps.isEmpty() ? null : eps.get(0).ip;
    }

    private void renderQr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600, hints);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = matrix.get(x, y) ? 0xFF0B1320 : Color.WHITE;
            qr.setImageBitmap(Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888));
            qrRendered = text;
        } catch (Exception e) {
            message.setText(Ui.problem(e));
        }
    }

    private void layoutSlots() {
        for (int i = 0; i < ReceiverService.SLOTS; i++) {
            FrameLayout f = slotFrames[i];
            SurfaceView v = slotViews[i];
            if (f.getWidth() == 0 || f.getHeight() == 0 || slotW[i] <= 0 || slotH[i] <= 0) continue;
            double scale = Math.min((double) f.getWidth() / slotW[i], (double) f.getHeight() / slotH[i]);
            int w = Math.max(1, (int) (slotW[i] * scale)), h = Math.max(1, (int) (slotH[i] * scale));
            ViewGroup.LayoutParams old = v.getLayoutParams();
            if (old.width != w || old.height != h) v.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        }
    }

    // ------------------------------------------------------------------ touch & toolbar

    private boolean onVideoTouch(int slot, View v, MotionEvent ev) {
        ReceiverService svc = ReceiverService.instance;
        if (svc != null && svc.touchEnabled(slot) && v.getWidth() > 0 && v.getHeight() > 0) {
            int x = (int) (ev.getX() * 65535f / v.getWidth()), y = (int) (ev.getY() * 65535f / v.getHeight());
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) svc.sendTouch(slot, 'd', x, y);
            else if (a == MotionEvent.ACTION_MOVE) {
                long now = SystemClock.uptimeMillis();
                if (now - lastMoveSent >= 16) { lastMoveSent = now; svc.sendTouch(slot, 'm', x, y); }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) svc.sendTouch(slot, 'u', x, y);
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) showToolbar();
        return true;
    }

    private void showToolbar() {
        toolbar.setVisibility(View.VISIBLE);
        handleBtn.setVisibility(View.GONE);
        main.removeCallbacks(hideToolbar);
        main.postDelayed(() -> { hideToolbar.run(); render(); }, TOOLBAR_HIDE_MS);
        render();
    }

    private void confirmEndPresentation() {
        new AlertDialog.Builder(this)
            .setTitle("Ukončit prezentaci?")
            .setMessage("Prezentující bude odpojen a tabule se vrátí na obrazovku s QR kódem.")
            .setPositiveButton("Ukončit", (d, w) -> {
                ReceiverService svc = ReceiverService.instance;
                if (svc != null) svc.endAll(Proto.CTL_END_CLOSED);
            })
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void ensureServiceStarted() {
        try {
            startForegroundService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_START));
        } catch (Exception e) {
            Ui.error(this, Ui.problem(e));
        }
    }

    private void closeScreen() {
        if (!BoardSettings.isAlwaysOn(this)) stopService(new Intent(this, ReceiverService.class));
        finish();
    }

    // ------------------------------------------------------------------ admin panel ("Správa")

    private void openAdmin() {
        ReceiverService svc = ReceiverService.instance;
        if (svc == null || svc.settings() == null) return;
        if (!svc.settings().hasAdminPin()) { showAdmin(); return; }
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint("PIN správce");
        new AlertDialog.Builder(this)
            .setTitle("Správa tabule")
            .setView(e)
            .setPositiveButton("Otevřít", (d, w) -> {
                if (svc.settings().checkAdminPin(e.getText().toString())) showAdmin();
                else Toast.makeText(this, "Nesprávný PIN správce.", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private LinearLayout devicesBox;
    private LinearLayout endpointsBox;
    private TextView diagView;

    private void showAdmin() {
        ReceiverService svc = ReceiverService.instance;
        if (svc == null) return;
        BoardSettings st = svc.settings();
        adminContent.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Správa tabule", 26);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Ui.button(this, "Hotovo", () -> { adminPanel.setVisibility(View.GONE); render(); }));
        adminContent.addView(head, Ui.vSpace(this, 0, 14));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 900;
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        adminContent.addView(cols);
        LinearLayout left = Ui.column(this), right = Ui.column(this);
        LinearLayout.LayoutParams colLp = wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2);
        if (wide) colLp.setMargins(0, 0, Ui.dp(this, 12), 0);
        cols.addView(left, colLp);
        cols.addView(right, wide ? new LinearLayout.LayoutParams(0, -2, 1f) : new LinearLayout.LayoutParams(-1, -2));

        // Board name
        LinearLayout nameCard = card(left, "NÁZEV TABULE (ZOBRAZÍ SE NA NOTEBOOCÍCH A TABLETECH)");
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText nameEdit = new EditText(this);
        nameEdit.setText(st.boardName());
        nameEdit.setTextColor(Ui.TEXT);
        nameEdit.setSingleLine(true);
        nameRow.addView(nameEdit, new LinearLayout.LayoutParams(0, -2, 1f));
        nameRow.addView(Ui.secondaryButton(this, "Uložit", () -> {
            st.setBoardName(nameEdit.getText().toString());
            svc.onBoardNameChanged();
            Toast.makeText(this, "Název uložen.", Toast.LENGTH_SHORT).show();
        }));
        nameCard.addView(nameRow);

        // Presentation
        LinearLayout pres = card(left, "PREZENTACE");
        Ui.toggleRow(pres, "Potvrzovat převzetí obrazovky", "Když už někdo prezentuje, další se připojí až po schválení na tabuli (moderátor).", st.moderator(), (b, c) -> st.setModerator(c));
        Ui.toggleRow(pres, "Rozdělená obrazovka", "Až dva prezentující vedle sebe (zvuk hraje od posledního připojeného).", st.split(), (b, c) -> { st.setSplit(c); svc.applySplitSetting(); });
        Ui.toggleRow(pres, "Povolit ovládání dotykem", "Dotyky na tabuli ovládají sdílený notebook (musí to povolit i notebook).", st.allowTouch(), (b, c) -> { st.setAllowTouch(c); render(); });
        TextView volLabel = Ui.text(this, "Hlasitost příjmu: " + st.rxVolume() + " %", 14);
        pres.addView(volLabel);
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(st.rxVolume());
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                volLabel.setText("Hlasitost příjmu: " + progress + " %");
                svc.setReceiveVolume(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        pres.addView(vol);
        pres.addView(Ui.muted(this, "Mění jen hlasitost Tabule Link, ne videokonference.", 12));

        // Start-up
        LinearLayout boot = card(left, "SPOUŠTĚNÍ");
        Ui.toggleRow(boot, "Trvalý příjem na pozadí (i po startu)", "Tabule stále naslouchá a při připojení sama otevře obraz.", st.alwaysOn(), (b, c) -> {
            st.setAlwaysOn(c);
            if (c) ensureServiceStarted();
        });
        Ui.toggleRow(boot, "Po startu tabule otevřít obrazovku s QR kódem", null, st.autoOpenOnBoot(), (b, c) -> st.setAutoOpenOnBoot(c));
        Ui.toggleRow(boot, "Zavřít obrazovku po 10 min nečinnosti", "V trvalém režimu se tabule vrátí na svou plochu; příjem běží dál.", st.idleClose(), (b, c) -> st.setIdleClose(c));
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            boot.addView(Ui.secondaryButton(this, "Povolit vyskočení obrazu přes jiné aplikace", () -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { Ui.error(this, "Otevřete v nastavení tabule oprávnění „Zobrazovat přes jiné aplikace“ pro Tabule Link."); }
            }), Ui.vSpace(this, 4, 0));
        }

        // Network
        LinearLayout net = card(right, "SÍŤ — ADRESA V QR KÓDU");
        endpointsBox = Ui.column(this);
        net.addView(endpointsBox);
        net.addView(Ui.secondaryButton(this, "Obnovit síť", this::refreshAdminDynamic), Ui.vSpace(this, 6, 0));

        // Security
        LinearLayout sec = card(right, "ZABEZPEČENÍ A SPÁROVANÁ ZAŘÍZENÍ");
        LinearLayout secRow = new LinearLayout(this);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.setMargins(0, 0, Ui.dp(this, 6), 0);
        secRow.addView(Ui.secondaryButton(this, "Nový QR kód a PIN", () -> startService(ReceiverService.ACTION_ROTATE_PAIRING)), half);
        secRow.addView(Ui.secondaryButton(this, "Ruční kód", this::showManualCode), new LinearLayout.LayoutParams(0, -2, 1f));
        sec.addView(secRow, Ui.vSpace(this, 0, 8));
        devicesBox = Ui.column(this);
        sec.addView(devicesBox);
        sec.addView(Ui.dangerButton(this, "Odvolat všechna zařízení", () -> new AlertDialog.Builder(this)
            .setTitle("Odvolat všechna zařízení?")
            .setMessage("Všechny tablety a notebooky (i starší verze 0.6) bude nutné znovu spárovat QR kódem nebo PINem.")
            .setPositiveButton("Odvolat vše", (d, w) -> startService(ReceiverService.ACTION_REVOKE_ALL))
            .setNegativeButton("Zrušit", null)
            .show()), Ui.vSpace(this, 8, 8));
        sec.addView(Ui.secondaryButton(this, st.hasAdminPin() ? "Změnit / zrušit PIN správce" : "Nastavit PIN správce (zamkne Správu)", this::editAdminPin));

        // Diagnostics & device
        LinearLayout diag = card(right, "DIAGNOSTIKA A ZAŘÍZENÍ");
        diagView = Ui.muted(this, "", 12);
        diagView.setTextIsSelectable(true);
        diag.addView(diagView);
        LinearLayout dRow = new LinearLayout(this);
        dRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, -2, 1f);
        h2.setMargins(0, 0, Ui.dp(this, 6), 0);
        dRow.addView(Ui.secondaryButton(this, "Zkopírovat diagnostiku", () -> Ui.copy(this, "Diagnostika tabule", diagnosticsText())), h2);
        dRow.addView(Ui.secondaryButton(this, "Přepnout na vysílač", this::switchRole), new LinearLayout.LayoutParams(0, -2, 1f));
        diag.addView(dRow, Ui.vSpace(this, 8, 0));

        adminPanel.setVisibility(View.VISIBLE);
        adminPanel.scrollTo(0, 0);
        refreshAdminDynamic();
    }

    private LinearLayout card(LinearLayout parent, String section) {
        LinearLayout c = Ui.card(this, false);
        c.addView(Ui.section(this, section));
        parent.addView(c, Ui.vSpace(this, 0, 12));
        return c;
    }

    private void startService(String action) {
        try { startForegroundService(new Intent(this, ReceiverService.class).setAction(action)); }
        catch (Exception e) { Ui.error(this, Ui.problem(e)); }
    }

    private void refreshAdminDynamic() {
        ReceiverService svc = ReceiverService.instance;
        if (svc == null || devicesBox == null) return;
        // endpoints
        endpointsBox.removeAllViews();
        List<Wire.Endpoint> eps = Wire.endpoints(this);
        String cur = currentIp(svc);
        if (eps.isEmpty()) endpointsBox.addView(Ui.muted(this, "Žádná IPv4 adresa. Připojte tabuli k Wi-Fi nebo kabelem.", 13));
        for (Wire.Endpoint e : eps) {
            boolean sel = e.ip.equals(cur);
            Button b = sel ? Ui.button(this, "✓ " + e.label(), () -> {}) : Ui.secondaryButton(this, e.label(), () -> {
                svc.setAdvertisedIp(e.ip);
                render();
            });
            b.setTextSize(13);
            endpointsBox.addView(b, Ui.vSpace(this, 0, 6));
        }
        // devices
        devicesBox.removeAllViews();
        List<DeviceRegistry.Device> list = svc.settings().registry().list();
        devicesBox.addView(Ui.muted(this, list.isEmpty() ? "Zatím žádné spárované zařízení (verze 0.7)." : "Spárovaná zařízení (" + list.size() + "):", 13));
        for (DeviceRegistry.Device d : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labels = Ui.column(this);
            TextView n = Ui.text(this, d.name, 15);
            n.setPadding(0, 0, 0, 0);
            labels.addView(n);
            TextView sub = Ui.muted(this, "naposledy " + Ui.ago(d.lastSeen), 12);
            sub.setPadding(0, 0, 0, 0);
            labels.addView(sub);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            Button rev = Ui.secondaryButton(this, "Odvolat", () -> new AlertDialog.Builder(this)
                .setTitle("Odvolat „" + d.name + "“?")
                .setMessage("Zařízení se už nebude moci připojit bez nového spárování.")
                .setPositiveButton("Odvolat", (dd, w) -> svc.revokeDevice(d.id))
                .setNegativeButton("Zrušit", null)
                .show());
            rev.setTextSize(13);
            row.addView(rev);
            devicesBox.addView(row, Ui.vSpace(this, 2, 4));
        }
        if (diagView != null) diagView.setText(diagnosticsText());
    }

    private void showManualCode() {
        if (code == null) return;
        new AlertDialog.Builder(this)
            .setTitle("Párovací kód (platí do dalšího spárování)")
            .setMessage(code)
            .setPositiveButton("Zkopírovat", (d, w) -> Ui.copy(this, "Párování", code))
            .setNegativeButton("Zavřít", null)
            .show();
    }

    private void editAdminPin() {
        ReceiverService svc = ReceiverService.instance;
        if (svc == null) return;
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint("4–8 číslic (prázdné = bez PINu)");
        new AlertDialog.Builder(this)
            .setTitle("PIN správce")
            .setMessage("Chrání panel Správa (nastavení, odvolání zařízení) před ostatními uživateli tabule.")
            .setView(e)
            .setPositiveButton("Uložit", (d, w) -> {
                String pin = e.getText().toString().trim();
                if (!pin.isEmpty() && !pin.matches("[0-9]{4,8}")) { Toast.makeText(this, "PIN musí mít 4–8 číslic.", Toast.LENGTH_SHORT).show(); return; }
                svc.settings().setAdminPin(pin);
                Toast.makeText(this, pin.isEmpty() ? "PIN správce zrušen." : "PIN správce nastaven.", Toast.LENGTH_SHORT).show();
                showAdmin();
            })
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void switchRole() {
        Ui.setRole(this, Ui.ROLE_SENDER);
        BoardSettings.prefs(this).edit().putBoolean("always_on", false).apply();
        stopService(new Intent(this, ReceiverService.class));
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private String diagnosticsText() {
        ReceiverService s = ReceiverService.instance;
        String eps = Wire.formatEndpointsInline(Wire.endpoints(this));
        if (s == null) return Ui.info() + "\nSíťová rozhraní: " + eps + "\nSlužba příjmu neběží.";
        StringBuilder sb = new StringBuilder(Ui.info());
        sb.append("\nRole: přijímač (tabule) · název: ").append(s.settings().boardName());
        sb.append("\nTrvalý příjem: ").append(s.settings().alwaysOn() ? "zapnuto" : "vypnuto");
        sb.append(" · moderátor: ").append(s.settings().moderator() ? "ano" : "ne");
        sb.append(" · rozdělení: ").append(s.settings().split() ? "ano" : "ne");
        sb.append("\nSíťová rozhraní: ").append(eps);
        sb.append("\nTLS klíč: ").append(s.diagKeySource);
        sb.append("\nFáze: ").append(s.diagPhase);
        sb.append("\nDekodér: ").append(s.diagDecoder);
        sb.append("\nZvuk: ").append(s.diagAudio);
        for (ReceiverService.SlotInfo si : s.sessions()) {
            sb.append("\nPrezentuje [").append(si.slot + 1).append("]: ").append(si.name).append(" · ").append(si.width).append("x").append(si.height)
                .append(si.paused ? " · pozastaveno" : "").append(si.touch ? " · dotyk" : "");
        }
        sb.append("\nFPS: ").append(s.diagFps).append(" · pakety/vykreslené/přeskočené: ")
            .append(s.diagPackets).append(" / ").append(s.diagRendered).append(" / ").append(s.diagSkipped);
        sb.append("\nSpárovaná zařízení: ").append(s.settings().registry().size());
        List<String> ev = s.events();
        if (!ev.isEmpty()) {
            sb.append("\nUdálosti:");
            for (String e : ev) sb.append("\n  ").append(e);
        }
        sb.append("\nPoslední chyba: ").append(s.diagLastError);
        return sb.toString();
    }

    // ------------------------------------------------------------------ ReceiverService.UiListener

    @Override public void onBoardChanged() { runOnUiThread(this::render); }

    @Override public void onSlotVideo(int slot, int width, int height) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            slotW[slot] = width;
            slotH[slot] = height;
            slotShowing[slot] = true;
            render();
        });
    }

    @Override public void onSlotEnded(int slot, String msg) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            slotShowing[slot] = false;
            render();
            if (msg != null) message.setText(msg);
        });
    }

    @Override public void onApprovalRequest(ReceiverService.Approval a) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) { a.decide(false); return; }
            if (approvalDialog != null) approvalDialog.dismiss();
            final boolean[] decided = {false};
            approvalDialog = new AlertDialog.Builder(this)
                .setTitle(a.deviceName + " chce převzít obrazovku")
                .setMessage("Právě prezentuje " + a.currentName + ". Povolit převzetí? (bez odpovědi se za 30 s odmítne)")
                .setPositiveButton("Povolit", (d, w) -> { decided[0] = true; a.decide(true); })
                .setNegativeButton("Odmítnout", (d, w) -> { decided[0] = true; a.decide(false); })
                .setOnDismissListener(d -> { if (!decided[0]) a.decide(false); approvalDialog = null; })
                .create();
            approvalDialog.show();
            main.postDelayed(() -> { if (approvalDialog != null && !decided[0]) approvalDialog.dismiss(); }, 30_000);
        });
    }

    @Override public void onDiagnostics() {
        runOnUiThread(() -> { if (adminPanel.getVisibility() == View.VISIBLE && diagView != null) diagView.setText(diagnosticsText()); });
    }

    @Override public void onToast(String text) {
        runOnUiThread(() -> { if (!isFinishing()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, IDLE_CLOSE_MS);
    }

    @Override protected void onStart() {
        super.onStart();
        main.removeCallbacks(attachRetry);
        attachRetry.run();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override public void onBackPressed() {
        if (adminPanel.getVisibility() == View.VISIBLE) { adminPanel.setVisibility(View.GONE); render(); return; }
        if (presenting()) { showToolbar(); return; }
        closeScreen();
    }

    @Override protected void onStop() {
        main.removeCallbacks(attachRetry);
        ReceiverService svc = ReceiverService.instance;
        if (svc != null) {
            for (int i = 0; i < ReceiverService.SLOTS; i++) svc.setSurface(i, null);
            svc.detachUi(this);
        }
        attached = false;
        if (!BoardSettings.isAlwaysOn(this)) {
            // Without always-on the board only receives while this screen is open.
            stopService(new Intent(this, ReceiverService.class));
        }
        super.onStop();
        if (!isFinishing() && !isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (approvalDialog != null) { approvalDialog.dismiss(); approvalDialog = null; }
        ReceiverService svc = ReceiverService.instance;
        if (svc != null) svc.detachUi(this);
        super.onDestroy();
    }
}
