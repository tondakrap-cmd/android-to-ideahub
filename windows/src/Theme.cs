using System;
using System.Drawing;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    /// <summary>Shared dark theme (same palette as the Android app) and control factories.</summary>
    public static class Theme
    {
        public static readonly Color Bg = Color.FromArgb(11, 19, 32);
        public static readonly Color Card = Color.FromArgb(22, 36, 54);
        public static readonly Color CardStroke = Color.FromArgb(40, 62, 88);
        public static readonly Color CardHi = Color.FromArgb(19, 46, 56);
        public static readonly Color CardHiStroke = Color.FromArgb(48, 148, 126);
        public static readonly Color SubPanel = Color.FromArgb(14, 24, 38);
        public static readonly Color Panel = Color.FromArgb(28, 46, 68);
        public static readonly Color Accent = Color.FromArgb(56, 217, 169);
        public static readonly Color AccentText = Color.FromArgb(7, 28, 28);
        public static readonly Color Text = Color.FromArgb(242, 247, 252);
        public static readonly Color Muted = Color.FromArgb(156, 178, 200);
        public static readonly Color DangerBg = Color.FromArgb(62, 30, 42);
        public static readonly Color DangerStroke = Color.FromArgb(152, 62, 84);
        public static readonly Color DangerText = Color.FromArgb(255, 176, 186);
        public static readonly Color WarnBg = Color.FromArgb(70, 52, 20);
        public static readonly Color WarnStroke = Color.FromArgb(190, 140, 50);

        public static Font Font(float pt, bool bold)
        {
            return new Font("Segoe UI", pt, bold ? FontStyle.Bold : FontStyle.Regular);
        }

        private static Button Styled(string text, Color bg, Color border, Color fg, bool bold, EventHandler click)
        {
            Button b = new Button();
            b.Text = text;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = border == Color.Empty ? 0 : 1;
            if (border != Color.Empty) b.FlatAppearance.BorderColor = border;
            b.BackColor = bg;
            b.ForeColor = fg;
            b.Font = Font(9.5f, bold);
            b.Cursor = Cursors.Hand;
            b.UseVisualStyleBackColor = false;
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            b.Padding = new Padding(10, 4, 10, 4);
            b.MinimumSize = new Size(0, 34);
            b.Margin = new Padding(0, 0, 8, 8);
            if (click != null) b.Click += click;
            return b;
        }

        public static Button AccentButton(string text, EventHandler click) { return Styled(text, Accent, Color.Empty, AccentText, true, click); }
        public static Button SecondaryButton(string text, EventHandler click) { return Styled(text, Panel, CardStroke, Text, false, click); }
        public static Button DangerButton(string text, EventHandler click) { return Styled(text, DangerBg, DangerStroke, DangerText, true, click); }

        public static Label Section(string text)
        {
            Label l = new Label();
            l.Text = text;
            l.ForeColor = Accent;
            l.Font = Font(8f, true);
            l.AutoSize = true;
            l.Margin = new Padding(0, 0, 0, 6);
            return l;
        }

        public static Label TextLabel(string text, float pt, Color color)
        {
            Label l = new Label();
            l.Text = text;
            l.ForeColor = color;
            l.Font = Font(pt, false);
            l.AutoSize = true;
            l.Margin = new Padding(0, 2, 0, 4);
            return l;
        }

        /// <summary>Card: a bordered panel with a single-column auto-sizing table inside.</summary>
        public static TableLayoutPanel MakeCard(Control parentCell, string title, bool highlight, out Panel card)
        {
            return MakeCard(parentCell, title, highlight, false, out card);
        }

        /// <summary>scrollable: content keeps its natural height and the card scrolls when the window is small.</summary>
        public static TableLayoutPanel MakeCard(Control parentCell, string title, bool highlight, bool scrollable, out Panel card)
        {
            Panel p = new Panel();
            p.Dock = DockStyle.Fill;
            p.BackColor = highlight ? CardHi : Card;
            p.Padding = new Padding(14, 12, 14, 8);
            p.Margin = new Padding(0, 0, 0, 12);
            Color stroke = highlight ? CardHiStroke : CardStroke;
            p.Paint += delegate(object s, PaintEventArgs e)
            {
                using (Pen pen = new Pen(stroke, 1f)) e.Graphics.DrawRectangle(pen, 0, 0, p.Width - 1, p.Height - 1);
            };
            TableLayoutPanel t = Column();
            t.Dock = scrollable ? DockStyle.Top : DockStyle.Fill;
            p.AutoScroll = scrollable;
            t.BackColor = p.BackColor;
            if (title != null) t.Controls.Add(Section(title));
            p.Controls.Add(t);
            card = p;
            return t;
        }

        public static TableLayoutPanel Column()
        {
            TableLayoutPanel t = new TableLayoutPanel();
            t.ColumnCount = 1;
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            t.AutoSize = true;
            t.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            t.GrowStyle = TableLayoutPanelGrowStyle.AddRows;
            t.Margin = new Padding(0);
            t.Padding = new Padding(0);
            return t;
        }

        public static FlowLayoutPanel Row()
        {
            FlowLayoutPanel f = new FlowLayoutPanel();
            f.FlowDirection = FlowDirection.LeftToRight;
            f.WrapContents = true;
            f.AutoSize = true;
            f.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            f.Dock = DockStyle.Fill;
            f.Margin = new Padding(0, 2, 0, 2);
            f.Padding = new Padding(0);
            return f;
        }

        public static ComboBox Combo()
        {
            ComboBox c = new ComboBox();
            c.DropDownStyle = ComboBoxStyle.DropDownList;
            c.FlatStyle = FlatStyle.Flat;
            c.BackColor = SubPanel;
            c.ForeColor = Text;
            c.Dock = DockStyle.Fill;
            c.Margin = new Padding(0, 0, 0, 8);
            return c;
        }

        public static CheckBox Check(string text, bool value, EventHandler changed)
        {
            CheckBox c = new CheckBox();
            c.Text = text;
            c.Checked = value;
            c.ForeColor = Text;
            c.AutoSize = true;
            c.Margin = new Padding(0, 2, 0, 4);
            if (changed != null) c.CheckedChanged += changed;
            return c;
        }
    }
}
