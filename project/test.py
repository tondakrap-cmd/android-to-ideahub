"""Run the portable protocol/pairing/QR tests with a JDK (Windows, Linux or macOS)."""
import argparse, os, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument('--java-home', type=pathlib.Path, default=os.environ.get('JAVA_HOME'))
p.add_argument('--print-vector', action='store_true')
a = p.parse_args()
exe = '.exe' if os.name == 'nt' else ''
def tool(name):
    return str(a.java_home.resolve() / 'bin' / (name + exe)) if a.java_home else name
out = root / 'build' / 'tests'
out.mkdir(parents=True, exist_ok=True)
jar = root / 'libs' / 'zxing-core-3.5.3.jar'
portable = ['PairCode', 'Packet', 'TlsSupport', 'NetUtil', 'FrameQueue', 'AudioUtil', 'DiscoveryProtocol',
            'Spake2', 'Proto', 'DeviceRegistry', 'AuthLimiter', 'BoardStore', 'VideoGeometry', 'DiscoveryClient', 'PairingCredentials',
            'UpdateCheck']
sources = [root / 'src/cz/ideamirror/app' / (n + '.java') for n in portable]
sources += [root / 'tests/cz/ideamirror/app/CoreTest.java', root / 'tests/cz/ideamirror/app/ProtoTest.java',
            root / 'tests/cz/ideamirror/app/UpdateCheckTest.java']

# The in-app updater compares versions, so all three places must agree.
import re
manifest = re.search(r'android:versionName="([^"]+)"', (root / 'AndroidManifest.xml').read_text(encoding='utf-8')).group(1)
gradle = re.search(r"versionName '([^']+)'", (root / 'build.gradle').read_text(encoding='utf-8')).group(1)
ui = re.search(r'VERSION = "([^"]+)"', (root / 'src/cz/ideamirror/app/Ui.java').read_text(encoding='utf-8')).group(1)
if not (manifest == gradle == ui):
    sys.exit(f'FAIL: verze se liší: AndroidManifest {manifest}, build.gradle {gradle}, Ui.VERSION {ui}')
subprocess.run([tool('javac'), '--release', '8', '-nowarn', '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(out)] + [str(x) for x in sources], check=True)
cmd = [tool('java')] + (['-DprintVector=1'] if a.print_vector else []) + ['-cp', str(out) + os.pathsep + str(jar), 'cz.ideamirror.app.CoreTest']
sys.exit(subprocess.run(cmd).returncode)
