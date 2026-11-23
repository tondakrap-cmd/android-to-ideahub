using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    /// <summary>Small always-on-top control bar shown while sharing. Excluded from screen capture (Windows 10 2004+).</summary>
    public sealed class MiniBar : Form
    {
        [DllImport("user32.dll")] private static extern bool SetWindowDisplayAffinity(IntPtr hwnd, uint affinity);
        [DllImport("user32.dll")] private static extern bool ReleaseCapture();
        [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hwnd, int msg, IntPtr w, IntPtr l);

        private readonly Label info;
        private readonly Button pauseBtn, muteBtn;

        public MiniBar(Action onPause, Action onMute, Action onStop, Action onOpen)
        {
            SuspendLayout();
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            BackColor = Theme.CardHi;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.5f, false);
            Padding = new Padding(6);
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;

            FlowLayoutPanel row = new FlowLayoutPanel();
            row.AutoSize = true;
            row.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            row.WrapContents = false;
            row.FlowDirection = FlowDirection.LeftToRight;
            row.BackColor = Theme.CardHi;
            row.Margin = new Padding(0);

            info = new Label();
            info.AutoSize = true;
            info.ForeColor = Theme.Accent;
            info.Font = Theme.Font(9.5f, true);
            info.Margin = new Padding(6, 9, 10, 0);
            info.Text = "● Vysílám";
            row.Controls.Add(info);

            pauseBtn = Theme.SecondaryButton("⏸ Pauza", delegate { onPause(); });
            muteBtn = Theme.SecondaryButton("🔇 Zvuk", delegate { onMute(); });
            Button stop = Theme.DangerButton("■ Ukončit", delegate { onStop(); });
            Button open = Theme.SecondaryButton("⤢", delegate { onOpen(); });
            foreach (Button b in new Button[] { pauseBtn, muteBtn, stop, open })
            {
                b.AutoSize = true;
                b.Height = 30;
                b.Margin = new Padding(3, 2, 3, 2);
                row.Controls.Add(b);
            }
            Controls.Add(row);

            MouseEventHandler drag = delegate(object s, MouseEventArgs e)
            {
                if (e.Button != MouseButtons.Left) return;
                ReleaseCapture();
                SendMessage(Handle, 0xA1, new IntPtr(2), IntPtr.Zero); // WM_NCLBUTTONDOWN, HTCAPTION
            };
            MouseDown += drag;
            row.MouseDown += drag;
            info.MouseDown += drag;

            AutoScaleDimensions = new SizeF(96f, 96f);
            AutoScaleMode = AutoScaleMode.Dpi;
            ResumeLayout(false);
            PerformLayout();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { SetWindowDisplayAffinity(Handle, 0x11); } catch { } // WDA_EXCLUDEFROMCAPTURE
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            Rectangle wa = Screen.PrimaryScreen.WorkingArea;
            Location = new Point(wa.Left + (wa.Width - Width) / 2, wa.Top + 8);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            using (Pen p = new Pen(Theme.CardHiStroke, 1f)) e.Graphics.DrawRectangle(p, 0, 0, Width - 1, Height - 1);
        }

        public void UpdateState(string text, bool paused, bool muted)
        {
            info.Text = text;
            pauseBtn.Text = paused ? "▶ Pokračovat" : "⏸ Pauza";
            muteBtn.Text = muted ? "🔊 Zvuk" : "🔇 Ztlumit";
        }
    }
}
