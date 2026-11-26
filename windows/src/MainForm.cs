using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    /// <summary>
    /// Main window: responsive layout (TableLayoutPanel + per-monitor DPI), one list of saved and discovered boards,
    /// "what to share" settings, live status bar, tray icon, mini bar and global hotkeys.
    /// </summary>
    public sealed class MainForm : Form
    {
        private const int HotkeyStop = 1, HotkeyPause = 2, HotkeyMute = 3;
        private const int VirtualScreenSetting = -2; // config.ini "screen=-2" = share as a new virtual monitor

        private sealed class BoardItem
        {
            public BoardEntry Saved;
            public DiscoveryOffer Offer;
            public bool Online;
            public string Fingerprint { get { return Saved != null ? Saved.Fingerprint : Offer.Fingerprint; } }
            public string Name { get { return Saved != null ? Saved.Name : Offer.Name; } }
            public string Address { get { return Saved != null ? Saved.Address : Offer.Ip + ":" + Offer.Port.ToString(CultureInfo.InvariantCulture); } }
            public bool Paired { get { return Saved != null && Saved.HasAnyKey; } }
        }

        private sealed class SourceItem
        {
            public int Screen = -1;
            public bool Virtual;
            public Native.WindowInfo Window;
            public string Label;
            public override string ToString() { return Label; }
        }

        // settings
        private readonly BoardStore store = new BoardStore();
        private string customFfmpegPath;
        private int qualityIndex = 1;
        private string encoderId = "auto";
        private bool audioEnabled = true;
        private int audioVolume = 70, lastNonZeroVolume = 70;
        private bool muteSpeakers = false;
        private bool allowTouch = false;
        private bool autoUpdateCheck = true;
        private long lastUpdateCheckTicks = 0;
        private string deviceName = Environment.MachineName;
        private int lastScreen = 0;

        private readonly List<BoardItem> items = new List<BoardItem>();
        private List<DiscoveryOffer> lastOffers = new List<DiscoveryOffer>();
        private volatile bool discovering;
        private TabuleSession activeSession;
        private bool reallyExiting;
        private bool busy;
        private bool refreshingSources;
        private volatile bool installingFfmpeg;
        private volatile bool installingDriver;

        // controls
        private Label statusPill, statusLabel, hotkeyHint, ffmpegLabel;
        private Panel ffmpegBanner;
        private Panel updateBanner;
        private Label updateLabel;
        private UpdateRelease pendingUpdate;
        private volatile bool updating;
        private CheckBox autoUpdateCheckBox;
        private ListBox boardsList;
        private Button shareBtn, discoverBtn, codeBtn, renameBtn, removeBtn, pairBtn;
        private Panel pinPanel;
        private TextBox pinBox, nameBox;
        private ComboBox sourceCombo, qualityCombo, encoderCombo;
        private CheckBox audioCheck, muteSpeakersCheck, touchCheck;
        private Label volLabel;
        private TrackBar volTrack;
        private Button muteBtn, pauseBtn, stopBtn, miniBtn;
        private NotifyIcon trayIcon;
        private System.Windows.Forms.Timer uiTimer;
        private MiniBar miniBar;
        private int discoveryCountdown = 0;

        public MainForm()
        {
            SuspendLayout();
            LoadSettings();
            // a virtual monitor left on the desktop by a crashed session is detached again
            ThreadPool.QueueUserWorkItem(delegate { VirtualMonitor.CleanupIfOurs(); });
            Icon appIcon = CreateAppIcon();
            Icon = appIcon;
            Text = "Tabule Link " + WireConstants.Version + " — Windows";
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.5f, false);
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(1000, 740);
            MinimumSize = new Size(720, 560);
            DoubleBuffered = true;
            KeyPreview = true;

            BuildUi(appIcon);
            SetupTray(appIcon);

            AutoScaleDimensions = new SizeF(96f, 96f);
            AutoScaleMode = AutoScaleMode.Dpi;
            ResumeLayout(false);
            PerformLayout();

            uiTimer = new System.Windows.Forms.Timer();
            uiTimer.Interval = 1000;
            uiTimer.Tick += delegate { OnTick(); };
            uiTimer.Start();

            RefreshFfmpegStatus();
            RefreshSources();
            RebuildBoardItems();
            RunDiscoveryAsync();
            Shown += delegate { ShowStartupNotice(); MaybeAutoCheckForUpdates(); };
        }

        // ------------------------------------------------------------------ layout

        private void BuildUi(Icon appIcon)
        {
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.ColumnCount = 1;
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            root.RowCount = 4;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Padding = new Padding(18, 14, 18, 12);
            root.BackColor = Theme.Bg;
            Controls.Add(root);

            // Header
            TableLayoutPanel header = new TableLayoutPanel();
            header.ColumnCount = 3;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            header.AutoSize = true;
            header.Dock = DockStyle.Fill;
            header.Margin = new Padding(0, 0, 0, 10);
            PictureBox logo = new PictureBox();
            logo.Image = appIcon.ToBitmap();
            logo.SizeMode = PictureBoxSizeMode.Zoom;
            logo.Size = new Size(44, 44);
            logo.Margin = new Padding(0, 0, 12, 0);
            header.Controls.Add(logo, 0, 0);
            TableLayoutPanel titleCol = Theme.Column();
            Label title = Theme.TextLabel("Tabule Link", 18f, Theme.Text);
            title.Font = Theme.Font(18f, true);
            title.Margin = new Padding(0);
            titleCol.Controls.Add(title);
            titleCol.Controls.Add(Theme.TextLabel("Šifrované sdílení obrazovky a zvuku na tabuli (Huawei IdeaHub)", 9.5f, Theme.Muted));
            header.Controls.Add(titleCol, 1, 0);
            statusPill = new Label();
            statusPill.AutoSize = true;
            statusPill.Font = Theme.Font(9.5f, true);
            statusPill.Padding = new Padding(12, 6, 12, 6);
            statusPill.Anchor = AnchorStyles.Right;
            statusPill.Text = "● Připraveno";
            statusPill.BackColor = Theme.Card;
            statusPill.ForeColor = Theme.Muted;
            header.Controls.Add(statusPill, 2, 0);
            root.Controls.Add(header, 0, 0);

            // FFmpeg banner (only when missing)
            ffmpegBanner = new Panel();
            ffmpegBanner.Dock = DockStyle.Fill;
            ffmpegBanner.AutoSize = true;
            ffmpegBanner.BackColor = Theme.WarnBg;
            ffmpegBanner.Padding = new Padding(12, 8, 12, 0);
            ffmpegBanner.Margin = new Padding(0, 0, 0, 10);
            ffmpegBanner.Paint += delegate(object s, PaintEventArgs e)
            {
                using (Pen pen = new Pen(Theme.WarnStroke, 1f)) e.Graphics.DrawRectangle(pen, 0, 0, ffmpegBanner.Width - 1, ffmpegBanner.Height - 1);
            };
            FlowLayoutPanel bannerRow = Theme.Row();
            ffmpegLabel = Theme.TextLabel("⚠ Chybí FFmpeg (potřebný pro obraz i zvuk).", 9.5f, Theme.Text);
            ffmpegLabel.Margin = new Padding(0, 8, 12, 8);
            bannerRow.Controls.Add(ffmpegLabel);
            bannerRow.Controls.Add(Theme.AccentButton("Nainstalovat FFmpeg", delegate { InstallFfmpegViaWinget(); }));
            bannerRow.Controls.Add(Theme.SecondaryButton("Vybrat ffmpeg.exe…", delegate { BrowseFfmpeg(); }));
            ffmpegBanner.Controls.Add(bannerRow);

            // Update banner (only when a newer version was found)
            updateBanner = new Panel();
            updateBanner.Dock = DockStyle.Fill;
            updateBanner.AutoSize = true;
            updateBanner.BackColor = Theme.CardHi;
            updateBanner.Padding = new Padding(12, 8, 12, 0);
            updateBanner.Margin = new Padding(0, 0, 0, 10);
            updateBanner.Visible = false;
            updateBanner.Paint += delegate(object s, PaintEventArgs e)
            {
                using (Pen pen = new Pen(Theme.CardHiStroke, 1f)) e.Graphics.DrawRectangle(pen, 0, 0, updateBanner.Width - 1, updateBanner.Height - 1);
            };
            FlowLayoutPanel updRow = Theme.Row();
            updateLabel = Theme.TextLabel("", 9.5f, Theme.Text);
            updateLabel.Margin = new Padding(0, 8, 12, 8);
            updRow.Controls.Add(updateLabel);
            updRow.Controls.Add(Theme.AccentButton("Aktualizovat", delegate { if (pendingUpdate != null) OfferUpdate(pendingUpdate); }));
            updRow.Controls.Add(Theme.SecondaryButton("Později", delegate { updateBanner.Visible = false; }));
            updateBanner.Controls.Add(updRow);

            TableLayoutPanel banners = Theme.Column();
            banners.Dock = DockStyle.Fill;
            banners.Controls.Add(ffmpegBanner);
            banners.Controls.Add(updateBanner);
            root.Controls.Add(banners, 0, 1);

            // Body: two columns
            TableLayoutPanel body = new TableLayoutPanel();
            body.Dock = DockStyle.Fill;
            body.ColumnCount = 2;
            body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 54f));
            body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 46f));
            body.RowCount = 1;
            body.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            body.Margin = new Padding(0);
            root.Controls.Add(body, 0, 2);

            // Left: boards
            Panel leftCard;
            TableLayoutPanel left = Theme.MakeCard(body, "TABULE · ULOŽENÉ A NALEZENÉ V SÍTI", false, out leftCard);
            leftCard.Margin = new Padding(0, 0, 10, 0);
            left.RowStyles.Clear();
            boardsList = new ListBox();
            boardsList.DrawMode = DrawMode.OwnerDrawFixed;
            boardsList.ItemHeight = 46;
            boardsList.BackColor = Theme.SubPanel;
            boardsList.ForeColor = Theme.Text;
            boardsList.BorderStyle = BorderStyle.None;
            boardsList.Dock = DockStyle.Fill;
            boardsList.IntegralHeight = false;
            boardsList.Height = 220;
            boardsList.Margin = new Padding(0, 0, 0, 10);
            boardsList.DrawItem += DrawBoardItem;
            boardsList.SelectedIndexChanged += delegate { UpdateBoardButtons(); };
            boardsList.DoubleClick += delegate { ShareSelected(); };
            left.Controls.Add(boardsList);
            left.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            left.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

            pinPanel = new Panel();
            pinPanel.AutoSize = true;
            pinPanel.Dock = DockStyle.Fill;
            pinPanel.Margin = new Padding(0, 0, 0, 6);
            FlowLayoutPanel pinRow = Theme.Row();
            Label pinLbl = Theme.TextLabel("PIN z tabule:", 9.5f, Theme.Muted);
            pinLbl.Margin = new Padding(0, 8, 8, 0);
            pinRow.Controls.Add(pinLbl);
            pinBox = new TextBox();
            pinBox.Font = new Font("Consolas", 14f, FontStyle.Bold);
            pinBox.Width = 120;
            pinBox.MaxLength = 8;
            pinBox.BackColor = Theme.SubPanel;
            pinBox.ForeColor = Theme.Accent;
            pinBox.BorderStyle = BorderStyle.FixedSingle;
            pinBox.Margin = new Padding(0, 2, 8, 8);
            pinBox.KeyDown += delegate(object s, KeyEventArgs e) { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; ShareSelected(); } };
            pinRow.Controls.Add(pinBox);
            pairBtn = Theme.AccentButton("Spárovat a sdílet", delegate { ShareSelected(); });
            pinRow.Controls.Add(pairBtn);
            pinPanel.Controls.Add(pinRow);
            left.Controls.Add(pinPanel);

            FlowLayoutPanel boardBtns = Theme.Row();
            shareBtn = Theme.AccentButton("⚡ Sdílet na vybranou tabuli", delegate { ShareSelected(); });
            shareBtn.Font = Theme.Font(10.5f, true);
            shareBtn.MinimumSize = new Size(0, 40);
            boardBtns.Controls.Add(shareBtn);
            discoverBtn = Theme.SecondaryButton("Hledat v síti", delegate { RunDiscoveryAsync(); });
            boardBtns.Controls.Add(discoverBtn);
            codeBtn = Theme.SecondaryButton("Vložit kód…", delegate { ConnectManualDialog(); });
            boardBtns.Controls.Add(codeBtn);
            renameBtn = Theme.SecondaryButton("Přejmenovat", delegate { RenameSelected(); });
            boardBtns.Controls.Add(renameBtn);
            removeBtn = Theme.SecondaryButton("Odebrat", delegate { RemoveSelected(); });
            boardBtns.Controls.Add(removeBtn);
            left.Controls.Add(boardBtns);
            body.Controls.Add(leftCard, 0, 0);

            // Right: what to share
            Panel rightCard;
            TableLayoutPanel right = Theme.MakeCard(body, "CO SDÍLET A JAK", false, true, out rightCard);
            right.Controls.Add(Theme.TextLabel("Zdroj obrazu:", 9f, Theme.Muted));
            TableLayoutPanel srcRow = new TableLayoutPanel();
            srcRow.ColumnCount = 2;
            srcRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            srcRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            srcRow.AutoSize = true;
            srcRow.Dock = DockStyle.Fill;
            srcRow.Margin = new Padding(0);
            sourceCombo = Theme.Combo();
            sourceCombo.SelectedIndexChanged += delegate { OnSourceSelectionChanged(); };
            srcRow.Controls.Add(sourceCombo, 0, 0);
            Button refreshSrc = Theme.SecondaryButton("↻", delegate { RefreshSources(); });
            refreshSrc.Margin = new Padding(6, 0, 0, 8);
            srcRow.Controls.Add(refreshSrc, 1, 0);
            right.Controls.Add(srcRow);

            right.Controls.Add(Theme.TextLabel("Kvalita a odezva:", 9f, Theme.Muted));
            qualityCombo = Theme.Combo();
            for (int i = 0; i < QualityProfile.All.Length; i++) qualityCombo.Items.Add(QualityProfile.All[i].Label);
            qualityCombo.SelectedIndex = qualityIndex;
            qualityCombo.SelectedIndexChanged += delegate { qualityIndex = qualityCombo.SelectedIndex; SaveSettings(); };
            right.Controls.Add(qualityCombo);

            right.Controls.Add(Theme.TextLabel("Kodér videa:", 9f, Theme.Muted));
            encoderCombo = Theme.Combo();
            int encSel = 0;
            for (int i = 0; i < Encoders.Ids.Length; i++)
            {
                encoderCombo.Items.Add(Encoders.Labels[i]);
                if (Encoders.Ids[i] == encoderId) encSel = i;
            }
            encoderCombo.SelectedIndex = encSel;
            encoderCombo.SelectedIndexChanged += delegate { encoderId = Encoders.Ids[Math.Max(0, encoderCombo.SelectedIndex)]; SaveSettings(); };
            right.Controls.Add(encoderCombo);

            audioCheck = Theme.Check("Přenášet zvuk Windows na tabuli (YouTube, video, prezentace)", audioEnabled, delegate
            {
                audioEnabled = audioCheck.Checked;
                UpdateAudioControls();
                SaveSettings();
            });
            audioCheck.Font = Theme.Font(9.5f, true);
            right.Controls.Add(audioCheck);

            TableLayoutPanel volRow = new TableLayoutPanel();
            volRow.ColumnCount = 2;
            volRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            volRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            volRow.AutoSize = true;
            volRow.Dock = DockStyle.Fill;
            volRow.Margin = new Padding(0);
            volLabel = Theme.TextLabel("", 9.5f, Theme.Accent);
            volLabel.Anchor = AnchorStyles.Left;
            volRow.Controls.Add(volLabel, 0, 0);
            muteBtn = Theme.SecondaryButton("Ztlumit", delegate { ToggleMute(); });
            volRow.Controls.Add(muteBtn, 1, 0);
            right.Controls.Add(volRow);
            volTrack = new TrackBar();
            volTrack.Minimum = 0;
            volTrack.Maximum = 100;
            volTrack.TickFrequency = 10;
            volTrack.Value = audioVolume;
            volTrack.Dock = DockStyle.Fill;
            volTrack.BackColor = Theme.Card;
            volTrack.Margin = new Padding(0, 0, 0, 4);
            volTrack.Scroll += delegate { SetVolume(volTrack.Value); };
            volTrack.MouseUp += delegate { SaveSettings(); };
            right.Controls.Add(volTrack);

            muteSpeakersCheck = Theme.Check("Ztlumit reproduktory notebooku během sdílení", muteSpeakers, delegate { muteSpeakers = muteSpeakersCheck.Checked; SaveSettings(); });
            right.Controls.Add(muteSpeakersCheck);
            right.Controls.Add(Theme.TextLabel("    (pokud by pak tabule nehrála, volbu vypněte — záleží na ovladači zvuku)", 8f, Theme.Muted));
            touchCheck = Theme.Check("Povolit ovládání notebooku dotykem z tabule", allowTouch, delegate { allowTouch = touchCheck.Checked; SaveSettings(); });
            right.Controls.Add(touchCheck);
            autoUpdateCheckBox = Theme.Check("Automaticky kontrolovat aktualizace (1× denně)", autoUpdateCheck, delegate { autoUpdateCheck = autoUpdateCheckBox.Checked; SaveSettings(); });
            right.Controls.Add(autoUpdateCheckBox);

            TableLayoutPanel nameRow = new TableLayoutPanel();
            nameRow.ColumnCount = 2;
            nameRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            nameRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            nameRow.AutoSize = true;
            nameRow.Dock = DockStyle.Fill;
            nameRow.Margin = new Padding(0, 6, 0, 0);
            Label nl = Theme.TextLabel("Jméno na tabuli:", 9f, Theme.Muted);
            nl.Anchor = AnchorStyles.Left;
            nameRow.Controls.Add(nl, 0, 0);
            nameBox = new TextBox();
            nameBox.Text = deviceName;
            nameBox.Dock = DockStyle.Fill;
            nameBox.BackColor = Theme.SubPanel;
            nameBox.ForeColor = Theme.Text;
            nameBox.BorderStyle = BorderStyle.FixedSingle;
            nameBox.Leave += delegate { deviceName = Proto2.DeviceName(nameBox.Text); nameBox.Text = deviceName; SaveSettings(); };
            nameRow.Controls.Add(nameBox, 1, 0);
            right.Controls.Add(nameRow);
            body.Controls.Add(rightCard, 1, 0);

            // Bottom status bar
            Panel bottomCard;
            TableLayoutPanel bottom = Theme.MakeCard(root, "STAV PŘENOSU", false, out bottomCard);
            bottomCard.AutoSize = true;
            bottomCard.Margin = new Padding(0, 10, 0, 0);
            statusLabel = Theme.TextLabel("Vyberte tabuli a klikněte na „Sdílet“. Na tabuli musí běžet Tabule Link (příjem).", 9.5f, Theme.Accent);
            statusLabel.AutoSize = true;
            statusLabel.MaximumSize = new Size(1600, 0);
            bottom.Controls.Add(statusLabel);
            FlowLayoutPanel actions = Theme.Row();
            pauseBtn = Theme.SecondaryButton("⏸ Pozastavit obraz", delegate { TogglePause(); });
            actions.Controls.Add(pauseBtn);
            stopBtn = Theme.DangerButton("■ Ukončit vysílání", delegate { StopStreaming(); });
            actions.Controls.Add(stopBtn);
            miniBtn = Theme.SecondaryButton("Mini lišta", delegate { ShowMiniBar(true); });
            actions.Controls.Add(miniBtn);
            actions.Controls.Add(Theme.SecondaryButton("Zkopírovat diagnostiku", delegate { CopyDiagnostics(); }));
            actions.Controls.Add(Theme.SecondaryButton("Aktualizace…", delegate { CheckForUpdates(true); }));
            actions.Controls.Add(Theme.SecondaryButton("Uklidit do lišty", delegate { HideToTray(); }));
            bottom.Controls.Add(actions);
            hotkeyHint = Theme.TextLabel("Zkratky: Ctrl+Alt+S ukončit · Ctrl+Alt+P pozastavit · Ctrl+Alt+M ztlumit zvuk", 8.5f, Theme.Muted);
            bottom.Controls.Add(hotkeyHint);
            root.Controls.Add(bottomCard, 0, 3);

            SetVolume(audioVolume);
            UpdateAudioControls();
        }

        private void DrawBoardItem(object sender, DrawItemEventArgs e)
        {
            if (e.Index < 0 || e.Index >= items.Count) return;
            BoardItem it = items[e.Index];
            bool sel = (e.State & DrawItemState.Selected) != 0;
            using (Brush bg = new SolidBrush(sel ? Theme.CardHi : Theme.SubPanel)) e.Graphics.FillRectangle(bg, e.Bounds);
            if (sel) using (Pen p = new Pen(Theme.CardHiStroke)) e.Graphics.DrawRectangle(p, e.Bounds.X, e.Bounds.Y, e.Bounds.Width - 1, e.Bounds.Height - 1);
            float scale = e.Graphics.DpiY / 96f;
            int pad = (int)(10 * scale);
            Color dot = it.Online ? Theme.Accent : Theme.Muted;
            using (Brush b = new SolidBrush(dot)) e.Graphics.FillEllipse(b, e.Bounds.X + pad, e.Bounds.Y + e.Bounds.Height / 2 - 5 * scale, 10 * scale, 10 * scale);
            using (Font nameFont = Theme.Font(10.5f, true))
            using (Font subFont = Theme.Font(8.5f, false))
            using (Brush tb = new SolidBrush(Theme.Text))
            using (Brush mb = new SolidBrush(Theme.Muted))
            {
                float x = e.Bounds.X + pad * 2 + 10 * scale;
                e.Graphics.DrawString(it.Name, nameFont, tb, x, e.Bounds.Y + 4 * scale);
                string sub = it.Address + " · " + (it.Paired ? (it.Saved.HasV2Key ? "spárováno" : "spárováno (tabule 0.6)") : "nespárováno — zadejte PIN")
                    + " · " + (it.Online ? "v síti" : "nenalezena");
                e.Graphics.DrawString(sub, subFont, mb, x, e.Bounds.Y + 24 * scale);
            }
        }

        // ------------------------------------------------------------------ boards

        private void RebuildBoardItems()
        {
            string selFp = SelectedItem() != null ? SelectedItem().Fingerprint : null;
            items.Clear();
            Dictionary<string, DiscoveryOffer> online = new Dictionary<string, DiscoveryOffer>();
            foreach (DiscoveryOffer o in lastOffers) online[o.Fingerprint] = o;
            bool changed = false;
            foreach (BoardEntry b in store.List())
            {
                BoardItem it = new BoardItem();
                it.Saved = b;
                DiscoveryOffer o;
                if (online.TryGetValue(b.Fingerprint, out o))
                {
                    it.Online = true;
                    if (o.Ip != b.Host || o.Port != b.Port)
                    {
                        b.Host = o.Ip;
                        b.Port = o.Port;
                        store.Upsert(b);
                        changed = true;
                    }
                    online.Remove(b.Fingerprint);
                }
                items.Add(it);
            }
            foreach (DiscoveryOffer o in lastOffers)
            {
                if (!online.ContainsKey(o.Fingerprint)) continue;
                BoardItem it = new BoardItem();
                it.Offer = o;
                it.Online = true;
                items.Add(it);
            }
            if (changed) SaveSettings();
            boardsList.BeginUpdate();
            boardsList.Items.Clear();
            foreach (BoardItem it in items) boardsList.Items.Add(it.Name);
            boardsList.EndUpdate();
            int idx = 0;
            for (int i = 0; i < items.Count; i++) if (items[i].Fingerprint == selFp) idx = i;
            if (items.Count > 0) boardsList.SelectedIndex = idx;
            UpdateBoardButtons();
        }

        private BoardItem SelectedItem()
        {
            int i = boardsList == null ? -1 : boardsList.SelectedIndex;
            return i >= 0 && i < items.Count ? items[i] : null;
        }

        private void UpdateBoardButtons()
        {
            BoardItem it = SelectedItem();
            bool live = activeSession != null && activeSession.IsRunning;
            bool needsPin = it != null && !it.Paired;
            pinPanel.Visible = needsPin && !live;
            shareBtn.Visible = !needsPin;
            shareBtn.Enabled = it != null && !live && !busy;
            pairBtn.Enabled = !busy;
            renameBtn.Enabled = it != null && it.Saved != null;
            removeBtn.Enabled = it != null && it.Saved != null;
            codeBtn.Enabled = !live && !busy;
            if (items.Count == 0)
            {
                statusLabel.Text = discovering ? "Hledám tabule v síti…" : "Žádná tabule. Na tabuli otevřete Tabule Link, nebo vložte kód z tabule (⚙ Správa → Ruční kód).";
            }
        }

        private void RunDiscoveryAsync()
        {
            if (discovering) return;
            discovering = true;
            discoverBtn.Enabled = false;
            discoverBtn.Text = "Hledám…";
            Thread t = new Thread(delegate()
            {
                List<DiscoveryOffer> list = DiscoveryOffer.DiscoverInLan(1800);
                InvokeSafe(delegate
                {
                    lastOffers = list;
                    discovering = false;
                    discoverBtn.Enabled = true;
                    discoverBtn.Text = "Hledat v síti";
                    RebuildBoardItems();
                });
            });
            t.IsBackground = true;
            t.Start();
        }

        private void RenameSelected()
        {
            BoardItem it = SelectedItem();
            if (it == null || it.Saved == null) return;
            string name = Prompt("Přejmenovat tabuli", "Název tabule:", it.Saved.Name);
            if (name == null) return;
            BoardEntry b = it.Saved.Clone();
            b.Name = Proto2.Sanitize(name, 64, b.Name);
            store.Upsert(b);
            SaveSettings();
            RebuildBoardItems();
        }

        private void RemoveSelected()
        {
            BoardItem it = SelectedItem();
            if (it == null || it.Saved == null) return;
            if (MessageBox.Show(this, "Odebrat „" + it.Saved.Name + "“? Pro další sdílení bude nutné tabuli znovu spárovat.", "Tabule Link",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            store.Remove(it.Saved.Fingerprint);
            SaveSettings();
            RebuildBoardItems();
        }

        private void ShareSelected()
        {
            if (activeSession != null && activeSession.IsRunning) { ShowInfo("Nejprve ukončete současné vysílání."); return; }
            BoardItem it = SelectedItem();
            if (it == null) { ShowInfo("Nejprve vyberte tabuli v seznamu (nebo klikněte na „Hledat v síti“)."); return; }
            if (it.Paired) { StartStreaming(it.Saved); return; }
            string pin = DiscoveryOffer.NormalizePin(pinBox.Text);
            if (pin == null) { ShowInfo("Zadejte 6místný PIN zobrazený na tabuli."); pinBox.Focus(); return; }
            DiscoveryOffer offer = it.Offer;
            string name = deviceName;
            RunBusy("Páruji s tabulí " + offer.Name + "…", delegate { return TabuleSession.PairWithPin(offer, pin, name); });
        }

        private void RunBusy(string message, Func<BoardEntry> job)
        {
            busy = true;
            statusLabel.Text = message;
            UpdateBoardButtons();
            Thread t = new Thread(delegate()
            {
                BoardEntry result = null;
                Exception error = null;
                try { result = job(); } catch (Exception ex) { error = ex; }
                InvokeSafe(delegate
                {
                    busy = false;
                    if (error != null)
                    {
                        statusLabel.Text = "Párování se nezdařilo: " + error.Message;
                        MessageBox.Show(this, error.Message, "Párování se nezdařilo", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        UpdateBoardButtons();
                        return;
                    }
                    pinBox.Text = "";
                    store.Upsert(result);
                    SaveSettings();
                    RebuildBoardItems();
                    statusLabel.Text = "Spárováno: " + result.Name;
                    StartStreaming(result);
                });
            });
            t.IsBackground = true;
            t.Start();
        }

        private void ConnectManualDialog()
        {
            string initial = "";
            try
            {
                string clip = Clipboard.GetText();
                if (!string.IsNullOrEmpty(clip) && clip.Trim().StartsWith("tabulelink://", StringComparison.Ordinal)) initial = clip.Trim();
            }
            catch { }
            string text = Prompt("Vložit párovací kód", "Kód tabulelink://… z tabule (⚙ Správa → Ruční kód):", initial);
            if (text == null) return;
            PairCode code;
            try { code = new PairCode(text); }
            catch (Exception ex) { ShowInfo(ex.Message); return; }
            string name = deviceName;
            RunBusy("Páruji s tabulí " + code.Host + "…", delegate { return TabuleSession.PairWithCode(code, name); });
        }

        // ------------------------------------------------------------------ sources

        private void RefreshSources()
        {
            refreshingSources = true;
            try
            {
                object prev = sourceCombo.SelectedItem;
                sourceCombo.Items.Clear();
                Screen[] all = Screen.AllScreens;
                for (int i = 0; i < all.Length; i++)
                {
                    SourceItem s = new SourceItem();
                    s.Screen = i;
                    s.Label = "🖥 Monitor " + (i + 1) + " — " + all[i].Bounds.Width + " × " + all[i].Bounds.Height + (all[i].Primary ? " (hlavní)" : "");
                    sourceCombo.Items.Add(s);
                }
                SourceItem v = new SourceItem();
                v.Virtual = true;
                v.Label = "➕ Vysílat jako nový monitor (rozšíření " + DisplayMath.VirtualWidth + " × " + DisplayMath.VirtualHeight + ")";
                int virtualIndex = sourceCombo.Items.Add(v);
                foreach (Native.WindowInfo w in Native.ListWindows(Handle))
                {
                    SourceItem s = new SourceItem();
                    s.Window = w;
                    s.Label = "🗔 Okno: " + w.Label;
                    sourceCombo.Items.Add(s);
                }
                int sel = lastScreen == VirtualScreenSetting ? virtualIndex : Math.Min(Math.Max(0, lastScreen), all.Length - 1);
                SourceItem p = prev as SourceItem;
                if (p != null)
                {
                    for (int i = 0; i < sourceCombo.Items.Count; i++)
                    {
                        SourceItem c = (SourceItem)sourceCombo.Items[i];
                        if (p.Virtual) { if (c.Virtual) sel = i; continue; }
                        if ((p.Window == null && c.Window == null && !c.Virtual && c.Screen == p.Screen) || (p.Window != null && c.Window != null && c.Window.Handle == p.Window.Handle)) sel = i;
                    }
                }
                if (sourceCombo.Items.Count > 0) sourceCombo.SelectedIndex = Math.Max(0, sel);
            }
            finally
            {
                refreshingSources = false;
            }
        }

        private void OnSourceSelectionChanged()
        {
            if (refreshingSources) return;
            SourceItem src = sourceCombo.SelectedItem as SourceItem;
            if (src == null) return;
            if (src.Virtual) lastScreen = VirtualScreenSetting;
            else if (src.Window == null) lastScreen = src.Screen;
            SaveSettings();

            bool idle = (activeSession == null || !activeSession.IsRunning) && !installingDriver && !installingFfmpeg;
            if (idle && src.Virtual)
            {
                QualityProfile q = QualityProfile.All[Math.Max(0, Math.Min(QualityProfile.All.Length - 1, qualityIndex))];
                if (q.MaxSide < DisplayMath.VirtualWidth)
                {
                    statusLabel.Text = "Nový monitor má plochu " + DisplayMath.VirtualWidth + " × " + DisplayMath.VirtualHeight
                        + "; přenos běží podle pole „Kvalita a odezva“ (pro plné 1920p zvolte Ultra).";
                }
            }
        }

        // ------------------------------------------------------------------ streaming

        private void StartStreaming(BoardEntry board)
        {
            if (activeSession != null && activeSession.IsRunning) { ShowInfo("Nejprve ukončete současné vysílání."); return; }
            if (string.IsNullOrEmpty(TabuleSession.FindFfmpeg(customFfmpegPath))) { ShowInfo("Chybí FFmpeg — klikněte nahoře na „Nainstalovat FFmpeg“ nebo „Vybrat ffmpeg.exe“."); return; }
            SourceItem src = sourceCombo.SelectedItem as SourceItem;
            SessionConfig cfg = new SessionConfig();
            cfg.Board = board.Clone();
            cfg.FfmpegPath = customFfmpegPath;
            if (src != null && src.Virtual)
            {
                if (!EnsureVirtualDisplayDriver()) return;
                cfg.VirtualMonitor = true;
                lastScreen = VirtualScreenSetting;
            }
            else if (src != null && src.Window != null)
            {
                cfg.WindowHandle = src.Window.Handle;
                cfg.WindowTitle = Native.WindowTitle(src.Window.Handle);
            }
            else
            {
                cfg.ScreenIndex = src != null ? src.Screen : 0;
                lastScreen = cfg.ScreenIndex;
            }
            cfg.Quality = QualityProfile.All[Math.Max(0, Math.Min(QualityProfile.All.Length - 1, qualityIndex))];
            cfg.Encoder = encoderId;
            cfg.AudioEnabled = audioEnabled;
            cfg.GetVolumePercent = delegate() { return audioVolume; };
            cfg.MuteSpeakers = muteSpeakers && audioEnabled;
            cfg.AllowTouch = allowTouch;
            cfg.DeviceName = deviceName;

            board.LastUsed = DateTime.UtcNow.Ticks;
            store.Upsert(board);
            SaveSettings();

            activeSession = new TabuleSession(cfg,
                delegate(string msg) { InvokeSafe(delegate { statusLabel.Text = msg; }); },
                delegate(BoardEntry updated) { InvokeSafe(delegate { MergeBoardUpdate(updated); }); },
                delegate(string reason, bool error) { InvokeSafe(delegate { OnSessionStopped(reason, error); }); },
                delegate(string notice) { InvokeSafe(delegate { Balloon(notice, ToolTipIcon.Info); }); });
            activeSession.Start();
            RefreshLiveUi();
            if (WindowState == FormWindowState.Minimized || !Visible) ShowMiniBar(false);
        }

        private void MergeBoardUpdate(BoardEntry updated)
        {
            BoardEntry cur = store.Find(updated.Fingerprint);
            if (cur == null) return;
            cur.Host = updated.Host;
            cur.Port = updated.Port;
            cur.Name = updated.Name;
            store.Upsert(cur);
            SaveSettings();
            RebuildBoardItems();
        }

        private void OnSessionStopped(string reason, bool error)
        {
            statusLabel.Text = reason;
            RefreshLiveUi();
            if (miniBar != null) miniBar.Hide();
            if (!Visible || WindowState == FormWindowState.Minimized || error) Balloon(reason, error ? ToolTipIcon.Warning : ToolTipIcon.Info);
        }

        private void StopStreaming()
        {
            TabuleSession s = activeSession;
            if (s != null) s.Stop();
            RefreshLiveUi();
        }

        private void TogglePause()
        {
            TabuleSession s = activeSession;
            if (s == null || !s.IsRunning) return;
            s.SetPaused(!s.IsPaused);
            RefreshLiveUi();
        }

        private void RefreshLiveUi()
        {
            bool live = activeSession != null && activeSession.IsRunning;
            if (live)
            {
                bool p = activeSession.IsPaused, r = activeSession.IsReconnecting;
                statusPill.Text = r ? "● Obnovuji spojení" : (p ? "● Pozastaveno" : "● Vysílám živě");
                statusPill.BackColor = Theme.CardHi;
                statusPill.ForeColor = Theme.Accent;
                trayIcon.Text = Trim63("Tabule Link — vysílám (" + activeSession.DiagFps + " fps)");
                pauseBtn.Text = p ? "▶ Pokračovat" : "⏸ Pozastavit obraz";
                if (miniBar != null && miniBar.Visible)
                    miniBar.UpdateState((r ? "● Obnovuji · " : (p ? "⏸ " : "● ")) + activeSession.BoardName + " · " + activeSession.DiagFps + " fps", p, audioVolume == 0);
            }
            else
            {
                statusPill.Text = "● Připraveno";
                statusPill.BackColor = Theme.Card;
                statusPill.ForeColor = Theme.Muted;
                trayIcon.Text = "Tabule Link " + WireConstants.Version;
                pauseBtn.Text = "⏸ Pozastavit obraz";
            }
            pauseBtn.Enabled = live;
            stopBtn.Enabled = live;
            miniBtn.Enabled = live;
            sourceCombo.Enabled = !live;
            qualityCombo.Enabled = !live;
            encoderCombo.Enabled = !live;
            touchCheck.Enabled = !live;
            muteSpeakersCheck.Enabled = !live;
            UpdateBoardButtons();
        }

        private static string Trim63(string s) { return s.Length > 63 ? s.Substring(0, 63) : s; }

        private void ShowMiniBar(bool fromButton)
        {
            if (activeSession == null || !activeSession.IsRunning) return;
            if (miniBar == null || miniBar.IsDisposed)
            {
                miniBar = new MiniBar(
                    delegate { TogglePause(); },
                    delegate { ToggleMute(); RefreshLiveUi(); },
                    delegate { StopStreaming(); },
                    delegate { RestoreFromTray(); });
            }
            miniBar.Show();
            RefreshLiveUi();
            if (fromButton) WindowState = FormWindowState.Minimized;
        }

        private void OnTick()
        {
            RefreshLiveUi();
            bool live = activeSession != null && activeSession.IsRunning;
            if (!live && !busy && Visible && WindowState != FormWindowState.Minimized)
            {
                if (++discoveryCountdown >= 20) { discoveryCountdown = 0; RunDiscoveryAsync(); }
            }
        }

        // ------------------------------------------------------------------ audio

        private void SetVolume(int v)
        {
            audioVolume = AudioUtil.ClampPercent(v);
            if (audioVolume > 0) lastNonZeroVolume = audioVolume;
            volLabel.Text = "Hlasitost zvuku na tabuli: " + audioVolume + " %";
            muteBtn.Text = audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit";
            if (volTrack.Value != audioVolume) volTrack.Value = audioVolume;
        }

        private void ToggleMute()
        {
            SetVolume(audioVolume == 0 ? Math.Max(20, lastNonZeroVolume) : 0);
            SaveSettings();
        }

        private void UpdateAudioControls()
        {
            volTrack.Enabled = audioEnabled;
            muteBtn.Enabled = audioEnabled;
            muteSpeakersCheck.Enabled = audioEnabled;
            volLabel.ForeColor = audioEnabled ? Theme.Accent : Theme.Muted;
        }

        // ------------------------------------------------------------------ ffmpeg

        private void RefreshFfmpegStatus()
        {
            bool found = !string.IsNullOrEmpty(TabuleSession.FindFfmpeg(customFfmpegPath));
            ffmpegBanner.Visible = !found;
        }

        private void BrowseFfmpeg()
        {
            using (OpenFileDialog ofd = new OpenFileDialog())
            {
                ofd.Title = "Vyberte soubor ffmpeg.exe";
                ofd.Filter = "FFmpeg (ffmpeg.exe)|ffmpeg.exe|Spustitelné soubory (*.exe)|*.exe";
                if (ofd.ShowDialog(this) == DialogResult.OK && File.Exists(ofd.FileName))
                {
                    customFfmpegPath = ofd.FileName;
                    SaveSettings();
                    RefreshFfmpegStatus();
                    statusLabel.Text = "FFmpeg nastaven: " + customFfmpegPath;
                }
            }
        }

        private void InstallFfmpegViaWinget()
        {
            if (installingFfmpeg)
            {
                ShowInfo("Instalace FFmpeg už probíhá. Dokončete prosím otevřené okno terminálu.");
                return;
            }
            try
            {
                installingFfmpeg = true;
                statusLabel.Text = "Spouštím instalaci FFmpeg přes winget… Po dokončení zavřete okno terminálu.";
                System.Diagnostics.ProcessStartInfo psi = new System.Diagnostics.ProcessStartInfo();
                psi.FileName = "cmd.exe";
                psi.Arguments = "/c echo Instaluji FFmpeg pro Tabule Link... & winget install --id Gyan.FFmpeg --accept-source-agreements --accept-package-agreements & echo. & echo Hotovo! Muzete toto okno zavrit. & pause";
                psi.UseShellExecute = true;
                System.Diagnostics.Process p = System.Diagnostics.Process.Start(psi);
                if (p == null) { installingFfmpeg = false; return; }
                Thread t = new Thread(delegate()
                {
                    try
                    {
                        try { p.WaitForExit(); } catch { }
                        InvokeSafe(delegate
                        {
                            RefreshFfmpegStatus();
                            statusLabel.Text = string.IsNullOrEmpty(TabuleSession.FindFfmpeg(customFfmpegPath))
                                ? "Instalace skončila, ale ffmpeg.exe nebyl nalezen. Zkuste jej vybrat ručně."
                                : "FFmpeg byl nainstalován a nalezen ✓";
                        });
                    }
                    finally { installingFfmpeg = false; }
                });
                t.IsBackground = true;
                t.Start();
            }
            catch (Exception ex)
            {
                installingFfmpeg = false;
                MessageBox.Show(this, "Nepodařilo se spustit winget: " + ex.Message + "\r\nStáhněte si ffmpeg.exe ručně a položte jej vedle TabuleLink.exe.",
                    "Instalace FFmpeg", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        // ------------------------------------------------------------------ updates (GitHub Releases)

        private void ShowStartupNotice()
        {
            if (Program.UpdatedTo != null)
            {
                // the status line is soon reused by board discovery, so also show a Windows notification
                statusLabel.Text = "Tabule Link byl aktualizován na verzi " + Program.UpdatedTo + " ✓";
                Balloon("Tabule Link byl aktualizován na verzi " + Program.UpdatedTo + ".", ToolTipIcon.Info);
            }
            if (Program.UpdateFailedPath != null)
            {
                MessageBox.Show(this, "Aktualizaci se nepodařilo dokončit, soubor aplikace byl zřejmě zamčený.\r\n\r\n"
                    + "Ověřená nová verze je uložena zde:\r\n" + Program.UpdateFailedPath + "\r\n\r\nMůžete ji spustit nebo jí ručně nahradit stávající soubor.",
                    "Aktualizace", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void MaybeAutoCheckForUpdates()
        {
            if (!autoUpdateCheck) return;
            if (DateTime.UtcNow.Ticks - lastUpdateCheckTicks < TimeSpan.TicksPerDay) return;
            CheckForUpdates(false);
        }

        /// <summary>manual = user clicked (shows results and errors); otherwise a quiet daily check that only shows the banner.</summary>
        private void CheckForUpdates(bool manual)
        {
            if (updating) { if (manual) ShowInfo("Aktualizace už probíhá."); return; }
            if (manual) statusLabel.Text = "Hledám novou verzi na GitHubu…";
            ThreadPool.QueueUserWorkItem(delegate
            {
                UpdateRelease found = null;
                string error = null;
                try { found = Updater.Check(); }
                catch (Exception ex) { error = ex.Message; }
                InvokeSafe(delegate
                {
                    if (error == null)
                    {
                        lastUpdateCheckTicks = DateTime.UtcNow.Ticks;
                        SaveSettings();
                    }
                    if (found != null)
                    {
                        pendingUpdate = found;
                        updateLabel.Text = "⬆ Je k dispozici Tabule Link " + found.Version + " (máte " + Updater.CurrentVersion + ").";
                        updateBanner.Visible = true;
                        if (manual) { statusLabel.Text = "Nalezena nová verze " + found.Version + "."; OfferUpdate(found); }
                    }
                    else if (manual)
                    {
                        statusLabel.Text = error != null ? "Aktualizace nelze ověřit." : "Máte nejnovější verzi " + Updater.CurrentVersion + " ✓";
                        if (error != null)
                            MessageBox.Show(this, "Nepodařilo se spojit s GitHubem: " + error + "\r\n\r\nZkontrolujte připojení k internetu. Vydání najdete i na " + UpdateCheck.ReleasesPage,
                                "Aktualizace", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        else ShowInfo("Máte nejnovější verzi Tabule Link " + Updater.CurrentVersion + ".");
                    }
                });
            });
        }

        private void OfferUpdate(UpdateRelease u)
        {
            if (updating) return;
            if (activeSession != null && activeSession.IsRunning) { ShowInfo("Nejdřív ukončete vysílání, pak aplikaci aktualizujte."); return; }
            string notes = UpdateCheck.ShortNotes(u.Notes, 1200);
            DialogResult r = MessageBox.Show(this,
                "Je k dispozici Tabule Link " + u.Version + " (máte " + Updater.CurrentVersion + ").\r\n\r\n"
                + (notes.Length > 0 ? notes + "\r\n\r\n" : "")
                + "Aktualizovat teď? Aplikace se stáhne z GitHubu, ověří se její podpis a pak se sama restartuje.",
                "Aktualizace Tabule Link", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r == DialogResult.Yes) ApplyUpdate(u);
        }

        private void ApplyUpdate(UpdateRelease u)
        {
            updating = true;
            statusLabel.Text = "Stahuji Tabule Link " + u.Version + "…";
            ThreadPool.QueueUserWorkItem(delegate
            {
                string exe;
                string error = Updater.DownloadAndVerify(u, delegate(int pct) { InvokeSafe(delegate { statusLabel.Text = "Stahuji Tabule Link " + u.Version + "… " + pct + " %"; }); }, out exe);
                InvokeSafe(delegate
                {
                    try
                    {
                        if (error != null)
                        {
                            statusLabel.Text = "Aktualizace se nepodařila.";
                            MessageBox.Show(this, error, "Aktualizace", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                            return;
                        }
                        if (activeSession != null && activeSession.IsRunning) { ShowInfo("Mezitím začalo vysílání. Aktualizaci spusťte znovu po jeho ukončení."); return; }
                        string current = Application.ExecutablePath;
                        if (!Updater.CanReplaceInPlace(current))
                        {
                            string saved = Updater.SaveToDownloads(exe);
                            statusLabel.Text = "Nová verze je uložena: " + saved;
                            MessageBox.Show(this, "Do složky s aplikací nelze zapisovat bez oprávnění správce:\r\n" + Path.GetDirectoryName(current)
                                + "\r\n\r\nOvěřená nová verze je uložena zde:\r\n" + saved + "\r\n\r\nUkončete Tabule Link a spusťte novou verzi (nebo jí nahraďte stávající soubor).",
                                "Aktualizace", MessageBoxButtons.OK, MessageBoxIcon.Information);
                            try { System.Diagnostics.Process.Start("explorer.exe", "/select,\"" + saved + "\""); } catch { }
                            return;
                        }
                        statusLabel.Text = "Instaluji aktualizaci a restartuji aplikaci…";
                        Updater.StartReplaceAfterExit(exe, current, u.Version);
                        ExitApplication();
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show(this, "Aktualizace se nepodařila: " + ex.Message, "Aktualizace", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    finally { updating = false; }
                });
            });
        }

        // ------------------------------------------------------------------ virtual monitor driver

        /// <summary>True when the Virtual Display Driver is ready; otherwise explains (in Czech) and offers installation.</summary>
        private bool EnsureVirtualDisplayDriver()
        {
            if (installingDriver)
            {
                ShowInfo("Instalace ovladače virtuálního monitoru už probíhá. Dokončete prosím otevřené okno instalace.");
                return false;
            }
            List<string> ids;
            string detail;
            VddState st = VirtualMonitor.Detect(out ids, out detail);
            if (st == VddState.Ready) return true;
            if (st == VddState.Disabled)
            {
                DialogResult r = MessageBox.Show(this,
                    char.ToUpper(detail[0]) + detail.Substring(1) + ".\r\n\r\n"
                    + "Povolte ho ve Správci zařízení: Grafické adaptéry → Virtual Display Driver → pravé tlačítko → Povolit zařízení. "
                    + "Pokud to nepomůže, ovladač přeinstalujte nebo restartujte počítač.\r\n\r\nOtevřít Správce zařízení?",
                    "Vysílat jako nový monitor", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (r == DialogResult.Yes)
                {
                    try { System.Diagnostics.Process.Start("devmgmt.msc"); } catch { }
                }
                return false;
            }
            DialogResult res = MessageBox.Show(this,
                "Pro vysílání jako nový monitor potřebuje Tabule Link bezplatný ovladač virtuálního monitoru "
                + "„Virtual Display Driver“ (open source, licence MIT, " + VirtualMonitor.ProjectUrl + ").\r\n\r\n"
                + "Instaluje se jen jednou přes winget. Windows se při instalaci zeptá na oprávnění správce "
                + "a na důvěru vydavateli ovladače (SignPath Foundation). Samotné vysílání pak oprávnění správce nepotřebuje.\r\n\r\n"
                + "Mimo vysílání je virtuální monitor odpojený, takže okna ani myš na něj nezabloudí.\r\n\r\n"
                + "Nainstalovat ovladač teď?",
                "Vysílat jako nový monitor — chybí ovladač", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
            if (res == DialogResult.Yes) InstallVirtualDisplayDriver();
            return false;
        }

        private void InstallVirtualDisplayDriver()
        {
            if (installingDriver)
            {
                ShowInfo("Instalace ovladače virtuálního monitoru už probíhá. Dokončete prosím otevřené okno instalace.");
                return;
            }
            try
            {
                installingDriver = true;
                System.Diagnostics.Process p = null;
                if (VirtualMonitor.FindWingetPackage() == null)
                {
                    // step 1: winget unpacks the signed driver package (portable, no admin rights needed)
                    statusLabel.Text = "Stahuji ovladač virtuálního monitoru přes winget… Po dokončení zavřete okno terminálu.";
                    System.Diagnostics.ProcessStartInfo psi = new System.Diagnostics.ProcessStartInfo();
                    psi.FileName = "cmd.exe";
                    psi.Arguments = "/c echo Stahuji ovladac virtualniho monitoru pro Tabule Link... & winget install --id " + VirtualMonitor.WingetId
                        + " -e --accept-source-agreements --accept-package-agreements & echo. & echo Hotovo! Zavrete toto okno, Tabule Link pak ovladac nainstaluje. & pause";
                    psi.UseShellExecute = true;
                    p = System.Diagnostics.Process.Start(psi);
                    if (p == null) { installingDriver = false; return; }
                }
                Thread t = new Thread(delegate()
                {
                    try
                    {
                        if (p != null) { try { p.WaitForExit(); } catch { } }
                        List<string> ids;
                        string detail = "";
                        string error = null;
                        // Check first in case a newer winget package already registered and started the driver itself
                        VddState st = VirtualMonitor.Detect(out ids, out detail);
                        if (st != VddState.Ready)
                        {
                            string pkg = VirtualMonitor.FindWingetPackage();
                            if (pkg == null)
                            {
                                error = "winget balíček ovladače nestáhl nebo má neočekávanou strukturu.";
                            }
                            else
                            {
                                // step 2: install the driver from the package (UAC prompt)
                                InvokeSafe(delegate { statusLabel.Text = "Instaluji ovladač virtuálního monitoru… Potvrďte prosím oprávnění správce a důvěru vydavateli ovladače."; });
                                error = VirtualMonitor.InstallDriverElevated(pkg);
                            }
                            for (int i = 0; i < 20; i++)
                            {
                                st = VirtualMonitor.Detect(out ids, out detail);
                                if (st == VddState.Ready || error != null) break;
                                Thread.Sleep(500);
                            }
                        }
                        if (st == VddState.Ready)
                        {
                            // the driver shows its monitor right after installation: keep it detached until sharing starts
                            VirtualMonitor.MarkManaged();
                            VirtualMonitor.CleanupIfOurs();
                        }
                        InvokeSafe(delegate
                        {
                            RefreshSources();
                            if (st == VddState.Ready)
                            {
                                statusLabel.Text = "Ovladač virtuálního monitoru je připraven ✓ Klikněte znovu na vysílání.";
                                return;
                            }
                            statusLabel.Text = "Ovladač virtuálního monitoru není připraven.";
                            MessageBox.Show(this, (error ?? "Instalace skončila, ale ovladač zatím není připraven (" + detail + ").")
                                + "\r\n\r\nZkuste restartovat počítač, nebo ovladač nainstalujte ručně v aplikaci Virtual Driver Control (příkaz „VDD Control“) podle návodu na "
                                + VirtualMonitor.ProjectUrl, "Instalace ovladače", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        });
                    }
                    finally { installingDriver = false; }
                });
                t.IsBackground = true;
                t.SetApartmentState(ApartmentState.STA); // ShellExecute (UAC "runas") expects an STA thread
                t.Start();
            }
            catch (Exception ex)
            {
                installingDriver = false;
                MessageBox.Show(this, "Nepodařilo se spustit winget: " + ex.Message + "\r\nOvladač si můžete nainstalovat ručně podle návodu na " + VirtualMonitor.ProjectUrl,
                    "Instalace ovladače", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        // ------------------------------------------------------------------ tray, hotkeys, window

        private void SetupTray(Icon appIcon)
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Zobrazit Tabule Link", null, delegate { RestoreFromTray(); });
            menu.Items.Add("Pozastavit / pokračovat", null, delegate { TogglePause(); });
            menu.Items.Add("Ukončit vysílání", null, delegate { StopStreaming(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Ukončit aplikaci", null, delegate { ExitApplication(); });
            trayIcon = new NotifyIcon();
            trayIcon.Icon = appIcon;
            trayIcon.Text = "Tabule Link " + WireConstants.Version;
            trayIcon.ContextMenuStrip = menu;
            trayIcon.DoubleClick += delegate { RestoreFromTray(); };
            trayIcon.Visible = true;
        }

        private void Balloon(string text, ToolTipIcon icon)
        {
            try { trayIcon.ShowBalloonTip(3000, "Tabule Link", text.Length > 250 ? text.Substring(0, 250) : text, icon); } catch { }
        }

        private void HideToTray()
        {
            Hide();
            Balloon(activeSession != null && activeSession.IsRunning ? "Vysílání pokračuje na pozadí. Dvojklikem na ikonu okno otevřete." : "Aplikace běží v oznamovací oblasti.", ToolTipIcon.Info);
            if (activeSession != null && activeSession.IsRunning) ShowMiniBar(false);
        }

        private void RestoreFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        private void ExitApplication()
        {
            reallyExiting = true;
            StopStreaming();
            Close();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            uint mods = Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT;
            bool ok = false;
            try
            {
                ok = Native.RegisterHotKey(Handle, HotkeyStop, mods, (uint)Keys.S);
                ok &= Native.RegisterHotKey(Handle, HotkeyPause, mods, (uint)Keys.P);
                ok &= Native.RegisterHotKey(Handle, HotkeyMute, mods, (uint)Keys.M);
            }
            catch { }
            if (!ok && hotkeyHint != null) hotkeyHint.Text += " (některé zkratky už používá jiná aplikace)";
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY)
            {
                int id = m.WParam.ToInt32();
                if (id == HotkeyStop) StopStreaming();
                else if (id == HotkeyPause) TogglePause();
                else if (id == HotkeyMute) { ToggleMute(); RefreshLiveUi(); }
            }
            base.WndProc(ref m);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (WindowState == FormWindowState.Minimized && activeSession != null && activeSession.IsRunning) ShowMiniBar(false);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!reallyExiting && activeSession != null && activeSession.IsRunning && e.CloseReason == CloseReason.UserClosing)
            {
                DialogResult res = MessageBox.Show(this, "Vysílání na tabuli právě běží.\r\nUkončit vysílání a zavřít aplikaci?", "Tabule Link", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (res != DialogResult.Yes) { e.Cancel = true; return; }
            }
            StopStreaming();
            // the session thread is a background thread: let it detach the virtual monitor before the process exits
            if (activeSession != null) activeSession.WaitStopped(4000);
            VirtualMonitor.CleanupIfOurs();
            try
            {
                Native.UnregisterHotKey(Handle, HotkeyStop);
                Native.UnregisterHotKey(Handle, HotkeyPause);
                Native.UnregisterHotKey(Handle, HotkeyMute);
            }
            catch { }
            if (miniBar != null) { try { miniBar.Close(); } catch { } }
            if (trayIcon != null) { trayIcon.Visible = false; trayIcon.Dispose(); }
            base.OnFormClosing(e);
        }

        // ------------------------------------------------------------------ dialogs & helpers

        private void ShowInfo(string text) { MessageBox.Show(this, text, "Tabule Link", MessageBoxButtons.OK, MessageBoxIcon.Information); }

        private string Prompt(string title, string label, string initial)
        {
            using (Form dlg = new Form())
            {
                dlg.Text = title;
                dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
                dlg.StartPosition = FormStartPosition.CenterParent;
                dlg.MaximizeBox = false;
                dlg.MinimizeBox = false;
                dlg.BackColor = Theme.Bg;
                dlg.ForeColor = Theme.Text;
                dlg.Font = Theme.Font(9.5f, false);
                dlg.AutoSize = true;
                dlg.AutoSizeMode = AutoSizeMode.GrowAndShrink;
                dlg.Padding = new Padding(14);
                TableLayoutPanel t = Theme.Column();
                t.Controls.Add(Theme.TextLabel(label, 9.5f, Theme.Muted));
                TextBox tb = new TextBox();
                tb.Text = initial ?? "";
                tb.Width = 520;
                tb.BackColor = Theme.SubPanel;
                tb.ForeColor = Theme.Text;
                tb.Font = new Font("Consolas", 10f);
                tb.Margin = new Padding(0, 0, 0, 10);
                t.Controls.Add(tb);
                FlowLayoutPanel btns = Theme.Row();
                btns.FlowDirection = FlowDirection.RightToLeft;
                Button cancel = Theme.SecondaryButton("Zrušit", delegate { dlg.DialogResult = DialogResult.Cancel; });
                Button ok = Theme.AccentButton("OK", delegate { dlg.DialogResult = DialogResult.OK; });
                btns.Controls.Add(cancel);
                btns.Controls.Add(ok);
                t.Controls.Add(btns);
                dlg.Controls.Add(t);
                dlg.AcceptButton = ok;
                dlg.CancelButton = cancel;
                dlg.AutoScaleDimensions = new SizeF(96f, 96f);
                dlg.AutoScaleMode = AutoScaleMode.Dpi;
                return dlg.ShowDialog(this) == DialogResult.OK ? tb.Text.Trim() : null;
            }
        }

        private void CopyDiagnostics()
        {
            StringBuilder sb = new StringBuilder();
            sb.AppendLine("Windows " + Environment.OSVersion.VersionString + " · Tabule Link " + WireConstants.Version + " · DPI: " + Program.DpiMode);
            sb.AppendLine("FFmpeg: " + (TabuleSession.FindFfmpeg(customFfmpegPath) ?? "NENALEZEN"));
            List<LocalEndpoint> eps = LocalNet.GetEndpoints();
            StringBuilder net = new StringBuilder();
            foreach (LocalEndpoint ep in eps) { if (net.Length > 0) net.Append(", "); net.Append(ep.Label); }
            sb.AppendLine("Síť notebooku: " + net);
            sb.AppendLine("Uložené tabule: " + store.Count + " · nalezené v síti: " + lastOffers.Count);
            if (activeSession != null) sb.AppendLine(activeSession.BuildDiagnostics());
            else sb.AppendLine("Stav: " + statusLabel.Text);
            try
            {
                Clipboard.SetText(sb.ToString());
                statusLabel.Text = "Diagnostika zkopírována do schránky.";
            }
            catch { }
        }

        // ------------------------------------------------------------------ settings

        private static string ConfigFilePath()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TabuleLink");
            Directory.CreateDirectory(dir);
            return Path.Combine(dir, "config.ini");
        }

        private void LoadSettings()
        {
            try
            {
                string path = ConfigFilePath();
                if (!File.Exists(path)) return;
                foreach (string line in File.ReadAllLines(path, Encoding.UTF8))
                {
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    string k = line.Substring(0, eq).Trim();
                    string v = line.Substring(eq + 1).Trim();
                    int n;
                    if (k == "board") store.TryAddLine(v, Native.Unprotect);
                    else if (k == "saved_uri" && v.Length > 0)
                    {
                        // migrate the single 0.6 pairing (shared secret) to the board list
                        try
                        {
                            PairCode p = new PairCode(v);
                            if (store.Find(p.Fingerprint) == null)
                            {
                                BoardEntry b = new BoardEntry();
                                b.Name = "Tabule " + p.Host;
                                b.Fingerprint = p.Fingerprint;
                                b.Host = p.Host;
                                b.Port = p.Port;
                                b.LegacySecret = p.Secret;
                                b.LastUsed = 1;
                                store.Upsert(b);
                            }
                        }
                        catch { }
                    }
                    else if (k == "quality" && int.TryParse(v, out n) && n >= 0 && n < QualityProfile.All.Length) qualityIndex = n;
                    else if (k == "encoder" && Array.IndexOf(Encoders.Ids, v) >= 0) encoderId = v;
                    else if (k == "audio") audioEnabled = v == "1";
                    else if (k == "volume" && int.TryParse(v, out n)) { audioVolume = AudioUtil.ClampPercent(n); if (audioVolume > 0) lastNonZeroVolume = audioVolume; }
                    else if (k == "mute_speakers") muteSpeakers = v == "1";
                    else if (k == "allow_touch") allowTouch = v == "1";
                    else if (k == "auto_update_check") autoUpdateCheck = v != "0";
                    else if (k == "last_update_check") long.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out lastUpdateCheckTicks);
                    else if (k == "device_name" && v.Length > 0) deviceName = Proto2.DeviceName(v);
                    else if (k == "screen" && int.TryParse(v, out n)) lastScreen = n;
                    else if (k == "ffmpeg" && v.Length > 0) customFfmpegPath = v;
                }
            }
            catch { }
        }

        private void SaveSettings()
        {
            try
            {
                StringBuilder sb = new StringBuilder();
                foreach (string line in store.Encode(Native.Protect)) sb.AppendLine("board=" + line);
                sb.AppendLine("quality=" + qualityIndex.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("encoder=" + encoderId);
                sb.AppendLine("audio=" + (audioEnabled ? "1" : "0"));
                sb.AppendLine("volume=" + audioVolume.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("mute_speakers=" + (muteSpeakers ? "1" : "0"));
                sb.AppendLine("allow_touch=" + (allowTouch ? "1" : "0"));
                sb.AppendLine("auto_update_check=" + (autoUpdateCheck ? "1" : "0"));
                sb.AppendLine("last_update_check=" + lastUpdateCheckTicks.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("device_name=" + deviceName);
                sb.AppendLine("screen=" + lastScreen.ToString(CultureInfo.InvariantCulture));
                if (!string.IsNullOrEmpty(customFfmpegPath)) sb.AppendLine("ffmpeg=" + customFfmpegPath);
                File.WriteAllText(ConfigFilePath(), sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        private void InvokeSafe(Action a)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try
            {
                if (InvokeRequired) BeginInvoke(a);
                else a();
            }
            catch { }
        }

        private static Icon CreateAppIcon()
        {
            using (Bitmap bmp = new Bitmap(48, 48))
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.FromArgb(14, 27, 43));
                using (Pen border = new Pen(Theme.Accent, 3f)) g.DrawRectangle(border, 6, 9, 36, 23);
                using (Brush stand = new SolidBrush(Theme.Accent))
                {
                    g.FillRectangle(stand, 20, 33, 8, 5);
                    g.FillRectangle(stand, 14, 38, 20, 3);
                }
                using (Brush play = new SolidBrush(Color.White))
                {
                    g.FillPolygon(play, new Point[] { new Point(21, 15), new Point(31, 20), new Point(21, 26) });
                }
                IntPtr hIcon = bmp.GetHicon();
                Icon clone;
                using (Icon tmp = Icon.FromHandle(hIcon)) clone = (Icon)tmp.Clone();
                try { Native.DestroyIcon(hIcon); } catch { }
                return clone;
            }
        }
    }
}
