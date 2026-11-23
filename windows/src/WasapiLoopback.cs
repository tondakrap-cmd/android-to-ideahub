using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace TabuleLinkWin
{
    /// <summary>
    /// Native Windows WASAPI Loopback system audio capture (zero external libraries).
    /// Captures default playback audio (YouTube, browser, presentations) as 16-bit stereo PCM LE.
    /// </summary>
    public sealed class WasapiLoopback : IDisposable
    {
        private const int ERender = 0;
        private const int EConsole = 0;
        private const int ClsCtxAll = 23;
        private const int ShareModeShared = 0;
        private const uint StreamFlagLoopback = 0x00020000u;
        private const uint BufferFlagSilent = 0x2u;

        private static readonly Guid ClsidMMDeviceEnumerator = new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
        private static readonly Guid IidIAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        private static readonly Guid IidIAudioCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        private static readonly Guid SubtypeIeeeFloat = new Guid("00000003-0000-0010-8000-00aa00389b71");
        private static readonly Guid SubtypePcm = new Guid("00000001-0000-0010-8000-00aa00389b71");

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
        }

        [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IAudioClient
        {
            [PreserveSig] int Initialize(int shareMode, uint streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, ref Guid audioSessionGuid);
            [PreserveSig] int GetBufferSize(out uint numBufferFrames);
            [PreserveSig] int GetStreamLatency(out long latency);
            [PreserveSig] int GetCurrentPadding(out uint numPaddingFrames);
            [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr ppClosestMatch);
            [PreserveSig] int GetMixFormat(out IntPtr ppDeviceFormat);
            [PreserveSig] int GetDevicePeriod(out long defaultDevicePeriod, out long minimumDevicePeriod);
            [PreserveSig] int Start();
            [PreserveSig] int Stop();
            [PreserveSig] int Reset();
            [PreserveSig] int SetEventHandle(IntPtr eventHandle);
            [PreserveSig] int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
        }

        [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IAudioCaptureClient
        {
            [PreserveSig] int GetBuffer(out IntPtr ppData, out uint numFramesToRead, out uint flags, out ulong devicePosition, out ulong qpcPosition);
            [PreserveSig] int ReleaseBuffer(uint numFramesRead);
            [PreserveSig] int GetNextPacketSize(out uint numFramesInNextPacket);
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct WaveFormatEx
        {
            public ushort FormatTag;
            public ushort Channels;
            public uint SamplesPerSec;
            public uint AvgBytesPerSec;
            public ushort BlockAlign;
            public ushort BitsPerSample;
            public ushort ExtraSize;
        }

        private volatile bool running;
        private Thread captureThread;
        public int SampleRate { get; private set; }
        public string FormatDescription { get; private set; }

        private IMMDeviceEnumerator enumerator;
        private IMMDevice device;
        private IAudioClient audioClient;
        private IAudioCaptureClient captureClient;
        private IntPtr mixFormatPtr = IntPtr.Zero;
        private int srcChannels;
        private int srcBits;
        private bool srcIsFloat;

        public void Open()
        {
            Type enumType = Type.GetTypeFromCLSID(ClsidMMDeviceEnumerator);
            enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(enumType);
            int hr = enumerator.GetDefaultAudioEndpoint(ERender, EConsole, out device);
            if (hr != 0 || device == null) throw new InvalidOperationException("Nenalezeno výchozí zvukové zařízení Windows (0x" + hr.ToString("X") + ").");

            Guid iidClient = IidIAudioClient;
            object clientObj;
            hr = device.Activate(ref iidClient, ClsCtxAll, IntPtr.Zero, out clientObj);
            if (hr != 0 || clientObj == null) throw new InvalidOperationException("Nepodařilo se otevřít WASAPI AudioClient (0x" + hr.ToString("X") + ").");
            audioClient = (IAudioClient)clientObj;

            hr = audioClient.GetMixFormat(out mixFormatPtr);
            if (hr != 0 || mixFormatPtr == IntPtr.Zero) throw new InvalidOperationException("Nepodařilo se zjistit formát zvuku Windows (0x" + hr.ToString("X") + ").");

            WaveFormatEx wf = (WaveFormatEx)Marshal.PtrToStructure(mixFormatPtr, typeof(WaveFormatEx));
            SampleRate = (int)wf.SamplesPerSec;
            srcChannels = wf.Channels;
            srcBits = wf.BitsPerSample;

            if (wf.FormatTag == 3)
            {
                srcIsFloat = true;
            }
            else if (wf.FormatTag == 1)
            {
                srcIsFloat = false;
            }
            else if (wf.FormatTag == 0xFFFE && wf.ExtraSize >= 22)
            {
                // WAVEFORMATEXTENSIBLE: SubFormat GUID starts at offset 24 (18 + 2 validBits + 4 channelMask)
                byte[] guidBytes = new byte[16];
                Marshal.Copy(new IntPtr(mixFormatPtr.ToInt64() + 24), guidBytes, 0, 16);
                Guid sub = new Guid(guidBytes);
                if (sub == SubtypeIeeeFloat) srcIsFloat = true;
                else if (sub == SubtypePcm) srcIsFloat = false;
                else throw new NotSupportedException("Nepodporovaný WASAPI audio subformát: " + sub);
            }
            else
            {
                throw new NotSupportedException("Nepodporovaný WASAPI formát zvuku (tag=" + wf.FormatTag + ").");
            }

            if (srcIsFloat && srcBits != 32) throw new NotSupportedException("Očekáván 32-bit float WASAPI mix formát.");
            if (!srcIsFloat && srcBits != 16) throw new NotSupportedException("Očekáván 16-bit PCM WASAPI mix formát.");

            Guid sessionGuid = Guid.Empty;
            hr = audioClient.Initialize(ShareModeShared, StreamFlagLoopback, 500000L, 0L, mixFormatPtr, ref sessionGuid);
            if (hr != 0) throw new InvalidOperationException("Inicializace WASAPI Loopback selhala (0x" + hr.ToString("X") + ").");

            Guid iidCap = IidIAudioCaptureClient;
            object capObj;
            hr = audioClient.GetService(ref iidCap, out capObj);
            if (hr != 0 || capObj == null) throw new InvalidOperationException("Nepodařilo se získat WASAPI CaptureClient (0x" + hr.ToString("X") + ").");
            captureClient = (IAudioCaptureClient)capObj;

            FormatDescription = "WASAPI Loopback " + SampleRate + " Hz · " + srcChannels + "ch " + (srcIsFloat ? "float32" : "pcm16");
        }

        public void Start(Action<byte[], int> onPcm16Stereo, Func<int> getVolumePercent, Action<Exception> onError)
        {
            int hr = audioClient.Start();
            if (hr != 0) throw new InvalidOperationException("Spuštění WASAPI Loopback selhalo (0x" + hr.ToString("X") + ").");
            running = true;
            captureThread = new Thread(delegate()
            {
                RunLoop(onPcm16Stereo, getVolumePercent, onError);
            });
            captureThread.IsBackground = true;
            captureThread.Priority = ThreadPriority.AboveNormal;
            captureThread.Name = "tabule-wasapi";
            captureThread.Start();
        }

        private void RunLoop(Action<byte[], int> onPcm16Stereo, Func<int> getVolumePercent, Action<Exception> onError)
        {
            int silenceFrames = Math.Max(480, SampleRate / 50); // 20 ms
            byte[] silenceBuf = new byte[silenceFrames * 4];
            byte[] convBuf = new byte[silenceFrames * 8];
            byte[] rawScratch = new byte[silenceFrames * srcChannels * (srcBits / 8) * 2];

            try
            {
                while (running)
                {
                    uint packetFrames;
                    int hr = captureClient.GetNextPacketSize(out packetFrames);
                    if (hr != 0) break;

                    if (packetFrames == 0)
                    {
                        Thread.Sleep(15);
                        if (!running) break;
                        // Feed low-overhead silence so ffmpeg AAC encoder stays warm with zero start lag
                        onPcm16Stereo(silenceBuf, silenceBuf.Length);
                        continue;
                    }

                    while (running && packetFrames > 0)
                    {
                        IntPtr dataPtr;
                        uint numFrames, flags;
                        ulong devPos, qpcPos;
                        hr = captureClient.GetBuffer(out dataPtr, out numFrames, out flags, out devPos, out qpcPos);
                        if (hr != 0) break;

                        try
                        {
                            int outBytes = (int)numFrames * 4; // 16-bit stereo = 4 B/frame
                            if (convBuf.Length < outBytes) convBuf = new byte[outBytes];

                            if ((flags & BufferFlagSilent) != 0 || dataPtr == IntPtr.Zero)
                            {
                                Array.Clear(convBuf, 0, outBytes);
                            }
                            else
                            {
                                int rawBytes = (int)numFrames * srcChannels * (srcBits / 8);
                                if (rawScratch.Length < rawBytes) rawScratch = new byte[rawBytes];
                                Marshal.Copy(dataPtr, rawScratch, 0, rawBytes);
                                ConvertToPcm16Stereo(rawScratch, (int)numFrames, convBuf);
                                int vol = getVolumePercent();
                                AudioUtil.ApplyGainPcm16Le(convBuf, outBytes, vol);
                            }
                            onPcm16Stereo(convBuf, outBytes);
                        }
                        finally
                        {
                            captureClient.ReleaseBuffer(numFrames);
                        }

                        hr = captureClient.GetNextPacketSize(out packetFrames);
                        if (hr != 0) break;
                    }
                }
            }
            catch (Exception ex)
            {
                if (running && onError != null) onError(ex);
            }
        }

        private void ConvertToPcm16Stereo(byte[] src, int frames, byte[] dst)
        {
            int ch = Math.Max(1, srcChannels);
            if (srcIsFloat)
            {
                int frameStride = ch * 4;
                for (int f = 0; f < frames; f++)
                {
                    int srcOff = f * frameStride;
                    float l = BitConverter.ToSingle(src, srcOff);
                    float r = ch >= 2 ? BitConverter.ToSingle(src, srcOff + 4) : l;
                    short sl = FloatToInt16(l);
                    short sr = FloatToInt16(r);
                    int dstOff = f * 4;
                    dst[dstOff] = (byte)(sl & 0xFF);
                    dst[dstOff + 1] = (byte)((sl >> 8) & 0xFF);
                    dst[dstOff + 2] = (byte)(sr & 0xFF);
                    dst[dstOff + 3] = (byte)((sr >> 8) & 0xFF);
                }
            }
            else
            {
                int frameStride = ch * 2;
                for (int f = 0; f < frames; f++)
                {
                    int srcOff = f * frameStride;
                    int dstOff = f * 4;
                    dst[dstOff] = src[srcOff];
                    dst[dstOff + 1] = src[srcOff + 1];
                    if (ch >= 2)
                    {
                        dst[dstOff + 2] = src[srcOff + 2];
                        dst[dstOff + 3] = src[srcOff + 3];
                    }
                    else
                    {
                        dst[dstOff + 2] = src[srcOff];
                        dst[dstOff + 3] = src[srcOff + 1];
                    }
                }
            }
        }

        private static short FloatToInt16(float sample)
        {
            if (sample > 1.0f) sample = 1.0f;
            else if (sample < -1.0f) sample = -1.0f;
            int v = (int)Math.Round(sample * 32767.0f);
            if (v > 32767) return 32767;
            if (v < -32768) return -32768;
            return (short)v;
        }

        public void Dispose()
        {
            running = false;
            if (captureThread != null)
            {
                try { captureThread.Join(600); } catch { }
                captureThread = null;
            }
            if (audioClient != null)
            {
                try { audioClient.Stop(); } catch { }
            }
            if (mixFormatPtr != IntPtr.Zero)
            {
                Marshal.FreeCoTaskMem(mixFormatPtr);
                mixFormatPtr = IntPtr.Zero;
            }
            ReleaseCom(ref captureClient);
            ReleaseCom(ref audioClient);
            ReleaseCom(ref device);
            ReleaseCom(ref enumerator);
        }

        private static void ReleaseCom<T>(ref T obj) where T : class
        {
            if (obj != null)
            {
                try { Marshal.ReleaseComObject(obj); } catch { }
                obj = null;
            }
        }
    }
}
