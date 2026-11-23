using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace TabuleLinkWin
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        private static void Main()
        {
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
                try { SetProcessDPIAware(); } catch { }
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
