package cz.ideamirror.app;

import android.app.*;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;

public class ReceiveActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView video;
    private View pairingOverlay;
    private TextView message, statusPill, address, allInterfacesView, diagView, rxVolLabel;
    private Button switchIpBtn;
    private ImageView qr;
    private volatile boolean running = true;
    private volatile Wire.Server server;
    private volatile SSLSocket client;
    private volatile AudioPlayer currentAudioPlayer;
    private volatile int receiveVolumePercent = 100;
    private Thread worker;
    private String code;
    private FrameLayout root;
    private volatile int videoWidth = 0, videoHeight = 0;
    private List<Wire.Endpoint> currentEndpoints = Collections.emptyList();
    private int selectedIndex = 0;
    private volatile Surface activeSurface;

    private volatile String diagPhase = "spouštění";
    private volatile String diagKeySource = "-";
    private volatile String diagDecoder = "-";
    private volatile String diagAudio = "čekám";
    private volatile String diagLastError = "-";
    private volatile long diagPackets = 0, diagRendered = 0, diagSkipped = 0;
    private volatile int diagFps = 0;
    private volatile String diagEndpoints = "-";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (bt - t) != (ob - ot)) resizeVideo();
        });

        // Modern full-screen pairing overlay
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackground(Ui.screenBg());
        pairingOverlay = sc;

        LinearLayout centerWrap = Ui.column(this);
        centerWrap.setGravity(Gravity.CENTER);
        int outerPad = Ui.dp(this, 24);
        centerWrap.setPadding(outerPad, Ui.dp(this, 20), outerPad, outerPad);
        sc.addView(centerWrap);

        // Main card container
        LinearLayout mainCard = Ui.card(this, false);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        centerWrap.addView(mainCard, cardLp);

        // Top header row inside card
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
        TextView sub = Ui.muted(this, "Připraveno k zrcadlení obrazu a zvuku ze spárovaného tabletu", 14);
        sub.setPadding(0, 0, 0, 0);
        titleCol.addView(sub);
        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        statusPill = Ui.pill(this, "● Čekám na připojení", Ui.CARD_HI, Ui.CARD_HI_STROKE, Ui.ACCENT);
        topBar.addView(statusPill);
        mainCard.addView(topBar, Ui.vSpace(this, 0, 16));

        // Two-column responsive layout: QR code on left, connection controls on right
        boolean wide = getResources().getConfiguration().screenWidthDp >= 640;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        mainCard.addView(body);

        // Left: QR card in crisp white rounded frame
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

        // Right: info, addresses & controls
        LinearLayout infoCol = Ui.column(this);
        message = Ui.text(this, "Připravuji zabezpečené připojení…", 16);
        message.setTextIsSelectable(true);
        infoCol.addView(message, Ui.vSpace(this, 0, 8));

        LinearLayout addrBox = Ui.subpanel(this);
        addrBox.addView(Ui.section(this, "ADRESA TABULE V QR KÓDU"));
        address = Ui.text(this, "Načítám…", 17);
        address.setTextColor(Ui.ACCENT);
        address.setTypeface(null, Typeface.BOLD);
        address.setTextIsSelectable(true);
        addrBox.addView(address);
        allInterfacesView = Ui.muted(this, "", 13);
        allInterfacesView.setTextIsSelectable(true);
        addrBox.addView(allInterfacesView, Ui.vSpace(this, 4, 0));
        infoCol.addView(addrBox, Ui.vSpace(this, 0, 12));

        // Row 1 of buttons
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

        // Row 2 of buttons
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

        // Receiver-side app volume (independent of IdeaHub system volume for videoconferencing)
        android.content.SharedPreferences srvPrefs = getSharedPreferences("tabule_server", MODE_PRIVATE);
        receiveVolumePercent = AudioUtil.clampPercent(srvPrefs.getInt("rx_volume", 100));
        LinearLayout volBox = Ui.subpanel(this);
        rxVolLabel = Ui.text(this, "Hlasitost příjmu na tabuli: " + receiveVolumePercent + " %", 14);
        rxVolLabel.setPadding(0, 0, 0, Ui.dp(this, 4));
        volBox.addView(rxVolLabel);
        SeekBar rxSeek = new SeekBar(this);
        rxSeek.setMax(100);
        rxSeek.setProgress(receiveVolumePercent);
        rxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                receiveVolumePercent = AudioUtil.clampPercent(progress);
                rxVolLabel.setText("Hlasitost příjmu na tabuli: " + receiveVolumePercent + " %");
                AudioPlayer ap = currentAudioPlayer;
                if (ap != null) ap.setVolumePercent(receiveVolumePercent);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                srvPrefs.edit().putInt("rx_volume", receiveVolumePercent).apply();
            }
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

        // Floating subtle close button in top-right (visible over video too)
        Button stop = Ui.dangerButton(this, "✕ Zavřít příjem", this::finish);
        stop.setAlpha(0.88f);
        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        int sm = Ui.dp(this, 14);
        stopLp.setMargins(sm, sm, sm, sm);
        root.addView(stop, stopLp);
        setContentView(root);
    }

    private void confirmResetKey() {
        new AlertDialog.Builder(this)
            .setTitle("Vytvořit nový párovací klíč?")
            .setMessage("Dříve uložená spojení v tabletech přestanou fungovat a bude nutné jednou načíst nový QR kód.")
            .setPositiveButton("Vytvořit nový klíč", (d, w) -> restartServerWithNewKey())
            .setNegativeButton("Zrušit", null)
            .show();
    }

    private void restartServerWithNewKey() {
        Wire.close(client);
        Wire.close(server);
        running = false;
        Thread prev = worker;
        worker = null;
        new Thread(() -> {
            if (prev != null) try { prev.join(1000); } catch (InterruptedException ignored) {}
            if (isFinishing() || isDestroyed()) return;
            running = true;
            Surface s = activeSurface;
            if (s != null && s.isValid()) {
                worker = new Thread(() -> listen(s, true), "tabule-receive");
                worker.start();
            }
        }).start();
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
        diagEndpoints = Wire.formatEndpointsInline(eps);
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
        diagEndpoints = Wire.formatEndpointsInline(eps);
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

    private String diagnosticsSummary() {
        return Ui.info() + "\nTLS: " + diagKeySource + " · Zvuk: " + diagAudio
            + "\nDekodér: " + diagDecoder + " · " + diagFps + " fps · Snímky/přeskočeno: "
            + diagRendered + "/" + diagSkipped;
    }

    private String diagnosticsText() {
        return Ui.info()
            + "\nRole: přijímač (tabule)"
            + "\nSíťová rozhraní: " + diagEndpoints
            + "\nTLS klíč: " + diagKeySource
            + "\nFáze: " + diagPhase
            + "\nDekodér videa: " + diagDecoder
            + "\nZvuk: " + diagAudio
            + "\nRozlišení videa: " + videoWidth + "x" + videoHeight
            + "\nFPS: " + diagFps
            + "\nPřijaté pakety / vykreslené / přeskočené: " + diagPackets + " / " + diagRendered + " / " + diagSkipped
            + "\nPoslední chyba: " + diagLastError;
    }

    private void refreshDiagUi() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && diagView != null) {
                diagView.setText(diagnosticsSummary());
            }
        });
    }

    private void showPairing(String statusText, String pillText) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            pairingOverlay.setVisibility(View.VISIBLE);
            message.setText(statusText);
            if (pillText != null) statusPill.setText(pillText);
            diagView.setText(diagnosticsSummary());
        });
    }

    private void updateCode(Wire.Endpoint ep) {
        try {
            if (server == null) return;
            int port = server.socket.getLocalPort();
            code = Wire.uri(ep.ip, port, server.fp, server.secret);
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
        } catch (Exception e) {
            diagLastError = Ui.problem(e);
            message.setText(diagLastError);
        }
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

    @Override public void surfaceCreated(SurfaceHolder holder) {
        activeSurface = holder.getSurface();
        if (worker != null) return;
        worker = new Thread(() -> listen(activeSurface, false), "tabule-receive");
        worker.start();
    }

    private static String applyVendorLowLatencyKeys(MediaFormat format, String codecName) {
        String low = codecName == null ? "" : codecName.toLowerCase(Locale.ROOT);
        String mfg = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (low.contains("hisi") || low.contains("kirin") || (mfg.contains("huawei") && !low.contains("google"))) {
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
            return "hisi-ll";
        }
        if (low.contains("qti") || low.contains("qcom")) {
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            return "qti-ll";
        }
        if (low.contains("mtk")) {
            format.setInteger("vendor.low-latency.enable", 1);
            return "mtk-ll";
        }
        return "std-ll";
    }

    private MediaCodec createAndConfigureDecoder(Surface surface, int width, int height) throws IOException {
        Exception firstErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            MediaCodec codec = null;
            try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                String mode;
                if (attempt == 0) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    f.setFloat(MediaFormat.KEY_OPERATING_RATE, 60.0f);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
                    if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = applyVendorLowLatencyKeys(f, codec.getName());
                } else if (attempt == 1) {
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                    mode = "basic-ll";
                } else {
                    mode = "default";
                }
                codec.configure(f, surface, null, 0);
                diagDecoder = codec.getName() + " (" + mode + ", " + width + "x" + height + ")";
                return codec;
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
                if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            }
        }
        throw new IOException("Nepodařilo se spustit H.264 dekodér: " + Ui.problem(firstErr), firstErr);
    }

    private void listen(Surface surface, boolean forceNewSecret) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        try {
            diagPhase = "příprava TLS serveru";
            Wire.Server local = new Wire.Server(getApplicationContext(), forceNewSecret);
            server = local;
            diagKeySource = local.keySource;
            if (!running) { local.close(); return; }

            List<Wire.Endpoint> eps = Wire.endpoints(this);
            if (eps.isEmpty()) {
                throw new IOException("Není dostupná síťová adresa. Připojte tabuli k Wi-Fi nebo kabelem a otevřete příjem znovu.");
            }
            diagEndpoints = Wire.formatEndpointsInline(eps);
            diagPhase = "čekám na připojení";

            runOnUiThread(() -> {
                if (!running) return;
                refreshEndpoints(true);
                message.setText(forceNewSecret
                    ? "Byl vygenerován nový párovací klíč. Načtěte nový QR kód tabletem."
                    : "Načtěte QR kód tabletem, nebo na spárovaném tabletu klepněte na Rychle připojit.");
                statusPill.setText("● Čekám na připojení");
                address.setOnClickListener(v -> chooseEndpointDialog());
            });

            while (running) {
                boolean streamStarted = false;
                try {
                    diagPhase = "čekám na připojení";
                    refreshDiagUi();
                    client = (SSLSocket) local.socket.accept();
                    diagPackets = 0;
                    diagRendered = 0;
                    diagSkipped = 0;
                    diagFps = 0;
                    diagAudio = "čekám";
                    diagLastError = "-";
                    diagPhase = "TLS 1.2 handshake";
                    refreshDiagUi();
                    try { client.setReceiveBufferSize(128 * 1024); } catch (Exception ignored) {}
                    client.setSoTimeout(10000);
                    client.setTcpNoDelay(true);
                    client.startHandshake();

                    diagPhase = "ověření párovacího klíče";
                    DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 32 * 1024));
                    String supplied = in.readUTF();
                    if (!MessageDigest.isEqual(local.secret.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
                        throw new IOException("Nesprávný párovací klíč. Načtěte aktuální QR kód.");
                    }
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    out.writeInt(Wire.MAGIC);
                    out.flush();

                    diagPhase = "čtení hlavičky videa";
                    if (in.readInt() != Wire.MAGIC) throw new IOException("Nekompatibilní verze přenosu.");
                    int width = in.readInt(), height = in.readInt();
                    if (width < 16 || height < 16 || width > 2560 || height > 2560 || (long) width * height > 2560L * 1600) {
                        throw new IOException("Nepodporované rozlišení (" + width + "×" + height + ").");
                    }
                    videoWidth = width;
                    videoHeight = height;
                    streamStarted = true;
                    receive(in, surface, width, height);
                } catch (EOFException e) {
                    if (running) {
                        if (streamStarted) {
                            diagPhase = "ukončeno vysílačem";
                            showPairing("Vysílání z tabletu bylo ukončeno.\nMůžete se kdykoli znovu připojit.", "● Připraveno");
                        } else {
                            diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
                            showPairing("Spojení se přerušilo (" + diagPhase + ").\nNa tabletu spusťte vysílání znovu.", "● Odpojeno");
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
                        showPairing("Připojení ukončeno (" + diagPhase + "): " + Ui.problem(e)
                            + "\nNa tabletu spusťte vysílání znovu.", "● Odpojeno");
                    }
                } finally {
                    Wire.close(client);
                    client = null;
                }
            }
        } catch (Exception e) {
            if (running) {
                diagLastError = Ui.problem(e) + " [" + diagPhase + "]";
                showPairing("Chyba příjmu (" + diagPhase + "): " + Ui.problem(e), "● Chyba");
            }
        } finally {
            Wire.close(server);
            server = null;
        }
    }

    private void receive(DataInputStream in, Surface surface, int width, int height) throws Exception {
        diagPhase = "konfigurace H.264 dekodéru";
        MediaCodec codec = createAndConfigureDecoder(surface, width, height);
        AudioPlayer audioPlayer = new AudioPlayer(receiveVolumePercent);
        currentAudioPlayer = audioPlayer;
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean decoding = new AtomicBoolean(true);
        Thread drain = null;
        try {
            codec.start();
            diagPhase = "přijímám obraz";
            refreshDiagUi();
            drain = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean first = true;
                long fpsStart = SystemClock.elapsedRealtime();
                long fpsCount = 0;
                try {
                    while (running && decoding.get()) {
                        int latest = codec.dequeueOutputBuffer(info, 10000);
                        if (latest < 0) continue;
                        while (running && decoding.get()) {
                            int newer = codec.dequeueOutputBuffer(info, 0);
                            if (newer >= 0) {
                                codec.releaseOutputBuffer(latest, false);
                                diagSkipped++;
                                latest = newer;
                            } else {
                                break;
                            }
                        }
                        codec.releaseOutputBuffer(latest, true);
                        diagRendered++;
                        fpsCount++;
                        long now = SystemClock.elapsedRealtime();
                        if (now - fpsStart >= 1000) {
                            diagFps = (int) Math.round(fpsCount * 1000.0 / Math.max(1, now - fpsStart));
                            diagAudio = audioPlayer.status() + " (" + audioPlayer.decodedFrames() + ")";
                            fpsStart = now;
                            fpsCount = 0;
                        }
                        if (first) {
                            first = false;
                            runOnUiThread(() -> {
                                if (running && !isFinishing() && !isDestroyed()) {
                                    resizeVideo();
                                    pairingOverlay.setVisibility(View.GONE);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    if (decoding.get()) {
                        failure.set(e);
                        Wire.close(client);
                    }
                }
            }, "tabule-decode");
            drain.start();

            while (running && failure.get() == null) {
                Packet packet = Packet.read(in);
                int size = packet.data.length;
                if (size == 0) continue;
                if (Packet.isAudio(packet.flags)) {
                    audioPlayer.enqueue(packet);
                    continue;
                }
                diagPackets++;
                int flags = Packet.sanitizeFlags(packet.flags);
                long pts = packet.pts;
                byte[] data = packet.data;
                int index;
                long deadline = SystemClock.elapsedRealtime() + 5000;
                do {
                    index = codec.dequeueInputBuffer(5000);
                    if (!running) return;
                    if (failure.get() != null) throw failure.get();
                    if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Dekodér nereaguje.");
                } while (index < 0);
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new IOException("Dekodér neposkytl vstupní buffer.");
                buffer.clear();
                if (buffer.remaining() < size) {
                    throw new IOException("Dekodér nepodporuje velikost snímku (" + size + " > " + buffer.remaining() + " B).");
                }
                buffer.put(data);
                codec.queueInputBuffer(index, 0, size, pts, flags);
            }
            if (failure.get() != null) throw failure.get();
        } finally {
            decoding.set(false);
            currentAudioPlayer = null;
            diagAudio = audioPlayer.status() + " (" + audioPlayer.decodedFrames() + ")";
            audioPlayer.close();
            if (drain != null) {
                drain.interrupt();
                try { drain.join(1500); } catch (InterruptedException ignored) {}
            }
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
        }
    }

    private void stop() {
        running = false;
        Wire.close(client);
        Wire.close(server);
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int t) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { activeSurface = null; stop(); }
    @Override protected void onStop() { stop(); super.onStop(); if (!isFinishing()) finish(); }
    @Override protected void onDestroy() { stop(); super.onDestroy(); }
}
