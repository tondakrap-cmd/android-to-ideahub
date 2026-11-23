package cz.ideamirror.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

public class ReceiveActivity extends Activity implements SurfaceHolder.Callback, ReceiverService.UiListener {
    private SurfaceView video;
    private View pairingOverlay;
    private TextView message, statusPill, pinView, address, allInterfacesView, diagView, rxVolLabel;
    private Button switchIpBtn, stopBtn;
    private ImageView qr;
    private FrameLayout root;
    private String code;
    private List<Wire.Endpoint> currentEndpoints = Collections.emptyList();
    private int selectedIndex = 0;
    private volatile Surface activeSurface;
    private volatile int videoWidth = 0, videoHeight = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean attached = false;

    private final Runnable attachRetry = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed() || activeSurface == null || !activeSurface.isValid()) return;
            ReceiverService svc = ReceiverService.instance;
            if (svc != null) {
                attached = true;
                svc.attachUi(ReceiveActivity.this, activeSurface);
            } else {
                main.postDelayed(this, 80);
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ensureServiceStarted();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (bt - t) != (ob - ot)) resizeVideo();
        });

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackground(Ui.screenBg());
        pairingOverlay = sc;

        LinearLayout centerWrap = Ui.column(this);
        centerWrap.setGravity(Gravity.CENTER);
        int outerPad = Ui.dp(this, 24);
        centerWrap.setPadding(outerPad, Ui.dp(this, 20), outerPad, outerPad);
        sc.addView(centerWrap);

        LinearLayout mainCard = Ui.card(this, false);
        centerWrap.addView(mainCard, new LinearLayout.LayoutParams(-1, -2));

        // Header
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(cz.ideamirror.app.R.drawable.icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        iconLp.setMargins(0, 0, Ui.dp(this, 12), 0);
        topBar.addView(icon, iconLp);

        LinearLayout titleCol = Ui.column(this);
        TextView title = Ui.text(this, "Tabule Link · Přijímač", 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 0);
        titleCol.addView(title);
        boolean alwaysOn = ReceiverService.isAlwaysOnEnabled(this);
        TextView sub = Ui.muted(this, alwaysOn
            ? "Trvalý příjem aktivní · další prezentující může kdykoli převzít obrazovku"
            : "Připraveno k zrcadlení · další prezentující může kdykoli převzít obrazovku", 14);
        sub.setPadding(0, 0, 0, 0);
        titleCol.addView(sub);
        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        statusPill = Ui.pill(this, "● Čekám na připojení", Ui.CARD_HI, Ui.CARD_HI_STROKE, Ui.ACCENT);
        topBar.addView(statusPill);
        mainCard.addView(topBar, Ui.vSpace(this, 0, 16));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 640;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        mainCard.addView(body);

        // Left: QR code
        LinearLayout qrCol = Ui.column(this);
        qrCol.setGravity(Gravity.CENTER);
        FrameLayout qrFrame = new FrameLayout(this);
        qrFrame.setBackground(Ui.rounded(this, Color.WHITE, Ui.ACCENT, 20));
        int qp = Ui.dp(this, 14);
        qrFrame.setPadding(qp, qp, qp, qp);
        qr = new ImageView(this);
        int qrSize = Ui.dp(this, wide ? 260 : 220);
        qrFrame.addView(qr, new FrameLayout.LayoutParams(qrSize, qrSize));
        qrCol.addView(qrFrame);
        TextView qrCaption = Ui.muted(this, "Načtěte poprvé QR kód — pak se tablet\npřipojí jedním klepnutím bez skenování.", 13);
        qrCaption.setGravity(Gravity.CENTER);
        qrCol.addView(qrCaption, Ui.vSpace(this, 10, 0));

        LinearLayout.LayoutParams qrColLp = wide
            ? new LinearLayout.LayoutParams(-2, -2)
            : new LinearLayout.LayoutParams(-1, -2);
        if (wide) qrColLp.setMargins(0, 0, Ui.dp(this, 24), 0);
        else qrColLp.setMargins(0, 0, 0, Ui.dp(this, 16));
        body.addView(qrCol, qrColLp);

        // Right: info, PIN, addresses & controls
        LinearLayout infoCol = Ui.column(this);
        message = Ui.text(this, "Připravuji zabezpečené připojení…", 16);
        message.setTextIsSelectable(true);
        infoCol.addView(message, Ui.vSpace(this, 0, 8));

        LinearLayout pinBox = Ui.card(this, true);
        int pp = Ui.dp(this, 12);
        pinBox.setPadding(pp, pp, pp, pp);
        pinBox.addView(Ui.section(this, "PIN PRO NOTEBOOK (WINDOWS) · AUTOMATICKÉ NALEZENÍ V SÍTI"));
        pinView = Ui.text(this, "··· ···", 30);
        pinView.setTextColor(Ui.ACCENT);
        pinView.setTypeface(null, Typeface.BOLD);
        pinView.setLetterSpacing(0.14f);
        pinView.setPadding(0, 0, 0, 0);
        pinView.setTextIsSelectable(true);
        pinBox.addView(pinView);
        TextView pinHint = Ui.muted(this, "Více notebooků i tabletů může mít stejné uložené spojení a střídavě si tabuli přebírat jedním kliknutím.", 12);
        pinHint.setPadding(0, Ui.dp(this, 2), 0, 0);
        pinBox.addView(pinHint);
        infoCol.addView(pinBox, Ui.vSpace(this, 0, 10));

        LinearLayout addrBox = Ui.subpanel(this);
        addrBox.addView(Ui.section(this, "ADRESA TABULE V QR KÓDU"));
        address = Ui.text(this, "Načítám…", 17);
        address.setTextColor(Ui.ACCENT);
        address.setTypeface(null, Typeface.BOLD);
        address.setTextIsSelectable(true);
        address.setOnClickListener(v -> chooseEndpointDialog());
        addrBox.addView(address);
        allInterfacesView = Ui.muted(this, "", 13);
        allInterfacesView.setTextIsSelectable(true);
        addrBox.addView(allInterfacesView, Ui.vSpace(this, 4, 0));
        infoCol.addView(addrBox, Ui.vSpace(this, 0, 12));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        switchIpBtn = Ui.button(this, "Vybrat síťovou adresu", this::chooseEndpointDialog);
        Button refreshNetBtn = Ui.secondaryButton(this, "Obnovit síť", () -> refreshEndpoints(false));
        LinearLayout.LayoutParams bLeft = new LinearLayout.LayoutParams(0, -2, 1f);
        bLeft.setMargins(0, 0, Ui.dp(this, 6), 0);
        LinearLayout.LayoutParams bRight = new LinearLayout.LayoutParams(0, -2, 1f);
        bRight.setMargins(Ui.dp(this, 6), 0, 0, 0);
        row1.addView(switchIpBtn, bLeft);
        row1.addView(refreshNetBtn, bRight);
        infoCol.addView(row1, Ui.vSpace(this, 0, 8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button manualBtn = Ui.secondaryButton(this, "Ruční kód", () -> {
            if (code != null) {
                new AlertDialog.Builder(this)
                    .setTitle("Párovací kód")
                    .setMessage(code)
                    .setPositiveButton("Zkopírovat", (d, w) -> Ui.copy(this, "Párování", code))
                    .setNegativeButton("Zavřít", null)
                    .show();
            }
        });
        Button resetKeyBtn = Ui.secondaryButton(this, "Nový klíč", this::confirmResetKey);
        Button diagBtn = Ui.secondaryButton(this, "Diagnostika", () -> Ui.copy(this, "Diagnostika tabule", diagnosticsText()));
        LinearLayout.LayoutParams bThirdL = new LinearLayout.LayoutParams(0, -2, 1f);
        bThirdL.setMargins(0, 0, Ui.dp(this, 4), 0);
        LinearLayout.LayoutParams bThirdM = new LinearLayout.LayoutParams(0, -2, 1f);
        bThirdM.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        LinearLayout.LayoutParams bThirdR = new LinearLayout.LayoutParams(0, -2, 1f);
        bThirdR.setMargins(Ui.dp(this, 4), 0, 0, 0);
        row2.addView(manualBtn, bThirdL);
        row2.addView(resetKeyBtn, bThirdM);
        row2.addView(diagBtn, bThirdR);
        infoCol.addView(row2, Ui.vSpace(this, 0, 10));

        int rxVol = ReceiverService.getReceiveVolume(this);
        LinearLayout volBox = Ui.subpanel(this);
        rxVolLabel = Ui.text(this, "Hlasitost příjmu na tabuli: " + rxVol + " %", 14);
        rxVolLabel.setPadding(0, 0, 0, Ui.dp(this, 4));
        volBox.addView(rxVolLabel);
        SeekBar rxSeek = new SeekBar(this);
        rxSeek.setMax(100);
        rxSeek.setProgress(rxVol);
        rxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                rxVolLabel.setText("Hlasitost příjmu na tabuli: " + progress + " %");
                ReceiverService.setReceiveVolume(ReceiveActivity.this, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        volBox.addView(rxSeek);
        volBox.addView(Ui.muted(this, "Omezí hlasitost jen pro Tabule Link (nemění hlasitost videokonference).", 12));
        infoCol.addView(volBox, Ui.vSpace(this, 0, 10));

        diagView = Ui.muted(this, diagnosticsSummary(), 12);
        diagView.setTextIsSelectable(true);
        infoCol.addView(diagView);

        LinearLayout.LayoutParams infoLp = wide
            ? new LinearLayout.LayoutParams(0, -2, 1f)
            : new LinearLayout.LayoutParams(-1, -2);
        body.addView(infoCol, infoLp);

        root.addView(sc, new FrameLayout.LayoutParams(-1, -1));

        stopBtn = Ui.dangerButton(this, alwaysOn ? "✕ Skrýt obraz" : "✕ Zavřít příjem", this::closeScreen);
        stopBtn.setAlpha(0.88f);
        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        int sm = Ui.dp(this, 14);
        stopLp.setMargins(sm, sm, sm, sm);
        root.addView(stopBtn, stopLp);
        setContentView(root);
    }

    private void ensureServiceStarted() {
        if (!ReceiverService.running) {
            try {
                startForegroundService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_START));
            } catch (Exception e) {
                Ui.error(this, Ui.problem(e));
            }
        }
    }

    private void closeScreen() {
        ReceiverService svc = ReceiverService.instance;
        if (svc != null) svc.detachUi(this);
        if (!ReceiverService.isAlwaysOnEnabled(this)) {
            stopService(new Intent(this, ReceiverService.class));
        }
        finish();
    }

    private void confirmResetKey() {
        new AlertDialog.Builder(this)
            .setTitle("Vytvořit nový párovací klíč a PIN?")
            .setMessage("Dříve uložená spojení v tabletech a noteboocích přestanou fungovat a bude nutné znovu načíst QR kód nebo zadat nový PIN.")
            .setPositiveButton("Vytvořit nový klíč", (d, w) -> {
                try {
                    startForegroundService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_RESET_KEY));
                } catch (Exception e) {
                    Ui.error(this, Ui.problem(e));
                }
            })
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void chooseEndpointDialog() {
        List<Wire.Endpoint> eps = Wire.endpoints(this);
        if (eps.isEmpty()) {
            Ui.error(this, "Tabule nemá žádnou dostupnou IPv4 síťovou adresu.");
            return;
        }
        String prevIp = (selectedIndex >= 0 && selectedIndex < currentEndpoints.size())
            ? currentEndpoints.get(selectedIndex).ip : null;
        currentEndpoints = eps;
        String[] labels = new String[eps.size()];
        for (int i = 0; i < eps.size(); i++) labels[i] = eps.get(i).label();
        new AlertDialog.Builder(this)
            .setTitle("Vyberte síťovou adresu tabule pro QR kód")
            .setItems(labels, (d, w) -> {
                selectedIndex = w;
                updateCode(eps.get(w));
                updateInterfacesBox(eps);
            })
            .setOnCancelListener(d -> {
                if (prevIp != null) restoreSelectionByIp(prevIp);
            })
            .show();
    }

    private void restoreSelectionByIp(String ip) {
        for (int i = 0; i < currentEndpoints.size(); i++) {
            if (currentEndpoints.get(i).ip.equals(ip)) { selectedIndex = i; return; }
        }
        selectedIndex = 0;
    }

    private void refreshEndpoints(boolean initial) {
        String prevIp = (!initial && selectedIndex >= 0 && selectedIndex < currentEndpoints.size())
            ? currentEndpoints.get(selectedIndex).ip : null;
        List<Wire.Endpoint> eps = Wire.endpoints(this);
        currentEndpoints = eps;
        if (eps.isEmpty()) {
            address.setText("Žádná IPv4 síťová adresa není dostupná.");
            allInterfacesView.setText("Připojte tabuli k Wi-Fi nebo kabelem a klepněte na „Obnovit síť“.");
            switchIpBtn.setText("Vybrat síťovou adresu (0)");
            return;
        }
        if (prevIp != null) restoreSelectionByIp(prevIp); else selectedIndex = 0;
        updateCode(eps.get(selectedIndex));
        updateInterfacesBox(eps);
        diagView.setText(diagnosticsSummary());
    }

    private void updateInterfacesBox(List<Wire.Endpoint> eps) {
        String header = eps.size() > 1
            ? "Všechna rozhraní tabule (" + eps.size() + "):"
            : "Dostupné rozhraní:";
        allInterfacesView.setText(header + "\n" + Wire.formatEndpointsMultiline(eps));
        switchIpBtn.setText(eps.size() > 1
            ? "Přepnout síť (" + (selectedIndex + 1) + "/" + eps.size() + ")"
            : "Síťová adresa (1/1)");
    }

    private void updateCode(Wire.Endpoint ep) {
        try {
            ReceiverService svc = ReceiverService.instance;
            Wire.Server srv = svc == null ? null : svc.getServer();
            if (srv == null) return;
            int port = srv.socket.getLocalPort();
            code = Wire.uri(ep.ip, port, srv.fp, srv.secret);
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 700, 700, hints);
            int[] pixels = new int[700 * 700];
            for (int y = 0; y < 700; y++) {
                for (int x = 0; x < 700; x++) {
                    pixels[y * 700 + x] = matrix.get(x, y) ? 0xFF0B1320 : Color.WHITE;
                }
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, 700, 700, Bitmap.Config.ARGB_8888));
            address.setText(ep.label() + " · port " + port);
            pinView.setText(DiscoveryProtocol.formatPin(srv.pin));
            svc.setAdvertisedIp(ep.ip);
        } catch (Exception e) {
            message.setText(Ui.problem(e));
        }
    }

    private String diagnosticsSummary() {
        ReceiverService s = ReceiverService.instance;
        if (s == null) return Ui.info() + "\nSpouštím službu příjmu…";
        return Ui.info() + "\nTLS: " + s.diagKeySource + " · Zvuk: " + s.diagAudio
            + "\nDekodér: " + s.diagDecoder + " · " + s.diagFps + " fps · Snímky/přeskočeno: "
            + s.diagRendered + "/" + s.diagSkipped;
    }

    private String diagnosticsText() {
        ReceiverService s = ReceiverService.instance;
        String eps = Wire.formatEndpointsInline(Wire.endpoints(this));
        if (s == null) return Ui.info() + "\nSíťová rozhraní: " + eps + "\nSlužba příjmu neběží.";
        return Ui.info()
            + "\nRole: přijímač (tabule)"
            + "\nTrvalý příjem: " + (ReceiverService.isAlwaysOnEnabled(this) ? "zapnuto" : "vypnuto")
            + "\nSíťová rozhraní: " + eps
            + "\nTLS klíč: " + s.diagKeySource
            + "\nFáze: " + s.diagPhase
            + "\nDekodér videa: " + s.diagDecoder
            + "\nZvuk: " + s.diagAudio
            + "\nRozlišení videa: " + s.videoWidth + "x" + s.videoHeight
            + "\nFPS: " + s.diagFps
            + "\nPřijaté pakety / vykreslené / přeskočené: " + s.diagPackets + " / " + s.diagRendered + " / " + s.diagSkipped
            + "\nPoslední chyba: " + s.diagLastError;
    }

    private void resizeVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || root.getWidth() == 0 || root.getHeight() == 0) return;
        double scale = Math.min((double) root.getWidth() / videoWidth, (double) root.getHeight() / videoHeight);
        int w = Math.max(1, (int) (videoWidth * scale)), h = Math.max(1, (int) (videoHeight * scale));
        ViewGroup.LayoutParams old = video.getLayoutParams();
        if (old.width != w || old.height != h) {
            video.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        }
    }

    // --- ReceiverService.UiListener callbacks ---

    @Override public void onServerReady() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshEndpoints(false);
            diagView.setText(diagnosticsSummary());
        });
    }

    @Override public void onStateChanged(String msg, String pill) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            message.setText(msg);
            if (pill != null) statusPill.setText(pill);
            diagView.setText(diagnosticsSummary());
        });
    }

    @Override public void onVideoStarted(int width, int height, boolean tookOver) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            videoWidth = width;
            videoHeight = height;
            resizeVideo();
            pairingOverlay.setVisibility(View.GONE);
        });
    }

    @Override public void onVideoEnded(String msg, String pill) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            pairingOverlay.setVisibility(View.VISIBLE);
            message.setText(msg);
            if (pill != null) statusPill.setText(pill);
            diagView.setText(diagnosticsSummary());
        });
    }

    @Override public void onDiagnosticsUpdated() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && diagView != null) {
                diagView.setText(diagnosticsSummary());
            }
        });
    }

    // --- SurfaceHolder & Activity lifecycle ---

    @Override public void surfaceCreated(SurfaceHolder holder) {
        activeSurface = holder.getSurface();
        main.removeCallbacks(attachRetry);
        attachRetry.run();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int t) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        main.removeCallbacks(attachRetry);
        activeSurface = null;
        if (attached) {
            attached = false;
            ReceiverService svc = ReceiverService.instance;
            if (svc != null) svc.detachUi(this);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onStop() {
        main.removeCallbacks(attachRetry);
        if (attached) {
            attached = false;
            ReceiverService svc = ReceiverService.instance;
            if (svc != null) svc.detachUi(this);
        }
        if (!ReceiverService.isAlwaysOnEnabled(this)) {
            stopService(new Intent(this, ReceiverService.class));
        }
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(attachRetry);
        if (attached) {
            attached = false;
            ReceiverService svc = ReceiverService.instance;
            if (svc != null) svc.detachUi(this);
        }
        super.onDestroy();
    }
}
