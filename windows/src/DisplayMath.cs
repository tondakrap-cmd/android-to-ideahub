using System;
using System.Collections.Generic;
using System.Drawing;

namespace TabuleLinkWin
{
    /// <summary>
    /// Pure geometry for the virtual monitor and touch input (no Win32 calls, covered by Test-Windows.ps1).
    /// All rectangles are in physical desktop pixels (the process is per-monitor DPI aware).
    /// </summary>
    public static class DisplayMath
    {
        /// <summary>Default virtual monitor mode: 16:9 like the board, same as the largest encode size (Ultra 1920 px).</summary>
        public const int VirtualWidth = 1920, VirtualHeight = 1080;

        /// <summary>
        /// Top-left corner for a new monitor placed to the right of the current desktop: next to the monitor with the
        /// rightmost edge (ties: the higher one), top-aligned with it. Never (0,0) when other monitors exist, so the
        /// new monitor can't become the primary one.
        /// </summary>
        public static Point PlaceRightOf(IList<Rectangle> desktop)
        {
            if (desktop == null || desktop.Count == 0) return Point.Empty;
            Rectangle best = desktop[0];
            for (int i = 1; i < desktop.Count; i++)
            {
                Rectangle r = desktop[i];
                if (r.Right > best.Right || (r.Right == best.Right && r.Top < best.Top)) best = r;
            }
            return new Point(best.Right, best.Top);
        }

        /// <summary>
        /// Picks the display mode for the virtual monitor: the exact target if offered, otherwise the largest mode with
        /// the same aspect ratio that fits, otherwise the largest mode that fits. Size.Empty = keep the current mode.
        /// </summary>
        public static Size ChooseMode(IList<Size> modes, int targetW, int targetH)
        {
            if (modes == null || modes.Count == 0 || targetW <= 0 || targetH <= 0) return Size.Empty;
            Size sameAspect = Size.Empty, fits = Size.Empty;
            foreach (Size m in modes)
            {
                if (m.Width == targetW && m.Height == targetH) return m;
                if (m.Width <= 0 || m.Height <= 0 || m.Width > targetW || m.Height > targetH) continue;
                long area = (long)m.Width * m.Height;
                if ((long)m.Width * targetH == (long)m.Height * targetW && area > (long)sameAspect.Width * sameAspect.Height) sameAspect = m;
                if (area > (long)fits.Width * fits.Height) fits = m;
            }
            return sameAspect != Size.Empty ? sameAspect : fits;
        }

        /// <summary>Maps board touch coordinates (0..65535 on both axes) to a desktop pixel inside the shared rectangle.</summary>
        public static Point TouchToDesktop(Rectangle r, int nx, int ny)
        {
            nx = Math.Max(0, Math.Min(65535, nx));
            ny = Math.Max(0, Math.Min(65535, ny));
            int x = r.Left + (int)Math.Round(nx / 65535.0 * (r.Width - 1));
            int y = r.Top + (int)Math.Round(ny / 65535.0 * (r.Height - 1));
            return new Point(x, y);
        }

        // ------------------------------------------------------------ Virtual Display Driver identification

        /// <summary>Hardware IDs of the Virtual Display Driver (VirtualDrivers / MikeTheTech, MttVDD.inf).</summary>
        public static readonly string[] VddHardwareIds = new string[] { @"Root\MttVDD", "MttVDD" };

        public static bool IsVddHardwareId(IEnumerable<string> hardwareIds)
        {
            if (hardwareIds == null) return false;
            foreach (string id in hardwareIds)
            {
                if (id == null) continue;
                foreach (string known in VddHardwareIds)
                    if (string.Equals(id.Trim(), known, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>Splits a REG_MULTI_SZ value (UTF-16, NUL separated) into strings.</summary>
        public static List<string> SplitMultiSz(string raw)
        {
            List<string> list = new List<string>();
            if (string.IsNullOrEmpty(raw)) return list;
            foreach (string s in raw.Split('\0'))
                if (s.Length > 0) list.Add(s);
            return list;
        }

        /// <summary>
        /// True when a DisplayConfig adapter device path (\\?\ROOT#DISPLAY#0000#{guid}) belongs to the device
        /// instance ID from SetupAPI (ROOT\DISPLAY\0000).
        /// </summary>
        public static bool AdapterPathMatchesInstance(string adapterDevicePath, string instanceId)
        {
            if (string.IsNullOrEmpty(adapterDevicePath) || string.IsNullOrEmpty(instanceId)) return false;
            string p = adapterDevicePath;
            if (p.StartsWith(@"\\?\", StringComparison.Ordinal)) p = p.Substring(4);
            string id = instanceId.Replace('\\', '#') + "#";
            return p.StartsWith(id, StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>Fallback: the driver's default EDID reports the monitor as MTT1337.</summary>
        public static bool LooksLikeVddMonitor(string monitorDevicePath)
        {
            return !string.IsNullOrEmpty(monitorDevicePath) && monitorDevicePath.IndexOf("MTT1337", StringComparison.OrdinalIgnoreCase) >= 0;
        }
    }
}
