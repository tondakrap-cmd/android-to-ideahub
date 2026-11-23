using System;
using System.Collections.Generic;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    /// <summary>Win32 / DXGI / Core Audio helpers (no external libraries).</summary>
    public static class Native
    {
        // ------------------------------------------------------------ DPI awareness

        [DllImport("user32.dll")] private static extern bool SetProcessDpiAwarenessContext(IntPtr value);
        [DllImport("shcore.dll")] private static extern int SetProcessDpiAwareness(int value);
        [DllImport("user32.dll")] private static extern bool SetProcessDPIAware();

        /// <summary>Per-monitor V2 so Screen.Bounds, gdigrab offsets and SendInput use physical pixels on every monitor.</summary>
        public static string EnableDpiAwareness()
        {
            try { if (SetProcessDpiAwarenessContext(new IntPtr(-4))) return "PerMonitorV2"; } catch { }
            try { if (SetProcessDpiAwareness(2) == 0) return "PerMonitor"; } catch { }
            try { SetProcessDPIAware(); return "System"; } catch { }
            return "Unaware";
        }

        // ------------------------------------------------------------ DXGI outputs (ddagrab adapter/output index)

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DXGI_OUTPUT_DESC
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
            public RECT DesktopCoordinates;
            public int AttachedToDesktop;
            public int Rotation;
            public IntPtr Monitor;
        }

        [ComImport, Guid("770aae78-f26f-4dba-a829-253c83d1b387"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IDXGIFactory1
        {
            [PreserveSig] int SetPrivateData(ref Guid name, uint size, IntPtr data);
            [PreserveSig] int SetPrivateDataInterface(ref Guid name, IntPtr unknown);
            [PreserveSig] int GetPrivateData(ref Guid name, ref uint size, IntPtr data);
            [PreserveSig] int GetParent(ref Guid riid, out IntPtr parent);
            [PreserveSig] int EnumAdapters(uint index, out IntPtr adapter);
            [PreserveSig] int MakeWindowAssociation(IntPtr hwnd, uint flags);
            [PreserveSig] int GetWindowAssociation(out IntPtr hwnd);
            [PreserveSig] int CreateSwapChain(IntPtr device, IntPtr desc, out IntPtr swapChain);
            [PreserveSig] int CreateSoftwareAdapter(IntPtr module, out IntPtr adapter);
            [PreserveSig] int EnumAdapters1(uint index, [MarshalAs(UnmanagedType.Interface)] out IDXGIAdapter1 adapter);
            [PreserveSig] int IsCurrent();
        }

        [ComImport, Guid("29038f61-3839-4626-91fd-086879011a05"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IDXGIAdapter1
        {
            [PreserveSig] int SetPrivateData(ref Guid name, uint size, IntPtr data);
            [PreserveSig] int SetPrivateDataInterface(ref Guid name, IntPtr unknown);
            [PreserveSig] int GetPrivateData(ref Guid name, ref uint size, IntPtr data);
            [PreserveSig] int GetParent(ref Guid riid, out IntPtr parent);
            [PreserveSig] int EnumOutputs(uint index, [MarshalAs(UnmanagedType.Interface)] out IDXGIOutput output);
            [PreserveSig] int GetDesc(IntPtr desc);
            [PreserveSig] int CheckInterfaceSupport(ref Guid name, out long version);
            [PreserveSig] int GetDesc1(IntPtr desc);
        }

        [ComImport, Guid("ae02eedb-c735-4690-8d52-5a8dc20213aa"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IDXGIOutput
        {
            [PreserveSig] int SetPrivateData(ref Guid name, uint size, IntPtr data);
            [PreserveSig] int SetPrivateDataInterface(ref Guid name, IntPtr unknown);
            [PreserveSig] int GetPrivateData(ref Guid name, ref uint size, IntPtr data);
            [PreserveSig] int GetParent(ref Guid riid, out IntPtr parent);
            [PreserveSig] int GetDesc(out DXGI_OUTPUT_DESC desc);
        }

        [DllImport("dxgi.dll")]
        private static extern int CreateDXGIFactory1(ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IDXGIFactory1 factory);

        /// <summary>
        /// Finds DXGI adapter + output index of a monitor (Screen.DeviceName, e.g. \\.\DISPLAY2). ddagrab enumerates
        /// outputs of one adapter, so on hybrid-GPU laptops the external monitor is often output 0 of adapter 1 —
        /// the 0.6 code used the Screen.AllScreens index and could capture the wrong monitor.
        /// </summary>
        public static bool FindDxgiOutput(string deviceName, out int adapterIndex, out int outputIndex)
        {
            adapterIndex = -1;
            outputIndex = -1;
            IDXGIFactory1 factory = null;
            try
            {
                Guid iid = typeof(IDXGIFactory1).GUID;
                if (CreateDXGIFactory1(ref iid, out factory) != 0 || factory == null) return false;
                for (uint a = 0; a < 16; a++)
                {
                    IDXGIAdapter1 adapter;
                    if (factory.EnumAdapters1(a, out adapter) != 0 || adapter == null) break;
                    try
                    {
                        for (uint o = 0; o < 16; o++)
                        {
                            IDXGIOutput output;
                            if (adapter.EnumOutputs(o, out output) != 0 || output == null) break;
                            try
                            {
                                DXGI_OUTPUT_DESC desc;
                                if (output.GetDesc(out desc) == 0 && string.Equals(desc.DeviceName, deviceName, StringComparison.OrdinalIgnoreCase))
                                {
                                    adapterIndex = (int)a;
                                    outputIndex = (int)o;
                                    return true;
                                }
                            }
                            finally { Marshal.ReleaseComObject(output); }
                        }
                    }
                    finally { Marshal.ReleaseComObject(adapter); }
                }
            }
            catch { }
            finally { if (factory != null) try { Marshal.ReleaseComObject(factory); } catch { } }
            return false;
        }

        // ------------------------------------------------------------ top-level windows (window sharing)

        public sealed class WindowInfo
        {
            public IntPtr Handle;
            public string Title;
            public string Label { get { return Title.Length > 70 ? Title.Substring(0, 70) + "…" : Title; } }
        }

        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder sb, int max);
        [DllImport("user32.dll")] private static extern int GetWindowTextLength(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
        [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
        [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
        [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out RECT value, int size);
        [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out int value, int size);

        public static List<WindowInfo> ListWindows(IntPtr exclude)
        {
            List<WindowInfo> list = new List<WindowInfo>();
            try
            {
                EnumWindows(delegate(IntPtr h, IntPtr l)
                {
                    try
                    {
                        if (h == exclude || !IsWindowVisible(h) || IsIconic(h)) return true;
                        long ex = GetWindowLongPtr(h, -20).ToInt64();
                        if ((ex & 0x80) != 0) return true; // WS_EX_TOOLWINDOW
                        int cloaked;
                        if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true;
                        int len = GetWindowTextLength(h);
                        if (len <= 0) return true;
                        StringBuilder sb = new StringBuilder(len + 1);
                        GetWindowText(h, sb, sb.Capacity);
                        string t = sb.ToString().Trim();
                        if (t.Length == 0 || t == "Program Manager") return true;
                        Rectangle r = WindowBounds(h);
                        if (r.Width < 120 || r.Height < 80) return true;
                        WindowInfo w = new WindowInfo();
                        w.Handle = h;
                        w.Title = t;
                        list.Add(w);
                    }
                    catch { }
                    return true;
                }, IntPtr.Zero);
            }
            catch { }
            return list;
        }

        /// <summary>Visible window bounds in physical pixels (DWM extended frame, without invisible borders).</summary>
        public static Rectangle WindowBounds(IntPtr h)
        {
            RECT r;
            try
            {
                if (DwmGetWindowAttribute(h, 9, out r, Marshal.SizeOf(typeof(RECT))) == 0 && r.Right > r.Left)
                    return Rectangle.FromLTRB(r.Left, r.Top, r.Right, r.Bottom);
            }
            catch { }
            if (GetWindowRect(h, out r)) return Rectangle.FromLTRB(r.Left, r.Top, r.Right, r.Bottom);
            return Rectangle.Empty;
        }

        public static string WindowTitle(IntPtr h)
        {
            int len = GetWindowTextLength(h);
            if (len <= 0) return "";
            StringBuilder sb = new StringBuilder(len + 1);
            GetWindowText(h, sb, sb.Capacity);
            return sb.ToString();
        }

        // ------------------------------------------------------------ touch from the board → mouse (SendInput)

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx, dy;
            public uint mouseData, dwFlags, time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public MOUSEINPUT mi;
        }

        [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint n, INPUT[] inputs, int size);
        [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);

        private const uint MOUSEEVENTF_MOVE = 0x0001, MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000, MOUSEEVENTF_ABSOLUTE = 0x8000;

        /// <summary>Moves the mouse to (x,y) in physical desktop pixels and optionally presses/releases the left button.</summary>
        public static void InjectMouse(int x, int y, char action)
        {
            int vx = GetSystemMetrics(76), vy = GetSystemMetrics(77), vw = Math.Max(2, GetSystemMetrics(78)), vh = Math.Max(2, GetSystemMetrics(79));
            INPUT i = new INPUT();
            i.type = 0;
            i.mi.dx = (int)Math.Round((x - vx) * 65535.0 / (vw - 1));
            i.mi.dy = (int)Math.Round((y - vy) * 65535.0 / (vh - 1));
            i.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
            if (action == 'd') i.mi.dwFlags |= MOUSEEVENTF_LEFTDOWN;
            else if (action == 'u') i.mi.dwFlags |= MOUSEEVENTF_LEFTUP;
            SendInput(1, new INPUT[] { i }, Marshal.SizeOf(typeof(INPUT)));
        }

        // ------------------------------------------------------------ mute laptop speakers (IAudioEndpointVolume)

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        }

        [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IAudioEndpointVolume
        {
            [PreserveSig] int RegisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int UnregisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int GetChannelCount(out uint count);
            [PreserveSig] int SetMasterVolumeLevel(float level, ref Guid ctx);
            [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid ctx);
            [PreserveSig] int GetMasterVolumeLevel(out float level);
            [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
            [PreserveSig] int SetChannelVolumeLevel(uint channel, float level, ref Guid ctx);
            [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid ctx);
            [PreserveSig] int GetChannelVolumeLevel(uint channel, out float level);
            [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
            [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
            [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
        }

        private static IAudioEndpointVolume DefaultEndpointVolume()
        {
            IMMDeviceEnumerator en = (IMMDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")));
            IMMDevice dev;
            if (en.GetDefaultAudioEndpoint(0, 0, out dev) != 0 || dev == null) return null;
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            object o;
            if (dev.Activate(ref iid, 23, IntPtr.Zero, out o) != 0) return null;
            return o as IAudioEndpointVolume;
        }

        /// <summary>Mutes the default playback device; returns the previous state (null = not available).</summary>
        public static bool? SetSpeakerMute(bool mute)
        {
            try
            {
                IAudioEndpointVolume v = DefaultEndpointVolume();
                if (v == null) return null;
                bool before;
                if (v.GetMute(out before) != 0) return null;
                Guid ctx = Guid.Empty;
                v.SetMute(mute, ref ctx);
                Marshal.ReleaseComObject(v);
                return before;
            }
            catch
            {
                return null;
            }
        }

        // ------------------------------------------------------------ DPAPI (saved device keys)

        private static readonly byte[] Entropy = Encoding.ASCII.GetBytes("TabuleLink device key v1");

        public static string Protect(string plain)
        {
            try
            {
                byte[] enc = ProtectedData.Protect(Encoding.UTF8.GetBytes(plain), Entropy, DataProtectionScope.CurrentUser);
                return "dp:" + Convert.ToBase64String(enc);
            }
            catch
            {
                return plain;
            }
        }

        public static string Unprotect(string stored)
        {
            if (stored == null || !stored.StartsWith("dp:", StringComparison.Ordinal)) return stored;
            try
            {
                return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(stored.Substring(3)), Entropy, DataProtectionScope.CurrentUser));
            }
            catch
            {
                return null;
            }
        }

        // ------------------------------------------------------------ global hotkeys

        [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr hwnd, int id, uint modifiers, uint vk);
        [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr hwnd, int id);
        public const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000;
        public const int WM_HOTKEY = 0x0312;

        [DllImport("user32.dll")] public static extern bool DestroyIcon(IntPtr hIcon);

        /// <summary>Quotes one command-line argument (CommandLineToArgvW rules).</summary>
        public static string QuoteArg(string arg)
        {
            if (arg.Length > 0 && arg.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) return arg;
            StringBuilder sb = new StringBuilder("\"");
            int backslashes = 0;
            foreach (char c in arg)
            {
                if (c == '\\') { backslashes++; continue; }
                if (c == '"') { sb.Append('\\', backslashes * 2 + 1); sb.Append('"'); backslashes = 0; continue; }
                sb.Append('\\', backslashes);
                backslashes = 0;
                sb.Append(c);
            }
            sb.Append('\\', backslashes * 2);
            sb.Append('"');
            return sb.ToString();
        }
    }
}
