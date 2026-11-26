using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    internal static class Program
    {
        public static string DpiMode = "-";
        public static string UpdatedTo;         // --updated=0.9.0 (started by the update helper after replacing the EXE)
        public static string UpdateFailedPath;  // --update-failed=<verified new EXE> (the EXE could not be replaced)

        [STAThread]
        private static void Main(string[] args)
        {
            foreach (string a in args ?? new string[0])
            {
                if (a.StartsWith("--updated=", StringComparison.Ordinal) && UpdateCheck.ParseVersion(a.Substring(10)) != null) UpdatedTo = a.Substring(10);
                else if (a.StartsWith("--update-failed=", StringComparison.Ordinal) && a.Length > 16) UpdateFailedPath = a.Substring(16);
            }
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e)
            {
                ShowFatal(e.Exception);
            };
            AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e)
            {
                ShowFatal(e.ExceptionObject as Exception);
            };
            try
            {
                DpiMode = Native.EnableDpiAwareness();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
            }
            catch (Exception ex)
            {
                ShowFatal(ex);
            }
        }

        private static void ShowFatal(Exception ex)
        {
            string msg = ex == null ? "Neznámá chyba aplikace." : (ex.GetType().Name + ": " + ex.Message + "\r\n\r\n" + ex.StackTrace);
            try
            {
                MessageBox.Show(msg, "Tabule Link — chyba spuštění", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }
    }
}
