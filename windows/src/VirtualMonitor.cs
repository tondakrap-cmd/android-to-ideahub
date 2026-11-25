using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace TabuleLinkWin
{
    public enum VddState { Missing, Disabled, Ready }

    /// <summary>
    /// "Share as a new monitor": an extended virtual monitor provided by the open-source Virtual Display Driver
    /// (github.com/VirtualDrivers/Virtual-Display-Driver, MIT, IddCx). The driver is installed once (winget, admin);
    /// its monitor is kept detached from the desktop and attached only while sharing — via SetDisplayConfig, which a
    /// standard user may call, so sharing itself never needs administrator rights.
    /// </summary>
    public static class VirtualMonitor
    {
        public const string WingetId = "VirtualDrivers.Virtual-Display-Driver";
        public const string ProjectUrl = "https://github.com/VirtualDrivers/Virtual-Display-Driver";

        private static readonly object Gate = new object();

        // ------------------------------------------------------------ driver detection (SetupAPI)

        [StructLayout(LayoutKind.Sequential)]
        private struct SP_DEVINFO_DATA
        {
            public uint cbSize;
            public Guid ClassGuid;
            public uint DevInst;
            public IntPtr Reserved;
        }

        [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr SetupDiGetClassDevsW(ref Guid classGuid, IntPtr enumerator, IntPtr hwnd, uint flags);
        [DllImport("setupapi.dll", SetLastError = true)]
        private static extern bool SetupDiEnumDeviceInfo(IntPtr set, uint index, ref SP_DEVINFO_DATA data);
        [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool SetupDiGetDeviceRegistryPropertyW(IntPtr set, ref SP_DEVINFO_DATA data, uint property, out uint regType, byte[] buffer, uint size, out uint required);
        [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool SetupDiGetDeviceInstanceIdW(IntPtr set, ref SP_DEVINFO_DATA data, StringBuilder id, int size, out int required);
        [DllImport("setupapi.dll")]
        private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
        [DllImport("cfgmgr32.dll")]
        private static extern int CM_Get_DevNode_Status(out uint status, out uint problem, uint devInst, uint flags);

        private static readonly Guid DisplayClass = new Guid("4d36e968-e325-11ce-bfc1-08002be10318");
        private const uint DIGCF_PRESENT = 0x2, SPDRP_HARDWAREID = 0x1, DN_STARTED = 0x8, CM_PROB_DISABLED = 22;

        /// <summary>Finds the Virtual Display Driver among present display adapters.</summary>
        public static VddState Detect(out List<string> instanceIds, out string detail)
        {
            instanceIds = new List<string>();
            detail = "ovladač Virtual Display Driver není nainstalovaný";
            VddState best = VddState.Missing;
            Guid cls = DisplayClass;
            IntPtr set = SetupDiGetClassDevsW(ref cls, IntPtr.Zero, IntPtr.Zero, DIGCF_PRESENT);
            if (set == IntPtr.Zero || set == new IntPtr(-1)) return best;
            try
            {
                for (uint i = 0; i < 64; i++)
                {
                    SP_DEVINFO_DATA d = new SP_DEVINFO_DATA();
                    d.cbSize = (uint)Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
                    if (!SetupDiEnumDeviceInfo(set, i, ref d)) break;
                    byte[] buf = new byte[2048];
                    uint regType, req;
                    if (!SetupDiGetDeviceRegistryPropertyW(set, ref d, SPDRP_HARDWAREID, out regType, buf, (uint)buf.Length, out req)) continue;
                    string raw = Encoding.Unicode.GetString(buf, 0, (int)Math.Min(req, (uint)buf.Length));
                    if (!DisplayMath.IsVddHardwareId(DisplayMath.SplitMultiSz(raw))) continue;
                    StringBuilder sb = new StringBuilder(400);
                    int need;
                    if (SetupDiGetDeviceInstanceIdW(set, ref d, sb, sb.Capacity, out need)) instanceIds.Add(sb.ToString());
                    uint status, problem;
                    bool started = CM_Get_DevNode_Status(out status, out problem, d.DevInst, 0) == 0 && (status & DN_STARTED) != 0;
                    if (started)
                    {
                        best = VddState.Ready;
                        detail = "Virtual Display Driver je připraven";
                    }
                    else if (best != VddState.Ready)
                    {
                        best = VddState.Disabled;
                        detail = problem == CM_PROB_DISABLED
                            ? "Virtual Display Driver je ve Správci zařízení zakázaný"
                            : "Virtual Display Driver nefunguje (kód problému " + problem + " ve Správci zařízení)";
                    }
                }
            }
            catch (Exception ex) { detail = "kontrola ovladače selhala: " + ex.Message; }
            finally { SetupDiDestroyDeviceInfoList(set); }
            return best;
        }

        // ------------------------------------------------------------ CCD (QueryDisplayConfig / SetDisplayConfig)

        [StructLayout(LayoutKind.Sequential)]
        private struct LUID { public uint LowPart; public int HighPart; }

        [StructLayout(LayoutKind.Sequential)]
        private struct DISPLAYCONFIG_PATH_SOURCE_INFO { public LUID adapterId; public uint id; public uint modeInfoIdx; public uint statusFlags; }

        [StructLayout(LayoutKind.Sequential)]
        private struct DISPLAYCONFIG_RATIONAL { public uint Numerator, Denominator; }

        [StructLayout(LayoutKind.Sequential)]
        private struct DISPLAYCONFIG_PATH_TARGET_INFO
        {
            public LUID adapterId;
            public uint id;
            public uint modeInfoIdx;
            public uint outputTechnology;
            public uint rotation;
            public uint scaling;
            public DISPLAYCONFIG_RATIONAL refreshRate;
            public uint scanLineOrdering;
            public int targetAvailable;
            public uint statusFlags;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct DISPLAYCONFIG_PATH_INFO
        {
            public DISPLAYCONFIG_PATH_SOURCE_INFO sourceInfo;
            public DISPLAYCONFIG_PATH_TARGET_INFO targetInfo;
            public uint flags;
        }

        /// <summary>Opaque 64-byte DISPLAYCONFIG_MODE_INFO (only passed back to SetDisplayConfig unchanged).</summary>
        [StructLayout(LayoutKind.Sequential, Size = 64)]
        private struct DISPLAYCONFIG_MODE_INFO { public uint infoType; public uint id; public LUID adapterId; }

        [StructLayout(LayoutKind.Sequential)]
        private struct DISPLAYCONFIG_DEVICE_INFO_HEADER { public uint type; public uint size; public LUID adapterId; public uint id; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DISPLAYCONFIG_SOURCE_DEVICE_NAME
        {
            public DISPLAYCONFIG_DEVICE_INFO_HEADER header;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string viewGdiDeviceName;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DISPLAYCONFIG_TARGET_DEVICE_NAME
        {
            public DISPLAYCONFIG_DEVICE_INFO_HEADER header;
            public uint flags;
            public uint outputTechnology;
            public ushort edidManufactureId;
            public ushort edidProductCodeId;
            public uint connectorInstance;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string monitorFriendlyDeviceName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string monitorDevicePath;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DISPLAYCONFIG_ADAPTER_NAME
        {
            public DISPLAYCONFIG_DEVICE_INFO_HEADER header;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string adapterDevicePath;
        }

        [DllImport("user32.dll")] private static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPaths, out uint numModes);
        [DllImport("user32.dll")]
        private static extern int QueryDisplayConfig(uint flags, ref uint numPaths, [Out] DISPLAYCONFIG_PATH_INFO[] paths, ref uint numModes, [Out] DISPLAYCONFIG_MODE_INFO[] modes, IntPtr topologyId);
        [DllImport("user32.dll")]
        private static extern int SetDisplayConfig(uint numPaths, [In] DISPLAYCONFIG_PATH_INFO[] paths, uint numModes, [In] DISPLAYCONFIG_MODE_INFO[] modes, uint flags);
        [DllImport("user32.dll")] private static extern int DisplayConfigGetDeviceInfo(ref DISPLAYCONFIG_SOURCE_DEVICE_NAME req);
        [DllImport("user32.dll")] private static extern int DisplayConfigGetDeviceInfo(ref DISPLAYCONFIG_TARGET_DEVICE_NAME req);
        [DllImport("user32.dll")] private static extern int DisplayConfigGetDeviceInfo(ref DISPLAYCONFIG_ADAPTER_NAME req);

        private const uint QDC_ALL_PATHS = 0x1, QDC_ONLY_ACTIVE_PATHS = 0x2;
        private const uint SDC_USE_SUPPLIED_DISPLAY_CONFIG = 0x20, SDC_APPLY = 0x80, SDC_SAVE_TO_DATABASE = 0x200, SDC_ALLOW_CHANGES = 0x400;
        private const uint DISPLAYCONFIG_PATH_ACTIVE = 0x1, MODE_IDX_INVALID = 0xffffffff;
        private const int ERROR_INSUFFICIENT_BUFFER = 122;

        private static int Query(uint flags, out DISPLAYCONFIG_PATH_INFO[] paths, out DISPLAYCONFIG_MODE_INFO[] modes)
        {
            paths = new DISPLAYCONFIG_PATH_INFO[0];
            modes = new DISPLAYCONFIG_MODE_INFO[0];
            for (int attempt = 0; attempt < 4; attempt++)
            {
                uint np, nm;
                int rc = GetDisplayConfigBufferSizes(flags, out np, out nm);
                if (rc != 0) return rc;
                DISPLAYCONFIG_PATH_INFO[] p = new DISPLAYCONFIG_PATH_INFO[np];
                DISPLAYCONFIG_MODE_INFO[] m = new DISPLAYCONFIG_MODE_INFO[nm];
                rc = QueryDisplayConfig(flags, ref np, p, ref nm, m, IntPtr.Zero);
                if (rc == ERROR_INSUFFICIENT_BUFFER) continue;
                if (rc != 0) return rc;
                Array.Resize(ref p, (int)np);
                Array.Resize(ref m, (int)nm);
                paths = p;
                modes = m;
                return 0;
            }
            return ERROR_INSUFFICIENT_BUFFER;
        }

        private static string AdapterPath(LUID adapter)
        {
            DISPLAYCONFIG_ADAPTER_NAME n = new DISPLAYCONFIG_ADAPTER_NAME();
            n.header.type = 4; // DISPLAYCONFIG_DEVICE_INFO_GET_ADAPTER_NAME
            n.header.size = (uint)Marshal.SizeOf(typeof(DISPLAYCONFIG_ADAPTER_NAME));
            n.header.adapterId = adapter;
            return DisplayConfigGetDeviceInfo(ref n) == 0 ? n.adapterDevicePath : null;
        }

        private static string MonitorPath(LUID adapter, uint targetId)
        {
            DISPLAYCONFIG_TARGET_DEVICE_NAME n = new DISPLAYCONFIG_TARGET_DEVICE_NAME();
            n.header.type = 2; // DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME
            n.header.size = (uint)Marshal.SizeOf(typeof(DISPLAYCONFIG_TARGET_DEVICE_NAME));
            n.header.adapterId = adapter;
            n.header.id = targetId;
            return DisplayConfigGetDeviceInfo(ref n) == 0 ? n.monitorDevicePath : null;
        }

        private static string SourceGdiName(LUID adapter, uint sourceId)
        {
            DISPLAYCONFIG_SOURCE_DEVICE_NAME n = new DISPLAYCONFIG_SOURCE_DEVICE_NAME();
            n.header.type = 1; // DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME
            n.header.size = (uint)Marshal.SizeOf(typeof(DISPLAYCONFIG_SOURCE_DEVICE_NAME));
            n.header.adapterId = adapter;
            n.header.id = sourceId;
            return DisplayConfigGetDeviceInfo(ref n) == 0 ? n.viewGdiDeviceName : null;
        }

        private static bool SameAdapter(LUID a, LUID b) { return a.LowPart == b.LowPart && a.HighPart == b.HighPart; }

        private static bool IsVddTarget(DISPLAYCONFIG_PATH_INFO p, List<string> instanceIds, Dictionary<long, bool> cache)
        {
            long key = ((long)p.targetInfo.adapterId.HighPart << 32) | p.targetInfo.adapterId.LowPart;
            bool isVdd;
            if (!cache.TryGetValue(key, out isVdd))
            {
                string ap = AdapterPath(p.targetInfo.adapterId);
                isVdd = false;
                foreach (string id in instanceIds) if (DisplayMath.AdapterPathMatchesInstance(ap, id)) isVdd = true;
                cache[key] = isVdd;
            }
            return isVdd || DisplayMath.LooksLikeVddMonitor(MonitorPath(p.targetInfo.adapterId, p.targetInfo.id));
        }

        private static string Win32Message(int rc)
        {
            switch (rc)
            {
                case 5: return "Windows změnu zobrazení odmítl (zamčená obrazovka nebo vzdálená plocha?)";
                case 31: return "grafický ovladač změnu zobrazení nepřijal";
                case 87: return "Windows nepřijal nastavení zobrazení";
                case 1610: return "Windows tuto kombinaci monitorů nepodporuje";
                default: return "chyba Windows " + rc;
            }
        }

        // ------------------------------------------------------------ attach / detach

        /// <summary>
        /// Attaches the virtual monitor as an extended desktop to the right of the current monitors and sets
        /// 1920 × 1080. Returns its GDI device name (\\.\DISPLAYn); throws IOException with a Czech message on failure.
        /// attachedByUs = false when no free target exists and the user already had a VDD monitor on the desktop (then it stays untouched).
        /// </summary>
        public static string Attach(int width, int height, out bool attachedByUs)
        {
            attachedByUs = false;
            lock (Gate)
            {
                List<string> ids;
                string detail;
                VddState st = Detect(out ids, out detail);
                if (st != VddState.Ready) throw new IOException(char.ToUpper(detail[0]) + detail.Substring(1) + ".");

                string marker = ReadMarker();
                if (marker != null)
                {
                    // Left over from a crashed Tabule Link session: reuse our own already-attached virtual monitor
                    string leftoverGdi = ActiveMarkedVddGdiName(ids, marker);
                    if (leftoverGdi != null)
                    {
                        attachedByUs = true;
                        SetModeAndPosition(leftoverGdi, width, height);
                        for (int i = 0; i < 20 && Bounds(leftoverGdi).IsEmpty; i++) Thread.Sleep(150);
                        return leftoverGdi;
                    }
                    WriteMarker(null);
                }

                DISPLAYCONFIG_PATH_INFO[] all;
                DISPLAYCONFIG_MODE_INFO[] modes;
                int rc = Query(QDC_ALL_PATHS, out all, out modes);
                if (rc != 0) throw new IOException("Nelze načíst nastavení monitorů: " + Win32Message(rc) + ".");
                Dictionary<long, bool> cache = new Dictionary<long, bool>();
                List<DISPLAYCONFIG_PATH_INFO> active = new List<DISPLAYCONFIG_PATH_INFO>();
                foreach (DISPLAYCONFIG_PATH_INFO p in all) if ((p.flags & DISPLAYCONFIG_PATH_ACTIVE) != 0) active.Add(p);
                if (active.Count == 0) throw new IOException("Windows nehlásí žádný aktivní monitor.");

                bool sawVdd = false, foundFree = false;
                DISPLAYCONFIG_PATH_INFO pick = new DISPLAYCONFIG_PATH_INFO();
                foreach (DISPLAYCONFIG_PATH_INFO p in all)
                {
                    if (!IsVddTarget(p, ids, cache)) continue;
                    sawVdd = true;
                    if ((p.flags & DISPLAYCONFIG_PATH_ACTIVE) != 0) continue;
                    if (p.targetInfo.targetAvailable == 0) continue;
                    bool sourceBusy = false;
                    foreach (DISPLAYCONFIG_PATH_INFO a in active)
                        if (SameAdapter(a.sourceInfo.adapterId, p.sourceInfo.adapterId) && a.sourceInfo.id == p.sourceInfo.id) sourceBusy = true;
                    if (sourceBusy) continue;
                    pick = p;
                    foundFree = true;
                    break;
                }

                if (foundFree)
                {
                    string monPath = MonitorPath(pick.targetInfo.adapterId, pick.targetInfo.id);
                    pick.flags = DISPLAYCONFIG_PATH_ACTIVE;
                    pick.sourceInfo.modeInfoIdx = MODE_IDX_INVALID;
                    pick.targetInfo.modeInfoIdx = MODE_IDX_INVALID;
                    pick.targetInfo.statusFlags = 0;
                    active.Add(pick);
                    DISPLAYCONFIG_PATH_INFO[] arr = active.ToArray();
                    rc = SetDisplayConfig((uint)arr.Length, arr, (uint)modes.Length, modes,
                        SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
                    if (rc != 0) throw new IOException("Virtuální monitor se nepodařilo připojit: " + Win32Message(rc) + ".");
                    WriteMarker(DisplayMath.FormatManagedMarker(pick.targetInfo.id, monPath));

                    try
                    {
                        string gdi = null;
                        for (int i = 0; i < 20 && gdi == null; i++)
                        {
                            gdi = ActiveTargetGdiName(pick.targetInfo.adapterId, pick.targetInfo.id);
                            if (gdi == null) Thread.Sleep(150);
                        }
                        if (gdi == null) throw new IOException("Virtuální monitor se připojil, ale Windows ho nezobrazil na ploše.");
                        attachedByUs = true;
                        SetModeAndPosition(gdi, width, height);
                        for (int i = 0; i < 20 && Bounds(gdi).IsEmpty; i++) Thread.Sleep(150);
                        return gdi;
                    }
                    catch
                    {
                        Detach();
                        throw;
                    }
                }

                // Fallback when no free target exists (e.g. manually installed driver with 1 monitor already attached by user):
                // share it without changing its mode/position and without detaching it afterwards.
                string existingGdi = ActiveVddGdiName(ids);
                if (existingGdi != null)
                {
                    attachedByUs = false;
                    for (int i = 0; i < 20 && Bounds(existingGdi).IsEmpty; i++) Thread.Sleep(150);
                    return existingGdi;
                }

                throw new IOException(sawVdd
                    ? "Virtual Display Driver nemá volný monitor. V aplikaci Virtual Driver Control nastavte počet monitorů alespoň 1."
                    : "Virtual Display Driver nehlásí žádný monitor. V aplikaci Virtual Driver Control nastavte počet monitorů alespoň 1 a ovladač znovu načtěte.");
            }
        }

        /// <summary>Detaches the virtual monitor managed by Tabule Link from the desktop. Returns null on success, else a Czech error.</summary>
        public static string Detach()
        {
            lock (Gate)
            {
                try
                {
                    string marker = ReadMarker();
                    List<string> ids;
                    string detail;
                    Detect(out ids, out detail);
                    DISPLAYCONFIG_PATH_INFO[] act;
                    DISPLAYCONFIG_MODE_INFO[] modes;
                    int rc = Query(QDC_ONLY_ACTIVE_PATHS, out act, out modes);
                    if (rc != 0) return "Nelze načíst nastavení monitorů: " + Win32Message(rc) + ".";
                    Dictionary<long, bool> cache = new Dictionary<long, bool>();
                    List<DISPLAYCONFIG_PATH_INFO> keep = new List<DISPLAYCONFIG_PATH_INFO>();
                    foreach (DISPLAYCONFIG_PATH_INFO p in act)
                    {
                        bool detachThis = IsVddTarget(p, ids, cache)
                            && DisplayMath.MarkerMatchesTarget(marker ?? "*", p.targetInfo.id, MonitorPath(p.targetInfo.adapterId, p.targetInfo.id));
                        if (!detachThis) keep.Add(p);
                    }
                    if (keep.Count == act.Length) { WriteMarker(null); return null; }
                    if (keep.Count == 0) return "Virtuální monitor je jediný aktivní displej, proto zůstává připojený.";
                    DISPLAYCONFIG_PATH_INFO[] arr = keep.ToArray();
                    rc = SetDisplayConfig((uint)arr.Length, arr, (uint)modes.Length, modes,
                        SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES | SDC_SAVE_TO_DATABASE);
                    if (rc != 0) return "Virtuální monitor se nepodařilo odpojit: " + Win32Message(rc) + ".";
                    WriteMarker(null);
                    return null;
                }
                catch (Exception ex)
                {
                    return "Virtuální monitor se nepodařilo odpojit: " + ex.Message;
                }
            }
        }

        /// <summary>After a crash or forced exit: detaches the monitor only if Tabule Link attached it (marker file).</summary>
        public static void CleanupIfOurs()
        {
            try
            {
                if (ReadMarker() != null) Detach();
            }
            catch { }
        }

        // ------------------------------------------------------------ driver installation

        private static string CurrentArch()
        {
            return Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE", EnvironmentVariableTarget.Machine)
                ?? Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") ?? "";
        }

        private static string FindDevconPath(string packageDir)
        {
            if (string.IsNullOrEmpty(packageDir)) return null;
            string preferred = Path.Combine(packageDir, @"Dependencies\devcon.exe");
            if (File.Exists(preferred)) return preferred;
            try
            {
                if (Directory.Exists(packageDir))
                {
                    string[] found = Directory.GetFiles(packageDir, "devcon.exe", SearchOption.AllDirectories);
                    if (found.Length > 0) return found[0];
                }
            }
            catch { }
            return null;
        }

        private static string ResolveDriverDir(string packageDir, string arch)
        {
            if (string.IsNullOrEmpty(packageDir) || !Directory.Exists(packageDir)) return null;
            foreach (string rel in DisplayMath.CandidateDriverSubdirs(arch))
            {
                string candidate = Path.Combine(packageDir, rel);
                if (File.Exists(Path.Combine(candidate, "MttVDD.inf"))) return candidate;
            }
            bool wantArm64 = string.Equals(arch, "ARM64", StringComparison.OrdinalIgnoreCase);
            try
            {
                foreach (string inf in Directory.GetFiles(packageDir, "MttVDD.inf", SearchOption.AllDirectories))
                {
                    string dir = Path.GetDirectoryName(inf);
                    if (string.IsNullOrEmpty(dir)) continue;
                    bool isArmDir = dir.IndexOf("ARM64", StringComparison.OrdinalIgnoreCase) >= 0;
                    if (isArmDir == wantArm64) return dir;
                }
            }
            catch { }
            return null;
        }

        /// <summary>
        /// Folder of the winget package (portable zip with Virtual Driver Control, the signed driver and devcon.exe).
        /// winget only unpacks it; the driver itself is installed by InstallDriverElevated.
        /// </summary>
        public static string FindWingetPackage()
        {
            try
            {
                string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Microsoft\WinGet\Packages");
                if (!Directory.Exists(root)) return null;
                string arch = CurrentArch();
                foreach (string dir in Directory.GetDirectories(root, WingetId + "_*"))
                    if (FindDevconPath(dir) != null && ResolveDriverDir(dir, arch) != null) return dir;
            }
            catch { }
            return null;
        }

        /// <summary>
        /// Installs the driver from the winget package like Virtual Driver Control does: settings to
        /// C:\VirtualDisplayDriver, then "devcon install MttVDD.inf Root\MttVDD". Runs elevated (UAC prompt; Windows
        /// may also ask to trust the publisher SignPath Foundation). Returns null on success, else a Czech error.
        /// </summary>
        public static string InstallDriverElevated(string packageDir)
        {
            string driverDir = ResolveDriverDir(packageDir, CurrentArch());
            string devcon = FindDevconPath(packageDir);
            if (driverDir == null || devcon == null || !File.Exists(Path.Combine(driverDir, "MttVDD.inf")))
                return "V balíčku ovladače chybí MttVDD.inf nebo devcon.exe (" + packageDir + ").";
            string log = Path.Combine(Path.GetTempPath(), "TabuleLink-vdd-install.log");
            try { if (File.Exists(log)) File.Delete(log); } catch { }
            string script = BuildInstallScript(packageDir, driverDir, @"C:\VirtualDisplayDriver", log);
            System.Diagnostics.ProcessStartInfo psi = new System.Diagnostics.ProcessStartInfo();
            psi.FileName = "powershell.exe";
            psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            psi.Verb = "runas";
            psi.UseShellExecute = true;
            psi.WindowStyle = System.Diagnostics.ProcessWindowStyle.Hidden;
            try
            {
                using (System.Diagnostics.Process p = System.Diagnostics.Process.Start(psi))
                {
                    if (p != null) p.WaitForExit();
                }
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                return ex.NativeErrorCode == 1223 ? "Instalace ovladače byla zrušena (oprávnění správce nebylo uděleno)." : "Instalaci ovladače nelze spustit: " + ex.Message;
            }
            string result = "";
            try { if (File.Exists(log)) result = File.ReadAllText(log).Trim(); } catch { }
            if (result.StartsWith("exit=0", StringComparison.Ordinal)) return null;
            return "Instalace ovladače se nepodařila" + (result.Length > 0 ? ": " + result : ".");
        }

        /// <summary>
        /// PowerShell script run elevated by InstallDriverElevated (public for the probe/test harness). The winget
        /// package lives in the user-writable %LOCALAPPDATA%, so nothing is executed from there: devcon.exe and the
        /// driver files are first copied into a fresh folder that only Administrators and SYSTEM can write, and the
        /// copies must carry a valid Authenticode signature (devcon: Microsoft, driver catalog: SignPath Foundation).
        /// </summary>
        public static string BuildInstallScript(string packageDir, string driverDir, string settingsDir, string log)
        {
            return BuildInstallScript(packageDir, driverDir, settingsDir, log, null);
        }

        /// <summary>
        /// Creates folder $w atomically with a protected DACL (no inherited entries): full control for Administrators
        /// and SYSTEM only, so a standard user can't swap devcon.exe between the signature check and its execution.
        /// </summary>
        public const string AdminOnlyDirectorySnippet =
            "$acl = New-Object System.Security.AccessControl.DirectorySecurity; $acl.SetAccessRuleProtection($true, $false); "
            + "foreach ($sid in 'S-1-5-32-544','S-1-5-18') { $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule("
            + "(New-Object System.Security.Principal.SecurityIdentifier $sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))) }; "
            + "$null = [System.IO.Directory]::CreateDirectory($w, $acl); ";

        /// <summary>testWorkRoot != null (tests only, not elevated): work folder there, without the admin-only ACL.</summary>
        public static string BuildInstallScript(string packageDir, string driverDir, string settingsDir, string log, string testWorkRoot)
        {
            string devconSrc = FindDevconPath(packageDir) ?? Path.Combine(packageDir, @"Dependencies\devcon.exe");
            return "$ErrorActionPreference='Stop'; $w=$null; try { "
                + "$w = Join-Path " + (testWorkRoot == null ? "$env:SystemRoot" : PsQuote(testWorkRoot)) + " ('" + (testWorkRoot == null ? "Temp\\" : "") + "TabuleLink-vdd-' + [guid]::NewGuid().ToString('N')); "
                + (testWorkRoot != null ? "New-Item -ItemType Directory -Path $w | Out-Null; " : AdminOnlyDirectorySnippet)
                + "Copy-Item -Path (Join-Path " + PsQuote(driverDir) + " '*') -Destination $w; "
                + "Copy-Item -LiteralPath " + PsQuote(devconSrc) + " -Destination (Join-Path $w 'devcon.exe'); "
                + "$d = Join-Path $w 'devcon.exe'; $s = Get-AuthenticodeSignature -FilePath $d; "
                + "if ($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') { throw 'devcon.exe nemá platný podpis Microsoftu' }; "
                + "$c = Get-AuthenticodeSignature -FilePath (Join-Path $w 'mttvdd.cat'); "
                + "if ($c.Status -ne 'Valid' -or $c.SignerCertificate.Subject -notmatch 'CN=SignPath Foundation') { throw 'katalog ovladače nemá platný podpis SignPath Foundation' }; "
                // settings: beside the INF, else in the package's Dependencies folder; without them the driver uses its defaults
                + "$t=" + PsQuote(settingsDir) + "; if (-not (Test-Path $t)) { New-Item -ItemType Directory -Path $t | Out-Null }; "
                + "if (-not (Test-Path (Join-Path $t 'vdd_settings.xml'))) { "
                + "$x = @((Join-Path $w 'vdd_settings.xml'), " + PsQuote(Path.Combine(packageDir, @"Dependencies\vdd_settings.xml")) + ") | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1; "
                + "if ($x) { Copy-Item -LiteralPath $x -Destination $t } }; "
                // stderr of a native command would throw under 'Stop' in Windows PowerShell 5.1
                + "$ErrorActionPreference='Continue'; $o = & $d install (Join-Path $w 'MttVDD.inf') 'Root\\MttVDD' 2>&1 | Out-String; $ec = $LASTEXITCODE; "
                + "('exit=' + $ec + ' ' + $o.Trim()) | Set-Content -Path " + PsQuote(log) + " -Encoding UTF8 "
                + "} catch { ('ERROR ' + $_.Exception.Message) | Set-Content -Path " + PsQuote(log) + " -Encoding UTF8 } "
                + "finally { if ($w -and (Test-Path -LiteralPath $w)) { Remove-Item -LiteralPath $w -Recurse -Force -ErrorAction SilentlyContinue } }";
        }

        private static string PsQuote(string s) { return "'" + s.Replace("'", "''") + "'"; }

        /// <summary>Marks the driver's monitor as managed by Tabule Link (right after installing the driver from the app).</summary>
        public static void MarkManaged() { WriteMarker("*"); }

        private static string ActiveTargetGdiName(LUID targetAdapter, uint targetId)
        {
            DISPLAYCONFIG_PATH_INFO[] act;
            DISPLAYCONFIG_MODE_INFO[] modes;
            if (Query(QDC_ONLY_ACTIVE_PATHS, out act, out modes) != 0) return null;
            foreach (DISPLAYCONFIG_PATH_INFO p in act)
                if (SameAdapter(p.targetInfo.adapterId, targetAdapter) && p.targetInfo.id == targetId)
                    return SourceGdiName(p.sourceInfo.adapterId, p.sourceInfo.id);
            return null;
        }

        private static string ActiveMarkedVddGdiName(List<string> ids, string marker)
        {
            DISPLAYCONFIG_PATH_INFO[] act;
            DISPLAYCONFIG_MODE_INFO[] modes;
            if (Query(QDC_ONLY_ACTIVE_PATHS, out act, out modes) != 0) return null;
            Dictionary<long, bool> cache = new Dictionary<long, bool>();
            foreach (DISPLAYCONFIG_PATH_INFO p in act)
            {
                if (IsVddTarget(p, ids, cache) && DisplayMath.MarkerMatchesTarget(marker, p.targetInfo.id, MonitorPath(p.targetInfo.adapterId, p.targetInfo.id)))
                    return SourceGdiName(p.sourceInfo.adapterId, p.sourceInfo.id);
            }
            return null;
        }

        private static string ActiveVddGdiName(List<string> ids)
        {
            DISPLAYCONFIG_PATH_INFO[] act;
            DISPLAYCONFIG_MODE_INFO[] modes;
            if (Query(QDC_ONLY_ACTIVE_PATHS, out act, out modes) != 0) return null;
            Dictionary<long, bool> cache = new Dictionary<long, bool>();
            foreach (DISPLAYCONFIG_PATH_INFO p in act)
                if (IsVddTarget(p, ids, cache)) return SourceGdiName(p.sourceInfo.adapterId, p.sourceInfo.id);
            return null;
        }

        private static string MarkerPath()
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TabuleLink");
            return Path.Combine(dir, "virtual-monitor.attached");
        }

        private static string ReadMarker()
        {
            try
            {
                string path = MarkerPath();
                if (!File.Exists(path)) return null;
                string content = File.ReadAllText(path, Encoding.UTF8).Trim();
                return content.Length > 0 ? content : "*";
            }
            catch { return null; }
        }

        private static void WriteMarker(string content)
        {
            try
            {
                string path = MarkerPath();
                if (!string.IsNullOrEmpty(content))
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    File.WriteAllText(path, content, Encoding.UTF8);
                }
                else if (File.Exists(path)) File.Delete(path);
            }
            catch { }
        }

        // ------------------------------------------------------------ mode, position, bounds (GDI)

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DEVMODE
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
            public ushort dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
            public uint dmFields;
            public int dmPositionX, dmPositionY;
            public uint dmDisplayOrientation, dmDisplayFixedOutput;
            public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
            public ushort dmLogPixels;
            public uint dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
            public uint dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool EnumDisplaySettingsW(string device, int mode, ref DEVMODE dm);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int ChangeDisplaySettingsExW(string device, ref DEVMODE dm, IntPtr hwnd, uint flags, IntPtr param);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int ChangeDisplaySettingsExW(string device, IntPtr dm, IntPtr hwnd, uint flags, IntPtr param);

        private const int ENUM_CURRENT_SETTINGS = -1;
        private const uint DM_POSITION = 0x20, DM_PELSWIDTH = 0x80000, DM_PELSHEIGHT = 0x100000;
        private const uint CDS_UPDATEREGISTRY = 0x1, CDS_NORESET = 0x10000000;

        private static DEVMODE NewDevMode()
        {
            DEVMODE dm = new DEVMODE();
            dm.dmSize = (ushort)Marshal.SizeOf(typeof(DEVMODE));
            return dm;
        }

        private static void SetModeAndPosition(string gdi, int width, int height)
        {
            List<Size> sizes = new List<Size>();
            for (int i = 0; i < 2000; i++)
            {
                DEVMODE m = NewDevMode();
                if (!EnumDisplaySettingsW(gdi, i, ref m)) break;
                Size s = new Size((int)m.dmPelsWidth, (int)m.dmPelsHeight);
                if (!sizes.Contains(s)) sizes.Add(s);
            }
            Size mode = DisplayMath.ChooseMode(sizes, width, height);

            List<Rectangle> others = new List<Rectangle>();
            foreach (MonitorEntry e in ListMonitors())
                if (!string.Equals(e.DeviceName, gdi, StringComparison.OrdinalIgnoreCase)) others.Add(e.Bounds);
            Point pos = DisplayMath.PlaceRightOf(others);

            DEVMODE dm = NewDevMode();
            if (!EnumDisplaySettingsW(gdi, ENUM_CURRENT_SETTINGS, ref dm)) dm = NewDevMode();
            dm.dmFields = DM_POSITION;
            dm.dmPositionX = pos.X;
            dm.dmPositionY = pos.Y;
            if (mode != Size.Empty)
            {
                dm.dmFields |= DM_PELSWIDTH | DM_PELSHEIGHT;
                dm.dmPelsWidth = (uint)mode.Width;
                dm.dmPelsHeight = (uint)mode.Height;
            }
            int rc = ChangeDisplaySettingsExW(gdi, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
            if (rc == 0) rc = ChangeDisplaySettingsExW(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
            if (rc != 0) ChangeDisplaySettingsExW(gdi, ref dm, IntPtr.Zero, 0, IntPtr.Zero); // dynamic only, not saved
        }

        public sealed class MonitorEntry
        {
            public string DeviceName;
            public Rectangle Bounds;
            public bool Primary;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct MONITORINFOEX
        {
            public uint cbSize;
            public Native.RECT rcMonitor;
            public Native.RECT rcWork;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szDevice;
        }

        private delegate bool MonitorEnumProc(IntPtr monitor, IntPtr hdc, IntPtr rect, IntPtr data);
        [DllImport("user32.dll")] private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc cb, IntPtr data);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool GetMonitorInfoW(IntPtr monitor, ref MONITORINFOEX info);

        /// <summary>Monitors currently attached to the desktop (fresh, unlike the cached Screen.AllScreens).</summary>
        public static List<MonitorEntry> ListMonitors()
        {
            List<MonitorEntry> list = new List<MonitorEntry>();
            try
            {
                EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, delegate(IntPtr h, IntPtr dc, IntPtr r, IntPtr d)
                {
                    MONITORINFOEX mi = new MONITORINFOEX();
                    mi.cbSize = (uint)Marshal.SizeOf(typeof(MONITORINFOEX));
                    if (GetMonitorInfoW(h, ref mi))
                    {
                        MonitorEntry e = new MonitorEntry();
                        e.DeviceName = mi.szDevice;
                        e.Bounds = Rectangle.FromLTRB(mi.rcMonitor.Left, mi.rcMonitor.Top, mi.rcMonitor.Right, mi.rcMonitor.Bottom);
                        e.Primary = (mi.dwFlags & 1) != 0;
                        list.Add(e);
                    }
                    return true;
                }, IntPtr.Zero);
            }
            catch { }
            return list;
        }

        /// <summary>Desktop bounds of an attached monitor in physical pixels, Rectangle.Empty when it isn't attached.</summary>
        public static Rectangle Bounds(string gdiName)
        {
            foreach (MonitorEntry e in ListMonitors())
                if (string.Equals(e.DeviceName, gdiName, StringComparison.OrdinalIgnoreCase)) return e.Bounds;
            return Rectangle.Empty;
        }
    }
}
