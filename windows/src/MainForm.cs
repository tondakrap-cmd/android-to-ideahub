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
    public sealed class MainForm : Form
    {
        private static readonly Color BgColor = Color.FromArgb(11, 19, 32);
        private static readonly Color CardColor = Color.FromArgb(22, 36, 54);
        private static readonly Color CardStroke = Color.FromArgb(40, 62, 88);
        private static readonly Color CardHiColor = Color.FromArgb(19, 46, 56);
        private static readonly Color CardHiStroke = Color.FromArgb(48, 148, 126);
        private static readonly Color SubPanelColor = Color.FromArgb(14, 24, 38);
        private static readonly Color AccentColor = Color.FromArgb(56, 217, 169);
        private static readonly Color AccentTextColor = Color.FromArgb(7, 28, 28);
        private static readonly Color TextPrimary = Color.FromArgb(242, 247, 252);
        private static readonly Color TextMuted = Color.FromArgb(156, 178, 200);
        private static readonly Color DangerBg = Color.FromArgb(62, 30, 42);
        private static readonly Color DangerStroke = Color.FromArgb(152, 62, 84);
        private static readonly Color DangerText = Color.FromArgb(255, 176, 186);

        private string savedUri;
        private string savedHost;
        private int qualityIndex = 1;
        private bool audioEnabled = true;
        private int audioVolume = 70;
        private int lastNonZeroVolume = 70;
        private List<DiscoveryOffer> discovered = new List<DiscoveryOffer>();
        private TabuleSession activeSession;
        private bool reallyExiting;

        private Label statusPill;
        private Panel savedCard;
        private Label savedHostLabel;
        private Button quickConnectBtn;
        private ListBox boardsList;
        private TextBox pinBox;
        private Button discoverBtn;
        private Button pairPinBtn;
        private Label localNetLabel;
        private ComboBox monitorCombo;
        private ComboBox qualityCombo;
        private CheckBox audioCheck;
        private Label volLabel;
        private TrackBar volTrack;
        private Button muteBtn;
        private Label statusLabel;
        private Button stopBtn;
        private NotifyIcon trayIcon;
        private System.Windows.Forms.Timer uiTimer;

        public MainForm()
        {
            LoadSettings();
            Icon appIcon = CreateAppIcon();
            Icon = appIcon;

            Text = "Tabule Link " + WireConstants.Version + " — Windows vysílač";
            ClientSize = new Size(900, 650);
            MinimumSize = new Size(860, 610);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = BgColor;
            ForeColor = TextPrimary;
            Font = new Font("Segoe UI", 9.5f);
            DoubleBuffered = true;

            BuildUi(appIcon);
            SetupTray(appIcon);

            uiTimer = new System.Windows.Forms.Timer();
            uiTimer.Interval = 1000;
            uiTimer.Tick += delegate { RefreshLiveUi(); };
            uiTimer.Start();

            RefreshSavedUi();
            RefreshMonitors();
            RefreshLocalNet();
            RunDiscoveryAsync();
        }

        private void BuildUi(Icon appIcon)
        {
            // Header
            Panel header = new Panel();
            header.Location = new Point(22, 16);
            header.Size = new Size(856, 56);
            header.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(header);

            PictureBox logo = new PictureBox();
            logo.Image = appIcon.ToBitmap();
            logo.SizeMode = PictureBoxSizeMode.StretchImage;
            logo.Location = new Point(0, 4);
            logo.Size = new Size(46, 46);
            header.Controls.Add(logo);

            Label title = new Label();
            title.Text = "Tabule Link";
            title.Font = new Font("Segoe UI", 18f, FontStyle.Bold);
            title.ForeColor = TextPrimary;
            title.AutoSize = true;
            title.Location = new Point(58, 2);
            header.Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "Šifrované zrcadlení obrazovky a zvuku Windows → Huawei IdeaHub · TLS 1.2";
            subtitle.Font = new Font("Segoe UI", 9.5f);
            subtitle.ForeColor = TextMuted;
            subtitle.AutoSize = true;
            subtitle.Location = new Point(60, 32);
            header.Controls.Add(subtitle);

            statusPill = new Label();
            statusPill.Text = "● Připraveno";
            statusPill.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            statusPill.ForeColor = TextMuted;
            statusPill.BackColor = CardColor;
            statusPill.TextAlign = ContentAlignment.MiddleCenter;
            statusPill.Size = new Size(150, 32);
            statusPill.Location = new Point(header.Width - 150, 12);
            statusPill.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            header.Controls.Add(statusPill);

            // LEFT COLUMN: Connection & Discovery
            Panel leftCol = new Panel();
            leftCol.Location = new Point(22, 82);
            leftCol.Size = new Size(418, 430);
            leftCol.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left;
            Controls.Add(leftCol);

            // Saved board card
            savedCard = MakeCard(true);
            savedCard.Location = new Point(0, 0);
            savedCard.Size = new Size(418, 108);
            leftCol.Controls.Add(savedCard);

            savedCard.Controls.Add(MakeSectionLabel("ULOŽENÁ TABULE · 1 KLIKNUTÍ BEZ PINU", 16, 12));
            savedHostLabel = MakeMutedLabel("", 16, 32, 386, 20);
            savedCard.Controls.Add(savedHostLabel);

            quickConnectBtn = MakeAccentButton("⚡ Připojit k uložené tabuli", 16, 58, 268, 38, delegate { ConnectSaved(); });
            savedCard.Controls.Add(quickConnectBtn);

            Button forgetBtn = MakeSecondaryButton("Zapomenout", 292, 58, 110, 38, delegate { ForgetSaved(); });
            savedCard.Controls.Add(forgetBtn);

            // LAN Discovery + PIN card
            Panel lanCard = MakeCard(false);
            lanCard.Location = new Point(0, 120);
            lanCard.Size = new Size(418, 214);
            leftCol.Controls.Add(lanCard);

            lanCard.Controls.Add(MakeSectionLabel("TABULE V MÍSTNÍ SÍTI (LAN) + 6MÍSTNÝ PIN", 16, 12));

            discoverBtn = MakeSecondaryButton("Hledat tabule", 292, 8, 110, 28, delegate { RunDiscoveryAsync(); });
            discoverBtn.Font = new Font("Segoe UI", 8.5f);
            lanCard.Controls.Add(discoverBtn);

            boardsList = new ListBox();
            boardsList.Location = new Point(16, 42);
            boardsList.Size = new Size(386, 62);
            boardsList.BackColor = SubPanelColor;
            boardsList.ForeColor = TextPrimary;
            boardsList.BorderStyle = BorderStyle.FixedSingle;
            boardsList.Font = new Font("Segoe UI", 9.5f);
            lanCard.Controls.Add(boardsList);

            Label pinLbl = MakeMutedLabel("PIN z tabule:", 16, 117, 92, 24);
            lanCard.Controls.Add(pinLbl);

            pinBox = new TextBox();
            pinBox.Location = new Point(110, 113);
            pinBox.Size = new Size(118, 30);
            pinBox.BackColor = SubPanelColor;
            pinBox.ForeColor = AccentColor;
            pinBox.Font = new Font("Consolas", 13f, FontStyle.Bold);
            pinBox.MaxLength = 8;
            pinBox.BorderStyle = BorderStyle.FixedSingle;
            lanCard.Controls.Add(pinBox);

            pairPinBtn = MakeAccentButton("Spárovat a vysílat", 236, 111, 166, 34, delegate { ConnectWithPin(); });
            lanCard.Controls.Add(pairPinBtn);

            Button manualBtn = MakeSecondaryButton("Vložit párovací kód ručně (tabulelink://…)", 16, 160, 386, 38, delegate { ConnectManualDialog(); });
            lanCard.Controls.Add(manualBtn);

            // Local network info card
            Panel netCard = MakeCard(false);
            netCard.Location = new Point(0, 346);
            netCard.Size = new Size(418, 84);
            leftCol.Controls.Add(netCard);

            netCard.Controls.Add(MakeSectionLabel("SÍŤ TOHOTO NOTEBOOKU", 16, 10));
            Button refreshNetBtn = MakeSecondaryButton("Obnovit", 326, 6, 76, 24, delegate { RefreshLocalNet(); });
            refreshNetBtn.Font = new Font("Segoe UI", 8f);
            netCard.Controls.Add(refreshNetBtn);

            localNetLabel = MakeMutedLabel("Zjišťuji…", 16, 32, 386, 44);
            localNetLabel.ForeColor = TextPrimary;
            netCard.Controls.Add(localNetLabel);

            // RIGHT COLUMN: Display, Quality, Audio, Volume
            Panel rightCol = new Panel();
            rightCol.Location = new Point(456, 82);
            rightCol.Size = new Size(422, 430);
            rightCol.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(rightCol);

            // Video card
            Panel videoCard = MakeCard(false);
            videoCard.Location = new Point(0, 0);
            videoCard.Size = new Size(422, 184);
            videoCard.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            rightCol.Controls.Add(videoCard);

            videoCard.Controls.Add(MakeSectionLabel("NASTAVENÍ OBRAZU", 16, 12));

            videoCard.Controls.Add(MakeMutedLabel("Sdílený monitor:", 16, 38, 140, 22));
            Button refreshMonBtn = MakeSecondaryButton("Obnovit", 330, 34, 76, 24, delegate { RefreshMonitors(); });
            refreshMonBtn.Font = new Font("Segoe UI", 8f);
            videoCard.Controls.Add(refreshMonBtn);

            monitorCombo = new ComboBox();
            monitorCombo.DropDownStyle = ComboBoxStyle.DropDownList;
            monitorCombo.Location = new Point(16, 64);
            monitorCombo.Size = new Size(390, 28);
            monitorCombo.BackColor = SubPanelColor;
            monitorCombo.ForeColor = TextPrimary;
            monitorCombo.FlatStyle = FlatStyle.Flat;
            videoCard.Controls.Add(monitorCombo);

            videoCard.Controls.Add(MakeMutedLabel("Režim kvality a odezvy:", 16, 102, 240, 22));
            qualityCombo = new ComboBox();
            qualityCombo.DropDownStyle = ComboBoxStyle.DropDownList;
            qualityCombo.Location = new Point(16, 128);
            qualityCombo.Size = new Size(390, 28);
            qualityCombo.BackColor = SubPanelColor;
            qualityCombo.ForeColor = TextPrimary;
            qualityCombo.FlatStyle = FlatStyle.Flat;
            for (int i = 0; i < QualityProfile.All.Length; i++) qualityCombo.Items.Add(QualityProfile.All[i].Label);
            qualityCombo.SelectedIndex = qualityIndex;
            qualityCombo.SelectedIndexChanged += delegate
            {
                qualityIndex = qualityCombo.SelectedIndex;
                SaveSettings();
            };
            videoCard.Controls.Add(qualityCombo);

            // Audio card
            Panel audioCard = MakeCard(false);
            audioCard.Location = new Point(0, 198);
            audioCard.Size = new Size(422, 232);
            audioCard.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            rightCol.Controls.Add(audioCard);

            audioCard.Controls.Add(MakeSectionLabel("ZVUK SYSTÉMU WINDOWS (WASAPI LOOPBACK)", 16, 12));

            audioCheck = new CheckBox();
            audioCheck.Text = "Přenášet systémový zvuk Windows na tabuli (AAC stereo)";
            audioCheck.ForeColor = TextPrimary;
            audioCheck.Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            audioCheck.Location = new Point(16, 40);
            audioCheck.Size = new Size(390, 24);
            audioCheck.Checked = audioEnabled;
            audioCheck.CheckedChanged += delegate
            {
                audioEnabled = audioCheck.Checked;
                UpdateAudioControlsState();
                SaveSettings();
            };
            audioCard.Controls.Add(audioCheck);

            audioCard.Controls.Add(MakeMutedLabel("Přenáší zvuk z prohlížeče, YouTube a prezentací bez Stereo Mixu.", 16, 66, 390, 22));

            volLabel = new Label();
            volLabel.Text = "Hlasitost zvuku na tabuli: " + audioVolume + " %";
            volLabel.ForeColor = AccentColor;
            volLabel.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
            volLabel.Location = new Point(16, 102);
            volLabel.Size = new Size(270, 24);
            audioCard.Controls.Add(volLabel);

            muteBtn = MakeSecondaryButton(audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit", 296, 98, 110, 30, delegate { ToggleMute(); });
            muteBtn.Font = new Font("Segoe UI", 8.5f);
            audioCard.Controls.Add(muteBtn);

            volTrack = new TrackBar();
            volTrack.Minimum = 0;
            volTrack.Maximum = 100;
            volTrack.TickFrequency = 10;
            volTrack.Value = audioVolume;
            volTrack.Location = new Point(12, 134);
            volTrack.Size = new Size(398, 45);
            volTrack.BackColor = CardColor;
            volTrack.Scroll += delegate
            {
                audioVolume = AudioUtil.ClampPercent(volTrack.Value);
                if (audioVolume > 0) lastNonZeroVolume = audioVolume;
                volLabel.Text = "Hlasitost zvuku na tabuli: " + audioVolume + " %";
                muteBtn.Text = audioVolume == 0 ? "Obnovit zvuk" : "Ztlumit";
                SaveSettings();
            };
            audioCard.Controls.Add(volTrack);

            audioCard.Controls.Add(MakeMutedLabel("Hlasitost lze měnit živě za běhu vysílání, aniž by se měnila hlasitost\nvideokonference na tabuli. FFmpeg: "
                + (TabuleSession.FindFfmpeg() != null ? "nalezen ✓" : "CHYBÍ!"), 16, 182, 390, 40));
            UpdateAudioControlsState();

            // BOTTOM STATUS & WINDOW CONTROL BAR
            Panel bottomCard = MakeCard(false);
            bottomCard.Location = new Point(22, 524);
            bottomCard.Size = new Size(856, 108);
            bottomCard.Anchor = AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(bottomCard);

            bottomCard.Controls.Add(MakeSectionLabel("STAV PŘENOSU A OVLÁDÁNÍ", 16, 10));

            statusLabel = new Label();
            statusLabel.Text = "Připraveno k připojení. Na tabuli otevřete Tabule Link → Přijímat obraz a zvuk.";
            statusLabel.ForeColor = AccentColor;
            statusLabel.Font = new Font("Segoe UI", 9.5f);
            statusLabel.Location = new Point(16, 28);
            statusLabel.Size = new Size(824, 24);
            statusLabel.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            bottomCard.Controls.Add(statusLabel);

            stopBtn = MakeDangerButton("Ukončit vysílání", 16, 58, 196, 38, delegate { StopStreaming("Vysílání ukončeno."); });
            bottomCard.Controls.Add(stopBtn);

            Button hideBtn = MakeSecondaryButton("Uklidit do lišty", 224, 58, 196, 38, delegate { HideToTray(); });
            bottomCard.Controls.Add(hideBtn);

            Button diagBtn = MakeSecondaryButton("Zkopírovat diagnostiku", 432, 58, 196, 38, delegate { CopyDiagnostics(); });
            bottomCard.Controls.Add(diagBtn);

            Button exitBtn = MakeSecondaryButton("Ukončit aplikaci", 640, 58, 200, 38, delegate { ExitApplication(); });
            bottomCard.Controls.Add(exitBtn);
        }

        private void SetupTray(Icon appIcon)
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Zobrazit Tabule Link", null, delegate { RestoreFromTray(); });
            menu.Items.Add("Ukončit vysílání", null, delegate { StopStreaming("Vysílání ukončeno."); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Ukončit aplikaci", null, delegate { ExitApplication(); });

            trayIcon = new NotifyIcon();
            trayIcon.Icon = appIcon;
            trayIcon.Text = "Tabule Link " + WireConstants.Version;
            trayIcon.ContextMenuStrip = menu;
            trayIcon.DoubleClick += delegate { RestoreFromTray(); };
            trayIcon.Visible = false;
        }

        private void HideToTray()
        {
            trayIcon.Visible = true;
            Hide();
            trayIcon.ShowBalloonTip(2000, "Tabule Link",
                activeSession != null && activeSession.IsRunning
                    ? "Vysílání na tabuli pokračuje na pozadí. Dvojklikem ikonu otevřete."
                    : "Aplikace byla uklizena do oznamovací oblasti.",
                ToolTipIcon.Info);
        }

        private void RestoreFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
            trayIcon.Visible = false;
        }

        private void ExitApplication()
        {
            reallyExiting = true;
            StopStreaming("Aplikace ukončena.");
            if (trayIcon != null) { trayIcon.Visible = false; trayIcon.Dispose(); }
            Close();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!reallyExiting && activeSession != null && activeSession.IsRunning && e.CloseReason == CloseReason.UserClosing)
            {
                DialogResult res = MessageBox.Show(this,
                    "Vysílání na tabuli právě běží.\r\nChcete vysílání ukončit a zavřít aplikaci?",
                    "Tabule Link", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (res != DialogResult.Yes)
                {
                    e.Cancel = true;
                    return;
                }
            }
            StopStreaming("Aplikace ukončena.");
            if (trayIcon != null) { trayIcon.Visible = false; trayIcon.Dispose(); }
            base.OnFormClosing(e);
        }

        private void UpdateAudioControlsState()
        {
            volTrack.Enabled = audioEnabled;
            muteBtn.Enabled = audioEnabled;
            volLabel.ForeColor = audioEnabled ? AccentColor : TextMuted;
        }

        private void ToggleMute()
        {
            int next = audioVolume == 0 ? Math.Max(20, lastNonZeroVolume) : 0;
            audioVolume = next;
            volTrack.Value = next;
            volLabel.Text = "Hlasitost zvuku na tabuli: " + next + " %";
            muteBtn.Text = next == 0 ? "Obnovit zvuk" : "Ztlumit";
            SaveSettings();
        }

        private void RefreshMonitors()
        {
            int prev = monitorCombo.SelectedIndex;
            monitorCombo.Items.Clear();
            Screen[] all = Screen.AllScreens;
            for (int i = 0; i < all.Length; i++)
            {
                Screen s = all[i];
                string lbl = "Monitor " + (i + 1) + " — " + s.Bounds.Width + " × " + s.Bounds.Height + (s.Primary ? " (hlavní)" : "");
                monitorCombo.Items.Add(lbl);
            }
            if (monitorCombo.Items.Count > 0)
            {
                monitorCombo.SelectedIndex = (prev >= 0 && prev < monitorCombo.Items.Count) ? prev : 0;
            }
        }

        private void RefreshLocalNet()
        {
            List<LocalEndpoint> eps = LocalNet.GetEndpoints();
            if (eps.Count == 0)
            {
                localNetLabel.Text = "Žádná aktivní IPv4 síťová adresa.";
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.Min(3, eps.Count); i++)
            {
                if (i > 0) sb.Append("\r\n");
                sb.Append("• ").Append(eps[i].Label);
            }
            localNetLabel.Text = sb.ToString();
        }

        private void RefreshSavedUi()
        {
            if (!string.IsNullOrEmpty(savedUri) && !string.IsNullOrEmpty(savedHost))
            {
                savedHostLabel.Text = "Poslední spárovaná tabule: " + savedHost;
                quickConnectBtn.Text = "⚡ Připojit (" + savedHost + ")";
                quickConnectBtn.Enabled = true;
            }
            else
            {
                savedHostLabel.Text = "Zatím není uložena žádná tabule — spárujte ji PINem nebo kódem níže.";
                quickConnectBtn.Text = "⚡ Žádná uložená tabule";
                quickConnectBtn.Enabled = false;
            }
        }

        private void RunDiscoveryAsync()
        {
            discoverBtn.Enabled = false;
            discoverBtn.Text = "Hledám…";
            boardsList.Items.Clear();
            boardsList.Items.Add("Hledám tabule v místní síti…");
            Thread t = new Thread(delegate()
            {
                List<DiscoveryOffer> list = DiscoveryOffer.DiscoverInLan(1400);
                InvokeSafe(delegate
                {
                    discovered = list;
                    boardsList.Items.Clear();
                    if (list.Count == 0)
                    {
                        boardsList.Items.Add("Žádná tabule nenalezena (ověřte otevřený příjem v0.5.0+, nebo vložte kód ručně).");
                    }
                    else
                    {
                        foreach (DiscoveryOffer o in list) boardsList.Items.Add(o.DisplayLabel);
                        boardsList.SelectedIndex = 0;
                    }
                    discoverBtn.Enabled = true;
                    discoverBtn.Text = "Hledat tabule";
                });
            });
            t.IsBackground = true;
            t.Start();
        }

        private void ConnectSaved()
        {
            if (string.IsNullOrEmpty(savedUri)) return;
            try
            {
                PairCode pair = new PairCode(savedUri);
                StartStreaming(pair.Host, pair.Port, pair.Fingerprint, pair.Secret, null);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "Tabule Link", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void ForgetSaved()
        {
            savedUri = null;
            savedHost = null;
            SaveSettings();
            RefreshSavedUi();
        }

        private void ConnectWithPin()
        {
            if (discovered.Count == 0 || boardsList.SelectedIndex < 0 || boardsList.SelectedIndex >= discovered.Count)
            {
                MessageBox.Show(this, "Nejprve vyberte nalezenou tabuli v seznamu (nebo klikněte na „Hledat tabule“).\r\nPokud má tabule starší verzi než 0.5.0, použijte „Vložit párovací kód ručně“.",
                    "Tabule Link", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            string pin = DiscoveryOffer.NormalizePin(pinBox.Text);
            if (pin == null)
            {
                MessageBox.Show(this, "Zadejte 6místný číselný PIN zobrazený na obrazovce tabule.",
                    "Tabule Link", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                pinBox.Focus();
                return;
            }
            DiscoveryOffer offer = discovered[boardsList.SelectedIndex];
            StartStreaming(offer.Ip, offer.Port, offer.Fingerprint, null, pin);
        }

        private void ConnectManualDialog()
        {
            Form dlg = new Form();
            dlg.Text = "Vložit párovací kód z tabule";
            dlg.ClientSize = new Size(520, 165);
            dlg.StartPosition = FormStartPosition.CenterParent;
            dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
            dlg.MaximizeBox = false;
            dlg.MinimizeBox = false;
            dlg.BackColor = BgColor;
            dlg.ForeColor = TextPrimary;

            Label lbl = MakeMutedLabel("Vložte kód tabulelink://… z tlačítka „Ruční kód“ na tabuli (uloží se pro příští 1-klik připojení):", 16, 14, 488, 34);
            dlg.Controls.Add(lbl);

            TextBox tb = new TextBox();
            tb.Location = new Point(16, 52);
            tb.Size = new Size(488, 52);
            tb.Multiline = true;
            tb.BackColor = SubPanelColor;
            tb.ForeColor = TextPrimary;
            tb.Font = new Font("Consolas", 9.5f);
            try
            {
                string clip = Clipboard.GetText();
                if (!string.IsNullOrEmpty(clip) && clip.Trim().StartsWith("tabulelink://", StringComparison.Ordinal)) tb.Text = clip.Trim();
            }
            catch { }
            dlg.Controls.Add(tb);

            Button ok = MakeAccentButton("Připojit a uložit", 268, 114, 134, 36, delegate { dlg.DialogResult = DialogResult.OK; dlg.Close(); });
            Button cancel = MakeSecondaryButton("Zrušit", 410, 114, 94, 36, delegate { dlg.DialogResult = DialogResult.Cancel; dlg.Close(); });
            dlg.Controls.Add(ok);
            dlg.Controls.Add(cancel);
            dlg.AcceptButton = ok;
            dlg.CancelButton = cancel;

            if (dlg.ShowDialog(this) == DialogResult.OK)
            {
                try
                {
                    PairCode pair = new PairCode(tb.Text);
                    SavePairedUri(tb.Text.Trim());
                    StartStreaming(pair.Host, pair.Port, pair.Fingerprint, pair.Secret, null);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Neplatný kód", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private void StartStreaming(string host, int port, string fp, string secret, string pin6)
        {
            if (activeSession != null && activeSession.IsRunning)
            {
                MessageBox.Show(this, "Nejprve ukončete současné vysílání.", "Tabule Link", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            SessionConfig cfg = new SessionConfig();
            cfg.Host = host;
            cfg.Port = port;
            cfg.Fingerprint = fp;
            cfg.Secret = secret;
            cfg.Pin6 = pin6;
            cfg.ScreenIndex = Math.Max(0, monitorCombo.SelectedIndex);
            cfg.Quality = QualityProfile.All[Math.Max(0, Math.Min(QualityProfile.All.Length - 1, qualityIndex))];
            cfg.AudioEnabled = audioEnabled;
            cfg.GetVolumePercent = delegate() { return audioVolume; };

            activeSession = new TabuleSession(
                cfg,
                delegate(string msg) { InvokeSafe(delegate { statusLabel.Text = msg; }); },
                delegate(string uri) { InvokeSafe(delegate { SavePairedUri(uri); pinBox.Text = ""; }); },
                delegate { InvokeSafe(delegate { RefreshLiveUi(); }); }
            );
            activeSession.Start();
            RefreshLiveUi();
        }

        private void StopStreaming(string reason)
        {
            TabuleSession s = activeSession;
            if (s != null)
            {
                s.Stop();
            }
            if (!IsDisposed) statusLabel.Text = reason;
            RefreshLiveUi();
        }

        private void RefreshLiveUi()
        {
            bool live = activeSession != null && activeSession.IsRunning;
            if (live)
            {
                statusPill.Text = "● Vysílám živě";
                statusPill.BackColor = CardHiColor;
                statusPill.ForeColor = AccentColor;
                trayIcon.Text = "Tabule Link — vysílám (" + activeSession.DiagFps + " fps)";
            }
            else
            {
                statusPill.Text = "● Připraveno";
                statusPill.BackColor = CardColor;
                statusPill.ForeColor = TextMuted;
                trayIcon.Text = "Tabule Link " + WireConstants.Version;
            }
        }

        private void CopyDiagnostics()
        {
            StringBuilder sb = new StringBuilder();
            sb.AppendLine("Windows " + Environment.OSVersion.VersionString + " · Tabule Link " + WireConstants.Version);
            sb.AppendLine("FFmpeg: " + (TabuleSession.FindFfmpeg() ?? "NENALEZEN"));
            sb.AppendLine("Síť notebooku: " + localNetLabel.Text.Replace("\r\n", ", "));
            if (activeSession != null) sb.AppendLine(activeSession.BuildDiagnostics());
            else sb.AppendLine("Stav: " + statusLabel.Text);
            try
            {
                Clipboard.SetText(sb.ToString());
                statusLabel.Text = "Diagnostika zkopírována do schránky.";
            }
            catch { }
        }

        private void SavePairedUri(string uri)
        {
            try
            {
                PairCode p = new PairCode(uri);
                savedUri = uri;
                savedHost = p.Host + ":" + p.Port.ToString(CultureInfo.InvariantCulture);
                SaveSettings();
                RefreshSavedUi();
            }
            catch { }
        }

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
                    if (k == "saved_uri" && v.Length > 0)
                    {
                        try { PairCode p = new PairCode(v); savedUri = v; savedHost = p.Host + ":" + p.Port; } catch { }
                    }
                    else if (k == "quality")
                    {
                        int q;
                        if (int.TryParse(v, out q) && q >= 0 && q < QualityProfile.All.Length) qualityIndex = q;
                    }
                    else if (k == "audio")
                    {
                        audioEnabled = (v == "1" || v.Equals("true", StringComparison.OrdinalIgnoreCase));
                    }
                    else if (k == "volume")
                    {
                        int vol;
                        if (int.TryParse(v, out vol))
                        {
                            audioVolume = AudioUtil.ClampPercent(vol);
                            if (audioVolume > 0) lastNonZeroVolume = audioVolume;
                        }
                    }
                }
            }
            catch { }
        }

        private void SaveSettings()
        {
            try
            {
                StringBuilder sb = new StringBuilder();
                if (!string.IsNullOrEmpty(savedUri)) sb.AppendLine("saved_uri=" + savedUri);
                sb.AppendLine("quality=" + qualityIndex.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("audio=" + (audioEnabled ? "1" : "0"));
                sb.AppendLine("volume=" + audioVolume.ToString(CultureInfo.InvariantCulture));
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

        private Panel MakeCard(bool highlight)
        {
            Panel p = new Panel();
            p.BackColor = highlight ? CardHiColor : CardColor;
            Color stroke = highlight ? CardHiStroke : CardStroke;
            p.Paint += delegate(object sender, PaintEventArgs e)
            {
                using (Pen pen = new Pen(stroke, 1f))
                {
                    e.Graphics.DrawRectangle(pen, 0, 0, p.Width - 1, p.Height - 1);
                }
            };
            return p;
        }

        private static Label MakeSectionLabel(string text, int x, int y)
        {
            Label l = new Label();
            l.Text = text;
            l.ForeColor = AccentColor;
            l.Font = new Font("Segoe UI", 8f, FontStyle.Bold);
            l.AutoSize = true;
            l.Location = new Point(x, y);
            return l;
        }

        private static Label MakeMutedLabel(string text, int x, int y, int w, int h)
        {
            Label l = new Label();
            l.Text = text;
            l.ForeColor = TextMuted;
            l.Font = new Font("Segoe UI", 9f);
            l.Location = new Point(x, y);
            l.Size = new Size(w, h);
            return l;
        }

        private static Button MakeStyledButton(string text, int x, int y, int w, int h, Color bg, Color border, Color fg, bool bold, EventHandler onClick)
        {
            Button b = new Button();
            b.Text = text;
            b.Location = new Point(x, y);
            b.Size = new Size(w, h);
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = border == Color.Transparent ? 0 : 1;
            b.FlatAppearance.BorderColor = border;
            b.BackColor = bg;
            b.ForeColor = fg;
            b.Font = new Font("Segoe UI", 9.5f, bold ? FontStyle.Bold : FontStyle.Regular);
            b.Cursor = Cursors.Hand;
            b.Click += onClick;
            return b;
        }

        private static Button MakeAccentButton(string text, int x, int y, int w, int h, EventHandler onClick)
        {
            return MakeStyledButton(text, x, y, w, h, AccentColor, Color.Transparent, AccentTextColor, true, onClick);
        }

        private static Button MakeSecondaryButton(string text, int x, int y, int w, int h, EventHandler onClick)
        {
            return MakeStyledButton(text, x, y, w, h, Color.FromArgb(28, 46, 68), CardStroke, TextPrimary, false, onClick);
        }

        private static Button MakeDangerButton(string text, int x, int y, int w, int h, EventHandler onClick)
        {
            return MakeStyledButton(text, x, y, w, h, DangerBg, DangerStroke, DangerText, true, onClick);
        }

        private static Icon CreateAppIcon()
        {
            using (Bitmap bmp = new Bitmap(48, 48))
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.FromArgb(14, 27, 43));
                using (Pen border = new Pen(AccentColor, 3f))
                {
                    g.DrawRectangle(border, 6, 9, 36, 23);
                }
                using (Brush stand = new SolidBrush(AccentColor))
                {
                    g.FillRectangle(stand, 20, 33, 8, 5);
                    g.FillRectangle(stand, 14, 38, 20, 3);
                }
                using (Brush play = new SolidBrush(Color.White))
                {
                    g.FillPolygon(play, new Point[] { new Point(21, 15), new Point(31, 20), new Point(21, 26) });
                }
                IntPtr hIcon = bmp.GetHicon();
                return (Icon)Icon.FromHandle(hIcon).Clone();
            }
        }
    }
}
