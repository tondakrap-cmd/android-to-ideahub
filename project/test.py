"""Run the portable protocol/pairing/QR tests with a JDK."""
import argparse, os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True,type=pathlib.Path);a=p.parse_args()
jdk=a.java_home.resolve();out=root/'build'/'tests';out.mkdir(parents=True,exist_ok=True)
jar=root/'libs'/'zxing-core-3.5.3.jar'
sources=[root/'src/cz/ideamirror/app/PairCode.java',root/'src/cz/ideamirror/app/Packet.java',root/'src/cz/ideamirror/app/TlsSupport.java',root/'src/cz/ideamirror/app/NetUtil.java',root/'src/cz/ideamirror/app/FrameQueue.java',root/'src/cz/ideamirror/app/AudioUtil.java',root/'tests/cz/ideamirror/app/CoreTest.java']
subprocess.run([str(jdk/'bin/javac.exe'),'--release','8','-encoding','UTF-8','-cp',str(jar),'-d',str(out)]+[str(x) for x in sources],check=True)
subprocess.run([str(jdk/'bin/java.exe'),'-cp',str(out)+os.pathsep+str(jar),'cz.ideamirror.app.CoreTest'],check=True)
