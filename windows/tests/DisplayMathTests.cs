using System;
using System.Collections.Generic;
using System.Drawing;

namespace TabuleLinkWin
{
    /// <summary>Virtual monitor placement, mode choice, touch mapping and driver identification.</summary>
    internal static class DisplayMathTests
    {
        private static void Check(bool ok, string msg) { WindowsTests.Check(ok, msg); }

        internal static void Run()
        {
            // placement: right of the rightmost monitor, top-aligned with it, never (0,0) next to other monitors
            Rectangle laptop = new Rectangle(0, 0, 2560, 1600);
            Check(DisplayMath.PlaceRightOf(new Rectangle[] { laptop }) == new Point(2560, 0), "place right of single laptop screen");
            Rectangle leftMonitor = new Rectangle(-1920, -200, 1920, 1080);
            Check(DisplayMath.PlaceRightOf(new Rectangle[] { leftMonitor, laptop }) == new Point(2560, 0), "place right of laptop when external is on the left");
            Rectangle rightMonitor = new Rectangle(2560, 300, 3840, 2160);
            Check(DisplayMath.PlaceRightOf(new Rectangle[] { laptop, rightMonitor }) == new Point(6400, 300), "place right of rightmost external monitor");
            Rectangle stacked = new Rectangle(0, -1080, 2560, 1080);
            Check(DisplayMath.PlaceRightOf(new Rectangle[] { laptop, stacked }) == new Point(2560, -1080), "tie on right edge: the higher monitor wins");
            Check(DisplayMath.PlaceRightOf(new Rectangle[0]) == Point.Empty, "no monitors → origin");

            // mode choice
            List<Size> modes = new List<Size> { new Size(800, 600), new Size(1280, 720), new Size(1920, 1080), new Size(2560, 1440), new Size(3840, 2160) };
            Check(DisplayMath.ChooseMode(modes, 1920, 1080) == new Size(1920, 1080), "exact 1920x1080 mode");
            List<Size> no1080 = new List<Size> { new Size(1024, 768), new Size(1280, 720), new Size(1600, 900), new Size(1680, 1050), new Size(3840, 2160) };
            Check(DisplayMath.ChooseMode(no1080, 1920, 1080) == new Size(1600, 900), "largest 16:9 mode that fits");
            List<Size> no169 = new List<Size> { new Size(1024, 768), new Size(1680, 1050), new Size(2560, 1600) };
            Check(DisplayMath.ChooseMode(no169, 1920, 1080) == new Size(1680, 1050), "largest fitting mode when no 16:9 mode fits");
            Check(DisplayMath.ChooseMode(new List<Size> { new Size(3840, 2160) }, 1920, 1080) == Size.Empty, "nothing fits → keep current");
            Check(DisplayMath.ChooseMode(new List<Size>(), 1920, 1080) == Size.Empty, "no modes → keep current");

            // touch on the virtual monitor (placed right of a 2560 px laptop screen)
            Rectangle vm = new Rectangle(2560, 0, 1920, 1080);
            Check(DisplayMath.TouchToDesktop(vm, 0, 0) == new Point(2560, 0), "touch top-left of virtual monitor");
            Check(DisplayMath.TouchToDesktop(vm, 65535, 65535) == new Point(4479, 1079), "touch bottom-right of virtual monitor");
            Check(DisplayMath.TouchToDesktop(vm, 32768, 32768) == new Point(2560 + 960, 540), "touch centre of virtual monitor");
            Check(DisplayMath.TouchToDesktop(vm, -5, 70000) == new Point(2560, 1079), "touch clamps out-of-range values");
            Rectangle vmLeft = new Rectangle(-1920, -1080, 1920, 1080);
            Check(DisplayMath.TouchToDesktop(vmLeft, 0, 65535) == new Point(-1920, -1), "touch on monitor with negative coordinates");
            Point p = DisplayMath.TouchToDesktop(vm, 16384, 49152);
            Check(p.X >= vm.Left && p.X < vm.Right && p.Y >= vm.Top && p.Y < vm.Bottom, "touch stays inside virtual monitor");

            // driver identification
            Check(DisplayMath.IsVddHardwareId(DisplayMath.SplitMultiSz("Root\\MttVDD\0\0")), "VDD hardware ID");
            Check(DisplayMath.IsVddHardwareId(new string[] { "root\\mttvdd" }), "VDD hardware ID case-insensitive");
            Check(!DisplayMath.IsVddHardwareId(DisplayMath.SplitMultiSz("PCI\\VEN_8086&DEV_46A6\0PCI\\VEN_8086\0\0")), "Intel GPU is not VDD");
            Check(!DisplayMath.IsVddHardwareId(new string[] { "Root\\Parsec\\VDA" }), "Parsec VDD is not VDD");
            Check(DisplayMath.SplitMultiSz("a\0b\0\0").Count == 2, "SplitMultiSz");
            Check(DisplayMath.AdapterPathMatchesInstance(@"\\?\ROOT#DISPLAY#0000#{5b45201d-f2f2-4f3b-85bb-30ff1f953599}", @"ROOT\DISPLAY\0000"), "adapter path matches instance");
            Check(!DisplayMath.AdapterPathMatchesInstance(@"\\?\ROOT#DISPLAY#00001#{5b45201d-f2f2-4f3b-85bb-30ff1f953599}", @"ROOT\DISPLAY\0000"), "adapter path of other instance");
            Check(!DisplayMath.AdapterPathMatchesInstance(@"\\?\PCI#VEN_8086&DEV_46A6#3&11583659&0&10#{5b45201d}", @"ROOT\DISPLAY\0000"), "GPU adapter path");
            Check(!DisplayMath.AdapterPathMatchesInstance(null, @"ROOT\DISPLAY\0000"), "null adapter path");
            Check(DisplayMath.LooksLikeVddMonitor(@"\\?\DISPLAY#MTT1337#1&28a6823a&0&UID256#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}"), "VDD monitor EDID");
            Check(!DisplayMath.LooksLikeVddMonitor(@"\\?\DISPLAY#BOE0A1C#4&1e3a7d5&0&UID8388688#{e6f07b5f}"), "laptop panel is not VDD");
        }
    }
}
